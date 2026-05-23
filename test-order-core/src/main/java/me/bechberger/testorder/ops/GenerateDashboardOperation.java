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

		List<ScoredTest> scored = DashboardOperation.scoreAndSort(allTests, scorer, state);
		long medianDuration = DashboardOperation.computeMedianDuration(scored);

		DashboardGenerator gen = new DashboardGenerator(projectName, stateFileLabel, indexFileLabel, pluginVersion);
		Map<String, Object> data;
		if (weightDefs != null) {
			data = gen.buildData(scored, changed, changedTests, state, weights, depMap, medianDuration, weightDefs,
					mlPredictions, healthReport);
		} else {
			data = gen.buildData(scored, changed, changedTests, state, weights, depMap, medianDuration,
					TestOrderState.WEIGHT_DEFS, mlPredictions, healthReport);
		}

		String html = gen.injectIntoTemplate(htmlTemplate, data);

		Path outputParent = outputPath.toAbsolutePath().getParent();
		if (outputParent != null) {
			Files.createDirectories(outputParent);
		}
		Files.writeString(outputPath, html, StandardCharsets.UTF_8);

		log.info("[test-order] Dashboard written to: " + outputPath);
		log.info("[test-order] To open automatically: mvn test-order:dashboard -Dtestorder.dashboard.open=true");

		return outputPath;
	}
}
