package me.bechberger.testorder.agent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javassist.*;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.Bytecode;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.ConstPool;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.Opcode;

import me.bechberger.testorder.agent.runtime.AgentLogger;
import me.bechberger.testorder.agent.runtime.ClassIdMap;

/**
 * Instruments classes to record usage via static UsageStore fast-path calls.
 * <p>
 * Supports configurable intelligent filtering:
 * <ul>
 * <li><b>WHITELIST</b>: Only instrument explicitly listed packages</li>
 * <li><b>BLACKLIST</b>: Instrument all except excluded packages</li>
 * <li><b>SMART</b>: Combination of whitelist (if specified) + heuristics
 * (default)</li>
 * <li><b>WHITELIST_SMART</b>: Strict whitelist with heuristics (no fallback to
 * all)</li>
 * </ul>
 *
 * Supports multiple instrumentation modes: METHOD_ENTRY, FULL, FULL_METHOD,
 * FULL_MEMBER.
 */
public class ClassTransformer implements ClassFileTransformer {

	enum FieldTrackingMode {
		NONE, STATIC_ONLY, ALL
	}

	private final IntelligentClassFilter filter;
	private final Agent.InstrumentationMode mode;
	private final FieldTrackingMode fieldTrackingMode;
	private final ClassIdMap classIdMap = ClassIdMap.getInstance();
	private volatile ConcurrentHashMap<ClassLoader, ClassPool> loaderPools = new ConcurrentHashMap<>();

	/**
	 * Maximum entries per string cache to prevent unbounded growth in large builds.
	 */
	private static final int MAX_CACHE_ENTRIES = 10_000;

	/**
	 * Whether transformation caches have been released. Once tests start running,
	 * class loading is essentially done and these caches are dead weight.
	 */
	private final AtomicBoolean cachesReleased = new AtomicBoolean();

	public ClassTransformer(Agent options) {
		this.mode = options.getMode();
		this.fieldTrackingMode = fieldTrackingModeFor(mode);
		// Build intelligent filter from options
		IntelligentClassFilter.Builder filterBuilder = new IntelligentClassFilter.Builder()
				.strategy(options.getFilterStrategy()).skipTestClasses(options.isSkipTestClasses())
				.useHeuristics(options.isUseHeuristics());

		// Add explicit includes (convert to slash-separated class names)
		for (String pkg : options.getIncludePackages()) {
			filterBuilder.explicitInclude(pkg);
		}

		// Add explicit excludes
		for (String pkg : options.getExcludePackages()) {
			filterBuilder.explicitExclude(pkg);
		}

		// Auto-detect packages from project structure if enabled and no explicit
		// includes
		if (options.isAutoDetectPackages() && options.getIncludePackages().isEmpty()) {
			ProjectStructureAnalyzer analyzer = new ProjectStructureAnalyzer(options.getProjectRoot());

			// Use analysis result if we found user packages
			if (!analyzer.getUserPackages().isEmpty()) {
				AgentLogger.log("[ClassTransformer] Auto-detected user packages: " + analyzer.getUserPackages());

				// Add detected user packages as includes
				for (String pkg : analyzer.getUserPackages()) {
					filterBuilder.explicitInclude(pkg);
				}

				// Add detected dependency packages as excludes
				for (String pkg : analyzer.getDependencyPackages()) {
					filterBuilder.explicitExclude(pkg);
				}

				// Add detected test packages as excludes
				for (String pkg : analyzer.getTestPackages()) {
					filterBuilder.explicitExclude(pkg);
				}
			}
		}

		this.filter = filterBuilder.build();
	}

	static FieldTrackingMode fieldTrackingModeFor(Agent.InstrumentationMode mode) {
		return switch (mode) {
			case METHOD_ENTRY -> FieldTrackingMode.NONE;
			case FULL, FULL_METHOD -> FieldTrackingMode.STATIC_ONLY;
			case FULL_MEMBER -> FieldTrackingMode.ALL;
		};
	}

