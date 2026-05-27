package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.ops.MutationAnalysisOperation;

/**
 * Runs PIT mutation testing scoped to the indexed test classes, computes
 * per-test mutation kill rates, updates the state file, and writes a
 * {@code test-mutation-results.json} report.
 * <p>
 * Designed for nightly / weekly CI runs. Kill rates stored in the state file
 * are automatically picked up by test scoring on subsequent {@code select} /
 * {@code order} runs (when {@code killRateBonus > 0}).
 * <p>
 * Usage: {@code mvn test-order:analyze-mutations}
 */
@Mojo(name = "analyze-mutations", aggregator = true)
public class MutationAnalyzeMojo extends AbstractTestOrderMojo {

	/**
	 * Output path for {@code test-mutation-results.json}. Defaults to
	 * {@code target/test-mutation-results.json} in the project root.
	 */
	@Parameter(property = MavenPluginConfigKeys.MUTATIONS_OUTPUT_FILE)
	private String outputFile;

	/**
	 * Maximum seconds to spend on mutation testing (0 = no limit).
	 */
	@Parameter(property = MavenPluginConfigKeys.MUTATIONS_TIME_BUDGET, defaultValue = "0")
	private int timeBudget;

	/**
	 * Comma-separated glob of production class names to mutate. When unset, the
	 * target classes are derived from the dependency index (all production classes
	 * that at least one test covers).
	 */
	@Parameter(property = MavenPluginConfigKeys.MUTATIONS_TARGET_CLASSES)
	private String targetClasses;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;

		Path idxPath = resolveIndexPath();
		if (!Files.exists(idxPath)) {
			throw new MojoExecutionException("Dependency index not found at " + idxPath
					+ ". Run learn mode first: mvn test -Dtestorder.mode=learn"
					+ "\n  For more details: mvn test-order:diagnose");
		}

		Path projectRoot = project.getBasedir().toPath().toAbsolutePath();
		Path statePath = ctx.resolveStateFile(stateFile);
		Path output = outputFile != null && !outputFile.isBlank()
				? Path.of(outputFile)
				: projectRoot.resolve("target/test-mutation-results.json");

		try {
			MutationAnalysisOperation.run(new MutationAnalysisOperation.Config(idxPath, statePath, output, projectRoot,
					targetClasses, timeBudget, pluginLog()));
		} catch (IOException e) {
			throw new MojoExecutionException("Mutation analysis failed: " + e.getMessage(), e);
		}
	}
}
