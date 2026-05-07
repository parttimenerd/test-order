package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.ops.MetricsWorkflow;
import me.bechberger.testorder.ops.TestMetricsExport;

/**
 * Exports test-order metrics as JSON for CI/CD dashboards and reporting tools.
 * <p>
 * Includes test counts, index health, change detection stats, and recommendations.
 * <p>
 * Usage: {@code mvn test-order:metrics}
 */
@Mojo(name = "metrics", aggregator = true)
public class MetricsMojo extends AbstractTestOrderMojo {

	/** Output JSON file. Defaults to target/test-order-metrics.json. */
	@Parameter(property = "testorder.metrics.output",
			defaultValue = "${project.build.directory}/test-order-metrics.json")
	private String outputFile;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;

		Path idxPath = resolveIndexPath();
		Path statePath = ctx.resolveStateFile(stateFile);
		Path testClassesDir = Path.of(project.getBuild().getTestOutputDirectory());

		TestMetricsExport metrics = MetricsWorkflow.generate(
				project.getArtifactId(), idxPath, statePath, testClassesDir,
				buildPluginContext(),
				"Run mvn test -Dtestorder.mode=learn",
				MavenPluginLog.wrap(getLog()));

		Path output = Path.of(outputFile);
		try {
			MetricsWorkflow.writeToFile(metrics, output, MavenPluginLog.wrap(getLog()));
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to write metrics: " + e.getMessage(), e);
		}
	}
}
