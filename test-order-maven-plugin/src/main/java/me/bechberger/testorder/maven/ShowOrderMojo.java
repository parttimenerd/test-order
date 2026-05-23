package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

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
		getLog().warn("[test-order] DEPRECATED: 'test-order:show-order' has been replaced by 'test-order:show'."
				+ " Please update your scripts and POM configurations.");

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

	/**
	 * Resolves the effective change mode for structural diffing. Since-last-run and
	 * explicit modes have no reliable structural-diff baseline, so structural
	 * complexity is disabled for those modes rather than using a mismatched git
	 * comparison.
	 */
	protected String resolveStructuralDiffMode() {
		if (changeMode == null || changeMode.isBlank()) {
			return null;
		}
		return switch (changeMode) {
			case "since-last-commit" -> "since-last-commit";
			case "uncommitted" -> "uncommitted";
			case "explicit", "since-last-run" -> null;
			case "auto" -> {
				if (changedClasses != null && !changedClasses.isBlank()) {
					yield null;
				}
				yield Files.exists(ctx.resolveHashFile(hashFile)) ? null : "since-last-commit";
			}
			default -> null;
		};
	}

	protected List<Path> resolveSourceRoots() {
		LinkedHashSet<Path> roots = new LinkedHashSet<>();
		roots.add(resolveSourceRoot());

		Path projectRoot = project.getBasedir().toPath().toAbsolutePath();
		Path kotlinRoot = projectRoot.resolve("src/main/kotlin");
		if (Files.isDirectory(kotlinRoot)) {
			roots.add(kotlinRoot);
		}

		return roots.stream().filter(Objects::nonNull).filter(Files::isDirectory).toList();
	}

}
