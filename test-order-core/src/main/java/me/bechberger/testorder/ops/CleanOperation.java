package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Removes all test-order generated files (index, state, hashes, deps
 * directory). Framework-agnostic — used by both the Maven {@code clean} mojo
 * and the Gradle {@code testOrderClean} task.
 */
public final class CleanOperation {

	private CleanOperation() {
	}

	/**
	 * Deletes all test-order files and directories.
	 *
	 * @param files
	 *            individual files to delete (index, state, hash files)
	 * @param dirs
	 *            directories to delete recursively (deps dir)
	 * @param log
	 *            logger
	 * @return number of items deleted
	 */
	public static int clean(List<Path> files, List<Path> dirs, PluginLog log) {
		int deleted = 0;
		for (Path file : files) {
			try {
				if (Files.deleteIfExists(file)) {
					log.info("[test-order] Deleted " + file);
					deleted++;
				}
			} catch (IOException e) {
				log.warn("[test-order] Failed to delete " + file + ": " + e.getMessage());
			}
		}
		for (Path dir : dirs) {
			if (Files.isDirectory(dir)) {
				try (var walk = Files.walk(dir)) {
					walk.sorted(Comparator.reverseOrder()).forEach(p -> {
						try {
							Files.deleteIfExists(p);
						} catch (IOException e) {
							log.warn("[test-order] Failed to delete " + p + ": " + e.getMessage());
						}
					});
					log.info("[test-order] Deleted " + dir);
					deleted++;
				} catch (IOException e) {
					log.warn("[test-order] Failed to walk " + dir + ": " + e.getMessage());
				}
			}
		}
		if (deleted == 0) {
			log.info("[test-order] Nothing to clean");
		}
		return deleted;
	}
}