	private ClassPool classPoolFor(ClassLoader loader) {
		if (loader == null) {
			ClassPool cp = new ClassPool(null);
			cp.appendSystemPath();
			return cp;
		}
		ConcurrentHashMap<ClassLoader, ClassPool> pools = loaderPools;
		if (pools == null) {
			// Caches released; create a one-off pool (rare: late class loading)
			ClassPool cp = new ClassPool(null);
			cp.appendSystemPath();
			cp.appendClassPath(new LoaderClassPath(loader));
			return cp;
		}
		ClassPool existing = pools.get(loader);
		if (existing != null) {
			return existing;
		}
		ClassPool cp = new ClassPool(null);
		cp.appendSystemPath();
		cp.appendClassPath(new LoaderClassPath(loader));
		ClassPool prev = pools.putIfAbsent(loader, cp);
		return prev != null ? prev : cp;
	}

	/**
	 * Release transformation-phase caches to reclaim memory once tests start
	 * running. After this call, late class transformations still work but without
	 * caching (rare case).
	 */
	public void releaseTransformationCaches() {
		if (!cachesReleased.compareAndSet(false, true))
			return;
		loaderPools = null;
		slashToDotCache = null;
		filter.clearCache();
		AgentLogger.log("[ClassTransformer] Released transformation caches to reclaim memory");
	}

