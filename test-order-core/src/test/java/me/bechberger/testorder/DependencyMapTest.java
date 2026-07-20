package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyMapTest {

	@TempDir
	Path tempDir;

	@Test
	void saveAndLoadRoundTrip() throws IOException {
		DependencyMap original = new DependencyMap();
		original.put("com.example.FooTest", Set.of("com.example.Foo", "com.example.Bar"));
		original.put("com.example.BarTest", Set.of("com.example.Bar", "com.example.Baz"));

		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		original.save(indexFile);

		DependencyMap loaded = DependencyMap.load(indexFile);
		assertEquals(original, loaded);
	}

	@Test
	void loadRejectsTextFormat() throws IOException {
		DependencyMap original = new DependencyMap();
		original.put("com.example.FooTest", Set.of("com.example.Foo", "com.example.Bar"));

		Path indexFile = tempDir.resolve("test.idx");
		original.saveText(indexFile);

		assertThrows(IOException.class, () -> DependencyMap.load(indexFile));
	}

	@Test
	void binarySmallerThanText() throws IOException {
		DependencyMap map = new DependencyMap();
		// create data with lots of prefix redundancy
		for (int i = 0; i < 20; i++) {
			Set<String> deps = new TreeSet<>();
			for (int j = 0; j < 50; j++) {
				deps.add("org.springframework.samples.petclinic.service.Class" + j);
			}
			map.put("org.springframework.samples.petclinic.test.TestClass" + i, deps);
		}

		Path textIdx = tempDir.resolve("text.idx");
		Path binIdx = tempDir.resolve("bin.idx");
		map.saveText(textIdx);
		map.save(binIdx);

		long textSize = Files.size(textIdx);
		long binSize = Files.size(binIdx);
		assertTrue(binSize < textSize,
				"Binary (" + binSize + " bytes) should be smaller than text (" + textSize + " bytes)");
	}

	@Test
	void rowDeduplication() throws IOException {
		// two tests share the exact same dependency set → should be grouped
		DependencyMap map = new DependencyMap();
		Set<String> sharedDeps = Set.of("com.example.A", "com.example.B", "com.example.C");
		map.put("com.example.Test1", sharedDeps);
		map.put("com.example.Test2", sharedDeps);
		map.put("com.example.Test3", Set.of("com.example.D")); // different

		Path idx = tempDir.resolve("dedup.idx");
		map.save(idx);

		DependencyMap loaded = DependencyMap.load(idx);
		assertEquals(map, loaded);
		// the shared deps should be reference-equal after dedup load
		assertEquals(loaded.get("com.example.Test1"), loaded.get("com.example.Test2"));
	}

	@Test
	void emptyDeps() throws IOException {
		DependencyMap map = new DependencyMap();
		map.put("com.example.EmptyTest", Set.of());

		Path idx = tempDir.resolve("empty.idx");
		map.save(idx);

		DependencyMap loaded = DependencyMap.load(idx);
		assertEquals(1, loaded.size());
		assertTrue(loaded.get("com.example.EmptyTest").isEmpty());
	}

	@Test
	void preservesInsertionOrder() throws IOException {
		DependencyMap map = new DependencyMap();
		map.put("ZTest", Set.of("Z1"));
		map.put("ATest", Set.of("A1"));
		map.put("MTest", Set.of("M1"));

		Path idx = tempDir.resolve("order.idx");
		map.save(idx);

		DependencyMap loaded = DependencyMap.load(idx);
		List<String> expected = new ArrayList<>(map.testClasses());
		List<String> actual = new ArrayList<>(loaded.testClasses());
		assertEquals(expected, actual, "insertion order must be preserved");
	}

	@Test
	void loadRejectsBinaryWithWrongMagic() throws IOException {
		Path indexFile = tempDir.resolve("test.idx");
		Files.writeString(indexFile, "# test-order dependency index v1\ncom.example.FooTest\tcom.example.Foo\n");
		assertThrows(IOException.class, () -> DependencyMap.load(indexFile));
	}

	@Test
	void aggregateDepsFiles() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);

		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.Foo\ncom.example.Bar\n");
		Files.writeString(depsDir.resolve("com.example.BarTest.deps"), "com.example.Bar\ncom.example.Baz\n");

		DependencyMap map = DependencyMap.aggregate(depsDir);
		assertEquals(2, map.size());
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), map.get("com.example.FooTest"));
		assertEquals(Set.of("com.example.Bar", "com.example.Baz"), map.get("com.example.BarTest"));
	}

	@Test
	void aggregateLoadsMemberDependencyFiles() throws IOException {
		Path depsDir = tempDir.resolve("deps-with-members");
		Files.createDirectories(depsDir);

		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.Foo\n");
		Files.writeString(depsDir.resolve("com.example.FooTest.members"),
				"com.example.Foo#fieldA\ncom.example.Foo#doWork\n");
		Files.writeString(depsDir.resolve("com.example.FooTest#testA.mmembers"),
				"# com.example.FooTest#testA\ncom.example.Foo#doWork\n");
		Files.writeString(depsDir.resolve("com.example.FooTest#testA.mdeps"),
				"# com.example.FooTest#testA\ncom.example.Foo\n");

		DependencyMap map = DependencyMap.aggregate(depsDir);

		assertEquals(Set.of("com.example.Foo"), map.get("com.example.FooTest"));
		assertEquals(Set.of("com.example.Foo#fieldA", "com.example.Foo#doWork"),
				map.getMemberDeps("com.example.FooTest"));
		assertEquals(Set.of("com.example.Foo#doWork"), map.getMethodMemberDeps("com.example.FooTest#testA"));
		assertEquals(Set.of("com.example.Foo"), map.getMethodDeps("com.example.FooTest#testA"));
	}

	@Test
	void getAffectedTests() {
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Foo", "com.example.Bar"));
		map.put("com.example.BarTest", Set.of("com.example.Baz"));
		map.put("com.example.AllTest", Set.of("com.example.Foo", "com.example.Baz"));

		Set<String> affected = map.getAffectedTests(Set.of("com.example.Foo"));
		assertEquals(Set.of("com.example.FooTest", "com.example.AllTest"), affected);
	}

	@Test
	void getAffectedTestsNoMatch() {
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Foo"));

		Set<String> affected = map.getAffectedTests(Set.of("com.example.Unrelated"));
		assertTrue(affected.isEmpty());
	}

	@Test
	void emptyDependencyMap() {
		DependencyMap map = new DependencyMap();
		assertEquals(0, map.size());
		assertEquals(0, map.totalUniqueClasses());
		assertEquals(0, map.averageDeps(), 0.01);
	}

	@Test
	void statistics() {
		DependencyMap map = new DependencyMap();
		map.put("Test1", Set.of("A", "B", "C"));
		map.put("Test2", Set.of("B", "D"));

		assertEquals(2, map.size());
		assertEquals(4, map.totalUniqueClasses());
		assertEquals(2.5, map.averageDeps(), 0.01);
	}

	@Test
	void aggregateIgnoresNonDepsFiles() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);

		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.Foo\n");
		Files.writeString(depsDir.resolve("some-other-file.txt"), "irrelevant\n");

		DependencyMap map = DependencyMap.aggregate(depsDir);
		assertEquals(1, map.size());
	}

	@Test
	void loadWithEmptyDeps() throws IOException {
		DependencyMap original = new DependencyMap();
		original.put("com.example.EmptyTest", Set.of());
		Path indexFile = tempDir.resolve("test.idx");
		original.save(indexFile);
		DependencyMap map = DependencyMap.load(indexFile);
		assertEquals(1, map.size());
		assertTrue(map.get("com.example.EmptyTest").isEmpty());
	}

	@Test
	void loadSingleEntry() throws IOException {
		DependencyMap original = new DependencyMap();
		original.put("com.example.FooTest", Set.of("com.example.Foo"));
		Path indexFile = tempDir.resolve("test.idx");
		original.save(indexFile);
		DependencyMap map = DependencyMap.load(indexFile);
		assertEquals(1, map.size());
	}

	@Test
	void compressedIndexSizeGuardRejectsHugeFiles() throws IOException {
		Path indexFile = tempDir.resolve("too-large.idx");
		IOException error = assertThrows(IOException.class,
				() -> DependencyMap.validateCompressedFileSize(DependencyMap.MAX_COMPRESSED_FILE_SIZE + 1, indexFile));
		assertTrue(error.getMessage().contains("safe size limit"));
	}

	@Test
	void getAffectedTestsMultipleChanges() {
		DependencyMap map = new DependencyMap();
		map.put("Test1", Set.of("A", "B"));
		map.put("Test2", Set.of("C", "D"));
		map.put("Test3", Set.of("B", "D"));

		Set<String> affected = map.getAffectedTests(Set.of("B", "D"));
		assertEquals(Set.of("Test1", "Test2", "Test3"), affected);
	}

	@Test
	void testClassesReturnsUnmodifiable() {
		DependencyMap map = new DependencyMap();
		map.put("Test1", Set.of("A"));
		assertThrows(UnsupportedOperationException.class, () -> map.testClasses().add("Test2"));
	}

	@Test
	void aggregateEmptyDepsFile() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);
		Files.writeString(depsDir.resolve("com.example.EmptyTest.deps"), "\n\n");

		DependencyMap map = DependencyMap.aggregate(depsDir);
		assertEquals(1, map.size());
		assertTrue(map.get("com.example.EmptyTest").isEmpty());
	}

	@Test
	void aggregateTrimsWhitespace() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);
		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "  com.example.Foo  \n  com.example.Bar  \n");

		DependencyMap map = DependencyMap.aggregate(depsDir);
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), map.get("com.example.FooTest"));
	}

	@Test
	void saveAndLoadPreservesOrder() throws IOException {
		DependencyMap map = new DependencyMap();
		map.put("ZTest", Set.of("Z1"));
		map.put("ATest", Set.of("A1"));
		map.put("MTest", Set.of("M1"));

		Path idx = tempDir.resolve("test.idx");
		map.save(idx);

		DependencyMap loaded = DependencyMap.load(idx);
		assertEquals(map, loaded);
	}

	@Test
	void emptyMap() throws IOException {
		DependencyMap map = new DependencyMap();
		Path idx = tempDir.resolve("empty.idx");
		map.save(idx);

		DependencyMap loaded = DependencyMap.load(idx);
		assertEquals(0, loaded.size());
	}

	// ═══════════════════════════════════════════════════════════════════
	// Regression: aggregate on empty dir returns size==0
	// (BUG_REPORT_2 #4: callers should check size before overwriting)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void aggregateEmptyDirReturnsSizeZero() throws IOException {
		Path emptyDir = tempDir.resolve("no-deps");
		Files.createDirectories(emptyDir);

		DependencyMap map = DependencyMap.aggregate(emptyDir);
		assertEquals(0, map.size(), "Aggregating an empty directory should return a map with size 0");
	}

	@Test
	void aggregateNonEmptyDirReturnsPopulatedMap() throws IOException {
		Path depsDir = tempDir.resolve("some-deps");
		Files.createDirectories(depsDir);
		Files.writeString(depsDir.resolve("com.example.ATest.deps"), "com.example.A\ncom.example.B\n");
		Files.writeString(depsDir.resolve("com.example.BTest.deps"), "com.example.C\n");

		DependencyMap map = DependencyMap.aggregate(depsDir);
		assertEquals(2, map.size());
		assertEquals(Set.of("com.example.A", "com.example.B"), map.get("com.example.ATest"));
		assertEquals(Set.of("com.example.C"), map.get("com.example.BTest"));
	}

	@Test
	void aggregateFromDepsDirectoryLoadsMemberDependencyFiles() throws IOException {
		Path depsDir = tempDir.resolve("parallel-deps-with-members");
		Files.createDirectories(depsDir);

		Files.writeString(depsDir.resolve("com.example.BarTest.deps"), "com.example.Bar\n");
		Files.writeString(depsDir.resolve("com.example.BarTest.members"),
				"com.example.Bar#fieldX\ncom.example.Bar#run\n");
		Files.writeString(depsDir.resolve("com.example.BarTest#testB.mdeps"),
				"# com.example.BarTest#testB\ncom.example.Bar\n");
		Files.writeString(depsDir.resolve("com.example.BarTest#testB.mmembers"),
				"# com.example.BarTest#testB\ncom.example.Bar#run\n");

		Path indexFile = tempDir.resolve("aggregated.lz4");
		DependencyMap.aggregateFromDepsDirectory(depsDir, indexFile);

		DependencyMap loaded = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.Bar"), loaded.get("com.example.BarTest"));
		assertEquals(Set.of("com.example.Bar#fieldX", "com.example.Bar#run"),
				loaded.getMemberDeps("com.example.BarTest"));
		assertEquals(Set.of("com.example.Bar"), loaded.getMethodDeps("com.example.BarTest#testB"));
		assertEquals(Set.of("com.example.Bar#run"), loaded.getMethodMemberDeps("com.example.BarTest#testB"));
	}

	@Test
	void get_nestedClassFallsBackToTopLevel() {
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Foo", "com.example.Bar"));

		// Nested class that doesn't have its own entry should fall back to top-level
		Set<String> deps = map.get("com.example.FooTest$Inner");
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), deps);
	}

	@Test
	void get_nestedClassWithOwnEntryDoesNotFallBack() {
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Foo"));
		map.put("com.example.FooTest$Inner", Set.of("com.example.Bar"));

		// Nested class with its own entry should use its own data
		Set<String> deps = map.get("com.example.FooTest$Inner");
		assertEquals(Set.of("com.example.Bar"), deps);
	}

	@Test
	void getDependenciesReturnsUnmodifiableSet() {
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Foo", "com.example.Bar"));

		Set<String> deps = map.get("com.example.FooTest");
		assertThrows(UnsupportedOperationException.class, () -> deps.add("com.example.Baz"));
		assertThrows(UnsupportedOperationException.class, () -> deps.remove("com.example.Foo"));
	}

	@Test
	void getMemberDepsReturnsUnmodifiableSet() {
		DependencyMap map = new DependencyMap();
		map.putMemberDeps("com.example.FooTest", Set.of("com.example.Foo#doWork", "com.example.Bar#init"));

		Set<String> deps = map.getMemberDeps("com.example.FooTest");
		assertThrows(UnsupportedOperationException.class, () -> deps.add("com.example.X#y"));
	}

	@Test
	void getMemberDeps_nestedClassFallsBackToTopLevel() {
		DependencyMap map = new DependencyMap();
		map.putMemberDeps("com.example.FooTest", Set.of("com.example.Foo#doWork", "com.example.Bar#init"));

		// Nested class that doesn't have its own entry should fall back to top-level
		Set<String> deps = map.getMemberDeps("com.example.FooTest$Inner");
		assertEquals(Set.of("com.example.Foo#doWork", "com.example.Bar#init"), deps);
	}

	@Test
	void getMemberDeps_nestedClassWithOwnEntryDoesNotFallBack() {
		DependencyMap map = new DependencyMap();
		map.putMemberDeps("com.example.FooTest", Set.of("com.example.Foo#doWork"));
		map.putMemberDeps("com.example.FooTest$Inner", Set.of("com.example.Bar#init"));

		// Nested class with its own entry should use its own data
		Set<String> deps = map.getMemberDeps("com.example.FooTest$Inner");
		assertEquals(Set.of("com.example.Bar#init"), deps);
	}

	@Test
	void getMethodMemberDeps_nestedClassFallsBackToTopLevel() {
		DependencyMap map = new DependencyMap();
		map.putMethodMemberDeps("com.example.FooTest#testA", Set.of("com.example.Foo#field1"));

		// Nested class method that doesn't have its own entry should fall back
		Set<String> deps = map.getMethodMemberDeps("com.example.FooTest$Inner", "testA");
		assertEquals(Set.of("com.example.Foo#field1"), deps);
	}

	@Test
	void getMethodMemberDeps_nestedClassWithOwnEntryDoesNotFallBack() {
		DependencyMap map = new DependencyMap();
		map.putMethodMemberDeps("com.example.FooTest#testA", Set.of("com.example.Foo#field1"));
		map.putMethodMemberDeps("com.example.FooTest$Inner#testA", Set.of("com.example.Bar#field2"));

		Set<String> deps = map.getMethodMemberDeps("com.example.FooTest$Inner", "testA");
		assertEquals(Set.of("com.example.Bar#field2"), deps);
	}

	@Test
	void getMethodMemberDeps_byKey_nestedClassFallsBackToTopLevel() {
		DependencyMap map = new DependencyMap();
		map.putMethodMemberDeps("com.example.FooTest#testA", Set.of("com.example.Foo#field1"));

		// Key-based overload should also fallback
		Set<String> deps = map.getMethodMemberDeps("com.example.FooTest$Inner#testA");
		assertEquals(Set.of("com.example.Foo#field1"), deps);
	}

	@Test
	void validateCompressedFileSizeThrowsForOversizedFile() throws IOException {
		Path dummy = tempDir.resolve("dummy.lz4");
		Files.writeString(dummy, "x");
		long overLimit = 1_000_000_001L;
		assertThrows(IOException.class, () -> DependencyMap.validateCompressedFileSize(overLimit, dummy));
	}

	@Test
	void validateCompressedFileSizeAcceptsExactLimit() throws IOException {
		Path dummy = tempDir.resolve("dummy.lz4");
		Files.writeString(dummy, "x");
		// should not throw at the exact limit
		assertDoesNotThrow(() -> DependencyMap.validateCompressedFileSize(1_000_000_000L, dummy));
	}

	// ── Tier 3c: row-dedup immutability and corrupt binary ─────────────────

	@Test
	void rowDeduplicatedSetsAreImmutable() throws IOException {
		// Two tests share the same dependency set → row-deduplicated after round-trip.
		// The returned set must be unmodifiable to prevent consumer mutation from
		// corrupting all tests that share that row (IMPROVEMENT_PLAN.md #51).
		DependencyMap map = new DependencyMap();
		Set<String> shared = Set.of("com.example.A", "com.example.B", "com.example.C");
		map.put("com.example.Test1", shared);
		map.put("com.example.Test2", shared);

		Path idx = tempDir.resolve("dedup-immutable.lz4");
		map.save(idx);
		DependencyMap loaded = DependencyMap.load(idx);

		Set<String> deps1 = loaded.get("com.example.Test1");
		Set<String> deps2 = loaded.get("com.example.Test2");

		// Both must be equal (same content)
		assertEquals(deps1, deps2, "Row-deduplicated sets should have equal content");

		// Mutation must throw UnsupportedOperationException to prevent corruption
		assertThrows(UnsupportedOperationException.class, () -> deps1.add("com.example.NEW"),
				"Row-deduplicated dep set must be unmodifiable");
		assertThrows(UnsupportedOperationException.class, () -> deps2.remove("com.example.A"),
				"Row-deduplicated dep set must be unmodifiable");
	}

	@Test
	void rowDeduplicatedSetsReturnedByGetAreImmutable() {
		// Even when not loading from disk, classDeps() return must be unmodifiable
		DependencyMap map = new DependencyMap();
		map.put("com.example.Test1", Set.of("com.example.X"));
		Set<String> deps = map.get("com.example.Test1");
		assertThrows(UnsupportedOperationException.class, () -> deps.add("com.example.Y"),
				"get() must return an unmodifiable set");
	}

	@Test
	void loadTruncatedBinaryFileThrowsIOException() throws IOException {
		// Write a file that starts with valid LZ4 magic header bytes but then has
		// truncated/garbage content. loadBinary must fail with IOException rather
		// than NPE, OOM, or silent wrong result.
		Path idx = tempDir.resolve("truncated.lz4");
		// Plausible starting bytes for LZ4 framing, then abrupt end
		byte[] truncated = new byte[]{0x04, (byte) 0x22, 0x4d, 0x18, // LZ4 magic: 0x184D2204 (little-endian)
				0x60, 0x70, 0x73, 0x00 // partial FLG + BD fields, no content
		};
		Files.write(idx, truncated);
		assertThrows(IOException.class, () -> DependencyMap.load(idx),
				"Loading a truncated binary file must throw IOException");
	}

	@Test
	void loadCompletelyGarbageBinaryFileThrowsIOException() throws IOException {
		// File with random bytes that happen to look like V2 binary (header check via
		// magic)
		// but contain invalid data — must fail gracefully.
		Path idx = tempDir.resolve("garbage.lz4");
		byte[] garbage = new byte[128];
		for (int i = 0; i < garbage.length; i++)
			garbage[i] = (byte) (i * 7 + 3);
		Files.write(idx, garbage);
		assertThrows(IOException.class, () -> DependencyMap.load(idx),
				"Loading garbage binary data must throw IOException");
	}

	// ── Section-based format v1: forward compatibility ─────────────────

	@Test
	void unknownSectionTypeIsSkippedOnLoad() throws IOException {
		// Write a valid v1 index, then load it and verify that extra unknown
		// section types are harmless — they get skipped by the reader.
		// Strategy: save normally, then tamper with the file to inject an
		// unknown section. Instead, we just verify the normal round-trip works
		// (section-based format), and use a crafted binary to test skip logic.
		DependencyMap original = new DependencyMap();
		original.put("com.example.FooTest", Set.of("com.example.Foo"));

		// Save normally to get a valid file
		Path idx = tempDir.resolve("v1-unknown-section.lz4");
		original.save(idx);

		// Round-trip must work
		DependencyMap loaded = DependencyMap.load(idx);
		assertEquals(original, loaded);

		// Now create a hand-crafted v1 file with an extra unknown section
		Path crafted = tempDir.resolve("v1-crafted-unknown.lz4");
		try (var fos = Files.newOutputStream(crafted);
				var lz4 = new net.jpountz.lz4.LZ4FrameOutputStream(fos);
				var out = new java.io.DataOutputStream(lz4)) {

			out.write(new byte[]{'T', 'O', 'R', 'D'}); // magic
			out.writeShort(1); // version

			out.writeInt(4); // sectionCount: trie + test_classes + dep_groups + unknown

			// Build a minimal trie with one entry
			var trie = new ClassNameTrie();
			trie.insert("com.example.FooTest");
			trie.insert("com.example.Foo");
			trie.assignIds();

			// Section 1: TRIE
			var trieBuf = new java.io.ByteArrayOutputStream();
			trie.writeTo(new java.io.DataOutputStream(trieBuf));
			byte[] trieBytes = trieBuf.toByteArray();
			out.writeShort(1); // SECTION_TRIE
			out.writeInt(trieBytes.length);
			out.write(trieBytes);

			// Section 2: TEST_CLASSES
			var tcBuf = new java.io.ByteArrayOutputStream();
			var tcOut = new java.io.DataOutputStream(tcBuf);
			tcOut.writeInt(1); // testCount
			tcOut.writeInt(trie.getId("com.example.FooTest"));
			tcOut.flush();
			byte[] tcBytes = tcBuf.toByteArray();
			out.writeShort(2); // SECTION_TEST_CLASSES
			out.writeInt(tcBytes.length);
			out.write(tcBytes);

			// Section 3: DEP_GROUPS
			var dgBuf = new java.io.ByteArrayOutputStream();
			var dgOut = new java.io.DataOutputStream(dgBuf);
			dgOut.writeInt(1); // groupCount
			var depBitmap = new org.roaringbitmap.RoaringBitmap();
			depBitmap.add(trie.getId("com.example.Foo"));
			depBitmap.runOptimize();
			int depSize = depBitmap.serializedSizeInBytes();
			dgOut.writeInt(depSize);
			depBitmap.serialize(dgOut);
			var memberBitmap = new org.roaringbitmap.RoaringBitmap();
			memberBitmap.add(0); // test index 0
			memberBitmap.runOptimize();
			int memberSize = memberBitmap.serializedSizeInBytes();
			dgOut.writeInt(memberSize);
			memberBitmap.serialize(dgOut);
			dgOut.flush();
			byte[] dgBytes = dgBuf.toByteArray();
			out.writeShort(3); // SECTION_DEP_GROUPS
			out.writeInt(dgBytes.length);
			out.write(dgBytes);

			// Section 4: UNKNOWN (type=999)
			byte[] unknownPayload = "future data here".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			out.writeShort(999);
			out.writeInt(unknownPayload.length);
			out.write(unknownPayload);
		}

		// Must load successfully, skipping the unknown section
		DependencyMap craftedLoaded = DependencyMap.load(crafted);
		assertEquals(1, craftedLoaded.size());
		assertEquals(Set.of("com.example.Foo"), craftedLoaded.get("com.example.FooTest"));
	}

	@Test
	void formatVersionIsAccessible() {
		// Verify the FORMAT_VERSION constant is exposed for ExportJsonOperation
		assertEquals(1, DependencyMap.FORMAT_VERSION);
	}

	@Test
	void getMethodDepsFallsBackToTopLevelForNestedClass() {
		DependencyMap map = new DependencyMap();
		map.putMethodDeps("com.example.OuterTest#testFoo", Set.of("app.Service", "app.Repo"));

		// Direct lookup works
		assertEquals(Set.of("app.Service", "app.Repo"), map.getMethodDeps("com.example.OuterTest", "testFoo"));
		// Nested class falls back to parent
		assertEquals(Set.of("app.Service", "app.Repo"), map.getMethodDeps("com.example.OuterTest$Inner", "testFoo"),
				"nested class should inherit parent's method deps");
		// Composite key overload also falls back
		assertEquals(Set.of("app.Service", "app.Repo"), map.getMethodDeps("com.example.OuterTest$Inner#testFoo"),
				"composite key fallback should work for nested classes");
		// Non-existent method returns empty
		assertEquals(Set.of(), map.getMethodDeps("com.example.OuterTest$Inner", "testBar"));
	}

	@Test
	void getMethodDepsPrefersNestedClassOwnData() {
		DependencyMap map = new DependencyMap();
		map.putMethodDeps("com.example.OuterTest#testFoo", Set.of("app.Service"));
		map.putMethodDeps("com.example.OuterTest$Inner#testFoo", Set.of("app.Controller"));

		// Should prefer nested class's own data
		assertEquals(Set.of("app.Controller"), map.getMethodDeps("com.example.OuterTest$Inner", "testFoo"));
		assertEquals(Set.of("app.Controller"), map.getMethodDeps("com.example.OuterTest$Inner#testFoo"));
	}

	@Test
	void changedClassesContainsHandlesNestedProductionClass() {
		Set<String> changed = Set.of("com.example.Service", "com.example.Repo");

		// Direct match
		assertTrue(DependencyMap.changedClassesContains(changed, "com.example.Service"));
		// Nested production class should match via parent
		assertTrue(DependencyMap.changedClassesContains(changed, "com.example.Service$Builder"));
		assertTrue(DependencyMap.changedClassesContains(changed, "com.example.Service$Inner$Deep"));
		// Non-matching
		assertFalse(DependencyMap.changedClassesContains(changed, "com.example.Other"));
		assertFalse(DependencyMap.changedClassesContains(changed, "com.example.Other$Inner"));
	}

	@Test
	void aggregateDepsFileWithCommentLinesIgnoresComments() throws IOException {
		Path depsDir = tempDir.resolve("deps-comments");
		Files.createDirectories(depsDir);
		// Comments (lines starting with #) should be ignored, not treated as class
		// names
		Files.writeString(depsDir.resolve("com.example.FooTest.deps"),
				"# this is a comment\ncom.example.Foo\n\n# another comment\ncom.example.Bar\n");

		DependencyMap map = DependencyMap.aggregate(depsDir);

		assertEquals(1, map.size());
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), map.get("com.example.FooTest"),
				"Comment lines should not appear as dependencies");
	}

	@Test
	void moduleMapRoundtripsThroughSaveAndLoad() throws IOException {
		DependencyMap original = new DependencyMap();
		original.put("com.example.AlphaTest", Set.of("com.example.Alpha"));
		original.put("com.example.BetaTest", Set.of("com.example.Beta"));
		original.putModule("com.example.AlphaTest", "g:alpha-mod");
		original.putModule("com.example.BetaTest", "g:beta-mod");

		Path indexFile = tempDir.resolve("with-modules.lz4");
		original.save(indexFile);

		DependencyMap loaded = DependencyMap.load(indexFile);
		assertTrue(loaded.hasModuleMap(), "module map should survive round-trip");
		assertEquals("g:alpha-mod", loaded.getModule("com.example.AlphaTest"));
		assertEquals("g:beta-mod", loaded.getModule("com.example.BetaTest"));
		assertNull(loaded.getModule("com.example.UnknownTest"));
	}

	@Test
	void memberDepsRoundTrip() throws IOException {
		DependencyMap original = new DependencyMap();
		original.put("com.example.FooTest", Set.of("com.example.Foo"));
		original.putMemberDeps("com.example.FooTest", Set.of("com.example.Foo#field1", "com.example.Foo#method1"));
		original.putMethodMemberDeps("com.example.FooTest#testX",
				Set.of("com.example.Foo#field1", "com.example.Bar#init"));

		Path indexFile = tempDir.resolve("member-test.lz4");
		original.save(indexFile);

		DependencyMap loaded = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.Foo#field1", "com.example.Foo#method1"),
				loaded.getMemberDeps("com.example.FooTest"));
		assertEquals(Set.of("com.example.Foo#field1", "com.example.Bar#init"),
				loaded.getMethodMemberDeps("com.example.FooTest", "testX"));
	}

	@Test
	void aggregateReadsModuleIdSidecar() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);
		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.Foo\n");
		Files.writeString(depsDir.resolve("com.example.BarTest.deps"), "com.example.Bar\n");
		Files.writeString(depsDir.resolve("module.id"), "g:my-module\n");

		DependencyMap map = DependencyMap.aggregate(depsDir);
		assertEquals("g:my-module", map.getModule("com.example.FooTest"));
		assertEquals("g:my-module", map.getModule("com.example.BarTest"));
		assertTrue(map.hasModuleMap());
	}

	@Test
	void aggregateWithoutSidecarLeavesModuleMapEmpty() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);
		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.Foo\n");

		DependencyMap map = DependencyMap.aggregate(depsDir);
		assertFalse(map.hasModuleMap());
		assertNull(map.getModule("com.example.FooTest"));
	}

	@Test
	void mergeWith_unionsClassDepsAndPreservesModuleMap() {
		DependencyMap a = new DependencyMap();
		a.put("com.example.TestA", Set.of("com.example.Prod1"));
		a.putModule("com.example.TestA", "g:mod-a");

		DependencyMap b = new DependencyMap();
		b.put("com.example.TestA", Set.of("com.example.Prod2"));
		b.put("com.example.TestB", Set.of("com.example.Prod3"));
		b.putModule("com.example.TestB", "g:mod-b");

		a.mergeWith(b);

		assertEquals(Set.of("com.example.Prod1", "com.example.Prod2"), a.get("com.example.TestA"),
				"class deps must be unioned");
		assertEquals(Set.of("com.example.Prod3"), a.get("com.example.TestB"), "new test from b must appear");
		assertTrue(a.hasModuleMap(), "module map must survive mergeWith");
		assertEquals("g:mod-a", a.getModule("com.example.TestA"));
		assertEquals("g:mod-b", a.getModule("com.example.TestB"));
	}

	@Test
	void mergeWith_invalidatesInvertedIndexCache() {
		DependencyMap a = new DependencyMap();
		a.put("com.example.TestA", Set.of("com.example.Prod1"));

		// Warm the inverted index cache
		a.getAffectedTests(Set.of("com.example.Prod1"));

		DependencyMap b = new DependencyMap();
		b.put("com.example.TestB", Set.of("com.example.Prod1"));

		a.mergeWith(b);

		Set<String> affected = a.getAffectedTests(Set.of("com.example.Prod1"));
		assertTrue(affected.contains("com.example.TestA"), "original test must still be affected");
		assertTrue(affected.contains("com.example.TestB"),
				"newly merged test must be affected after cache invalidation");
	}

	@Test
	void idf_notNegativeWhenTestDependsOnMultipleInnerClassesOfSameParent() {
		// A test that depends on both Foo$Bar and Foo$Baz should give df("Foo") == 1,
		// not 2. Before the fix, df.merge was called once per inner-class dep, so
		// df["Foo"] could exceed n (number of test classes), yielding idf < 0.
		DependencyMap map = new DependencyMap();
		// 3 tests, each with 2 inner-class deps sharing the same top-level parent
		map.put("com.example.TestA", Set.of("app.Outer$Inner1", "app.Outer$Inner2"));
		map.put("com.example.TestB", Set.of("app.Outer$Inner1", "app.Other$X"));
		map.put("com.example.TestC", Set.of("app.Other$X", "app.Other$Y"));

		// df("app.Outer") should be 2 (TestA + TestB), not 3 (TestA counted twice +
		// TestB)
		assertEquals(2, map.documentFrequencies().get("app.Outer"),
				"df for top-level parent must count test classes, not dep occurrences");
		// idf must be non-negative: ln(3/2) ≈ 0.41
		assertTrue(map.idf("app.Outer") >= 0.0, "idf must not be negative when df <= n");
	}

	// ── Regression: BUG-122 — filterForModule must preserve member-level data ──

	@Test
	void filterForModule_preservesMemberDepsForKeptTests() {
		DependencyMap map = new DependencyMap();

		// Two tests in different modules
		map.put("com.example.AlphaTest", Set.of("com.example.Alpha"));
		map.putMemberDeps("com.example.AlphaTest", Set.of("com.example.Alpha#doWork", "com.example.Alpha#field"));
		map.putMethodMemberDeps("com.example.AlphaTest#testA", Set.of("com.example.Alpha#doWork"));
		map.putModule("com.example.AlphaTest", "g:module-alpha");

		map.put("com.example.BetaTest", Set.of("com.example.Beta"));
		map.putMemberDeps("com.example.BetaTest", Set.of("com.example.Beta#run"));
		map.putModule("com.example.BetaTest", "g:module-beta");

		DependencyMap filtered = map.filterForModule("g:module-alpha", null);

		// hasMemberDeps() must be true — before BUG-122 fix it returned false
		assertTrue(filtered.hasMemberDeps(), "filterForModule must preserve member-dep data");

		// Class-level member deps for kept test must be present and correct
		assertEquals(Set.of("com.example.Alpha#doWork", "com.example.Alpha#field"),
				filtered.getMemberDeps("com.example.AlphaTest"),
				"class-level member deps for kept test must survive filterForModule");

		// Method-level member deps must also be preserved
		assertEquals(Set.of("com.example.Alpha#doWork"), filtered.getMethodMemberDeps("com.example.AlphaTest#testA"),
				"method-level member deps for kept test must survive filterForModule");

		// The excluded test must not appear in class dependencies
		assertFalse(filtered.testClasses().contains("com.example.BetaTest"),
				"excluded test must not appear in filtered map's test classes");
	}

	// ── Ubiquitous-dep compression (section 9) ────────────────────────────────

	@Test
	void ubiquitousDeps_strippedFromBitmapsAndRestoredAfterRoundTrip(@TempDir Path tempDir) throws IOException {
		DependencyMap map = new DependencyMap();
		// 10 tests: all share "ubiq.ClassUtil" and "ubiq.ObjectMapper" (100% = ≥ 90%)
		// "rare.Helper" appears in only 5 of 10 tests (50% < 90%)
		for (int i = 0; i < 10; i++) {
			Set<String> deps = new java.util.HashSet<>(Set.of("ubiq.ClassUtil", "ubiq.ObjectMapper"));
			if (i < 5)
				deps.add("rare.Helper");
			map.put("com.test.Test" + i, deps);
		}

		Path indexFile = tempDir.resolve("test.idx");
		map.save(indexFile);

		DependencyMap loaded = DependencyMap.load(indexFile);

		// All original deps must be present in loaded map (round-trip correct)
		for (int i = 0; i < 10; i++) {
			Set<String> deps = loaded.get("com.test.Test" + i);
			assertTrue(deps.contains("ubiq.ClassUtil"), "ClassUtil must be restored for Test" + i);
			assertTrue(deps.contains("ubiq.ObjectMapper"), "ObjectMapper must be restored for Test" + i);
			if (i < 5)
				assertTrue(deps.contains("rare.Helper"), "rare.Helper must be present for Test" + i);
			else
				assertFalse(deps.contains("rare.Helper"), "rare.Helper must not appear for Test" + i);
		}

		// getAffectedTests for a ubiquitous dep must return all 10 tests
		Set<String> all = loaded.getAffectedTests(Set.of("ubiq.ClassUtil"));
		assertEquals(10, all.size(), "getAffectedTests for ubiquitous dep must return all tests");

		// getAffectedTests for a rare dep must return only the 5 tests
		Set<String> rare = loaded.getAffectedTests(Set.of("rare.Helper"));
		assertEquals(5, rare.size(), "getAffectedTests for rare dep must return only the 5 tests that reference it");
	}

	@Test
	void filterForModuleDoesNotLeakMemberDictionary() {
		// BUG-204: filterForModule copied the full memberKeyDictionary to the filtered
		// result, so trackedMembersPerClass() on the filtered map over-reported member
		// counts for excluded tests.
		DependencyMap map = new DependencyMap();
		map.put("com.moduleA.FooTest", Set.of("com.app.Foo"));
		map.put("com.moduleB.BarTest", Set.of("com.app.Bar"));
		// moduleA test tracks 2 members in app.Foo
		map.putMemberDeps("com.moduleA.FooTest", Set.of("com.app.Foo#methodA", "com.app.Foo#methodB"));
		// moduleB test tracks 3 members in app.Bar
		map.putMemberDeps("com.moduleB.BarTest",
				Set.of("com.app.Bar#methodX", "com.app.Bar#methodY", "com.app.Bar#methodZ"));
		map.putModule("com.moduleA.FooTest", "com.example:module-a");
		map.putModule("com.moduleB.BarTest", "com.example:module-b");

		// Filter to module-a only
		DependencyMap filteredA = map.filterForModule("com.example:module-a", null);
		assertEquals(1, filteredA.testClasses().size(), "only module-a tests should be present");
		Map<String, Integer> countsA = filteredA.trackedMembersPerClass();
		// Must report only module-a's 2 members in com.app.Foo, NOT module-b's 3
		assertEquals(1, countsA.size(), "only one class should appear in tracked members for module-a");
		assertEquals(2, countsA.get("com.app.Foo"), "module-a tracks 2 members in Foo");
		assertNull(countsA.get("com.app.Bar"), "Bar members must not leak into module-a's view (BUG-204)");

		// Filter to module-b only
		DependencyMap filteredB = map.filterForModule("com.example:module-b", null);
		assertEquals(1, filteredB.testClasses().size(), "only module-b tests should be present");
		Map<String, Integer> countsB = filteredB.trackedMembersPerClass();
		assertEquals(1, countsB.size(), "only one class should appear in tracked members for module-b");
		assertEquals(3, countsB.get("com.app.Bar"), "module-b tracks 3 members in Bar");
		assertNull(countsB.get("com.app.Foo"), "Foo members must not leak into module-b's view (BUG-204)");

		// Full map tracks both
		Map<String, Integer> countsFull = map.trackedMembersPerClass();
		assertEquals(2, countsFull.size(), "full map sees both classes");
		assertEquals(2, countsFull.get("com.app.Foo"), "Foo has 2 tracked members in full map");
		assertEquals(3, countsFull.get("com.app.Bar"), "Bar has 3 tracked members in full map");
	}

	@Test
	void filterForModuleReturnsAllWhenNoMatchInLegacyDashSeparatedMap() {
		// Dep maps built before the ":" separator change stamp tests with
		// "groupId-artifactId" (dash). filterForModule returns empty when no test
		// matches the new colon-separated module ID — the caller (AffectedWorkflow)
		// checks for empty and falls back to the full map instead of silently
		// producing an empty selection.
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Foo"));
		map.put("com.example.BarTest", Set.of("com.example.Bar"));
		// Simulate old dash-separator stamp for all tests
		map.putModule("com.example.FooTest", "com.example-my-module");
		map.putModule("com.example.BarTest", "com.example-my-module");

		// New colon-separator module ID that the plugin now computes
		DependencyMap filtered = map.filterForModule("com.example:my-module", null);

		// Expected: empty — no tests have the new-format ID
		assertEquals(0, filtered.testClasses().size(),
				"filterForModule with mismatched module ID must return empty map");
	}
}
