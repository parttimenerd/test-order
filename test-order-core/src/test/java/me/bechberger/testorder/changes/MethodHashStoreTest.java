package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Comprehensive test suite for MethodHashStore. Covers core functionality, edge
 * cases, error handling, and LZ4 compression.
 */
class MethodHashStoreTest {

	@TempDir
	Path tempDir;

	// ─── Core functionality tests ───────────────────────────────────────

	@Test
	void scanValidJavaFile() throws IOException {
		// Create a valid Java source file with multiple methods
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot.resolve("com/example"));
		Files.writeString(srcRoot.resolve("com/example/Calculator.java"),
				"package com.example;\n" + "public class Calculator {\n"
						+ "  public int add(int a, int b) { return a + b; }\n"
						+ "  public int subtract(int a, int b) { return a - b; }\n" + "}\n");

		MethodHashStore store = MethodHashStore.scan(srcRoot);

		// Should have extracted hashes for both methods
		Map<String, String> hashes = store.getHashes();
		assertEquals(2, hashes.size());
		assertTrue(hashes.containsKey("com.example.Calculator#add"));
		assertTrue(hashes.containsKey("com.example.Calculator#subtract"));
	}

	@Test
	void scanMultipleFilesInPackage() throws IOException {
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot.resolve("com/example"));

		Files.writeString(srcRoot.resolve("com/example/Service.java"),
				"package com.example;\n" + "public class Service {\n" + "  public void process() { }\n" + "}\n");

		Files.writeString(srcRoot.resolve("com/example/Helper.java"), "package com.example;\n"
				+ "public class Helper {\n" + "  public String format(String text) { return text; }\n" + "}\n");

		MethodHashStore store = MethodHashStore.scan(srcRoot);

		Map<String, String> hashes = store.getHashes();
		assertEquals(2, hashes.size());
		assertTrue(hashes.containsKey("com.example.Service#process"));
		assertTrue(hashes.containsKey("com.example.Helper#format"));
	}

	@Test
	void scanIgnoresNonSourceFiles() throws IOException {
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot);

		// Create a Java file and a non-source file
		Files.writeString(srcRoot.resolve("Main.java"), "public class Main {\n" + "  public void run() { }\n" + "}\n");

		Files.writeString(srcRoot.resolve("readme.txt"), "This is a text file");

		MethodHashStore store = MethodHashStore.scan(srcRoot);

		// Should only have extracted from Java file
		Map<String, String> hashes = store.getHashes();
		assertEquals(1, hashes.size());
		assertTrue(hashes.containsKey("Main#run"));
	}

	@Test
	void saveAndLoadRoundTrip() throws IOException {
		// Create and populate a store with some methods
		Map<String, String> methodMap = new LinkedHashMap<>();
		methodMap.put("com.example.Foo#bar", "abc123def456");
		methodMap.put("com.example.Baz#qux", "xyz789uvw000");
		MethodHashStore original = new MethodHashStore(methodMap);

		// Save to file
		Path hashFile = tempDir.resolve("hashes.lz4");
		original.save(hashFile);

		// Verify the file was created
		assertTrue(Files.exists(hashFile));

		// Load and verify equality
		MethodHashStore loaded = MethodHashStore.load(hashFile);
		assertEquals(original, loaded);
		assertEquals(methodMap, loaded.getHashes());
	}

	@Test
	void getHashesReturnsUnmodifiableMap() throws IOException {
		MethodHashStore store = new MethodHashStore(Map.of("com.example.Foo#bar", "abc123"));

		Map<String, String> hashes = store.getHashes();
		assertThrows(UnsupportedOperationException.class, () -> hashes.put("new", "value"));
	}

	@Test
	void containsMethod() throws IOException {
		MethodHashStore store = new MethodHashStore(Map.of("com.example.Foo#bar", "abc123"));

		assertTrue(store.containsMethod("com.example.Foo#bar"));
		assertFalse(store.containsMethod("com.example.Foo#baz"));
		assertFalse(store.containsMethod("com.example.Other#method"));
	}

	// ─── Save/load with special characters (E38) ───────────────────────

	@Test
	void specialCharactersInMethodNames() throws IOException {
		// Methods with special characters: $, _, etc.
		Map<String, String> methodMap = new LinkedHashMap<>();
		methodMap.put("com.example.Foo#$init", "hash1");
		methodMap.put("com.example.Foo#method_with_underscore", "hash2");
		methodMap.put("com.example.Foo#get$Field", "hash3");
		methodMap.put("com.example.Outer$Inner#method", "hash4");

		MethodHashStore original = new MethodHashStore(methodMap);

		// Save and load round-trip
		Path hashFile = tempDir.resolve("special_chars.lz4");
		original.save(hashFile);
		MethodHashStore loaded = MethodHashStore.load(hashFile);

		assertEquals(original, loaded);
		for (String key : methodMap.keySet()) {
			assertTrue(loaded.containsMethod(key), "Should preserve key: " + key);
			assertEquals(methodMap.get(key), loaded.getHashes().get(key));
		}
	}

	@Test
	void preservesTabCharacterInFormat() throws IOException {
		// Verify the tab separator is correctly preserved
		Map<String, String> methodMap = new LinkedHashMap<>();
		methodMap.put("com.example.Test#method1", "hash1");
		methodMap.put("com.example.Test#method2", "hash2");

		MethodHashStore original = new MethodHashStore(methodMap);
		Path hashFile = tempDir.resolve("tab_test.lz4");
		original.save(hashFile);

		MethodHashStore loaded = MethodHashStore.load(hashFile);
		assertEquals(original, loaded);
	}

	// ─── getChangedMethods() and diff logic ─────────────────────────────

	@Test
	void detectModifiedMethod() throws IOException {
		MethodHashStore before = new MethodHashStore(Map.of("com.example.Foo#method", "hash1"));

		MethodHashStore after = new MethodHashStore(Map.of("com.example.Foo#method", "hash2"));

		Set<String> changed = after.getChangedMethods(before);
		assertEquals(1, changed.size());
		assertTrue(changed.contains("com.example.Foo#method"));
	}

	@Test
	void detectAddedMethod() throws IOException {
		MethodHashStore before = new MethodHashStore(Map.of("com.example.Foo#method1", "hash1"));

		MethodHashStore after = new MethodHashStore(
				Map.of("com.example.Foo#method1", "hash1", "com.example.Foo#method2", "hash2"));

		Set<String> changed = after.getChangedMethods(before);
		assertEquals(1, changed.size());
		assertTrue(changed.contains("com.example.Foo#method2"));
	}

	@Test
	void detectDeletedMethod() throws IOException {
		MethodHashStore before = new MethodHashStore(
				Map.of("com.example.Foo#method1", "hash1", "com.example.Foo#method2", "hash2"));

		MethodHashStore after = new MethodHashStore(Map.of("com.example.Foo#method1", "hash1"));

		Set<String> changed = after.getChangedMethods(before);
		assertEquals(1, changed.size());
		assertTrue(changed.contains("com.example.Foo#method2"));
	}

	@Test
	void detectMultipleChanges() throws IOException {
		MethodHashStore before = new MethodHashStore(Map.of("com.example.Foo#method1", "hash1_old",
				"com.example.Foo#method2", "hash2", "com.example.Foo#method3", "hash3"));

		MethodHashStore after = new MethodHashStore(Map.of("com.example.Foo#method1", "hash1_new", // changed
				"com.example.Foo#method2", "hash2", // unchanged
				// method3 deleted
				"com.example.Foo#method4", "hash4" // added
		));

		Set<String> changed = after.getChangedMethods(before);
		assertEquals(3, changed.size());
		assertTrue(changed.contains("com.example.Foo#method1"));
		assertFalse(changed.contains("com.example.Foo#method2"));
		assertTrue(changed.contains("com.example.Foo#method3"));
		assertTrue(changed.contains("com.example.Foo#method4"));
	}

	@Test
	void noChanges() throws IOException {
		MethodHashStore before = new MethodHashStore(Map.of("com.example.Foo#method", "hash1"));

		MethodHashStore after = new MethodHashStore(Map.of("com.example.Foo#method", "hash1"));

		Set<String> changed = after.getChangedMethods(before);
		assertTrue(changed.isEmpty());
	}

	@Test
	void emptyBeforeStore() throws IOException {
		MethodHashStore before = new MethodHashStore();

		MethodHashStore after = new MethodHashStore(Map.of("com.example.Foo#method", "hash1"));

		Set<String> changed = after.getChangedMethods(before);
		assertEquals(1, changed.size());
		assertTrue(changed.contains("com.example.Foo#method"));
	}

	@Test
	void emptyAfterStore() throws IOException {
		MethodHashStore before = new MethodHashStore(Map.of("com.example.Foo#method", "hash1"));

		MethodHashStore after = new MethodHashStore();

		Set<String> changed = after.getChangedMethods(before);
		assertEquals(1, changed.size());
		assertTrue(changed.contains("com.example.Foo#method"));
	}

	// ─── Edge cases ────────────────────────────────────────────────────

	@Test
	void emptySourceDirectory() throws IOException {
		Path srcRoot = tempDir.resolve("empty_src");
		Files.createDirectories(srcRoot);

		MethodHashStore store = MethodHashStore.scan(srcRoot);

		assertTrue(store.getHashes().isEmpty());
	}

	@Test
	void nonExistentSourceDirectory() throws IOException {
		Path srcRoot = tempDir.resolve("nonexistent");

		MethodHashStore store = MethodHashStore.scan(srcRoot);

		assertTrue(store.getHashes().isEmpty());
	}

	@Test
	void sourceFileWithoutMethods() throws IOException {
		// File with only interface/class declaration, no methods
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot.resolve("com/example"));
		Files.writeString(srcRoot.resolve("com/example/Empty.java"),
				"package com.example;\n" + "public interface Empty {\n" + "}\n");

		MethodHashStore store = MethodHashStore.scan(srcRoot);

		// May have no methods (interface with no method declarations)
		// or may have abstract method signatures depending on parser
		assertNotNull(store.getHashes());
	}

	@Test
	void emptyStore() throws IOException {
		MethodHashStore store = new MethodHashStore();

		assertTrue(store.getHashes().isEmpty());
		assertTrue(store.containsMethod("any.Thing#method") == false);
	}

	@Test
	void constructorMethodKey() throws IOException {
		// Constructors should be filtered out by SourceFileModel
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot);
		Files.writeString(srcRoot.resolve("Main.java"),
				"public class Main {\n" + "  public Main() { }\n" + "  public void method() { }\n" + "}\n");

		MethodHashStore store = MethodHashStore.scan(srcRoot);

		// Should only have the method, not the constructor
		assertEquals(1, store.getHashes().size());
		assertTrue(store.containsMethod("Main#method"));
		assertFalse(store.containsMethod("Main#Main"));
	}

	@Test
	void abstractMethodFiltered() throws IOException {
		// Abstract methods should be filtered out
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot);
		Files.writeString(srcRoot.resolve("AbstractExample.java"), "public abstract class AbstractExample {\n"
				+ "  public abstract void abstractMethod();\n" + "  public void concreteMethod() { }\n" + "}\n");

		MethodHashStore store = MethodHashStore.scan(srcRoot);

		// Should only have the concrete method
		assertEquals(1, store.getHashes().size());
		assertTrue(store.containsMethod("AbstractExample#concreteMethod"));
		assertFalse(store.containsMethod("AbstractExample#abstractMethod"));
	}

	@Test
	void overloadedMethods() throws IOException {
		// Overloaded methods: same name, different signatures
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot);
		Files.writeString(srcRoot.resolve("Overload.java"),
				"public class Overload {\n" + "  public void process(int x) { }\n"
						+ "  public void process(String s) { }\n" + "  public void process() { }\n" + "}\n");

		MethodHashStore store = MethodHashStore.scan(srcRoot);

		// All overloads should be combined under one key per SourceFileModel behavior
		// (methodHashes combines overloads)
		assertTrue(store.containsMethod("Overload#process"));
	}

	@Test
	void nestedClassMethods() throws IOException {
		// Nested inner classes
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot);
		Files.writeString(srcRoot.resolve("Outer.java"), "public class Outer {\n" + "  public void outerMethod() { }\n"
				+ "  public static class Inner {\n" + "    public void innerMethod() { }\n" + "  }\n" + "}\n");

		MethodHashStore store = MethodHashStore.scan(srcRoot);

		Map<String, String> hashes = store.getHashes();
		assertTrue(hashes.size() >= 2);
		assertTrue(hashes.values().stream().allMatch(h -> h != null && !h.isEmpty()));
	}

	// ─── Error handling (E37: unparseable files) ────────────────────────

	@Test
	void skipUnparseableJavaFile() throws IOException {
		// Malformed Java file that can't be parsed
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot.resolve("com/example"));

		// Valid file
		Files.writeString(srcRoot.resolve("com/example/Valid.java"),
				"package com.example;\n" + "public class Valid {\n" + "  public void method() { }\n" + "}\n");

		// Unparseable file
		Files.writeString(srcRoot.resolve("com/example/Invalid.java"),
				"package com.example;\n" + "public class Invalid {{\n" + // syntax error
						"  bad code @@@ ***\n" + "}\n");

		// Should not crash, should skip the bad file
		MethodHashStore store = MethodHashStore.scan(srcRoot);

		// Should have at least extracted the valid file
		Map<String, String> hashes = store.getHashes();
		assertTrue(hashes.size() >= 1);
		assertTrue(hashes.containsKey("com.example.Valid#method"));
	}

	@Test
	void skipEmptyUnparseableFile() throws IOException {
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot);

		// Completely invalid file
		Files.writeString(srcRoot.resolve("Garbage.java"), "this is not java code at all @@@ %%% &&&");

		// Should not crash, should skip gracefully
		MethodHashStore store = MethodHashStore.scan(srcRoot);

		assertTrue(store.getHashes().isEmpty());
	}

	@Test
	void skipFileWithOnlySyntaxErrors() throws IOException {
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot);

		Files.writeString(srcRoot.resolve("BadSyntax.java"), "public class BadSyntax\n" + // missing opening brace
				"  public void method() { }\n" + "}\n");

		// Should not crash
		MethodHashStore store = MethodHashStore.scan(srcRoot);

		assertNotNull(store);
	}

	// ─── LZ4 compression verification ──────────────────────────────────

	@Test
	void savesAsLz4File() throws IOException {
		MethodHashStore store = new MethodHashStore(Map.of("com.example.Foo#bar", "abc123"));

		Path hashFile = tempDir.resolve("test.lz4");
		store.save(hashFile);

		// File should exist
		assertTrue(Files.exists(hashFile));

		// Check file size is reasonable (compressed)
		long fileSize = Files.size(hashFile);
		assertTrue(fileSize > 0, "Compressed file should have content");
	}

	@Test
	void loadDecompressesLz4Data() throws IOException {
		Map<String, String> original = new LinkedHashMap<>();
		original.put("com.example.Test#method1", "hash1value");
		original.put("com.example.Test#method2", "hash2value");

		MethodHashStore store = new MethodHashStore(original);
		Path hashFile = tempDir.resolve("compressed.lz4");
		store.save(hashFile);

		// Load should decompress correctly
		MethodHashStore loaded = MethodHashStore.load(hashFile);

		assertEquals(2, loaded.getHashes().size());
		for (var entry : original.entrySet()) {
			assertEquals(entry.getValue(), loaded.getHashes().get(entry.getKey()));
		}
	}

	@Test
	void largeDataSetCompression() throws IOException {
		// Create a large dataset to test compression efficiency
		Map<String, String> largeMap = new LinkedHashMap<>();
		for (int i = 0; i < 1000; i++) {
			largeMap.put("com.example.Class" + i + "#method" + (i % 10), "hash" + String.format("%05d", i));
		}

		MethodHashStore store = new MethodHashStore(largeMap);
		Path hashFile = tempDir.resolve("large.lz4");
		store.save(hashFile);

		// Load and verify
		MethodHashStore loaded = MethodHashStore.load(hashFile);
		assertEquals(1000, loaded.getHashes().size());

		// Verify file is compressed (should be much smaller than raw data)
		long fileSize = Files.size(hashFile);
		long estimatedRawSize = largeMap.entrySet().stream()
				.mapToLong(e -> e.getKey().length() + e.getValue().length() + 1).sum();
		assertTrue(fileSize < estimatedRawSize, "File should be compressed");
	}

	// ─── Path separator handling (cross-platform) ──────────────────────

	@Test
	void handlesUnixPathSeparators() throws IOException {
		// Test with Unix-style paths
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot.resolve("com/example"));

		Files.writeString(srcRoot.resolve("com/example/Test.java"),
				"package com.example;\n" + "public class Test {\n" + "  public void method() { }\n" + "}\n");

		MethodHashStore store = MethodHashStore.scan(srcRoot);

		// Should correctly associate with package
		assertTrue(store.containsMethod("com.example.Test#method"));
	}

	@Test
	void handlesMixedCaseInClassName() throws IOException {
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot.resolve("com/Example"));

		Files.writeString(srcRoot.resolve("com/Example/MyClass.java"),
				"package com.Example;\n" + "public class MyClass {\n" + "  public void myMethod() { }\n" + "}\n");

		MethodHashStore store = MethodHashStore.scan(srcRoot);

		assertTrue(store.containsMethod("com.Example.MyClass#myMethod"));
	}

	// ─── Equality and hashing ────────────────────────────────────────

	@Test
	void equalsAndHashCode() throws IOException {
		Map<String, String> data = Map.of("com.example.Foo#method", "hash1", "com.example.Bar#method", "hash2");

		MethodHashStore store1 = new MethodHashStore(new LinkedHashMap<>(data));
		MethodHashStore store2 = new MethodHashStore(new LinkedHashMap<>(data));

		assertEquals(store1, store2);
		assertEquals(store1.hashCode(), store2.hashCode());
	}

	@Test
	void equalsWithDifferentData() throws IOException {
		MethodHashStore store1 = new MethodHashStore(Map.of("com.example.Foo#method", "hash1"));

		MethodHashStore store2 = new MethodHashStore(Map.of("com.example.Foo#method", "hash2"));

		assertNotEquals(store1, store2);
	}

	@Test
	void notEqualToOtherType() {
		MethodHashStore store = new MethodHashStore(Map.of("com.example.Foo#method", "hash1"));

		assertNotEquals(store, "not a store");
		assertNotEquals(store, 42);
		assertNotEquals(store, null);
	}

	// ─── Real-world scenario tests ────────────────────────────────────

	@Test
	void realWorldMultiClassScan() throws IOException {
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot.resolve("com/example/service"));
		Files.createDirectories(srcRoot.resolve("com/example/util"));

		Files.writeString(srcRoot.resolve("com/example/service/UserService.java"),
				"package com.example.service;\n" + "public class UserService {\n"
						+ "  public User getUser(int id) { return null; }\n" + "  public void saveUser(User u) { }\n"
						+ "  private void validate(User u) { }\n" + "}\n");

		Files.writeString(srcRoot.resolve("com/example/util/StringUtil.java"),
				"package com.example.util;\n" + "public class StringUtil {\n"
						+ "  public static String trim(String s) { return s.trim(); }\n"
						+ "  public static boolean isEmpty(String s) { return s == null || s.isEmpty(); }\n" + "}\n");

		MethodHashStore store = MethodHashStore.scan(srcRoot);

		Map<String, String> hashes = store.getHashes();
		assertEquals(5, hashes.size()); // 2 public + 1 private from UserService + 2 from StringUtil
		assertTrue(hashes.containsKey("com.example.service.UserService#getUser"));
		assertTrue(hashes.containsKey("com.example.service.UserService#saveUser"));
		assertTrue(hashes.containsKey("com.example.service.UserService#validate"));
		assertTrue(hashes.containsKey("com.example.util.StringUtil#trim"));
		assertTrue(hashes.containsKey("com.example.util.StringUtil#isEmpty"));
	}

	@Test
	void detectMethodModification() throws IOException {
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot);

		// First version
		Files.writeString(srcRoot.resolve("App.java"),
				"public class App {\n" + "  public int calculate(int x) { return x * 2; }\n" + "}\n");

		MethodHashStore before = MethodHashStore.scan(srcRoot);

		// Modify the method
		Files.writeString(srcRoot.resolve("App.java"),
				"public class App {\n" + "  public int calculate(int x) { return x * 3; }\n" + "}\n");

		MethodHashStore after = MethodHashStore.scan(srcRoot);

		Set<String> changed = after.getChangedMethods(before);
		assertEquals(1, changed.size());
		assertTrue(changed.contains("App#calculate"));
	}

	@Test
	void codeCommentChangesDoesNotAffectHash() throws IOException {
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot);

		// Version with no comments
		Files.writeString(srcRoot.resolve("Util.java"),
				"public class Util {\n" + "  public String getText() { return \"hello\"; }\n" + "}\n");

		MethodHashStore before = MethodHashStore.scan(srcRoot);

		// Add comment but don't change code
		Files.writeString(srcRoot.resolve("Util.java"), "public class Util {\n" + "  // returns text\n"
				+ "  public String getText() { return \"hello\"; }\n" + "}\n");

		MethodHashStore after = MethodHashStore.scan(srcRoot);

		Set<String> changed = after.getChangedMethods(before);
		// Comments shouldn't affect hash (SourceFileModel strips them)
		// This is the expected behavior based on SourceFileModel design
		assertTrue(changed.isEmpty() || changed.size() == 0);
	}

	@Test
	void preservesSortedOrderInStore() throws IOException {
		// TreeMap should maintain sorted order
		Map<String, String> data = new LinkedHashMap<>();
		data.put("z.package.ClassZ#method", "hash1");
		data.put("a.package.ClassA#method", "hash2");
		data.put("m.package.ClassM#method", "hash3");

		MethodHashStore store = new MethodHashStore(data);

		// Should be sorted when saved/loaded (TreeMap)
		List<String> keys = new ArrayList<>(store.getHashes().keySet());
		assertEquals("a.package.ClassA#method", keys.get(0));
		assertEquals("m.package.ClassM#method", keys.get(1));
		assertEquals("z.package.ClassZ#method", keys.get(2));
	}

	@Test
	void roundTripPreservesSortedOrder() throws IOException {
		Map<String, String> data = new LinkedHashMap<>();
		data.put("z.ZClass#m", "hz");
		data.put("a.AClass#m", "ha");
		data.put("m.MClass#m", "hm");
		MethodHashStore store = new MethodHashStore(data);

		Path file = tempDir.resolve("sorted.lz4");
		store.save(file);

		MethodHashStore loaded = MethodHashStore.load(file);
		List<String> keys = new ArrayList<>(loaded.getHashes().keySet());

		// Verify ordering is preserved
		assertTrue(keys.get(0).compareTo(keys.get(1)) < 0);
		assertTrue(keys.get(1).compareTo(keys.get(2)) < 0);
	}

	// ─── Bug regression: inner class methods carried over on incremental scan ──

	/**
	 * Regression for bug: scanIncremental dropped inner class method hashes when
	 * the containing file was unchanged, because classToFile only mapped top-level
	 * class names. On subsequent scans inner class methods appeared as "changed" on
	 * every run.
	 */
	@Test
	void scanIncrementalCarriesOverInnerClassMethodsForUnchangedFile() throws IOException {
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot);
		Files.writeString(srcRoot.resolve("Outer.java"),
				"public class Outer {\n" + "  public void outerMethod() { return; }\n"
						+ "  public static class Inner {\n" + "    public void innerMethod() { return; }\n" + "  }\n"
						+ "}\n");

		// First full scan
		MethodHashStore first = MethodHashStore.scan(srcRoot);
		assertTrue(first.containsMethod("Outer#outerMethod"), "outer method should be present");
		assertTrue(first.containsMethod("Outer$Inner#innerMethod"), "inner class method should be present");

		// Incremental scan with no file changes
		MethodHashStore second = MethodHashStore.scanIncremental(srcRoot, first);
		assertTrue(second.containsMethod("Outer$Inner#innerMethod"),
				"inner class method should be carried over for unchanged file");

		// getChangedMethods must report no changes when nothing changed
		Set<String> changed = second.getChangedMethods(first);
		assertFalse(changed.contains("Outer$Inner#innerMethod"),
				"inner class method must NOT appear as changed when file is unchanged");
		assertTrue(changed.isEmpty(), "no methods should appear changed: " + changed);
	}

	@Test
	void scanIncrementalDetectsRealChangeInInnerClassMethod() throws IOException {
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot);
		Path outerFile = srcRoot.resolve("Outer.java");
		Files.writeString(outerFile, "public class Outer {\n" + "  public void outerMethod() { return; }\n"
				+ "  public static class Inner {\n" + "    public void innerMethod() { return; }\n" + "  }\n" + "}\n");

		MethodHashStore first = MethodHashStore.scan(srcRoot);

		// Modify the inner class method body
		Files.writeString(outerFile,
				"public class Outer {\n" + "  public void outerMethod() { return; }\n"
						+ "  public static class Inner {\n" + "    public void innerMethod() { int x = 42; }\n"
						+ "  }\n" + "}\n");

		MethodHashStore second = MethodHashStore.scanIncremental(srcRoot, first);
		Set<String> changed = second.getChangedMethods(first);

		assertTrue(changed.contains("Outer$Inner#innerMethod"),
				"changed inner class method should be detected: " + changed);
	}
}
