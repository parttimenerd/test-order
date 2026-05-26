package me.bechberger.testorder.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

import me.bechberger.testorder.agent.runtime.ClassIdMap;
import me.bechberger.testorder.agent.runtime.ClassIdMapping;

class OfflineInstrumentorTest {

	@TempDir
	Path tempDir;

	@Test
	void instrumentsSimpleClass() throws IOException {
		Path classesDir = tempDir.resolve("classes");
		Files.createDirectories(classesDir.resolve("com/example"));

		// Generate a simple class
		byte[] classBytes = generateSimpleClass("com/example/Hello");
		Files.write(classesDir.resolve("com/example/Hello.class"), classBytes);

		ClassIdMap map = ClassIdMap.createForBenchmark();
		OfflineInstrumentor instrumentor = new OfflineInstrumentor(Agent.InstrumentationMode.CLASS,
				List.of("com.example"), List.of(), map);

		ClassIdMapping mapping = instrumentor.instrument(classesDir);

		assertEquals(1, instrumentor.getTransformedCount());
		assertEquals(0, instrumentor.getSkippedCount());
		assertEquals(1, mapping.classCount());
		assertEquals("com.example.Hello", mapping.getClassName(0));
	}

	@Test
	void skipsAlreadyInstrumentedClass() throws IOException {
		Path classesDir = tempDir.resolve("classes");
		Files.createDirectories(classesDir.resolve("com/example"));

		byte[] classBytes = generateSimpleClass("com/example/Widget");
		Files.write(classesDir.resolve("com/example/Widget.class"), classBytes);

		ClassIdMap map1 = ClassIdMap.createForBenchmark();
		OfflineInstrumentor instrumentor = new OfflineInstrumentor(Agent.InstrumentationMode.CLASS,
				List.of("com.example"), List.of(), map1);

		// First pass: instrument
		instrumentor.instrument(classesDir);
		assertEquals(1, instrumentor.getTransformedCount());

		// Second pass: should skip (marker attribute present)
		ClassIdMap map2 = ClassIdMap.createForBenchmark();
		OfflineInstrumentor instrumentor2 = new OfflineInstrumentor(Agent.InstrumentationMode.CLASS,
				List.of("com.example"), List.of(), map2);
		instrumentor2.instrument(classesDir);
		assertEquals(0, instrumentor2.getTransformedCount());
		assertEquals(1, instrumentor2.getSkippedCount());
	}

	@Test
	void respectsIncludePackageFilter() throws IOException {
		Path classesDir = tempDir.resolve("classes");
		Files.createDirectories(classesDir.resolve("com/included"));
		Files.createDirectories(classesDir.resolve("com/excluded"));

		Files.write(classesDir.resolve("com/included/A.class"), generateSimpleClass("com/included/A"));
		Files.write(classesDir.resolve("com/excluded/B.class"), generateSimpleClass("com/excluded/B"));

		ClassIdMap map = ClassIdMap.createForBenchmark();
		OfflineInstrumentor instrumentor = new OfflineInstrumentor(Agent.InstrumentationMode.CLASS,
				List.of("com.included"), List.of(), map);

		ClassIdMapping mapping = instrumentor.instrument(classesDir);

		assertEquals(1, instrumentor.getTransformedCount());
		// B is skipped by filter
		assertTrue(instrumentor.getSkippedCount() >= 1);
	}

	@Test
	void instrumentedClassContainsMarkerAttribute() throws IOException {
		Path classesDir = tempDir.resolve("classes");
		Files.createDirectories(classesDir.resolve("com/marker"));

		byte[] original = generateSimpleClass("com/marker/Foo");
		assertFalse(OfflineInstrumentor.hasMarkerAttribute(original));

		Files.write(classesDir.resolve("com/marker/Foo.class"), original);

		ClassIdMap map = ClassIdMap.createForBenchmark();
		OfflineInstrumentor instrumentor = new OfflineInstrumentor(Agent.InstrumentationMode.CLASS,
				List.of("com.marker"), List.of(), map);
		instrumentor.instrument(classesDir);

		byte[] transformed = Files.readAllBytes(classesDir.resolve("com/marker/Foo.class"));
		assertTrue(OfflineInstrumentor.hasMarkerAttribute(transformed));
	}

