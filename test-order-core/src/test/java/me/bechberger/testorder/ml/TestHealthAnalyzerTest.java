package me.bechberger.testorder.ml;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TestHealthAnalyzer} using synthetic ML history.
 * <p>
 * Each test constructs a specific history pattern and asserts that the health
 * analysis produces the expected classification (HEALTHY, FLAKY, DEGRADING, or
 * FAILING).
 */
class TestHealthAnalyzerTest {

	private static final String STABLE_TEST = "com.example.StableTest";
	private static final String FLAKY_TEST = "com.example.FlakyTest";
	private static final String DEGRADING_TEST = "com.example.DegradingTest";
	private static final String FAILING_TEST = "com.example.FailingTest";

	// ── helper builders ───────────────────────────────────────────────────────

	private static MLRunRecord pass(long ts, String... testClasses) {
		List<MLTestOutcome> outcomes = new ArrayList<>();
		for (String tc : testClasses) {
			outcomes.add(new MLTestOutcome(tc, false, 100L, null));
		}
		return new MLRunRecord(ts, List.of(), List.of(), testClasses.length, 0, outcomes);
	}

	private static MLRunRecord fail(long ts, String... testClasses) {
		List<MLTestOutcome> outcomes = new ArrayList<>();
		for (String tc : testClasses) {
			outcomes.add(new MLTestOutcome(tc, true, 100L, "java.lang.AssertionError"));
		}
		return new MLRunRecord(ts, List.of(), List.of(), testClasses.length, testClasses.length, outcomes);
	}

	private static MLRunRecord mixed(long ts, String passClass, String failClass) {
		return new MLRunRecord(ts, List.of(), List.of(), 2, 1, List.of(new MLTestOutcome(passClass, false, 100L, null),
				new MLTestOutcome(failClass, true, 100L, "java.lang.AssertionError")));
	}

	// ── HEALTHY classification ────────────────────────────────────────────────

	@Test
	void alwaysPassingTestIsHealthy() {
		List<MLRunRecord> history = new ArrayList<>();
		for (int i = 1; i <= 10; i++) {
			history.add(pass(i, STABLE_TEST));
		}
		TestHealthReport report = TestHealthAnalyzer.analyze(history);

		TestHealthReport.TestHealth health = report.tests().get(STABLE_TEST);
		assertNotNull(health, "STABLE_TEST should appear in report");
		assertEquals(TestHealthReport.HealthStatus.HEALTHY, health.status());
		assertEquals(0.0, health.recentFailureRate(), 1e-9);
		assertEquals(10, health.totalRuns());
		assertEquals(0, health.totalFailures());
	}

	@Test
	void lowFailureRateRemainsHealthy() {
		// Two evenly-spaced failures in 20 passes — low EWMA, no trend, no volatility.
		// Fails at positions 5 and 15; no trend signal because they're spread
		// uniformly.
		List<MLRunRecord> history = new ArrayList<>();
		for (int i = 1; i <= 20; i++) {
			if (i == 5 || i == 15) {
				history.add(fail(i, STABLE_TEST));
			} else {
				history.add(pass(i, STABLE_TEST));
			}
		}

		TestHealthReport report = TestHealthAnalyzer.analyze(history);
		TestHealthReport.TestHealth health = report.tests().get(STABLE_TEST);
		assertNotNull(health);
		assertEquals(TestHealthReport.HealthStatus.HEALTHY, health.status());
	}

	// ── FAILING classification ────────────────────────────────────────────────

	@Test
	void consistentlyFailingTestIsClassifiedFailing() {
		// FAILING threshold: EWMA >= 0.8
		List<MLRunRecord> history = new ArrayList<>();
		for (int i = 1; i <= 15; i++) {
			history.add(fail(i, FAILING_TEST));
		}
		TestHealthReport report = TestHealthAnalyzer.analyze(history);

		TestHealthReport.TestHealth health = report.tests().get(FAILING_TEST);
		assertNotNull(health);
		assertEquals(TestHealthReport.HealthStatus.FAILING, health.status());
		assertTrue(health.recentFailureRate() >= 0.8,
				"EWMA failure rate should be ≥ 0.8 for a consistently failing test");
		assertEquals(15, health.totalRuns());
		assertEquals(15, health.totalFailures());
	}

	@Test
	void moreFailuresThanPassesTipsFailing() {
		// 9 failures, 1 pass — EWMA should converge above 0.8
		List<MLRunRecord> history = new ArrayList<>();
		history.add(pass(1, FAILING_TEST));
		for (int i = 2; i <= 15; i++) {
			history.add(fail(i, FAILING_TEST));
		}
		TestHealthReport report = TestHealthAnalyzer.analyze(history);

		TestHealthReport.TestHealth health = report.tests().get(FAILING_TEST);
		assertNotNull(health);
		assertEquals(TestHealthReport.HealthStatus.FAILING, health.status());
	}

