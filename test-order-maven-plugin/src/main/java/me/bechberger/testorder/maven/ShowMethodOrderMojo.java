package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.workflows.ShowMethodOrderWorkflow;

/**
 * Displays the computed test method execution order within each test class.
 * <p>
 * Usage: {@code mvn test-order:show-method-order}
 * <p>
 * Requires method ordering to be enabled and at least one prior test run with
 * method-level telemetry.
 *
 * @deprecated Use {@code mvn test-order:show -Dtestorder.show.methods=true}
 *             instead. This goal will be removed in a future release.
 */
@Deprecated
@Mojo(name = "show-method-order", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES, aggregator = true)
public class ShowMethodOrderMojo extends AbstractTestOrderMojo {

	/**
	 * Print a full per-method score breakdown instead of the compact table.
	 */
	@Parameter(property = "testorder.showMethodOrder.explain", defaultValue = "false")
	protected boolean explain;

	@Override
	protected void validateParameters() throws MojoExecutionException {
		ParameterValidator validator = new ParameterValidator(getLog());
		validator.validateChangeMode(changeMode);
		if (weightsFile != null && !weightsFile.isBlank()) {
			validator.validateFilePath(weightsFile, "weightsFile");
		}
	}

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		Path idxPath = resolveIndexPath();

		if (!Files.exists(idxPath)) {
			autoAggregateOrFail(idxPath);
		}

		PluginContext pctx = buildPluginContextBuilder().methodOrderingEnabled(true).build();

		try {
			ShowMethodOrderWorkflow.printReport(pctx, System.out, explain);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to compute method order", e);
		}
	}
}
