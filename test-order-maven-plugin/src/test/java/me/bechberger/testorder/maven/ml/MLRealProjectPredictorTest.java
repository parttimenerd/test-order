package me.bechberger.testorder.maven.ml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.ml.MLHistoryPersistence;
import me.bechberger.testorder.ml.MLRunRecord;
import me.bechberger.testorder.ml.MLTestOutcome;
import me.bechberger.testorder.ml.TestHealthAnalyzer;
import me.bechberger.testorder.ml.TestHealthReport;

/**
 * Integration-style tests for ML failure prediction using realistic test class
 * names from the test-order-core module and synthetic history that simulates
 * plausible failure patterns.
 * <p>
 * Unlike the synthetic unit tests in {@link TestFailurePredictorTest}, these
 * tests use a richer test suite (16+ classes), co-failure relationships, and
 * mixed patterns to exercise the full feature extraction pipeline.
 */
class MLRealProjectPredictorTest {

	@TempDir
	Path tempDir;

	// ── realistic test class names from test-order-core ───────────────────────

	private static final String APFD = "me.bechberger.testorder.APFDCalculatorTest";
	private static final String DEPS_SCORING = "me.bechberger.testorder.DepsAndScoringTest";
	private static final String DURATION = "me.bechberger.testorder.DurationTrackerTest";
	private static final String INDEX_SERVER = "me.bechberger.testorder.IndexCollectorServerTest";
	private static final String PARTIAL_RUN = "me.bechberger.testorder.PartialRunAggregatorTest";
	private static final String PERSISTENCE = "me.bechberger.testorder.PersistenceSupportTest";
	private static final String RUN_HISTORY = "me.bechberger.testorder.RunHistoryManagerTest";
	private static final String SCORING_OPT = "me.bechberger.testorder.ScoringOptimizerRealisticTest";
	private static final String SET_COVER = "me.bechberger.testorder.SetCoverComputerTest";
	private static final String STATE = "me.bechberger.testorder.TestOrderStateTest";
	private static final String SELECTOR = "me.bechberger.testorder.TestSelectorTest";
	private static final String SPLIT_ADVISOR = "me.bechberger.testorder.TestSplitAdvisorTest";
	private static final String TIERED = "me.bechberger.testorder.TieredTestSelectorTest";
	private static final String USABILITY = "me.bechberger.testorder.UsabilityIssuesTest";

	private static final List<String> ALL_TESTS = List.of(APFD, DEPS_SCORING, DURATION, INDEX_SERVER, PARTIAL_RUN,
			PERSISTENCE, RUN_HISTORY, SCORING_OPT, SET_COVER, STATE, SELECTOR, SPLIT_ADVISOR, TIERED, USABILITY);

	// ── production classes for a minimal dep map ─────────────────────────────

	private static final String APFD_CLASS = "me.bechberger.testorder.APFDCalculator";
	private static final String SCORING_CLASS = "me.bechberger.testorder.TestScorer";
	private static final String STATE_CLASS = "me.bechberger.testorder.TestOrderState";
	private static final String SELECTOR_CLASS = "me.bechberger.testorder.TestSelector";
	private static final String INDEX_CLASS = "me.bechberger.testorder.IndexCollectorServer";

	// ── helper builders ───────────────────────────────────────────────────────

	private static MLTestOutcome pass(String tc) {
		return new MLTestOutcome(tc, false, 200L, null);
	}

	private static MLTestOutcome fail(String tc, String ex) {
		return new MLTestOutcome(tc, true, 200L, ex);
	}

	/** All-pass run */
	private static MLRunRecord allPass(long ts, List<String> changedClasses) {
		List<MLTestOutcome> outcomes = ALL_TESTS.stream().map(MLRealProjectPredictorTest::pass).toList();
		return new MLRunRecord(ts, changedClasses, List.of(), ALL_TESTS.size(), 0, outcomes);
	}

	/** Run where specific tests fail */
	private static MLRunRecord runWith(long ts, List<String> changedClasses, Set<String> failingTests) {
		List<MLTestOutcome> outcomes = ALL_TESTS.stream()
				.map(tc -> failingTests.contains(tc) ? fail(tc, "java.lang.AssertionError") : pass(tc)).toList();
		int totalFails = (int) failingTests.stream().filter(ALL_TESTS::contains).count();
		return new MLRunRecord(ts, changedClasses, List.of(), ALL_TESTS.size(), totalFails, outcomes);
	}

	// ── test 1: risky test ranks higher than stable ───────────────────────────

