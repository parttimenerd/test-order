package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.changes.ChangeDetectionSupport;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.workflows.ShowOrderWorkflow;

/**
 * Displays the computed test execution order without running any tests.
 * <p>
 * Usage: {@code mvn test-order:show-order}
 *
 * @deprecated Use {@code mvn test-order:show} instead. This goal will be
 *             removed in a future release.
 */
@Deprecated
@Mojo(name = "show-order", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES, aggregator = true)
public class ShowOrderMojo extends AbstractTestOrderMojo {

	/**
	 * Print a full per-test score breakdown instead of the compact table.
	 */
	@Parameter(property = MavenPluginConfigKeys.SHOW_ORDER_EXPLAIN, defaultValue = "false")
	protected boolean explain;

	/**
	 * Show full class names instead of abbreviated package prefixes.
	 */
	@Parameter(property = MavenPluginConfigKeys.SHOW_ORDER_FULL_NAMES, defaultValue = "false")
	protected boolean fullNames;

	/** Number of top-scored test classes to always include (-1 = all affected). */
	@Parameter(property = MavenPluginConfigKeys.SELECT_TOP_N, defaultValue = "-1")
	private int topN;

	/** Number of random fast tests to include for coverage diversity. */
	@Parameter(property = MavenPluginConfigKeys.SELECT_RANDOM_M, defaultValue = "10")
	private int randomM;

	/** Random seed for reproducible selection (optional). */
	@Parameter(property = MavenPluginConfigKeys.SELECT_SEED)
	private Long seed;

	/**
	 * Returns the structural-diff mode string to use for this invocation, or
	 * {@code null} when structural diff is not applicable.
	 * <p>
	 * For {@code auto} mode: returns {@code "since-last-commit"} when no hash
	 * snapshot exists yet (first run), and {@code null} when a snapshot is
	 * available (hash-based comparison will be used instead).
	 */
	protected String resolveStructuralDiffMode() {
		Path hashFilePath = ctx.resolveHashFile(hashFile);
		try {
			var mode = ChangeDetectionSupport.resolveMode(changeMode, hashFilePath);
			return switch (mode) {
				case SINCE_LAST_COMMIT -> "since-last-commit";
				default -> null;
			};
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * show-order allows explicit mode with no changedClasses (means "no changes").
	 */
	@Override
	protected void validateParameters() throws MojoExecutionException {
		ParameterValidator validator = new ParameterValidator(getLog());
		validator.validateChangeMode(changeMode);
		// Skip validateExplicitModeRequirements — empty changedClasses is valid for
		// show-order
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

		// auto-aggregate if needed
		if (!Files.exists(idxPath)) {
			autoAggregateOrFail(idxPath);
		}

		// auto-enable explain when debug mode is active (R13-6)
		boolean effectiveExplain = explain
				|| "true".equalsIgnoreCase(project.getProperties().getProperty("testorder.debug"))
				|| "true".equalsIgnoreCase(System.getProperty("testorder.debug"));

		PluginContext pctx = buildPluginContextBuilder().topN(topN).randomM(randomM).seed(seed).build();

		try {
			ShowOrderWorkflow.printReportWithSelectionPreview(pctx, System.out, effectiveExplain, fullNames, true,
					true);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to compute test order", e);
		}
	}

}
