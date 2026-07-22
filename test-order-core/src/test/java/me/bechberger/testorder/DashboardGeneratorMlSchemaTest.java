package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import me.bechberger.testorder.ml.FlakyRuntimeReport;
import me.bechberger.testorder.ml.TestHealthReport;
import me.bechberger.testorder.ml.TestHealthReport.HealthStatus;
import me.bechberger.testorder.ml.TestHealthReport.TestHealth;

class DashboardGeneratorMlSchemaTest {

	private static final Set<String> PRUNED_KEYS = Set.of("flakinessScore", "degradationTrend", "recentFailureRate",
			"volatility", "totalRuns", "totalFailures");

	@Test
	void killRate_innerClass_notClobberedToNullBySecondWrite() {
		// Regression: buildTestEntries writes killRate twice. The first write uses
		// ScoreResult.killRate() (which the scorer resolves via inner→top-level
		// fallback). The second write does a fallback-less killRates.get(st.name())
		// and, when the mutation map is non-empty but lacks the inner-class key,
		// overwrites the good value with null. For an inner test class whose kill
		// rate is stored under the top-level parent name, the dashboard then shows
		// null instead of the real rate.
		String inner = "com.example.OuterTest$Inner";
		String topLevel = "com.example.OuterTest";

		// Scorer already resolved the rate to 0.75 via top-level fallback.
		TestScorer.ScoreResult result = new TestScorer.ScoreResult(10, 1, 2, 0.0, false, false, false, false, 0.0, 0.0,
				false, 0.75);
		DashboardGenerator.ScoredTest scored = new DashboardGenerator.ScoredTest(inner, result, 5L, 0.0);

		// State's mutation kill-rate map is keyed by the TOP-LEVEL name only.
		TestOrderState state = new TestOrderState();
		state.setKillRates(Map.of(topLevel, 0.75));

		DashboardGenerator gen = new DashboardGenerator("p", "state", "index", "v");
		DashboardGenerator.RuntimeExtras extras = new DashboardGenerator.RuntimeExtras(List.of(), 0L,
				FlakyRuntimeReport.empty());

		Map<String, Object> data = gen.buildData(List.of(scored), Set.of(), Set.of(), state,
				TestOrderState.ScoringWeights.DEFAULT, new DependencyMap(), 0L, TestOrderState.WEIGHT_DEFS, Map.of(),
				new TestHealthReport(Map.of(), 0L, 0), extras);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> tests = (List<Map<String, Object>>) data.get("tests");
		assertNotNull(tests);
		assertEquals(1, tests.size());
		Object killRate = tests.get(0).get("killRate");
		assertNotNull(killRate, "inner-class killRate must not be clobbered to null by the second write");
		assertEquals(0.75, ((Number) killRate).doubleValue(), 1e-9);
	}

	@Test
	void mlSection_doesNotEmitPrunedKeys() {
		DashboardGenerator gen = new DashboardGenerator("p", "state", "index", "v");
		TestHealth health = new TestHealth("com.example.FooTest", 0.42, 0.1, 0.25, 0.05, 30, 7, HealthStatus.FLAKY);
		TestHealthReport report = new TestHealthReport(Map.of("com.example.FooTest", health), 0L, 30);

		DashboardGenerator.RuntimeExtras extras = new DashboardGenerator.RuntimeExtras(List.of(), 0L,
				FlakyRuntimeReport.empty());

		Map<String, Object> data = gen.buildData(List.of(), Set.of(), Set.of(), new TestOrderState(),
				TestOrderState.ScoringWeights.DEFAULT, new DependencyMap(), 0L, TestOrderState.WEIGHT_DEFS, Map.of(),
				report, extras);

		@SuppressWarnings("unchecked")
		Map<String, Object> ml = (Map<String, Object>) data.get("ml");
		assertNotNull(ml, "ml section emitted");

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> tests = (List<Map<String, Object>>) ml.get("tests");
		assertNotNull(tests, "ml.tests emitted");
		assertEquals(1, tests.size(), "expected one ml test entry");

		Map<String, Object> entry = tests.get(0);
		for (String pruned : PRUNED_KEYS) {
			assertFalse(entry.containsKey(pruned), "ml entry must not contain pruned key: " + pruned);
		}
		assertTrue(entry.containsKey("failRate"), "failRate kept");
		assertTrue(entry.containsKey("runsAnalyzed"), "runsAnalyzed kept");
		assertTrue(entry.containsKey("recentTrend"), "recentTrend kept");
	}

