package me.bechberger.testorder.ml;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline statistical analyzer that produces a {@link TestHealthReport} from ML
 * history.
 * <p>
 * Uses lightweight time series analysis (EWMA, autocorrelation, trend slope,
 * volatility) to classify each test as HEALTHY, DEGRADING, FLAKY, or FAILING.
 * No external ML framework dependency — pure statistical methods appropriate
 * for sparse binary (pass/fail) data with irregular intervals.
 * <p>
 * Designed to run periodically (e.g., nightly CI job) rather than on every
 * build.
 */
public final class TestHealthAnalyzer {

	/** Minimum runs before a test is included in the report. */
	private static final int MIN_RUNS = 3;

	/** EWMA smoothing factor — higher values weight recent runs more heavily. */
	private static final double EWMA_ALPHA = 0.3;

	/** Sub-window size for trend slope and volatility computation. */
	private static final int SUB_WINDOW = 5;

	/** Maximum number of recent outcomes to track per test. */
	private static final int MAX_HISTORY = 100;

	// Classification thresholds
	private static final double FLAKY_VOLATILITY_THRESHOLD = 0.15;
	private static final double FLAKY_AUTOCORRELATION_THRESHOLD = 0.3;
	private static final double DEGRADING_TREND_THRESHOLD = 0.02;
	private static final double FAILING_RATE_THRESHOLD = 0.8;

	private TestHealthAnalyzer() {
	}

	/**
	 * Analyzes ML history and produces a test health report.
	 *
	 * @param history
	 *            ordered list of ML run records (oldest first)
	 * @return health report with per-test classifications
	 */
	public static TestHealthReport analyze(List<MLRunRecord> history) {
		if (history.isEmpty()) {
			return new TestHealthReport(Map.of(), System.currentTimeMillis(), 0);
		}

		// Build per-test outcome sequences
		Map<String, TestSequence> sequences = new HashMap<>();
		for (MLRunRecord run : history) {
			Set<String> runTests = new HashSet<>();
			for (MLTestOutcome outcome : run.outcomes()) {
				runTests.add(outcome.testClass());
				TestSequence seq = sequences.computeIfAbsent(outcome.testClass(), k -> new TestSequence());
				seq.addOutcome(outcome.failed());
			}
		}

		// Classify each test
		Map<String, TestHealthReport.TestHealth> tests = new HashMap<>();
		for (var entry : sequences.entrySet()) {
			String testClass = entry.getKey();
			TestSequence seq = entry.getValue();
			if (seq.totalRuns < MIN_RUNS) {
				continue;
			}

			double ewma = seq.computeEWMA();
			double trend = seq.computeTrendSlope();
			double autocorr = seq.computeAutocorrelation();
			double volatility = seq.computeVolatility();
			double flakiness = computeFlakinessScore(volatility, autocorr, ewma);
			TestHealthReport.HealthStatus status = classify(ewma, trend, volatility, autocorr);

			tests.put(testClass, new TestHealthReport.TestHealth(testClass, flakiness, trend, ewma, volatility,
					seq.totalRuns, seq.totalFailures, status));
		}

		return new TestHealthReport(tests, System.currentTimeMillis(), history.size());
	}

	private static double computeFlakinessScore(double volatility, double autocorrelation, double ewma) {
		// Flakiness is high when:
		// - Volatility is high (inconsistent results across windows)
		// - Autocorrelation is negative (alternating pass/fail)
		// - Failure rate is moderate (not consistently passing or failing)
		double volatilityComponent = Math.min(1.0, volatility / 0.5);
		double autocorrComponent = Math.max(0, -autocorrelation); // negative autocorr = flaky
		double midRangeComponent = 1.0 - Math.abs(2.0 * ewma - 1.0); // peaks at 50% failure rate
		return Math.min(1.0, 0.4 * volatilityComponent + 0.3 * autocorrComponent + 0.3 * midRangeComponent);
	}

