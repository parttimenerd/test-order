package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;

import me.bechberger.testorder.changes.HashSnapshotSupport;

/**
 * Snapshots file hashes for main and test source directories, including Kotlin
 * sibling directories when they exist.
 */
public final class HashSnapshotOperation {

	private HashSnapshotOperation() {
	}

	/**
	 * Derives the hash-file path for a Kotlin source root from the corresponding
	 * Java hash-file path by inserting {@code -kotlin} before the extension.
	 */
	public static Path kotlinHashFile(Path javaHashFile) {
		String name = javaHashFile.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String kotlinName = dot > 0 ? name.substring(0, dot) + "-kotlin" + name.substring(dot) : name + "-kotlin";
		return javaHashFile.resolveSibling(kotlinName);
	}

	/**
	 * Snapshots main and test source directories (plus their Kotlin siblings).
	 *
	 * @param sourceRoot
	 *            main Java source root
	 * @param hashFile
	 *            hash file for main sources
	 * @param testSourceRoot
	 *            test Java source root
	 * @param testHashFile
	 *            hash file for test sources
	 * @param log
	 *            callback {@code (label, path)} for info messages; may be
	 *            {@code null}
	 * @param warn
	 *            callback {@code (label, message)} for warnings; may be
	 *            {@code null}
	 */
	public static void snapshot(Path sourceRoot, Path hashFile, Path testSourceRoot, Path testHashFile,
			BiConsumer<String, Path> log, BiConsumer<String, String> warn) {
		snapshotSingle(sourceRoot, hashFile, "source", log, warn);
		snapshotSingle(testSourceRoot, testHashFile, "test source", log, warn);

		// Also snapshot Kotlin sibling directories if they exist
		Path kotlinMain = sourceRoot.resolveSibling("kotlin");
		if (Files.isDirectory(kotlinMain)) {
			snapshotSingle(kotlinMain, kotlinHashFile(hashFile), "Kotlin source", log, warn);
		}
		Path kotlinTest = testSourceRoot.resolveSibling("kotlin");
		if (Files.isDirectory(kotlinTest)) {
			snapshotSingle(kotlinTest, kotlinHashFile(testHashFile), "Kotlin test source", log, warn);
		}
	}

	private static void snapshotSingle(Path root, Path hashFile, String label, BiConsumer<String, Path> log,
			BiConsumer<String, String> warn) {
		try {
			if (HashSnapshotSupport.snapshotDirectory(root, hashFile)) {
				if (log != null) {
					log.accept(label, hashFile);
				}
			}
		} catch (IOException e) {
			if (warn != null) {
				warn.accept(label, e.getMessage());
			}
		}
	}
}
