package me.bechberger.testorder.ml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A test health report produced by offline analysis of ML history. Identifies
 * flaky, degrading, and failing tests with per-test health metrics.
 *
 * @param tests
 *            per-test health data keyed by fully qualified class name
 * @param analyzedAt
 *            epoch millis when the analysis was performed
 * @param runsAnalyzed
 *            total number of historical runs that were analyzed
 */
public record TestHealthReport(Map<String, TestHealth> tests, long analyzedAt, int runsAnalyzed) {

	/**
	 * Health status classification for a single test class.
	 */
	public enum HealthStatus {
		/** Test passes consistently. */
		HEALTHY,
		/** Test failure rate is increasing over time. */
		DEGRADING,
		/**
		 * Test shows inconsistent pass/fail patterns (high autocorrelation or
		 * volatility).
		 */
		FLAKY,
		/** Test is currently failing consistently. */
		FAILING
	}

	/**
	 * Per-test health metrics computed from historical failure patterns.
	 *
	 * @param testClass
	 *            fully qualified test class name
	 * @param flakinessScore
	 *            0.0 = stable, 1.0 = very flaky (based on volatility +
	 *            autocorrelation)
	 * @param degradationTrend
	 *            positive = getting worse, negative = improving (failure rate
	 *            slope)
	 * @param recentFailureRate
	 *            EWMA failure rate (0.0–1.0)
	 * @param volatility
	 *            std dev of per-window failure rates
	 * @param totalRuns
	 *            total runs observed for this test
	 * @param totalFailures
	 *            total failures observed
	 * @param status
	 *            overall health classification
	 */
	public record TestHealth(String testClass, double flakinessScore, double degradationTrend, double recentFailureRate,
			double volatility, int totalRuns, int totalFailures, HealthStatus status) {
	}

	/**
	 * Saves the report to a human-readable text file.
	 */
	public void save(Path reportFile) throws IOException {
		Files.createDirectories(reportFile.getParent());
		var sb = new StringBuilder();
		sb.append("# Test Order Health Report\n");
		sb.append("# Analyzed: ").append(Instant.ofEpochMilli(analyzedAt)).append('\n');
		sb.append("# Runs analyzed: ").append(runsAnalyzed).append('\n');
		sb.append("# Format: class|status|flakiness|trend|failRate|volatility|runs|failures\n");
		sb.append("#\n");

		List<TestHealth> sorted = new ArrayList<>(tests.values());
		sorted.sort(Comparator.comparing((TestHealth h) -> h.status().ordinal()).reversed()
				.thenComparing(Comparator.comparingDouble(TestHealth::flakinessScore).reversed()));

		for (TestHealth th : sorted) {
			sb.append(th.testClass()).append('|').append(th.status()).append('|')
					.append(String.format(java.util.Locale.US, "%.3f", th.flakinessScore())).append('|')
					.append(String.format(java.util.Locale.US, "%+.4f", th.degradationTrend())).append('|')
					.append(String.format(java.util.Locale.US, "%.3f", th.recentFailureRate())).append('|')
					.append(String.format(java.util.Locale.US, "%.3f", th.volatility())).append('|')
					.append(th.totalRuns()).append('|').append(th.totalFailures()).append('\n');
		}
		Path temp = me.bechberger.testorder.PersistenceSupport.temporarySibling(reportFile);
		Files.writeString(temp, sb.toString());
		me.bechberger.testorder.PersistenceSupport.moveIntoPlace(temp, reportFile);
	}

	/**
	 * Loads a previously saved health report.
	 */
	public static TestHealthReport load(Path reportFile) throws IOException {
		if (!Files.exists(reportFile)) {
			return new TestHealthReport(Map.of(), 0, 0);
		}
		Map<String, TestHealth> tests = new HashMap<>();
		long analyzedAt = 0;
		int runsAnalyzed = 0;
		for (String line : Files.readAllLines(reportFile)) {
			if (line.startsWith("# Analyzed: ")) {
				try {
					analyzedAt = Instant.parse(line.substring("# Analyzed: ".length()).trim()).toEpochMilli();
				} catch (Exception ignored) {
				}
				continue;
			}
			if (line.startsWith("# Runs analyzed: ")) {
				try {
					runsAnalyzed = Integer.parseInt(line.substring("# Runs analyzed: ".length()).trim());
				} catch (NumberFormatException ignored) {
				}
				continue;
			}
			if (line.startsWith("#") || line.isBlank()) {
				continue;
			}
			String[] parts = line.split("\\|");
			if (parts.length >= 8) {
				try {
					String cls = parts[0];
					HealthStatus status = HealthStatus.valueOf(parts[1]);
					double flakiness = Double.parseDouble(parts[2]);
					double trend = Double.parseDouble(parts[3]);
					double failRate = Double.parseDouble(parts[4]);
					double volatility = Double.parseDouble(parts[5]);
					int runs = Integer.parseInt(parts[6]);
					int failures = Integer.parseInt(parts[7]);
					tests.put(cls, new TestHealth(cls, flakiness, trend, failRate, volatility, runs, failures, status));
				} catch (Exception ignored) {
				}
			}
		}
		return new TestHealthReport(tests, analyzedAt, runsAnalyzed);
	}

	/**
	 * Returns tests matching the given status, sorted by flakiness descending.
	 */
	public List<TestHealth> byStatus(HealthStatus status) {
		return tests.values().stream().filter(t -> t.status() == status)
				.sorted(Comparator.comparingDouble(TestHealth::flakinessScore).reversed()).toList();
	}

	/**
	 * Returns a formatted summary string suitable for console output.
	 */
	public String formatSummary() {
		var sb = new StringBuilder();
		sb.append(String.format("Test Health Report — %d runs analyzed%n", runsAnalyzed));
		sb.append(String.format("═══════════════════════════════════════════════════%n"));
		sb.append("  Legend: flakiness [0..1], trend (+ worsening / - improving), failRate [0..1]\n");
		sb.append("  Priority: FAILING first, then FLAKY, then DEGRADING.\n");

		appendCategory(sb, HealthStatus.FAILING, "FAILING");
		appendCategory(sb, HealthStatus.FLAKY, "FLAKY");
		appendCategory(sb, HealthStatus.DEGRADING, "DEGRADING");

		if (byStatus(HealthStatus.FAILING).isEmpty() && byStatus(HealthStatus.FLAKY).isEmpty()
				&& byStatus(HealthStatus.DEGRADING).isEmpty()) {
			sb.append("\n  No unhealthy tests detected in current history window.\n");
		}

		List<TestHealth> healthy = byStatus(HealthStatus.HEALTHY);
		sb.append(String.format("%n  HEALTHY: %d test(s)%n", healthy.size()));
		return sb.toString();
	}

	private void appendCategory(StringBuilder sb, HealthStatus status, String label) {
		List<TestHealth> items = byStatus(status);
		if (items.isEmpty()) {
			return;
		}
		sb.append(String.format("%n  %s (%d test(s)):%n", label, items.size()));
		for (TestHealth th : items) {
			String shortName = th.testClass();
			int dot = shortName.lastIndexOf('.');
			if (dot > 0) {
				shortName = shortName.substring(dot + 1);
			}
			sb.append(String.format("    %-40s flakiness=%.2f  trend=%+.3f  failRate=%.2f%n", shortName,
					th.flakinessScore(), th.degradationTrend(), th.recentFailureRate()));
		}
	}
}