	@Test
	void riskyTestRanksHigherThanStableTest() throws IOException {
		DependencyMap depMap = new DependencyMap();
		depMap.put(APFD, Set.of(APFD_CLASS, SCORING_CLASS));
		depMap.put(DEPS_SCORING, Set.of(SCORING_CLASS, STATE_CLASS));
		depMap.put(DURATION, Set.of(SCORING_CLASS));
		depMap.put(INDEX_SERVER, Set.of(INDEX_CLASS));
		depMap.put(PARTIAL_RUN, Set.of(STATE_CLASS, SELECTOR_CLASS));
		for (String t : List.of(PERSISTENCE, RUN_HISTORY, SCORING_OPT, SET_COVER, STATE, SELECTOR, SPLIT_ADVISOR,
				TIERED, USABILITY)) {
			depMap.put(t, Set.of(STATE_CLASS));
		}

		// APFD and DEPS_SCORING fail frequently when APFD_CLASS or SCORING_CLASS
		// changes.
		// All other tests pass consistently.
		List<MLRunRecord> history = new ArrayList<>();
		history.add(allPass(1, List.of(APFD_CLASS)));
		history.add(runWith(2, List.of(APFD_CLASS), Set.of(APFD, DEPS_SCORING)));
		history.add(runWith(3, List.of(APFD_CLASS), Set.of(APFD)));
		history.add(allPass(4, List.of(STATE_CLASS)));
		history.add(runWith(5, List.of(APFD_CLASS), Set.of(APFD, DEPS_SCORING)));
		history.add(runWith(6, List.of(SCORING_CLASS), Set.of(DEPS_SCORING)));
		history.add(allPass(7, List.of(STATE_CLASS)));
		history.add(runWith(8, List.of(APFD_CLASS, SCORING_CLASS), Set.of(APFD, DEPS_SCORING)));

		Path histFile = tempDir.resolve("history.lz4");
		MLHistoryPersistence.save(histFile, history, 0);

		Set<String> changedClasses = Set.of(APFD_CLASS, SCORING_CLASS);
		Map<String, Double> predictions = TestFailurePredictor.trainAndPredict(histFile, depMap, changedClasses,
				Set.of(), depMap.testClasses());

		assertFalse(predictions.isEmpty(), "Should produce predictions with 8 runs");
		assertTrue(predictions.containsKey(APFD), "APFD test must be in predictions");
		assertTrue(predictions.containsKey(TIERED), "TIERED test must be in predictions");

		double riskyScore = predictions.get(APFD);
		double stableScore = predictions.get(TIERED);
		assertTrue(riskyScore > stableScore,
				"Frequently-failing APFD test (P=" + riskyScore + ") should rank above rarely-failing TIERED test (P="
						+ stableScore + ") when its changed classes are in the change set");
	}

	// ── test 2: co-failing tests elevate each other ───────────────────────────

	@Test
	void coFailingTestsGetElevatedPredictions() throws IOException {
		DependencyMap depMap = new DependencyMap();
		// INDEX_SERVER and PARTIAL_RUN co-fail whenever INDEX_CLASS changes
		depMap.put(INDEX_SERVER, Set.of(INDEX_CLASS, STATE_CLASS));
		depMap.put(PARTIAL_RUN, Set.of(INDEX_CLASS, SELECTOR_CLASS));
		for (String t : List.of(APFD, DEPS_SCORING, DURATION, PERSISTENCE, RUN_HISTORY, SCORING_OPT, SET_COVER, STATE,
				SELECTOR, SPLIT_ADVISOR, TIERED, USABILITY)) {
			depMap.put(t, Set.of(STATE_CLASS));
		}

		// INDEX_SERVER and PARTIAL_RUN always fail together
		List<MLRunRecord> history = new ArrayList<>();
		history.add(allPass(1, List.of(STATE_CLASS)));
		history.add(runWith(2, List.of(INDEX_CLASS), Set.of(INDEX_SERVER, PARTIAL_RUN)));
		history.add(allPass(3, List.of(STATE_CLASS)));
		history.add(runWith(4, List.of(INDEX_CLASS), Set.of(INDEX_SERVER, PARTIAL_RUN)));
		history.add(allPass(5, List.of(STATE_CLASS)));
		history.add(runWith(6, List.of(INDEX_CLASS), Set.of(INDEX_SERVER, PARTIAL_RUN)));
		history.add(allPass(7, List.of(SELECTOR_CLASS)));
		history.add(runWith(8, List.of(INDEX_CLASS), Set.of(INDEX_SERVER, PARTIAL_RUN)));

		Path histFile = tempDir.resolve("cofail-history.lz4");
		MLHistoryPersistence.save(histFile, history, 0);

		Map<String, Double> predictions = TestFailurePredictor.trainAndPredict(histFile, depMap, Set.of(INDEX_CLASS),
				Set.of(), depMap.testClasses());

		assertFalse(predictions.isEmpty());
		assertTrue(predictions.containsKey(INDEX_SERVER));
		assertTrue(predictions.containsKey(PARTIAL_RUN));

		// Both co-failing tests should rank higher than the control group
		double stableScore = predictions.get(APFD);
		assertTrue(predictions.get(INDEX_SERVER) > stableScore,
				"Co-failing INDEX_SERVER should rank above stable APFD test");
		assertTrue(predictions.get(PARTIAL_RUN) > stableScore,
				"Co-failing PARTIAL_RUN should rank above stable APFD test");
	}

