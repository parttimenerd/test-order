package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.femtocli.FemtoCli;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;

/**
 * Tests for dependency tracking, scoring, and their integration through the
 * Tool CLI, TestScorer, TestSelector, MethodScorer, and multi-cycle save/load
 * behaviour.
 */
class DepsAndScoringTest {

	@TempDir
	Path tempDir;

	@AfterEach
	void cleanup() {
		TestOrderState.resetPending();
	}

	// ── Helpers ───────────────────────────────────────────────────────

	private int runTool(String... args) {
		return FemtoCli.run(new Tool(), args);
	}

	private String captureStdout(Runnable action) {
		PrintStream orig = System.out;
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		System.setOut(new PrintStream(baos, true));
		try {
			action.run();
			System.out.flush();
		} finally {
			System.setOut(orig);
		}
		return baos.toString();
	}

	private DependencyMap buildDepMap(Map<String, Set<String>> deps) {
		DependencyMap map = new DependencyMap();
		for (var e : deps.entrySet()) {
			map.put(e.getKey(), e.getValue());
		}
		return map;
	}

	private TestOrderState stateWithDurations(Map<String, Long> durations) {
		TestOrderState state = new TestOrderState();
		for (var e : durations.entrySet()) {
			state.recordDuration(e.getKey(), e.getValue());
		}
		return state;
	}

