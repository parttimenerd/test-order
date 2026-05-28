package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stress-tests scoring and selection with injected synthetic failure history.
 * Verifies the full save/load/score cycle under conditions similar to what the
 * auto-mode pipeline sees after several real test runs.
 */
class SyntheticHistoryStressTest {

	@TempDir
	Path dir;

	@AfterEach
	void cleanup() {
		TestOrderState.resetPending();
	}

	// Build a dep map with N tests, each depending on a unique class
	private DependencyMap buildLinearDepMap(int n) {
		DependencyMap map = new DependencyMap();
		for (int i = 0; i < n; i++) {
			map.put("com.test.T" + i, Set.of("app.Class" + i));
		}
		return map;
	}

	@Test
	void stateWithManyFailuresSurvivedMultipleSaveCycles() throws IOException {
		// Inject failures for 10 tests, save/load 5 times, assert non-zero scores
		Path stateFile = dir.resolve("state.lz4");
		TestOrderState state = new TestOrderState();

		// Inject 1-10 failures for tests T0..T9
		for (int i = 0; i < 10; i++) {
			for (int f = 0; f <= i; f++)
				state.recordFailure("com.test.T" + i);
		}
		state.save(stateFile);

		// 5 more save cycles, injecting a dummy failure each time to trigger decay
		for (int cycle = 0; cycle < 5; cycle++) {
			state.recordFailure("com.dummy.Dummy" + cycle);
			state.save(stateFile);
		}

		TestOrderState loaded = TestOrderState.load(stateFile);

		// T9 had 10 failures — should still have the highest score after decay
		double maxScore = -1;
		String maxCls = null;
		for (int i = 0; i < 10; i++) {
			double s = loaded.failureScore("com.test.T" + i);
			if (s > maxScore) {
				maxScore = s;
				maxCls = "com.test.T" + i;
			}
		}
		assertEquals("com.test.T9", maxCls, "T9 (10 injected failures) should have highest failure score after decay");
		assertTrue(maxScore > 0.0, "T9 should still have a positive score after 5 decay cycles");
	}

	@Test
	void selectionOrderMatchesFailureSeverity() throws IOException {
		// 5 tests with different failure counts; verify selection ordering
		Path stateFile = dir.resolve("state.lz4");
		DependencyMap depMap = buildLinearDepMap(5);

		TestOrderState state = new TestOrderState();
		for (int i = 0; i < 5; i++) {
			state.recordDuration("com.test.T" + i, 100L);
			for (int f = 0; f < i + 1; f++)
				state.recordFailure("com.test.T" + i);
		}
		state.save(stateFile);
		TestOrderState loaded = TestOrderState.load(stateFile);

		var weights = new TestOrderState.ScoringWeights(0, 0, 5, 0, 0, 0, 0);
		TestScorer scorer = new TestScorer(weights, depMap, loaded, Set.of(), Set.of(), depMap.testClasses());

		// Score all 5 and verify ordering: T4 (5 failures) > T3 (4) > ... > T0 (1)
		List<String> byScore = depMap.testClasses().stream()
				.sorted(Comparator.comparingInt(t -> -scorer.score(t).score())).toList();

		assertEquals("com.test.T4", byScore.get(0), "T4 (5 failures) should score first: " + byScore);
		assertEquals("com.test.T3", byScore.get(1));
		assertEquals("com.test.T0", byScore.get(4), "T0 (1 failure) should score last: " + byScore);
	}

	@Test
	void selectorWithInjectedHistoryPicksHighestFailureFirst() throws IOException {
		Path stateFile = dir.resolve("state.lz4");
		DependencyMap depMap = buildLinearDepMap(10);

		TestOrderState state = new TestOrderState();
		for (int i = 0; i < 10; i++) {
			state.recordDuration("com.test.T" + i, 100L);
		}
		// Inject 7 failures for T5 — it should be selected first
		for (int f = 0; f < 7; f++)
			state.recordFailure("com.test.T5");
		state.save(stateFile);
		TestOrderState loaded = TestOrderState.load(stateFile);

		TestSelector.Selection sel = new TestSelector(depMap, loaded, Set.of(), Set.of(), loaded.weights(),
				new TestSelector.Config(1, 0, 42L)).select();

		assertEquals(1, sel.selected().size());
		assertEquals("com.test.T5", sel.selected().get(0), "T5 with 7 injected failures should be selected first");
	}