	@Test
	void cacheSection_omitted_whenCacheDisabledAndEmpty() {
		String prior = System.getProperty(TestOrderConfig.CACHE_SKIP_UNCHANGED);
		try {
			System.clearProperty(TestOrderConfig.CACHE_SKIP_UNCHANGED);

			DashboardGenerator gen = new DashboardGenerator("p", "state", "index", "v");
			DashboardGenerator.RuntimeExtras extras = new DashboardGenerator.RuntimeExtras(List.of(), 0L,
					FlakyRuntimeReport.empty());

			Map<String, Object> data = gen.buildData(List.of(), Set.of(), Set.of(), new TestOrderState(),
					TestOrderState.ScoringWeights.DEFAULT, new DependencyMap(), 0L, TestOrderState.WEIGHT_DEFS,
					Map.of(), new TestHealthReport(Map.of(), 0L, 0), extras);

			assertNull(data.get("cache"), "cache section omitted when sysprop unset and no cached tests");
		} finally {
			if (prior == null) {
				System.clearProperty(TestOrderConfig.CACHE_SKIP_UNCHANGED);
			} else {
				System.setProperty(TestOrderConfig.CACHE_SKIP_UNCHANGED, prior);
			}
		}
	}

	@Test
	void cacheSection_emittedEmpty_whenCacheEnabledButNoTestsSkipped() {
		String prior = System.getProperty(TestOrderConfig.CACHE_SKIP_UNCHANGED);
		try {
			System.setProperty(TestOrderConfig.CACHE_SKIP_UNCHANGED, "true");

			DashboardGenerator gen = new DashboardGenerator("p", "state", "index", "v");
			DashboardGenerator.RuntimeExtras extras = new DashboardGenerator.RuntimeExtras(List.of(), 0L,
					FlakyRuntimeReport.empty());

			Map<String, Object> data = gen.buildData(List.of(), Set.of(), Set.of(), new TestOrderState(),
					TestOrderState.ScoringWeights.DEFAULT, new DependencyMap(), 0L, TestOrderState.WEIGHT_DEFS,
					Map.of(), new TestHealthReport(Map.of(), 0L, 0), extras);

			@SuppressWarnings("unchecked")
			Map<String, Object> cache = (Map<String, Object>) data.get("cache");
			assertNotNull(cache, "cache section emitted when sysprop set even with no cached tests");
			assertEquals(Boolean.TRUE, cache.get("enabled"));
			assertEquals(0, cache.get("skippedCount"));
			assertEquals(0L, cache.get("timeSavedMs"));
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> tests = (List<Map<String, Object>>) cache.get("tests");
			assertNotNull(tests, "tests array present");
			assertTrue(tests.isEmpty(), "tests array empty when no tests skipped");
		} finally {
			if (prior == null) {
				System.clearProperty(TestOrderConfig.CACHE_SKIP_UNCHANGED);
			} else {
				System.setProperty(TestOrderConfig.CACHE_SKIP_UNCHANGED, prior);
			}
		}
	}

	// ── Sysprop edge cases for the round-2 case-insensitive "true" check ────

	@Test
	void cacheSection_omitted_whenSyspropFalseLiteral() {
		assertCacheOmitted("false");
	}

	@Test
	void cacheSection_omitted_whenSyspropOne() {
		assertCacheOmitted("1");
	}

	@Test
	void cacheSection_omitted_whenSyspropYes() {
		assertCacheOmitted("yes");
	}

