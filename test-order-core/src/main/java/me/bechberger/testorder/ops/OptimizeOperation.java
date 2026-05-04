package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import me.bechberger.testorder.OptimizationDefaults;
import me.bechberger.testorder.PersistenceSupport;
import me.bechberger.testorder.TestOrderState;

/**
 * Runs the scoring-weight optimisation cycle: load state → optimise → save.
 */
public final class OptimizeOperation {

	private OptimizeOperation() {
	}

	/** Outcome of one optimisation attempt. */
	public record Result(TestOrderState.ScoringWeights previousWeights, TestOrderState.ScoringWeights optimizedWeights,
			boolean overfit, double trainScore, double validationScore, long elapsedMs, long totalRuns,
			long failureRuns) {
	}

	/**
	 * Executes the optimisation.
	 *
	 * @param statePath
	 *            path to the test-order state file (must exist)
	 * @param log
	 *            callback for informational messages (may be {@code null})
	 * @return result, or {@code null} when there are not enough failure runs
	 * @throws IOException
	 *             if the state file cannot be loaded or saved
	 */
	public static Result run(Path statePath, Consumer<String> log) throws IOException {
		return PersistenceSupport.withFileLock(statePath, () -> {
			TestOrderState state;
			try {
				state = TestOrderState.load(statePath);
			} catch (IOException e) {
				throw new IOException("Failed to load state file: " + statePath + ": " + e.getMessage(), e);
			}

			long totalRuns = state.runs().size();
			long withFailures = state.runs().stream().filter(r -> r.totalFailures() > 0).count();

			if (log != null) {
				log.accept("[test-order] Runs: " + totalRuns + " total, " + withFailures + " with failures");
			}

			TestOrderState.ScoringWeights current = state.weights();
			if (log != null) {
				log.accept("[test-order] Current weights:  " + current.format());
			}

			long startMs = System.currentTimeMillis();

			// Suppress JUL output from ScoringOptimizer — caller controls logging
			java.util.logging.Logger julLogger = java.util.logging.Logger
					.getLogger("me.bechberger.testorder.TestOrderState");
			java.util.logging.Level previousLevel = julLogger.getLevel();
			julLogger.setLevel(java.util.logging.Level.OFF);
			TestOrderState.OptimizeResult optimized;
			try {
				optimized = state.optimize();
			} finally {
				julLogger.setLevel(previousLevel);
			}
			long elapsedMs = System.currentTimeMillis() - startMs;

			if (optimized == null) {
				if (log != null) {
					log.accept(String.format("[test-order] Need at least %d runs with failures to optimise (have %d).",
							OptimizationDefaults.MIN_RUNS_FOR_OPTIMISATION, withFailures));
				}
				return null;
			}

			state.setWeights(optimized.weights());
			state.save(statePath);

			if (log != null) {
				log.accept(String.format("[test-order] Optimised weights:  %s  (%.1fs)", optimized.weights().format(),
						elapsedMs / 1000.0));
				if (optimized.overfit()) {
					log.accept("[test-order] Overfitting detected — default weights used instead.");
				}
			}

			return new Result(current, optimized.weights(), optimized.overfit(), optimized.trainScore(),
					optimized.validationScore(), elapsedMs, totalRuns, withFailures);
		});
	}
}
