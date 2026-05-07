package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.ops.PluginLog;
import me.bechberger.testorder.ops.coverage.CoverageAnalysis;
import me.bechberger.testorder.ops.coverage.CoverageOperation;

/**
 * Mojo goal: coverage — analyzes dependency-based test coverage and generates
 * reports. Thin wrapper around {@link CoverageOperation} in test-order-core.
 */
@Mojo(name = "coverage", aggregator = true)
public class CoverageMojo extends AbstractTestOrderMojo {

	/**
	 * Minimum number of exercising tests for a class to be considered
	 * "well-tested".
	 */
	@Parameter(property = "testorder.coverage.threshold", defaultValue = "2")
	private int threshold;

	/** Output directory for generated coverage reports. */
	@Parameter(property = "testorder.coverage.outputDir", defaultValue = "${project.build.directory}/coverage-reports")
	private String outputDir;

	/** Fail the build if any classes are below the coverage threshold. */
	@Parameter(property = "testorder.coverage.failOnViolation", defaultValue = "false")
	private boolean failOnViolation;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;

		if (threshold < 1) {
			throw new MojoExecutionException("threshold must be >= 1, got " + threshold);
		}

		Path indexPath = resolveIndexPath();

		if (!Files.exists(indexPath)) {
			// Try auto-aggregation from .deps before giving up
			Path depsDirPath = ctx.resolveDepsDir(depsDir);
			if (Files.isDirectory(depsDirPath) && hasDepsFiles(depsDirPath)) {
				try {
					DependencyMap map = DependencyMap.aggregate(depsDirPath);
					if (map.size() > 0) {
						Files.createDirectories(indexPath.getParent());
						map.save(indexPath);
						getLog().info("[test-order] Auto-aggregated " + map.size() + " test classes → " + indexPath);
					} else {
						getLog().warn("[test-order] No dependency index found at " + indexPath
								+ " — run 'mvn test' (learn mode) first.");
						return;
					}
				} catch (IOException e) {
					getLog().warn("[test-order] Failed to auto-aggregate from " + depsDirPath + ": " + e.getMessage());
					getLog().warn("[test-order] No dependency index found — run 'mvn test' first.");
					return;
				}
			} else {
				getLog().warn("[test-order] No dependency index found at " + indexPath
						+ " — run 'mvn test' (learn mode) first.");
				return;
			}
		}

		PluginLog log = MavenPluginLog.wrap(getLog());

		try {
			DependencyMap depMap = DependencyMap.load(indexPath);
			CoverageAnalysis analysis = CoverageOperation.analyze(depMap, log);

			CoverageOperation.writeReports(analysis, Path.of(outputDir), threshold, log);
			CoverageOperation.printSummary(analysis, threshold, System.out);

			if (failOnViolation) {
				int violations = analysis.belowThreshold(threshold).size();
				if (violations > 0) {
					throw new MojoExecutionException("[test-order] Coverage check failed: " + violations
							+ " class(es) below threshold of " + threshold + " exercising tests.");
				}
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to analyze coverage", e);
		}
	}
}
