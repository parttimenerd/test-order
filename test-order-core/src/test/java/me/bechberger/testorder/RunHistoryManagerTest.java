package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

	private static TestOrderState.RunRecord run(long timestamp) {
		return new TestOrderState.RunRecord(timestamp, 0, 0, -1, 0.0, List.of());
	}
}
