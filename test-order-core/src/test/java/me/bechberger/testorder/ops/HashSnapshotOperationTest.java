package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