	@Test
	void instrumentedClassContainsUsageStoreCall() throws IOException {
		Path classesDir = tempDir.resolve("classes");
		Files.createDirectories(classesDir.resolve("com/call"));

		Files.write(classesDir.resolve("com/call/Service.class"),
				generateClassWithMethod("com/call/Service", "doWork"));

		ClassIdMap map = ClassIdMap.createForBenchmark();
		OfflineInstrumentor instrumentor = new OfflineInstrumentor(Agent.InstrumentationMode.CLASS, List.of("com.call"),
				List.of(), map);
		instrumentor.instrument(classesDir);

		byte[] transformed = Files.readAllBytes(classesDir.resolve("com/call/Service.class"));
		assertTrue(containsUsageStoreCall(transformed));
	}

	@Test
	void emptyDirectoryProducesEmptyMapping() throws IOException {
		Path classesDir = tempDir.resolve("empty");
		Files.createDirectories(classesDir);

		ClassIdMap map = ClassIdMap.createForBenchmark();
		OfflineInstrumentor instrumentor = new OfflineInstrumentor(Agent.InstrumentationMode.CLASS, List.of("com.any"),
				List.of(), map);
		ClassIdMapping mapping = instrumentor.instrument(classesDir);

		assertEquals(0, instrumentor.getTransformedCount());
		assertEquals(0, mapping.classCount());
	}

	@Test
	void nonExistentDirectoryProducesEmptyMapping() throws IOException {
		Path classesDir = tempDir.resolve("nonexistent");

		ClassIdMap map = ClassIdMap.createForBenchmark();
		OfflineInstrumentor instrumentor = new OfflineInstrumentor(Agent.InstrumentationMode.CLASS, List.of("com.any"),
				List.of(), map);
		ClassIdMapping mapping = instrumentor.instrument(classesDir);

		assertEquals(0, mapping.classCount());
	}

	@Test
	void multipleClassesGetDeterministicIds() throws IOException {
		Path classesDir = tempDir.resolve("classes");
		Files.createDirectories(classesDir.resolve("com/det"));

		// Create classes — sorted order should be A, B, C
		Files.write(classesDir.resolve("com/det/C.class"), generateSimpleClass("com/det/C"));
		Files.write(classesDir.resolve("com/det/A.class"), generateSimpleClass("com/det/A"));
		Files.write(classesDir.resolve("com/det/B.class"), generateSimpleClass("com/det/B"));

		ClassIdMap map = ClassIdMap.createForBenchmark();
		OfflineInstrumentor instrumentor = new OfflineInstrumentor(Agent.InstrumentationMode.CLASS, List.of("com.det"),
				List.of(), map);
		ClassIdMapping mapping = instrumentor.instrument(classesDir);

		assertEquals(3, instrumentor.getTransformedCount());
		// Sorted order: A.class, B.class, C.class
		assertEquals("com.det.A", mapping.getClassName(0));
		assertEquals("com.det.B", mapping.getClassName(1));
		assertEquals("com.det.C", mapping.getClassName(2));
	}

	@Test
	void fullMemberModeRecordsMembers() throws IOException {
		Path classesDir = tempDir.resolve("classes");
		Files.createDirectories(classesDir.resolve("com/member"));

		Files.write(classesDir.resolve("com/member/Svc.class"), generateClassWithMethod("com/member/Svc", "process"));

		ClassIdMap map = ClassIdMap.createForBenchmark();
		OfflineInstrumentor instrumentor = new OfflineInstrumentor(Agent.InstrumentationMode.MEMBER,
				List.of("com.member"), List.of(), map);
		ClassIdMapping mapping = instrumentor.instrument(classesDir);

		assertEquals(1, instrumentor.getTransformedCount());
		assertTrue(mapping.memberCount() > 0);
	}

