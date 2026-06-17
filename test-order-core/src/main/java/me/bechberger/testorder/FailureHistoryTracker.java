package me.bechberger.testorder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import me.bechberger.testorder.annotations.ThreadSafe;

/**
 * Tracks class-level and method-level failure history with pending per-run
 * updates.
 *
 * <p>
 * Thread-safety: all mutating methods use {@link ConcurrentHashMap#merge} for
 * atomic per-key updates. {@link #mergeForSave} takes sequential (non-atomic)
 * snapshots of historical and pending maps; this is safe because pending writes
 * arriving between snapshots are accounted for via the "pending-only" loop.
 * Callers must not invoke {@link #applyPersisted} concurrently with other
 * methods.
 */
@ThreadSafe
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

	/** Returns all class names known to the failure history (loaded + pending). */
	Set<String> knownClasses() {
		Set<String> classes = new java.util.HashSet<>(failureScores.keySet());
		classes.addAll(pendingFailureScores.keySet());
		return classes;
	}

	void pruneToActiveClasses(Set<String> activeClasses) {
		failureScores.keySet().removeIf(key -> !isActive(key, activeClasses));
		methodFailureScores.keySet().removeIf(k -> {
			int hash = k.indexOf('#');
			return hash >= 0 && !isActive(k.substring(0, hash), activeClasses);
		});
	}

	/** Returns true if the class is active, or its top-level enclosing class is. */
	private static boolean isActive(String className, Set<String> activeClasses) {
		if (activeClasses.contains(className)) {
			return true;
		}
		int dollar = className.indexOf('$');
		return dollar > 0 && activeClasses.contains(className.substring(0, dollar));
	}

	PersistedScores mergeForSave(boolean hasRunData, double failureDecay, double methodFailureDecay,
			double failurePruneThreshold, Logger log) {
		double retain = hasRunData ? (1.0 - failureDecay) : 1.0;
		Map<String, Object> mergedFailures = new LinkedHashMap<>();
		// Non-atomic snapshot: two separate HashMap copies taken sequentially.
		// Concurrent writes arriving between the two copies are safe because
		// the "pending-only" loop below accounts for any entries added after the
		// fsSnapshot was taken.
		Map<String, Double> fsSnapshot = new java.util.HashMap<>(failureScores);
		Map<String, Double> pfsSnapshot = new java.util.HashMap<>(pendingFailureScores);
		for (var entry : fsSnapshot.entrySet()) {
			double historical = entry.getValue() * retain;
			double pending = pfsSnapshot.getOrDefault(entry.getKey(), 0.0);
			double total = historical + pending;
			if (total >= failurePruneThreshold) {
				mergedFailures.put(entry.getKey(), total);
			} else if (entry.getValue() >= failurePruneThreshold) {
				log.fine(() -> "Pruned failure score for " + entry.getKey() + ": " + entry.getValue() + " -> " + total
						+ " (threshold " + failurePruneThreshold + ")");
			}
		}
		for (var entry : pfsSnapshot.entrySet()) {
			if (!fsSnapshot.containsKey(entry.getKey())) {
				if (entry.getValue() >= failurePruneThreshold) {
					mergedFailures.put(entry.getKey(), entry.getValue());
				}
			}
		}

		double methodRetain = hasRunData ? (1.0 - methodFailureDecay) : 1.0;
		Map<String, Object> mergedMethodFailures = new LinkedHashMap<>();
		// Non-atomic snapshot: two separate HashMap copies taken sequentially.
		Map<String, Double> mfsSnapshot = new java.util.HashMap<>(methodFailureScores);
		Map<String, Double> pmfsSnapshot = new java.util.HashMap<>(pendingMethodFailureScores);
		for (var entry : mfsSnapshot.entrySet()) {
			double historical = entry.getValue() * methodRetain;
			double pending = pmfsSnapshot.getOrDefault(entry.getKey(), 0.0);
			double total = historical + pending;
			if (total >= failurePruneThreshold) {
				mergedMethodFailures.put(entry.getKey(), total);
			}
		}
		for (var entry : pmfsSnapshot.entrySet()) {
			if (!mfsSnapshot.containsKey(entry.getKey())) {
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
