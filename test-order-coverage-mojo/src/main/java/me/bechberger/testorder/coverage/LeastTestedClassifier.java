package me.bechberger.testorder.coverage;

import java.util.*;
import java.util.stream.Collectors;

public class LeastTestedClassifier {
	private final CoverageReport report;

	public enum Severity {
		CRITICAL("Critical", 0, 30), IMPORTANT("Important", 30, 50), REVIEW("Should Review", 50,
				70), ACCEPTABLE("Acceptable", 70, 100);

		private final String label;
		private final int minCoverage;
		private final int maxCoverage;

		Severity(String label, int minCoverage, int maxCoverage) {
			this.label = label;
			this.minCoverage = minCoverage;
			this.maxCoverage = maxCoverage;
		}

		public static Severity classify(int coveragePercent) {
			for (Severity severity : values()) {
				if (coveragePercent >= severity.minCoverage && coveragePercent < severity.maxCoverage) {
					return severity;
				}
			}
			return ACCEPTABLE;
		}

		public String getLabel() {
			return label;
		}
	}

	public LeastTestedClassifier(CoverageReport report) {
		this.report = report;
	}

	/**
	 * Get all classes below threshold, grouped by severity
	 */
	public Map<Severity, List<ClassMetrics>> classifyByThreshold(int threshold) {
		Map<Severity, List<ClassMetrics>> result = new LinkedHashMap<>();

		// Initialize severity buckets
		for (Severity severity : Severity.values()) {
			if (severity.minCoverage < threshold) {
				result.put(severity, new ArrayList<>());
			}
		}

		// Classify each class below threshold
		for (ClassMetrics metric : report.getBelowThreshold(threshold)) {
			Severity severity = Severity.classify(metric.getLineCoverage());
			result.computeIfAbsent(severity, k -> new ArrayList<>()).add(metric);
		}

		// Sort each severity group by coverage (lowest first)
		result.forEach((severity, classes) -> classes.sort(Comparator.comparingInt(ClassMetrics::getLineCoverage)));

		return result;
	}

	/**
	 * Get top N least tested classes
	 */
	public List<ClassMetrics> getLeastTested(int limit) {
		return report.getLeastTested(limit);
	}

	/**
	 * Get least tested classes by module
	 */
	public Map<String, List<ClassMetrics>> classifyByModule(int threshold) {
		Map<String, List<ClassMetrics>> result = new TreeMap<>();

		for (ClassMetrics metric : report.getBelowThreshold(threshold)) {
			String module = metric.getModule();
			result.computeIfAbsent(module, k -> new ArrayList<>()).add(metric);
		}

		// Sort each module's classes by coverage
		result.forEach((module, classes) -> classes.sort(Comparator.comparingInt(ClassMetrics::getLineCoverage)));

		return result;
	}

	/**
	 * Get least tested classes by package
	 */
	public Map<String, List<ClassMetrics>> classifyByPackage(int threshold) {
		Map<String, List<ClassMetrics>> result = new TreeMap<>();

		for (ClassMetrics metric : report.getBelowThreshold(threshold)) {
			String packageName = metric.getPackageName();
			result.computeIfAbsent(packageName, k -> new ArrayList<>()).add(metric);
		}

		// Sort each package's classes by coverage
		result.forEach((pkg, classes) -> classes.sort(Comparator.comparingInt(ClassMetrics::getLineCoverage)));

		return result;
	}

	/**
	 * Get summary statistics for threshold classification
	 */
	public Map<String, Object> getThresholdSummary(int threshold) {
		Map<String, Object> summary = new LinkedHashMap<>();

		Map<Severity, List<ClassMetrics>> classified = classifyByThreshold(threshold);
		int totalBelow = classified.values().stream().mapToInt(List::size).sum();

		summary.put("threshold", threshold);
		summary.put("totalClassesBelow", totalBelow);
		summary.put("totalClasses", report.getOverallStats().getClassCount());

		for (Severity severity : Severity.values()) {
			List<ClassMetrics> classes = classified.getOrDefault(severity, new ArrayList<>());
			summary.put(severity.getLabel(), classes.size());
		}

		return summary;
	}
}
