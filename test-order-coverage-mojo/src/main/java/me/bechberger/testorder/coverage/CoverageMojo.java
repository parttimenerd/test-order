package me.bechberger.testorder.coverage;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.execution.MavenSession;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Mojo goal: coverage
 * Analyzes test coverage across the project and identifies least tested classes.
 * Generates markdown reports and JSON output for CI integration.
 */
@Mojo(name = "coverage", aggregator = true)
public class CoverageMojo extends AbstractMojo {

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Parameter(defaultValue = "${reactorProjects}", readonly = true)
    private List<MavenProject> reactorProjects;

    @Parameter(property = "coverage.threshold", defaultValue = "50")
    private int threshold;

    /** Output directory for generated coverage reports. */
    @Parameter(property = "coverage.outputDir", defaultValue = "${project.build.directory}/coverage-reports")
    private File outputDir;

    /** Output format for coverage reports. Valid values: 'comprehensive' (default, markdown + JSON), 'markdown', 'json'. */
    @Parameter(property = "coverage.outputFormat", defaultValue = "comprehensive")
    private String outputFormat;

    /** Comma-separated list of module names to include in analysis. If not set, all modules are included. Example: '-DincludeModules=core,cli' */
    @Parameter(property = "coverage.includeModules")
    private String includeModules;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // Validate parameters
        CoverageParameterValidator validator = new CoverageParameterValidator(getLog());
        validator.validateThreshold(threshold);
        validator.validateOutputFormat(outputFormat);
        validator.validateOutputDirectory(outputDir.getAbsolutePath(), "outputDir");
        validator.validateIncludeModules(includeModules);

        getLog().info("Starting coverage analysis with threshold: " + threshold + "%");

