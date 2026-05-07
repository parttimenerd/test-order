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
