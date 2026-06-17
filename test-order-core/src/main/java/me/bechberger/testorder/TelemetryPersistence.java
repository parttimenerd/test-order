package me.bechberger.testorder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared telemetry persistence logic for test-order listeners.
 * <p>
 * Both JUnit and TestNG listeners accumulate durations, failures, and execution
 * order during a test run, then persist them to the state file. This class
 * encapsulates the common persistence operations.
 */
public final class TelemetryPersistence {

	private TelemetryPersistence() {
	}

	/**
	 * Loads an existing state from file, or returns a new empty state if the file
	 * does not exist or is corrupt.
	 */
	public static TestOrderState loadStateOrEmpty(Path stateFile) {
		try {
			return TestOrderState.load(stateFile);
		} catch (IOException e) {
			if (java.nio.file.Files.exists(stateFile)) {
				TestOrderLogger.warn("Failed to load state from {}: {} — starting fresh", stateFile, e.getMessage());
			}
			return new TestOrderState();
		}
	}

	/**
	 * Applies accumulated telemetry data (durations, failures) to the given state.
	 *
	 * @param state
	 *            the state to update
	 * @param pendingDurations
	 *            class-level durations (className → list of ms)
	 * @param failedClassNames
	 *            set of failed class names
	 * @param pendingMethodDurations
	 *            method-level durations (className#method → list of ms)
	 * @param failedMethodNames
	 *            set of failed method keys (className#method)
	 */
	public static void applyPendingTelemetry(TestOrderState state, Map<String, List<Long>> pendingDurations,
			Set<String> failedClassNames, Map<String, List<Long>> pendingMethodDurations,
			Set<String> failedMethodNames) {
		for (var entry : pendingDurations.entrySet()) {
			List<Long> durations = entry.getValue();
			if (durations.isEmpty()) {
				continue;
			}
			// Accumulate all per-method-invocation durations into a single per-run
			// observation before applying the EMA, so one test run advances the EMA
			// exactly once regardless of how many methods were timed. This prevents
			// the EMA from being driven too low by repeated micro-observations.
			if (durations.size() == 1) {
				state.recordDuration(entry.getKey(), durations.get(0));
			} else {
				double sum = 0.0;
				for (Long d : durations) {
					sum += d.doubleValue();
				}
				state.recordDuration(entry.getKey(), Math.round(sum / durations.size()));
			}
		}
		for (String failed : failedClassNames) {
			state.recordFailure(failed);
		}
		for (var entry : pendingMethodDurations.entrySet()) {
			String[] parts = entry.getKey().split("#", 2);
			if (parts.length == 2) {
				List<Long> durations = entry.getValue();
				if (durations.size() <= 1) {
					durations.forEach(duration -> state.recordMethodDuration(parts[0], parts[1], duration));
				} else {
					// Multiple observations in one run (e.g. @ParameterizedTest invocations).
					// Aggregate to a single observation to preserve cross-run EMA smoothing.
					// Use double arithmetic to avoid integer division loss, then round.
					double sum = 0.0;
					for (Long d : durations) {
						sum += d.doubleValue();
					}
					long avg = Math.round(sum / durations.size());
					state.recordMethodDuration(parts[0], parts[1], avg);
				}
			}
		}
		for (String methodKey : failedMethodNames) {
			String[] parts = methodKey.split("#", 2);
			if (parts.length == 2) {
				state.recordMethodFailure(parts[0], parts[1]);
			}
		}
	}

	/**
	 * Applies the {@code testorder.history.maxRuns} system property to the state if
	 * set.
	 */
	public static void applyHistoryMaxRuns(TestOrderState state) {
		String maxRunsProp = System.getProperty(TestOrderConfig.HISTORY_MAX_RUNS);
		if (maxRunsProp != null) {
			try {
				state.setHistoryMaxRuns(Integer.parseInt(maxRunsProp));
			} catch (IllegalArgumentException e) {
				TestOrderLogger.warn("Invalid {} value '{}': {}", TestOrderConfig.HISTORY_MAX_RUNS, maxRunsProp,
						e.getMessage());
			}
		}
	}

	/**
	 * Performs a best-effort emergency save of accumulated telemetry. Intended for
	 * shutdown hooks when the normal finish callback was never invoked.
	 *
	 * @param statePath
	 *            path to the state file
	 * @param pendingDurations
	 *            class-level durations
	 * @param failedClassNames
	 *            failed class names
	 * @param pendingMethodDurations
	 *            method-level durations
	 * @param failedMethodNames
	 *            failed method keys
	 * @param aggregatedMode
	 *            when true, use saveAggregatedFork (no decay) — decay happens later
	 *            when PartialRunAggregator.mergeAndApply runs
	 */
	public static void emergencySave(String statePath, Map<String, List<Long>> pendingDurations,
			Set<String> failedClassNames, Map<String, List<Long>> pendingMethodDurations, Set<String> failedMethodNames,
			boolean aggregatedMode) {
		String effectivePath = statePath;
		if (effectivePath == null || effectivePath.isEmpty()) {
			effectivePath = TestOrderState.getPendingStatePath();
		}
		if (effectivePath == null || effectivePath.isEmpty())
			return;

		try {
			Path stateFile = Path.of(effectivePath);
			PersistenceSupport.withFileLock(stateFile, () -> {
				TestOrderState state = loadStateOrEmpty(stateFile);
				applyHistoryMaxRuns(state);
				applyPendingTelemetry(state, pendingDurations, failedClassNames, pendingMethodDurations,
						failedMethodNames);
				if (aggregatedMode) {
					state.saveAggregatedFork(stateFile);
				} else {
					state.save(stateFile);
				}
				return state;
			});
		} catch (Exception ignored) {
			TestOrderLogger.warn("emergencySave failed (best-effort shutdown hook): {}", ignored.getMessage());
			// Best-effort: shutdown hooks must not throw
		}
	}
}
