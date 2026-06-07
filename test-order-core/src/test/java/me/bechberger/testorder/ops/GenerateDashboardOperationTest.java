package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DashboardGenerator;
import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestScorer;

class GenerateDashboardOperationTest {

	@TempDir
	Path tempDir;

	@Test
	void generateLogsGuidanceWithoutMisleadingOpenBrowserMessage() throws IOException {
		List<String> infos = new ArrayList<>();
		PluginLog log = new PluginLog() {
			@Override
			public void info(String message) {
				infos.add(message);
			}

			@Override
			public void warn(String message) {
			}

			@Override
			public void debug(String message) {
			}
		};

		Path out = tempDir.resolve("dashboard/index.html");
		Path generated = GenerateDashboardOperation.generate(List.of(), null, new TestOrderState(),
				TestOrderState.ScoringWeights.DEFAULT, Set.of(), Set.of(), new DependencyMap(), "demo-project",
				".test-order/state.lz4", ".test-order/test-dependencies.lz4", "0.0.1-SNAPSHOT", null,
				"<html><body>" + DashboardGenerator.DATA_PLACEHOLDER + "</body></html>", out, log);

		assertTrue(Files.exists(generated), "Dashboard file should be written");
		assertTrue(infos.stream().anyMatch(s -> s.contains("Dashboard written to:")),
				"Should log written dashboard path");
		assertTrue(infos.stream().anyMatch(s -> s.contains("mvn test-order:dashboard -Dtestorder.dashboard.open=true")),
				"Should log explicit open guidance");
		assertFalse(infos.stream().anyMatch(s -> s.contains("Open in browser")),
				"Should not log misleading unconditional open-browser message");
	}

	// ── DashboardGenerator mutation section ───────────────────────────────────

	private DashboardGenerator.ScoredTest scored(String name) {
		var result = new TestScorer.ScoreResult(0, 0, 0, 0.0, false, false, false, false, 0.0, 0.0, false, -1.0);
		return new DashboardGenerator.ScoredTest(name, result, 100L, 0.0);
	}

	@SuppressWarnings("unchecked")
	@Test
	void buildDataIncludesMutationSectionWhenKillRatesSet() {
		TestOrderState state = new TestOrderState();
		state.setKillRates(Map.of("com.FooTest", 0.5, "com.BarTest", 0.3, "com.BazTest", 0.0));

		var gen = new DashboardGenerator("proj", "state.lz4", "index.lz4", "0.0.1");
		Map<String, Object> data = gen.buildData(
				List.of(scored("com.FooTest"), scored("com.BarTest"), scored("com.BazTest")), Set.of(), Set.of(), state,
				TestOrderState.ScoringWeights.DEFAULT, new DependencyMap(), 100L);

		Map<String, Object> mutation = (Map<String, Object>) data.get("mutation");
		assertNotNull(mutation, "mutation section should be present when kill rates are set");
		assertEquals(true, mutation.get("enabled"));

		List<Map<String, Object>> tests = (List<Map<String, Object>>) mutation.get("tests");
		assertNotNull(tests);
		assertEquals(3, tests.size());
		// Sorted descending by kill rate
		assertEquals("com.FooTest", tests.get(0).get("testClass"));
		assertEquals("com.BarTest", tests.get(1).get("testClass"));
		assertEquals("com.BazTest", tests.get(2).get("testClass"));
	}

	@Test
	void buildDataOmitsMutationSectionWithNoKillRates() {
		TestOrderState state = new TestOrderState(); // no kill rates

		var gen = new DashboardGenerator("proj", "state.lz4", "index.lz4", "0.0.1");
		Map<String, Object> data = gen.buildData(List.of(scored("com.FooTest")), Set.of(), Set.of(), state,
				TestOrderState.ScoringWeights.DEFAULT, new DependencyMap(), 100L);

		assertNull(data.get("mutation"), "mutation section should be absent when no kill rates");
	}

