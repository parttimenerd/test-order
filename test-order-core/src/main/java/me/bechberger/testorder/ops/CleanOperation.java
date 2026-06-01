package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

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
		Set<Path> parentDirs = new java.util.LinkedHashSet<>();
		for (Path file : files) {
			if (file == null) {
				continue;
			}
			// Delete the main file plus any sibling lock/temp files left by
			// PersistenceSupport
			for (String suffix : List.of("", ".lock", ".tmp")) {
				Path candidate = suffix.isEmpty() ? file : file.resolveSibling(file.getFileName() + suffix);
				try {
					if (Files.deleteIfExists(candidate)) {
						log.info("[test-order] Deleted " + candidate);
						deleted++;
						if (candidate.getParent() != null) {
							parentDirs.add(candidate.getParent());
						}
					}
				} catch (IOException e) {
					log.warn("[test-order] Failed to delete " + candidate + ": " + e.getMessage());
				}
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
		// Remove parent directories (e.g. .test-order/) if they are now empty
		for (Path parent : parentDirs) {
			try {
				if (Files.isDirectory(parent) && isEmptyDirectory(parent)) {
					Files.delete(parent);
					log.info("[test-order] Deleted empty directory " + parent);
					deleted++;
				}
			} catch (IOException e) {
				// best-effort — not critical
			}
		}
		if (deleted == 0) {
			log.info("[test-order] Nothing to clean");
		}
		return deleted;
	}

	private static boolean isEmptyDirectory(Path dir) throws IOException {
		try (var entries = Files.newDirectoryStream(dir)) {
			return !entries.iterator().hasNext();
		}
	}
}
