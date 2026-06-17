package me.bechberger.testorder.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.objectweb.asm.*;

import me.bechberger.testorder.agent.runtime.AgentLogger;
import me.bechberger.testorder.agent.runtime.ClassIdMap;

/**
 * ASM-based class transformer — significantly faster than the Javassist-based
 * {@link ClassTransformer} because ASM uses a streaming visitor model rather
 * than building a full intermediate object model.
 * <p>
 * Produces identical instrumentation: records class/member usage at method
 * entry points, and optionally instruments field accesses.
 */
public class AsmClassTransformer implements ClassFileTransformer {

	enum FieldTrackingMode {
		NONE, STATIC_ONLY
	}

	private static final String USAGE_STORE = "me/bechberger/testorder/agent/runtime/UsageStore";

	private final IntelligentClassFilter filter;
	private final Agent.InstrumentationMode mode;
	private final FieldTrackingMode fieldTrackingMode;
	private final ClassIdMap classIdMap = ClassIdMap.getInstance();
	private final AtomicBoolean cachesReleased = new AtomicBoolean();
	/**
	 * Non-null only in selective learn mode; null means "instrument everything".
	 */
	private final Set<String> uncertainClassesDots;

	public AsmClassTransformer(Agent options) {
		this(options, null);
	}

	public AsmClassTransformer(Agent options, Set<String> uncertainClasses) {
		this.uncertainClassesDots = uncertainClasses;
		this.mode = options.getMode();
		this.fieldTrackingMode = fieldTrackingModeFor(mode);

		IntelligentClassFilter.Builder filterBuilder = new IntelligentClassFilter.Builder()
				.strategy(options.getFilterStrategy()).skipTestClasses(options.isSkipTestClasses())
				.useHeuristics(options.isUseHeuristics());

		for (String pkg : options.getIncludePackages()) {
			filterBuilder.explicitInclude(pkg);
		}
		for (String pkg : options.getExcludePackages()) {
			filterBuilder.explicitExclude(pkg);
		}

		if (options.isAutoDetectPackages() && options.getIncludePackages().isEmpty()) {
			ProjectStructureAnalyzer analyzer = new ProjectStructureAnalyzer(options.getProjectRoot());
			if (!analyzer.getUserPackages().isEmpty()) {
				AgentLogger.log("[AsmClassTransformer] Auto-detected user packages: " + analyzer.getUserPackages());
				for (String pkg : analyzer.getUserPackages()) {
					filterBuilder.explicitInclude(pkg);
				}
				for (String pkg : analyzer.getDependencyPackages()) {
					filterBuilder.explicitExclude(pkg);
				}
				// Include test packages so that test utilities (helpers, base classes,
				// custom assertions) are instrumented as dependencies. The skipTestClasses
				// heuristic handles skipping actual test classes (Test*, *Test, *Tests,
				// *TestCase) to avoid circular self-dependencies.
				for (String pkg : analyzer.getTestPackages()) {
					filterBuilder.explicitInclude(pkg);
				}
			}
		}

		this.filter = filterBuilder.build();
	}

	static FieldTrackingMode fieldTrackingModeFor(Agent.InstrumentationMode mode) {
		return switch (mode) {
			// MEMBER uses STATIC_ONLY too: instance field tracking adds massive
			// overhead (every GETFIELD/PUTFIELD instrumented) for marginal value since
			// the dependency is already captured via method-entry member recording.
			// The member-level precision comes from recording WHICH methods are called
			// (via recordMemberUsageIdFast at method entry), not from tracking individual
			// instance field reads.
			case CLASS, METHOD, MEMBER -> FieldTrackingMode.STATIC_ONLY;
		};
	}

	public void releaseTransformationCaches() {
		if (!cachesReleased.compareAndSet(false, true))
			return;
		// Don't clear the filter cache — it's bounded at 50K entries and classes
		// loaded lazily during tests would otherwise pay the full filter evaluation
		// cost on every load (10 String.contains checks + regex). The memory cost
		// (~2-4 MB for 50K entries) is acceptable for the perf benefit.
		AgentLogger.log("[AsmClassTransformer] Released transformation caches (filter cache retained)");
	}