	@Test
	void failureScoreDecaysTowardsZeroWithoutNewFailures() throws IOException {
		// Inject 1 failure, then keep saving with other failures to force decay
		// Verify that the score monotonically decreases
		Path stateFile = dir.resolve("state.lz4");
		TestOrderState state = new TestOrderState();
		state.recordFailure("com.test.Victim");
		state.save(stateFile);

		double prev = TestOrderState.load(stateFile).failureScore("com.test.Victim");
		assertTrue(prev > 0);

		for (int cycle = 0; cycle < 20; cycle++) {
			// Trigger decay by saving with a fresh pending failure for another class
			state.recordFailure("com.dummy.Other" + cycle);
			state.save(stateFile);
			double curr = TestOrderState.load(stateFile).failureScore("com.test.Victim");
			assertTrue(curr <= prev, "Score should not increase at cycle " + cycle + ": was " + prev + ", now " + curr);
			prev = curr;
		}
		// After 20 cycles, score should be much lower than initial
		assertTrue(prev < 0.5, "After 20 decay cycles, score should be < 0.5 (was 1.0 initially), got " + prev);
	}

	@Test
	void newTestInDepMapAlwaysSelectedEvenWithHighFailureHistory() throws IOException {
		// If a completely new test appears (not in depMap), it gets the new-test bonus
		// regardless of how many other tests have failures
		Path stateFile = dir.resolve("state.lz4");
		DependencyMap depMap = buildLinearDepMap(5);

		TestOrderState state = new TestOrderState();
		for (int i = 0; i < 5; i++)
			state.recordDuration("com.test.T" + i, 100L);
		// Inject massive failures for existing tests
		for (int i = 0; i < 5; i++) {
			for (int f = 0; f < 20; f++)
				state.recordFailure("com.test.T" + i);
		}
		state.save(stateFile);

		// com.test.NewTest is not in depMap → it is a new test
		TestSelector.Selection sel = new TestSelector(depMap, TestOrderState.load(stateFile), Set.of(),
				Set.of("com.test.NewTest"), // NewTest is a changed test class
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(0, 0, 42L)).select();

		assertTrue(sel.selected().contains("com.test.NewTest"),
				"New test not in depMap must always be selected (newTest bonus)");
	}

	@Test
	void runHistoryAccumulatesCorrectlyAcrossManySaves() throws IOException {
		Path stateFile = dir.resolve("state.lz4");
		TestOrderState state = new TestOrderState();

		// Simulate 30 test runs
		for (int run = 0; run < 30; run++) {
			List<TestOrderState.TestOutcome> outcomes = new ArrayList<>();
			for (int t = 0; t < 5; t++) {
				boolean failed = (run % 5 == 0 && t == 0); // periodic failure
				outcomes.add(new TestOrderState.TestOutcome("com.test.T" + t, 100, false, false, 0, 0, 0.0, false,
						false, failed, 0.0));
			}
			state.addRunRecord(new TestOrderState.RunRecord(run * 1000L, 5, failed(outcomes), -1, 0.8, outcomes));
			state.save(stateFile);
			state = TestOrderState.load(stateFile);
		}

		// Should have at most MAX_HISTORY_RUNS records
		assertTrue(state.runs().size() <= 50, "Run history must be capped at 50, got " + state.runs().size());
		assertTrue(state.runs().size() > 0, "Should have at least some run history");
	}

	private static int failed(List<TestOrderState.TestOutcome> outcomes) {
		return (int) outcomes.stream().filter(TestOrderState.TestOutcome::failed).count();
	}

