package me.bechberger.testorder.coverage;

import java.util.*;
import java.util.stream.Collectors;

public class CoverageReporter {
    private final LeastTestedClassifier classifier;
    private final CoverageReport report;

    public CoverageReporter(CoverageReport report) {
        this.report = report;
        this.classifier = new LeastTestedClassifier(report);
    }

    /**
     * Generate a summary report
     */
    public String generateSummary(int threshold) {
        StringBuilder sb = new StringBuilder();
        CoverageReport.OverallStats overall = report.getOverallStats();

        sb.append("# Coverage Summary\n\n");
        sb.append("## Overall Metrics\n\n");
        sb.append(String.format("- **Average Coverage**: %d%%\n", overall.getAverageLineCoverage()));
        sb.append(String.format("- **Minimum Coverage**: %d%%\n", overall.getMinimumLineCoverage()));
        sb.append(String.format("- **Total Classes**: %d\n", overall.getClassCount()));

        Map<String, Object> thresholdSummary = classifier.getThresholdSummary(threshold);
        sb.append("\n## Coverage Distribution (threshold: ").append(threshold).append("%)\n\n");
        for (Map.Entry<String, Object> entry : thresholdSummary.entrySet()) {
            if (!entry.getKey().equals("threshold")) {
                sb.append(String.format("- **%s**: %s\n", entry.getKey(), entry.getValue()));
            }
        }

        return sb.toString();
    }

    /**
     * Generate detailed report for classes below threshold
     */
    public String generateDetailedReport(int threshold) {
        StringBuilder sb = new StringBuilder();

        sb.append(generateSummary(threshold));

        Map<LeastTestedClassifier.Severity, List<ClassMetrics>> classified = 
                classifier.classifyByThreshold(threshold);

        for (LeastTestedClassifier.Severity severity : LeastTestedClassifier.Severity.values()) {
            List<ClassMetrics> classes = classified.getOrDefault(severity, new ArrayList<>());
            if (classes.isEmpty()) continue;

            sb.append("\n## ").append(severity.getLabel())
                    .append(" (").append(classes.size()).append(" classes)\n\n");

            for (ClassMetrics metric : classes) {
                sb.append(String.format("- `%s` (%d%%) [Module: %s] Tests: %d\n",
                        metric.getFullyQualifiedName(),
                        metric.getLineCoverage(),
                        metric.getModule(),
                        metric.getTestCount()));
            }
        }

        return sb.toString();
    }

    /**
     * Generate module-level report
     */
    public String generateModuleReport() {
        StringBuilder sb = new StringBuilder();

        sb.append("# Coverage by Module\n\n");
        sb.append("| Module | Avg Coverage | Classes | High (≥80%) | Medium (50-80%) | Low (<50%) |\n");
        sb.append("|--------|--------------|---------|-------------|-----------------|------------|\n");

        Map<String, CoverageReport.ModuleStats> moduleStats = report.getModuleStats();
        double totalAvg = 0;
        int moduleCount = 0;

        for (Map.Entry<String, CoverageReport.ModuleStats> entry : moduleStats.entrySet()) {
            CoverageReport.ModuleStats stats = entry.getValue();
            double avgCov = stats.getAverageLineCoverage();
            totalAvg += avgCov;
            moduleCount++;

            sb.append(String.format("| %s | %d%% | %d | %d | %d | %d |\n",
                    entry.getKey(),
                    (int)avgCov,
                    stats.getClassCount(),
                    stats.getHighCoverageCount(80),
                    stats.getMediumCoverageCount(50, 80),
                    stats.getLowCoverageCount(50)));
        }

        if (moduleCount > 0) {
            CoverageReport.OverallStats overall = report.getOverallStats();
            sb.append(String.format("| **Overall** | **%d%%** | **%d** | **%d** | **%d** | **%d** |\n",
                    (int)(totalAvg / moduleCount),
                    overall.getClassCount(),
                    0,  // placeholder
                    0,  // placeholder
                    0)); // placeholder
        }

        return sb.toString();
    }

    /**
     * Generate recommendations for improving coverage
     */
    public String generateRecommendations(int threshold) {
        StringBuilder sb = new StringBuilder();
        Map<LeastTestedClassifier.Severity, List<ClassMetrics>> classified = 
                classifier.classifyByThreshold(threshold);

        sb.append("# Coverage Improvement Recommendations\n\n");

        List<ClassMetrics> critical = classified.getOrDefault(LeastTestedClassifier.Severity.CRITICAL, new ArrayList<>());
        if (!critical.isEmpty()) {
            sb.append("## Priority 1: Critical Classes (< 30%)\n\n");
            sb.append("These classes have minimal test coverage and should be prioritized:\n\n");
            for (ClassMetrics metric : critical) {
                sb.append(String.format("- **%s**: Currently at %d%% coverage\n",
                        metric.getFullyQualifiedName(),
                        metric.getLineCoverage()));
            }
        }

        List<ClassMetrics> important = classified.getOrDefault(LeastTestedClassifier.Severity.IMPORTANT, new ArrayList<>());
        if (!important.isEmpty()) {
            sb.append("\n## Priority 2: Important Classes (30-50%)\n\n");
            sb.append("These classes have low coverage and should be addressed next:\n\n");
            for (ClassMetrics metric : important.stream().limit(10).collect(Collectors.toList())) {
                sb.append(String.format("- **%s**: Currently at %d%% coverage\n",
                        metric.getFullyQualifiedName(),
                        metric.getLineCoverage()));
            }
            if (important.size() > 10) {
                sb.append(String.format("- ... and %d more\n", important.size() - 10));
            }
        }

        return sb.toString();
    }

    /**
     * Get statistics by module
     */
    public Map<String, Map<String, Object>> getModuleStatistics() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();

        for (Map.Entry<String, CoverageReport.ModuleStats> entry : report.getModuleStats().entrySet()) {
            Map<String, Object> stats = new LinkedHashMap<>();
            CoverageReport.ModuleStats ms = entry.getValue();

            stats.put("avgCoverage", String.format("%.1f%%", ms.getAverageLineCoverage()));
            stats.put("classCount", ms.getClassCount());
            stats.put("high", ms.getHighCoverageCount(80));
            stats.put("medium", ms.getMediumCoverageCount(50, 80));
            stats.put("low", ms.getLowCoverageCount(50));

            result.put(entry.getKey(), stats);
        }

        return result;
    }
}