	@Test
	void cacheSection_emittedEmpty_whenSyspropTrueUppercase() {
		String prior = System.getProperty(TestOrderConfig.CACHE_SKIP_UNCHANGED);
		try {
			System.setProperty(TestOrderConfig.CACHE_SKIP_UNCHANGED, "TRUE");
			Map<String, Object> data = buildDataWithEmptyExtras();
			assertNotNull(data.get("cache"),
					"case-insensitive 'true' must enable the cache section (round-2 contract)");
		} finally {
			restore(prior);
		}
	}

	// ── Java↔TS schema-drift guard for the cache section ────────────────────

	@Test
	void cacheSection_emittedFields_matchTypescriptSchema() {
		String prior = System.getProperty(TestOrderConfig.CACHE_SKIP_UNCHANGED);
		try {
			System.setProperty(TestOrderConfig.CACHE_SKIP_UNCHANGED, "true");

			DashboardGenerator gen = new DashboardGenerator("p", "state", "index", "v");
			DashboardGenerator.RuntimeExtras extras = new DashboardGenerator.RuntimeExtras(List.of("com.A"), 500L,
					FlakyRuntimeReport.empty());

			Map<String, Object> data = gen.buildData(List.of(), Set.of(), Set.of(), new TestOrderState(),
					TestOrderState.ScoringWeights.DEFAULT, new DependencyMap(), 0L, TestOrderState.WEIGHT_DEFS,
					Map.of(), new TestHealthReport(Map.of(), 0L, 0), extras);

			@SuppressWarnings("unchecked")
			Map<String, Object> cache = (Map<String, Object>) data.get("cache");
			assertNotNull(cache);
			// TS contract: types.ts:159-164 CacheData = { enabled, skippedCount,
			// timeSavedMs, tests[] }. Any drift on either side silently empties the
			// dashboard Cache tab.
			assertEquals(Set.of("enabled", "skippedCount", "timeSavedMs", "tests"), cache.keySet(),
					"cache section keys must exactly match TS CacheData (types.ts:159-164)");

			@SuppressWarnings("unchecked")
			List<Map<String, Object>> tests = (List<Map<String, Object>>) cache.get("tests");
			assertEquals(1, tests.size());
			assertEquals(Set.of("testClass", "passStreak", "durationMs"), tests.get(0).keySet(),
					"cache entry keys must exactly match TS CacheEntry (types.ts:152-157)");
		} finally {
			restore(prior);
		}
	}

	// ── helpers ────────────────────────────────────────────────────────────

	private static void assertCacheOmitted(String syspropValue) {
		String prior = System.getProperty(TestOrderConfig.CACHE_SKIP_UNCHANGED);
		try {
			System.setProperty(TestOrderConfig.CACHE_SKIP_UNCHANGED, syspropValue);
			assertNull(buildDataWithEmptyExtras().get("cache"),
					"sysprop=\"" + syspropValue + "\" must NOT emit cache section (only case-insensitive 'true' does)");
		} finally {
			restore(prior);
		}
	}

	private static Map<String, Object> buildDataWithEmptyExtras() {
		DashboardGenerator gen = new DashboardGenerator("p", "state", "index", "v");
		DashboardGenerator.RuntimeExtras extras = new DashboardGenerator.RuntimeExtras(List.of(), 0L,
				FlakyRuntimeReport.empty());
		return gen.buildData(List.of(), Set.of(), Set.of(), new TestOrderState(), TestOrderState.ScoringWeights.DEFAULT,
				new DependencyMap(), 0L, TestOrderState.WEIGHT_DEFS, Map.of(), new TestHealthReport(Map.of(), 0L, 0),
				extras);
	}

	private static void restore(String prior) {
		if (prior == null) {
			System.clearProperty(TestOrderConfig.CACHE_SKIP_UNCHANGED);
		} else {
			System.setProperty(TestOrderConfig.CACHE_SKIP_UNCHANGED, prior);
		}
	}
}
