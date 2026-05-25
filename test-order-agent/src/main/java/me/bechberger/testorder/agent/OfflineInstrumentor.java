package me.bechberger.testorder.agent;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.objectweb.asm.*;

import me.bechberger.testorder.agent.runtime.ClassIdMap;
import me.bechberger.testorder.agent.runtime.ClassIdMapping;

/**
 * Offline (build-time) instrumentor that transforms class files in-place.
 * <p>
 * Walks a classes directory, applies the same instrumentation as
 * {@link AsmClassTransformer} (method-entry recording, field access tracking),
 * and writes transformed classes back to the same location. A marker attribute
 * ({@link #MARKER_ATTRIBUTE_NAME}) prevents double-instrumentation.
 * <p>
 * After instrumentation, writes a {@link ClassIdMapping} file that maps each
 * class name to its assigned integer ID. At test runtime, this mapping is
 * loaded by
 * {@link me.bechberger.testorder.agent.runtime.OfflineRuntimeBootstrap} to
 * configure UsageStore without needing a Java agent.
 */
public class OfflineInstrumentor {

	/**
	 * Custom class-file attribute name used as a marker to prevent
	 * double-instrumentation.
	 */
	public static final String MARKER_ATTRIBUTE_NAME = "TestOrderInstrumented";

	private static final String USAGE_STORE = "me/bechberger/testorder/agent/runtime/UsageStore";

	private final IntelligentClassFilter filter;
	private final Agent.InstrumentationMode mode;
	private final AsmClassTransformer.FieldTrackingMode fieldTrackingMode;
	private final ClassIdMap classIdMap;

	private final AtomicInteger transformedCount = new AtomicInteger();
	private final AtomicInteger skippedCount = new AtomicInteger();
	private final AtomicInteger maxClassId = new AtomicInteger();
	private final AtomicInteger maxMemberId = new AtomicInteger();
	private Path backupDir;
	private Path classesDir;
	private boolean ignoreMarker;

	/**
	 * Create an offline instrumentor.
	 *
	 * @param mode
	 *            instrumentation mode (CLASS, METHOD, MEMBER)
	 * @param includePackages
	 *            comma-separated package prefixes to include (dot-separated)
	 * @param excludePackages
	 *            comma-separated package prefixes to exclude (dot-separated)
	 */
	public OfflineInstrumentor(Agent.InstrumentationMode mode, List<String> includePackages,
			List<String> excludePackages) {
		this(mode, includePackages, excludePackages, ClassIdMap.getInstance());
	}

	/**
	 * Create an offline instrumentor with a specific ClassIdMap (for testing).
	 */
	OfflineInstrumentor(Agent.InstrumentationMode mode, List<String> includePackages, List<String> excludePackages,
			ClassIdMap classIdMap) {
		this.mode = mode;
		this.fieldTrackingMode = AsmClassTransformer.fieldTrackingModeFor(mode);
		this.classIdMap = classIdMap;

		IntelligentClassFilter.Builder filterBuilder = new IntelligentClassFilter.Builder()
				.strategy(IntelligentClassFilter.Strategy.SMART).skipTestClasses(true).useHeuristics(true);

		for (String pkg : includePackages) {
			filterBuilder.explicitInclude(pkg);
		}
		for (String pkg : excludePackages) {
			filterBuilder.explicitExclude(pkg);
		}

		this.filter = filterBuilder.build();
	}

	/**
	 * Instrument all class files in the given directory.
	 *
	 * @param classesDir
	 *            the directory containing compiled .class files (e.g.
	 *            target/classes)
	 * @return the class-id mapping for serialization
	 */
	public ClassIdMapping instrument(Path classesDir) throws IOException {
		return instrument(classesDir, null);
	}

