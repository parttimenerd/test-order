package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for the skip-if-unchanged cache phase
 * ({@link TestSelector.CacheConfig}).
 */
class TestSelectorCacheTest {

	private DependencyMap buildDepMap(Map<String, Set<String>> deps) {
		DependencyMap map = new DependencyMap();
		for (var e : deps.entrySet()) {
			map.put(e.getKey(), e.getValue());
		}
		return map;
	}

	/** Record N consecutive passing runs for the given tests. */
	private void recordPassingRuns(TestOrderState state, int n, String... tests) {
		List<String> order = Arrays.asList(tests);
		for (int i = 0; i < n; i++) {
			state.addRunRecord(TestOrderState.buildRunRecord(order, Set.of()));
		}
	}

	private TestSelector.CacheConfig cache(int streak, double maxFraction) {
		return new TestSelector.CacheConfig(true, streak, maxFraction);
	}

	@Test
	void cacheDisabledByDefault_noCachedEntries() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X")));
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.A", 100L);
		recordPassingRuns(state, 5, "com.A");

		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(-1, 0, 42L)).select();

		assertTrue(sel.cached().isEmpty(), "default config has cache disabled");
		assertTrue(sel.selected().contains("com.A"));
	}

	@Test
	void cachesTestWithSufficientPassStreak_andNoDepChange() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X"), "com.B", Set.of("app.Y")));
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.A", 100L);
		state.recordDuration("com.B", 200L);
		// 3 passing runs each
		recordPassingRuns(state, 3, "com.A", "com.B");

		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(-1, 0, 42L), Set.of(), Map.of(),
				cache(3, 0.9)).select();

		// 2 tests; cap = floor(0.9 * 2) = 1. Both eligible, only 1 can be cached.
		assertEquals(1, sel.cached().size(), "cap binds at 1");
		assertEquals(1, sel.selected().size() + sel.remaining().size(), "the other still runs");
	}

	@Test
	void doesNotCacheTestAffectedByChange() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X"), "com.B", Set.of("app.Y")));
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.A", 100L);
		state.recordDuration("com.B", 200L);
		recordPassingRuns(state, 5, "com.A", "com.B");

		// app.X changed → com.A is affected, must run
		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of("app.X"), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(-1, 0, 42L), Set.of(), Map.of(),
				cache(3, 1.0)).select();

		assertFalse(sel.cached().contains("com.A"), "change-affected test must not be cached");
		// com.B unaffected and has streak → cacheable
		assertTrue(sel.cached().contains("com.B"), "unaffected test with streak must be cached");
	}

	@Test
	void doesNotCacheWithoutSufficientStreak() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X")));
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.A", 100L);
		recordPassingRuns(state, 2, "com.A"); // streak=2, but minPassStreak=3

		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(-1, 0, 42L), Set.of(), Map.of(),
				cache(3, 1.0)).select();

		assertTrue(sel.cached().isEmpty(), "streak too short → not cached");
	}

	@Test
	void streakBrokenByFailure_notCached() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X")));
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.A", 100L);
		// Pass, fail, pass, pass → most recent streak is only 2
		List<String> order = List.of("com.A");
		state.addRunRecord(TestOrderState.buildRunRecord(order, Set.of()));
		state.addRunRecord(TestOrderState.buildRunRecord(order, Set.of("com.A")));
		state.addRunRecord(TestOrderState.buildRunRecord(order, Set.of()));
		state.addRunRecord(TestOrderState.buildRunRecord(order, Set.of()));

		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(-1, 0, 42L), Set.of(), Map.of(),
				cache(3, 1.0)).select();

		assertTrue(sel.cached().isEmpty(), "recent failure breaks streak");
	}

	@Test
	void alwaysRunTestsAreNotCached() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X"), "com.B", Set.of("app.Y")));
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.A", 100L);
		state.recordDuration("com.B", 200L);
		recordPassingRuns(state, 5, "com.A", "com.B");

		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(-1, 0, 42L), Set.of("com.A"), Map.of(),
				cache(3, 1.0)).select();

		assertFalse(sel.cached().contains("com.A"), "@AlwaysRun must override cache");
		assertTrue(sel.selected().contains("com.A"));
	}

	@Test
	void maxSkipFractionCap_prefersSlowerTests() {
		// 4 tests with distinct durations; cap = floor(0.5 * 4) = 2 → keep the 2
		// slowest
		Map<String, Set<String>> deps = new LinkedHashMap<>();
		Map<String, Long> durations = new LinkedHashMap<>();
		for (int i = 0; i < 4; i++) {
			deps.put("com.T" + i, Set.of("app.Dep" + i));
			durations.put("com.T" + i, (long) (i + 1) * 100L); // 100, 200, 300, 400
		}
		DependencyMap depMap = buildDepMap(deps);
		TestOrderState state = new TestOrderState();
		durations.forEach(state::recordDuration);
		recordPassingRuns(state, 5, deps.keySet().toArray(String[]::new));

		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(-1, 0, 42L), Set.of(), Map.of(),
				cache(3, 0.5)).select();

		assertEquals(2, sel.cached().size(), "cap = 2");
		// The two slowest (T2=300ms, T3=400ms) should be the ones cached.
		assertTrue(sel.cached().contains("com.T2"), "slower test cached");
		assertTrue(sel.cached().contains("com.T3"), "slowest test cached");
	}

	@Test
	void cacheRemovesFromSelectedAndRemaining() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X")));
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.A", 100L);
		recordPassingRuns(state, 5, "com.A");

		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(-1, 0, 42L), Set.of(), Map.of(),
				cache(3, 1.0)).select();

		assertTrue(sel.cached().contains("com.A"));
		assertFalse(sel.selected().contains("com.A"), "must not be in selected");
		assertFalse(sel.remaining().contains("com.A"), "must not be in remaining either");
	}

	@Test
	void backwardCompatConstructor_setsDisabledCacheConfig() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X")));
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.A", 100L);
		recordPassingRuns(state, 5, "com.A");

		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(-1, 0, 42L)).select();

		assertTrue(sel.cached().isEmpty());
	}

	@Test
	void quarantinedTestNotCacheSkipped_evenWithLongPassStreak() {
		// A quarantined flaky test reports as ABORTED (not failed), so its pass streak
		// would grow unchecked and the cache would eventually skip it forever — masking
		// the flakiness. The CacheConfig.quarantinedTests guard must prevent that.
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X"), "com.B", Set.of("app.Y")));
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.A", 100L);
		state.recordDuration("com.B", 200L);
		recordPassingRuns(state, 10, "com.A", "com.B");

		TestSelector.CacheConfig cfg = new TestSelector.CacheConfig(true, 3, 1.0, Set.of("com.A"));
		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(-1, 0, 42L), Set.of(), Map.of(), cfg)
				.select();

		assertFalse(sel.cached().contains("com.A"), "quarantined test must not be cache-skipped");
		assertTrue(sel.cached().contains("com.B"), "non-quarantined eligible test must still be cached");
	}
}
