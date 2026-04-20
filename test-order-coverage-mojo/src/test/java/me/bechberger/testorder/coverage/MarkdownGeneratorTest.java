package me.bechberger.testorder.coverage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static me.bechberger.testorder.coverage.TestMetricsBuilder.create;
import static org.junit.jupiter.api.Assertions.*;

public class MarkdownGeneratorTest {

    private Path tempDir;
    private MarkdownGenerator generator;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("markdown-test");

        List<ClassMetrics> metrics = new ArrayList<>();
        metrics.add(create("pkg.ClassA", "module1", 75, 80, 72, 10));
        metrics.add(create("pkg.ClassB", "module1", 40, 35, 42, 3));
        metrics.add(create("pkg2.ClassC", "module2", 85, 90, 88, 12));

        CoverageReport report = new CoverageReport(metrics);
        CoverageReporter reporter = new CoverageReporter(report);
        generator = new MarkdownGenerator(reporter);
    }

    @Test
    void testWriteCoverageByModule() throws IOException {
        File outputFile = new File(tempDir.toFile(), "coverage.md");
        generator.writeCoverageByModule(outputFile);

        assertTrue(outputFile.exists());
        String content = Files.readString(outputFile.toPath());
        assertFalse(content.isEmpty());
        assertTrue(content.contains("Coverage by Module"));
        assertTrue(content.contains("module1"));
        assertTrue(content.contains("module2"));
    }

    @Test
    void testWriteLeastTestedClasses() throws IOException {
        File outputFile = new File(tempDir.toFile(), "least-tested.md");
        generator.writeLeastTestedClasses(outputFile, 50);

        assertTrue(outputFile.exists());
        String content = Files.readString(outputFile.toPath());
        assertFalse(content.isEmpty());
        assertTrue(content.contains("ClassB")); // 40% coverage, below 50%
    }

    @Test
    void testWriteRecommendations() throws IOException {
        File outputFile = new File(tempDir.toFile(), "recommendations.md");
        generator.writeRecommendations(outputFile, 50);

        assertTrue(outputFile.exists());
        String content = Files.readString(outputFile.toPath());
        assertFalse(content.isEmpty());
        assertTrue(content.contains("Recommendations"));
    }

    @Test
    void testWriteCoverageMetricsJson() throws IOException {
        File outputFile = new File(tempDir.toFile(), "metrics.json");
        generator.writeCoverageMetricsJson(outputFile, 50);

        assertTrue(outputFile.exists());
        String content = Files.readString(outputFile.toPath());
        assertFalse(content.isEmpty());
        assertTrue(content.contains("threshold"));
        assertTrue(content.contains("moduleStatistics"));
    }

    @Test
    void testWriteAllReports() throws IOException {
        generator.writeAllReports(tempDir.toFile(), 50);

        File moduleFile = new File(tempDir.toFile(), "COVERAGE_BY_MODULE.md");
        File leastTestedFile = new File(tempDir.toFile(), "LEAST_TESTED_CLASSES.md");
        File recommendationsFile = new File(tempDir.toFile(), "COVERAGE_RECOMMENDATIONS.md");
        File jsonFile = new File(tempDir.toFile(), "coverage-metrics.json");

        assertTrue(moduleFile.exists());
        assertTrue(leastTestedFile.exists());
        assertTrue(recommendationsFile.exists());
        assertTrue(jsonFile.exists());
    }

    @Test
    void testWriteComprehensiveReport() throws IOException {
        File outputFile = new File(tempDir.toFile(), "comprehensive.md");
        generator.writeComprehensiveReport(outputFile, 50);

        assertTrue(outputFile.exists());
        String content = Files.readString(outputFile.toPath());
        assertFalse(content.isEmpty());
        assertTrue(content.contains("Coverage Summary"));
        assertTrue(content.contains("Coverage by Module"));
        assertTrue(content.contains("Recommendations"));
    }

    @Test
    void testCreateOutputDirectory() throws IOException {
        File newDir = new File(tempDir.toFile(), "new/nested/dir");
        assertFalse(newDir.exists());

        File outputFile = new File(newDir, "test.md");
        generator.writeCoverageByModule(outputFile);

        assertTrue(newDir.exists());
        assertTrue(outputFile.exists());
    }

    @Test
    void testJsonFormatting() throws IOException {
        File outputFile = new File(tempDir.toFile(), "metrics.json");
        generator.writeCoverageMetricsJson(outputFile, 50);

        String content = Files.readString(outputFile.toPath());
        assertTrue(content.contains("\"threshold\""));
        assertTrue(content.contains("\"moduleStatistics\""));
        assertTrue(content.contains("\"timestamp\""));
    }
}
