package me.bechberger.testorder.coverage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static me.bechberger.testorder.coverage.TestMetricsBuilder.create;
import static org.junit.jupiter.api.Assertions.*;

public class LeastTestedClassifierTest {

    private CoverageReport report;
    private LeastTestedClassifier classifier;

    @BeforeEach
    void setUp() {
        List<ClassMetrics> metrics = new ArrayList<>();
        metrics.add(create("pkg.Critical", "module1", 15, 10, 20, 1));
        metrics.add(create("pkg.Important", "module1", 35, 40, 30, 2));
        metrics.add(create("pkg.Review", "module1", 60, 65, 55, 5));
        metrics.add(create("pkg.Good", "module1", 85, 90, 88, 10));
        metrics.add(create("pkg2.LowCov", "module2", 25, 20, 30, 3));

        report = new CoverageReport(metrics);
        classifier = new LeastTestedClassifier(report);
    }

    @Test
    void testClassifyBySeverity() {
        Map<LeastTestedClassifier.Severity, List<ClassMetrics>> classified =
                classifier.classifyByThreshold(50);

        List<ClassMetrics> critical = classified.get(LeastTestedClassifier.Severity.CRITICAL);
        List<ClassMetrics> important = classified.get(LeastTestedClassifier.Severity.IMPORTANT);
        List<ClassMetrics> review = classified.get(LeastTestedClassifier.Severity.REVIEW);

        assertNotNull(critical);
        assertEquals(2, critical.size()); // Critical: 15%, 25%
        assertTrue(critical.get(0).getFullyQualifiedName().equals("pkg.Critical"));
        assertTrue(critical.get(1).getFullyQualifiedName().equals("pkg2.LowCov"));

        assertNotNull(important);
        assertEquals(1, important.size()); // Important: 35%
        assertEquals("pkg.Important", important.get(0).getFullyQualifiedName());

        assertNull(review); // No classes in 50-70% range when threshold=50
    }

    @Test
    void testSeverityClassification() {
        assertEquals(LeastTestedClassifier.Severity.CRITICAL, LeastTestedClassifier.Severity.classify(25));
        assertEquals(LeastTestedClassifier.Severity.IMPORTANT, LeastTestedClassifier.Severity.classify(40));
        assertEquals(LeastTestedClassifier.Severity.REVIEW, LeastTestedClassifier.Severity.classify(60));
        assertEquals(LeastTestedClassifier.Severity.ACCEPTABLE, LeastTestedClassifier.Severity.classify(90));
    }

    @Test
    void testGetLeastTested() {
        List<ClassMetrics> leastTested = classifier.getLeastTested(3);

        assertEquals(3, leastTested.size());
        assertEquals(15, leastTested.get(0).getLineCoverage());
        assertEquals(25, leastTested.get(1).getLineCoverage());
        assertEquals(35, leastTested.get(2).getLineCoverage());
    }

    @Test
    void testClassifyByModule() {
        Map<String, List<ClassMetrics>> byModule = classifier.classifyByModule(50);

        assertEquals(2, byModule.size());
        assertTrue(byModule.containsKey("module1"));
        assertTrue(byModule.containsKey("module2"));

        List<ClassMetrics> module1 = byModule.get("module1");
        assertEquals(2, module1.size()); // Critical and Important
        assertEquals(15, module1.get(0).getLineCoverage());
    }

    @Test
    void testClassifyByPackage() {
        Map<String, List<ClassMetrics>> byPackage = classifier.classifyByPackage(50);

        assertEquals(2, byPackage.size());
        assertTrue(byPackage.containsKey("pkg"));
        assertTrue(byPackage.containsKey("pkg2"));

        List<ClassMetrics> pkg = byPackage.get("pkg");
        assertEquals(2, pkg.size()); // Critical and Important
    }

    @Test
    void testThresholdSummary() {
        Map<String, Object> summary = classifier.getThresholdSummary(50);

        assertEquals(50, summary.get("threshold"));
        assertEquals(3, summary.get("totalClassesBelow"));
        assertEquals(5, summary.get("totalClasses"));
        assertEquals(2, summary.get("Critical"));
        assertEquals(1, summary.get("Important"));
    }

    @Test
    void testSortingByCoverage() {
        List<ClassMetrics> leastTested = classifier.getLeastTested(5);

        for (int i = 0; i < leastTested.size() - 1; i++) {
            assertTrue(leastTested.get(i).getLineCoverage() <=
                    leastTested.get(i + 1).getLineCoverage());
        }
    }
}