	@Override
	public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (className == null) {
			return null;
		}
		if (classBeingRedefined != null) {
			return null;
		}
		if (!shouldInstrument(className)) {
			return null;
		}
		try {
			return doTransform(className, classfileBuffer);
		} catch (Throwable e) {
			if (e instanceof Error)
				throw (Error) e;
			// Catch Exception (and Throwable sub-types that are not Errors) to handle
			// unexpected failures during class transformation in forked JVMs where
			// classpath or agent state is partially initialized.
			if (AgentLogger.isVerbose()) {
				AgentLogger.log("[transform] FAIL: " + className + " -> " + e);
			}
			return null;
		}
	}

	boolean shouldInstrument(String className) {
		if (className.equals("module-info") || className.endsWith("/module-info")) {
			return false;
		}
		if (!filter.shouldInstrument(className)) {
			return false;
		}
		if (uncertainClassesDots != null) {
			return uncertainClassesDots.contains(className.replace('/', '.'));
		}
		return true;
	}

	private byte[] doTransform(String className, byte[] classfileBuffer) {
		// Skip classes already instrumented offline
		if (OfflineInstrumentor.hasMarkerAttribute(classfileBuffer)) {
			return null;
		}
		ClassReader cr = new ClassReader(classfileBuffer);

		String fqcn = className.replace('/', '.');
		int mainClassId = classIdMap.getOrRegisterClass(fqcn);
		if (mainClassId < 0) {
			return null;
		}

		int[] ids = new int[]{mainClassId};

		boolean recordMembers = mode == Agent.InstrumentationMode.MEMBER;

		// Single pass: transform (field collection happens inline in the visitor)
		// Use 0 (no COMPUTE flags) — we manually adjust maxStack in the method visitor
		// which is much faster than ASM's COMPUTE_MAXS data flow analysis.
		ClassWriter cw = new ClassWriter(cr, 0);
		InstrumentingClassVisitor cv = new InstrumentingClassVisitor(cw, className, fqcn, ids, recordMembers);
		cr.accept(cv, 0);

		byte[] result = cw.toByteArray();
		if (AgentLogger.isVerbose()) {
			AgentLogger.log("[transform] OK: " + className + " (" + classfileBuffer.length + " -> " + result.length
					+ " bytes)");
		}
		return result;
	}

	/**
	 * ClassVisitor that instruments all methods/constructors with usage recording.
	 */
	private class InstrumentingClassVisitor extends ClassVisitor {
		private final String className; // internal name (e.g. com/foo/Bar)
		private final String fqcn;
		private int[] classIds;
		private final boolean recordMembers;

		InstrumentingClassVisitor(ClassVisitor cv, String className, String fqcn, int[] classIds,
				boolean recordMembers) {
			super(Opcodes.ASM9, cv);
			this.className = className;
			this.fqcn = fqcn;
			this.classIds = classIds;
			this.recordMembers = recordMembers;
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
				String[] exceptions) {
			MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
			if (mv == null) {
				return null;
			}
			// Skip abstract, native, synthetic, and bridge methods
			if ((access
					& (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) {
				return mv;
			}

			int memberId = recordMembers ? classIdMap.getOrRegisterMember(fqcn + "#" + name) : -1;
			boolean instrumentFields = fieldTrackingMode != FieldTrackingMode.NONE;

			return new InstrumentingMethodVisitor(mv, classIds, memberId, instrumentFields, fqcn, className, access);
		}
	}

	/**
	 * MethodVisitor that inserts recording calls at method entry (visitCode) and
	 * optionally before field accesses. Uses a plain MethodVisitor instead of
	 * AdviceAdapter — our injected code (push int + invokestatic) doesn't reference
	 * {@code this}, so it's safe even before super() in constructors. Manually
	 * tracks maxStack delta to avoid COMPUTE_MAXS.
	 */
	private class InstrumentingMethodVisitor extends MethodVisitor {
		private final int[] classIds;
		private final int memberId;
		private final boolean instrumentFields;
		private final String selfFqcn; // dot-form (com.example.Foo)
		private final String selfClassName; // slash-form (com/example/Foo) — for self-check in visitFieldInsn
		private final boolean isPrivate;
		private int extraStack = 0;

		InstrumentingMethodVisitor(MethodVisitor mv, int[] classIds, int memberId, boolean instrumentFields,
				String selfFqcn, String selfClassName, int access) {
			super(Opcodes.ASM9, mv);
			this.classIds = classIds;
			this.memberId = memberId;
			this.instrumentFields = instrumentFields;
			this.selfFqcn = selfFqcn;
			this.selfClassName = selfClassName;
			this.isPrivate = (access & Opcodes.ACC_PRIVATE) != 0;
		}

		@Override
		public void visitCode() {
			super.visitCode();
			if (isPrivate) {
				// Private methods can only be reached through non-private entry points
				// in the same class, which already record the class ID. Only emit
				// member-level recording if needed.
				if (memberId >= 0) {
					emitPushInt(memberId);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, USAGE_STORE, "recordMemberUsageIdFast", "(I)V", false);
					extraStack = Math.max(extraStack, 1);
				}
				return;
			}
			if (memberId >= 0 && classIds.length == 1 && classIds[0] >= 0) {
				// METHOD/MEMBER: only record memberId; classId derived at flush.
				emitPushInt(memberId);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, USAGE_STORE, "recordMemberUsageIdFast", "(I)V", false);
				extraStack = Math.max(extraStack, 1);
			} else {
				// CLASS mode: no method tracker, use recordClassOnly for minimal hot path
				String classMethod = (mode == Agent.InstrumentationMode.CLASS)
						? "recordClassOnly"
						: "recordUsageIdFast";
				for (int classId : classIds) {
					if (classId >= 0) {
						emitPushInt(classId);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, USAGE_STORE, classMethod, "(I)V", false);
					}
				}
				extraStack = Math.max(extraStack, 1);
			}
		}

		@Override
		public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
			if (instrumentFields) {
				insertFieldAccessRecording(opcode, owner, name);
			}
			super.visitFieldInsn(opcode, owner, name, descriptor);
		}

		@Override
		public void visitMaxs(int maxStack, int maxLocals) {
			super.visitMaxs(maxStack + extraStack, maxLocals);
		}

		private void emitPushInt(int value) {
			if (value >= -1 && value <= 5) {
				mv.visitInsn(Opcodes.ICONST_0 + value);
			} else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
				mv.visitIntInsn(Opcodes.BIPUSH, value);
			} else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
				mv.visitIntInsn(Opcodes.SIPUSH, value);
			} else {
				mv.visitLdcInsn(value);
			}
		}

		private void insertFieldAccessRecording(int opcode, String owner, String fieldName) {
			// Only record static field accesses; instance field accesses
			// (GETFIELD/PUTFIELD)
			// are already covered by method-entry recording of the accessor method.
			if (opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD) {
				return;
			}
			if (owner.equals(selfClassName)) {
				return;
			}
			// Check filter before converting to dot-form — avoids String alloc for
			// non-instrumented owners (the common case for third-party static fields).
			if (!filter.shouldInstrument(owner)) {
				return;
			}

			String ownerDot = owner.replace('/', '.');
			boolean recordMemberAccess = mode == Agent.InstrumentationMode.MEMBER;
			int classId = classIdMap.getOrRegisterClass(ownerDot);
			int memberFieldId = recordMemberAccess ? classIdMap.getOrRegisterMember(ownerDot + "#" + fieldName) : -1;

			if (classId >= 0 && memberFieldId >= 0) {
				emitPushInt(memberFieldId);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, USAGE_STORE, "recordMemberUsageIdFast", "(I)V", false);
				extraStack = Math.max(extraStack, 1);
			} else if (classId >= 0) {
				emitPushInt(classId);
				String m = (mode == Agent.InstrumentationMode.CLASS) ? "recordClassOnly" : "recordUsageIdFast";
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, USAGE_STORE, m, "(I)V", false);
				extraStack = Math.max(extraStack, 1);
			} else if (memberFieldId >= 0) {
				emitPushInt(memberFieldId);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, USAGE_STORE, "recordMemberUsageIdFast", "(I)V", false);
				extraStack = Math.max(extraStack, 1);
			}
		}
	}
}