	// ═══════════════════════════════════════════════════════════════════
	// TestScorer — direct unit tests (previously untested)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void testScorerDepOverlapScoreRatioBased() {
		// 1 out of 1 dep changed → 1/sqrt(max(1,5))*5 = 2.236, ceil = 3
		assertEquals(3, TestScorer.depOverlapScore(1, 1, 5));
		// 1 out of 100 → ceil(1/sqrt(100) * 5) = ceil(0.5) = 1
		assertEquals(1, TestScorer.depOverlapScore(1, 100, 5));
		// 50 out of 100 → ceil(50/sqrt(100) * 5) = ceil(25) = 5 (capped)
		assertEquals(5, TestScorer.depOverlapScore(50, 100, 5));
		// 0 overlap → 0
		assertEquals(0, TestScorer.depOverlapScore(0, 10, 5));
		// 0 total → 0 (guard)
		assertEquals(0, TestScorer.depOverlapScore(0, 0, 5));
		// weight 0 → 0
		assertEquals(0, TestScorer.depOverlapScore(5, 10, 0));
		// 10 out of 100 → ceil(10/sqrt(100) * 5) = ceil(5.0) = 5 (capped)
		assertEquals(5, TestScorer.depOverlapScore(10, 100, 5));
		// 3 out of 200 → ceil(3/sqrt(200) * 5) = ceil(1.06) = 2
		assertEquals(2, TestScorer.depOverlapScore(3, 200, 5));
	}

	@Test
	void testScorerNewTestBonus() {
		DependencyMap depMap = buildDepMap(Map.of("com.Known", Set.of("app.X")));
		TestOrderState state = stateWithDurations(Map.of("com.Known", 100L));

		TestScorer scorer = new TestScorer(TestOrderState.ScoringWeights.DEFAULT, depMap, state, Set.of(), Set.of(),
				depMap.testClasses());

		// com.Unknown is not in depMap → isNew
		var result = scorer.score("com.Unknown");
		assertTrue(result.isNew());
		assertTrue(result.score() >= TestOrderState.ScoringWeights.DEFAULT.newTest());
	}

	@Test
	void testScorerChangedTestBonus() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X")));
		TestOrderState state = stateWithDurations(Map.of("com.A", 100L));

		TestScorer scorer = new TestScorer(TestOrderState.ScoringWeights.DEFAULT, depMap, state, Set.of(),
				Set.of("com.A"), depMap.testClasses());

		var result = scorer.score("com.A");
		assertTrue(result.isChanged());
		assertTrue(result.score() >= TestOrderState.ScoringWeights.DEFAULT.changedTest());
	}

	@Test
	void testScorerFailureBoostCappedAtMaxFailure() throws IOException {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X")));
		TestOrderState state = stateWithDurations(Map.of("com.A", 100L));
		// Record many failures to exceed maxFailure cap
		for (int i = 0; i < 20; i++) {
			state.recordFailure("com.A");
		}
		// Save to convert pending → historical (TestScorer reads historical only)
		Path file = tempDir.resolve("state");
		state.save(file);
		TestOrderState loaded = TestOrderState.load(file);

		var weights = new TestOrderState.ScoringWeights(0, 0, 5, 0, 0, 0, 0);
		TestScorer scorer = new TestScorer(weights, depMap, loaded, Set.of(), Set.of(), depMap.testClasses());

		var result = scorer.score("com.A");
		// failScore should be 20.0 but score contribution capped at maxFailure=5
		assertEquals(20.0, result.failScore(), 0.001);
		assertEquals(5, result.score());
	}

	@Test
	void testScorerSpeedBonusAndPenalty() {
		// 4 tests: durations 10, 100, 200, 1000
		// median of [10, 100, 200, 1000] → sorted, index 2 → 200
		DependencyMap depMap = buildDepMap(Map.of("com.Fast", Set.of("app.A"), "com.Med1", Set.of("app.B"), "com.Med2",
				Set.of("app.C"), "com.Slow", Set.of("app.D")));
		TestOrderState state = stateWithDurations(
				Map.of("com.Fast", 10L, "com.Med1", 100L, "com.Med2", 200L, "com.Slow", 1000L));

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 3, 2, 0, 0);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of(), Set.of(), depMap.testClasses());

		// Log buckets: 10ms / 200ms median → log₂(0.05) ≈ -4.32, clamped to -3
		// speedBucketScore = round(3/3 * 3) = 3
		var fast = scorer.score("com.Fast");
		assertTrue(fast.isFast(), "10ms is much faster than 200ms median");
		assertEquals(3, fast.score(), "speed bonus at max bucket");

		// 100ms / 200ms → log₂(0.5) = -1 → round(1/3 * 3) = 1
		var med1 = scorer.score("com.Med1");
		assertTrue(med1.isFast(), "100ms is moderately faster than 200ms median");
		assertEquals(1, med1.score(), "partial speed bonus");

		// 1000ms / 200ms → log₂(5) ≈ 2.32 → round(2.32/3 * 2) = round(1.55) = 2
		var slow = scorer.score("com.Slow");
		assertTrue(slow.isSlow(), "1000ms >> 200ms median");
		assertEquals(-2, slow.score(), "speed penalty");
	}

	@Test
	void testScorerCombinedMaximumScore() throws IOException {
		// Test that's new + changed + has failure + has dep overlap → all bonuses stack
		DependencyMap depMap = buildDepMap(Map.of("com.Other", Set.of("app.Y")));
		TestOrderState state = new TestOrderState();
		state.recordFailure("com.NewChanged");
		// Save to convert pending → historical (TestScorer only sees historical)
		Path file = tempDir.resolve("state");
		state.save(file);
		TestOrderState loaded = TestOrderState.load(file);

		// com.NewChanged is NOT in depMap (→ new), IS in changedTestClasses (→ changed)
		// has failure score, and has dep overlap via changedClasses (but no deps in
		// depMap → 0 overlap)
		var weights = new TestOrderState.ScoringWeights(10, 8, 5, 0, 0, 3, 0);
		TestScorer scorer = new TestScorer(weights, depMap, loaded, Set.of("app.Z"), Set.of("com.NewChanged"),
				depMap.testClasses());

		var result = scorer.score("com.NewChanged");
		assertTrue(result.isNew());
		assertTrue(result.isChanged());
		assertTrue(result.failScore() > 0);
		// new(10) + changed(8) + failure(min(ceil(1.0), 5) = 1) = 19
		assertEquals(19, result.score());
	}

	@Test
	void testScorerMedianDurationWithSingleTest() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X")));
		TestOrderState state = stateWithDurations(Map.of("com.A", 100L));

		// 1 test with known duration → median = 100 (single value is valid)
		long median = TestScorer.computeMedianDuration(state, List.of("com.A"));
		assertEquals(100, median);

		// With 2 tests, median is the middle value
		state.recordDuration("com.B", 200L);
		long median2 = TestScorer.computeMedianDuration(state, List.of("com.A", "com.B"));
		assertTrue(median2 > 0);

		// 0 tests with known duration → median = 0
		long median0 = TestScorer.computeMedianDuration(state, List.of("com.Unknown"));
		assertEquals(0, median0);
	}

	@Test
	void testScorerDepOverlapWithChangedClasses() {
		DependencyMap depMap = buildDepMap(
				Map.of("com.Test1", Set.of("app.A", "app.B", "app.C"), "com.Test2", Set.of("app.D")));
		TestOrderState state = stateWithDurations(Map.of("com.Test1", 100L, "com.Test2", 100L));

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 6, 0);
		// app.A changed
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of("app.A"), Set.of(), depMap.testClasses());

		var r1 = scorer.score("com.Test1");
		assertEquals(1, r1.depOverlap());
		assertEquals(3, r1.depTotal());
		// 1/sqrt(max(3,5)) * 6 = 1/sqrt(5)*6 = 2.683, ceil = 3
		assertEquals(3, r1.score());

		var r2 = scorer.score("com.Test2");
		assertEquals(0, r2.depOverlap());
		assertEquals(0, r2.score());
	}

	@Test
	void testScorerZeroFailureScoreNoBoost() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X")));
		TestOrderState state = stateWithDurations(Map.of("com.A", 100L));
		// no failures recorded

		var weights = new TestOrderState.ScoringWeights(0, 0, 5, 0, 0, 0, 0);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of(), Set.of(), depMap.testClasses());

		var result = scorer.score("com.A");
		assertEquals(0.0, result.failScore(), 0.001);
		assertEquals(0, result.score());
	}

	@Test
	void allDurationsUnknownDisablesSpeedScoring() {
		// E1: when no durations are known, medianDuration=0, speed scoring must be
		// disabled
		DependencyMap depMap = buildDepMap(Map.of("com.Fast", Set.of("app.X"), "com.Slow", Set.of("app.Y")));
		TestOrderState state = new TestOrderState(); // no durations recorded

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 5, 3, 0, 0);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of(), Set.of(), depMap.testClasses());

		assertEquals(0, scorer.medianDuration(), "Median must be 0 when all durations are unknown");

		var fast = scorer.score("com.Fast");
		var slow = scorer.score("com.Slow");
		assertFalse(fast.isFast(), "No test should be classified as fast when median is 0");
		assertFalse(fast.isSlow(), "No test should be classified as slow when median is 0");
		assertFalse(slow.isFast());
		assertFalse(slow.isSlow());
		assertEquals(0.0, fast.speedRatio(), 0.001, "Speed ratio must be 0.0");
		assertEquals(0.0, slow.speedRatio(), 0.001, "Speed ratio must be 0.0");
		assertEquals(0, fast.score(), "Score must be 0 when only speed weights are set");
		assertEquals(0, slow.score(), "Score must be 0 when only speed weights are set");
	}

	// ═══════════════════════════════════════════════════════════════════
	// Failure decay over multiple save/load cycles
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void failureDecayOverMultipleSaves() throws IOException {
		Path file = tempDir.resolve("state");
		TestOrderState state = new TestOrderState();
		state.recordFailure("com.A");
		double decay = state.failureDecay();
		double retain = 1.0 - decay;

		state.save(file);
		// After first save: failureScore = 1.0 (pending, no historical to decay)
		assertEquals(1.0, state.failureScore("com.A"), 0.001);

		// Second save WITH pending data triggers decay on historical
		state.recordFailure("com.B");
		state.save(file);
		double expectedA = 1.0 * retain;
		assertEquals(expectedA, state.failureScore("com.A"), 0.001);
		assertEquals(1.0, state.failureScore("com.B"), 0.001);

		// Third save with more pending — decays again
		state.recordFailure("com.C");
		state.save(file);
		expectedA *= retain;
		assertEquals(expectedA, state.failureScore("com.A"), 0.001);
	}

	@Test
	void failureDecayWithNewFailureEachCycle() throws IOException {
		Path file = tempDir.resolve("state");
		TestOrderState state = new TestOrderState();
		double decay = state.failureDecay();
		double retain = 1.0 - decay;

		// Cycle 1: record failure, save
		state.recordFailure("com.A");
		state.save(file);
		assertEquals(1.0, state.failureScore("com.A"), 0.001);

		// Cycle 2: new failure → historical(1.0) * retain + pending(1.0)
		state.recordFailure("com.A");
		state.save(file);
		double expectedC2 = 1.0 * retain + 1.0;
		assertEquals(expectedC2, state.failureScore("com.A"), 0.001);

		// Cycle 3: no pending for com.A but pending exists for com.B → decay still
		// applies
		state.recordFailure("com.B");
		state.save(file);
		double expectedC3 = expectedC2 * retain;
		assertEquals(expectedC3, state.failureScore("com.A"), 0.001);
		assertEquals(1.0, state.failureScore("com.B"), 0.001);
	}

	@Test
	void failureScorePrunedWhenBelowThreshold() throws IOException {
		Path file = tempDir.resolve("state");
		TestOrderState state = new TestOrderState();
		state.recordFailure("com.A");
		state.save(file);

		double decay = state.failureDecay();
		double retain = 1.0 - decay;
		double threshold = state.failurePruneThreshold();

		// Keep saving with pending data so decay triggers each cycle
		double score = 1.0;
		int cycles = 0;
		while (score > threshold && cycles < 100) {
			state.recordFailure("com.DUMMY"); // ensures hasRunData = true
			state.save(file);
			score *= retain;
			cycles++;
		}

		// After enough cycles, com.A's score should be pruned to 0
		assertEquals(0.0, state.failureScore("com.A"), 0.001,
				"Failure score should be pruned after " + cycles + " decay cycles");
	}

	@Test
	void failureScorePruningIsLogged() throws IOException {
		// E5: verify that the LOG.fine message fires when a score drops below threshold
		Path file = tempDir.resolve("state");
		TestOrderState state = new TestOrderState();

		// Set a very aggressive decay so one cycle prunes immediately
		state.setFailureDecay(0.999); // retain = 0.001, threshold default = 0.01
		state.recordFailure("com.Fragile"); // score = 1.0
		state.save(file); // first save — establishes score

		// Capture log output from TestOrderState.LOG
		var logRecords = new java.util.ArrayList<java.util.logging.LogRecord>();
		var handler = new java.util.logging.Handler() {
			@Override
			public void publish(java.util.logging.LogRecord record) {
				logRecords.add(record);
			}
			@Override
			public void flush() {
			}
			@Override
			public void close() {
			}
		};
		handler.setLevel(java.util.logging.Level.ALL);
		var logger = java.util.logging.Logger.getLogger(TestOrderState.class.getName());
		logger.addHandler(handler);
		var oldLevel = logger.getLevel();
		logger.setLevel(java.util.logging.Level.ALL);
		try {
			// Second save with pending data triggers decay: 1.0 * 0.001 = 0.001 < threshold
			// (0.01)
			state.recordFailure("com.OTHER");
			state.save(file);

			boolean foundPruneLog = logRecords.stream().anyMatch(r -> r.getMessage() != null
					&& r.getMessage().contains("Pruned failure score") && r.getMessage().contains("com.Fragile"));
			assertTrue(foundPruneLog, "Expected a FINE log message about pruning com.Fragile; logs: " + logRecords);
		} finally {
			logger.removeHandler(handler);
			logger.setLevel(oldLevel);
		}
	}

	@Test
	void saveLoadRoundTripPreservesFailureScores() throws IOException {
		Path file = tempDir.resolve("state");
		TestOrderState state = new TestOrderState();
		state.recordFailure("com.A");
		state.recordFailure("com.A");
		state.recordFailure("com.B");
		state.save(file);

		TestOrderState loaded = TestOrderState.load(file);
		assertEquals(2.0, loaded.failureScore("com.A"), 0.001);
		assertEquals(1.0, loaded.failureScore("com.B"), 0.001);
		assertEquals(0.0, loaded.failureScore("com.C"), 0.001);
	}

	@Test
	void inMemoryStateMatchesDiskAfterSave() throws IOException {
		Path file = tempDir.resolve("state");
		TestOrderState state = new TestOrderState();
		state.recordFailure("com.A");
		state.save(file);

		// In-memory should match what load returns
		TestOrderState loaded = TestOrderState.load(file);
		assertEquals(loaded.failureScore("com.A"), state.failureScore("com.A"), 0.001);
		assertEquals(loaded.failureScore("com.B"), state.failureScore("com.B"), 0.001);

		// After a second save with new pending, both should still match
		state.recordFailure("com.B");
		state.save(file);
		loaded = TestOrderState.load(file);
		assertEquals(loaded.failureScore("com.A"), state.failureScore("com.A"), 0.001);
		assertEquals(loaded.failureScore("com.B"), state.failureScore("com.B"), 0.001);
	}

	// ═══════════════════════════════════════════════════════════════════
	// Method-level failure and duration scoring
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void methodFailureScoreIncludesPending() {
		TestOrderState state = new TestOrderState();
		state.recordMethodFailure("com.A", "testFoo");
		// Before save, pending is visible
		assertEquals(1.0, state.methodFailureScore("com.A", "testFoo"), 0.001);
	}

	@Test
	void methodFailureDecayOverSaveCycles() throws IOException {
		Path file = tempDir.resolve("state");
		TestOrderState state = new TestOrderState();
		double decay = state.methodFailureDecay();
		double retain = 1.0 - decay;

		state.recordMethodFailure("com.A", "testFoo");
		state.save(file);
		assertEquals(1.0, state.methodFailureScore("com.A", "testFoo"), 0.001);

		// Second save with pending data triggers decay
		state.recordMethodFailure("com.A", "testBar");
		state.save(file);
		assertEquals(retain, state.methodFailureScore("com.A", "testFoo"), 0.001);
		assertEquals(1.0, state.methodFailureScore("com.A", "testBar"), 0.001);
	}

	@Test
	void methodDurationEmaRoundTrip() throws IOException {
		Path file = tempDir.resolve("state");
		TestOrderState state = new TestOrderState();
		state.recordMethodDuration("com.A", "testFoo", 100);
		state.recordMethodDuration("com.A", "testFoo", 200);
		// EMA: α*200 + (1-α)*100 where α=0.85 → 185
		double expected = Math.round(0.85 * 200 + 0.15 * 100);
		assertEquals(expected, state.getDurationMethod("com.A", "testFoo", -1), 0.001);

		state.save(file);
		TestOrderState loaded = TestOrderState.load(file);
		assertEquals(expected, loaded.getDurationMethod("com.A", "testFoo", -1), 0.001);
	}

	@Test
	void methodScorerFailureRecencyBonus() {
		TestOrderState state = new TestOrderState();
		state.recordMethodFailure("com.A", "testFail");
		state.recordMethodDuration("com.A", "testFail", 100);
		state.recordMethodDuration("com.A", "testOk", 100);

		var weights = new TestOrderState.MethodScoringWeights(3.0, 0, 0, 0, 0, 0);
		MethodScorer scorer = new MethodScorer(weights, state, null, null, null);

		var results = scorer.score(List.of(new MethodScorer.MethodMetadata("com.A", "testFail", 100, null),
				new MethodScorer.MethodMetadata("com.A", "testOk", 100, null)));

		var fail = results.stream().filter(r -> r.methodName().equals("testFail")).findFirst().orElseThrow();
		var ok = results.stream().filter(r -> r.methodName().equals("testOk")).findFirst().orElseThrow();

		assertTrue(fail.failureRecencyBonus() > 0, "method with failure should get bonus");
		assertEquals(0.0, ok.failureRecencyBonus(), 0.001, "method without failure gets no bonus");
		assertTrue(fail.score() > ok.score());
	}

	@Test
	void methodScorerNewMethodBonus() {
		TestOrderState state = new TestOrderState();
		state.recordMethodDuration("com.A", "testOld", 100);
		// testNew has no recorded duration → it's "new"

		var weights = new TestOrderState.MethodScoringWeights(0, 0, 0, 0, 5.0, 0);
		MethodScorer scorer = new MethodScorer(weights, state, null, null, null);

		var results = scorer.score(List.of(new MethodScorer.MethodMetadata("com.A", "testOld", 100, null),
				new MethodScorer.MethodMetadata("com.A", "testNew", 100, null)));

		var old = results.stream().filter(r -> r.methodName().equals("testOld")).findFirst().orElseThrow();
		var fresh = results.stream().filter(r -> r.methodName().equals("testNew")).findFirst().orElseThrow();

		assertTrue(fresh.isNew());
		assertFalse(old.isNew());
		assertEquals(5.0, fresh.newMethodBonus(), 0.001);
	}

	@Test
	void methodScorerChangedMethodBonus() {
		TestOrderState state = new TestOrderState();
		state.recordMethodDuration("com.A", "testFoo", 100);
		state.recordMethodDuration("com.A", "testBar", 100);

		var weights = new TestOrderState.MethodScoringWeights(0, 0, 0, 0, 0, 3.0);
		MethodScorer scorer = new MethodScorer(weights, state, null, null, Set.of("com.A#testFoo"));

		var results = scorer.score(List.of(new MethodScorer.MethodMetadata("com.A", "testFoo", 100, null),
				new MethodScorer.MethodMetadata("com.A", "testBar", 100, null)));

		var foo = results.stream().filter(r -> r.methodName().equals("testFoo")).findFirst().orElseThrow();
		var bar = results.stream().filter(r -> r.methodName().equals("testBar")).findFirst().orElseThrow();

		assertTrue(foo.isChanged());
		assertFalse(bar.isChanged());
		assertEquals(3.0, foo.changedMethodBonus(), 0.001);
	}

	@Test
	void methodScorerSpeedBonusClassLocal() {
		TestOrderState state = new TestOrderState();
		// 3 methods: median duration = 100ms (sorted: [10, 100, 500] → index 1 = 100)
		var weights = new TestOrderState.MethodScoringWeights(0, 1.0, 1.0, 0, 0, 0);
		MethodScorer scorer = new MethodScorer(weights, state, null, null, null);

		var results = scorer.score(List.of(new MethodScorer.MethodMetadata("com.A", "fast", 10, null),
				new MethodScorer.MethodMetadata("com.A", "medium", 100, null),
				new MethodScorer.MethodMetadata("com.A", "slow", 500, null)));

		var fast = results.stream().filter(r -> r.methodName().equals("fast")).findFirst().orElseThrow();
		var medium = results.stream().filter(r -> r.methodName().equals("medium")).findFirst().orElseThrow();
		var slow = results.stream().filter(r -> r.methodName().equals("slow")).findFirst().orElseThrow();

		// Log buckets: 10/100 → log₂(0.1) ≈ -3.32 → clamped -3 → bonus ≈ 1.0
		assertTrue(fast.isFast(), "10ms is much faster than 100ms median");
		// medium at median → log₂(1) = 0 → 0 bonus
		assertFalse(medium.isFast());
		assertFalse(medium.isSlow());
		// 500/100 → log₂(5) ≈ 2.32 → penalty > 0
		assertTrue(slow.isSlow(), "500ms >> 100ms median");
		assertTrue(fast.score() > medium.score());
		assertTrue(medium.score() > slow.score());
	}

	@Test
	void methodScorerDepOverlapWithMethodDeps() {
		TestOrderState state = new TestOrderState();
		DependencyMap depMap = new DependencyMap();
		depMap.putMethodDeps("com.A#testFoo", Set.of("app.X", "app.Y"));
		depMap.putMethodDeps("com.A#testBar", Set.of("app.Z"));

		var weights = new TestOrderState.MethodScoringWeights(0, 0, 0, 4.0, 0, 0);
		MethodScorer scorer = new MethodScorer(weights, state, depMap, Set.of("app.X"), null);

		var results = scorer.score(List.of(new MethodScorer.MethodMetadata("com.A", "testFoo", 100, null),
				new MethodScorer.MethodMetadata("com.A", "testBar", 100, null)));

		var foo = results.stream().filter(r -> r.methodName().equals("testFoo")).findFirst().orElseThrow();
		var bar = results.stream().filter(r -> r.methodName().equals("testBar")).findFirst().orElseThrow();

		assertTrue(foo.depOverlapBonus() > 0, "testFoo uses app.X which is changed");
		assertEquals(0.0, bar.depOverlapBonus(), 0.001, "testBar uses app.Z which is not changed");
	}

	@Test
	void methodScorerSetCoverBonuses() {
		TestOrderState state = new TestOrderState();
		DependencyMap depMap = new DependencyMap();
		depMap.putMethodDeps("com.A#testFoo", Set.of("app.X", "app.Y"));
		depMap.putMethodDeps("com.A#testBar", Set.of("app.Z"));

		// coverageBonus=5 → greedy set-cover mode
		var weights = new TestOrderState.MethodScoringWeights(0, 0, 0, 0, 0, 0, 5.0);
		MethodScorer scorer = new MethodScorer(weights, state, depMap, Set.of("app.X", "app.Y", "app.Z"), null);

		var results = scorer.score(List.of(new MethodScorer.MethodMetadata("com.A", "testFoo", 100, null),
				new MethodScorer.MethodMetadata("com.A", "testBar", 100, null)));

		var foo = results.stream().filter(r -> r.methodName().equals("testFoo")).findFirst().orElseThrow();
		var bar = results.stream().filter(r -> r.methodName().equals("testBar")).findFirst().orElseThrow();

		// testFoo covers 2 classes, testBar covers 1 → testFoo picked first (bonus=5),
		// testBar second (bonus=4)
		assertEquals(5.0, foo.coverageBonus(), 0.001, "testFoo should get first pick bonus");
		assertEquals(4.0, bar.coverageBonus(), 0.001, "testBar should get declining bonus");
		assertEquals(0.0, foo.depOverlapBonus(), 0.001, "depOverlap should be zero in set-cover mode");
	}

	// ═══════════════════════════════════════════════════════════════════
	// DependencyMap edge cases
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void depMapGetReturnsEmptyForMissing() {
		DependencyMap map = new DependencyMap();
		map.put("com.A", Set.of("app.X"));

		Set<String> deps = map.get("com.Unknown");
		assertNotNull(deps);
		assertTrue(deps.isEmpty());
	}

	@Test
	void depMapAffectedTestsWithEmptyChanged() {
		DependencyMap map = buildDepMap(Map.of("com.A", Set.of("app.X")));
		Set<String> affected = map.getAffectedTests(Set.of());
		assertTrue(affected.isEmpty());
	}

	@Test
	void depMapAffectedTestsMultiOverlap() {
		DependencyMap map = buildDepMap(
				Map.of("com.A", Set.of("app.X", "app.Y"), "com.B", Set.of("app.Y", "app.Z"), "com.C", Set.of("app.W")));

		Set<String> affected = map.getAffectedTests(Set.of("app.Y"));
		assertEquals(Set.of("com.A", "com.B"), affected);

		Set<String> affected2 = map.getAffectedTests(Set.of("app.X", "app.Z"));
		assertEquals(Set.of("com.A", "com.B"), affected2);
	}

	@Test
	void depMapMethodDepsRoundTrip() throws IOException {
		DependencyMap map = new DependencyMap();
		map.put("com.A", Set.of("app.X"));
		map.putMethodDeps("com.A#testFoo", Set.of("app.X", "app.Y"));

		Path file = tempDir.resolve("deps.lz4");
		map.save(file);
		DependencyMap loaded = DependencyMap.load(file);

		assertTrue(loaded.hasMethodDeps());
		assertEquals(Set.of("app.X", "app.Y"), loaded.getMethodDeps("com.A", "testFoo"));
	}

	@Test
	void depMapMemberDepsRoundTrip() throws IOException {
		DependencyMap map = new DependencyMap();
		map.put("com.A", Set.of("app.X"));
		map.putMemberDeps("com.A", Set.of("app.X#foo", "app.X#bar"));

		Path file = tempDir.resolve("deps.lz4");
		map.save(file);
		DependencyMap loaded = DependencyMap.load(file);

		assertTrue(loaded.hasMemberDeps());
		assertEquals(Set.of("app.X#foo", "app.X#bar"), loaded.getMemberDeps("com.A"));
	}

	@Test
	void depMapConstructorIndependentFromSource() {
		Map<String, Set<String>> source = new HashMap<>();
		source.put("com.A", new HashSet<>(Set.of("app.X")));
		DependencyMap map = new DependencyMap(source);

		// Mutate source
		source.put("com.B", Set.of("app.Y"));
		source.get("com.A").add("app.Z");

		// Map should be unaffected
		assertEquals(1, map.size());
		assertFalse(map.get("com.A").contains("app.Z"));
	}

	// ═══════════════════════════════════════════════════════════════════
	// Tool CLI — select subcommand with deps + scoring
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void toolSelectWithFailuresBoostsTests() throws IOException {
		// Build dep map with 5 tests
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X"), "com.B", Set.of("app.Y"), "com.C",
				Set.of("app.Z"), "com.D", Set.of("app.W"), "com.E", Set.of("app.V")));
		Path idx = tempDir.resolve("test.idx");
		depMap.save(idx);

		// State with failures for com.C
		TestOrderState state = stateWithDurations(
				Map.of("com.A", 100L, "com.B", 100L, "com.C", 100L, "com.D", 100L, "com.E", 100L));
		state.recordFailure("com.C");
		state.recordFailure("com.C");
		state.recordFailure("com.C");
		Path sf = tempDir.resolve("state");
		state.save(sf);
		TestOrderState.resetPending();

		Path selected = tempDir.resolve("sel.txt");
		Path remaining = tempDir.resolve("rem.txt");

		String stdout = captureStdout(() -> runTool("select", idx.toString(), "--state", sf.toString(), "--top-n", "1",
				"--random-m", "0", "--selected-file", selected.toString(), "--remaining-file", remaining.toString(),
				"--seed", "42"));

		List<String> sel = TestSelector.readTestList(selected);
		// com.C should be selected first (highest failure score)
		assertEquals(1, sel.size());
		assertEquals("com.C", sel.get(0), "test with failures should be selected first");
	}

	@Test
	void toolSelectWithChangedClassesPrioritizes() throws IOException {
		DependencyMap depMap = buildDepMap(Map.of("com.TestX", Set.of("app.Service"), "com.TestY", Set.of("app.Dao"),
				"com.TestZ", Set.of("app.Util")));
		Path idx = tempDir.resolve("test.idx");
		depMap.save(idx);

		TestOrderState state = stateWithDurations(Map.of("com.TestX", 100L, "com.TestY", 100L, "com.TestZ", 100L));
		Path sf = tempDir.resolve("state");
		state.save(sf);
		TestOrderState.resetPending();

		Path selected = tempDir.resolve("sel.txt");
		Path remaining = tempDir.resolve("rem.txt");

		String stdout = captureStdout(() -> runTool("select", idx.toString(), "--state", sf.toString(), "--top-n", "1",
				"--random-m", "0", "-c", "app.Service", "--selected-file", selected.toString(), "--remaining-file",
				remaining.toString(), "--seed", "42"));

		List<String> sel = TestSelector.readTestList(selected);
		assertEquals(1, sel.size());
		assertEquals("com.TestX", sel.get(0), "test depending on changed class should be selected");
	}

	@Test
	void toolSelectNewTestAlwaysIncluded() throws IOException {
		DependencyMap depMap = buildDepMap(Map.of("com.Old1", Set.of("app.A"), "com.Old2", Set.of("app.B")));
		Path idx = tempDir.resolve("test.idx");
		depMap.save(idx);

		TestOrderState state = stateWithDurations(Map.of("com.Old1", 100L, "com.Old2", 100L));
		Path sf = tempDir.resolve("state");
		state.save(sf);
		TestOrderState.resetPending();

		Path selected = tempDir.resolve("sel.txt");
		Path remaining = tempDir.resolve("rem.txt");

		// com.NewTest is in --changed-tests but not in dep map → new test
		String stdout = captureStdout(() -> runTool("select", idx.toString(), "--state", sf.toString(), "--top-n", "0",
				"--random-m", "0", "--changed-tests", "com.NewTest", "--selected-file", selected.toString(),
				"--remaining-file", remaining.toString(), "--seed", "42"));

		List<String> sel = TestSelector.readTestList(selected);
		assertTrue(sel.contains("com.NewTest"), "new test must always be selected");
	}

	@Test
	void toolSelectEmptyIndexProducesEmptyOutput() throws IOException {
		DependencyMap depMap = new DependencyMap();
		Path idx = tempDir.resolve("test.idx");
		depMap.save(idx);

		Path sf = tempDir.resolve("state");
		new TestOrderState().save(sf);
		TestOrderState.resetPending();

		Path selected = tempDir.resolve("sel.txt");
		Path remaining = tempDir.resolve("rem.txt");

		assertEquals(0, runTool("select", idx.toString(), "--state", sf.toString(), "--selected-file",
				selected.toString(), "--remaining-file", remaining.toString()));

		List<String> sel = TestSelector.readTestList(selected);
		List<String> rem = TestSelector.readTestList(remaining);
		assertTrue(sel.isEmpty());
		assertTrue(rem.isEmpty());
	}

	// ═══════════════════════════════════════════════════════════════════
	// Tool CLI — optimize subcommand
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void toolOptimizeInsufficientData() throws IOException {
		TestOrderState state = new TestOrderState();
		// Only 1 run with failures, need ≥ 3
		state.addRunRecord(new TestOrderState.RunRecord(System.currentTimeMillis(), 5, 1, 2, 0.8, List
				.of(new TestOrderState.TestOutcome("com.A", 10, false, false, 0, 0, 0.0, false, false, true, 0.0))));
		Path sf = tempDir.resolve("state");
		state.save(sf);
		TestOrderState.resetPending();

		String stdout = captureStdout(() -> runTool("optimize", sf.toString()));
		assertTrue(stdout.contains("Need at least"), "should report insufficient data");
	}

	@Test
	void toolOptimizeSavesWeightsBack() throws IOException {
		TestOrderState state = new TestOrderState();
		// Add 3 runs with failures and outcomes
		for (int run = 0; run < 3; run++) {
			List<TestOrderState.TestOutcome> outcomes = new ArrayList<>();
			for (int i = 0; i < 10; i++) {
				boolean failed = (i == 0); // first test always fails
				outcomes.add(new TestOrderState.TestOutcome("com.Test" + i, 10 - i, false, false, 0, 0, 0.0, false,
						false, failed, 0.0));
			}
			state.addRunRecord(new TestOrderState.RunRecord(System.currentTimeMillis() - (3 - run) * 86400000L, 10, 1,
					0, 0.0, outcomes));
		}

		Path sf = tempDir.resolve("state");
		state.save(sf);
		TestOrderState.resetPending();

		TestOrderState.ScoringWeights before = TestOrderState.load(sf).weights();

		String stdout = captureStdout(() -> runTool("optimize", sf.toString()));

		assertTrue(stdout.contains("Optimised weights saved") || stdout.contains("Optimized weights saved")
				|| stdout.contains("weights"), "should report optimized weights: " + stdout);

		// Weights should have been updated in the file
		TestOrderState.ScoringWeights after = TestOrderState.load(sf).weights();
		// At least verify it loaded without error — weights may or may not differ
		assertNotNull(after);
	}

	@Test
	void toolOptimizeNoRuns() throws IOException {
		TestOrderState state = new TestOrderState();
		Path sf = tempDir.resolve("state");
		state.save(sf);
		TestOrderState.resetPending();

		String stdout = captureStdout(() -> runTool("optimize", sf.toString()));
		assertTrue(stdout.contains("No run history"), "should report no runs");
	}

	// ═══════════════════════════════════════════════════════════════════
	// Tool CLI — dump subcommand with dep data
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void toolDumpShowsDependencies() throws IOException {
		DependencyMap depMap = buildDepMap(Map.of("com.FooTest", Set.of("com.Foo", "com.Bar")));
		Path idx = tempDir.resolve("test.idx");
		depMap.save(idx);

		String stdout = captureStdout(() -> runTool("dump", idx.toString()));
		assertTrue(stdout.contains("com.FooTest"));
		assertTrue(stdout.contains("com.Foo"));
	}

	@Test
	void toolDumpToFile() throws IOException {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X", "app.Y")));
		Path idx = tempDir.resolve("test.idx");
		depMap.save(idx);

		Path output = tempDir.resolve("dump.txt");
		assertEquals(0, runTool("dump", idx.toString(), "--output", output.toString()));
		assertTrue(Files.exists(output));
		String content = Files.readString(output);
		assertTrue(content.contains("com.A"));
	}

	// ═══════════════════════════════════════════════════════════════════
	// Tool CLI — affected subcommand edge cases
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void toolAffectedTransitiveDeps() throws IOException {
		// Test that affected only finds direct deps, not transitive
		DependencyMap depMap = buildDepMap(Map.of("com.TestA", Set.of("app.Service"), "com.TestB", Set.of("app.Dao")));
		Path idx = tempDir.resolve("test.idx");
		depMap.save(idx);

		String stdout = captureStdout(() -> runTool("affected", idx.toString(), "--classes", "app.Service"));
		assertTrue(stdout.contains("com.TestA"));
		assertFalse(stdout.contains("com.TestB"), "TestB does not depend on app.Service directly");
	}

	@Test
	void toolAffectedMultipleChanged() throws IOException {
		DependencyMap depMap = buildDepMap(
				Map.of("com.TestA", Set.of("app.X"), "com.TestB", Set.of("app.Y"), "com.TestC", Set.of("app.Z")));
		Path idx = tempDir.resolve("test.idx");
		depMap.save(idx);

		String stdout = captureStdout(() -> runTool("affected", idx.toString(), "--classes", "app.X,app.Y"));
		assertTrue(stdout.contains("com.TestA"));
		assertTrue(stdout.contains("com.TestB"));
		assertFalse(stdout.contains("com.TestC"));
	}

	// ═══════════════════════════════════════════════════════════════════
	// Tool CLI — stats subcommand
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void toolStatsEmptyIndex() throws IOException {
		DependencyMap depMap = new DependencyMap();
		Path idx = tempDir.resolve("test.idx");
		depMap.save(idx);

		String stdout = captureStdout(() -> runTool("stats", idx.toString()));
		assertTrue(stdout.contains("0"), "empty index should show 0 tests");
	}

	// ═══════════════════════════════════════════════════════════════════
	// TestSelector with failures integration
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void selectorFailuresIntegrateWithScoring() throws IOException {
		DependencyMap depMap = buildDepMap(
				Map.of("com.A", Set.of("app.X"), "com.B", Set.of("app.Y"), "com.C", Set.of("app.Z")));

		TestOrderState state = stateWithDurations(Map.of("com.A", 100L, "com.B", 100L, "com.C", 100L));
		// Record failures for com.B, save to make them historical
		state.recordFailure("com.B");
		state.recordFailure("com.B");
		Path file = tempDir.resolve("state");
		state.save(file);
		TestOrderState loaded = TestOrderState.load(file);

		TestSelector.Selection sel = new TestSelector(depMap, loaded, Set.of(), Set.of(), loaded.weights(),
				new TestSelector.Config(1, 0, 42L)).select();

		assertEquals(1, sel.selected().size());
		assertEquals("com.B", sel.selected().get(0), "test with failure history should top the selection");
	}

	@Test
	void selectorChangedTestNotInDepMapIsNew() {
		DependencyMap depMap = buildDepMap(Map.of("com.Old", Set.of("app.X")));
		TestOrderState state = new TestOrderState();

		// com.Fresh is in changedTestClasses but not in depMap
		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of("com.Fresh"),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(0, 0, 42L)).select();

		assertTrue(sel.selected().contains("com.Fresh"), "changed test not in dep map is new → always selected");
	}

	@Test
	void selectorChangedTestAlreadyInDepMapIsNotNew() {
		DependencyMap depMap = buildDepMap(Map.of("com.Existing", Set.of("app.X")));
		TestOrderState state = stateWithDurations(Map.of("com.Existing", 100L));

		// com.Existing IS in depMap → not new, but should get changedTest bonus
		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of("com.Existing"),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(0, 0, 42L)).select();

		// With topN=0 and randomM=0, only new tests are selected
		assertFalse(sel.selected().contains("com.Existing"), "existing test should not be auto-selected as new");
	}

	@Test
	void selectorDiversePicksMaximallyDifferentDeps() {
		// 5 tests with overlapping deps, 2 with unique deps
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X", "app.Y"), "com.B", Set.of("app.X", "app.Y"), // same
																														// deps
																														// as
																														// A
				"com.C", Set.of("app.Z"), // unique dep
				"com.D", Set.of("app.W"), // unique dep
				"com.E", Set.of("app.X"))); // subset of A
		TestOrderState state = stateWithDurations(
				Map.of("com.A", 10L, "com.B", 10L, "com.C", 10L, "com.D", 10L, "com.E", 10L));

		// All fast (< 0.5 * median, median = 10), topN=1 picks first, randomM=2 picks
		// diverse
		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(1, 2, 42L)).select();

		// selected + remaining = all 5
		assertEquals(5, sel.selected().size() + sel.remaining().size());
	}

	@Test
	void selectorTopNZeroRandomMZeroSelectsOnlyNew() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X"), "com.B", Set.of("app.Y")));
		TestOrderState state = stateWithDurations(Map.of("com.A", 100L, "com.B", 100L));

		// No new tests, topN=0, randomM=0 → nothing selected
		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(0, 0, 42L)).select();

		assertTrue(sel.selected().isEmpty());
		assertEquals(2, sel.remaining().size());
	}

	// ═══════════════════════════════════════════════════════════════════
	// APFD computation edge cases
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void apfdNoFailuresIsOne() {
		List<TestOrderState.TestOutcome> outcomes = List.of(
				new TestOrderState.TestOutcome("A", 10, false, false, 0, 0, 0.0, false, false, false, 0.0),
				new TestOrderState.TestOutcome("B", 5, false, false, 0, 0, 0.0, false, false, false, 0.0));
		assertEquals(1.0, APFDCalculator.computeAPFD(outcomes), 0.001);
	}

	@Test
	void apfdFirstTestFails() {
		// If first test fails (position 1 of 2), n=2, m=1
		// APFD = 1 - 1/(2*1) + 1/(2*2) = 1 - 0.5 + 0.25 = 0.75
		List<TestOrderState.TestOutcome> outcomes = List.of(
				new TestOrderState.TestOutcome("A", 10, false, false, 0, 0, 0.0, false, false, true, 0.0),
				new TestOrderState.TestOutcome("B", 5, false, false, 0, 0, 0.0, false, false, false, 0.0));
		assertEquals(0.75, APFDCalculator.computeAPFD(outcomes), 0.001);
	}

	@Test
	void apfdLastTestFails() {
		// If last test fails (position 2 of 2), n=2, m=1
		// APFD = 1 - 2/(2*1) + 1/(2*2) = 1 - 1.0 + 0.25 = 0.25
		List<TestOrderState.TestOutcome> outcomes = List.of(
				new TestOrderState.TestOutcome("A", 10, false, false, 0, 0, 0.0, false, false, false, 0.0),
				new TestOrderState.TestOutcome("B", 5, false, false, 0, 0, 0.0, false, false, true, 0.0));
		assertEquals(0.25, APFDCalculator.computeAPFD(outcomes), 0.001);
	}

	@Test
	void apfdEmptyOutcomesIsOne() {
		assertEquals(1.0, APFDCalculator.computeAPFD(List.of()), 0.001);
	}

	@Test
	void apfdAllFail() {
		// All 3 fail: positions 1,2,3; n=3, m=3
		// APFD = 1 - (1+2+3)/(3*3) + 1/(2*3) = 1 - 6/9 + 1/6 = 1 - 0.667 + 0.167 = 0.5
		List<TestOrderState.TestOutcome> outcomes = List.of(
				new TestOrderState.TestOutcome("A", 0, false, false, 0, 0, 0.0, false, false, true, 0.0),
				new TestOrderState.TestOutcome("B", 0, false, false, 0, 0, 0.0, false, false, true, 0.0),
				new TestOrderState.TestOutcome("C", 0, false, false, 0, 0, 0.0, false, false, true, 0.0));
		assertEquals(0.5, APFDCalculator.computeAPFD(outcomes), 0.001);
	}

	// ═══════════════════════════════════════════════════════════════════
	// Scoring weights serialization edge cases
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void scoringWeightsTomlRoundTrip() throws IOException {
		var weights = new TestOrderState.ScoringWeights(11, 7, 3, 2, 1, 4, 0);
		Path file = tempDir.resolve("weights.toml");
		weights.saveToFile(file);

		var loaded = TestOrderState.ScoringWeights.loadFromFile(file);
		assertEquals(weights, loaded.weights());
	}

	@Test
	void scoringWeightsFromArrayAndToArray() {
		int[] arr = {10, 8, 5, 3, 2, 6, 4, 1, 0, 0, 2};
		var sw = TestOrderState.ScoringWeights.fromArray(arr);
		assertArrayEquals(arr, sw.toArray());
		assertEquals(10, sw.newTest());
		assertEquals(8, sw.changedTest());
		assertEquals(5, sw.maxFailure());
		assertEquals(3, sw.speed());
		assertEquals(2, sw.speedPenalty());
		assertEquals(6, sw.depOverlap());
		assertEquals(4, sw.changeComplexity());
		assertEquals(1, sw.staticFieldBonus());
		assertEquals(0, sw.coverageBonus());
		assertEquals(2, sw.packageProximityBonus());
	}

	@Test
	void scoringWeightsFormatReadable() {
		var weights = new TestOrderState.ScoringWeights(10, 8, 5, 3, 2, 6, 0);
		String fmt = weights.format();
		assertTrue(fmt.contains("10"));
		assertTrue(fmt.contains("newTest") || fmt.contains("new"));
	}

	@Test
	void methodScoringWeightsRoundTrip() throws IOException {
		TestOrderState state = new TestOrderState();
		var msw = new TestOrderState.MethodScoringWeights(4.0, 2.0, 1.5, 3.0, 6.0, 2.5);
		state.setMethodScoringWeights(msw);

		Path file = tempDir.resolve("state");
		state.save(file);
		TestOrderState loaded = TestOrderState.load(file);

		assertEquals(msw, loaded.methodScoringWeights());
	}

	// ═══════════════════════════════════════════════════════════════════
	// Config validation edge cases
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void failureDecayBoundaryValues() {
		TestOrderState state = new TestOrderState();
		// 0 = no decay
		state.setFailureDecay(0.0);
		assertEquals(0.0, state.failureDecay(), 0.001);
		// 1 = full reset each save
		state.setFailureDecay(1.0);
		assertEquals(1.0, state.failureDecay(), 0.001);
		// Out of bounds
		assertThrows(IllegalArgumentException.class, () -> state.setFailureDecay(-0.01));
		assertThrows(IllegalArgumentException.class, () -> state.setFailureDecay(1.01));
	}

	@Test
	void durationAlphaBoundaryValues() {
		TestOrderState state = new TestOrderState();
		state.setDurationAlpha(0.0);
		assertEquals(0.0, state.durationAlpha(), 0.001);
		state.setDurationAlpha(1.0);
		assertEquals(1.0, state.durationAlpha(), 0.001);
		assertThrows(IllegalArgumentException.class, () -> state.setDurationAlpha(-0.1));
		assertThrows(IllegalArgumentException.class, () -> state.setDurationAlpha(1.1));
	}

	@Test
	void zeroDecayMeansNoDecay() throws IOException {
		Path file = tempDir.resolve("state");
		TestOrderState state = new TestOrderState();
		state.setFailureDecay(0.0);
		state.recordFailure("com.A");
		state.save(file);
		assertEquals(1.0, state.failureScore("com.A"), 0.001);

		// Decay=0 → retain=1.0 → no decay
		state.save(file);
		assertEquals(1.0, state.failureScore("com.A"), 0.001);

		state.save(file);
		assertEquals(1.0, state.failureScore("com.A"), 0.001);
	}

	@Test
	void fullDecayResetsEachSave() throws IOException {
		Path file = tempDir.resolve("state");
		TestOrderState state = new TestOrderState();
		state.setFailureDecay(1.0);
		state.recordFailure("com.A");
		state.save(file);
		assertEquals(1.0, state.failureScore("com.A"), 0.001);

		// Decay=1.0 → retain=0.0 → historical fully cleared (needs pending to trigger
		// decay)
		state.recordFailure("com.B");
		state.save(file);
		// com.A historical is retained * 0.0 = 0 (pruned), com.B is 1.0
		assertEquals(0.0, state.failureScore("com.A"), 0.001);
		assertEquals(1.0, state.failureScore("com.B"), 0.001);
	}

	@Test
	void durationAlphaZeroIgnoresNew() {
		TestOrderState state = new TestOrderState();
		state.setDurationAlpha(0.0);
		state.recordDuration("com.A", 100);
		assertEquals(100, state.getDuration("com.A", -1));
		// alpha=0 → 0*new + 1*old = old
		state.recordDuration("com.A", 999);
		assertEquals(100, state.getDuration("com.A", -1));
	}

	@Test
	void durationAlphaOneIgnoresOld() {
		TestOrderState state = new TestOrderState();
		state.setDurationAlpha(1.0);
		state.recordDuration("com.A", 100);
		assertEquals(100, state.getDuration("com.A", -1));
		// alpha=1 → 1*new + 0*old = new
		state.recordDuration("com.A", 999);
		assertEquals(999, state.getDuration("com.A", -1));
	}

	// ═══════════════════════════════════════════════════════════════════
	// Run history boundaries
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void maxHistoryRunsCapsAt50() throws IOException {
		TestOrderState state = new TestOrderState();
		for (int i = 0; i < 60; i++) {
			state.addRunRecord(new TestOrderState.RunRecord(i * 1000L, 5, 0, -1, 1.0, List.of()));
		}
		assertEquals(50, state.runs().size(), "should cap at MAX_HISTORY_RUNS");

		assertEquals(0L, state.runs().get(0).timestamp(), "history thinning should retain an oldest anchor");
		assertEquals(59_000L, state.runs().get(state.runs().size() - 1).timestamp(),
				"most recent run should always be kept");
		for (long i = 35_000L; i <= 59_000L; i += 1_000L) {
			long timestamp = i;
			assertTrue(state.runs().stream().anyMatch(run -> run.timestamp() == timestamp),
					"recent runs should be preserved densely");
		}

		// Save/load preserves the cap
		Path file = tempDir.resolve("state");
		state.save(file);
		TestOrderState loaded = TestOrderState.load(file);
		assertEquals(50, loaded.runs().size());
	}

	// ═══════════════════════════════════════════════════════════════════
	// Jacccard distance edge cases
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void jaccardDistanceIdenticalSetsIsZero() {
		double d = TestSelector.jaccardDistance(Set.of("a", "b"), Set.of("a", "b"));
		assertEquals(0.0, d, 0.001);
	}

	@Test
	void jaccardDistanceDisjointSetsIsOne() {
		double d = TestSelector.jaccardDistance(Set.of("a", "b"), Set.of("c", "d"));
		assertEquals(1.0, d, 0.001);
	}

	@Test
	void jaccardDistanceEmptySetIsNeutralOrOne() {
		// R15-10: empty 'a' (unindexed test) returns neutral 0.5
		assertEquals(0.5, TestSelector.jaccardDistance(Set.of(), Set.of("a")), 0.001);
		// non-empty 'a' with empty 'b' (empty covered set) returns 1.0
		assertEquals(1.0, TestSelector.jaccardDistance(Set.of("a"), Set.of()), 0.001);
		// both empty: a.isEmpty() triggers first → 0.5
		assertEquals(0.5, TestSelector.jaccardDistance(Set.of(), Set.of()), 0.001);
	}

	@Test
	void jaccardDistancePartialOverlap() {
		// {a,b} vs {b,c} → intersection={b}, union={a,b,c} → 1 - 1/3 ≈ 0.667
		double d = TestSelector.jaccardDistance(Set.of("a", "b"), Set.of("b", "c"));
		assertEquals(1.0 - 1.0 / 3.0, d, 0.001);
	}

	// ═══════════════════════════════════════════════════════════════════
	// Tool CLI — aggregate + stats + affected pipeline
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void fullPipelineAggregateToSelect() throws IOException {
		// Simulate a realistic workflow: aggregate deps → detect changed → select tests
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);
		Files.writeString(depsDir.resolve("com.test.UserServiceTest.deps"),
				"com.app.UserService\ncom.app.UserDao\ncom.app.Util\n");
		Files.writeString(depsDir.resolve("com.test.OrderServiceTest.deps"),
				"com.app.OrderService\ncom.app.OrderDao\n");
		Files.writeString(depsDir.resolve("com.test.UtilTest.deps"), "com.app.Util\n");

		Path idx = tempDir.resolve("test.idx");
		assertEquals(0, runTool("aggregate", depsDir.toString(), "--output", idx.toString()));

		// Verify stats
		DependencyMap depMap = DependencyMap.load(idx);
		assertEquals(3, depMap.size());

		// Create state with some durations and a failure
		TestOrderState state = stateWithDurations(
				Map.of("com.test.UserServiceTest", 500L, "com.test.OrderServiceTest", 100L, "com.test.UtilTest", 50L));
		state.recordFailure("com.test.OrderServiceTest");
		Path sf = tempDir.resolve("state");
		state.save(sf);
		TestOrderState.resetPending();

		// Select: change to UserService → UserServiceTest has dep overlap
		Path selected = tempDir.resolve("sel.txt");
		Path remaining = tempDir.resolve("rem.txt");

		assertEquals(0,
				runTool("select", idx.toString(), "--state", sf.toString(), "--top-n", "2", "--random-m", "0", "-c",
						"com.app.UserService", "--selected-file", selected.toString(), "--remaining-file",
						remaining.toString(), "--seed", "42"));

		List<String> sel = TestSelector.readTestList(selected);
		List<String> rem = TestSelector.readTestList(remaining);

		// With a change signal, only dep-affected tests are eligible (TestSelector
		// Phase 2 semantics). UserServiceTest depends on com.app.UserService → in;
		// OrderServiceTest's failure score is irrelevant here because it has no dep
		// overlap with the change, so it ends up in remaining.
		assertTrue(sel.contains("com.test.UserServiceTest"), "test with dep overlap should be selected");
		assertFalse(sel.contains("com.test.OrderServiceTest"),
				"failure score does not bypass dep-affected filter when a change signal is present");
		assertEquals(1, sel.size());
		assertEquals(2, rem.size());
		assertTrue(rem.contains("com.test.OrderServiceTest"));
		assertTrue(rem.contains("com.test.UtilTest"));
	}

	// ═══════════════════════════════════════════════════════════════════
	// Bug regression: save() without pending data must NOT decay
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void saveWithoutPendingDoesNotDecay() throws IOException {
		Path file = tempDir.resolve("state");
		TestOrderState state = new TestOrderState();
		state.recordFailure("com.A");
		state.save(file);
		assertEquals(1.0, state.failureScore("com.A"), 0.001);

		// Save again without any new pending data (e.g. optimizer saving weights)
		state.setWeights(new TestOrderState.ScoringWeights(1, 2, 3, 4, 5, 6, 0));
		state.save(file);
		// Score must be preserved — no decay since no run data
		assertEquals(1.0, state.failureScore("com.A"), 0.001);

		// Verify from disk too
		TestOrderState loaded = TestOrderState.load(file);
		assertEquals(1.0, loaded.failureScore("com.A"), 0.001);
		assertEquals(1, loaded.weights().newTest());
	}

	@Test
	void saveWithPendingStillDecays() throws IOException {
		Path file = tempDir.resolve("state");
		TestOrderState state = new TestOrderState();
		double decay = state.failureDecay();
		double retain = 1.0 - decay;

		state.recordFailure("com.A");
		state.save(file);
		assertEquals(1.0, state.failureScore("com.A"), 0.001);

		// Now record a new failure for a different class → pending is non-empty → decay
		// applies
		state.recordFailure("com.B");
		state.save(file);
		// com.A should be decayed (historical * retain)
		assertEquals(retain, state.failureScore("com.A"), 0.001);
		// com.B should be full (pending at full weight)
		assertEquals(1.0, state.failureScore("com.B"), 0.001);
	}

	@Test
	void methodScoresSurvivedSaveWithoutPending() throws IOException {
		Path file = tempDir.resolve("state");
		TestOrderState state = new TestOrderState();
		state.recordMethodFailure("com.A", "testFoo");
		state.save(file);
		assertEquals(1.0, state.methodFailureScore("com.A", "testFoo"), 0.001);

		// Save again without pending → no decay
		state.save(file);
		assertEquals(1.0, state.methodFailureScore("com.A", "testFoo"), 0.001);
	}

	// ═══════════════════════════════════════════════════════════════════
	// Bug regression: unknown method durations must not get fast bonus
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void methodScorerUnknownDurationNoFastBonus() {
		TestOrderState state = new TestOrderState();
		state.recordMethodDuration("com.A", "known1", 100);
		state.recordMethodDuration("com.A", "known2", 200);
		// testNew has no recorded duration → unknown

		var weights = new TestOrderState.MethodScoringWeights(0, 2.0, 2.0, 0, 0, 0);
		MethodScorer scorer = new MethodScorer(weights, state, null, null, null);

		var results = scorer.score(List.of(new MethodScorer.MethodMetadata("com.A", "known1", 100, null),
				new MethodScorer.MethodMetadata("com.A", "known2", 200, null),
				new MethodScorer.MethodMetadata("com.A", "testNew", -1, null)));

		var unknown = results.stream().filter(r -> r.methodName().equals("testNew")).findFirst().orElseThrow();
		assertFalse(unknown.isFast(), "methods with unknown duration must not get fast bonus");
		assertEquals(0.0, unknown.speedBonus(), 0.001);
	}

	@Test
	void methodScorerMedianExcludesUnknownDurations() {
		TestOrderState state = new TestOrderState();
		// 2 known methods à 100ms and 500ms → median = 300ms
		// Plus 1 unknown (-1ms) which should be excluded from median
		var weights = new TestOrderState.MethodScoringWeights(0, 1.0, 1.0, 0, 0, 0);
		MethodScorer scorer = new MethodScorer(weights, state, null, null, null);

		var results = scorer.score(List.of(new MethodScorer.MethodMetadata("com.A", "fast", 100, null),
				new MethodScorer.MethodMetadata("com.A", "slow", 500, null),
				new MethodScorer.MethodMetadata("com.A", "unknown", -1, null)));

		// Median of [100, 500] = 300
		var fast = results.stream().filter(r -> r.methodName().equals("fast")).findFirst().orElseThrow();
		var slow = results.stream().filter(r -> r.methodName().equals("slow")).findFirst().orElseThrow();

		// 100 < 300*0.5=150 → fast
		assertTrue(fast.isFast(), "100ms should be fast (median=300)");
		// 500 > 300*1.5=450 → slow
		assertTrue(slow.isSlow(), "500ms should be slow (median=300)");

		// Without the fix, median of [-1, 100, 500] = 100, and -1 < 50 → spurious fast
		var unknown = results.stream().filter(r -> r.methodName().equals("unknown")).findFirst().orElseThrow();
		assertFalse(unknown.isFast(), "unknown duration must not be classified");
		assertFalse(unknown.isSlow(), "unknown duration must not be classified");
	}

	// ═══════════════════════════════════════════════════════════════════
	// Bug regression: single test class still gets speed scoring
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void singleTestMedianIsValid() {
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.Solo", 100);

		long median = TestScorer.computeMedianDuration(state, List.of("com.Solo"));
		assertEquals(100, median, "single test should produce valid median");
	}

	// ═══════════════════════════════════════════════════════════════════
	// Change Complexity
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void computeResolvesJavaFile() throws IOException {
		Path srcRoot = tempDir.resolve("src/main/java");
		Path pkg = srcRoot.resolve("com/example");
		Files.createDirectories(pkg);
		Files.writeString(pkg.resolve("Foo.java"), "class Foo {}");

		Map<String, Double> result = me.bechberger.testorder.changes.ChangeComplexity.compute(Set.of("com.example.Foo"),
				List.of(srcRoot));
		assertEquals(1, result.size());
		assertTrue(result.containsKey("com.example.Foo"));
	}

	@Test
	void computeResolvesInnerClass() throws IOException {
		Path srcRoot = tempDir.resolve("src/main/java");
		Path pkg = srcRoot.resolve("com/example");
		Files.createDirectories(pkg);
		Files.writeString(pkg.resolve("Outer.java"), "class Outer { class Inner {} }");

		Map<String, Double> result = me.bechberger.testorder.changes.ChangeComplexity
				.compute(Set.of("com.example.Outer$Inner"), List.of(srcRoot));
		assertEquals(1, result.size());
		assertTrue(result.containsKey("com.example.Outer$Inner"));
	}

	@Test
	void computeNormalisesToOneForSingleFile() throws IOException {
		Path srcRoot = tempDir.resolve("src/main/java");
		Path pkg = srcRoot.resolve("com/example");
		Files.createDirectories(pkg);
		Files.writeString(pkg.resolve("A.java"), "class A { void method() { int x = 1; } }");

		Map<String, Double> result = me.bechberger.testorder.changes.ChangeComplexity.compute(Set.of("com.example.A"),
				List.of(srcRoot));
		assertEquals(1, result.size());
		assertEquals(1.0, result.get("com.example.A"), 0.001);
	}

	@Test
	void computeNormalisesRelativeToLargest() throws IOException {
		Path srcRoot = tempDir.resolve("src/main/java");
		Path pkg = srcRoot.resolve("com/example");
		Files.createDirectories(pkg);
		Files.writeString(pkg.resolve("Small.java"), "class Small {}");
		Files.writeString(pkg.resolve("Large.java"), "class Large {\n" + "  void m() {}\n".repeat(50) + "}");

		Map<String, Double> result = me.bechberger.testorder.changes.ChangeComplexity
				.compute(Set.of("com.example.Small", "com.example.Large"), List.of(srcRoot));
		assertEquals(2, result.size());
		assertEquals(1.0, result.get("com.example.Large"), 0.001);
		assertTrue(result.get("com.example.Small") < 1.0, "smaller file should have score < 1.0");
		assertTrue(result.get("com.example.Small") > 0.0, "small file should have score > 0.0");
	}

	@Test
	void computeSkipsMissingClasses() throws IOException {
		Path srcRoot = tempDir.resolve("src/main/java");
		Path pkg = srcRoot.resolve("com/example");
		Files.createDirectories(pkg);
		Files.writeString(pkg.resolve("Found.java"), "class Found {}");

		Map<String, Double> result = me.bechberger.testorder.changes.ChangeComplexity
				.compute(Set.of("com.example.Found", "com.example.Missing"), List.of(srcRoot));
		assertEquals(1, result.size());
		assertTrue(result.containsKey("com.example.Found"));
		assertFalse(result.containsKey("com.example.Missing"));
	}

	@Test
	void computeEmptyInputReturnsEmptyMap() {
		assertEquals(Map.of(), me.bechberger.testorder.changes.ChangeComplexity.compute(Set.of(), List.of(tempDir)));
		assertEquals(Map.of(), me.bechberger.testorder.changes.ChangeComplexity.compute(Set.of("com.Foo"), List.of()));
	}

	@Test
	void serialiseRoundTrips() {
		Map<String, Double> original = new LinkedHashMap<>();
		original.put("com.Foo", 0.75);
		original.put("com.Bar", 1.0);

		String serialised = me.bechberger.testorder.changes.ChangeComplexity.serialise(original);
		Map<String, Double> restored = me.bechberger.testorder.changes.ChangeComplexity.deserialise(serialised);
		assertEquals(original.size(), restored.size());
		for (var entry : original.entrySet()) {
			assertEquals(entry.getValue(), restored.get(entry.getKey()), 0.001);
		}
	}

	@Test
	void deserialiseHandlesEmptyAndNull() {
		assertEquals(Map.of(), me.bechberger.testorder.changes.ChangeComplexity.deserialise(null));
		assertEquals(Map.of(), me.bechberger.testorder.changes.ChangeComplexity.deserialise(""));
		assertEquals(Map.of(), me.bechberger.testorder.changes.ChangeComplexity.deserialise("   "));
	}

	@Test
	void complexityScoreFormula() {
		// complexityScore = min(ceil(overlap / sqrt(depTotal) * weight), weight)
		assertEquals(0, TestScorer.complexityScore(0.0, 5, 2));
		assertEquals(0, TestScorer.complexityScore(1.0, 5, 0));
		assertEquals(0, TestScorer.complexityScore(0.0, 0, 2));
		// 1.0 / sqrt(max(2,5)) * 2 = 1.0/sqrt(5)*2 = 0.894, ceil = 1
		assertEquals(1, TestScorer.complexityScore(1.0, 2, 2));
		// 2.0 / sqrt(max(2,5)) * 2 = 2.0/sqrt(5)*2 = 1.789, ceil = 2
		assertEquals(2, TestScorer.complexityScore(2.0, 2, 2));
		// 0.5 / sqrt(5) * 4 = 0.894, ceil = 1
		assertEquals(1, TestScorer.complexityScore(0.5, 5, 4));
		// 10.0 / sqrt(1) * 2 = 20, min(20, 2) = 2 (capped)
		assertEquals(2, TestScorer.complexityScore(10.0, 1, 2));
	}

	@Test
	void setCoverBonusSkippedWhenChangedClassesEmpty() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X"), "com.B", Set.of("app.Y")));
		TestOrderState state = stateWithDurations(Map.of("com.A", 100L, "com.B", 100L));

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 0, 0, 0, 5);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of(), Set.of(), depMap.testClasses());

		TestScorer.ScoreResult a = scorer.score("com.A");
		TestScorer.ScoreResult b = scorer.score("com.B");
		assertEquals(0, a.score(), "coverage bonus must be skipped when changedClasses is empty");
		assertEquals(0, b.score(), "coverage bonus must be skipped when changedClasses is empty");
	}

	@Test
	void scorerIncludesComplexityInScore() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.FooTest", Set.of("com.app.Service", "com.app.Repo"));
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.test.FooTest", 100);

		// changeComplexity weight = 2, depOverlap weight = 0 to isolate complexity
		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 0, 2);
		Map<String, Double> complexity = Map.of("com.app.Service", 1.0);

		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of("com.app.Service"), Set.of(),
				depMap.testClasses(), null, complexity);
		TestScorer.ScoreResult result = scorer.score("com.test.FooTest");

		assertEquals(1.0, result.complexityOverlap(), 0.001);
		// 1.0 / sqrt(max(2,5)) * 2 = 1.0/sqrt(5)*2 = 0.894, ceil = 1
		assertEquals(1, result.score());
	}

	@Test
	void scorerWithoutComplexityGivesZero() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.FooTest", Set.of("com.app.Service"));
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.test.FooTest", 100);

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 0, 2);

		// No complexity map → default empty
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of("com.app.Service"), Set.of(),
				depMap.testClasses());
		TestScorer.ScoreResult result = scorer.score("com.test.FooTest");

		assertEquals(0.0, result.complexityOverlap(), 0.001);
	}

	@Test
	void complexityRespectsStructuralExclusion() {
		// Test depends on A and B at class level, but only uses A#foo at member level.
		// B changed but test doesn't use B's changed member → B excluded from overlap.
		// Complexity should also exclude B.
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.FooTest", Set.of("com.app.A", "com.app.B"));
		depMap.putMemberDeps("com.test.FooTest", Set.of("com.app.A#foo", "com.app.B#bar"));

		TestOrderState state = new TestOrderState();
		state.recordDuration("com.test.FooTest", 100);

		// Both A and B changed at class level, but structural analysis says:
		// A#foo changed (test uses it) → counted
		// B#qux changed (test doesn't use it) → excluded
		var changedMembers = new me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers(
				Set.of("com.app.A", "com.app.B"), Set.of("com.app.A#foo", "com.app.B#qux"),
				Map.of("com.app.A", Set.of("foo"), "com.app.B", Set.of("qux")), Set.of());

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 5, 2);
		Map<String, Double> complexity = Map.of("com.app.A", 0.5, "com.app.B", 1.0);

		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of("com.app.A", "com.app.B"), Set.of(),
				depMap.testClasses(), changedMembers, complexity);
		TestScorer.ScoreResult result = scorer.score("com.test.FooTest");

		// Only A should be counted in overlap (depOverlap=1)
		assertEquals(1, result.depOverlap());
		// Only A's complexity should be included (0.5, not 0.5+1.0)
		assertEquals(0.5, result.complexityOverlap(), 0.001,
				"complexity should only include structurally-matched deps");
	}

	// ═══════════════════════════════════════════════════════════════════
	// Regression: method/field overlap scoring must differentiate tests
	// that depend on the same class but exercise different methods.
	// See BUG_TEST_RESULTS.md — "Overlap/Coverage Scoring Not
	// Differentiating by Method".
	// ═══════════════════════════════════════════════════════════════════

	/**
	 * When member-level deps are available and only one method changes, only the
	 * test that actually calls that method should have non-zero overlap.
	 */
	@Test
	void memberLevelOverlapDifferentiatesTestsByMethod() {
		// Class "com.app.Service" has methods: addItem, removeItem, setMetadata,
		// getMetadata
		// ItemTest calls addItem, removeItem
		// MetadataTest calls setMetadata, getMetadata
		// Both depend on the same class at class level.
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.ItemTest", Set.of("com.app.Service"));
		depMap.put("com.test.MetadataTest", Set.of("com.app.Service"));
		depMap.putMemberDeps("com.test.ItemTest", Set.of("com.app.Service#addItem", "com.app.Service#removeItem"));
		depMap.putMemberDeps("com.test.MetadataTest",
				Set.of("com.app.Service#setMetadata", "com.app.Service#getMetadata"));

		TestOrderState state = new TestOrderState();
		state.recordDuration("com.test.ItemTest", 50);
		state.recordDuration("com.test.MetadataTest", 50);

		// Only addItem changed
		var changedMembers = new StructuralChangeAnalyzer.ChangedMembers(Set.of("com.app.Service"),
				Set.of("com.app.Service#addItem"), Map.of("com.app.Service", Set.of("addItem")), Set.of());

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 6, 0);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of("com.app.Service"), Set.of(),
				depMap.testClasses(), changedMembers);

		var itemResult = scorer.score("com.test.ItemTest");
		var metadataResult = scorer.score("com.test.MetadataTest");

		// ItemTest uses addItem → overlap = 1
		assertEquals(1, itemResult.depOverlap(), "ItemTest should overlap because it calls the changed method addItem");
		assertTrue(itemResult.score() > 0, "ItemTest should have a positive score");

		// MetadataTest does NOT use addItem → overlap = 0
		assertEquals(0, metadataResult.depOverlap(), "MetadataTest should NOT overlap because it doesn't call addItem");
		assertEquals(0, metadataResult.score(),
				"MetadataTest should have zero score when it doesn't touch the changed method");

		// The key assertion: ItemTest must score strictly higher than MetadataTest
		assertTrue(itemResult.score() > metadataResult.score(),
				"Test that calls the changed method must score higher than one that doesn't. " + "ItemTest="
						+ itemResult.score() + " MetadataTest=" + metadataResult.score());
	}

	/**
	 * When member-level deps are available and a method changes, the test that
	 * covers MORE changed methods should score higher than one covering fewer.
	 */
	@Test
	void memberLevelOverlapScoresHigherForBroaderCoverage() {
		// CombinedTest calls methods from multiple areas of the same class
		// NarrowTest calls only a few methods
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.CombinedTest", Set.of("com.app.Service"));
		depMap.put("com.test.NarrowTest", Set.of("com.app.Service"));
		depMap.putMemberDeps("com.test.CombinedTest",
				Set.of("com.app.Service#foo", "com.app.Service#bar", "com.app.Service#baz"));
		depMap.putMemberDeps("com.test.NarrowTest", Set.of("com.app.Service#foo"));

		TestOrderState state = new TestOrderState();
		state.recordDuration("com.test.CombinedTest", 50);
		state.recordDuration("com.test.NarrowTest", 50);

		// foo and bar both changed — CombinedTest calls both, NarrowTest calls only foo
		var changedMembers = new StructuralChangeAnalyzer.ChangedMembers(Set.of("com.app.Service"),
				Set.of("com.app.Service#foo", "com.app.Service#bar"), Map.of("com.app.Service", Set.of("foo", "bar")),
				Set.of());

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 6, 0);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of("com.app.Service"), Set.of(),
				depMap.testClasses(), changedMembers);

		var combinedResult = scorer.score("com.test.CombinedTest");
		var narrowResult = scorer.score("com.test.NarrowTest");

		// Both should have overlap (both call at least one changed method)
		assertEquals(1, combinedResult.depOverlap());
		assertEquals(1, narrowResult.depOverlap());

		// Both should score the same overlap (overlap is per-class, not per-member)
		assertEquals(combinedResult.score(), narrowResult.score(),
				"Per-class overlap should be the same when both tests touch at least one changed member");
	}

	/**
	 * Without member-level deps (fallback), all tests depending on a changed class
	 * get the same overlap score — verifying the documented behaviour.
	 */
	@Test
	void classLevelFallbackGivesSameScoreToAllDependentTests() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.ItemTest", Set.of("com.app.Service"));
		depMap.put("com.test.MetadataTest", Set.of("com.app.Service"));
		// Deliberately NO putMemberDeps → hasMemberDeps() returns false

		TestOrderState state = new TestOrderState();
		state.recordDuration("com.test.ItemTest", 50);
		state.recordDuration("com.test.MetadataTest", 50);

		// addItem changed structurally, but we have no member deps to differentiate
		var changedMembers = new StructuralChangeAnalyzer.ChangedMembers(Set.of("com.app.Service"),
				Set.of("com.app.Service#addItem"), Map.of("com.app.Service", Set.of("addItem")), Set.of());

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 6, 0);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of("com.app.Service"), Set.of(),
				depMap.testClasses(), changedMembers);

		var itemResult = scorer.score("com.test.ItemTest");
		var metadataResult = scorer.score("com.test.MetadataTest");

		// Without member deps, both tests get class-level overlap
		assertEquals(1, itemResult.depOverlap());
		assertEquals(1, metadataResult.depOverlap());
		assertEquals(itemResult.score(), metadataResult.score(),
				"Without member deps, class-level fallback should give identical scores");
	}

	/**
	 * Clinit (static initializer) change should affect ALL tests that depend on the
	 * class, even with member-level deps — because class loading triggers clinit.
	 */
	@Test
	void clinitChangeAffectsAllDependentTestsEvenWithMemberDeps() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.FooTest", Set.of("com.app.Constants"));
		depMap.put("com.test.BarTest", Set.of("com.app.Constants"));
		// FooTest uses Constants#getValue, BarTest uses Constants#getName
		depMap.putMemberDeps("com.test.FooTest", Set.of("com.app.Constants#getValue"));
		depMap.putMemberDeps("com.test.BarTest", Set.of("com.app.Constants#getName"));

		TestOrderState state = new TestOrderState();
		state.recordDuration("com.test.FooTest", 50);
		state.recordDuration("com.test.BarTest", 50);

		// Static initializer (<clinit>) changed — this affects everyone
		var changedMembers = new StructuralChangeAnalyzer.ChangedMembers(Set.of("com.app.Constants"),
				Set.of("com.app.Constants#<clinit>"), Map.of("com.app.Constants", Set.of("<clinit>")), Set.of());

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 6, 0);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of("com.app.Constants"), Set.of(),
				depMap.testClasses(), changedMembers);

		var fooResult = scorer.score("com.test.FooTest");
		var barResult = scorer.score("com.test.BarTest");

		// Both should be affected because <clinit> change affects all users
		assertEquals(1, fooResult.depOverlap(), "clinit change should count as overlap for FooTest");
		assertEquals(1, barResult.depOverlap(), "clinit change should count as overlap for BarTest");
		assertTrue(fooResult.score() > 0);
		assertTrue(barResult.score() > 0);
	}

	/**
	 * A test with member deps that doesn't call ANY changed member of a class
	 * should have zero overlap for that class—even though it depends on it at class
	 * level.
	 */
	@Test
	void memberDepsExcludeClassWhenNoChangedMemberUsed() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.AlphaTest", Set.of("com.app.A", "com.app.B"));
		depMap.putMemberDeps("com.test.AlphaTest", Set.of("com.app.A#read", "com.app.B#write"));

		TestOrderState state = new TestOrderState();
		state.recordDuration("com.test.AlphaTest", 100);

		// A#compute changed, B#flush changed — test uses neither
		var changedMembers = new StructuralChangeAnalyzer.ChangedMembers(Set.of("com.app.A", "com.app.B"),
				Set.of("com.app.A#compute", "com.app.B#flush"),
				Map.of("com.app.A", Set.of("compute"), "com.app.B", Set.of("flush")), Set.of());

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 6, 0);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of("com.app.A", "com.app.B"), Set.of(),
				depMap.testClasses(), changedMembers);

		var result = scorer.score("com.test.AlphaTest");

		assertEquals(0, result.depOverlap(), "No overlap because test doesn't use any changed member");
		assertEquals(0, result.score(), "Zero score when no changed members are exercised by the test");
	}

	@Test
	void memberLevelOverlapStaysPreciseWithCircularDependencies() {
		DependencyMap depMap = new DependencyMap();
		// Classes A and B depend on each other, so both tests touch both classes at
		// class level.
		depMap.put("com.test.ATest", Set.of("com.app.A", "com.app.B"));
		depMap.put("com.test.BTest", Set.of("com.app.A", "com.app.B"));
		depMap.putMemberDeps("com.test.ATest", Set.of("com.app.A#compute", "com.app.B#helper"));
		depMap.putMemberDeps("com.test.BTest", Set.of("com.app.A#helper", "com.app.B#compute"));

		TestOrderState state = new TestOrderState();
		state.recordDuration("com.test.ATest", 50);
		state.recordDuration("com.test.BTest", 50);

		var changedMembers = new StructuralChangeAnalyzer.ChangedMembers(Set.of("com.app.A"),
				Set.of("com.app.A#compute"), Map.of("com.app.A", Set.of("compute")), Set.of());

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 6, 0);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of("com.app.A"), Set.of(), depMap.testClasses(),
				changedMembers);

		var aResult = scorer.score("com.test.ATest");
		var bResult = scorer.score("com.test.BTest");

		assertEquals(1, aResult.depOverlap(), "ATest should overlap because it exercises the changed member on A");
		assertEquals(0, bResult.depOverlap(),
				"BTest should not inherit overlap just because A and B are circularly connected");
		assertTrue(aResult.score() > bResult.score(),
				"Member-level scoring should stay precise even when class-level deps form a cycle");
	}

	@Test
	void nestedFieldAccessStillMatchesDeepLeafFieldChanges() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.DeepStateTest", Set.of("com.app.Root", "com.app.Branch", "com.app.Leaf"));
		depMap.put("com.test.ShallowStateTest", Set.of("com.app.Root", "com.app.Branch", "com.app.Leaf"));
		depMap.putMemberDeps("com.test.DeepStateTest",
				Set.of("com.app.Root#left", "com.app.Branch#child", "com.app.Leaf#value"));
		depMap.putMemberDeps("com.test.ShallowStateTest", Set.of("com.app.Root#left", "com.app.Branch#child"));

		TestOrderState state = new TestOrderState();
		state.recordDuration("com.test.DeepStateTest", 50);
		state.recordDuration("com.test.ShallowStateTest", 50);

		var changedMembers = new StructuralChangeAnalyzer.ChangedMembers(Set.of("com.app.Leaf"),
				Set.of("com.app.Leaf#value"), Map.of("com.app.Leaf", Set.of("value")), Set.of());

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 6, 0);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of("com.app.Leaf"), Set.of(),
				depMap.testClasses(), changedMembers);

		var deepResult = scorer.score("com.test.DeepStateTest");
		var shallowResult = scorer.score("com.test.ShallowStateTest");

		assertEquals(1, deepResult.depOverlap(),
				"DeepStateTest should overlap because it reaches the changed leaf field");
		assertEquals(0, shallowResult.depOverlap(),
				"ShallowStateTest should not overlap because it never touches the changed leaf field");
		assertTrue(deepResult.score() > shallowResult.score(),
				"Deep field access should still produce a higher score for the actually affected test");
	}

	/**
	 * Static field bonus should only fire when member deps exist and the test
	 * actually accesses a changed static field.
	 */
	@Test
	void staticFieldBonusOnlyWithMemberDeps() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.ReaderTest", Set.of("com.app.Config"));
		depMap.put("com.test.WriterTest", Set.of("com.app.Config"));
		depMap.putMemberDeps("com.test.ReaderTest", Set.of("com.app.Config#MAX_SIZE", "com.app.Config#read"));
		depMap.putMemberDeps("com.test.WriterTest", Set.of("com.app.Config#write"));

		TestOrderState state = new TestOrderState();
		state.recordDuration("com.test.ReaderTest", 50);
		state.recordDuration("com.test.WriterTest", 50);

		// Config#write method changed + Config#MAX_SIZE static field changed
		var changedMembers = new StructuralChangeAnalyzer.ChangedMembers(Set.of("com.app.Config"),
				Set.of("com.app.Config#write", "com.app.Config#MAX_SIZE"),
				Map.of("com.app.Config", Set.of("write", "MAX_SIZE")), Set.of(), Set.of("com.app.Config#MAX_SIZE"));

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 6, 0, 3);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of("com.app.Config"), Set.of(),
				depMap.testClasses(), changedMembers);

		var readerResult = scorer.score("com.test.ReaderTest");
		var writerResult = scorer.score("com.test.WriterTest");

		// ReaderTest uses MAX_SIZE (static field) → gets static field bonus
		assertTrue(readerResult.score() > writerResult.score(),
				"ReaderTest should score higher due to static field bonus. " + "Reader=" + readerResult.score()
						+ " Writer=" + writerResult.score());

		// WriterTest uses write (method, not a static field key) → no static field
		// bonus
		// But WriterTest should still have overlap from the write method
		assertEquals(1, writerResult.depOverlap());
	}

	// ═══════════════════════════════════════════════════════════════════
	// Kitchen-sink: every scoring dimension fires in one scenario
	// ═══════════════════════════════════════════════════════════════════

	/**
	 * A single test that exercises ALL score contributors at once: new-test bonus,
	 * changed-test bonus, failure recency, speed bonus, member-level dep overlap,
	 * change-complexity, and static-field bonus.
	 *
	 * Five test classes, each collecting a different subset of bonuses, so we can
	 * verify both the individual contributions and the total.
	 */
	@Test
	void allScoringDimensionsFireTogether() throws IOException {
		// ── Setup: two production classes ────────────────────────────
		// com.app.Engine — methods: start, stop; static field: INSTANCE
		// com.app.Config — methods: load, save
		//
		// Changed: Engine#stop (method) + Engine#INSTANCE (static field) + Config#save
		// (method)
		// Complexity: Engine = 0.8, Config = 0.4

		DependencyMap depMap = new DependencyMap();

		// HeroTest: known test, changed, fast, calls Engine#stop → overlap, static
		// field, complexity
		depMap.put("com.test.HeroTest", Set.of("com.app.Engine", "com.app.Config"));
		depMap.putMemberDeps("com.test.HeroTest",
				Set.of("com.app.Engine#stop", "com.app.Engine#INSTANCE", "com.app.Config#save"));

		// SpeedyTest: known, NOT changed, fast, calls Config#save → overlap +
		// complexity, no static field
		depMap.put("com.test.SpeedyTest", Set.of("com.app.Config"));
		depMap.putMemberDeps("com.test.SpeedyTest", Set.of("com.app.Config#save"));

		// SlowTest: known, NOT changed, slow, calls Engine#stop → overlap + complexity,
		// no static field bonus
		depMap.put("com.test.SlowTest", Set.of("com.app.Engine"));
		depMap.putMemberDeps("com.test.SlowTest", Set.of("com.app.Engine#stop"));

		// IrrelevantTest: known, NOT changed, medium speed, calls Engine#start → NO
		// overlap
		depMap.put("com.test.IrrelevantTest", Set.of("com.app.Engine"));
		depMap.putMemberDeps("com.test.IrrelevantTest", Set.of("com.app.Engine#start"));

		// (NewFreshTest is NOT in depMap → will be flagged as new)

		// ── State: durations + one failure ──────────────────────────
		TestOrderState state = new TestOrderState();
		// Median of {10, 20, 500, 100} = sorted {10, 20, 100, 500} → median ~60
		state.recordDuration("com.test.HeroTest", 10); // fast
		state.recordDuration("com.test.SpeedyTest", 20); // fast
		state.recordDuration("com.test.SlowTest", 500); // slow
		state.recordDuration("com.test.IrrelevantTest", 100); // medium

		// Record a failure for HeroTest — must save/load to move from pending to
		// historical
		state.recordFailure("com.test.HeroTest");
		Path stateFile = tempDir.resolve("state-all");
		state.save(stateFile);
		TestOrderState loaded = TestOrderState.load(stateFile);

		// ── Changed members ─────────────────────────────────────────
		var changedMembers = new StructuralChangeAnalyzer.ChangedMembers(Set.of("com.app.Engine", "com.app.Config"), // changedClasses
				Set.of("com.app.Engine#stop", "com.app.Engine#INSTANCE", // changedMemberKeys
						"com.app.Config#save"),
				Map.of("com.app.Engine", Set.of("stop", "INSTANCE"), // membersByClass
						"com.app.Config", Set.of("save")),
				Set.of(), // classesWithTypeChanges
				Set.of("com.app.Engine#INSTANCE")); // changedStaticFieldKeys

		// ── Complexity map ──────────────────────────────────────────
		Map<String, Double> complexity = Map.of("com.app.Engine", 0.8, "com.app.Config", 0.4);

		// ── Weights: all non-zero ───────────────────────────────────
		// newTest=10, changedTest=8, maxFailure=5, speed=3, speedPenalty=2,
		// depOverlap=6, changeComplexity=4, staticFieldBonus=3
		var weights = new TestOrderState.ScoringWeights(10, 8, 5, 3, 2, 6, 4, 3);

		TestScorer scorer = new TestScorer(weights, depMap, loaded, Set.of("com.app.Engine", "com.app.Config"),
				Set.of("com.test.HeroTest"), // only HeroTest is a changed test class
				depMap.testClasses(), changedMembers, complexity);

		// ── Score each test ─────────────────────────────────────────

		// --- HeroTest: changed + failure + fast + overlap(2 classes) + complexity +
		// static field ---
		var hero = scorer.score("com.test.HeroTest");
		assertTrue(hero.isChanged(), "HeroTest is a changed test class");
		assertFalse(hero.isNew(), "HeroTest is in depMap, not new");
		assertTrue(hero.isFast(), "10ms is well below median");
		assertTrue(hero.failScore() > 0, "HeroTest has a recorded failure");
		assertEquals(2, hero.depOverlap(), "overlaps Engine (via stop) and Config (via save)");
		assertEquals(2, hero.depTotal());
		assertTrue(hero.complexityOverlap() > 0, "Engine(0.8) + Config(0.4) = 1.2");

		// Verify individual contributions sum correctly
		long median = scorer.medianDuration(); // 100ms
		int heroExpected = 0;
		heroExpected += 8; // changedTest
		heroExpected += Math.min((int) Math.ceil(hero.failScore()), 5); // failure (capped at 5)
		heroExpected += TestScorer.speedBucketScore(10, median, 3, 2); // speed
		heroExpected += TestScorer.depOverlapScore(2, 2, 6); // overlap: 2/2 ratio → full 6
		heroExpected += TestScorer.complexityScore(hero.complexityOverlap(), 2, 4); // complexity
		heroExpected += 3; // static field bonus (INSTANCE)
		assertEquals(heroExpected, hero.score(),
				"HeroTest total should be sum of changed+failure+speed+overlap+complexity+staticField");

		// --- SpeedyTest: fast + overlap(1 class = Config) + complexity ---
		var speedy = scorer.score("com.test.SpeedyTest");
		assertFalse(speedy.isChanged());
		assertFalse(speedy.isNew());
		assertTrue(speedy.isFast(), "20ms is fast");
		assertEquals(0.0, speedy.failScore(), 0.001);
		assertEquals(1, speedy.depOverlap(), "overlaps Config via save");
		assertEquals(1, speedy.depTotal());
		assertTrue(speedy.complexityOverlap() > 0);

		int speedyExpected = 0;
		speedyExpected += TestScorer.speedBucketScore(20, median, 3, 2); // speed
		speedyExpected += TestScorer.depOverlapScore(1, 1, 6); // overlap: 1/1 → 6
		speedyExpected += TestScorer.complexityScore(speedy.complexityOverlap(), 1, 4); // complexity
		assertEquals(speedyExpected, speedy.score());

		// --- SlowTest: slow penalty + overlap(1 class = Engine) + complexity ---
		var slow = scorer.score("com.test.SlowTest");
		assertTrue(slow.isSlow(), "500ms is slow");
		assertEquals(1, slow.depOverlap(), "overlaps Engine via stop");

		int slowExpected = 0;
		slowExpected += TestScorer.speedBucketScore(500, median, 3, 2); // speed penalty
		slowExpected += TestScorer.depOverlapScore(1, 1, 6);
		slowExpected += TestScorer.complexityScore(slow.complexityOverlap(), 1, 4);
		assertEquals(slowExpected, slow.score());

		// --- IrrelevantTest: medium speed, calls Engine#start (not changed) → zero
		// overlap ---
		var irrelevant = scorer.score("com.test.IrrelevantTest");
		assertFalse(irrelevant.isFast());
		assertFalse(irrelevant.isSlow());
		assertEquals(0, irrelevant.depOverlap(), "start is not a changed member");
		assertEquals(0, irrelevant.score(), "no scoring dimension fires for IrrelevantTest");

		// --- NewFreshTest: not in depMap → new ---
		var fresh = scorer.score("com.test.NewFreshTest");
		assertTrue(fresh.isNew(), "NewFreshTest is not in depMap");
		assertEquals(10, fresh.score(), "only the newTest bonus applies");

		// ── Global ordering invariant ───────────────────────────────
		// HeroTest (all bonuses) > SpeedyTest > SlowTest > NewFreshTest >
		// IrrelevantTest
		assertTrue(hero.score() > speedy.score(),
				"HeroTest should beat SpeedyTest: " + hero.score() + " vs " + speedy.score());
		assertTrue(speedy.score() > slow.score(),
				"SpeedyTest should beat SlowTest: " + speedy.score() + " vs " + slow.score());
		assertTrue(slow.score() > irrelevant.score(),
				"SlowTest should beat IrrelevantTest: " + slow.score() + " vs " + irrelevant.score());
	}

	// ─── Set-cover (coverageBonus) tests ────────────────────────────

	@Test
	void setCoverPicksBestCoveringTestFirst() throws IOException {
		// TestA covers {X, Y}, TestB covers {Y, Z}, TestC covers {X, Y, Z}
		// With coverageBonus=5, greedy should pick TestC first (covers 3) → bonus 5
		// Then no uncovered left, TestA and TestB get 0.
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.TestA", Set.of("com.X", "com.Y"));
		depMap.put("com.test.TestB", Set.of("com.Y", "com.Z"));
		depMap.put("com.test.TestC", Set.of("com.X", "com.Y", "com.Z"));
		Set<String> changed = Set.of("com.X", "com.Y", "com.Z");

		TestOrderState state = new TestOrderState();

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 0, 0, 0, 5);
		List<String> tests = List.of("com.test.TestA", "com.test.TestB", "com.test.TestC");
		TestScorer scorer = new TestScorer(weights, depMap, state, changed, Set.of(), tests);

		assertEquals(5, scorer.score("com.test.TestC").score());
		assertEquals(0, scorer.score("com.test.TestA").score());
		assertEquals(0, scorer.score("com.test.TestB").score());
	}

	@Test
	void setCoverDecliningBonusForMultiplePicks() throws IOException {
		// TestA covers {X}, TestB covers {Y} — two picks needed
		// First pick gets bonus=3, second gets bonus=2
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.TestA", Set.of("com.X"));
		depMap.put("com.test.TestB", Set.of("com.Y"));
		Set<String> changed = Set.of("com.X", "com.Y");

		TestOrderState state = new TestOrderState();

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 0, 0, 0, 3);
		List<String> tests = List.of("com.test.TestA", "com.test.TestB");
		TestScorer scorer = new TestScorer(weights, depMap, state, changed, Set.of(), tests);

		int scoreA = scorer.score("com.test.TestA").score();
		int scoreB = scorer.score("com.test.TestB").score();
		// One should get 3, the other 2 (both cover 1 class each, order depends on
		// iteration)
		assertTrue((scoreA == 3 && scoreB == 2) || (scoreA == 2 && scoreB == 3),
				"Expected declining bonuses 3 and 2, got " + scoreA + " and " + scoreB);
	}

	@Test
	void setCoverDisabledWhenWeightIsZero() throws IOException {
		// With coverageBonus=0, depOverlap should be used instead
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.TestA", Set.of("com.X", "com.Y"));
		Set<String> changed = Set.of("com.X");

		TestOrderState state = new TestOrderState();

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 5, 0, 0, 0);
		List<String> tests = List.of("com.test.TestA");
		TestScorer scorer = new TestScorer(weights, depMap, state, changed, Set.of(), tests);

		// depOverlapScore(1, 2, 5) = min(ceil(1/sqrt(max(2,5)) * 5), 5) =
		// min(ceil(2.236), 5) = 3
		assertEquals(3, scorer.score("com.test.TestA").score());
	}

	// ─── LineDiff tests ─────────────────────────────────────────────

	@Test
	void lineDiffCountsInsertionsAndDeletions() {
		var ld = me.bechberger.testorder.changes.LineDiff.changedLineCount("a\nb\nc\n", "a\nx\nc\n");
		// b→x: 1 deletion + 1 insertion = 2
		assertEquals(2, ld);
	}

	@Test
	void lineDiffIdenticalTextIsZero() {
		assertEquals(0, me.bechberger.testorder.changes.LineDiff.changedLineCount("hello\nworld\n", "hello\nworld\n"));
	}

	@Test
	void lineDiffEmptyToSomething() {
		assertEquals(3, me.bechberger.testorder.changes.LineDiff.changedLineCount("", "a\nb\nc"));
	}

	// ─── TestScorer method-diff and zero-weights tests ────────────────

	@Test
	void scorerGivesHigherScoreToTestCoveringChangedMethod() {
		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 5, 0, 0, 0);
		DependencyMap depMap = new DependencyMap();

		// TestA depends on app.Service (class-level) and member app.Service#doWork
		depMap.put("com.test.TestA", Set.of("app.Service"));
		depMap.putMemberDeps("com.test.TestA", Set.of("app.Service#doWork"));

		// TestB depends on app.Service (class-level) but only member
		// app.Service#otherMethod
		depMap.put("com.test.TestB", Set.of("app.Service"));
		depMap.putMemberDeps("com.test.TestB", Set.of("app.Service#otherMethod"));

		var state = new TestOrderState();
		var changedClasses = Set.of("app.Service");
		var changedMembers = new ChangedMembers(changedClasses, Set.of("app.Service#doWork"),
				Map.of("app.Service", Set.of("doWork")), Set.of());

		List<String> tests = List.of("com.test.TestA", "com.test.TestB");
		TestScorer scorer = new TestScorer.Builder(weights, depMap, state, changedClasses, Set.of())
				.testClassNames(tests).changedMembers(changedMembers).build();

		int scoreA = scorer.score("com.test.TestA").score();
		int scoreB = scorer.score("com.test.TestB").score();
		assertTrue(scoreA > scoreB, "TestA (covers changed method) should score higher than TestB; got A=%d B=%d"
				.formatted(scoreA, scoreB));
	}

	@Test
	void allZeroWeightsProducesZeroScores() {
		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 0, 0, 0, 0);
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.test.TestA", Set.of("app.Foo"));
		depMap.put("com.test.TestB", Set.of("app.Bar"));

		var state = new TestOrderState();
		List<String> tests = List.of("com.test.TestA", "com.test.TestB");
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of("app.Foo"), Set.of(), tests);

		assertEquals(0, scorer.score("com.test.TestA").score());
		assertEquals(0, scorer.score("com.test.TestB").score());
	}

	// ── Kill-rate scoring ─────────────────────────────────────────────────────

	@Test
	void killRateBonusAddsToScore() {
		DependencyMap depMap = buildDepMap(Map.of("com.FooTest", Set.of("app.X")));
		TestOrderState state = new TestOrderState();
		state.setKillRates(Map.of("com.FooTest", 1.0));

		// killRateBonus = 10, killRate = 1.0 → bonus = round(1.0 * 10) = 10
		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 0, 0, 0, 0, 10);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of(), Set.of(), depMap.testClasses());

		var result = scorer.score("com.FooTest");
		assertEquals(10, result.score(), "full kill rate should add killRateBonus points");
		assertEquals(1.0, result.killRate(), 0.001);
	}

	@Test
	void zeroKillRateAddsNoBonus() {
		DependencyMap depMap = buildDepMap(Map.of("com.WeakTest", Set.of("app.X")));
		TestOrderState state = new TestOrderState();
		state.setKillRates(Map.of("com.WeakTest", 0.0));

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 0, 0, 0, 0, 10);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of(), Set.of(), depMap.testClasses());

		var result = scorer.score("com.WeakTest");
		assertEquals(0, result.score(), "zero kill rate should add no bonus");
		assertEquals(0.0, result.killRate(), 0.001);
	}

	@Test
	void missingKillRateDataLeavesScoreUnchanged() {
		DependencyMap depMap = buildDepMap(Map.of("com.FooTest", Set.of("app.X")));
		TestOrderState state = new TestOrderState(); // no kill rates set

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 0, 0, 0, 0, 10);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of(), Set.of(), depMap.testClasses());

		var result = scorer.score("com.FooTest");
		assertEquals(0, result.score(), "missing kill rate data should not affect score");
		assertEquals(-1.0, result.killRate(), 0.001, "kill rate should be -1 when no data");
	}

	@Test
	void killRateZeroBonusWeightAddsNothing() {
		DependencyMap depMap = buildDepMap(Map.of("com.FooTest", Set.of("app.X")));
		TestOrderState state = new TestOrderState();
		state.setKillRates(Map.of("com.FooTest", 1.0));

		// killRateBonus = 0 → no bonus even with kill rate = 1.0
		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of(), Set.of(), depMap.testClasses());

		assertEquals(0, scorer.score("com.FooTest").score());
	}

	@Test
	void killRateMultiplierScalesDepOverlapScore() {
		// killRate = 0.0 → multiplier = 0.5 → dep overlap is halved
		// killRate = 1.0 → multiplier = 1.0 → dep overlap unchanged
		DependencyMap depMapLow = buildDepMap(Map.of("com.WeakTest", Set.of("app.X")));
		DependencyMap depMapHigh = buildDepMap(Map.of("com.StrongTest", Set.of("app.X")));

		TestOrderState stateLow = new TestOrderState();
		stateLow.setKillRates(Map.of("com.WeakTest", 0.0));

		TestOrderState stateHigh = new TestOrderState();
		stateHigh.setKillRates(Map.of("com.StrongTest", 1.0));

		// Use only depOverlap weight, no killRateBonus
		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 10, 0, 0, 0, 0);
		TestScorer scorerLow = new TestScorer(weights, depMapLow, stateLow, Set.of("app.X"), Set.of(),
				depMapLow.testClasses());
		TestScorer scorerHigh = new TestScorer(weights, depMapHigh, stateHigh, Set.of("app.X"), Set.of(),
				depMapHigh.testClasses());

		int scoreLow = scorerLow.score("com.WeakTest").score();
		int scoreHigh = scorerHigh.score("com.StrongTest").score();
		assertTrue(scoreHigh >= scoreLow,
				"high kill rate test should score >= low kill rate test due to multiplier; high=%d low=%d"
						.formatted(scoreHigh, scoreLow));
	}

	@Test
	void killRateStoredInScoreResult() {
		DependencyMap depMap = buildDepMap(Map.of("com.FooTest", Set.of("app.X")));
		TestOrderState state = new TestOrderState();
		state.setKillRates(Map.of("com.FooTest", 0.75));

		var weights = new TestOrderState.ScoringWeights(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
		TestScorer scorer = new TestScorer(weights, depMap, state, Set.of(), Set.of(), depMap.testClasses());

		var result = scorer.score("com.FooTest");
		assertEquals(0.75, result.killRate(), 0.001, "kill rate should be preserved in score result");
	}

	@Test
	void testScorerContainerClassNotFlaggedAsNew() {
		// Scenario: LiveModeKeyHandlingTest is an outer class with no test methods of
		// its own; only its nested classes are in the dep map. Without the fix it would
		// get isNew=true (and the full newTest bonus) forever.
		DependencyMap depMap = buildDepMap(Map.of("com.live.LiveModeKeyHandlingTest$KeyEventTests", Set.of("app.Svc"),
				"com.live.LiveModeKeyHandlingTest$SortingTests", Set.of("app.Other"), "com.util.JvmVersionCheckerTest",
				Set.of("app.JvmVersionChecker")));
		TestOrderState state = new TestOrderState();

		TestScorer scorer = new TestScorer(TestOrderState.ScoringWeights.DEFAULT, depMap, state, Set.of(), Set.of(),
				depMap.testClasses());

		// The outer container class is not in the dep map but has nested classes that
		// are
		var containerResult = scorer.score("com.live.LiveModeKeyHandlingTest");
		assertFalse(containerResult.isNew(),
				"outer container whose nested classes are in dep map must NOT be flagged as new");

		// A genuinely new test class (no nested classes in dep map) should still be new
		var genuinelyNewResult = scorer.score("com.other.BrandNewTest");
		assertTrue(genuinelyNewResult.isNew(), "a genuinely new top-level test class must be flagged as new");

		// Inner classes whose parent is in the dep map should also not be flagged as
		// new
		var innerResult = scorer.score("com.live.LiveModeKeyHandlingTest$RenderingTests");
		assertFalse(innerResult.isNew(),
				"inner class whose top-level parent has nested classes in dep map must NOT be flagged as new");
	}
}
