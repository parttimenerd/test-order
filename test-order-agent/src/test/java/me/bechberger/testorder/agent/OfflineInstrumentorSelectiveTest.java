package me.bechberger.testorder.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

import me.bechberger.testorder.agent.runtime.ClassIdMap;

class OfflineInstrumentorSelectiveTest {

	@TempDir
	Path tempDir;

	private byte[] generateSimpleClass(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
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

	@Test
	void nullUncertainSetInstrumentsAllFilteredClasses() throws IOException {
		Path classesDir = tempDir.resolve("classes");
		Files.createDirectories(classesDir.resolve("com/example"));
		Files.write(classesDir.resolve("com/example/Foo.class"), generateSimpleClass("com/example/Foo"));
		Files.write(classesDir.resolve("com/example/Bar.class"), generateSimpleClass("com/example/Bar"));

		ClassIdMap map = ClassIdMap.createForBenchmark();
		OfflineInstrumentor instrumentor = new OfflineInstrumentor(Agent.InstrumentationMode.CLASS,
				List.of("com.example"), List.of(), null, map);

		instrumentor.instrument(classesDir);
		assertEquals(2, instrumentor.getTransformedCount());
	}

	@Test
	void emptyUncertainSetSkipsAllClasses() throws IOException {
		Path classesDir = tempDir.resolve("classes");
		Files.createDirectories(classesDir.resolve("com/example"));
		Files.write(classesDir.resolve("com/example/Foo.class"), generateSimpleClass("com/example/Foo"));
		Files.write(classesDir.resolve("com/example/Bar.class"), generateSimpleClass("com/example/Bar"));

		ClassIdMap map = ClassIdMap.createForBenchmark();
		OfflineInstrumentor instrumentor = new OfflineInstrumentor(Agent.InstrumentationMode.CLASS,
				List.of("com.example"), List.of(), Set.of(), map);

		instrumentor.instrument(classesDir);
		assertEquals(0, instrumentor.getTransformedCount());
		assertEquals(2, instrumentor.getSkippedCount());
	}

	@Test
	void uncertainSetInstrumentsOnlyMatchingClass() throws IOException {
		Path classesDir = tempDir.resolve("classes");
		Files.createDirectories(classesDir.resolve("com/example"));
		Files.write(classesDir.resolve("com/example/Foo.class"), generateSimpleClass("com/example/Foo"));
		Files.write(classesDir.resolve("com/example/Bar.class"), generateSimpleClass("com/example/Bar"));

		ClassIdMap map = ClassIdMap.createForBenchmark();
		OfflineInstrumentor instrumentor = new OfflineInstrumentor(Agent.InstrumentationMode.CLASS,
				List.of("com.example"), List.of(), Set.of("com.example.Foo"), map);

		instrumentor.instrument(classesDir);
		assertEquals(1, instrumentor.getTransformedCount());
		assertEquals(1, instrumentor.getSkippedCount());
	}

	@Test
	void includeFilterStillAppliesBeforeUncertainCheck() throws IOException {
		Path classesDir = tempDir.resolve("classes");
		Files.createDirectories(classesDir.resolve("com/other"));
		Files.write(classesDir.resolve("com/other/Baz.class"), generateSimpleClass("com/other/Baz"));

		ClassIdMap map = ClassIdMap.createForBenchmark();
		// uncertain set includes com.other.Baz but include filter only covers
		// com.example
		OfflineInstrumentor instrumentor = new OfflineInstrumentor(Agent.InstrumentationMode.CLASS,
				List.of("com.example"), List.of(), Set.of("com.other.Baz"), map);

		instrumentor.instrument(classesDir);
		assertEquals(0, instrumentor.getTransformedCount());
	}
}