	@SuppressWarnings("unchecked")
	@Test
	void buildDataMutationSummaryCountsTiers() {
		TestOrderState state = new TestOrderState();
		state.setKillRates(Map.of("com.HighTest", 0.20, // ≥ 0.15 → high
				"com.MedTest", 0.10, // ≥ 0.05 → medium
				"com.LowTest", 0.02, // > 0 → low
				"com.NoneTest", 0.0)); // 0 → none

		var gen = new DashboardGenerator("proj", "state.lz4", "index.lz4", "0.0.1");
		Map<String, Object> data = gen.buildData(
				List.of(scored("com.HighTest"), scored("com.MedTest"), scored("com.LowTest"), scored("com.NoneTest")),
				Set.of(), Set.of(), state, TestOrderState.ScoringWeights.DEFAULT, new DependencyMap(), 100L);

		Map<String, Object> mutation = (Map<String, Object>) data.get("mutation");
		Map<String, Object> summary = (Map<String, Object>) mutation.get("summary");
		assertEquals(1, summary.get("high"));
		assertEquals(1, summary.get("medium"));
		assertEquals(1, summary.get("low"));
		assertEquals(1, summary.get("none"));
	}

	@SuppressWarnings("unchecked")
	@Test
	void buildDataPerTestKillRateRounded() {
		TestOrderState state = new TestOrderState();
		state.setKillRates(Map.of("com.FooTest", 0.123456789));

		var gen = new DashboardGenerator("proj", "state.lz4", "index.lz4", "0.0.1");
		Map<String, Object> data = gen.buildData(List.of(scored("com.FooTest")), Set.of(), Set.of(), state,
				TestOrderState.ScoringWeights.DEFAULT, new DependencyMap(), 100L);

		// The test entry should have a rounded killRate field
		List<Map<String, Object>> tests = (List<Map<String, Object>>) data.get("tests");
		Double killRate = (Double) tests.get(0).get("killRate");
		assertNotNull(killRate);
		// Rounded to 4 decimal places: 0.1235
		assertEquals(0.1235, killRate, 0.00005);
	}

	@Test
	void buildDataTestKillRateNullWhenNoData() {
		TestOrderState state = new TestOrderState(); // no kill rates

		var gen = new DashboardGenerator("proj", "state.lz4", "index.lz4", "0.0.1");
		Map<String, Object> data = gen.buildData(List.of(scored("com.FooTest")), Set.of(), Set.of(), state,
				TestOrderState.ScoringWeights.DEFAULT, new DependencyMap(), 100L);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> tests = (List<Map<String, Object>>) data.get("tests");
		// When no kill rates exist, the killRate key should not be present in test
		// entries
		assertFalse(tests.get(0).containsKey("killRate"),
				"killRate should be absent when no kill rate data is available");
	}

	// ── DashboardGenerator.computeMedian ────────────────────────────────────────

	@Test
	void computeMedian_emptyArray_returnsZero() {
		assertEquals(0L, DashboardGenerator.computeMedian(new long[0]));
	}

	@Test
	void computeMedian_singleElement_returnsElement() {
		assertEquals(42L, DashboardGenerator.computeMedian(new long[]{42L}));
	}

	@Test
	void computeMedian_oddCount_returnsMiddle() {
		// sorted: [1, 3, 5] → middle = 3
		assertEquals(3L, DashboardGenerator.computeMedian(new long[]{5L, 1L, 3L}));
	}

	@Test
	void computeMedian_evenCount_returnsAverageOfMiddleTwo() {
		// sorted: [2, 4, 6, 8] → (4 + 6) / 2 = 5
		assertEquals(5L, DashboardGenerator.computeMedian(new long[]{8L, 2L, 4L, 6L}));
	}

	@Test
	void computeMedian_evenCount_oddSum_roundsDown() {
		// sorted: [1, 2] → (1 + 2) / 2 = 1 (integer division)
		assertEquals(1L, DashboardGenerator.computeMedian(new long[]{1L, 2L}));
	}

	@Test
	void computeMedian_veryLargeValues_noOverflow() {
		// BUG-89: (Long.MAX_VALUE + Long.MAX_VALUE) overflows to a negative number.
		// Overflow-safe formula must be used.
		long hi = Long.MAX_VALUE;
		long lo = Long.MAX_VALUE - 2;
		// sorted: [lo, hi] → lo + (hi - lo) / 2 = lo + 1
		assertEquals(lo + 1, DashboardGenerator.computeMedian(new long[]{hi, lo}));
	}
}
