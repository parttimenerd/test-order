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
 * Unified inspection command that displays the computed test execution order.
 *
 * <p>
 * Shows only the class-level test order by default for quick, readable output.
 * Use {@code mvn test-order:show-all} to also see method order and ML health.
 *
 * <p>
 * Usage examples:
 *
 * <pre>
 * mvn test-order:show                               # class order only (default)
 * mvn test-order:show -Dtestorder.show.methods=true # also show method order
 * mvn test-order:show -Dtestorder.show.ml=true      # also show ML health
 * mvn test-order:show -Dtestorder.show.format=json  # machine-readable output
 * mvn test-order:show -Dtestorder.show.filter=*Service*  # filter by name
 * mvn test-order:show-all                           # all sections
 * </pre>
 */
@Mojo(name = "show", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES, aggregator = true)
public class ShowMojo extends AbstractTestOrderMojo {

	/** Include class-level test order section. */
	@Parameter(property = "testorder.show.classes", defaultValue = "true")
	private boolean classes;

	/**
	 * Include method-level order section. Default is false (disabled). Set to
	 * "true" to enable, or use {@code mvn test-order:show-all}.
	 */
	@Parameter(property = "testorder.show.methods", defaultValue = "false")
	private String methods;

	/**
	 * Include ML health analysis section. Default is false (disabled). Set to
	 * "true" to enable, or use {@code mvn test-order:show-all}.
	 */
	@Parameter(property = "testorder.show.ml", defaultValue = "false")
	private String ml;

	/** Show all sections (equivalent to classes+methods+ml all forced on). */
	@Parameter(property = "testorder.show.all", defaultValue = "false")
	private boolean all;

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
	 *
	 * <pre>
	 * mvn test-order:show                              # show top 20 (default)
	 * mvn test-order:show -Dtestorder.show.limit=50   # show top 50
	 * mvn test-order:show -Dtestorder.show.limit=-1   # show all
	 * </pre>
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
				getLog().info("[test-order] No dependency index found — run `mvn test` "
						+ "(auto-detects learn mode) or `mvn -Dtestorder.mode=learn test` first.");
				return;
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to read dependency index at " + idxPath, e);
		}

		// auto-enable explain when debug mode is active
		boolean effectiveExplain = explain
				|| "true".equalsIgnoreCase(project.getProperties().getProperty("testorder.debug"))
				|| "true".equalsIgnoreCase(System.getProperty("testorder.debug"))
				// backward compat: old property name testorder.showOrder.explain still
				// activates explain
				|| "true".equalsIgnoreCase(System.getProperty(MavenPluginConfigKeys.SHOW_ORDER_EXPLAIN))
				|| "true".equalsIgnoreCase(
						session.getUserProperties().getProperty(MavenPluginConfigKeys.SHOW_ORDER_EXPLAIN));

		// Resolve boolean/auto flags
		Boolean effectiveMethods = resolveAutoFlag(methods);
		Boolean effectiveMl = resolveAutoFlag(ml);
		if (all) {
			effectiveMethods = Boolean.TRUE;
			effectiveMl = Boolean.TRUE;
		}

		ShowWorkflow.Options opts = new ShowWorkflow.Options(classes, effectiveMethods, effectiveMl, effectiveExplain,
				fullNames, format, filter, topN, randomM, seed, displayLimit);

		// Resolve ML history dir (ML predictions generated in maven-plugin layer)
		Path mlHistoryDir = resolveMLHistoryDir();

		PluginContext pctx = buildPluginContextBuilder().topN(topN).randomM(randomM).seed(seed)
				.methodOrderingEnabled(effectiveMethods != null ? effectiveMethods : true).build();

		try {
			ShowWorkflow.ShowResult result = ShowWorkflow.compute(pctx, opts, mlHistoryDir);

			// Generate ML predictions if ML history available and we have predictions
			// support
			if (result.healthReport() != null) {
				result = enrichWithMLPredictions(result, mlHistoryDir, pctx);
			}

			if (opts.isJson()) {
				System.out.println(ShowJsonFormatter.format(result, filter));
			} else {
				ShowWorkflow.printReport(System.out, result, opts, pctx);
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to compute show report", e);
		}
	}

	/**
	 * Enriches the show result with ML failure predictions generated via Tribuo.
	 * Called only when ML history is available.
	 */
	private ShowWorkflow.ShowResult enrichWithMLPredictions(ShowWorkflow.ShowResult result, Path mlHistoryDir,
			PluginContext pctx) {
		try {
			Path historyFile = mlHistoryDir.resolve("history.lz4");
			if (!Files.exists(historyFile)) {
				return result;
			}
			Path idxPath = resolveIndexPath();
			if (!Files.exists(idxPath)) {
				return result;
			}
			me.bechberger.testorder.DependencyMap depMap = result.analysis().depMap();
			Set<String> testClasses = result.analysis().allTests();
			if (testClasses.isEmpty()) {
				return result;
			}

			Map<String, Double> predictions = me.bechberger.testorder.maven.ml.TestFailurePredictor.trainAndPredict(
					historyFile, depMap, result.analysis().changedClasses(), result.analysis().changedTests(),
					testClasses);

			if (predictions.isEmpty()) {
				return result;
			}

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

	/**
	 * Resolves "true"/"false"/null from string parameter (null = auto-detect).
	 */
	private static Boolean resolveAutoFlag(String value) {
		if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value)) {
			return null; // auto-detect
		}
		return Boolean.parseBoolean(value);
	}

}
