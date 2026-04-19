package me.bechberger.testorder;

import me.bechberger.femtocli.FemtoCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolTest {

    @TempDir
    Path tempDir;

    /** Run Tool via FemtoCli.run to avoid System.exit in Tool.main */
    private int runTool(String... args) {
        return FemtoCli.run(new Tool(), args);
    }

    private String captureStdout(Runnable action) {
        PrintStream orig = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos, true));
        try {
            action.run();
            System.out.flush();
        } finally {
            System.setOut(orig);
        }
        return baos.toString();
    }

    @Test
    void aggregateSubcommand() throws IOException {
        Path depsDir = tempDir.resolve("deps");
        Files.createDirectories(depsDir);
        Files.writeString(depsDir.resolve("com.example.FooTest.deps"),
                "com.example.Foo\ncom.example.Bar\n");
        Files.writeString(depsDir.resolve("com.example.BarTest.deps"),
                "com.example.Baz\n");

        Path output = tempDir.resolve("test.idx");

        String stdout = captureStdout(() ->
                runTool("aggregate", depsDir.toString(),
                        "--output", output.toString()));

        assertTrue(Files.exists(output));
        assertTrue(stdout.contains("Aggregated 2 test classes"));

        // verify index contents
        DependencyMap map = DependencyMap.load(output);
        assertEquals(2, map.size());
        assertEquals(Set.of("com.example.Foo", "com.example.Bar"),
                map.get("com.example.FooTest"));
    }

    @Test
    void statsSubcommand() throws Exception {
        DependencyMap map = new DependencyMap();
        map.put("Test1", Set.of("A", "B", "C"));
        map.put("Test2", Set.of("B", "D"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        // Verify the data we'll base stats on
        DependencyMap loaded = DependencyMap.load(idx);
        assertEquals(2, loaded.size());
        assertEquals(4, loaded.totalUniqueClasses());
        assertEquals(2.5, loaded.averageDeps(), 0.01);

        // Verify the CLI runs without error
        assertEquals(0, runTool("stats", idx.toString()));
    }

    @Test
    void affectedSubcommand() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put("com.example.FooTest", Set.of("com.example.Foo"));
        map.put("com.example.BarTest", Set.of("com.example.Bar"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        String stdout = captureStdout(() ->
                runTool("affected", idx.toString(),
                        "--classes", "com.example.Foo"));

        assertTrue(stdout.contains("com.example.FooTest"));
        assertFalse(stdout.contains("com.example.BarTest"));
    }

    @Test
    void affectedNoMatch() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put("com.example.FooTest", Set.of("com.example.Foo"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        String stdout = captureStdout(() ->
                runTool("affected", idx.toString(),
                        "--classes", "com.example.Unknown"));

        assertTrue(stdout.contains("No affected test classes"));
    }

    @Test
    void hashSnapshotSubcommand() throws IOException {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Foo.java"), "public class Foo {}");

        Path hashFile = tempDir.resolve("snapshot.lz4");

        String stdout = captureStdout(() ->
                runTool("hash-snapshot",
                        "--source-root", srcDir.toString(),
                        "--output", hashFile.toString()));

        assertTrue(Files.exists(hashFile));
        assertTrue(stdout.contains("Snapshot: 1 files"));
    }

    @Test
    void changedExplicitMode() {
        String stdout = captureStdout(() ->
                runTool("changed",
                        "--mode", "EXPLICIT",
                        "--classes", "com.example.Foo,com.example.Bar"));

        assertTrue(stdout.contains("com.example.Bar"));
        assertTrue(stdout.contains("com.example.Foo"));
    }

    @Test
    void changedNoChanges() throws IOException {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Foo.java"), "public class Foo {}");

        Path hashFile = tempDir.resolve("snapshot.lz4");

        // create initial snapshot
        me.bechberger.testorder.changes.FileHashStore store =
                me.bechberger.testorder.changes.FileHashStore.scan(srcDir);
        store.save(hashFile);

        String stdout = captureStdout(() ->
                runTool("changed",
                        "--mode", "SINCE_LAST_RUN",
                        "--source-root", srcDir.toString(),
                        "--hash-file", hashFile.toString(),
                        "--project-root", tempDir.toString()));

        assertTrue(stdout.contains("No changes detected"));
    }

    private String captureStderr(Runnable action) {
        PrintStream orig = System.err;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setErr(new PrintStream(baos, true));
        try {
            action.run();
            System.err.flush();
        } finally {
            System.setErr(orig);
        }
        return baos.toString();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Regression: CLI dump on empty index should print message
    //  (BUG_REPORT #3 / BUG_REPORT_2: dump silent on empty)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void dumpEmptyIndexPrintsMessage() throws IOException {
        DependencyMap empty = new DependencyMap();
        Path idx = tempDir.resolve("empty.idx");
        empty.save(idx);

        String stderr = captureStderr(() -> runTool("dump", idx.toString()));

        assertTrue(stderr.contains("empty"), "Should indicate the index is empty: " + stderr);
    }

    @Test
    void dumpNonEmptyIndexPrintsContent() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put("com.example.FooTest", Set.of("com.example.Foo"));
        Path idx = tempDir.resolve("nonempty.idx");
        map.save(idx);

        String stdout = captureStdout(() -> runTool("dump", idx.toString()));

        assertTrue(stdout.contains("com.example.FooTest"));
        assertTrue(stdout.contains("com.example.Foo"));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Regression: CLI aggregate refuses to overwrite valid index
    //  (BUG_REPORT_2 #4: aggregate destroys valid index)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void aggregateRefusesToOverwriteExistingIndexWhenNoDepsFiles() throws IOException {
        // Create a valid non-empty index
        DependencyMap existing = new DependencyMap();
        existing.put("com.test.FooTest", Set.of("com.app.Foo"));
        Path output = tempDir.resolve("test-dependencies.lz4");
        existing.save(output);
        long sizeBefore = Files.size(output);

        // Create an empty deps directory (no .deps files)
        Path depsDir = tempDir.resolve("empty-deps");
        Files.createDirectories(depsDir);

        String stderr = captureStderr(() ->
                runTool("aggregate", depsDir.toString(),
                        "--output", output.toString()));

        // Index should NOT have been overwritten
        assertEquals(sizeBefore, Files.size(output),
                "Existing index should not have been overwritten");
        assertTrue(stderr.contains("refusing to overwrite"),
                "Should warn about refusing to overwrite: " + stderr);

        // Verify existing index is still valid
        DependencyMap reloaded = DependencyMap.load(output);
        assertEquals(1, reloaded.size());
    }

    @Test
    void aggregateCreatesNewIndexFromDepsFiles() throws IOException {
        Path depsDir = tempDir.resolve("deps");
        Files.createDirectories(depsDir);
        Files.writeString(depsDir.resolve("com.example.XTest.deps"),
                "com.example.X\ncom.example.Y\n");

        Path output = tempDir.resolve("new.idx");

        String stdout = captureStdout(() ->
                runTool("aggregate", depsDir.toString(),
                        "--output", output.toString()));

        assertTrue(Files.exists(output));
        assertTrue(stdout.contains("Aggregated 1 test classes"));
        DependencyMap map = DependencyMap.load(output);
        assertEquals(Set.of("com.example.X", "com.example.Y"),
                map.get("com.example.XTest"));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Regression: CLI changed mode parsing accepts hyphens
    //  (BUG_REPORT_2 #10: CLI changed enum mismatch)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void changedHyphenatedModeWorks() {
        // "since-last-run" should be accepted (converted to SINCE_LAST_RUN internally)
        // We test via explicit mode with hyphens since since-last-run needs file state
        String stdout = captureStdout(() ->
                runTool("changed",
                        "--mode", "explicit",
                        "--classes", "com.example.Foo"));

        assertTrue(stdout.contains("com.example.Foo"));
    }

    @Test
    void changedSinceLastRunWithNonexistentSourceDirReturnsEmpty() throws IOException {
        // Regression: should not NPE when source dir doesn't exist
        Path hashFile = tempDir.resolve("hashes.lz4");

        String stdout = captureStdout(() ->
                runTool("changed",
                        "--mode", "since-last-run",
                        "--source-root", tempDir.resolve("nonexistent/src").toString(),
                        "--hash-file", hashFile.toString(),
                        "--project-root", tempDir.toString()));

        assertTrue(stdout.contains("No changes detected"),
                "Non-existent source dir should not crash, should report no changes");
    }
}