	@Test
	void classFileToInternalNameConvertsCorrectly() {
		Path root = Path.of("/project/target/classes");
		Path classFile = Path.of("/project/target/classes/com/example/MyClass.class");

		String result = OfflineInstrumentor.classFileToInternalName(classFile, root);
		assertEquals("com/example/MyClass", result);
	}

	@Test
	void mappingSaveAndLoadRoundTrip() throws IOException {
		Path classesDir = tempDir.resolve("classes");
		Files.createDirectories(classesDir.resolve("com/rt"));

		Files.write(classesDir.resolve("com/rt/Alpha.class"), generateSimpleClass("com/rt/Alpha"));
		Files.write(classesDir.resolve("com/rt/Beta.class"), generateSimpleClass("com/rt/Beta"));

		ClassIdMap map = ClassIdMap.createForBenchmark();
		OfflineInstrumentor instrumentor = new OfflineInstrumentor(Agent.InstrumentationMode.CLASS, List.of("com.rt"),
				List.of(), map);
		ClassIdMapping mapping = instrumentor.instrument(classesDir);

		Path mappingFile = tempDir.resolve("mapping.bin");
		mapping.save(mappingFile);

		ClassIdMapping loaded = ClassIdMapping.load(mappingFile);
		assertEquals(mapping.classCount(), loaded.classCount());
		for (int i = 0; i < mapping.classCount(); i++) {
			assertEquals(mapping.getClassName(i), loaded.getClassName(i));
		}
	}

	// ── Helpers ──────────────────────────────────────────────────

	/**
	 * Generate a minimal valid class with no methods (other than default
	 * constructor).
	 */
	private byte[] generateSimpleClass(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

		// Default constructor
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * Generate a class with a named method that has a method body.
	 */
	private byte[] generateClassWithMethod(String internalName, String methodName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

		// Default constructor
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();

		// Named method with a simple body
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 1);
		mv.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void instrumentsNonStaticInnerClass() throws IOException {
		// Regression test for BUG-8: offline instrumentor must not corrupt
		// @Nested inner class constructors (which take the outer instance as
		// first parameter).
		Path classesDir = tempDir.resolve("classes");
		Files.createDirectories(classesDir.resolve("com/inner"));
		String outerName = "com/inner/OuterClass";
		String innerName = "com/inner/OuterClass$Inner";
		Files.write(classesDir.resolve("com/inner/OuterClass.class"), generateSimpleClass(outerName));
		Files.write(classesDir.resolve("com/inner/OuterClass$Inner.class"), generateInnerClass(outerName, innerName));
		ClassIdMap map = ClassIdMap.createForBenchmark();
		OfflineInstrumentor instrumentor = new OfflineInstrumentor(Agent.InstrumentationMode.CLASS,
				List.of("com.inner"), List.of(), map);
		ClassIdMapping mapping = instrumentor.instrument(classesDir);
		assertEquals(2, instrumentor.getTransformedCount(), "both outer and inner should be instrumented");
		byte[] innerBytes = Files.readAllBytes(classesDir.resolve("com/inner/OuterClass$Inner.class"));
		assertTrue(OfflineInstrumentor.hasMarkerAttribute(innerBytes),
				"inner class should carry the instrumentation marker");
	}

	private byte[] generateInnerClass(String outerName, String innerName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, innerName, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, "this$0", "L" + outerName + ";", null, null)
				.visitEnd();
		cw.visitInnerClass(innerName, outerName, "Inner", Opcodes.ACC_PUBLIC);
		String ctorDesc = "(L" + outerName + ";)V";
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorDesc, null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitFieldInsn(Opcodes.PUTFIELD, innerName, "this$0", "L" + outerName + ";");
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(2, 2);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * Check if transformed bytecode contains a call to UsageStore.
	 */
	private boolean containsUsageStoreCall(byte[] classBytes) {
		ClassReader cr = new ClassReader(classBytes);
		boolean[] found = {false};
		cr.accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean itf) {
						if (owner.contains("UsageStore")) {
							found[0] = true;
						}
					}
				};
			}
		}, 0);
		return found[0];
	}
}