	// ── test 3: health analysis on realistic history ───────────────────────────

	@Test
	void healthAnalysisClassifiesRealisticPatterns() throws IOException {
		// Build history: APFD alternates (flaky), INDEX_SERVER always fails (failing),
		// DURATION passes consistently (healthy), USABILITY degrading slowly.
		List<MLRunRecord> history = new ArrayList<>();
		int ts = 1;

		// 20 runs: APFD alternates, INDEX_SERVER fails 90%, DURATION passes, USABILITY
		// degrades (fails every other run in last 10: pos 10,12,14,16,18 → 5/20 = 25%)
		for (int i = 0; i < 20; i++) {
			boolean apfdFails = i % 2 == 0; // alternating
			boolean indexFails = i >= 2; // fails from run 3 onwards → ~90% failure rate
			// Usability fails at even indices in [10,20): runs 10,12,14,16,18 — spaced so
			// EWMA stays below 0.8 while overall trend remains non-HEALTHY
			boolean usabilityFails = i >= 10 && i % 2 == 0;

			List<MLTestOutcome> outcomes = new ArrayList<>();
			outcomes.add(apfdFails ? fail(APFD, "AssertionError") : pass(APFD));
			outcomes.add(indexFails ? fail(INDEX_SERVER, "RuntimeException") : pass(INDEX_SERVER));
			outcomes.add(pass(DURATION));
			outcomes.add(usabilityFails ? fail(USABILITY, "AssertionError") : pass(USABILITY));

			int fails = (apfdFails ? 1 : 0) + (indexFails ? 1 : 0) + (usabilityFails ? 1 : 0);
			history.add(new MLRunRecord(ts++, List.of(), List.of(), 4, fails, outcomes));
		}

		TestHealthReport report = TestHealthAnalyzer.analyze(history);

		assertEquals(4, report.tests().size(), "All 4 tests should appear in the report");
		assertEquals(20, report.runsAnalyzed());

		// DURATION: always passes → HEALTHY
		assertEquals(TestHealthReport.HealthStatus.HEALTHY, report.tests().get(DURATION).status(),
				"Always-passing DURATION should be HEALTHY");

		// INDEX_SERVER: fails ~90% → FAILING (EWMA ≥ 0.8)
		assertEquals(TestHealthReport.HealthStatus.FAILING, report.tests().get(INDEX_SERVER).status(),
				"~90%-failing INDEX_SERVER should be FAILING");

		// APFD: perfectly alternating → FLAKY
		assertEquals(TestHealthReport.HealthStatus.FLAKY, report.tests().get(APFD).status(),
				"Alternating APFD should be FLAKY");

		// USABILITY: interleaved failures in second half → non-HEALTHY but rate << 0.8
		TestHealthReport.HealthStatus usabilityStatus = report.tests().get(USABILITY).status();
		assertNotEquals(TestHealthReport.HealthStatus.HEALTHY, usabilityStatus,
				"USABILITY with failures in second half should not be HEALTHY");
	}

	// ── test 4: too-short history returns empty ────────────────────────────────

	@Test
	void tooShortHistoryReturnsEmptyPredictions() throws IOException {
		DependencyMap depMap = new DependencyMap();
		depMap.put(APFD, Set.of(APFD_CLASS));

		List<MLRunRecord> history = List.of(allPass(1, List.of(APFD_CLASS)), allPass(2, List.of(APFD_CLASS)),
				allPass(3, List.of(APFD_CLASS)));

		Path histFile = tempDir.resolve("short.lz4");
		MLHistoryPersistence.save(histFile, history, 0);

		Map<String, Double> predictions = TestFailurePredictor.trainAndPredict(histFile, depMap, Set.of(APFD_CLASS),
				Set.of(), depMap.testClasses());

		assertTrue(predictions.isEmpty(), "With only 3 runs (< MIN_HISTORY_RUNS=5) predictions should be empty");
	}
}