	private static TestHealthReport.HealthStatus classify(double ewma, double trend, double volatility,
			double autocorrelation) {
		if (ewma >= FAILING_RATE_THRESHOLD) {
			return TestHealthReport.HealthStatus.FAILING;
		}
		if (volatility >= FLAKY_VOLATILITY_THRESHOLD || autocorrelation <= -FLAKY_AUTOCORRELATION_THRESHOLD) {
			return TestHealthReport.HealthStatus.FLAKY;
		}
		if (trend >= DEGRADING_TREND_THRESHOLD && ewma > 0.05) {
			return TestHealthReport.HealthStatus.DEGRADING;
		}
		return TestHealthReport.HealthStatus.HEALTHY;
	}

	/**
	 * Per-test outcome sequence with bounded history for statistical analysis.
	 */
	static final class TestSequence {
		final int[] outcomes = new int[MAX_HISTORY]; // ring buffer: 0=pass, 1=fail
		int count; // total outcomes added
		int totalRuns;
		int totalFailures;

		void addOutcome(boolean failed) {
			outcomes[count % MAX_HISTORY] = failed ? 1 : 0;
			count++;
			totalRuns++;
			if (failed) {
				totalFailures++;
			}
		}

		double computeEWMA() {
			int n = Math.min(count, MAX_HISTORY);
			int oldest = count - n;
			double ewma = 0.0;
			for (int i = 0; i < n; i++) {
				ewma = EWMA_ALPHA * outcomes[(oldest + i) % MAX_HISTORY] + (1 - EWMA_ALPHA) * ewma;
			}
			return ewma;
		}

		double computeTrendSlope() {
			int n = Math.min(count, MAX_HISTORY);
			if (n < SUB_WINDOW * 2) {
				return 0.0;
			}
			int numWindows = n / SUB_WINDOW;
			double[] rates = new double[numWindows];
			int oldest = count - n;
			for (int w = 0; w < numWindows; w++) {
				int fails = 0;
				for (int i = 0; i < SUB_WINDOW; i++) {
					fails += outcomes[(oldest + w * SUB_WINDOW + i) % MAX_HISTORY];
				}
				rates[w] = (double) fails / SUB_WINDOW;
			}
			double meanX = (numWindows - 1) / 2.0;
			double meanY = 0;
			for (double r : rates) {
				meanY += r;
			}
			meanY /= numWindows;
			double num = 0, den = 0;
			for (int i = 0; i < numWindows; i++) {
				num += (i - meanX) * (rates[i] - meanY);
				den += (i - meanX) * (i - meanX);
			}
			return den > 0 ? num / den : 0.0;
		}

		double computeAutocorrelation() {
			int n = Math.min(count, MAX_HISTORY);
			if (n < 3) {
				return 0.0;
			}
			int oldest = count - n;
			double mean = 0;
			for (int i = 0; i < n; i++) {
				mean += outcomes[(oldest + i) % MAX_HISTORY];
			}
			mean /= n;
			double num = 0, den = 0;
			for (int i = 0; i < n; i++) {
				double xi = outcomes[(oldest + i) % MAX_HISTORY] - mean;
				den += xi * xi;
				if (i < n - 1) {
					double xi1 = outcomes[(oldest + i + 1) % MAX_HISTORY] - mean;
					num += xi * xi1;
				}
			}
			return den > 0 ? num / den : 0.0;
		}

		double computeVolatility() {
			int n = Math.min(count, MAX_HISTORY);
			if (n < SUB_WINDOW * 2) {
				return 0.0;
			}
			int numWindows = n / SUB_WINDOW;
			int oldest = count - n;
			double meanRate = 0;
			double[] rates = new double[numWindows];
			for (int w = 0; w < numWindows; w++) {
				int fails = 0;
				for (int i = 0; i < SUB_WINDOW; i++) {
					fails += outcomes[(oldest + w * SUB_WINDOW + i) % MAX_HISTORY];
				}
				rates[w] = (double) fails / SUB_WINDOW;
				meanRate += rates[w];
			}
			meanRate /= numWindows;
			double variance = 0;
			for (double r : rates) {
				variance += (r - meanRate) * (r - meanRate);
			}
			return Math.sqrt(variance / numWindows);
		}
	}
}
