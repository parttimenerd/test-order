package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
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

	/**
	 * BUG-171: {@code @Nested} inner test classes are indexed as separate
	 * {@code Outer$Nested} FQCNs. Surefire runs the outer class, so tier lists must
	 * be collapsed to outer-class form — nested siblings must not inflate tier
	 * counts or land in different tiers.
	 */
	@Test
	void collapsesNestedSiblingsToOuterClassAcrossTiers() {
		DependencyMap depMap = new DependencyMap();
		// Three @Nested siblings of the same outer class, plus three distinct classes.
		depMap.put("t.OuterA$N1", Set.of("p.S1"));
		depMap.put("t.OuterA$N2", Set.of("p.S2"));
		depMap.put("t.OuterA$N3", Set.of("p.S3"));
		depMap.put("t.OuterB", Set.of("p.S4"));
		depMap.put("t.OuterC", Set.of("p.S5"));
		depMap.put("t.OuterD", Set.of("p.S6"));

		TieredTestSelector selector = new TieredTestSelector(depMap, new TestOrderState(), Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TieredTestSelector.Config(0.5, false), Set.of());

		TieredTestSelector.TieredSelection selection = selector.select();

		List<String> all = selection.allInOrder();
		for (String t : all) {
			assertFalse(t.contains("$"), "tier entry must be collapsed to the outer class: " + t);
		}
		// 6 raw FQCNs collapse to 4 runnable outer classes (OuterA once).
		assertEquals(4, all.size(), "tiers must report distinct outer classes, not nested siblings");
		assertEquals(new java.util.LinkedHashSet<>(all).size(), all.size(),
				"no outer class may appear in more than one tier");
		assertTrue(all.contains("t.OuterA"), "OuterA must appear exactly once across tiers");
	}

	// ── applyShard ───────────────────────────────────────────────────────────

	private static List<String> shardTests(int n) {
		List<String> result = new java.util.ArrayList<>();
		for (int i = 0; i < n; i++)
			result.add("t" + i);
		return result;
	}

	@Test
	void applyShardNullSpecReturnsAll() {
		List<String> all = shardTests(5);
		assertEquals(all, TieredTestSelector.applyShard(all, null));
	}

	@Test
	void applyShardBlankSpecReturnsAll() {
		List<String> all = shardTests(5);
		assertEquals(all, TieredTestSelector.applyShard(all, "  "));
	}

	@Test
	void applyShardSingleShardReturnsAll() {
		List<String> all = shardTests(5);
		assertEquals(all, TieredTestSelector.applyShard(all, "1/1"));
	}

	@Test
	void applyShardEvenSplit() {
		List<String> all = shardTests(6);
		assertEquals(List.of("t0", "t1"), TieredTestSelector.applyShard(all, "1/3"));
		assertEquals(List.of("t2", "t3"), TieredTestSelector.applyShard(all, "2/3"));
		assertEquals(List.of("t4", "t5"), TieredTestSelector.applyShard(all, "3/3"));
	}

	@Test
	void applyShardUnevenSplit_remainderDistributedToFirstShards() {
		// 5 tests, 3 shards: shards 1 and 2 get 2, shard 3 gets 1
		List<String> all = shardTests(5);
		List<String> s1 = TieredTestSelector.applyShard(all, "1/3");
		List<String> s2 = TieredTestSelector.applyShard(all, "2/3");
		List<String> s3 = TieredTestSelector.applyShard(all, "3/3");
		assertEquals(2, s1.size());
		assertEquals(2, s2.size());
		assertEquals(1, s3.size());
		List<String> combined = new java.util.ArrayList<>(s1);
		combined.addAll(s2);
		combined.addAll(s3);
		assertEquals(all, combined);
	}

	@Test
	void applyShardCoverageIsComplete() {
		List<String> all = shardTests(10);
		List<String> combined = new java.util.ArrayList<>();
		for (int k = 1; k <= 4; k++) {
			combined.addAll(TieredTestSelector.applyShard(all, k + "/4"));
		}
		assertEquals(all, combined);
	}

	@Test
	void applyShardEmptyListReturnsEmpty() {
		assertEquals(List.of(), TieredTestSelector.applyShard(List.of(), "2/3"));
	}

	@Test
	void applyShardMalformedSpecThrows() {
		assertThrows(IllegalArgumentException.class, () -> TieredTestSelector.applyShard(shardTests(3), "abc"));
		assertThrows(IllegalArgumentException.class, () -> TieredTestSelector.applyShard(shardTests(3), "1/0"));
		assertThrows(IllegalArgumentException.class, () -> TieredTestSelector.applyShard(shardTests(3), "0/3"));
		assertThrows(IllegalArgumentException.class, () -> TieredTestSelector.applyShard(shardTests(3), "4/3"));
	}

	@Test
	void applyShardSingleTest() {
		List<String> single = List.of("only");
		assertEquals(List.of("only"), TieredTestSelector.applyShard(single, "1/3"));
		// shards 2 and 3 get nothing when n < N
		assertEquals(List.of(), TieredTestSelector.applyShard(single, "2/3"));
		assertEquals(List.of(), TieredTestSelector.applyShard(single, "3/3"));
	}
}
