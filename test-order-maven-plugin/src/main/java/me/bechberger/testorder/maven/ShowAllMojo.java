package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.WarnOnce;
import me.bechberger.testorder.ops.workflows.ShowJsonFormatter;
import me.bechberger.testorder.ops.workflows.ShowWorkflow;

/**
 * Shows the full test-order report: class order, method order (if data exists),
 * and ML health analysis (if history exists). Use {@code mvn test-order:show}
 * for a concise class-only view.
 *
 * <p>
 * Method order is shown when per-method telemetry has been collected (requires
 * {@code MEMBER} or {@code METHOD} instrumentation mode). ML health is shown
 * when enough run history has been accumulated.
 *
 * <p>
 * Usage examples:
 *
 * <pre>
 * mvn test-order:show-all                              # all available sections
 * mvn test-order:show-all -Dtestorder.show.explain=true  # verbose score breakdown
 * mvn test-order:show-all -Dtestorder.show.format=json   # machine-readable
 * </pre>
 */
@Mojo(name = "show-all", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES, aggregator = true)
public class ShowAllMojo extends AbstractTestOrderMojo {

	/** Print a full per-test score breakdown instead of the compact table. */
	@Parameter(property = MavenPluginConfigKeys.SHOW_EXPLAIN, defaultValue = "false")
	private boolean explain;

	/** Show full class names instead of abbreviated package prefixes. */
	@Parameter(property = MavenPluginConfigKeys.SHOW_ORDER_FULL_NAMES, defaultValue = "false")
	private boolean fullNames;

	/** Output format: "text" (default) or "json". */
	@Parameter(property = "testorder.show.format", defaultValue = "text")
	private String format;

	/** Glob filter to restrict output to matching test class names. */
	@Parameter(property = "testorder.show.filter")
	private String filter;

	/**
	 * Maximum number of rows to display per module in the class-order table.
	 * Defaults to 20 so large projects don't flood the terminal. Use -1 to show all
	 * tests.
	 */
	@Parameter(property = "testorder.show.limit", defaultValue = "20")
	private int displayLimit;

	/** Number of top-scored test classes to always include (-1 = all affected). */
	@Parameter(property = MavenPluginConfigKeys.SELECT_TOP_N, defaultValue = "-1")
	private int topN;

	/**
	 * Number of random fast tests to include for coverage diversity. Use 0 to
	 * disable random selection.
	 */
	@Parameter(property = MavenPluginConfigKeys.SELECT_RANDOM_M, defaultValue = "10")
	private int randomM;

	/** Random seed for reproducible selection (optional). */
	@Parameter(property = MavenPluginConfigKeys.SELECT_SEED)
	private Long seed;

	@Override
	protected void validateParameters() throws MojoExecutionException {
		ParameterValidator validator = new ParameterValidator(getLog());
		validator.validateChangeMode(changeMode);
		if (weightsFile != null && !weightsFile.isBlank()) {
			validator.validateFilePath(weightsFile, "weightsFile");
		}
		if (format != null && !format.equalsIgnoreCase("text") && !format.equalsIgnoreCase("json")) {
			throw new MojoExecutionException("[test-order] Invalid format '" + format + "'. Supported: text, json");
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

		try {
			me.bechberger.testorder.DependencyMap depMap = me.bechberger.testorder.DependencyMap.load(idxPath);
			if (depMap.testClasses().isEmpty()) {
				getLog().info("[test-order] No dependency index found." + "\nRun: mvn test -Dtestorder.mode=learn");
				return;
			}
		} catch (IOException e) {
			throw new MojoExecutionException(
					"Failed to read dependency index at " + idxPath + " (the file may be corrupted)."
							+ "\nRun: mvn test-order:clean" + "\nRun: mvn test -Dtestorder.mode=learn",
					e);
		}

		boolean effectiveExplain = explain
				|| "true".equalsIgnoreCase(project.getProperties().getProperty("testorder.debug"))
				|| "true".equalsIgnoreCase(System.getProperty("testorder.debug"))
				// backward compat: old property name testorder.showOrder.explain still
				// activates explain
				|| "true".equalsIgnoreCase(System.getProperty(MavenPluginConfigKeys.SHOW_ORDER_EXPLAIN))
				|| "true".equalsIgnoreCase(
						session.getUserProperties().getProperty(MavenPluginConfigKeys.SHOW_ORDER_EXPLAIN));

		// show-all: force all sections on (auto-detect for method/ml so we don't
		// emit "unavailable" noise when data genuinely doesn't exist)
		// BUG-172: honor Surefire <excludes> in the Selection Preview.
		java.util.List<String> excludePatterns = SurefireHelper.readExcludePatterns(project);
		ShowWorkflow.Options opts = new ShowWorkflow.Options(true, null, null, effectiveExplain, fullNames, format,
				filter, topN, randomM, seed, displayLimit, excludePatterns);

		Path mlHistoryDir = resolveMLHistoryDir();

		PluginContext pctx = buildPluginContextBuilder().topN(topN).randomM(randomM).seed(seed)
				.methodOrderingEnabled(true).build();

		try {
			ShowWorkflow.ShowResult result = ShowWorkflow.compute(pctx, opts, mlHistoryDir);

			if (result.healthReport() != null) {
				result = enrichWithMLPredictions(result, mlHistoryDir, pctx);
			}

			if (opts.isJson()) {
				System.out.println(ShowJsonFormatter.format(result, filter));
			} else {
				ShowWorkflow.printReport(System.out, result, opts, pctx);
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to compute show-all report", e);
		}
	}

	private ShowWorkflow.ShowResult enrichWithMLPredictions(ShowWorkflow.ShowResult result, Path mlHistoryDir,
			PluginContext pctx) {
		try {
			Path historyFile = mlHistoryDir.resolve("history.lz4");
			if (!Files.exists(historyFile))
				return result;
			Path idxPath = resolveIndexPath();
			if (!Files.exists(idxPath))
				return result;
			me.bechberger.testorder.DependencyMap depMap = result.analysis().depMap();
			Set<String> testClasses = result.analysis().allTests();
			if (testClasses.isEmpty())
				return result;

			Map<String, Double> predictions = me.bechberger.testorder.maven.ml.TestFailurePredictor.trainAndPredict(
					historyFile, depMap, result.analysis().changedClasses(), result.analysis().changedTests(),
					testClasses);

			if (predictions.isEmpty())
				return result;

			return new ShowWorkflow.ShowResult(result.classOrder(), result.methodOrder(), result.healthReport(),
					predictions, result.analysis());
		} catch (Exception e) {
			WarnOnce.warn(MavenPluginLog.wrap(getLog()), "ml-train-failure", "[test-order] ML predictions unavailable: "
					+ e.getMessage() + " — rerun with -Dtestorder.verbose=true for stack traces.");
			if (Boolean.getBoolean("testorder.verbose")) {
				e.printStackTrace();
			}
			return result;
		}
	}
}
