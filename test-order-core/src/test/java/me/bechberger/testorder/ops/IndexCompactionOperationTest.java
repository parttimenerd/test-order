package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;

class IndexCompactionOperationTest {

	@TempDir
	Path tempDir;

	@Test
	void compactsDepsFilesIntoIndex() throws IOException {
		Path depsDir = tempDir.resolve(".test-order/deps");
		Files.createDirectories(depsDir);

		Files.writeString(depsDir.resolve("com.example.FooTest.deps"), "com.example.Foo\ncom.example.Bar\n");
		Files.writeString(depsDir.resolve("com.example.BarTest.deps"), "com.example.Bar\n");

		Path indexFile = tempDir.resolve(".test-order/test-order.idx");

		IndexCompactionOperation.CompactionResult result = IndexCompactionOperation.compact(depsDir, indexFile,
				PluginLog.NOOP);

		assertEquals(2, result.afterTestCount());
		assertTrue(Files.exists(indexFile));

		DependencyMap map = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), map.get("com.example.FooTest"));
		assertEquals(Set.of("com.example.Bar"), map.get("com.example.BarTest"));
	}

	@Test
	void returnsNoDepsWhenDirectoryMissing() throws IOException {
		Path missingDepsDir = tempDir.resolve("does-not-exist");
		Path indexFile = tempDir.resolve(".test-order/test-order.idx");

		IndexCompactionOperation.CompactionResult result = IndexCompactionOperation.compact(missingDepsDir, indexFile,
				PluginLog.NOOP);

		assertEquals("No .deps directory", result.description());
		assertEquals(0, result.afterTestCount());
	}

	@Test
	void reportsRemovedStaleTestsAfterRebuild() throws IOException {
		Path depsDir = tempDir.resolve(".test-order/deps");
		Files.createDirectories(depsDir);
		Files.writeString(depsDir.resolve("com.example.KeptTest.deps"), "com.example.Kept\n");

		Path indexFile = tempDir.resolve(".test-order/test-order.idx");
		Files.createDirectories(indexFile.getParent());

		DependencyMap oldMap = new DependencyMap();
		oldMap.put("com.example.StaleTest", Set.of("com.example.Stale"));
		oldMap.put("com.example.KeptTest", Set.of("com.example.Kept"));
		oldMap.save(indexFile);

		IndexCompactionOperation.CompactionResult result = IndexCompactionOperation.compact(depsDir, indexFile,
				PluginLog.NOOP);

		assertEquals(2, result.beforeTestCount());
		assertEquals(1, result.afterTestCount());
		assertEquals(1, result.removedTests());
		assertTrue(result.hasChanges());
	}
}
