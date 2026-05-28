package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PartialRunAggregatorTest {

	@TempDir
	Path dir;

	@AfterEach
	void cleanup() {
		TestOrderState.resetPending();
	}

	// ── helpers ───────────────────────────────────────────────────────────

	private TestOrderState.RunRecord makeRecord(String... testClasses) {
		List<TestOrderState.TestOutcome> outcomes = new ArrayList<>();
		for (String tc : testClasses) {
			outcomes.add(new TestOrderState.TestOutcome(tc, 10, false, false, 2, 5, 0.5, true, false, false, 0.1, 1.2,
					false));
		}
		return new TestOrderState.RunRecord(System.currentTimeMillis(), testClasses.length, 0, -1, 1.0, outcomes);
	}

	private TestOrderState.RunRecord makeFailingRecord(String failClass) {
		TestOrderState.TestOutcome pass = new TestOrderState.TestOutcome("com.test.Pass", 5, false, false, 0, 0, 0.0,
				false, false, false, 0.0, 0.0, false);
		TestOrderState.TestOutcome fail = new TestOrderState.TestOutcome(failClass, 8, false, false, 1, 2, 1.5, false,
				false, true, 0.0, 0.0, false);
		return new TestOrderState.RunRecord(System.currentTimeMillis(), 2, 1, 1, 0.5, List.of(fail, pass)); // fail
																											// first by
																											// score,
																											// pass
																											// second
	}

	// ── round-trip ─────────────────────────────────────────────────────────

	@Test
	void singleForkRoundTrip() throws IOException {
		Path pendingDir = dir.resolve("pending");
		Path stateFile = dir.resolve("state.lz4");
		String buildId = "build-001";

		TestOrderState.RunRecord record = makeRecord("com.test.FooTest", "com.test.BarTest");
		PartialRunAggregator.writePartial(pendingDir, buildId, record, false);

		// Exactly one .part file should be written
		try (var stream = Files.list(pendingDir)) {
			assertEquals(1, stream.filter(p -> p.toString().endsWith(".part")).count());
		}

		boolean merged = PartialRunAggregator.mergeAndApply(pendingDir, buildId, stateFile);
		assertTrue(merged);

		// Partial files cleaned up after merge
		try (var stream = Files.list(pendingDir)) {
			assertEquals(0, stream.filter(p -> p.toString().endsWith(".part")).count());
		}

		// State file should now contain the run record
		TestOrderState loaded = TestOrderState.load(stateFile);
		assertEquals(1, loaded.runs().size());
		assertEquals(2, loaded.runs().get(0).totalTests());
		assertEquals(0, loaded.runs().get(0).totalFailures());
		// runsSinceLearn should have been incremented (non-learn run)
		assertEquals(1, loaded.runsSinceLearn());
	}

	@Test
	void multipleForksAreMergedIntoOneRecord() throws IOException {
		Path pendingDir = dir.resolve("pending");
		Path stateFile = dir.resolve("state.lz4");
		String buildId = "build-multi";

		// Simulate 3 forks each with 2 tests
		PartialRunAggregator.writePartial(pendingDir, buildId, makeRecord("com.test.A", "com.test.B"), false);
		PartialRunAggregator.writePartial(pendingDir, buildId, makeRecord("com.test.C", "com.test.D"), false);
		PartialRunAggregator.writePartial(pendingDir, buildId, makeRecord("com.test.E", "com.test.F"), false);

		boolean merged = PartialRunAggregator.mergeAndApply(pendingDir, buildId, stateFile);
		assertTrue(merged);

		TestOrderState loaded = TestOrderState.load(stateFile);
		assertEquals(1, loaded.runs().size(), "All forks should merge to exactly one RunRecord");
		assertEquals(6, loaded.runs().get(0).totalTests(), "All 6 test outcomes should be merged");
	}

	@Test
	void learnRunDoesNotIncrementRunsSinceLearn() throws IOException {
		Path pendingDir = dir.resolve("pending");
		Path stateFile = dir.resolve("state.lz4");
		String buildId = "build-learn";

		PartialRunAggregator.writePartial(pendingDir, buildId, makeRecord("com.test.T"), true /* isLearnRun */);
		PartialRunAggregator.mergeAndApply(pendingDir, buildId, stateFile);

		TestOrderState loaded = TestOrderState.load(stateFile);
		assertEquals(0, loaded.runsSinceLearn(), "Learn-mode run must not increment runsSinceLearn");
	}

	@Test
	void failureInForkSurvivesMerge() throws IOException {
		Path pendingDir = dir.resolve("pending");
		Path stateFile = dir.resolve("state.lz4");
		String buildId = "build-fail";

		PartialRunAggregator.writePartial(pendingDir, buildId, makeFailingRecord("com.test.Broken"), false);
		PartialRunAggregator.mergeAndApply(pendingDir, buildId, stateFile);

		TestOrderState loaded = TestOrderState.load(stateFile);
		assertEquals(1, loaded.runs().get(0).totalFailures());
	}

	// ── edge cases ─────────────────────────────────────────────────────────

	@Test
	void mergeReturnsFalseWhenNoPendingDir() throws IOException {
		Path nonexistent = dir.resolve("no-such-dir");
		assertFalse(PartialRunAggregator.mergeAndApply(nonexistent, "any-id", dir.resolve("state.lz4")));
	}

	@Test
	void mergeReturnsFalseWhenNoMatchingPartFiles() throws IOException {
		Path pendingDir = dir.resolve("pending");
		Files.createDirectories(pendingDir);
		// Write a partial for a different buildId
		PartialRunAggregator.writePartial(pendingDir, "other-build", makeRecord("com.T"), false);

		assertFalse(PartialRunAggregator.mergeAndApply(pendingDir, "target-build", dir.resolve("state.lz4")));
	}

	@Test
	void malformedPartFileIsSkippedGracefully() throws IOException {
		Path pendingDir = dir.resolve("pending");
		Files.createDirectories(pendingDir);
		String buildId = "build-corrupt";
		// Write a corrupt partial file
		Files.writeString(pendingDir.resolve(buildId + "-bad.part"), "not valid content\n");
		// Also write a valid one
		PartialRunAggregator.writePartial(pendingDir, buildId, makeRecord("com.test.Valid"), false);

		// Should still merge the valid partial successfully
		boolean merged = PartialRunAggregator.mergeAndApply(pendingDir, buildId, dir.resolve("state.lz4"));
		assertTrue(merged);
	}

	@Test
	void cleanStalePartialsRemovesOldFiles() throws IOException {
		Path pendingDir = dir.resolve("pending");
		Files.createDirectories(pendingDir);

		// Write a stale file with an old modification time
		Path staleFile = pendingDir.resolve("old-build-stale.part");
		Files.writeString(staleFile, "buildId=old-build\n");
		staleFile.toFile().setLastModified(System.currentTimeMillis() - 60_000L);

		// Write a recent file (within threshold)
		Path recentFile = pendingDir.resolve("new-build-recent.part");
		Files.writeString(recentFile, "buildId=new-build\n");

		// Clean files older than 30 seconds
		PartialRunAggregator.cleanStalePartials(pendingDir, 30_000L);

		assertFalse(Files.exists(staleFile), "Stale file (60s old) should be removed");
		assertTrue(Files.exists(recentFile), "Recent file should be kept");
	}

	@Test
	void cleanStalePartialsIsNoOpWhenDirMissing() {
		// Must not throw
		assertDoesNotThrow(() -> PartialRunAggregator.cleanStalePartials(dir.resolve("no-such-dir"), 1000L));
	}

	@Test
	void outcomeFieldsPreservedAcrossRoundTrip() throws IOException {
		Path pendingDir = dir.resolve("pending");
		Path stateFile = dir.resolve("state.lz4");
		String buildId = "build-fields";

		// Outcome with non-trivial fields
		var outcome = new TestOrderState.TestOutcome("com.test.Complex", 42, true, true, 7, 10, 3.5, true, false, true,
				2.1, 0.8, true);
		var record = new TestOrderState.RunRecord(1000L, 1, 1, 0, 0.5, List.of(outcome));

		PartialRunAggregator.writePartial(pendingDir, buildId, record, false);
		PartialRunAggregator.mergeAndApply(pendingDir, buildId, stateFile);

		TestOrderState loaded = TestOrderState.load(stateFile);
		assertEquals(1, loaded.runs().size());
		var merged = loaded.runs().get(0);
		assertEquals(1, merged.totalTests());
		assertEquals(1, merged.totalFailures());

		var o = merged.outcomes().get(0);
		assertEquals("com.test.Complex", o.testClass());
		assertEquals(42, o.totalScore());
		assertTrue(o.isNew());
		assertTrue(o.isChanged());
		assertEquals(7, o.depOverlap());
		assertEquals(10, o.depTotal());
		assertEquals(3.5, o.failScore(), 1e-9);
		assertTrue(o.isFast());
		assertFalse(o.isSlow());
		assertTrue(o.failed());
		assertEquals(2.1, o.complexityOverlap(), 1e-9);
		assertEquals(0.8, o.speedRatio(), 1e-9);
		assertTrue(o.hasStaticFieldOverlap());
	}
}
