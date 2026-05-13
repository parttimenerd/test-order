package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class TieredTestSelectorTest {

	@Test
	void splitsIntoThreeTiersUsingDurationBudget() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.AffectedTest", Set.of("com.example.Service"));
		depMap.put("com.example.FastTest", Set.of("com.example.Other"));
		depMap.put("com.example.SlowTest", Set.of("com.example.Other"));
		depMap.put("com.example.AlwaysRunSmokeTest", Set.of("com.example.Unrelated"));

		TestOrderState state = new TestOrderState();
		state.recordDuration("com.example.FastTest", 10);
		state.recordDuration("com.example.SlowTest", 100);
		state.recordDuration("com.example.AffectedTest", 30);
		state.recordDuration("com.example.AlwaysRunSmokeTest", 5);

		TieredTestSelector selector = new TieredTestSelector(depMap, state, Set.of("com.example.Service"), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TieredTestSelector.Config(0.5, true),
				Set.of("com.example.AlwaysRunSmokeTest"));

		TieredTestSelector.TieredSelection selection = selector.select();

		assertTrue(selection.tier1().contains("com.example.AffectedTest"));
		assertTrue(selection.tier1().contains("com.example.AlwaysRunSmokeTest"));
		assertEquals(1, selection.tier2().size(), "Tier 2 should contain only top half duration budget");
		assertEquals(1, selection.tier3().size(), "Tier 3 should contain the remaining test");
		assertEquals(4, selection.allInOrder().size());
	}

	@Test
	void durationBudgetAccountsForUnknownDurations() {
		// 4 tests with NO dependency overlap → all go to remaining (none in tier 1).
		// 2 have known durations (100ms each), 2 have unknown durations.
		// With tier2Fraction=0.5, tier 2 should contain ~50% = 2 tests.
		DependencyMap depMap = new DependencyMap();
		depMap.put("t.Known1", Set.of("p.A"));
		depMap.put("t.Unknown1", Set.of("p.B"));
		depMap.put("t.Known2", Set.of("p.C"));
		depMap.put("t.Unknown2", Set.of("p.D"));

		TestOrderState state = new TestOrderState();
		// Only record durations for 2 of the 4 tests
		state.recordDuration("t.Known1", 100);
		state.recordDuration("t.Known2", 100);
		// t.Unknown1 and t.Unknown2 have NO recorded duration → Long.MAX_VALUE

		TieredTestSelector selector = new TieredTestSelector(depMap, state, Set.of(), // no changed classes → nothing
																						// goes to tier 1
				Set.of(), TestOrderState.ScoringWeights.DEFAULT, new TieredTestSelector.Config(0.5, true), Set.of());

		TieredTestSelector.TieredSelection selection = selector.select();

		// All 4 tests should be in remaining (none in tier 1)
		assertEquals(0, selection.tier1().size(), "No tests should be in tier 1");
		// With tier2Fraction=0.5, tier 2 should contain ~50% = 2 tests
		// Bug: budget is computed from known durations only (200 * 0.5 = 100ms),
		// but unknown tests consume estimated avgDuration (100ms) from the budget.
		// The first test costs 100ms, exhausting the budget, so only 1 is selected
		// instead of 2.
		assertEquals(2, selection.tier2().size(), "tier2Fraction=0.5 should select ~50% of remaining tests, "
				+ "but got tier2=" + selection.tier2() + ", tier3=" + selection.tier3());
		assertEquals(2, selection.tier3().size());
	}

	@Test
	void supportsCountBasedTierTwoSelection() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("t.A", Set.of("p.A"));
		depMap.put("t.B", Set.of("p.B"));
		depMap.put("t.C", Set.of("p.C"));
		depMap.put("t.D", Set.of("p.D"));

		TieredTestSelector selector = new TieredTestSelector(depMap, new TestOrderState(), Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TieredTestSelector.Config(0.5, false), Set.of());

		TieredTestSelector.TieredSelection selection = selector.select();

		assertEquals(0, selection.tier1().size());
		assertEquals(2, selection.tier2().size());
		assertEquals(2, selection.tier3().size());
	}
}