	/**
	 * Instrument all class files in the given directory, optionally backing up
	 * originals.
	 *
	 * @param classesDir
	 *            the directory containing compiled .class files (e.g.
	 *            target/classes)
	 * @param backupDir
	 *            if non-null, original bytes are saved here before transformation
	 *            (same relative structure). A marker file {@code .instrumented} is
	 *            written on completion.
	 * @return the class-id mapping for serialization
	 */
	public ClassIdMapping instrument(Path classesDir, Path backupDir) throws IOException {
		transformedCount.set(0);
		skippedCount.set(0);
		maxClassId.set(0);
		maxMemberId.set(0);
		this.backupDir = backupDir;
		this.classesDir = classesDir;

		if (!Files.isDirectory(classesDir)) {
			return ClassIdMapping.fromClassIdMap(classIdMap, 0, 0);
		}

		// Write marker BEFORE instrumenting so that if the process is killed
		// mid-instrumentation, the next run will detect the marker and restore
		// whatever was already backed up. Restore is idempotent (copying
		// originals over originals is a no-op for un-modified files).
		if (backupDir != null) {
			Files.createDirectories(backupDir);
			Files.writeString(backupDir.resolve(".instrumented"), classesDir.toAbsolutePath().toString());
		}

		// Collect class files first (sorted for deterministic ID assignment)
		List<Path> classFiles = new ArrayList<>();
		Files.walkFileTree(classesDir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (file.toString().endsWith(".class")) {
					classFiles.add(file);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		Collections.sort(classFiles);

		// Pre-register class IDs sequentially for deterministic assignment
		// (ConcurrentHashMap.computeIfAbsent is not deterministic across threads)
		for (Path classFile : classFiles) {
			String internalName = classFileToInternalName(classFile, classesDir);
			if (shouldInstrument(internalName)) {
				String fqcn = internalName.replace('/', '.');
				classIdMap.getOrRegisterClass(fqcn);
			}
		}

		// Parallel instrumentation — ASM transform is CPU-bound, per-file I/O
		// is independent. ClassIdMap is thread-safe (ConcurrentHashMap + atomics).
		if (classFiles.size() >= 16) {
			classFiles.parallelStream().forEach(classFile -> {
				try {
					instrumentFile(classFile, classesDir);
				} catch (Exception e) {
					System.err.println("[test-order] Failed to instrument " + classFile + ": " + e.getMessage());
					skippedCount.incrementAndGet();
				}
			});
		} else {
			for (Path classFile : classFiles) {
				try {
					instrumentFile(classFile, classesDir);
				} catch (Exception e) {
					System.err.println("[test-order] Failed to instrument " + classFile + ": " + e.getMessage());
					skippedCount.incrementAndGet();
				}
			}
		}

		// If nothing was transformed, remove the marker (no restore needed)
		if (backupDir != null && transformedCount.get() == 0) {
			Files.deleteIfExists(backupDir.resolve(".instrumented"));
		}

		return ClassIdMapping.fromClassIdMap(classIdMap, maxClassId.get(), maxMemberId.get());
	}

	/**
	 * Restore original class files from a backup created by
	 * {@link #instrument(Path, Path)}.
	 * <p>
	 * This method is designed to be idempotent and safe against partial execution
	 * (e.g., if a previous restore was killed mid-way). It also cleans up any
	 * leftover {@code .tmp} files from interrupted instrumentation.
	 *
	 * @param backupDir
	 *            the backup directory (must contain {@code .instrumented} marker)
	 * @return true if restoration was performed, false if no backup found
	 */
	public static boolean restore(Path backupDir) throws IOException {
		if (backupDir == null || !Files.isDirectory(backupDir)) {
			return false;
		}
		Path marker = backupDir.resolve(".instrumented");
		if (!Files.exists(marker)) {
			return false;
		}
		Path classesDir = Path.of(Files.readString(marker).trim());

		// Restore all backed-up class files
		Files.walkFileTree(backupDir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (file.toString().endsWith(".class")) {
					try {
						Path relative = backupDir.relativize(file);
						Path target = classesDir.resolve(relative);
						Files.createDirectories(target.getParent());
						Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					} catch (IOException e) {
						// Per-file resilience: log and continue restoring remaining files.
						// A single permission/lock error shouldn't prevent other restorations.
						System.err.println("[test-order] Failed to restore " + file + ": " + e.getMessage());
					}
				}
				return FileVisitResult.CONTINUE;
			}
		});

		// Clean up leftover .tmp files from interrupted instrumentation
		if (Files.isDirectory(classesDir)) {
			Files.walkFileTree(classesDir, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (file.toString().endsWith(".class.tmp")) {
						Files.deleteIfExists(file);
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}

		// Clean up backup directory (best-effort; partial cleanup is harmless
		// since next restore will simply re-run)
		Files.walkFileTree(backupDir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				try {
					Files.delete(file);
				} catch (IOException ignored) {
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
				try {
					Files.delete(dir);
				} catch (IOException ignored) {
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return true;
	}

	public int getTransformedCount() {
		return transformedCount.get();
	}

	public int getSkippedCount() {
		return skippedCount.get();
	}

	/** Lock-free max update via CAS loop — never regresses. */
	private static void updateMax(AtomicInteger target, int candidate) {
		int cur;
		while (candidate > (cur = target.get())) {
			if (target.compareAndSet(cur, candidate))
				return;
		}
	}

	/**
	 * When true, ignore the marker attribute and re-instrument classes that were
	 * previously instrumented. Use when the mapping file is lost but class files
	 * still carry stale instrumentation.
	 */
	public void setIgnoreMarker(boolean ignoreMarker) {
		this.ignoreMarker = ignoreMarker;
	}

	private void instrumentFile(Path classFile, Path root) throws IOException {
		String internalName = classFileToInternalName(classFile, root);
		if (!shouldInstrument(internalName)) {
			skippedCount.incrementAndGet();
			return;
		}

		byte[] original = Files.readAllBytes(classFile);

		// Single-pass transform that also detects the marker attribute.
		// Avoids double-parsing: the old approach created two ClassReaders
		// (one for marker check, one for transform).
		byte[] transformed = doTransform(internalName, original);
		if (transformed != null) {
			// Backup original before overwriting
			if (backupDir != null) {
				Path relative = this.classesDir.relativize(classFile);
				Path backupFile = backupDir.resolve(relative);
				// createDirectories is idempotent — safe to call from parallel threads
				// without the old createdDirs guard, which had a race: thread A adds to
				// the set but hasn't finished createDirectories when thread B skips
				// the call and writes to a not-yet-created directory.
				Files.createDirectories(backupFile.getParent());
				// Only write if no backup exists yet — the first (original,
				// pre-instrumentation)
				// bytes are the true backup; subsequent re-instrumentation should not
				// overwrite them with already-instrumented bytes.
				if (!Files.exists(backupFile)) {
					Files.write(backupFile, original);
				}
			}
			// Write to temp file then atomic-move to avoid corrupted .class on kill
			Path tmpFile = classFile.resolveSibling(classFile.getFileName() + ".tmp");
			Files.write(tmpFile, transformed);
			try {
				Files.move(tmpFile, classFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
						java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(tmpFile, classFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			transformedCount.incrementAndGet();
		} else {
			skippedCount.incrementAndGet();
		}
	}

	private boolean shouldInstrument(String className) {
		if (className == null || className.equals("module-info") || className.endsWith("/module-info")) {
			return false;
		}
		return filter.shouldInstrument(className);
	}

	private byte[] doTransform(String className, byte[] classfileBuffer) {
		ClassReader cr = new ClassReader(classfileBuffer);

		// Check marker attribute in the same parse pass (avoid double-parsing).
		// ClassReader exposes attributes via the visitor pattern, so we detect it
		// inside our ClassVisitor below. However, for a quick pre-check we can scan
		// for the attribute name directly — CR stores attributes as a linked list.
		if (!ignoreMarker && hasMarkerAttributeFast(cr)) {
			return null;
		}

		String fqcn = className.replace('/', '.');
		int mainClassId = classIdMap.getOrRegisterClass(fqcn);
		if (mainClassId < 0) {
			return null;
		}
		// Lock-free max update: CAS loop that never regresses
		updateMax(maxClassId, mainClassId + 1);

		// Build class ID array
		int[] ids = new int[]{mainClassId};

		boolean recordMembers = mode == Agent.InstrumentationMode.MEMBER;

		// Transform with marker attribute addition
		ClassWriter cw = new ClassWriter(cr, 0);
		OfflineClassVisitor cv = new OfflineClassVisitor(cw, className, fqcn, ids, recordMembers);
		cr.accept(cv, 0);

		return cw.toByteArray();
	}

	/**
	 * Check if a class already has the TestOrderInstrumented marker attribute. Uses
	 * the ClassReader that was already created for the transform — no extra
	 * allocation. Falls back to a quick visitor with
	 * SKIP_CODE|SKIP_DEBUG|SKIP_FRAMES.
	 */
	private static boolean hasMarkerAttributeFast(ClassReader cr) {
		MarkerDetector detector = new MarkerDetector();
		cr.accept(detector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return detector.found;
	}

	/**
	 * Check if a class already has the TestOrderInstrumented marker attribute
	 * (static convenience for raw bytes).
	 */
	static boolean hasMarkerAttribute(byte[] classBytes) {
		try {
			return hasMarkerAttributeFast(new ClassReader(classBytes));
		} catch (Exception e) {
			return false;
		}
	}

	private static class MarkerDetector extends ClassVisitor {
		boolean found;

		MarkerDetector() {
			super(Opcodes.ASM9);
		}

		@Override
		public void visitAttribute(Attribute attribute) {
			if (MARKER_ATTRIBUTE_NAME.equals(attribute.type)) {
				found = true;
			}
		}
	}

	static String classFileToInternalName(Path classFile, Path root) {
		String relative = root.relativize(classFile).toString();
		// Remove .class suffix and normalize separators
		String name = relative.substring(0, relative.length() - 6);
		return name.replace(java.io.File.separatorChar, '/');
	}

	/**
	 * ClassVisitor that instruments methods and adds the marker attribute.
	 */
	private class OfflineClassVisitor extends ClassVisitor {
		private final String className;
		private final String fqcn;
		private final int[] classIds;
		private final boolean recordMembers;

		OfflineClassVisitor(ClassVisitor cv, String className, String fqcn, int[] classIds, boolean recordMembers) {
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
			if (mv == null)
				return null;

			if ((access
					& (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) {
				return mv;
			}

			int memberId = recordMembers ? classIdMap.getOrRegisterMember(fqcn + "#" + name) : -1;
			if (memberId >= 0) {
				updateMax(maxMemberId, memberId + 1);
			}
			boolean instrumentFields = fieldTrackingMode != AsmClassTransformer.FieldTrackingMode.NONE;

			return new OfflineMethodVisitor(mv, classIds, memberId, instrumentFields, fqcn, access);
		}

		@Override
		public void visitEnd() {
			// Add marker attribute to prevent double-instrumentation
			cv.visitAttribute(new MarkerAttribute());
			super.visitEnd();
		}
	}

	/**
	 * MethodVisitor that injects recording calls (same logic as
	 * AsmClassTransformer.InstrumentingMethodVisitor).
	 */
	private class OfflineMethodVisitor extends MethodVisitor {
		private final int[] classIds;
		private final int memberId;
		private final boolean instrumentFields;
		private final String selfFqcn;
		private final boolean isPrivate;
		private int extraStack = 0;

		OfflineMethodVisitor(MethodVisitor mv, int[] classIds, int memberId, boolean instrumentFields, String selfFqcn,
				int access) {
			super(Opcodes.ASM9, mv);
			this.classIds = classIds;
			this.memberId = memberId;
			this.instrumentFields = instrumentFields;
			this.selfFqcn = selfFqcn;
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
				// FULL mode: no method tracker, use recordClassOnly for minimal hot path
				String classMethod = (mode == Agent.InstrumentationMode.CLASS)
						? "recordClassOnly"
						: "recordUsageIdFast";
				for (int classId : classIds) {
					if (classId >= 0) {
						emitPushInt(classId);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, USAGE_STORE, classMethod, "(I)V", false);
					}
				}
				if (memberId >= 0) {
					emitPushInt(memberId);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, USAGE_STORE, "recordMemberUsageIdFast", "(I)V", false);
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
			if (opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD)
				return;
			String ownerDot = owner.replace('/', '.');
			if (ownerDot.equals(selfFqcn))
				return;
			if (!filter.shouldInstrument(owner))
				return;

			boolean recordMemberAccess = mode == Agent.InstrumentationMode.MEMBER;
			int classId = classIdMap.getOrRegisterClass(ownerDot);
			int memberFieldId = recordMemberAccess ? classIdMap.getOrRegisterMember(ownerDot + "#" + fieldName) : -1;

			if (classId >= 0)
				updateMax(maxClassId, classId + 1);
			if (memberFieldId >= 0)
				updateMax(maxMemberId, memberFieldId + 1);

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

	/**
	 * Custom ASM attribute used as a marker to detect already-instrumented classes.
	 */
	static class MarkerAttribute extends Attribute {
		MarkerAttribute() {
			super(MARKER_ATTRIBUTE_NAME);
		}

		@Override
		protected Attribute read(ClassReader classReader, int offset, int length, char[] charBuffer,
				int codeAttributeOffset, Label[] labels) {
			return new MarkerAttribute();
		}

		@Override
		protected ByteVector write(ClassWriter classWriter, byte[] code, int codeLength, int maxStack, int maxLocals) {
			return new ByteVector(0); // zero-length content
		}
	}
}
