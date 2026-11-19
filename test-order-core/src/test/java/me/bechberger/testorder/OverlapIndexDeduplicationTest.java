package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangeKind;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;
import me.bechberger.testorder.ops.AffectedOperation;
import me.bechberger.testorder.ops.PluginLog;

/**
 * Evidence test for the shared per-selection overlap join
 * ({@link CoverageClosure.OverlapIndex}): in one select() run the join
 * (StructuralChangeAnalyzer.computeOverlapClasses per affected test) used to
 * run TWICE — once in TestScorer's set-cover pipeline, once in
 * CoverageClosure.compute. With the shared index it runs exactly once per
 * distinct candidate test (~2x → 1x), with identical outputs.
 */
class OverlapIndexDeduplicationTest {

	private static final int TESTS = 2000;
	private static final int CHANGED = 200;

	@TempDir
	java.nio.file.Path tempDir;

	/**
	 * 2000 tests × ~20 deps over 300 production classes, 200 of them changed; every
	 * test depends on at least one changed class, so both pipelines' candidate sets
	 * cover all 2000 tests.
	 */
	private static DependencyMap syntheticDepMap() {
		DependencyMap depMap = new DependencyMap();
		for (int i = 0; i < TESTS; i++) {
			Set<String> deps = new LinkedHashSet<>();
			deps.add("com.app.C" + (i % CHANGED)); // guaranteed changed-class overlap
			for (int k = 1; k < 20; k++) {
				deps.add("com.app.C" + ((i * 7 + k * 13) % 300));
			}
			depMap.put("com.example.T" + i + "Test", deps);
		}
		return depMap;
	}

	private static Set<String> changedClasses() {
		Set<String> changed = new LinkedHashSet<>();
		for (int c = 0; c < CHANGED; c++) {
			changed.add("com.app.C" + c);
		}
		return changed;
	}

	/**
	 * coverageBonus > 0 so TestScorer's set-cover pipeline (the join) actually
	 * runs.
	 */
	private static TestOrderState.ScoringWeights weights() {
		return new TestOrderState.ScoringWeights(50, 100, 30, 10, 10, 40, 20, 15, 25, 10, 2, 4);
	}

	private static Map<String, TestScorer.ScoreResult> scores(TestScorer scorer, Set<String> tests) {
		Map<String, TestScorer.ScoreResult> out = new java.util.LinkedHashMap<>();
		for (String t : tests) {
			out.put(t, scorer.score(t));
		}
		return out;
	}

	@Test
	void overlapJoinComputedOncePerTest_withEquivalenceAndTiming() {
		DependencyMap depMap = syntheticDepMap();
		Set<String> changed = changedClasses();
		TestOrderState state = new TestOrderState();
		Set<String> testClassNames = depMap.testClasses();

		// ── legacy pipelines (no shared index): the join runs in the closure AND
		// in the scorer's set-cover pre-scan — ~2x per candidate test
		long legacyStart = System.nanoTime();
		CoverageClosure legacyClosure = CoverageClosure.compute(depMap, changed, Set.of(), null);
		TestScorer legacyScorer = new TestScorer(weights(), depMap, state, changed, Set.of(), testClassNames);
		Map<String, TestScorer.ScoreResult> legacyScores = scores(legacyScorer, testClassNames);
		long legacyNanos = System.nanoTime() - legacyStart;

		// ── shared pipelines: one memoized join per distinct candidate test
		long sharedStart = System.nanoTime();
		CoverageClosure.OverlapIndex index = CoverageClosure.OverlapIndex.forSelection(depMap, changed, Set.of(), null);
		CoverageClosure sharedClosure;
		Map<String, TestScorer.ScoreResult> sharedScores;
		CoverageClosure.OverlapIndex.install(index);
		try {
			sharedClosure = CoverageClosure.compute(depMap, changed, Set.of(), null, index);
			TestScorer sharedScorer = new TestScorer(weights(), depMap, state, changed, Set.of(), testClassNames);
			sharedScores = scores(sharedScorer, testClassNames);
		} finally {
			CoverageClosure.OverlapIndex.clear();
		}
		long sharedNanos = System.nanoTime() - sharedStart;

		// ── behavior equivalence (content-level: set iteration order is not part
		// of the contract; weighted sums compared with FP tolerance)
		assertEquals(legacyClosure.universe(), sharedClosure.universe());
		assertEquals(new java.util.HashSet<>(legacyClosure.coverCore()),
				new java.util.HashSet<>(sharedClosure.coverCore()));
		assertEquals(legacyClosure.soleCovererTests(), sharedClosure.soleCovererTests());
		assertTrue(legacyClosure.coverCore().size() > 0, "synthetic change must produce a cover core");
		for (String t : testClassNames) {
			TestScorer.ScoreResult legacy = legacyScores.get(t);
			TestScorer.ScoreResult shared = sharedScores.get(t);
			assertEquals(legacy.score(), shared.score(), "score divergence for " + t);
			assertEquals(legacy.depOverlap(), shared.depOverlap(), "depOverlap divergence for " + t);
			assertEquals(legacy.weightedDepOverlap(), shared.weightedDepOverlap(), 1e-9,
					"weightedDepOverlap divergence for " + t);
		}

		// ── call-count evidence: legacy join calls = closure candidates + scorer
		// affected tests (~2x); the shared index performs exactly one per
		// distinct candidate test (~1x). No changed test classes and no nested
		// classes in the synthetic map, so both pipelines query the same
		// candidate set.
		int candidates = depMap.getAffectedTests(changed).size();
		long legacyJoinCalls = 2L * candidates;
		assertEquals(candidates, index.rawComputationCount(),
				"shared join must run exactly once per distinct candidate test");
		assertEquals(legacyJoinCalls, 2 * index.rawComputationCount(),
				"legacy pipelines would run the join ~2x per candidate (~2x → 1x)");

		System.out.printf("[overlap-dedup] legacy join calls=%d (%.1f ms), shared join calls=%d (%.1f ms)%n",
				legacyJoinCalls, legacyNanos / 1_000_000.0, index.rawComputationCount(), sharedNanos / 1_000_000.0);
	}

