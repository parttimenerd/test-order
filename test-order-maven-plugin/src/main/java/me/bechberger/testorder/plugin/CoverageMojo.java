package me.bechberger.testorder.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
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
public class CoverageMojo extends AbstractMojo {

	/** Path to the dependency index file. */
	@Parameter(property = "testorder.index", defaultValue = ".test-order/test-dependencies.lz4")
	private File indexFile;

	/**
	 * Minimum number of exercising tests for a class to be considered
	 * "well-tested".
	 */
	@Parameter(property = "coverage.threshold", defaultValue = "2")
	private int threshold;

	/** Output directory for generated coverage reports. */
	@Parameter(property = "coverage.outputDir", defaultValue = "${project.build.directory}/coverage-reports")
	private File outputDir;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (threshold < 1) {
			throw new MojoExecutionException("threshold must be >= 1, got " + threshold);
		}

		Path indexPath = indexFile.toPath();
		if (!indexPath.isAbsolute()) {
			indexPath = Path.of(System.getProperty("user.dir")).resolve(indexPath);
		}

		if (!indexPath.toFile().exists()) {
			getLog().warn("No dependency index found at " + indexPath + " — run test-order:learn first.");
			return;
		}

		PluginLog log = MavenPluginLog.wrap(getLog());

		try {
			DependencyMap depMap = DependencyMap.load(indexPath);
			CoverageAnalysis analysis = CoverageOperation.analyze(depMap, log);

			CoverageOperation.writeReports(analysis, outputDir.toPath(), threshold, log);
			CoverageOperation.printSummary(analysis, threshold, System.out);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to analyze coverage", e);
		}
	}
}
