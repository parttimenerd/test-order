package me.bechberger.testorder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tracks class-level and method-level EMA durations and variance used for
 * adaptive smoothing.
 */
final class DurationTracker {

	private final Map<String, Long> classDurations = new LinkedHashMap<>();
	private final Map<String, Double> classDurationVariances = new LinkedHashMap<>();
	private final Map<String, Map<String, Double>> methodDurations = new LinkedHashMap<>();
	private final Map<String, Map<String, Double>> methodDurationVariances = new LinkedHashMap<>();

	long getClassDuration(String testClass, long defaultValue) {
		return classDurations.getOrDefault(testClass, defaultValue);
	}

	void recordClassDuration(String testClass, long measuredMs, double alpha, double varianceThreshold,
			double minAdaptiveAlphaFactor) {
		Long previous = classDurations.get(testClass);
		if (previous == null) {
			classDurations.put(testClass, measuredMs);
			classDurationVariances.put(testClass, 0.0);
			return;
		}
		double variance = classDurationVariances.getOrDefault(testClass, 0.0);
		double effectiveAlpha = adaptiveAlpha(alpha, previous.doubleValue(), variance, varianceThreshold,
				minAdaptiveAlphaFactor);
		double delta = measuredMs - previous;
		classDurations.put(testClass, Math.round(effectiveAlpha * measuredMs + (1.0 - effectiveAlpha) * previous));
		classDurationVariances.put(testClass, updatedVariance(variance, delta, effectiveAlpha));
	}

	double getMethodDuration(String className, String methodName, double defaultValue) {
		return methodDurations.getOrDefault(className, Map.of()).getOrDefault(methodName, defaultValue);
	}

	void recordMethodDuration(String className, String methodName, long measuredMs, double alpha,
			double varianceThreshold, double minAdaptiveAlphaFactor) {
		Map<String, Double> classMethods = methodDurations.computeIfAbsent(className, ignored -> new LinkedHashMap<>());
		Map<String, Double> classVariances = methodDurationVariances.computeIfAbsent(className,
				ignored -> new LinkedHashMap<>());

		Double previous = classMethods.get(methodName);
		if (previous == null) {
			classMethods.put(methodName, (double) measuredMs);
			classVariances.put(methodName, 0.0);
			return;
		}
		double variance = classVariances.getOrDefault(methodName, 0.0);
		double effectiveAlpha = adaptiveAlpha(alpha, previous, variance, varianceThreshold, minAdaptiveAlphaFactor);
		double delta = measuredMs - previous;
		classMethods.put(methodName,
				(double) Math.round(effectiveAlpha * measuredMs + (1.0 - effectiveAlpha) * previous));
		classVariances.put(methodName, updatedVariance(variance, delta, effectiveAlpha));
	}

	Map<String, Long> classDurations() {
		return Collections.unmodifiableMap(classDurations);
	}

	Map<String, Double> classDurationVariances() {
		return Collections.unmodifiableMap(classDurationVariances);
	}

	Map<String, Map<String, Double>> methodDurations() {
		return Collections.unmodifiableMap(methodDurations);
	}

	Map<String, Map<String, Double>> methodDurationVariances() {
		return Collections.unmodifiableMap(methodDurationVariances);
	}

	void putClassDuration(String className, long duration) {
		classDurations.put(className, duration);
	}

	void putClassDurationVariance(String className, double variance) {
		classDurationVariances.put(className, variance);
	}

	void putMethodDuration(String className, String methodName, double duration) {
		methodDurations.computeIfAbsent(className, ignored -> new LinkedHashMap<>()).put(methodName, duration);
	}

	void putMethodDurationVariance(String className, String methodName, double variance) {
		methodDurationVariances.computeIfAbsent(className, ignored -> new LinkedHashMap<>()).put(methodName, variance);
	}

	void pruneToActiveClasses(Set<String> activeClasses) {
		classDurations.keySet().removeIf(key -> !isActive(key, activeClasses));
		classDurationVariances.keySet().removeIf(key -> !isActive(key, activeClasses));
		methodDurations.keySet().removeIf(key -> !isActive(key, activeClasses));
		methodDurationVariances.keySet().removeIf(key -> !isActive(key, activeClasses));
	}

	/** Returns true if the class is active, or if its top-level enclosing class is active. */
	static boolean isActive(String className, Set<String> activeClasses) {
		if (activeClasses.contains(className)) {
			return true;
		}
		int dollar = className.indexOf('$');
		return dollar > 0 && activeClasses.contains(className.substring(0, dollar));
	}

	private static double adaptiveAlpha(double baseAlpha, double mean, double variance, double varianceThreshold,
			double minAdaptiveAlphaFactor) {
		if (baseAlpha <= 0 || mean <= 0 || variance <= 0 || varianceThreshold <= 0) {
			return baseAlpha;
		}
		double relativeStdDev = Math.sqrt(variance) / Math.max(mean, 1.0);
		if (relativeStdDev <= varianceThreshold) {
			return baseAlpha;
		}
		double minAlpha = Math.max(0.05, baseAlpha * minAdaptiveAlphaFactor);
		return Math.max(minAlpha, baseAlpha * (varianceThreshold / relativeStdDev));
	}

	private static double updatedVariance(double previousVariance, double delta, double alpha) {
		return alpha * delta * delta + (1.0 - alpha) * previousVariance;
	}
}
