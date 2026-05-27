package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import javax.tools.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BytecodeHashStoreTest {

	@TempDir
	Path tempDir;

	@Test
	void emptyDirReturnsEmptyStore() throws IOException {
		BytecodeHashStore store = BytecodeHashStore.scan(tempDir);
		assertTrue(store.isEmpty());
		assertTrue(store.getClassHashes().isEmpty());
		assertTrue(store.getMethodHashes().isEmpty());
	}

	@Test
	void nullDirReturnsEmptyStore() throws IOException {
		assertTrue(BytecodeHashStore.scan(null).isEmpty());
	}

	@Test
	void scanProducesPerClassAndPerMethodHashes() throws Exception {
		compile(tempDir, "class Foo { public int bar() { return 1; } public int baz() { return 2; } }");
		BytecodeHashStore store = BytecodeHashStore.scan(tempDir);

		assertEquals(1, store.getClassHashes().size());
		assertTrue(store.getClassHashes().containsKey("Foo"));
		// At least the two declared methods (the implicit ctor is also counted).
		assertTrue(store.getMethodHashes().size() >= 3,
				"expected at least 3 method hashes, got: " + store.getMethodHashes());
	}

	@Test
	void saveLoadRoundTrip() throws Exception {
		compile(tempDir, "class Foo { public int bar() { return 1; } }");
		BytecodeHashStore store = BytecodeHashStore.scan(tempDir);
		Path file = tempDir.resolve("bytecode.lz4");
		store.save(file);

		BytecodeHashStore loaded = BytecodeHashStore.load(file);
		assertEquals(store, loaded);
	}

	@Test
	void getChangedClassesDetectsModifiedClass() throws Exception {
		Path classesA = Files.createDirectory(tempDir.resolve("a"));
		Path classesB = Files.createDirectory(tempDir.resolve("b"));
		compile(classesA, "class Foo { public int bar() { return 1; } }");
		compile(classesB, "class Foo { public int bar() { return 99; } }");

		BytecodeHashStore prev = BytecodeHashStore.scan(classesA);
		BytecodeHashStore curr = BytecodeHashStore.scan(classesB);
		Set<String> changed = curr.getChangedClasses(prev);
		assertEquals(Set.of("Foo"), changed);
	}

	@Test
	void getChangedMethodKeysIsLocalizedToTheChangedMethod() throws Exception {
		Path classesA = Files.createDirectory(tempDir.resolve("a"));
		Path classesB = Files.createDirectory(tempDir.resolve("b"));
		// bar() unchanged; baz() body changes.
		compile(classesA, "class Foo { public int bar() { return 1; } public int baz() { return 2; } }");
		compile(classesB, "class Foo { public int bar() { return 1; } public int baz() { return 999; } }");

		BytecodeHashStore prev = BytecodeHashStore.scan(classesA);
		BytecodeHashStore curr = BytecodeHashStore.scan(classesB);
		Set<String> changedMethodKeys = curr.getChangedMethodKeys(prev);

		assertTrue(changedMethodKeys.contains("Foo#baz"), "Foo#baz should be flagged: " + changedMethodKeys);
		assertFalse(changedMethodKeys.contains("Foo#bar"), "Foo#bar should NOT be flagged: " + changedMethodKeys);
	}

	@Test
	void detectsAddedAndDeletedClasses() throws Exception {
		Path classesA = Files.createDirectory(tempDir.resolve("a"));
		Path classesB = Files.createDirectory(tempDir.resolve("b"));
		compile(classesA, "class A { public int x() { return 1; } }");
		compile(classesB, "class B { public int y() { return 2; } }");

		BytecodeHashStore prev = BytecodeHashStore.scan(classesA);
		BytecodeHashStore curr = BytecodeHashStore.scan(classesB);
		Set<String> changed = curr.getChangedClasses(prev);
		assertTrue(changed.contains("A"), "deleted class A should be flagged");
		assertTrue(changed.contains("B"), "added class B should be flagged");
	}

	@Test
	void commentOnlyEditDoesNotChangeHash() throws Exception {
		Path classesA = Files.createDirectory(tempDir.resolve("a"));
		Path classesB = Files.createDirectory(tempDir.resolve("b"));
		// Comment-only difference. Bytecode should be identical after SKIP_DEBUG +
		// SourceFile strip.
		compile(classesA, "class Foo { public int bar() { return 1; } }");
		compile(classesB, "// a brand new comment\n" + "class Foo { /* hello */ public int bar() { return 1; } }");

		BytecodeHashStore prev = BytecodeHashStore.scan(classesA);
		BytecodeHashStore curr = BytecodeHashStore.scan(classesB);
		assertEquals(prev.getClassHashes().get("Foo"), curr.getClassHashes().get("Foo"),
				"comment-only edits must not change the bytecode hash");
		assertTrue(curr.getChangedClasses(prev).isEmpty(),
				"no class should be flagged as changed: " + curr.getChangedClasses(prev));
	}

	// ── helper ──────────────────────────────────────────────────────

	private static void compile(Path dst, String... sources) throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "javax.tools.JavaCompiler not available (run with JDK)");
		List<JavaFileObject> units = new ArrayList<>();
		for (String src : sources) {
			units.add(new InMemorySource(extractClassName(src), src));
		}
		StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
		fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(dst.toFile()));
		assertTrue(compiler.getTask(null, fm, null, List.of("-source", "11", "-target", "11"), null, units).call(),
				"Compilation failed");
	}

	private static String extractClassName(String src) {
		for (String line : src.split("\n")) {
			line = line.trim();
			boolean isClass = line.startsWith("public class ") || line.startsWith("class ");
			if (!isClass) {
				continue;
			}
			String[] parts = line.split("\\s+");
			for (int i = 0; i < parts.length - 1; i++) {
				if (parts[i].equals("class")) {
					return parts[i + 1].replaceAll("[{<].*", "");
				}
			}
		}
		throw new IllegalArgumentException("could not extract class name");
	}

	private static class InMemorySource extends SimpleJavaFileObject {
		private final String code;

		InMemorySource(String name, String code) {
			super(URI.create("string:///" + name + ".java"), Kind.SOURCE);
			this.code = code;
		}

		@Override
		public CharSequence getCharContent(boolean ignoreEncodingErrors) {
			return code;
		}
	}
}