	/**
	 * Member-level branch: the overlap result ignores the match set entirely, so
	 * the shared index must pass it through UNFILTERED — both consumers must see
	 * identical cover behavior.
	 */
	@Test
	void memberLevelOverlapSharedWithoutFiltering() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.ATest", Set.of("com.example.A"));
		depMap.put("com.example.BTest", Set.of("com.example.B"));
		depMap.put("com.example.DTest", Set.of("com.example.A"));
		depMap.putMemberDeps("com.example.ATest", Set.of("com.example.A#other"));
		depMap.putMemberDeps("com.example.DTest", Set.of("com.example.A#m"));
		Map<String, Set<String>> byClass = Map.of("com.example.A", Set.of("m"));
		ChangedMembers members = new ChangedMembers(Set.of("com.example.A"), Set.of("com.example.A#m"), byClass,
				Set.of(), Set.of(), Map.of("com.example.A#m", ChangeKind.BODY));
		Set<String> changed = Set.of("com.example.A");

		CoverageClosure legacy = CoverageClosure.compute(depMap, changed, Set.of(), members);

		CoverageClosure.OverlapIndex index = CoverageClosure.OverlapIndex.forSelection(depMap, changed, Set.of(),
				members);
		CoverageClosure shared;
		CoverageClosure.OverlapIndex.install(index);
		try {
			shared = CoverageClosure.compute(depMap, changed, Set.of(), members, index);
		} finally {
			CoverageClosure.OverlapIndex.clear();
		}

		assertEquals(legacy.universe(), shared.universe());
		assertEquals(new java.util.HashSet<>(legacy.coverCore()), new java.util.HashSet<>(shared.coverCore()));
		assertEquals(legacy.soleCovererTests(), shared.soleCovererTests());
		// DTest is the sole coverer of com.A at member precision in both pipelines
		assertEquals(legacy.soleCovererTests(), Set.of("com.example.DTest"));
	}

	/**
	 * End-to-end: the thread-confined index is cleared after every select() run.
	 */
	@Test
	void selectClearsThreadConfinedIndex() throws IOException {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.ATest", Set.of("com.example.A"));
		depMap.put("com.example.DTest", Set.of("com.example.A"));
		depMap.putMemberDeps("com.example.ATest", Set.of("com.example.A#other"));
		depMap.putMemberDeps("com.example.DTest", Set.of("com.example.A#m"));
		Map<String, Set<String>> byClass = Map.of("com.example.A", Set.of("m"));
		ChangedMembers members = new ChangedMembers(Set.of("com.example.A"), Set.of("com.example.A#m"), byClass,
				Set.of(), Set.of(), Map.of("com.example.A#m", ChangeKind.BODY));
		TestOrderState state = new TestOrderState();
		PluginLog log = new PluginLog() {
			@Override
			public void info(String message) {
			}

			@Override
			public void warn(String message) {
			}

			@Override
			public void debug(String message) {
			}
		};

		AffectedOperation.SelectConfig config = new AffectedOperation.SelectConfig(depMap, state,
				Set.of("com.example.A"), Set.of(), state.weights(), 10, 0, null, Set.of(), tempDir.resolve("sel.txt"),
				tempDir.resolve("rem.txt"), log, null, TestSelector.CacheConfig.DISABLED, members, false);

		AffectedOperation.SelectResult result = AffectedOperation.select(config);

		List<String> selected = result.selection().selected();
		assertTrue(selected.contains("com.example.DTest"), "sole member-level coverer must be selected: " + selected);
		assertNull(CoverageClosure.OverlapIndex.current(),
				"the shared overlap index must be cleared when select() returns");
	}
}
