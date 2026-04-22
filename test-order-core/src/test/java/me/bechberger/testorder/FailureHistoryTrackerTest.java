package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

class FailureHistoryTrackerTest {

	@Test
	void failureScoreIncludesPendingAndHistoricalData() {
		FailureHistoryTracker tracker = new FailureHistoryTracker();
		tracker.loadFailureScore("com.test.A", 2.0);
		tracker.recordFailure("com.test.A");
		tracker.recordFailure("com.test.A");

		assertEquals(4.0, tracker.failureScore("com.test.A"));
	}

	@Test
	void mergeForSaveAppliesDecayAndPruning() {
		FailureHistoryTracker tracker = new FailureHistoryTracker();
		tracker.loadFailureScore("keep", 2.0);
		tracker.loadFailureScore("drop", 0.5);
		tracker.recordFailure("new");

		FailureHistoryTracker.PersistedScores persisted = tracker.mergeForSave(true, 0.5, 0.5, 0.6,
				Logger.getAnonymousLogger());

		assertEquals(2, persisted.failureScores().size());
		assertEquals(1.0, persisted.failureScores().get("keep"));
		assertEquals(1.0, persisted.failureScores().get("new"));
	}

	@Test
	void methodFailureScoreIncludesPendingAndHistoricalData() {
		FailureHistoryTracker tracker = new FailureHistoryTracker();
		tracker.loadMethodFailureScore("com.test.A#testOne", 3.0);
		tracker.recordMethodFailure("com.test.A", "testOne");

		assertEquals(4.0, tracker.methodFailureScore("com.test.A", "testOne"));
	}

	@Test
	void pruneToActiveClassesRemovesStaleEntries() {
		FailureHistoryTracker tracker = new FailureHistoryTracker();
		tracker.loadFailureScore("active.Test", 1.0);
		tracker.loadFailureScore("stale.Test", 2.0);
		tracker.loadMethodFailureScore("active.Test#testA", 3.0);
		tracker.loadMethodFailureScore("stale.Test#testB", 4.0);

		tracker.pruneToActiveClasses(Set.of("active.Test"));

		assertEquals(Set.of("active.Test"), tracker.failureScores().keySet());
		assertEquals(Set.of("active.Test#testA"), tracker.methodFailureScores().keySet());
	}

	@Test
	void applyPersistedReplacesHistoricalAndClearsPending() {
		FailureHistoryTracker tracker = new FailureHistoryTracker();
		tracker.loadFailureScore("old", 1.0);
		tracker.recordFailure("old");
		tracker.loadMethodFailureScore("A#old", 2.0);
		tracker.recordMethodFailure("A", "old");

		FailureHistoryTracker.PersistedScores persisted = new FailureHistoryTracker.PersistedScores(
				java.util.Map.of("new", 5.0), java.util.Map.of("A#new", 6.0));
		tracker.applyPersisted(persisted);

		assertEquals(5.0, tracker.failureScore("new"));
		assertEquals(0.0, tracker.failureScore("old"));
		assertEquals(6.0, tracker.methodFailureScore("A", "new"));
		assertEquals(0.0, tracker.methodFailureScore("A", "old"));
	}
}
