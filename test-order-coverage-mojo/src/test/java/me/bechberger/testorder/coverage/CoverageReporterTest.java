package me.bechberger.testorder.coverage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static me.bechberger.testorder.coverage.TestMetricsBuilder.create;
import static org.junit.jupiter.api.Assertions.*;

public class CoverageReporterTest {

    private CoverageReport report;
    private CoverageReporter reporter;

    @BeforeEach
    void setUp() {
        List<ClassMetrics> metrics = new ArrayList<>();
        metrics.add(create("com.app.Service", "core", 85, 90, 88, 15));
        metrics.add(create("com.app.Util", "core", 45, 40, 42, 3));
        metrics.add(create("com.app.Handler", "cli", 70, 75, 68, 8));
        metrics.add(create("com.app.Config", "cli", 55, 50, 58, 5));

        report = new CoverageReport(metrics);
        reporter = new CoverageReporter(report);
    }

    @Test
    void testGenerateSummary() {
        String summary = reporter.generateSummary(50);

        assertNotNull(summary);
        assertFalse(summary.isEmpty());
        assertTrue(summary.contains("Coverage Summary"));
        assertTrue(summary.contains("Average Coverage"));
        assertTrue(summary.contains("Total Classes"));
    }

    @Test
    void testGenerateDetailedReport() {
        String detailed = reporter.generateDetailedReport(50);

        assertNotNull(detailed);
        assertFalse(detailed.isEmpty());
        assertTrue(detailed.contains("Coverage Summary"));
        assertTrue(detailed.contains("Important") || detailed.contains("Critical"));
    }

    @Test
    void testGenerateModuleReport() {
        String moduleReport = reporter.generateModuleReport();

        assertNotNull(moduleReport);
        assertFalse(moduleReport.isEmpty());
        assertTrue(moduleReport.contains("Coverage by Module"));
        assertTrue(moduleReport.contains("core"));
        assertTrue(moduleReport.contains("cli"));
        assertTrue(moduleReport.contains("Overall"));
    }

    @Test
    void testGenerateRecommendations() {
        String recommendations = reporter.generateRecommendations(50);

        assertNotNull(recommendations);
        assertFalse(recommendations.isEmpty());
        assertTrue(recommendations.contains("Recommendations"));
    }

    @Test
    void testGetModuleStatistics() {
        Map<String, Map<String, Object>> stats = reporter.getModuleStatistics();

        assertNotNull(stats);
        assertFalse(stats.isEmpty());
        assertTrue(stats.containsKey("core"));
        assertTrue(stats.containsKey("cli"));

        Map<String, Object> coreStats = stats.get("core");
        assertTrue(coreStats.containsKey("avgCoverage"));
        assertTrue(coreStats.containsKey("classCount"));
        assertTrue(coreStats.containsKey("high"));
        assertTrue(coreStats.containsKey("medium"));
        assertTrue(coreStats.containsKey("low"));
    }

    @Test
    void testReportIncludesAllClasses() {
        String summary = reporter.generateSummary(50);

        assertTrue(summary.contains("4"));  // Total classes
    }

    @Test
    void testReportFiltersThreshold() {
        String detailed = reporter.generateDetailedReport(80);

        // Should include only classes below 80%
        assertFalse(detailed.contains("Service")); // 85% coverage, above threshold
    }
}