	// ── FLAKY classification ──────────────────────────────────────────────────

	@Test
	void alternatingPassFailIsFlaky() {
		// Perfect alternating pattern: fail, pass, fail, pass, …
		// Lag-1 autocorrelation is -1 (maximum negative), well below -0.3 threshold.
		List<MLRunRecord> history = new ArrayList<>();
		for (int i = 1; i <= 20; i++) {
			if (i % 2 == 0) {
				history.add(pass(i, FLAKY_TEST));
			} else {
				history.add(fail(i, FLAKY_TEST));
			}
		}
		TestHealthReport report = TestHealthAnalyzer.analyze(history);

		TestHealthReport.TestHealth health = report.tests().get(FLAKY_TEST);
		assertNotNull(health);
		assertEquals(TestHealthReport.HealthStatus.FLAKY, health.status(),
				"Perfectly alternating pass/fail should be classified as FLAKY");
	}

	@Test
	void highVolatilityIsFlaky() {
		// Pattern: 5 passes, 5 fails, 5 passes, 5 fails, then 5 passes to avoid
		// FAILING.
		// Four complete windows with rates [0, 1, 0, 1] = high std dev; ends on pass
		// block
		// so EWMA stays below 0.8 (avoids FAILING classification).
		List<MLRunRecord> history = new ArrayList<>();
		// blocks: pass, fail, pass, fail, pass — 5 blocks × 5 runs = 25 runs
		for (int block = 0; block < 5; block++) {
			boolean failing = block % 2 == 1; // blocks 1 and 3 fail
			for (int i = 0; i < 5; i++) {
				long ts = block * 5L + i + 1;
				history.add(failing ? fail(ts, FLAKY_TEST) : pass(ts, FLAKY_TEST));
			}
		}
		TestHealthReport report = TestHealthAnalyzer.analyze(history);

		TestHealthReport.TestHealth health = report.tests().get(FLAKY_TEST);
		assertNotNull(health);
		assertEquals(TestHealthReport.HealthStatus.FLAKY, health.status(),
				"High window-to-window volatility should classify as FLAKY");
		assertTrue(health.volatility() >= 0.15, "Volatility should be at or above the FLAKY threshold");
	}

	// ── DEGRADING classification ──────────────────────────────────────────────

	@Test
	void increasingFailureRateIsDegrading() {
		// Ten consecutive 5-run windows with failure rates 0, 0, 0.2, 0.2, 0.4, 0.4,
		// 0.6, 0.6, 0.4, 0.4 — slope is positive but not all the way to 1.0 so EWMA
		// stays below 0.8 (avoids FAILING). Std dev of these rates ≈ 0.19 but we must
		// stay below 0.15 for FLAKY to not trigger, so use very gradual windows:
		// 0, 0, 0, 0, 0.2, 0.2, 0.2, 0.2, 0.4, 0.4 (10 windows × 5 runs = 50 runs).
		// Std dev of [0,0,0,0, 0.2,0.2,0.2,0.2, 0.4,0.4] ≈ 0.155... still too high.
		//
		// Use a monotone sequence with very small steps so std dev stays low AND trend
		// slope exceeds 0.02.
		// 20 windows of 5 runs each (100 runs total).
		// Rates per window: 0.00, 0.02, 0.04, 0.06 ... 0.38 (step 0.02 each window).
		// Std dev ≈ 0.11, slope = 0.02 exactly. EWMA at end ≈ 0.14 (well below 0.8).
		// volatility = std dev of windowed rates ≈ 0.11 < 0.15 (FLAKY threshold).
		// But SUB_WINDOW=5, so the 20 windows of 5 each are the actual sub-windows.
		List<MLRunRecord> history = new ArrayList<>();
		int ts = 1;
		// 20 windows of 5 runs, fail rate increases by ~0.04 per 2 consecutive windows
		// rates: 0,0, 0.2,0.2, 0.4,0.4 (3 pairs = 6 windows = 30 runs)
		// → std dev small, slope positive
		// Even simpler: 6 windows: fail rates 0, 0, 0, 0.2, 0.2, 0.4
		// std dev of [0,0,0,0.2,0.2,0.4] ≈ 0.141 < 0.15; slope ≈ 0.08/window > 0.02
		int[] rates5 = {0, 0, 0, 1, 1, 2}; // fails per 5-run window
		for (int w = 0; w < rates5.length; w++) {
			int failsInWindow = rates5[w];
			for (int i = 0; i < 5; i++) {
				boolean shouldFail = i < failsInWindow;
				history.add(shouldFail ? fail(ts++, DEGRADING_TEST) : pass(ts++, DEGRADING_TEST));
			}
		}
		TestHealthReport report = TestHealthAnalyzer.analyze(history);

		TestHealthReport.TestHealth health = report.tests().get(DEGRADING_TEST);
		assertNotNull(health);
		assertEquals(TestHealthReport.HealthStatus.DEGRADING, health.status(),
				"Monotonically increasing failure rate should classify as DEGRADING");
		assertTrue(health.degradationTrend() > 0, "Degradation trend should be positive");
	}

