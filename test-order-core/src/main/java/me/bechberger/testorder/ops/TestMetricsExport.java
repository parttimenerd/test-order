package me.bechberger.testorder.ops;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.bechberger.util.json.PrettyPrinter;

/**
 * Test execution metrics for export and reporting.
 * Captures performance data, coverage, and test-order effectiveness.
 */
public record TestMetricsExport(
        String timestamp,
        String project,
        String mode,
        Map<String, Object> metrics,
        Map<String, Object> changesDetected,
        Map<String, Object> indexHealth,
        List<String> recommendations) {

    /**
     * Builder for fluent metrics construction.
     */
    public static class Builder {
        private final String project;
        private final String mode;
        private final Map<String, Object> metrics = new HashMap<>();
        private final Map<String, Object> changesDetected = new HashMap<>();
        private final Map<String, Object> indexHealth = new HashMap<>();
        private final List<String> recommendations = new java.util.ArrayList<>();

        public Builder(String project, String mode) {
            this.project = project;
            this.mode = mode;
        }

        public Builder testMetrics(int totalTests, int selectedTests, int deferredTests) {
            metrics.put("total_tests", totalTests);
            metrics.put("tests_selected", selectedTests);
            metrics.put("tests_deferred", deferredTests);
            return this;
        }

        public Builder executionTime(double seconds) {
            metrics.put("execution_time_seconds", seconds);
            return this;
        }

        public Builder estimatedSavings(double savedSeconds, double percentage) {
            metrics.put("estimated_savings_seconds", savedSeconds);
            metrics.put("savings_percentage", Math.round(percentage * 100.0) / 100.0);
            return this;
        }

        public Builder indexAge(long seconds) {
            metrics.put("index_age_seconds", seconds);
            return this;
        }

        public Builder coverage(double classes, double methods, double lines) {
            Map<String, Object> coverage = new HashMap<>();
            coverage.put("classes", classes);
            coverage.put("methods", methods);
            coverage.put("lines", lines);
            metrics.put("coverage", coverage);
            return this;
        }

        public Builder changesDetected(int classes, int testClasses, int methods) {
            changesDetected.put("classes", classes);
            changesDetected.put("test_classes", testClasses);
            changesDetected.put("methods", methods);
            return this;
        }

        public Builder indexHealth(long totalEntries, long staleEntries) {
            indexHealth.put("total_entries", totalEntries);
            indexHealth.put("stale_entries", staleEntries);
            if (staleEntries > 0) {
                long percentage = (staleEntries * 100) / Math.max(1, totalEntries);
                indexHealth.put("stale_percentage", percentage);
                if (percentage > 10) {
                    addRecommendation("Run testOrderCompact to remove " + staleEntries + " stale entries");
                }
            }
            return this;
        }

        public Builder addRecommendation(String recommendation) {
            recommendations.add(recommendation);
            return this;
        }

        public TestMetricsExport build() {
            return new TestMetricsExport(
                    Instant.now().toString(),
                    project,
                    mode,
                    Map.copyOf(metrics),
                    Map.copyOf(changesDetected),
                    Map.copyOf(indexHealth),
                    List.copyOf(recommendations));
        }
    }

    /**
     * Convert to a map for JSON serialization.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("timestamp", timestamp);
        map.put("project", project);
        map.put("mode", mode);
        map.put("metrics", Map.copyOf(metrics));
        map.put("changes_detected", Map.copyOf(changesDetected));
        map.put("index_health", Map.copyOf(indexHealth));
        if (!recommendations.isEmpty()) {
            map.put("recommendations", List.copyOf(recommendations));
        }
        return map;
    }

    /**
     * Export metrics as JSON string.
     */
    public String toJson() {
        return PrettyPrinter.prettyPrint(toMap());
    }

    /**
     * Export metrics as compact JSON.
     */
    public String toCompactJson() {
        return PrettyPrinter.compactPrint(toMap());
    }
}
