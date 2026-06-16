package me.bechberger.testorder.ml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link MLHistoryPersistence} — save/load round-trip, maxRuns
 * pruning, and sequential append.
 */
class MLHistoryPersistenceTest {

	@TempDir
	Path tempDir;

	// ── helpers ───────────────────────────────────────────────────────────────

	private static MLRunRecord run(long ts, boolean fail) {
		MLTestOutcome outcome = new MLTestOutcome("com.example.SomeTest", fail, 100L,
				fail ? "java.lang.AssertionError" : null);
		return new MLRunRecord(ts, List.of("com.app.A"), List.of(), 1, fail ? 1 : 0, List.of(outcome));
	}

	private static MLRunRecord richRun(long ts) {
		List<MLTestOutcome> outcomes = List.of(
				new MLTestOutcome("com.example.AlphaTest", true, 200L, "java.lang.RuntimeException"),
				new MLTestOutcome("com.example.BetaTest", false, 50L, null),
				new MLTestOutcome("com.example.GammaTest", true, 300L, "java.io.IOException"));
		return new MLRunRecord(ts, List.of("com.app.Service", "com.app.Repository"), List.of("com.example.HelperTest"),
				3, 2, outcomes);
	}

	// ── basic round-trip ──────────────────────────────────────────────────────

	@Test
	void emptyHistoryRoundTrip() throws IOException {
		Path f = tempDir.resolve("empty.lz4");
		MLHistoryPersistence.save(f, List.of(), 0);
		List<MLRunRecord> loaded = MLHistoryPersistence.load(f);
		assertTrue(loaded.isEmpty());
	}

	@Test
	void singleRunRoundTrip() throws IOException {
		Path f = tempDir.resolve("single.lz4");
		MLRunRecord r = run(42L, true);
		MLHistoryPersistence.save(f, List.of(r), 0);

		List<MLRunRecord> loaded = MLHistoryPersistence.load(f);
		assertEquals(1, loaded.size());
		MLRunRecord got = loaded.get(0);
		assertEquals(42L, got.timestamp());
		assertEquals(1, got.totalTests());
		assertEquals(1, got.totalFailures());
		assertEquals(1, got.outcomes().size());
		assertEquals("com.example.SomeTest", got.outcomes().get(0).testClass());
		assertTrue(got.outcomes().get(0).failed());
		assertEquals("java.lang.AssertionError", got.outcomes().get(0).failureType());
	}

	@Test
	void allOutcomeFieldsPreserved() throws IOException {
		Path f = tempDir.resolve("rich.lz4");
		MLHistoryPersistence.save(f, List.of(richRun(999L)), 0);

		List<MLRunRecord> loaded = MLHistoryPersistence.load(f);
		assertEquals(1, loaded.size());
		MLRunRecord r = loaded.get(0);
		assertEquals(999L, r.timestamp());
		assertEquals(List.of("com.app.Service", "com.app.Repository"), r.changedClasses());
		assertEquals(List.of("com.example.HelperTest"), r.changedTestClasses());
		assertEquals(3, r.totalTests());
		assertEquals(2, r.totalFailures());

		// Check each outcome
		assertEquals("com.example.AlphaTest", r.outcomes().get(0).testClass());
		assertTrue(r.outcomes().get(0).failed());
		assertEquals("java.lang.RuntimeException", r.outcomes().get(0).failureType());
		assertEquals(200L, r.outcomes().get(0).durationMs());

		assertEquals("com.example.BetaTest", r.outcomes().get(1).testClass());
		assertFalse(r.outcomes().get(1).failed());
		assertNull(r.outcomes().get(1).failureType());
		assertEquals(50L, r.outcomes().get(1).durationMs());

		assertEquals("com.example.GammaTest", r.outcomes().get(2).testClass());
		assertTrue(r.outcomes().get(2).failed());
		assertEquals("java.io.IOException", r.outcomes().get(2).failureType());
	}

	@Test
	void multipleRunsOrderPreserved() throws IOException {
		Path f = tempDir.resolve("multi.lz4");
		List<MLRunRecord> runs = List.of(run(1L, false), run(2L, true), run(3L, false));
		MLHistoryPersistence.save(f, runs, 0);

		List<MLRunRecord> loaded = MLHistoryPersistence.load(f);
		assertEquals(3, loaded.size());
		assertEquals(1L, loaded.get(0).timestamp());
		assertEquals(2L, loaded.get(1).timestamp());
		assertEquals(3L, loaded.get(2).timestamp());
	}

	// ── missing file → empty list ─────────────────────────────────────────────

	@Test
	void loadMissingFileReturnsEmpty() throws IOException {
		Path f = tempDir.resolve("nonexistent.lz4");
		assertFalse(Files.exists(f));
		List<MLRunRecord> loaded = MLHistoryPersistence.load(f);
		assertTrue(loaded.isEmpty());
	}

