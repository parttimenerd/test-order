package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import me.bechberger.testorder.DependencyMap;

/**
 * Index compaction operation to rebuild index from .deps files. Useful for
 * cleaning stale entries or fixing corrupted index files.
 */
public final class IndexCompactionOperation {

	private IndexCompactionOperation() {
	}

	/**
	 * Rebuild index from all .deps files in a directory. Merges all test
	 * dependencies and writes a new index file.
	 *
	 * @param depsDir
	 *            directory containing .deps files
	 * @param outputFile
	 *            path to write compacted index
	 * @param log
	 *            logger for messages
	 * @return result with before/after statistics
	 * @throws IOException
	 *             if read/write fails
	 */
	public static CompactionResult compact(Path depsDir, Path outputFile, PluginLog log) throws IOException {
		if (!Files.exists(depsDir)) {
			log.warn("Deps directory not found: " + depsDir);
			return new CompactionResult(0, 0, 0, "No .deps directory");
		}

		// Count .deps files
		long depsFileCount;
		try (var stream = Files.list(depsDir)) {
			depsFileCount = stream.filter(p -> p.getFileName().toString().endsWith(".deps")).count();
		}

		if (depsFileCount == 0) {
			log.info("No .deps files found in " + depsDir);
			return new CompactionResult(0, 0, 0, "No .deps files");
		}

		log.info("Compacting index from " + depsFileCount + " .deps files");

		// Use DependencyMap.aggregate to merge all dependencies
		DependencyMap mergedMap = DependencyMap.aggregate(depsDir);
		long afterClasses = mergedMap.size();

		// Get before state if index exists
		long beforeSize = 0;
		long beforeClasses = 0;
		if (Files.exists(outputFile)) {
			beforeSize = Files.size(outputFile);
			try {
				beforeClasses = DependencyMap.load(outputFile).size();
			} catch (IOException e) {
				log.debug("Could not read existing index: " + e.getMessage());
			}
		}

		if (afterClasses == 0) {
			log.warn("No test classes found in .deps files");
			return new CompactionResult(beforeClasses, afterClasses, 0, "No test classes to index");
		}

		// Write new index
		Files.createDirectories(outputFile.getParent());
		mergedMap.save(outputFile);
		long afterSize = Files.size(outputFile);

		log.info("Compaction complete:");
		log.info("  Before: " + beforeClasses + " test classes (" + formatBytes(beforeSize) + ")");
		log.info("  After:  " + afterClasses + " test classes (" + formatBytes(afterSize) + ")");
		log.info("  Saved index to: " + outputFile.toAbsolutePath());

		String change = beforeClasses == 0
				? "Initial index creation"
				: (afterClasses > beforeClasses
						? "Added " + (afterClasses - beforeClasses) + " new tests"
						: (afterClasses < beforeClasses
								? "Removed " + (beforeClasses - afterClasses) + " stale tests"
								: "No changes"));

		return new CompactionResult(beforeClasses, afterClasses, afterSize, change);
	}

	/**
	 * Statistics from a compaction operation.
	 */
	public record CompactionResult(long beforeTestCount, long afterTestCount, long newIndexSize, String description) {

		public boolean hasChanges() {
			return beforeTestCount != afterTestCount;
		}

		public long addedTests() {
			return Math.max(0, afterTestCount - beforeTestCount);
		}

		public long removedTests() {
			return Math.max(0, beforeTestCount - afterTestCount);
		}
	}

	private static String formatBytes(long bytes) {
		if (bytes <= 0)
			return "0 B";
		final String[] units = new String[] { "B", "KB", "MB", "GB" };
		int digitGroups = Math.min((int) (Math.log10(bytes) / Math.log10(1024)), units.length - 1);
		return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
	}
}
