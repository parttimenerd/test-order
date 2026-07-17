package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for BUG-170: JUnit 5 {@code @Nested} inner test classes are indexed as
 * separate {@code Outer$Nested} FQCNs. Surefire runs the OUTER class (nested
 * tests execute as its children), and {@code SurefireHelper.configureIncludes}
 * already collapses {@code Outer$Nested} → {@code Outer} before building
 * {@code -Dtest=}. The selection layer must budget the topN cap by outer class
 * and emit outer-class names, so nested siblings do not each consume a distinct
 * selection slot (topN starvation) and reported counts are not inflated.
 */
class TestSelectorNestedTest {

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

	/**
	 * Three {@code @Nested} siblings of {@code OuterA} plus three distinct outer
	 * classes. With topN=2 and no change signal, the budget must count OuterA once,
	 * so the selection collapses to exactly two runnable outer classes.
	 */
	@Test
	void topNBudgetCountsByOuterClass() {
		Map<String, Set<String>> deps = new LinkedHashMap<>();
		deps.put("com.OuterA$N1", Set.of("app.S1"));
		deps.put("com.OuterA$N2", Set.of("app.S2"));
		deps.put("com.OuterA$N3", Set.of("app.S3"));
		deps.put("com.OuterB", Set.of("app.S4"));
		deps.put("com.OuterC", Set.of("app.S5"));
		deps.put("com.OuterD", Set.of("app.S6"));
		DependencyMap depMap = buildDepMap(deps);
		TestOrderState state = stateWithDurations(Map.of("com.OuterA$N1", 100L, "com.OuterA$N2", 100L, "com.OuterA$N3",
				100L, "com.OuterB", 100L, "com.OuterC", 100L, "com.OuterD", 100L));

		// No change signal → topN fallback picks the top-2 by budget.
		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(2, 0, 42L)).select();

		Set<String> selectedOuter = new LinkedHashSet<>(sel.selected());
		assertEquals(2, selectedOuter.size(),
				"topN=2 must yield exactly 2 distinct outer classes (OuterA counts once)");
	}

	/** The emitted selected list must be in outer-class form: no '$', no dupes. */
	@Test
	void selectedListIsCollapsedToOuterClass() {
		Map<String, Set<String>> deps = new LinkedHashMap<>();
		deps.put("com.OuterA$N1", Set.of("app.S1"));
		deps.put("com.OuterA$N2", Set.of("app.S2"));
		deps.put("com.OuterB", Set.of("app.S3"));
		DependencyMap depMap = buildDepMap(deps);
		TestOrderState state = stateWithDurations(
				Map.of("com.OuterA$N1", 100L, "com.OuterA$N2", 100L, "com.OuterB", 100L));

		// topN=-1 (no cap), no change → select all.
		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(-1, 0, 42L)).select();

		for (String s : sel.selected()) {
			assertFalse(s.contains("$"), "selected entry must be collapsed to the outer class: " + s);
		}
		assertEquals(new LinkedHashSet<>(sel.selected()).size(), sel.selected().size(),
				"selected list must not contain duplicate outer classes");
		assertTrue(sel.selected().contains("com.OuterA"), "OuterA must appear once");
		assertTrue(sel.selected().contains("com.OuterB"), "OuterB must appear");
		assertEquals(2, sel.selected().size(), "two nested siblings collapse to one outer class");
	}

	/**
	 * When two changed deps both map to nested siblings of the same outer class,
	 * the change-signal topN budget must count that outer class once.
	 */
	@Test
	void changeSignalBudgetCountsOuterOnce() {
		Map<String, Set<String>> deps = new LinkedHashMap<>();
		deps.put("com.OuterA$N1", Set.of("app.S1"));
		deps.put("com.OuterA$N2", Set.of("app.S2"));
		deps.put("com.OuterB", Set.of("app.S3"));
		DependencyMap depMap = buildDepMap(deps);
		TestOrderState state = stateWithDurations(
				Map.of("com.OuterA$N1", 100L, "com.OuterA$N2", 100L, "com.OuterB", 100L));
		// Both changed classes are covered only by OuterA's nested siblings.
		Set<String> changed = Set.of("app.S1", "app.S2");

		// topN=1: OuterA's two affected siblings must consume only ONE budget unit.
		TestSelector.Selection sel = new TestSelector(depMap, state, changed, Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(1, 0, 42L)).select();

		assertTrue(sel.selected().contains("com.OuterA"), "OuterA is change-affected and must be selected");
		assertEquals(1, new LinkedHashSet<>(sel.selected()).size(), "OuterA must count once toward topN=1");
	}

	/** An outer class must never appear in both selected and remaining. */
	@Test
	void remainingDoesNotDuplicateSelectedOuter() {
		Map<String, Set<String>> deps = new LinkedHashMap<>();
		deps.put("com.OuterA$N1", Set.of("app.S1"));
		deps.put("com.OuterA$N2", Set.of("app.S2"));
		deps.put("com.OuterA$N3", Set.of("app.S3"));
		deps.put("com.OuterB", Set.of("app.S4"));
		DependencyMap depMap = buildDepMap(deps);
		TestOrderState state = stateWithDurations(
				Map.of("com.OuterA$N1", 100L, "com.OuterA$N2", 100L, "com.OuterA$N3", 100L, "com.OuterB", 100L));
		// Change touches only one sibling of OuterA → OuterA selected; other siblings
		// must not resurface in remaining under the same outer name.
		Set<String> changed = Set.of("app.S1");

		TestSelector.Selection sel = new TestSelector(depMap, state, changed, Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(-1, 0, 42L)).select();

		Set<String> selectedOuter = new LinkedHashSet<>(sel.selected());
		for (String r : sel.remaining()) {
			assertFalse(r.contains("$"), "remaining entry must be collapsed to the outer class: " + r);
			assertFalse(selectedOuter.contains(r), "outer class must not be in both selected and remaining: " + r);
		}
		assertTrue(selectedOuter.contains("com.OuterA"), "OuterA selected via changed sibling");
	}

	/**
	 * Regression guard: a repo with zero nested classes must behave exactly as
	 * before — the collapse is a no-op on non-nested names.
	 */
	@Test
	void nonNestedRepoUnaffected() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X"), "com.B", Set.of("app.Y"), "com.C",
				Set.of("app.Z"), "com.D", Set.of("app.W")));
		TestOrderState state = stateWithDurations(Map.of("com.A", 100L, "com.B", 200L, "com.C", 300L, "com.D", 400L));
		Set<String> changed = Set.of("app.X");

		TestSelector.Selection sel = new TestSelector(depMap, state, changed, Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(2, 0, 42L)).select();

		assertTrue(sel.selected().contains("com.A"), "com.A should be selected (has dep overlap)");
		assertEquals(1, sel.selected().size(), "only the change-affected test runs");
		assertEquals(3, sel.remaining().size());
	}
}
