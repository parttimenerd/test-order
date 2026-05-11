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
				TestOrderLogger.warn("Failed to load state from {}: {} — starting fresh",
						stateFile, e.getMessage());
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
			entry.getValue().forEach(duration -> state.recordDuration(entry.getKey(), duration));
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
					// Applying N separate EMA updates in one run effectively erases historical
					// data after ~log(0.03)/log(1-alpha) updates.
					long avg = durations.stream().mapToLong(Long::longValue).sum() / durations.size();
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
				TestOrderLogger.warn("Invalid {} value '{}': {}", TestOrderConfig.HISTORY_MAX_RUNS,
						maxRunsProp, e.getMessage());
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
	 */
	public static void emergencySave(String statePath, Map<String, List<Long>> pendingDurations,
			Set<String> failedClassNames, Map<String, List<Long>> pendingMethodDurations,
			Set<String> failedMethodNames) {
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
				state.save(stateFile);
				return state;
			});
		} catch (Exception ignored) {
			// Best-effort: shutdown hooks must not throw
		}
	}
}
