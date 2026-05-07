package me.bechberger.testorder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Tracks class-level and method-level failure history with pending per-run
 * updates.
 */
final class FailureHistoryTracker {

	private final Map<String, Double> failureScores = new ConcurrentHashMap<>();
	private final Map<String, Double> pendingFailureScores = new ConcurrentHashMap<>();
	private final Map<String, Double> methodFailureScores = new ConcurrentHashMap<>();
	private final Map<String, Double> pendingMethodFailureScores = new ConcurrentHashMap<>();

	void recordFailure(String testClass) {
		pendingFailureScores.merge(testClass, 1.0, Double::sum);
	}

	double failureScore(String testClass) {
		return failureScores.getOrDefault(testClass, 0.0) + pendingFailureScores.getOrDefault(testClass, 0.0);
	}

	Map<String, Double> failureScores() {
		return Collections.unmodifiableMap(failureScores);
	}

	void recordMethodFailure(String className, String methodName) {
		String key = className + "#" + methodName;
		pendingMethodFailureScores.merge(key, 1.0, Double::sum);
	}

	double methodFailureScore(String className, String methodName) {
		String key = className + "#" + methodName;
		return methodFailureScores.getOrDefault(key, 0.0) + pendingMethodFailureScores.getOrDefault(key, 0.0);
	}

	Map<String, Double> methodFailureScores() {
		return Collections.unmodifiableMap(methodFailureScores);
	}

	boolean hasPendingData() {
		return !pendingFailureScores.isEmpty() || !pendingMethodFailureScores.isEmpty();
	}

	void pruneToActiveClasses(Set<String> activeClasses) {
		failureScores.keySet().retainAll(activeClasses);
		methodFailureScores.keySet().removeIf(k -> {
			int hash = k.indexOf('#');
			return hash > 0 && !activeClasses.contains(k.substring(0, hash));
		});
	}

	PersistedScores mergeForSave(boolean hasRunData, double failureDecay, double methodFailureDecay,
			double failurePruneThreshold, Logger log) {
		double retain = hasRunData ? (1.0 - failureDecay) : 1.0;
		Map<String, Object> mergedFailures = new LinkedHashMap<>();
		for (var entry : failureScores.entrySet()) {
			double historical = entry.getValue() * retain;
			double pending = pendingFailureScores.getOrDefault(entry.getKey(), 0.0);
			double total = historical + pending;
			if (total >= failurePruneThreshold) {
				mergedFailures.put(entry.getKey(), total);
			} else if (entry.getValue() >= failurePruneThreshold) {
				log.fine(() -> "Pruned failure score for " + entry.getKey() + ": " + entry.getValue() + " -> " + total
						+ " (threshold " + failurePruneThreshold + ")");
			}
		}
		for (var entry : pendingFailureScores.entrySet()) {
			if (!failureScores.containsKey(entry.getKey())) {
				if (entry.getValue() >= failurePruneThreshold) {
					mergedFailures.put(entry.getKey(), entry.getValue());
				}
			}
		}

		double methodRetain = hasRunData ? (1.0 - methodFailureDecay) : 1.0;
		Map<String, Object> mergedMethodFailures = new LinkedHashMap<>();
		for (var entry : methodFailureScores.entrySet()) {
			double historical = entry.getValue() * methodRetain;
			double pending = pendingMethodFailureScores.getOrDefault(entry.getKey(), 0.0);
			double total = historical + pending;
			if (total >= failurePruneThreshold) {
				mergedMethodFailures.put(entry.getKey(), total);
			}
		}
		for (var entry : pendingMethodFailureScores.entrySet()) {
			if (!methodFailureScores.containsKey(entry.getKey())) {
				if (entry.getValue() >= failurePruneThreshold) {
					mergedMethodFailures.put(entry.getKey(), entry.getValue());
				}
			}
		}

		return new PersistedScores(mergedFailures, mergedMethodFailures);
	}

	void applyPersisted(PersistedScores persistedScores) {
		failureScores.clear();
		for (var e : persistedScores.failureScores().entrySet()) {
			failureScores.put(e.getKey(), ((Number) e.getValue()).doubleValue());
		}
		pendingFailureScores.clear();

		methodFailureScores.clear();
		for (var e : persistedScores.methodFailureScores().entrySet()) {
			methodFailureScores.put(e.getKey(), ((Number) e.getValue()).doubleValue());
		}
		pendingMethodFailureScores.clear();
	}

	void loadFailureScore(String testClass, double score) {
		failureScores.put(testClass, score);
	}

	void loadMethodFailureScore(String key, double score) {
		methodFailureScores.put(key, score);
	}

	record PersistedScores(Map<String, Object> failureScores, Map<String, Object> methodFailureScores) {
	}
}
