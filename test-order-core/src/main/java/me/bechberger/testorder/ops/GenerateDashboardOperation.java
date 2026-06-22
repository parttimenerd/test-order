package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.bechberger.testorder.DashboardGenerator;
import me.bechberger.testorder.DashboardGenerator.ScoredTest;
import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestScorer;
import me.bechberger.testorder.ml.CacheRuntimeReport;
import me.bechberger.testorder.ml.FlakyRuntimeReport;
import me.bechberger.testorder.ml.TestHealthReport;

/**
 * Generates a self-contained HTML dashboard. Framework-agnostic — used by both
 * the Maven {@code dashboard} mojo and the Gradle {@code testOrderDashboard}
 * task.
 *
 * <p>
 * Callers supply the template HTML (from {@code DashboardResources} in the
 * dashboard module) and the scoring inputs; this operation handles the scoring,
 * data-model building, template injection, and file writing.
 */
public final class GenerateDashboardOperation {

	private GenerateDashboardOperation() {
	}

	/**
	 * Generates the dashboard HTML and writes it to {@code outputPath}.
	 *
	 * @param allTests
	 *            full set of test FQCNs to display
	 * @param scorer
	 *            pre-built scorer (from {@link ShowOrderOperation#buildScorer})
	 * @param state
	 *            current state (run history, durations, etc.)
	 * @param weights
	 *            resolved scoring weights
	 * @param changed
	 *            changed source classes (may be empty)
	 * @param changedTests
	 *            changed test classes (may be empty)
	 * @param depMap
	 *            loaded dependency map
	 * @param projectName
	 *            project name for the dashboard header
	 * @param stateFileLabel
	 *            path label for state file (display only)
	 * @param indexFileLabel
	 *            path label for index file (display only)
	 * @param pluginVersion
	 *            plugin version string
	 * @param weightDefs
	 *            weight slider definitions (may be null → default defs)
	 * @param htmlTemplate
	 *            assembled HTML template string
	 * @param outputPath
	 *            where to write the final HTML
	 * @param log
	 *            logger
	 * @return path to the written HTML file
	 * @throws IOException
	 *             on I/O failure
	 */
	public static Path generate(Collection<String> allTests, TestScorer scorer, TestOrderState state,
			TestOrderState.ScoringWeights weights, Set<String> changed, Set<String> changedTests, DependencyMap depMap,
			String projectName, String stateFileLabel, String indexFileLabel, String pluginVersion,
			List<TestOrderState.WeightDef> weightDefs, String htmlTemplate, Path outputPath, PluginLog log)
			throws IOException {
		return generate(allTests, scorer, state, weights, changed, changedTests, depMap, projectName, stateFileLabel,
				indexFileLabel, pluginVersion, weightDefs, null, null, htmlTemplate, outputPath, log);
	}

	/**
	 * Generates the dashboard HTML with optional ML data and writes it to
	 * {@code outputPath}.
	 *
	 * @param mlPredictions
	 *            ML failure predictions per test class (null if ML not available)
	 * @param healthReport
	 *            ML health report (null if ML not available)
	 */
	public static Path generate(Collection<String> allTests, TestScorer scorer, TestOrderState state,
			TestOrderState.ScoringWeights weights, Set<String> changed, Set<String> changedTests, DependencyMap depMap,
			String projectName, String stateFileLabel, String indexFileLabel, String pluginVersion,
			List<TestOrderState.WeightDef> weightDefs, Map<String, Double> mlPredictions, TestHealthReport healthReport,
			String htmlTemplate, Path outputPath, PluginLog log) throws IOException {
		return generate(allTests, scorer, state, weights, changed, changedTests, depMap, projectName, stateFileLabel,
				indexFileLabel, pluginVersion, weightDefs, mlPredictions, healthReport, null, htmlTemplate, outputPath,
				log);
	}

