package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Schema-stability guard for Phase C facade refactor.
 *
 * <p>
 * Loads a real {@code state.lz4} captured before any extraction, asserts its
 * persisted JSON tree matches what
 * {@link TestOrderState#toPersistedRoot(boolean)} produces, and round-trips it
 * through {@link TestOrderState#saveAggregatedFork(Path)} to confirm load →
 * save → load preserves the same logical tree. Must pass after every Phase C
 * sub-phase commit; if it fails, the refactor has shifted persistence semantics
 * and the change must be reverted or fixed.
 *
 * <p>
 * The comparison is on parsed JSON (not raw bytes) because LZ4 compression and
 * key-ordering of nested maps can vary, but the logical shape and values must
 * not.
 */
final class StateGoldenFileTest {

	private static final String GOLDEN_RESOURCE = "/golden/state-v1.lz4";

	@Test
	void goldenStateLoadsAndPersistsToSameTree(@TempDir Path tmp) throws Exception {
		Path goldenCopy = tmp.resolve("state.lz4");
		try (InputStream in = StateGoldenFileTest.class.getResourceAsStream(GOLDEN_RESOURCE)) {
			assertNotNull(in, "golden resource missing: " + GOLDEN_RESOURCE);
			Files.copy(in, goldenCopy);
		}

		TestOrderState loaded = TestOrderState.load(goldenCopy);
		Map<String, Object> persisted = loaded.toPersistedRoot(false);

		assertEquals(1, persisted.get("schemaVersion"), "schemaVersion must remain v1");
		Object weights = persisted.get("weights");
		assertTrue(weights instanceof Map, "weights must be a map");
		assertNotNull(persisted.get("durations"), "durations key must be present");

		List<String> expectedKeyOrder = List.of("schemaVersion", "config", "weights", "durations", "durationVariances",
				"failureScores", "runs", "methodDurations", "methodDurationVariances", "methodFailureScores",
				"methodWeights", "killRates", "mutationTotalMutants", "mutationTotalKilled");
		List<String> actualKeys = new java.util.ArrayList<>(persisted.keySet());
		int prev = -1;
		for (String k : actualKeys) {
			int idx = expectedKeyOrder.indexOf(k);
			assertTrue(idx >= 0, "unexpected top-level key: " + k);
			assertTrue(idx > prev, "key out of canonical order: " + k + " (got " + actualKeys + ")");
			prev = idx;
		}
	}

	@Test
	void roundTripPreservesPersistedTree(@TempDir Path tmp) throws Exception {
		Path goldenCopy = tmp.resolve("state.lz4");
		try (InputStream in = StateGoldenFileTest.class.getResourceAsStream(GOLDEN_RESOURCE)) {
			assertNotNull(in, "golden resource missing: " + GOLDEN_RESOURCE);
			Files.copy(in, goldenCopy);
		}

		TestOrderState first = TestOrderState.load(goldenCopy);
		Map<String, Object> firstTree = normalize(first.toPersistedRoot(false));

		Path resaved = tmp.resolve("resaved.lz4");
		first.saveAggregatedFork(resaved);

		TestOrderState second = TestOrderState.load(resaved);
		Map<String, Object> secondTree = normalize(second.toPersistedRoot(false));

		assertEquals(firstTree, secondTree, "load → save → load must preserve persisted tree");
	}

	/**
	 * Recursively converts maps to a deterministic form for equality comparison.
	 * Numbers are coerced to a canonical type so int↔long↔double round-trips
	 * through JSON parsing don't cause spurious failures.
	 */
	private static Map<String, Object> normalize(Map<String, Object> in) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : in.entrySet()) {
			out.put(e.getKey(), normalizeValue(e.getValue()));
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Object normalizeValue(Object v) {
		if (v instanceof Map<?, ?> m) {
			Map<String, Object> sub = new LinkedHashMap<>();
			for (Map.Entry<?, ?> e : m.entrySet()) {
				sub.put(String.valueOf(e.getKey()), normalizeValue(e.getValue()));
			}
			return sub;
		}
		if (v instanceof List<?> list) {
			List<Object> sub = new java.util.ArrayList<>(list.size());
			for (Object item : list) {
				sub.add(normalizeValue(item));
			}
			return sub;
		}
		if (v instanceof Number n) {
			double d = n.doubleValue();
			if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
				return (long) d;
			}
			return d;
		}
		return v;
	}
}
