package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HashSnapshotOperationTest {

	@TempDir
	Path tempDir;

	@Test
	void snapshot_nullTestSourceRoot_doesNotThrow() {
		Path sourceRoot = tempDir.resolve("src/main/java");
		Path hashFile = tempDir.resolve("hashes.lz4");
		Path testHashFile = tempDir.resolve("test-hashes.lz4");

		assertDoesNotThrow(() -> HashSnapshotOperation.snapshot(sourceRoot, hashFile, null, testHashFile, null, null),
				"null testSourceRoot must not throw NPE");
		assertFalse(Files.exists(testHashFile), "no test hash file should be written for null testSourceRoot");
	}

	@Test
	void snapshot_nullSourceRoot_doesNotThrow() {
		Path testSourceRoot = tempDir.resolve("src/test/java");
		Path hashFile = tempDir.resolve("hashes.lz4");
		Path testHashFile = tempDir.resolve("test-hashes.lz4");

		assertDoesNotThrow(
				() -> HashSnapshotOperation.snapshot(null, hashFile, testSourceRoot, testHashFile, null, null),
				"null sourceRoot must not throw NPE");
		assertFalse(Files.exists(hashFile), "no hash file should be written for null sourceRoot");
	}

	@Test
	void snapshot_bothNullSourceRoots_doesNotThrow() {
		Path hashFile = tempDir.resolve("hashes.lz4");
		Path testHashFile = tempDir.resolve("test-hashes.lz4");

		assertDoesNotThrow(() -> HashSnapshotOperation.snapshot(null, hashFile, null, testHashFile, null, null),
				"both null source roots must not throw NPE");
	}

	@Test
	void detectChangedMethods_nullSourceRoot_returnsEmpty() {
		Path methodHashFile = tempDir.resolve("method-hashes.lz4");
		Set<String> result = ChangeDetectionOps.detectChangedMethods(null, methodHashFile, PluginLog.NOOP);
		assertTrue(result.isEmpty(), "null testSourceRoot must return empty set without NPE");
	}

	@Test
	void snapshotMethodHashes_nullSourceRoot_doesNotThrow() {
		Path methodHashFile = tempDir.resolve("method-hashes.lz4");
		assertDoesNotThrow(() -> ChangeDetectionOps.snapshotMethodHashes(null, methodHashFile, PluginLog.NOOP),
				"null testSourceRoot must not throw NPE");
		assertFalse(Files.exists(methodHashFile), "no method hash file should be written for null testSourceRoot");
	}
}
