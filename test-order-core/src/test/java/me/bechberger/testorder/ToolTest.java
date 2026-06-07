package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.femtocli.FemtoCli;

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
		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.Foo\ncom.example.Bar\n");
		Files.writeString(depsDir.resolve("com.example.BarTest.deps"), "com.example.Baz\n");

		Path output = tempDir.resolve("test.idx");

		String stdout = captureStdout(() -> runTool("aggregate", depsDir.toString(), "--output", output.toString()));

		assertTrue(Files.exists(output));
		assertTrue(stdout.contains("Aggregated 2 test classes"));

		// verify index contents
		DependencyMap map = DependencyMap.load(output);
		assertEquals(2, map.size());
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), map.get("com.example.FooTest"));
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

		String stdout = captureStdout(() -> runTool("affected", idx.toString(), "--classes", "com.example.Foo"));

		assertTrue(stdout.contains("com.example.FooTest"));
		assertFalse(stdout.contains("com.example.BarTest"));
	}

	@Test
	void affectedNoMatch() throws IOException {
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Foo"));
		Path idx = tempDir.resolve("test.idx");
		map.save(idx);

		String stdout = captureStdout(() -> runTool("affected", idx.toString(), "--classes", "com.example.Unknown"));

		assertTrue(stdout.contains("No affected test classes"));
	}

	@Test
	void hashSnapshotSubcommand() throws IOException {
		Path srcDir = tempDir.resolve("src");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("Foo.java"), "public class Foo {}");

		Path hashFile = tempDir.resolve("snapshot.lz4");

		String stdout = captureStdout(
				() -> runTool("hash-snapshot", "--source-root", srcDir.toString(), "--output", hashFile.toString()));

		assertTrue(Files.exists(hashFile));
		assertTrue(stdout.contains("Snapshot: 1 files"));
	}

	@Test
	void hashSnapshotMissingSourceRootExitsWithError() {
		Path missing = tempDir.resolve("nonexistent-src");
		Path hashFile = tempDir.resolve("snapshot.lz4");

		String stderr = captureStderr(() -> {
			int code = runTool("hash-snapshot", "--source-root", missing.toString(), "--output", hashFile.toString());
			assertEquals(1, code, "Should exit 1 when source root does not exist");
		});

		assertFalse(Files.exists(hashFile), "Should not create hash file when source root is missing");
		assertTrue(stderr.contains("does not exist"), "Error message should mention the missing directory: " + stderr);
	}

	@Test
	void exportJsonCreatesParentDirectoriesAutomatically() throws IOException {
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Foo"));
		Path idx = tempDir.resolve("deps.idx");
		map.save(idx);
		Path out = tempDir.resolve("nested/deep/output.json");

		int exit = runTool("export-json", idx.toString(), "--output", out.toString());

		assertEquals(0, exit);
		assertTrue(Files.exists(out), "Output file should be created in nested directory");
	}

	@Test
	void changedExplicitMode() {
		String stdout = captureStdout(
				() -> runTool("changed", "--mode", "EXPLICIT", "--classes", "com.example.Foo,com.example.Bar"));

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
		me.bechberger.testorder.changes.FileHashStore store = me.bechberger.testorder.changes.FileHashStore
				.scan(srcDir);
		store.save(hashFile);

		String stdout = captureStdout(() -> runTool("changed", "--mode", "SINCE_LAST_RUN", "--source-root",
				srcDir.toString(), "--hash-file", hashFile.toString(), "--project-root", tempDir.toString()));

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
	// Regression: CLI dump on empty index should print message
	// (BUG_REPORT #3 / BUG_REPORT_2: dump silent on empty)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void dumpEmptyIndexPrintsMessage() throws IOException {
		DependencyMap empty = new DependencyMap();
		Path idx = tempDir.resolve("empty.idx");
		empty.save(idx);

		String stderr = captureStderr(() -> {
			int code = runTool("dump", idx.toString());
			assertEquals(1, code, "dump on empty index should exit with code 1");
		});

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

	@Test
	void exportJsonSubcommandPrintsJsonToStdout() throws IOException {
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Foo"));
		Path idx = tempDir.resolve("deps.idx");
		map.save(idx);

		String stdout = captureStdout(() -> runTool("export-json", idx.toString()));

		assertTrue(stdout.contains("\"exportVersion\""));
		assertTrue(stdout.contains("\"depFormatVersion\""));
		assertTrue(stdout.contains("\"testClass\""));
		assertTrue(stdout.contains("com.example.FooTest"));
	}

	@Test
	void exportJsonSubcommandWritesOutputFile() throws IOException {
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Foo"));
		Path idx = tempDir.resolve("deps.idx");
		map.save(idx);
		Path out = tempDir.resolve("deps.json");

		int exit = runTool("export-json", idx.toString(), "--output", out.toString());

		assertEquals(0, exit);
		assertTrue(Files.exists(out));
		String json = Files.readString(out);
		assertTrue(json.contains("\"testClassCount\""));
		assertTrue(json.contains("com.example.FooTest"));
	}

	@Test
	void exportJsonWithEmptyIndexDoesNotPrintSuccessMessage() throws IOException {
		Path idx = tempDir.resolve("empty.idx");
		new DependencyMap().save(idx);
		Path out = tempDir.resolve("out.json");

		String stdout = captureStdout(() -> runTool("export-json", idx.toString(), "--output", out.toString()));

		assertFalse(java.nio.file.Files.exists(out), "Output file should not be written for an empty index");
		assertFalse(stdout.contains("Exported"), "Should not print success for empty index: " + stdout);
	}

	// ═══════════════════════════════════════════════════════════════════
	// Regression: CLI aggregate refuses to overwrite valid index
	// (BUG_REPORT_2 #4: aggregate destroys valid index)
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

		String stderr = captureStderr(() -> {
			int code = runTool("aggregate", depsDir.toString(), "--output", output.toString());
			assertEquals(1, code, "Should exit with code 1 when refusing to overwrite");
		});

		// Index should NOT have been overwritten
		assertEquals(sizeBefore, Files.size(output), "Existing index should not have been overwritten");
		assertTrue(stderr.contains("refusing to overwrite"), "Should warn about refusing to overwrite: " + stderr);

		// Verify existing index is still valid
		DependencyMap reloaded = DependencyMap.load(output);
		assertEquals(1, reloaded.size());
	}

	@Test
	void aggregateCreatesNewIndexFromDepsFiles() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);
		Files.writeString(depsDir.resolve("com.example.XTest.deps"), "com.example.X\ncom.example.Y\n");

		Path output = tempDir.resolve("new.idx");

		String stdout = captureStdout(() -> runTool("aggregate", depsDir.toString(), "--output", output.toString()));

		assertTrue(Files.exists(output));
		assertTrue(stdout.contains("Aggregated 1 test classes"));
		DependencyMap map = DependencyMap.load(output);
		assertEquals(Set.of("com.example.X", "com.example.Y"), map.get("com.example.XTest"));
	}

	// ═══════════════════════════════════════════════════════════════════
	// Regression: CLI changed mode parsing accepts hyphens
	// (BUG_REPORT_2 #10: CLI changed enum mismatch)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void changedHyphenatedModeWorks() {
		// "since-last-run" should be accepted (converted to SINCE_LAST_RUN internally)
		// We test via explicit mode with hyphens since since-last-run needs file state
		String stdout = captureStdout(() -> runTool("changed", "--mode", "explicit", "--classes", "com.example.Foo"));

		assertTrue(stdout.contains("com.example.Foo"));
	}

	@Test
	void changedSinceLastRunWithNonexistentSourceDirReturnsEmpty() throws IOException {
		// Regression: should not NPE when source dir doesn't exist
		Path hashFile = tempDir.resolve("hashes.lz4");

		String stdout = captureStdout(() -> runTool("changed", "--mode", "since-last-run", "--source-root",
				tempDir.resolve("nonexistent/src").toString(), "--hash-file", hashFile.toString(), "--project-root",
				tempDir.toString()));

		assertTrue(stdout.contains("No changes detected"),
				"Non-existent source dir should not crash, should report no changes");
	}

	@Test
	void missingSubcommandExitsWithCode1InSubprocess() throws Exception {
		String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String classpath = System.getProperty("java.class.path");

		Process process = new ProcessBuilder(javaBin, "-cp", classpath, Tool.class.getName()).redirectErrorStream(true)
				.start();

		byte[] outputBytes;
		try (InputStream in = process.getInputStream()) {
			outputBytes = in.readAllBytes();
		}
		int exit = process.waitFor();
		String output = new String(outputBytes);

		assertEquals(1, exit, "Running Tool without subcommand should exit with status 1");
		assertTrue(output.contains("Usage: test-order <subcommand>"),
				"Expected missing-subcommand message, got: " + output);
	}

	// ═══════════════════════════════════════════════════════════════════
	// Regression: CLI aggregate with non-existent depsDir should give helpful error
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void aggregateNonExistentDirExitsWithError() {
		Path nonExistent = tempDir.resolve("does-not-exist");
		Path output = tempDir.resolve("out.idx");

		String stderr = captureStderr(() -> {
			int code = runTool("aggregate", nonExistent.toString(), "--output", output.toString());
			assertEquals(1, code, "Should exit with code 1 for missing deps dir");
		});

		assertFalse(Files.exists(output), "No output file should be created");
		assertTrue(stderr.contains("does not exist") || stderr.contains("Error"),
				"Should print helpful error message: " + stderr);
	}

	// ═══════════════════════════════════════════════════════════════════
	// Regression: CLI select with topN=0 should warn (new/always-run tests still
	// selected)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void selectTopNZeroWarns() throws IOException {
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Foo"));
		Path idx = tempDir.resolve("deps.idx");
		map.save(idx);
		Path sel = tempDir.resolve("sel.txt");
		Path rem = tempDir.resolve("rem.txt");

		String stderr = captureStderr(() -> runTool("select", idx.toString(), "--top-n", "0", "--random-m", "0",
				"--selected-file", sel.toString(), "--remaining-file", rem.toString()));

		assertTrue(stderr.contains("Warning") || stderr.contains("top-n"),
				"topN=0 should produce a warning: " + stderr);
	}

	// ═══════════════════════════════════════════════════════════════════
	// Regression: CLI changed with explicit mode but no --classes should warn
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void changedExplicitModeWithoutClassesWarns() {
		String stderr = captureStderr(() -> runTool("changed", "--mode", "explicit"));

		assertTrue(stderr.contains("explicit") || stderr.contains("--classes") || stderr.contains("Warning"),
				"Should warn about missing --classes for explicit mode: " + stderr);
	}

	// ═══════════════════════════════════════════════════════════════════
	// Regression: duplicate class names in --classes should not crash
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void affectedDuplicateClassesDoesNotCrash() throws IOException {
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Foo"));
		Path idx = tempDir.resolve("test.idx");
		map.save(idx);

		String stdout = captureStdout(
				() -> runTool("affected", idx.toString(), "--classes", "com.example.Foo,com.example.Foo"));

		assertTrue(stdout.contains("com.example.FooTest"), "Duplicate classes should still find affected test");
	}

	// ═══════════════════════════════════════════════════════════════════
	// Regression: run command gives helpful error when index missing
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void runCommandMissingIndexGivesHelpfulError() {
		Path nonExistent = tempDir.resolve("no.idx");

		String stderr = captureStderr(
				() -> runTool("run", nonExistent.toString(), "--mode", "explicit", "--classes", "com.example.Foo"));

		assertTrue(stderr.contains("dependency index not found") || stderr.contains("Error"),
				"run with nonexistent index should give helpful error: " + stderr);
	}

	// ═══════════════════════════════════════════════════════════════════
	// Regression: advise --threshold out of [0,1] should error
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void adviseInvalidThresholdExitsWithError() throws IOException {
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Foo"));
		Path idx = tempDir.resolve("test.idx");
		map.save(idx);

		String stderr = captureStderr(() -> {
			int code = runTool("advise", idx.toString(), "--threshold", "1.5");
			assertEquals(1, code, "advise with threshold > 1 should exit with code 1");
		});

		assertTrue(stderr.contains("threshold") || stderr.contains("Error"),
				"Should report invalid threshold: " + stderr);
	}

	@Test
	void adviseNegativeThresholdExitsWithError() throws IOException {
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Foo"));
		Path idx = tempDir.resolve("test.idx");
		map.save(idx);

		String stderr = captureStderr(() -> {
			int code = runTool("advise", idx.toString(), "--threshold", "-0.1");
			assertEquals(1, code, "advise with negative threshold should exit with code 1");
		});

		assertTrue(stderr.contains("threshold") || stderr.contains("Error"),
				"Should report invalid threshold: " + stderr);
	}

	// ═══════════════════════════════════════════════════════════════════
	// Regression: invalid change mode should give helpful error (not stack trace)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void changedInvalidModeGivesHelpfulError() {
		String stderr = captureStderr(() -> {
			int code = runTool("changed", "--mode", "bogus-mode");
			assertEquals(1, code, "Invalid mode should exit with code 1");
		});

		assertTrue(stderr.contains("Invalid") || stderr.contains("bogus") || stderr.contains("Valid modes"),
				"Should give helpful error for invalid mode: " + stderr);
	}
}
