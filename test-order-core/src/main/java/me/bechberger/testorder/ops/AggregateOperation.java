package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.PersistenceSupport;

/**
 * Aggregates individual {@code .deps} files from learn mode into a single
 * binary dependency index. Framework-agnostic — used by both Maven and Gradle.
 */
public final class AggregateOperation {

	private AggregateOperation() {
	}

	/** Result of an aggregation attempt. */
	public record Result(int testClassCount, boolean written) {
	}

	/**
	 * Aggregates all {@code .deps} files in {@code depsDir} into a single binary
	 * index at {@code indexPath}. Uses a file lock around the write to support
	 * concurrent builds.
	 *
	 * @param depsDir
	 *            directory containing {@code .deps} files
	 * @param indexPath
	 *            output binary index path
	 * @param log
	 *            logger
	 * @return result with number of test classes aggregated and whether the file
	 *         was written
	 * @throws IOException
	 *             on I/O failure
	 */
	public static Result aggregate(Path depsDir, Path indexPath, PluginLog log) throws IOException {
		DependencyMap map = DependencyMap.aggregate(depsDir);
		if (map.size() == 0) {
			if (Files.exists(indexPath)) {
				log.warn("[test-order] No .deps files found — refusing to overwrite existing index at " + indexPath);
			} else {
				log.warn("[test-order] No .deps files found — no index to write.");
			}
			return new Result(0, false);
		}
		if (Files.exists(indexPath)) {
			log.debug("[test-order] Overwriting existing index at " + indexPath);
		}
		PersistenceSupport.withFileLock(indexPath, () -> {
			map.save(indexPath);
			return null;
		});
		log.info("[test-order] Aggregated " + map.size() + " test classes → " + indexPath);
		return new Result(map.size(), true);
	}
}
