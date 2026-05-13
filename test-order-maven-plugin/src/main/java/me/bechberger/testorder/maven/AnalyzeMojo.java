package me.bechberger.testorder.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import me.bechberger.testorder.ml.MLHistoryPersistence;
import me.bechberger.testorder.ml.MLRunRecord;
import me.bechberger.testorder.ml.TestHealthAnalyzer;
import me.bechberger.testorder.ml.TestHealthReport;

/**
 * Analyzes ML test history and produces a test health report identifying flaky,
 * degrading, and failing tests.
 * <p>
 * This goal is designed for periodic execution (e.g., nightly CI jobs) rather
 * than on every build. It uses lightweight statistical time series analysis
 * (EWMA, autocorrelation, trend slope, volatility) to classify each test's
 * health status.
 * <p>
 * Usage: {@code mvn test-order:analyze}
 * <p>
 * Output: {@code .test-order/test-health-report.txt}
 *
 * @deprecated Use {@code mvn test-order:show -Dtestorder.show.ml=true} instead.
 *             This goal will be removed in a future release.
 */
@Deprecated
@Mojo(name = "analyze", requiresProject = true, threadSafe = true)
public class AnalyzeMojo extends AbstractTestOrderMojo {

	@Override
	public void execute() throws MojoExecutionException {
		initContext();

		if (skipIfNotExplicitlySelectedReactorProject("analyze")) {
			return;
		}

		Path stateDir = ctx.resolveStateFile(stateFile).getParent();
		Path historyFile = stateDir.resolve("ml").resolve("history.lz4");

		if (!Files.exists(historyFile)) {
			getLog().warn("[test-order] No ML history found at " + historyFile);
			getLog().warn("[test-order] Run tests with -Dtestorder.ml.enabled=true to collect ML history first.");
			return;
		}

		try {
			List<MLRunRecord> history = MLHistoryPersistence.load(historyFile);
			if (history.isEmpty()) {
				getLog().info("[test-order] ML history is empty — nothing to analyze.");
				return;
			}

			getLog().info("[test-order] Analyzing " + history.size() + " historical test runs...");

			TestHealthReport report = TestHealthAnalyzer.analyze(history);

			// Save report
			Path reportFile = stateDir.resolve("test-health-report.txt");
			report.save(reportFile);

			// Print summary
			getLog().info("");
			for (String line : report.formatSummary().split("\n")) {
				getLog().info("[test-order] " + line);
			}
			getLog().info("");

			// Print actionable details for non-healthy tests
			printDetails(report, TestHealthReport.HealthStatus.FAILING);
			printDetails(report, TestHealthReport.HealthStatus.FLAKY);
			printDetails(report, TestHealthReport.HealthStatus.DEGRADING);

			getLog().info("[test-order] Full report saved to: " + reportFile);
		} catch (Exception e) {
			throw new MojoExecutionException("Failed to analyze test health: " + e.getMessage(), e);
		}
	}

	private void printDetails(TestHealthReport report, TestHealthReport.HealthStatus status) {
		List<TestHealthReport.TestHealth> tests = report.byStatus(status);
		if (tests.isEmpty()) {
			return;
		}
		for (TestHealthReport.TestHealth th : tests) {
			getLog().info(String.format(
					"[test-order]   %s: flakiness=%.2f  trend=%+.3f  "
							+ "failRate=%.2f  volatility=%.3f  (%d/%d failures)",
					th.testClass(), th.flakinessScore(), th.degradationTrend(), th.recentFailureRate(), th.volatility(),
					th.totalFailures(), th.totalRuns()));
		}
	}
}