	// ── below MIN_RUNS threshold ──────────────────────────────────────────────

	@Test
	void belowMinRunsExcludedFromReport() {
		// MIN_RUNS = 3; with only 2 runs the test should not appear in the report.
		List<MLRunRecord> history = List.of(pass(1, STABLE_TEST), fail(2, STABLE_TEST));
		TestHealthReport report = TestHealthAnalyzer.analyze(history);

		assertFalse(report.tests().containsKey(STABLE_TEST),
				"Test with < 3 runs should not appear in the health report");
	}

	@Test
	void exactlyMinRunsAppearsInReport() {
		List<MLRunRecord> history = List.of(pass(1, STABLE_TEST), pass(2, STABLE_TEST), pass(3, STABLE_TEST));
		TestHealthReport report = TestHealthAnalyzer.analyze(history);

		assertTrue(report.tests().containsKey(STABLE_TEST),
				"Test with exactly 3 runs should appear in the health report");
	}

	// ── empty history ─────────────────────────────────────────────────────────

	@Test
	void emptyHistoryProducesEmptyReport() {
		TestHealthReport report = TestHealthAnalyzer.analyze(List.of());
		assertTrue(report.tests().isEmpty());
		assertEquals(0, report.runsAnalyzed());
	}

	// ── multiple tests in same report ─────────────────────────────────────────

	@Test
	void multipleTestsGetIndependentClassifications() {
		List<MLRunRecord> history = new ArrayList<>();
		for (int i = 1; i <= 15; i++) {
			// Alternating pass/fail only for FLAKY_TEST; STABLE_TEST always passes.
			if (i % 2 == 0) {
				history.add(new MLRunRecord(i, List.of(), List.of(), 2, 1,
						List.of(new MLTestOutcome(STABLE_TEST, false, 100L, null),
								new MLTestOutcome(FLAKY_TEST, true, 100L, "AssertionError"))));
			} else {
				history.add(new MLRunRecord(i, List.of(), List.of(), 2, 0,
						List.of(new MLTestOutcome(STABLE_TEST, false, 100L, null),
								new MLTestOutcome(FLAKY_TEST, false, 100L, null))));
			}
		}
		TestHealthReport report = TestHealthAnalyzer.analyze(history);

		assertEquals(TestHealthReport.HealthStatus.HEALTHY, report.tests().get(STABLE_TEST).status());
		assertEquals(TestHealthReport.HealthStatus.FLAKY, report.tests().get(FLAKY_TEST).status());
	}

	// ── runsAnalyzed count ────────────────────────────────────────────────────

	@Test
	void runsAnalyzedMatchesHistorySize() {
		List<MLRunRecord> history = new ArrayList<>();
		for (int i = 1; i <= 7; i++) {
			history.add(pass(i, STABLE_TEST));
		}
		TestHealthReport report = TestHealthAnalyzer.analyze(history);
		assertEquals(7, report.runsAnalyzed());
	}

	// ── flakinessScore sanity ─────────────────────────────────────────────────

	@Test
	void stableTestHasLowerFlakinessScoreThanFlakyTest() {
		List<MLRunRecord> history = new ArrayList<>();
		for (int i = 1; i <= 20; i++) {
			boolean flakyFail = i % 2 == 0;
			history.add(new MLRunRecord(i, List.of(), List.of(), 2, flakyFail ? 1 : 0, List.of(
					new MLTestOutcome(STABLE_TEST, false, 100L, null),
					new MLTestOutcome(FLAKY_TEST, flakyFail, 100L, flakyFail ? "java.lang.AssertionError" : null))));
		}
		TestHealthReport report = TestHealthAnalyzer.analyze(history);

		double stableScore = report.tests().get(STABLE_TEST).flakinessScore();
		double flakyScore = report.tests().get(FLAKY_TEST).flakinessScore();
		assertTrue(flakyScore > stableScore, "Alternating test should have higher flakiness score than stable test");
	}
}