	// ── maxRuns pruning ───────────────────────────────────────────────────────

	@Test
	void saveWithMaxRunsKeepsMostRecent() throws IOException {
		Path f = tempDir.resolve("pruned.lz4");
		List<MLRunRecord> runs = List.of(run(1L, false), run(2L, true), run(3L, false), run(4L, true), run(5L, false));
		MLHistoryPersistence.save(f, runs, 3);

		List<MLRunRecord> loaded = MLHistoryPersistence.load(f);
		assertEquals(3, loaded.size());
		// Should keep the 3 most recent
		assertEquals(3L, loaded.get(0).timestamp());
		assertEquals(4L, loaded.get(1).timestamp());
		assertEquals(5L, loaded.get(2).timestamp());
	}

	@Test
	void saveWithMaxRunsZeroKeepsAll() throws IOException {
		Path f = tempDir.resolve("unlimited.lz4");
		List<MLRunRecord> runs = List.of(run(1L, false), run(2L, true), run(3L, false));
		MLHistoryPersistence.save(f, runs, 0);

		List<MLRunRecord> loaded = MLHistoryPersistence.load(f);
		assertEquals(3, loaded.size());
	}

	@Test
	void saveWithMaxRunsLargerThanListKeepsAll() throws IOException {
		Path f = tempDir.resolve("no-prune.lz4");
		List<MLRunRecord> runs = List.of(run(1L, false), run(2L, true));
		MLHistoryPersistence.save(f, runs, 100);

		List<MLRunRecord> loaded = MLHistoryPersistence.load(f);
		assertEquals(2, loaded.size());
	}

	// ── append ────────────────────────────────────────────────────────────────

	@Test
	void appendCreatesFileWhenMissing() throws IOException {
		Path f = tempDir.resolve("appended.lz4");
		assertFalse(Files.exists(f));

		MLHistoryPersistence.append(f, run(10L, false), 0);

		List<MLRunRecord> loaded = MLHistoryPersistence.load(f);
		assertEquals(1, loaded.size());
		assertEquals(10L, loaded.get(0).timestamp());
	}

	@Test
	void appendAddsToExistingHistory() throws IOException {
		Path f = tempDir.resolve("append2.lz4");
		MLHistoryPersistence.save(f, List.of(run(1L, false), run(2L, true)), 0);

		MLHistoryPersistence.append(f, run(3L, false), 0);

		List<MLRunRecord> loaded = MLHistoryPersistence.load(f);
		assertEquals(3, loaded.size());
		assertEquals(3L, loaded.get(2).timestamp());
	}

	@Test
	void appendPrunesWhenMaxRunsExceeded() throws IOException {
		Path f = tempDir.resolve("append-prune.lz4");
		List<MLRunRecord> initial = List.of(run(1L, false), run(2L, true), run(3L, false));
		MLHistoryPersistence.save(f, initial, 0);

		// maxRuns=3: after appending run(4), list has 4 entries → prune to 3
		MLHistoryPersistence.append(f, run(4L, true), 3);

		List<MLRunRecord> loaded = MLHistoryPersistence.load(f);
		assertEquals(3, loaded.size());
		assertEquals(2L, loaded.get(0).timestamp());
		assertEquals(3L, loaded.get(1).timestamp());
		assertEquals(4L, loaded.get(2).timestamp());
	}

	// ── overwrite semantics ───────────────────────────────────────────────────

	@Test
	void saveOverwritesPreviousFile() throws IOException {
		Path f = tempDir.resolve("overwrite.lz4");
		MLHistoryPersistence.save(f, List.of(run(1L, false), run(2L, true)), 0);
		MLHistoryPersistence.save(f, List.of(run(99L, false)), 0);

		List<MLRunRecord> loaded = MLHistoryPersistence.load(f);
		assertEquals(1, loaded.size());
		assertEquals(99L, loaded.get(0).timestamp());
	}

	// ── large history stress test ─────────────────────────────────────────────

	@Test
	void largeHistoryRoundTrip() throws IOException {
		Path f = tempDir.resolve("large.lz4");
		List<MLRunRecord> runs = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			runs.add(run((long) i, i % 3 == 0));
		}
		MLHistoryPersistence.save(f, runs, 0);

		List<MLRunRecord> loaded = MLHistoryPersistence.load(f);
		assertEquals(200, loaded.size());
		for (int i = 0; i < 200; i++) {
			assertEquals((long) i, loaded.get(i).timestamp());
			assertEquals(i % 3 == 0 ? 1 : 0, loaded.get(i).totalFailures());
		}
	}
}
