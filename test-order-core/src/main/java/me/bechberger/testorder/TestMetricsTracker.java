package me.bechberger.testorder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import me.bechberger.testorder.annotations.ThreadSafe;

/**
 * Owns the duration and failure-history trackers that back
 * {@link TestOrderState}.
 *
 * <p>
 * Phase C1 of the facade refactor: pulls the {@link DurationTracker} and
 * {@link FailureHistoryTracker} fields out of {@link TestOrderState} so the
 * outer class becomes a thinner facade. Persistence orchestration still lives
 * on {@link TestOrderState} until C4; this component exposes package-private
 * accessors ({@link #durationTracker()}, {@link #failureHistory()}) so the
 * orchestrator can reach through.
 *
 * <p>
 * Both backing trackers are individually thread-safe (Phase A). The compound
 * pruning operation in {@link #pruneDeletedTestClasses(Path)} is not — callers
 * must serialise it against concurrent record/query traffic.
 */
@ThreadSafe
final class TestMetricsTracker {

	private final DurationTracker durationTracker;
	private final FailureHistoryTracker failureHistory;
	private final StateConfiguration config;

	TestMetricsTracker(StateConfiguration config) {
		this.config = config;
		this.durationTracker = new DurationTracker();
		this.failureHistory = new FailureHistoryTracker();
	}

	DurationTracker durationTracker() {
		return durationTracker;
	}

	FailureHistoryTracker failureHistory() {
		return failureHistory;
	}

	// ── Class durations ───────────────────────────────────────────────

	long getDuration(String testClass, long defaultValue) {
		return durationTracker.getClassDuration(testClass, defaultValue);
	}

	long classDuration(String testClass) {
		return getDuration(testClass, 0L);
	}

	double getDurationVariance(String testClass, double defaultValue) {
		Double v = durationTracker.classDurationVariances().get(testClass);
		return v != null ? v : defaultValue;
	}

	Map<String, Long> getClassDurations() {
		return durationTracker.classDurations();
	}

	void recordDuration(String testClass, long measuredMs, double minAdaptiveAlphaFactor) {
		durationTracker.recordClassDuration(testClass, measuredMs, config.durationAlpha(),
				config.emaVarianceThreshold(), minAdaptiveAlphaFactor);
	}

	// ── Class failures ────────────────────────────────────────────────

	void recordFailure(String testClass) {
		failureHistory.recordFailure(testClass);
	}

	double failureScore(String testClass) {
		return failureHistory.failureScore(testClass);
	}

	Map<String, Double> getFailureScores() {
		return failureHistory.failureScores();
	}

	// ── Method durations ──────────────────────────────────────────────

	double getDurationMethod(String className, String methodName, double defaultValue) {
		return durationTracker.getMethodDuration(className, methodName, defaultValue);
	}

	void recordMethodDuration(String className, String methodName, long measuredMs, double minAdaptiveAlphaFactor) {
		durationTracker.recordMethodDuration(className, methodName, measuredMs, config.methodDurationAlpha(),
				config.emaVarianceThreshold(), minAdaptiveAlphaFactor);
	}

	Map<String, Map<String, Double>> getMethodDurations() {
		return durationTracker.methodDurations();
	}

	// ── Method failures ───────────────────────────────────────────────

	void recordMethodFailure(String className, String methodName) {
		failureHistory.recordMethodFailure(className, methodName);
	}

	double methodFailureScore(String className, String methodName) {
		return failureHistory.methodFailureScore(className, methodName);
	}

	Map<String, Double> getMethodFailureScores() {
		return failureHistory.methodFailureScores();
	}

	// ── Pruning ───────────────────────────────────────────────────────

	void pruneToActiveClasses(Set<String> activeClasses) {
		durationTracker.pruneToActiveClasses(activeClasses);
		failureHistory.pruneToActiveClasses(activeClasses);
	}

	/**
	 * Prunes state entries for test classes whose compiled class file is
	 * definitively absent from {@code testClassesDir}. See
	 * {@link TestOrderState#pruneDeletedTestClasses(Path)} for full semantics.
	 */
	Set<String> pruneDeletedTestClasses(Path testClassesDir) {
		if (testClassesDir == null || !Files.isDirectory(testClassesDir)) {
			return Set.of();
		}
		Set<String> tracked = new LinkedHashSet<>();
		tracked.addAll(durationTracker.classDurations().keySet());
		tracked.addAll(failureHistory.knownClasses());

		Set<String> pruned = new LinkedHashSet<>();
		for (String fqcn : tracked) {
			if (fqcn.contains("$")) {
				continue;
			}
			Path classFile = testClassesDir.resolve(fqcn.replace('.', '/') + ".class");
			if (!Files.exists(classFile)) {
				pruned.add(fqcn);
			}
		}
		if (!pruned.isEmpty()) {
			Set<String> retained = new HashSet<>(durationTracker.classDurations().keySet());
			retained.addAll(failureHistory.knownClasses());
			retained.removeAll(pruned);
			Set<String> allTracked = new HashSet<>(durationTracker.classDurations().keySet());
			allTracked.addAll(failureHistory.knownClasses());
			for (String fqcn : allTracked) {
				if (fqcn.contains("$")) {
					String outer = fqcn.substring(0, fqcn.indexOf('$'));
					if (!retained.contains(outer)) {
						pruned.add(fqcn);
					}
				}
			}
			Set<String> finalRetained = new HashSet<>(durationTracker.classDurations().keySet());
			finalRetained.addAll(failureHistory.knownClasses());
			finalRetained.removeAll(pruned);
			durationTracker.pruneToActiveClasses(finalRetained);
			failureHistory.pruneToActiveClasses(finalRetained);
		}
		return Collections.unmodifiableSet(pruned);
	}
}
