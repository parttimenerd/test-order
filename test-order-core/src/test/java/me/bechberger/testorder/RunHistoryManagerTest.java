package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class RunHistoryManagerTest {

	@Test
	void addCapsRunHistoryAtConfiguredMaximum() {
		RunHistoryManager manager = new RunHistoryManager();

		for (int i = 0; i < 12; i++) {
			manager.add(run(i), 5);
		}

		assertEquals(5, manager.runs().size());
	}

	@Test
	void thinRunHistoryKeepsMostRecentRuns() {
		List<TestOrderState.RunRecord> source = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			source.add(run(i));
		}

		List<TestOrderState.RunRecord> thinned = RunHistoryManager.thinRunHistory(source, 6);

		assertEquals(6, thinned.size());
		assertEquals(7L, thinned.get(3).timestamp());
		assertEquals(8L, thinned.get(4).timestamp());
		assertEquals(9L, thinned.get(5).timestamp());
	}

	@Test
	void thinRunHistoryRejectsNonPositiveMaxRuns() {
		assertThrows(IllegalArgumentException.class, () -> RunHistoryManager.thinRunHistory(List.of(run(1)), 0));
	}

	@Test
	void thinRunHistoryExactlyAtMaxKeepsAll() {
		List<TestOrderState.RunRecord> source = List.of(run(1), run(2), run(3));
		List<TestOrderState.RunRecord> thinned = RunHistoryManager.thinRunHistory(source, 3);
		assertEquals(3, thinned.size());
	}

	@Test
	void thinRunHistoryMaxRunsOneMaintainsLastRun() {
		List<TestOrderState.RunRecord> source = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			source.add(run(i));
		}
		List<TestOrderState.RunRecord> thinned = RunHistoryManager.thinRunHistory(source, 1);
		assertEquals(1, thinned.size());
		assertEquals(4L, thinned.get(0).timestamp()); // last run retained
	}

	@Test
	void thinRunHistoryMaxRunsTwoWithManyRuns() {
		List<TestOrderState.RunRecord> source = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			source.add(run(i));
		}
		List<TestOrderState.RunRecord> thinned = RunHistoryManager.thinRunHistory(source, 2);
		assertEquals(2, thinned.size());
		// Last run must be in result
		assertEquals(9L, thinned.get(thinned.size() - 1).timestamp());
	}

	@Test
	void thinRunHistoryOrderPreserved() {
		List<TestOrderState.RunRecord> source = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			source.add(run(i));
		}
		List<TestOrderState.RunRecord> thinned = RunHistoryManager.thinRunHistory(source, 4);
		assertEquals(4, thinned.size());
		// Result must be in chronological order (ascending timestamps)
		for (int i = 0; i < thinned.size() - 1; i++) {
			assertTrue(thinned.get(i).timestamp() < thinned.get(i + 1).timestamp(),
					"Runs must be in chronological order");
		}
	}

	private static TestOrderState.RunRecord run(long timestamp) {
		return new TestOrderState.RunRecord(timestamp, 0, 0, -1, 0.0, List.of());
	}
}