	@Override
	public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (className == null) {
			return classfileBuffer;
		}
		// Skip re-instrumentation on class redefinition (hot-swap, JRebel, etc.)
		if (classBeingRedefined != null) {
			return classfileBuffer;
		}
		if (!shouldInstrument(className)) {
			return classfileBuffer;
		}
		try {
			ClassPool cp = classPoolFor(loader);
			synchronized (cp) {
				CtClass cc = null;
				try {
					cc = cp.makeClass(new ByteArrayInputStream(classfileBuffer));
					if (cc.isFrozen()) {
						return classfileBuffer;
					}
					// skip classes without a SourceFile attribute (generated at runtime)
					if (cc.getClassFile().getAttribute("SourceFile") == null) {
						return classfileBuffer;
					}
					instrument(className, cc);
					byte[] result = cc.toBytecode();
					if (AgentLogger.isVerbose()) {
						AgentLogger.log("[transform] OK: " + className + " (" + classfileBuffer.length + " -> "
								+ result.length + " bytes)");
					}
					return result;
				} finally {
					if (cc != null) {
						cc.detach(); // release from the scoped pool to avoid unbounded memory growth
					}
				}
			}
		} catch (CannotCompileException | BadBytecode | IOException | RuntimeException e) {
			if (AgentLogger.isVerbose()) {
				AgentLogger.log("[transform] FAIL: " + className + " -> " + e);
			}
			return classfileBuffer;
		}
	}

	boolean shouldInstrument(String className) {
		// module descriptors are not instrumentable classes
		if (className.equals("module-info") || className.endsWith("/module-info")) {
			return false;
		}
		// Use intelligent filter with caching
		return filter.shouldInstrument(className);
	}

	private volatile ConcurrentHashMap<String, String> slashToDotCache = new ConcurrentHashMap<>();

	private void instrument(String className, CtClass cc) throws BadBytecode {
		ConcurrentHashMap<String, String> dotCache = slashToDotCache;
		String fqcn = dotCache != null ? dotCache.get(className) : null;
		if (fqcn == null) {
			fqcn = className.replace('/', '.');
			if (dotCache != null && dotCache.size() < MAX_CACHE_ENTRIES) {
				String prev = dotCache.putIfAbsent(className, fqcn);
				if (prev != null)
					fqcn = prev;
			}
		}
		int mainClassId = classIdMap.getOrRegisterClass(fqcn);
		if (mainClassId < 0) {
			return;
		}

		// Collect class IDs: main class + interfaces + interface-typed fields
		List<Integer> classIds = new ArrayList<>();
		classIds.add(mainClassId);

		// Also record: (a) interfaces this class directly implements, and (b)
		// interface-typed declared fields — when their names match includePackages.
		try {
			CtClass[] interfaces = cc.getInterfaces();
			for (CtClass iface : interfaces) {
				String ifaceSlash = iface.getName().replace('.', '/');
				if (!ifaceSlash.equals(className) && shouldInstrument(ifaceSlash)) {
					int id = classIdMap.getOrRegisterClass(iface.getName());
					if (id >= 0) {
						classIds.add(id);
					}
				}
			}
		} catch (NotFoundException ignored) {
		}
		try {
			CtField[] fields = cc.getDeclaredFields();
			for (CtField field : fields) {
				try {
					CtClass ft = field.getType();
					if (ft.isInterface()) {
						String ftSlash = ft.getName().replace('.', '/');
						if (!ftSlash.equals(className) && shouldInstrument(ftSlash)) {
							int id = classIdMap.getOrRegisterClass(ft.getName());
							if (id >= 0) {
								classIds.add(id);
							}
						}
					}
				} catch (NotFoundException ignored) {
				}
			}
		} catch (RuntimeException ignored) {
		}

		int[] ids = classIds.stream().mapToInt(Integer::intValue).toArray();
		instrumentMethodEntry(cc, fqcn, ids, mode == Agent.InstrumentationMode.FULL_MEMBER);
	}

	private void instrumentMethodEntry(CtClass cc, String selfFqcn, int[] classIds, boolean recordMembers)
			throws BadBytecode {
		boolean includeFieldAccess = fieldTrackingMode != FieldTrackingMode.NONE;
		ConstPool cp = cc.getClassFile().getConstPool();

		// instrument all declared methods
		for (CtMethod method : cc.getDeclaredMethods()) {
			if (Modifier.isAbstract(method.getModifiers()) || Modifier.isNative(method.getModifiers())) {
				continue;
			}
			try {
				int memberId = recordMembers ? classIdMap.getOrRegisterMember(selfFqcn + "#" + method.getName()) : -1;
				insertEntryBytecode(method.getMethodInfo(), cp, classIds, memberId, false);
				if (includeFieldAccess) {
					instrumentFieldAccesses(method.getMethodInfo(), cp, selfFqcn, recordMembers);
				}
			} catch (BadBytecode e) {
				// skip methods that can't be instrumented (e.g., synthetic bridge methods)
			}
		}
		// instrument constructors
		for (CtConstructor ctor : cc.getDeclaredConstructors()) {
			if (Modifier.isAbstract(ctor.getModifiers())) {
				continue;
			}
			try {
				int memberId = recordMembers ? classIdMap.getOrRegisterMember(selfFqcn + "#<init>") : -1;
				insertEntryBytecode(ctor.getMethodInfo(), cp, classIds, memberId, true);
				if (includeFieldAccess) {
					instrumentFieldAccesses(ctor.getMethodInfo(), cp, selfFqcn, recordMembers);
				}
			} catch (BadBytecode e) {
				// skip
			}
		}
		// also instrument static initializer if present
		CtConstructor clinit = cc.getClassInitializer();
		if (clinit != null) {
			try {
				int memberId = recordMembers ? classIdMap.getOrRegisterMember(selfFqcn + "#<clinit>") : -1;
				insertEntryBytecode(clinit.getMethodInfo(), cp, classIds, memberId, false);
				if (includeFieldAccess) {
					instrumentFieldAccesses(clinit.getMethodInfo(), cp, selfFqcn, recordMembers);
				}
			} catch (BadBytecode e) {
				// skip
			}
		}
	}

	/**
	 * Inserts recording bytecode at the entry point of a method/constructor. Uses
	 * raw bytecode emission instead of javassist's source compiler, avoiding the
	 * overhead of TypeChecker/CodeGen for each method.
	 */
	private void insertEntryBytecode(MethodInfo mi, ConstPool cp, int[] classIds, int memberId, boolean isConstructor)
			throws BadBytecode {
		CodeAttribute ca = mi.getCodeAttribute();
		if (ca == null)
			return;
		Bytecode bc = new Bytecode(cp, 1, 0);
		for (int classId : classIds) {
			if (classId >= 0) {
				bc.addIconst(classId);
				bc.addInvokestatic("me.bechberger.testorder.agent.runtime.UsageStore", "recordUsageIdFast", "(I)V");
			}
		}
		if (memberId >= 0) {
			bc.addIconst(memberId);
			bc.addInvokestatic("me.bechberger.testorder.agent.runtime.UsageStore", "recordMemberUsageIdFast", "(I)V");
		}
		byte[] bytecode = bc.get();
		if (bytecode.length == 0)
			return;

		CodeIterator ci = ca.iterator();
		if (isConstructor) {
			// For constructors, insert after super/this call
			int pos = skipSuperConstructorCall(ci);
			ci.insert(pos, bytecode);
		} else {
			ci.insert(bytecode);
		}
		// Our instrumentation uses at most 1 stack slot (for the int argument)
		ca.setMaxStack(Math.max(ca.getMaxStack(), 1));
	}

	/**
	 * Finds the bytecode position right after the super()/this() call in a
	 * constructor.
	 */
	private int skipSuperConstructorCall(CodeIterator ci) throws BadBytecode {
		// Walk bytecode looking for invokespecial on <init>
		// Track the 'this' reference depth to handle nested new+invokespecial
		int nested = 0;
		while (ci.hasNext()) {
			int pos = ci.next();
			int op = ci.byteAt(pos);
			if (op == Opcode.NEW) {
				nested++;
			} else if (op == Opcode.INVOKESPECIAL) {
				if (nested > 0) {
					nested--;
				} else {
					// This is the super/this call; return position after it
					return ci.hasNext() ? ci.next() : pos + 3;
				}
			}
		}
		// Fallback: insert at beginning (shouldn't happen for valid constructors)
		ci.begin();
		return 0;
	}

	/**
	 * Instruments field accesses in a method by scanning bytecode directly for
	 * getfield/putfield/getstatic/putstatic instructions and inserting recording
	 * calls before them. This avoids the javassist compiler overhead of ExprEditor.
	 */
	private void instrumentFieldAccesses(MethodInfo mi, ConstPool cp, String selfFqcn, boolean recordMembers)
			throws BadBytecode {
		CodeAttribute ca = mi.getCodeAttribute();
		if (ca == null)
			return;
		CodeIterator ci = ca.iterator();

		// First pass: collect field access positions that need instrumentation
		record FieldAccessEntry(int pos, int classId, int memberId) {
		}
		List<FieldAccessEntry> entries = new ArrayList<>();
		Set<String> seenClasses = new HashSet<>();
		Set<String> seenMembers = new HashSet<>();

		while (ci.hasNext()) {
			int pos = ci.next();
			int op = ci.byteAt(pos);
			if (op != Opcode.GETFIELD && op != Opcode.PUTFIELD && op != Opcode.GETSTATIC && op != Opcode.PUTSTATIC) {
				continue;
			}
			if (fieldTrackingMode == FieldTrackingMode.STATIC_ONLY
					&& (op == Opcode.GETFIELD || op == Opcode.PUTFIELD)) {
				continue;
			}
			int fieldRefIndex = ci.u16bitAt(pos + 1);
			String declaringClass = cp.getFieldrefClassName(fieldRefIndex);
			String fieldName = cp.getFieldrefName(fieldRefIndex);
			// skip self — already recorded by method-entry instrumentation
			if (declaringClass.equals(selfFqcn)) {
				continue;
			}
			// skip JDK/framework and generated classes
			String internalName = declaringClass.replace('.', '/');
			if (!filter.shouldInstrument(internalName)) {
				continue;
			}
			int classId = -1;
			if (seenClasses.add(declaringClass)) {
				classId = classIdMap.getOrRegisterClass(declaringClass);
			}
			int memberId = -1;
			if (recordMembers) {
				String memberKey = declaringClass + "#" + fieldName;
				if (seenMembers.add(memberKey)) {
					memberId = classIdMap.getOrRegisterMember(memberKey);
				}
			}
			if (classId >= 0 || memberId >= 0) {
				entries.add(new FieldAccessEntry(pos, classId, memberId));
			}
		}

		if (entries.isEmpty())
			return;

		// Second pass: insert bytecode from back to front to maintain positions
		for (int i = entries.size() - 1; i >= 0; i--) {
			FieldAccessEntry entry = entries.get(i);
			Bytecode bc = new Bytecode(cp, 2, 0);
			if (entry.classId >= 0 && entry.memberId >= 0) {
				// Combined call: saves one invokestatic (5 bytes per site)
				bc.addIconst(entry.classId);
				bc.addIconst(entry.memberId);
				bc.addInvokestatic("me.bechberger.testorder.agent.runtime.UsageStore", "recordFieldAccessFast",
						"(II)V");
			} else if (entry.classId >= 0) {
				bc.addIconst(entry.classId);
				bc.addInvokestatic("me.bechberger.testorder.agent.runtime.UsageStore", "recordUsageIdFast", "(I)V");
			} else if (entry.memberId >= 0) {
				bc.addIconst(entry.memberId);
				bc.addInvokestatic("me.bechberger.testorder.agent.runtime.UsageStore", "recordMemberUsageIdFast",
						"(I)V");
			}
			ci.insert(entry.pos, bc.get());
		}
		ca.setMaxStack(Math.max(ca.getMaxStack(), 2));
	}
}