	@Test
	void scorerHandlesMaxFailureCap() throws IOException {
		// Inject 100 failures — score contribution should be capped at maxFailure
		// weight
		Path stateFile = dir.resolve("state.lz4");
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.test.Flaky", 100L);
		for (int i = 0; i < 100; i++)
			state.recordFailure("com.test.Flaky");
		state.save(stateFile);
		TestOrderState loaded = TestOrderState.load(stateFile);

		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.Flaky", Set.of("app.X"));

		int cap = 7;
		var weights = new TestOrderState.ScoringWeights(0, 0, cap, 0, 0, 0, 0);
		TestScorer scorer = new TestScorer(weights, depMap, loaded, Set.of(), Set.of(), depMap.testClasses());

		TestScorer.ScoreResult result = scorer.score("com.test.Flaky");
		assertTrue(result.failScore() > cap, "Raw failScore should exceed cap (100 failures): " + result.failScore());
		assertEquals(cap, result.score(), "Score contribution must be capped at maxFailure=" + cap);
	}

	@Test
	void mixedFailureAndDepOverlapScoresStack() throws IOException {
		// Test has both failure history AND dep overlap — both should contribute
		Path stateFile = dir.resolve("state.lz4");
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.A", Set.of("app.X", "app.Y"));
		depMap.put("com.test.B", Set.of("app.Z")); // B has no overlap

		TestOrderState state = new TestOrderState();
		state.recordDuration("com.test.A", 100L);
		state.recordDuration("com.test.B", 100L);
		// Only A has failure history
		for (int i = 0; i < 3; i++)
			state.recordFailure("com.test.A");
		state.save(stateFile);
		TestOrderState loaded = TestOrderState.load(stateFile);

		// A has dep overlap (app.X changed) AND failure history
		var weights = new TestOrderState.ScoringWeights(0, 0, 5, 0, 0, 5, 0);
		TestScorer scorer = new TestScorer(weights, depMap, loaded, Set.of("app.X"), // app.X changed
				Set.of(), depMap.testClasses());

		var aResult = scorer.score("com.test.A");
		var bResult = scorer.score("com.test.B");

		assertTrue(aResult.failScore() > 0, "A should have failure contribution");
		assertTrue(aResult.depOverlap() > 0, "A should have dep overlap");
		assertTrue(aResult.score() > bResult.score(),
				"A (failure + overlap) must score higher than B (no overlap, no failure): " + aResult.score() + " vs "
						+ bResult.score());

		// Verify both components contributed
		var weightsNoFailure = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 5, 0);
		TestScorer scorerNoFail = new TestScorer(weightsNoFailure, depMap, loaded, Set.of("app.X"), Set.of(),
				depMap.testClasses());
		int aWithoutFailure = scorerNoFail.score("com.test.A").score();
		assertTrue(aResult.score() > aWithoutFailure,
				"Failure contribution should increase A's score beyond dep-overlap alone: " + aResult.score() + " vs "
						+ aWithoutFailure);
	}

	@Test
	void scorerMedianShiftsAsMoreDurationsAreRecorded() throws IOException {
		// Start with 2 tests, then add a very fast one — median should shift
		// and affect scoring
		Path stateFile = dir.resolve("state.lz4");
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.Slow", Set.of("app.A"));
		depMap.put("com.test.Medium", Set.of("app.B"));
		depMap.put("com.test.Fast", Set.of("app.C"));

		TestOrderState state = new TestOrderState();
		state.recordDuration("com.test.Slow", 1000L);
		state.recordDuration("com.test.Medium", 200L);
		state.recordDuration("com.test.Fast", 10L);
		state.save(stateFile);
		TestOrderState loaded = TestOrderState.load(stateFile);

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 3, 2, 0, 0);
		List<String> tests = List.of("com.test.Slow", "com.test.Medium", "com.test.Fast");
		TestScorer scorer = new TestScorer(weights, depMap, loaded, Set.of(), Set.of(), tests);

		var fast = scorer.score("com.test.Fast");
		var slow = scorer.score("com.test.Slow");

		assertTrue(fast.isFast(), "10ms should be fast vs median ~200ms: " + scorer.medianDuration());
		assertTrue(slow.isSlow(), "1000ms should be slow vs median ~200ms");
		assertTrue(fast.score() > 0, "fast test should get speed bonus");
		assertTrue(slow.score() < 0, "slow test should get speed penalty");
	}
}
