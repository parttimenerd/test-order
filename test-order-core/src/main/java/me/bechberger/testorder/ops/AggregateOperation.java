package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
	public record Result(int depsFileCount, int testClassCount, boolean written) {
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
		return aggregate(depsDir, indexPath, log, false);
	}

	/**
	 * Aggregates all {@code .deps} files in {@code depsDir} into a single binary
	 * index at {@code indexPath}.
	 *
	 * @param incremental
	 *            when {@code true}, loads the existing index first and merges new
	 *            deps into it (union semantics). When {@code false}, rebuilds the
	 *            entire index from the current {@code .deps} files only (replacing
	 *            the previous index). Use {@code true} for selective-learn runs
	 *            where only a subset of tests was re-instrumented.
	 */
	public static Result aggregate(Path depsDir, Path indexPath, PluginLog log, boolean incremental)
			throws IOException {
		log.info("[test-order] Aggregating test dependencies" + (incremental ? " (incremental)" : "") + "...");
		int depsFileCount = 0;
		try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(depsDir)) {
			depsFileCount = (int) files.filter(f -> f.getFileName().toString().endsWith(".deps")).count();
		} catch (NoSuchFileException ignored) {
			// depsDir hasn't been created yet — agent produced no .deps files
		}

		if (incremental) {
			if (depsFileCount == 0) {
				log.info("[test-order] No .deps files found — nothing to merge into existing index.");
				return new Result(0, 0, false);
			}
			// Load existing index as base and merge new .deps files into it.
			// aggregateFromDepsDirectory handles file-locking, logging, and merge
			// semantics (union deps per test class), and returns the total post-merge
			// test-class count so we don't re-decompress the index just for size().
			int testClassCount = DependencyMap.aggregateFromDepsDirectory(depsDir, indexPath, log);
			return new Result(depsFileCount, testClassCount, true);
		}

		DependencyMap map = DependencyMap.aggregate(depsDir, log);
		if (map.size() == 0) {
			if (Files.exists(indexPath)) {
				log.warn("[test-order] No .deps files found — refusing to overwrite existing index at " + indexPath);
			} else {
				log.warn("[test-order] No .deps files found — no index to write.");
			}
			return new Result(0, 0, false);
		}
		if (Files.exists(indexPath)) {
			log.debug("[test-order] Overwriting existing index at " + indexPath);
		}
		PersistenceSupport.withFileLock(indexPath, () -> {
			map.save(indexPath);
			return null;
		});
		log.info("[test-order] Aggregated " + map.size() + " test classes → " + indexPath);
		return new Result(depsFileCount, map.size(), true);
	}
}