        try {
            List<ClassMetrics> allMetrics = new ArrayList<>();
            Map<String, Integer> testCounts = new HashMap<>();

            // Parse coverage reports from all modules
            for (MavenProject project : reactorProjects) {
                String moduleName = project.getArtifactId();

                // Skip if not in includeModules filter
                if (!shouldIncludeModule(moduleName)) {
                    getLog().debug("Skipping module: " + moduleName);
                    continue;
                }

                getLog().debug("Processing module: " + moduleName);

                // Parse JaCoCo reports
                File jacocoDir = null;
                if (project.getBuild() != null) {
                    jacocoDir = new File(project.getBuild().getDirectory(), "site/jacoco");
                } else {
                    getLog().warn("Build directory not available for module: " + moduleName);
                    continue;
                }

                File jacocoXml = new File(jacocoDir, "index.xml");

                if (jacocoXml.exists()) {
                    JaCoCoReportParser jacocoParser = new JaCoCoReportParser();
                    List<ClassMetrics> metrics = jacocoParser.parse(jacocoXml);

                    // Set module name for each metric
                    for (ClassMetrics metric : metrics) {
                        ClassMetrics updated = new ClassMetrics(
                                metric.getFullyQualifiedName(),
                                moduleName,
                                metric.getPackageName(),
                                metric.getClassName(),
                                metric.getLineCoverage(),
                                metric.getMethodCoverage(),
                                metric.getBranchCoverage(),
                                metric.getStatementsCovered(),
                                metric.getStatementsTotal(),
                                metric.getMethodsCovered(),
                                metric.getMethodsTotal(),
                                metric.getTestCount(),
                                metric.getTestNames(),
                                metric.isAbstract(),
                                metric.isInterface(),
                                metric.isEnum()
                        );
                        allMetrics.add(updated);
                    }
                    getLog().debug("  Found " + metrics.size() + " classes in JaCoCo report");
                }

                // Parse Surefire test reports
                File sureFireDir = null;
                if (project.getBuild() != null) {
                    sureFireDir = new File(project.getBuild().getDirectory(), "surefire-reports");
                } else {
                    getLog().debug("Build directory not available for Surefire reports in module: " + moduleName);
                    continue;
                }

                if (sureFireDir.exists()) {
                    SurefireReportParser sureFireParser = new SurefireReportParser();
                    Map<String, Integer> counts = sureFireParser.parseTestCounts(sureFireDir);
                    testCounts.putAll(counts);
                    getLog().debug("  Found " + counts.size() + " test classes in Surefire reports");
                }
            }

            if (allMetrics.isEmpty()) {
                getLog().warn("No coverage data found. Please run tests and ensure JaCoCo reports are generated.");
                return;
            }

            // Merge test counts into metrics
            List<ClassMetrics> mergedMetrics = new ArrayList<>();
            for (ClassMetrics metric : allMetrics) {
                Integer testCount = testCounts.getOrDefault(metric.getFullyQualifiedName(), 0);
                ClassMetrics merged = new ClassMetrics(
                        metric.getFullyQualifiedName(),
                        metric.getModule(),
                        metric.getPackageName(),
                        metric.getClassName(),
                        metric.getLineCoverage(),
                        metric.getMethodCoverage(),
                        metric.getBranchCoverage(),
                        metric.getStatementsCovered(),
                        metric.getStatementsTotal(),
                        metric.getMethodsCovered(),
                        metric.getMethodsTotal(),
                        testCount,
                        metric.getTestNames(),
                        metric.isAbstract(),
                        metric.isInterface(),
                        metric.isEnum()
                );
                mergedMetrics.add(merged);
            }

            // Create coverage report
            CoverageReport report = new CoverageReport(mergedMetrics);
            CoverageReporter reporter = new CoverageReporter(report);
            MarkdownGenerator generator = new MarkdownGenerator(reporter);

            // Generate output
            outputDir.mkdirs();
            generateOutput(generator, report);

            // Log summary
            CoverageReport.OverallStats stats = report.getOverallStats();
            getLog().info("");
            getLog().info("=== Coverage Analysis Complete ===");
            getLog().info("Average Coverage: " + String.format("%.1f%%", stats.getAverageLineCoverage()));
            getLog().info("Total Classes: " + stats.getClassCount());
            getLog().info("Classes Below " + threshold + "%: " + report.getBelowThreshold(threshold).size());
            getLog().info("Reports written to: " + outputDir.getAbsolutePath());
            getLog().info("");

        } catch (IOException | ParserConfigurationException | SAXException e) {
            throw new MojoExecutionException("Failed to analyze coverage", e);
        }
    }

    private boolean shouldIncludeModule(String moduleName) {
        if (includeModules == null || includeModules.isEmpty()) {
            return true;
        }

        String[] modules = includeModules.split(",");
        for (String module : modules) {
            if (moduleName.equals(module.trim())) {
                return true;
            }
        }
        return false;
    }

    private void generateOutput(MarkdownGenerator generator, CoverageReport report) throws IOException {
        if ("json".equalsIgnoreCase(outputFormat)) {
            File jsonFile = new File(outputDir, "coverage-metrics.json");
            generator.writeCoverageMetricsJson(jsonFile, threshold);
            getLog().info("Generated: " + jsonFile.getName());
        } else if ("module".equalsIgnoreCase(outputFormat)) {
            File moduleFile = new File(outputDir, "COVERAGE_BY_MODULE.md");
            generator.writeCoverageByModule(moduleFile);
            getLog().info("Generated: " + moduleFile.getName());
        } else if ("least-tested".equalsIgnoreCase(outputFormat)) {
            File leastTestedFile = new File(outputDir, "LEAST_TESTED_CLASSES.md");
            generator.writeLeastTestedClasses(leastTestedFile, threshold);
            getLog().info("Generated: " + leastTestedFile.getName());
        } else {
            // Default: comprehensive report
            generator.writeAllReports(outputDir, threshold);
            getLog().info("Generated: COVERAGE_BY_MODULE.md");
            getLog().info("Generated: LEAST_TESTED_CLASSES.md");
            getLog().info("Generated: COVERAGE_RECOMMENDATIONS.md");
            getLog().info("Generated: coverage-metrics.json");
        }
    }
}