	/**
	 * Generates the dashboard HTML with optional ML data and depsDir for static
	 * analysis visualization.
	 *
	 * @param depsDir
	 *            directory containing uncertain-classes*.txt files (null if not
	 *            available)
	 */
	public static Path generate(Collection<String> allTests, TestScorer scorer, TestOrderState state,
			TestOrderState.ScoringWeights weights, Set<String> changed, Set<String> changedTests, DependencyMap depMap,
			String projectName, String stateFileLabel, String indexFileLabel, String pluginVersion,
			List<TestOrderState.WeightDef> weightDefs, Map<String, Double> mlPredictions, TestHealthReport healthReport,
			Path depsDir, String htmlTemplate, Path outputPath, PluginLog log) throws IOException {
		return generate(allTests, scorer, state, weights, changed, changedTests, depMap, projectName, stateFileLabel,
				indexFileLabel, pluginVersion, weightDefs, mlPredictions, healthReport, depsDir,
				DashboardGenerator.RuntimeExtras.EMPTY, htmlTemplate, outputPath, log);
	}

	/**
	 * Generates the dashboard HTML with optional ML data, depsDir, and runtime
	 * extras (cache + flaky retry/quarantine outcomes).
	 */
	public static Path generate(Collection<String> allTests, TestScorer scorer, TestOrderState state,
			TestOrderState.ScoringWeights weights, Set<String> changed, Set<String> changedTests, DependencyMap depMap,
			String projectName, String stateFileLabel, String indexFileLabel, String pluginVersion,
			List<TestOrderState.WeightDef> weightDefs, Map<String, Double> mlPredictions, TestHealthReport healthReport,
			Path depsDir, DashboardGenerator.RuntimeExtras extras, String htmlTemplate, Path outputPath, PluginLog log)
			throws IOException {

		List<ScoredTest> scored = DashboardOperation.scoreAndSort(allTests, scorer, state);
		long medianDuration = DashboardOperation.computeMedianDuration(scored);

		DashboardGenerator gen = new DashboardGenerator(projectName, stateFileLabel, indexFileLabel, pluginVersion,
				depsDir);
		DashboardGenerator.RuntimeExtras effectiveExtras = extras != null
				? extras
				: DashboardGenerator.RuntimeExtras.EMPTY;
		List<TestOrderState.WeightDef> defs = weightDefs != null ? weightDefs : TestOrderState.WEIGHT_DEFS;
		Map<String, Object> data = gen.buildData(scored, changed, changedTests, state, weights, depMap, medianDuration,
				defs, mlPredictions, healthReport, effectiveExtras);

		String html = gen.injectIntoTemplate(htmlTemplate, data);

		Path outputParent = outputPath.toAbsolutePath().getParent();
		if (outputParent != null) {
			Files.createDirectories(outputParent);
		}
		Path temp = me.bechberger.testorder.PersistenceSupport.temporarySibling(outputPath);
		Files.writeString(temp, html, StandardCharsets.UTF_8);
		me.bechberger.testorder.PersistenceSupport.moveIntoPlace(temp, outputPath);

		log.info("[test-order] Dashboard written to: " + outputPath);
		log.info("[test-order] To open automatically: add -Dtestorder.dashboard.open=true");

		return outputPath;
	}

	/**
	 * Loads {@code flaky-runtime.txt} and {@code cache-runtime.txt} from
	 * {@code stateDir} (if present) and wraps them in a
	 * {@link DashboardGenerator.RuntimeExtras}. Returns
	 * {@link DashboardGenerator.RuntimeExtras#EMPTY} when {@code stateDir} is null
	 * or both files are missing/empty.
	 */
	public static DashboardGenerator.RuntimeExtras autoLoadExtras(Path stateDir) {
		if (stateDir == null) {
			return DashboardGenerator.RuntimeExtras.EMPTY;
		}
		FlakyRuntimeReport flaky = FlakyRuntimeReport.load(stateDir.resolve(FlakyRuntimeReport.DEFAULT_FILENAME));
		CacheRuntimeReport cache = CacheRuntimeReport.load(stateDir.resolve(CacheRuntimeReport.DEFAULT_FILENAME));
		if (flaky.isEmpty() && cache.isEmpty()) {
			return DashboardGenerator.RuntimeExtras.EMPTY;
		}
		return new DashboardGenerator.RuntimeExtras(cache.classes(), cache.totalDurationMs(), flaky);
	}
}
