package me.bechberger.testorder.ml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared helper for loading ML history and producing a health report.
 * Encapsulates the load → deserialize → analyze pattern used by both Maven and
 * Gradle plugin layers.
 */
public final class MLHealthLoader {

	private MLHealthLoader() {
	}

	/**
	 * Result of loading ML history — contains the health report and raw run
	 * records.
	 *
	 * @param healthReport
	 *            the analyzed health report (null if no data)
	 * @param runs
	 *            raw run records (empty list if no data)
	 */
	public record LoadResult(TestHealthReport healthReport, List<MLRunRecord> runs) {

		/** Returns true if ML data was found and analyzed. */
		public boolean hasData() {
			return healthReport != null;
		}
	}

	/**
	 * Loads ML history from the given directory, analyzes it, and returns a health
	 * report. Returns a result with null healthReport if no history exists or the
	 * history is empty.
	 *
	 * @param mlHistoryDir
	 *            directory containing {@code history.lz4}
	 * @return load result (never null)
	 */
	public static LoadResult load(Path mlHistoryDir) {
		if (mlHistoryDir == null) {
			return new LoadResult(null, List.of());
		}
		Path historyFile = mlHistoryDir.resolve("history.lz4");
		if (!Files.exists(historyFile)) {
			return new LoadResult(null, List.of());
		}
		try {
			List<MLRunRecord> runs = MLHistoryPersistence.load(historyFile);
			if (runs.isEmpty()) {
				return new LoadResult(null, runs);
			}
			TestHealthReport report = TestHealthAnalyzer.analyze(runs);
			return new LoadResult(report, runs);
		} catch (Exception e) {
			return new LoadResult(null, List.of());
		}
	}
}
