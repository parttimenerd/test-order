package me.bechberger.testorder.coverage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregated coverage metrics across modules and classes. Provides query and
 * statistical methods for analysis.
 */
public class CoverageReport {
	private final Map<String, List<ClassMetrics>> byModule;
	private final Map<String, ClassMetrics> byClass;
	private final List<ClassMetrics> allMetrics;

	public CoverageReport(List<ClassMetrics> metrics) {
		this.allMetrics = new ArrayList<>(metrics);
		this.byModule = new LinkedHashMap<>();
		this.byClass = new LinkedHashMap<>();

		// Build indices
		for (ClassMetrics metric : metrics) {
			byClass.put(metric.getFullyQualifiedName(), metric);
			byModule.computeIfAbsent(metric.getModule(), k -> new ArrayList<>()).add(metric);
		}
	}

	/**
	 * Get all metrics below a coverage threshold.
	 */
	public List<ClassMetrics> getBelowThreshold(int percentThreshold) {
		return allMetrics.stream().filter(m -> !m.shouldSkip()).filter(m -> m.getLineCoverage() < percentThreshold)
				.sorted() // Uses Comparable implementation
				.collect(Collectors.toList());
	}

	/**
	 * Get the N least tested classes.
	 */
	public List<ClassMetrics> getLeastTested(int count) {
		return allMetrics.stream().filter(m -> !m.shouldSkip()).sorted().limit(count).collect(Collectors.toList());
	}

	/**
	 * Get coverage statistics for a specific module.
	 */
	public ModuleStats getModuleStats(String module) {
		List<ClassMetrics> metrics = byModule.getOrDefault(module, Collections.emptyList());
		return new ModuleStats(module, metrics);
	}

	/**
	 * Get coverage statistics for all modules.
	 */
	public Map<String, ModuleStats> getModuleStats() {
		Map<String, ModuleStats> stats = new LinkedHashMap<>();
		for (String module : byModule.keySet()) {
			stats.put(module, getModuleStats(module));
		}
		return stats;
	}

	/**
	 * Get overall coverage statistics.
	 */
	public OverallStats getOverallStats() {
		List<ClassMetrics> nonSkipped = allMetrics.stream().filter(m -> !m.shouldSkip()).collect(Collectors.toList());

		if (nonSkipped.isEmpty()) {
			return new OverallStats(0, 0, 0, 0, 0);
		}

		double avgLine = nonSkipped.stream().mapToInt(ClassMetrics::getLineCoverage).average().orElse(0);

		double avgMethod = nonSkipped.stream().mapToInt(ClassMetrics::getMethodCoverage).average().orElse(0);

		double avgBranch = nonSkipped.stream().mapToInt(ClassMetrics::getBranchCoverage).average().orElse(0);

		int minLine = nonSkipped.stream().mapToInt(ClassMetrics::getLineCoverage).min().orElse(0);

		return new OverallStats(nonSkipped.size(), (int) avgLine, (int) avgMethod, (int) avgBranch, minLine);
	}

	public List<ClassMetrics> getAllMetrics() {
		return new ArrayList<>(allMetrics);
	}

	public Map<String, List<ClassMetrics>> getByModule() {
		return new LinkedHashMap<>(byModule);
	}

	public int getModuleCount() {
		return byModule.size();
	}

	public int getClassCount() {
		return byClass.size();
	}

	/**
	 * Module-level statistics.
	 */
	public static class ModuleStats {
		private final String module;
		private final List<ClassMetrics> metrics;

		public ModuleStats(String module, List<ClassMetrics> metrics) {
			this.module = module;
			this.metrics = new ArrayList<>(metrics);
		}

		public String getModule() {
			return module;
		}
		public int getClassCount() {
			return metrics.size();
		}
		public int getNonSkippedCount() {
			return (int) metrics.stream().filter(m -> !m.shouldSkip()).count();
		}

		public double getAverageLineCoverage() {
			List<ClassMetrics> nonSkipped = metrics.stream().filter(m -> !m.shouldSkip()).collect(Collectors.toList());
			if (nonSkipped.isEmpty())
				return 0;
			return nonSkipped.stream().mapToInt(ClassMetrics::getLineCoverage).average().orElse(0);
		}

		public int getHighCoverageCount(int threshold) {
			return (int) metrics.stream().filter(m -> !m.shouldSkip()).filter(m -> m.getLineCoverage() >= threshold)
					.count();
		}

		public int getMediumCoverageCount(int low, int high) {
			return (int) metrics.stream().filter(m -> !m.shouldSkip())
					.filter(m -> m.getLineCoverage() >= low && m.getLineCoverage() < high).count();
		}

		public int getLowCoverageCount(int threshold) {
			return (int) metrics.stream().filter(m -> !m.shouldSkip()).filter(m -> m.getLineCoverage() < threshold)
					.count();
		}
	}

	/**
	 * Overall project statistics.
	 */
	public static class OverallStats {
		private final int classCount;
		private final int avgLineCoverage;
		private final int avgMethodCoverage;
		private final int avgBranchCoverage;
		private final int minLineCoverage;

		public OverallStats(int classCount, int avgLine, int avgMethod, int avgBranch, int minLine) {
			this.classCount = classCount;
			this.avgLineCoverage = avgLine;
			this.avgMethodCoverage = avgMethod;
			this.avgBranchCoverage = avgBranch;
			this.minLineCoverage = minLine;
		}

		public int getClassCount() {
			return classCount;
		}
		public int getAverageLineCoverage() {
			return avgLineCoverage;
		}
		public int getAverageMethodCoverage() {
			return avgMethodCoverage;
		}
		public int getAverageBranchCoverage() {
			return avgBranchCoverage;
		}
		public int getMinimumLineCoverage() {
			return minLineCoverage;
		}
	}
}
