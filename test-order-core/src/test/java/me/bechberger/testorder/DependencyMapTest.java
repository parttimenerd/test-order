package me.bechberger.testorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

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
    void saveTextAndLoadRoundTrip() throws IOException {
        DependencyMap original = new DependencyMap();
        original.put("com.example.FooTest", Set.of("com.example.Foo", "com.example.Bar"));
        original.put("com.example.BarTest", Set.of("com.example.Bar", "com.example.Baz"));

        Path indexFile = tempDir.resolve("test.idx");
        original.saveText(indexFile);

        DependencyMap loaded = DependencyMap.load(indexFile);
        assertEquals(original, loaded);
    }

    @Test
    void v2BinarySmallerThanV1Text() throws IOException {
        DependencyMap map = new DependencyMap();
        // create data with lots of prefix redundancy
        for (int i = 0; i < 20; i++) {
            Set<String> deps = new TreeSet<>();
            for (int j = 0; j < 50; j++) {
                deps.add("org.springframework.samples.petclinic.service.Class" + j);
            }
            map.put("org.springframework.samples.petclinic.test.TestClass" + i, deps);
        }

        Path v1 = tempDir.resolve("v1.idx");
        Path v2 = tempDir.resolve("v2.idx");
        map.saveText(v1);
        map.save(v2);

        long v1Size = Files.size(v1);
        long v2Size = Files.size(v2);
        assertTrue(v2Size < v1Size,
                "V2 (" + v2Size + " bytes) should be smaller than V1 (" + v1Size + " bytes)");
    }

    @Test
    void v2RowDeduplication() throws IOException {
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
    void v2EmptyDeps() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put("com.example.EmptyTest", Set.of());

        Path idx = tempDir.resolve("empty.idx");
        map.save(idx);

        DependencyMap loaded = DependencyMap.load(idx);
        assertEquals(1, loaded.size());
        assertTrue(loaded.get("com.example.EmptyTest").isEmpty());
    }

    @Test
    void v2PreservesInsertionOrder() throws IOException {
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
    void loadAutoDetectsV1() throws IOException {
        Path indexFile = tempDir.resolve("test.idx");
        Files.writeString(indexFile, "# test-order dependency index v1\ncom.example.FooTest\tcom.example.Foo\n");
        DependencyMap map = DependencyMap.load(indexFile);
        assertEquals(1, map.size());
        assertEquals(Set.of("com.example.Foo"), map.get("com.example.FooTest"));
    }

    @Test
    void aggregateDepsFiles() throws IOException {
        Path depsDir = tempDir.resolve("deps");
        Files.createDirectories(depsDir);

        Files.writeString(depsDir.resolve("com.example.FooTest.deps"),
                "com.example.Foo\ncom.example.Bar\n");
        Files.writeString(depsDir.resolve("com.example.BarTest.deps"),
                "com.example.Bar\ncom.example.Baz\n");

        DependencyMap map = DependencyMap.aggregate(depsDir);
        assertEquals(2, map.size());
        assertEquals(Set.of("com.example.Foo", "com.example.Bar"), map.get("com.example.FooTest"));
        assertEquals(Set.of("com.example.Bar", "com.example.Baz"), map.get("com.example.BarTest"));
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
        // test class with no dependencies (V1 text format)
        Path indexFile = tempDir.resolve("test.idx");
        Files.writeString(indexFile, "# test-order dependency index v1\ncom.example.EmptyTest\t\n");
        DependencyMap map = DependencyMap.load(indexFile);
        assertEquals(1, map.size());
        assertTrue(map.get("com.example.EmptyTest").isEmpty());
    }

    @Test
    void loadSkipsBlankLines() throws IOException {
        Path indexFile = tempDir.resolve("test.idx");
        Files.writeString(indexFile,
                "# test-order dependency index v1\n\ncom.example.FooTest\tcom.example.Foo\n\n");
        DependencyMap map = DependencyMap.load(indexFile);
        assertEquals(1, map.size());
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
        assertThrows(UnsupportedOperationException.class, () ->
                map.testClasses().add("Test2"));
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
        Files.writeString(depsDir.resolve("com.example.FooTest.deps"),
                "  com.example.Foo  \n  com.example.Bar  \n");

        DependencyMap map = DependencyMap.aggregate(depsDir);
        assertEquals(Set.of("com.example.Foo", "com.example.Bar"),
                map.get("com.example.FooTest"));
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
    void v2EmptyMap() throws IOException {
        DependencyMap map = new DependencyMap();
        Path idx = tempDir.resolve("empty.idx");
        map.save(idx);

        DependencyMap loaded = DependencyMap.load(idx);
        assertEquals(0, loaded.size());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Regression: aggregate on empty dir returns size==0
    //  (BUG_REPORT_2 #4: callers should check size before overwriting)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void aggregateEmptyDirReturnsSizeZero() throws IOException {
        Path emptyDir = tempDir.resolve("no-deps");
        Files.createDirectories(emptyDir);

        DependencyMap map = DependencyMap.aggregate(emptyDir);
        assertEquals(0, map.size(),
                "Aggregating an empty directory should return a map with size 0");
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
}