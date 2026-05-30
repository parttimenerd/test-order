package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;

class AggregateOperationIncrementalTest {

	@TempDir
	Path tempDir;

	@Test
	void nonIncrementalReplacesExistingIndex() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);
		Path indexFile = tempDir.resolve("index.lz4");

		// First full run: FooTest + BarTest
		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.Foo\n");
		Files.writeString(depsDir.resolve("com.example.BarTest.deps"), "com.example.Bar\n");
		AggregateOperation.aggregate(depsDir, indexFile, PluginLog.NOOP, false);

		// Second run: only FooTest in deps dir (BarTest file removed)
		Files.delete(depsDir.resolve("com.example.BarTest.deps"));
		AggregateOperation.aggregate(depsDir, indexFile, PluginLog.NOOP, false);

		DependencyMap map = DependencyMap.load(indexFile);
		assertNotNull(map.get("com.example.FooTest"));
		// Non-incremental replaces entirely — only 1 test class remains
		assertEquals(1, map.size(), "Non-incremental should replace index with only current .deps files");
	}

	@Test
	void incrementalPreservesExistingEntriesNotInCurrentRun() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);
		Path indexFile = tempDir.resolve("index.lz4");

		// First full learn: FooTest + BarTest
		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.Foo\n");
		Files.writeString(depsDir.resolve("com.example.BarTest.deps"), "com.example.Bar\n");
		AggregateOperation.aggregate(depsDir, indexFile, PluginLog.NOOP, false);

		// Selective learn: only FooTest was re-instrumented (BarTest deps file removed)
		Files.delete(depsDir.resolve("com.example.BarTest.deps"));
		// FooTest now also covers com.example.Baz
		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.Foo\ncom.example.Baz\n");
		AggregateOperation.aggregate(depsDir, indexFile, PluginLog.NOOP, true);

		DependencyMap map = DependencyMap.load(indexFile);
		// FooTest has the updated (union) deps
		assertTrue(map.get("com.example.FooTest").contains("com.example.Foo"));
		assertTrue(map.get("com.example.FooTest").contains("com.example.Baz"));
		// BarTest entry is PRESERVED from the previous index (not deleted)
		assertEquals(2, map.size(), "Incremental should preserve BarTest from previous index");
		assertTrue(map.get("com.example.BarTest").contains("com.example.Bar"));
	}

	@Test
	void incrementalUnionsDepsForReInstrumentedTest() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);
		Path indexFile = tempDir.resolve("index.lz4");

		// First full learn: FooTest depends on A and B
		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.A\ncom.example.B\n");
		AggregateOperation.aggregate(depsDir, indexFile, PluginLog.NOOP, false);

		// Selective re-learn: FooTest now records C (A and B may or may not appear
		// again)
		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.C\n");
		AggregateOperation.aggregate(depsDir, indexFile, PluginLog.NOOP, true);

		DependencyMap map = DependencyMap.load(indexFile);
		Set<String> deps = map.get("com.example.FooTest");
		// Union: all three classes present
		assertTrue(deps.contains("com.example.A"), "A from first run should be retained");
		assertTrue(deps.contains("com.example.B"), "B from first run should be retained");
		assertTrue(deps.contains("com.example.C"), "C from second run should be added");
	}

	@Test
	void incrementalWithNoDepsFilesIsNoOp() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);
		Path indexFile = tempDir.resolve("index.lz4");

		// Seed the index with one entry
		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.Foo\n");
		AggregateOperation.aggregate(depsDir, indexFile, PluginLog.NOOP, false);
		// After non-incremental aggregate the .deps files are not archived (only
		// incremental archives)
		// Delete the file manually to simulate empty deps dir for the next run
		Files.list(depsDir).filter(p -> p.getFileName().toString().endsWith(".deps")).forEach(p -> {
			try {
				Files.deleteIfExists(p);
			} catch (IOException ignored) {
			}
		});

		// Incremental with empty deps dir: should be a no-op
		AggregateOperation.Result result = AggregateOperation.aggregate(depsDir, indexFile, PluginLog.NOOP, true);
		assertFalse(result.written());

		// Index is unchanged
		DependencyMap map = DependencyMap.load(indexFile);
		assertEquals(1, map.size(), "Index should be unchanged — 1 test class from initial load");
		assertFalse(map.get("com.example.FooTest").isEmpty());
	}

	@Test
	void nonIncrementalSetsWrittenFlagWhenDepsExist() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);
		Path indexFile = tempDir.resolve("index.lz4");

		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.Foo\n");
		AggregateOperation.Result result = AggregateOperation.aggregate(depsDir, indexFile, PluginLog.NOOP, false);

		assertTrue(result.written());
		assertEquals(1, result.depsFileCount());
		assertTrue(result.testClassCount() > 0);
	}

	@Test
	void depsFilesArchivedAfterIncrementalMerge() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);
		Path indexFile = tempDir.resolve("index.lz4");

		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.Foo\n");
		// Use incremental=true (alwaysLearn path) to trigger archive
		AggregateOperation.aggregate(depsDir, indexFile, PluginLog.NOOP, true);

		// .deps file must be gone from depsDir
		assertEquals(0, Files.list(depsDir).filter(p -> p.getFileName().toString().endsWith(".deps")).count(),
				".deps files should be archived after incremental merge");

		// archived file must be in .archived subdirectory
		Path archiveDir = depsDir.resolve(".archived");
		assertTrue(Files.isDirectory(archiveDir), ".archived directory should be created");
		long archivedCount = Files.list(archiveDir).filter(p -> p.getFileName().toString().endsWith(".deps")).count();
		assertEquals(1, archivedCount, "Exactly one .deps file should be in .archived");
	}

	@Test
	void archivePrunesOldestWhenOver50Files() throws IOException {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);
		Path indexFile = tempDir.resolve("index.lz4");
		Path archiveDir = depsDir.resolve(".archived");
		Files.createDirectories(archiveDir);

		// Pre-seed archive with 55 dummy files with distinct timestamps
		for (int i = 0; i < 55; i++) {
			Path f = archiveDir.resolve(String.format("old-%03d.deps", i));
			Files.writeString(f, "dummy");
			// set modified time to i seconds in the past so ordering is deterministic
			f.toFile().setLastModified(System.currentTimeMillis() - (55 - i) * 1000L);
		}
		assertEquals(55, Files.list(archiveDir).count());

		// Add one .deps file to trigger a new incremental merge
		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.Foo\n");
		AggregateOperation.aggregate(depsDir, indexFile, PluginLog.NOOP, true);

		// Archive should be ≤ 50 files (1 new + pruned old = at most 50 total)
		long remaining = Files.list(archiveDir).count();
		assertTrue(remaining <= 50, "Archive should be pruned to ≤ 50 files; got: " + remaining);
	}
}
