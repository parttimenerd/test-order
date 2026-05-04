package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestSelectorTest {

	@TempDir
	Path tempDir;

	/** Build a simple dependency map with the given test classes. */
	private DependencyMap buildDepMap(Map<String, Set<String>> deps) {
		DependencyMap map = new DependencyMap();
		for (var e : deps.entrySet()) {
			map.put(e.getKey(), e.getValue());
		}
		return map;
	}

	/** Build a state with durations recorded for the given tests. */
	private TestOrderState stateWithDurations(Map<String, Long> durations) {
		TestOrderState state = new TestOrderState();
		for (var e : durations.entrySet()) {
			state.recordDuration(e.getKey(), e.getValue());
		}
		return state;
	}

	@Test
	void newTestsAlwaysSelected() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X"), "com.B", Set.of("app.Y")));
		TestOrderState state = stateWithDurations(Map.of("com.A", 100L, "com.B", 200L));
		// "com.New" is not in depMap → treated as new
		Set<String> changedTests = Set.of("com.New");

		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), changedTests,
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(0, 0, 42L)).select(); // topN=0,
																										// randomM=0 →
																										// only new
																										// tests

		assertTrue(sel.selected().contains("com.New"), "new test must be selected");
	}

	@Test
	void topNSelectsHighestScored() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X"), "com.B", Set.of("app.Y"), "com.C",
				Set.of("app.Z"), "com.D", Set.of("app.W")));
		TestOrderState state = stateWithDurations(Map.of("com.A", 100L, "com.B", 200L, "com.C", 300L, "com.D", 400L));
		// A has dep overlap with changed class → gets higher score
		Set<String> changed = Set.of("app.X");

		TestSelector.Selection sel = new TestSelector(depMap, state, changed, Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(2, 0, 42L)).select();

		// A should be in selected (dep overlap), topN=2 so exactly 2 selected
		assertTrue(sel.selected().contains("com.A"), "com.A should be selected (has dep overlap)");
		assertEquals(2, sel.selected().size());
		assertEquals(2, sel.remaining().size());
	}

	@Test
	void randomMSelectsFastDiverseTests() {
		// 10 tests, 5 of them fast (duration < 0.5 * median)
		Map<String, Set<String>> deps = new LinkedHashMap<>();
		Map<String, Long> durations = new LinkedHashMap<>();
		// median will be around 500ms
		for (int i = 0; i < 10; i++) {
			deps.put("com.Test" + i, Set.of("app.Dep" + i));
			durations.put("com.Test" + i, i < 5 ? 50L : 500L);
		}
		DependencyMap depMap = buildDepMap(deps);
		TestOrderState state = stateWithDurations(durations);

		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(0, 3, 42L)).select(); // topN=0,
																										// randomM=3

		// should have selected 3 fast tests with diverse deps
		assertEquals(3, sel.selected().size());
		assertEquals(7, sel.remaining().size());
		// all selected should be fast tests (index 0-4)
		for (String tc : sel.selected()) {
			int idx = Integer.parseInt(tc.substring("com.Test".length()));
			assertTrue(idx < 5, "selected test " + tc + " should be fast");
		}
	}

	@Test
	void selectedAndRemainingAreDisjointAndComplete() {
		Map<String, Set<String>> deps = new LinkedHashMap<>();
		Map<String, Long> durations = new LinkedHashMap<>();
		for (int i = 0; i < 20; i++) {
			deps.put("com.Test" + i, Set.of("app.Dep" + (i % 5)));
			durations.put("com.Test" + i, i < 10 ? 50L : 500L);
		}
		DependencyMap depMap = buildDepMap(deps);
		TestOrderState state = stateWithDurations(durations);

		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(5, 3, 123L)).select();

		Set<String> all = new LinkedHashSet<>(sel.selected());
		all.addAll(sel.remaining());
		assertEquals(20, all.size(), "selected + remaining must cover all tests");

		Set<String> overlap = new LinkedHashSet<>(sel.selected());
		overlap.retainAll(sel.remaining());
		assertTrue(overlap.isEmpty(), "selected and remaining must not overlap");
	}

	@Test
	void seedProducesReproducibleResults() {
		Map<String, Set<String>> deps = new LinkedHashMap<>();
		Map<String, Long> durations = new LinkedHashMap<>();
		for (int i = 0; i < 20; i++) {
			deps.put("com.Test" + i, Set.of("app.Dep" + (i % 3)));
			durations.put("com.Test" + i, i < 10 ? 50L : 500L);
		}
		DependencyMap depMap = buildDepMap(deps);
		TestOrderState state = stateWithDurations(durations);

		TestSelector.Config cfg = new TestSelector.Config(3, 5, 999L);
		TestSelector.Selection sel1 = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, cfg).select();
		TestSelector.Selection sel2 = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, cfg).select();

		assertEquals(sel1.selected(), sel2.selected(), "same seed → same selection");
	}

	@Test
	void writeAndReadTestListRoundTrip() throws IOException {
		List<String> tests = List.of("com.A", "com.B", "com.C");
		Path file = tempDir.resolve("tests.txt");
		TestSelector.writeTestList(tests, file);
		List<String> loaded = TestSelector.readTestList(file);
		assertEquals(tests, loaded);
	}

	@Test
	void readTestListSkipsCommentsAndBlankLines() throws IOException {
		Path file = tempDir.resolve("tests.txt");
		Files.writeString(file, "# comment\n\ncom.A\n  com.B  \n# another comment\ncom.C\n");
		List<String> loaded = TestSelector.readTestList(file);
		assertEquals(List.of("com.A", "com.B", "com.C"), loaded);
	}

	@Test
	void toSurefireIncludes() {
		String includes = TestSelector.toSurefireIncludes(List.of("com.A", "com.B"));
		assertEquals("com.A,com.B", includes);
	}

	@Test
	void emptyDepMapSelectsNothing() {
		DependencyMap depMap = new DependencyMap();
		TestOrderState state = new TestOrderState();

		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(10, 5, 42L)).select();

		assertTrue(sel.selected().isEmpty());
		assertTrue(sel.remaining().isEmpty());
	}

	@Test
	void topNLargerThanTestCountSelectsAll() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X"), "com.B", Set.of("app.Y")));
		TestOrderState state = stateWithDurations(Map.of("com.A", 100L, "com.B", 200L));

		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(100, 0, 42L)).select();

		assertEquals(2, sel.selected().size());
		assertTrue(sel.remaining().isEmpty());
	}

	@Test
	void largeSuiteSelectionCompletesWithinReasonableTime() {
		assertTimeout(Duration.ofSeconds(5), () -> {
			Map<String, Set<String>> deps = new LinkedHashMap<>();
			Map<String, Long> durations = new LinkedHashMap<>();

			for (int i = 0; i < 2_000; i++) {
				Set<String> testDeps = new LinkedHashSet<>();
				testDeps.add("app.Shared" + (i % 40));
				testDeps.add("app.Feature" + (i % 200));
				testDeps.add("app.Layer" + (i % 15));
				deps.put("com.test.Suite" + i, testDeps);
				durations.put("com.test.Suite" + i, i < 800 ? 40L : 400L + (i % 50));
			}

			DependencyMap depMap = buildDepMap(deps);
			TestOrderState state = stateWithDurations(durations);
			Set<String> changed = Set.of("app.Shared1", "app.Feature7", "app.Layer3");

			TestSelector.Selection sel = new TestSelector(depMap, state, changed, Set.of(),
					TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(50, 25, 42L)).select();

			assertEquals(2_000, sel.selected().size() + sel.remaining().size());
			assertTrue(sel.selected().size() >= 50, "Selection should include at least the configured top-N tests");
		});
	}

	// ── @AlwaysRun class selection tests ──────────────────────────────

	@Test
	void alwaysRunClassesAlwaysSelected() {
		DependencyMap depMap = buildDepMap(
				Map.of("com.A", Set.of("app.X"), "com.B", Set.of("app.Y"), "com.Smoke", Set.of("app.Z")));
		TestOrderState state = stateWithDurations(Map.of("com.A", 100L, "com.B", 200L, "com.Smoke", 50L));

		// topN=0, randomM=0 → only alwaysRun should be selected
		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(0, 0, 42L), Set.of("com.Smoke"))
				.select();

		assertTrue(sel.selected().contains("com.Smoke"),
				"alwaysRun class must be selected even with zero topN/randomM");
		assertEquals(1, sel.selected().size());
	}

	@Test
	void alwaysRunClassesSelectedBeforeTopN() {
		DependencyMap depMap = buildDepMap(
				Map.of("com.A", Set.of("app.X"), "com.B", Set.of("app.Y"), "com.Smoke", Set.of("app.Z")));
		TestOrderState state = stateWithDurations(Map.of("com.A", 100L, "com.B", 200L, "com.Smoke", 50L));
		Set<String> changed = Set.of("app.X"); // com.A gets dep overlap score

		TestSelector.Selection sel = new TestSelector(depMap, state, changed, Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(2, 0, 42L), Set.of("com.Smoke"))
				.select();

		assertTrue(sel.selected().contains("com.Smoke"));
		// com.Smoke should appear before topN entries
		int smokeIdx = sel.selected().indexOf("com.Smoke");
		assertEquals(0, smokeIdx, "alwaysRun class should be first in selected list");
	}

	@Test
	void alwaysRunClassesNotInRemaining() {
		DependencyMap depMap = buildDepMap(
				Map.of("com.A", Set.of("app.X"), "com.B", Set.of("app.Y"), "com.Smoke", Set.of("app.Z")));
		TestOrderState state = stateWithDurations(Map.of("com.A", 100L, "com.B", 200L, "com.Smoke", 50L));

		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(1, 0, 42L), Set.of("com.Smoke"))
				.select();

		assertFalse(sel.remaining().contains("com.Smoke"), "alwaysRun class must not appear in remaining");
	}

	@Test
	void alwaysRunWithNewTestsBothSelected() {
		DependencyMap depMap = buildDepMap(Map.of("com.A", Set.of("app.X"), "com.Smoke", Set.of("app.Z")));
		TestOrderState state = stateWithDurations(Map.of("com.A", 100L, "com.Smoke", 50L));
		// com.New is not in depMap → treated as new
		Set<String> changedTests = Set.of("com.New");

		TestSelector.Selection sel = new TestSelector(depMap, state, Set.of(), changedTests,
				TestOrderState.ScoringWeights.DEFAULT, new TestSelector.Config(0, 0, 42L), Set.of("com.Smoke"))
				.select();

		assertTrue(sel.selected().contains("com.Smoke"), "alwaysRun must be selected");
		assertTrue(sel.selected().contains("com.New"), "new test must be selected");
	}
}
