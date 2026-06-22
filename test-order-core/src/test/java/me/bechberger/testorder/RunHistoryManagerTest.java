package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

	/**
	 * Stresses {@code TestOrderState.runs()} (delegated to
	 * {@code RunHistoryStorage.runs()}) under a concurrent writer to verify the
	 * round-1 fix that swapped the live-view-under-monitor footgun for a defensive
	 * copy. A regression to the live-view shape would surface here as
	 * {@code ConcurrentModificationException} during iteration.
	 */
	@Test
	void runs_concurrentReadsDuringWritesProduceConsistentSnapshots() throws Exception {
		TestOrderState state = new TestOrderState();
		int iterations = 500;
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		AtomicReference<Throwable> readerError = new AtomicReference<>();
		try {
			pool.submit(() -> {
				try {
					start.await();
					for (int i = 0; i < iterations; i++) {
						state.addRunRecord(run(i));
					}
				} catch (Throwable t) {
					readerError.compareAndSet(null, t);
				}
			});
			pool.submit(() -> {
				try {
					start.await();
					for (int i = 0; i < iterations; i++) {
						List<TestOrderState.RunRecord> snapshot = state.runs();
						for (TestOrderState.RunRecord r : snapshot) {
							assertNotNull(r, "snapshot entry must not be null mid-iteration");
						}
					}
				} catch (Throwable t) {
					readerError.compareAndSet(null, t);
				}
			});
			start.countDown();
			pool.shutdown();
			assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "threads did not finish");
		} finally {
			pool.shutdownNow();
		}
		assertTrue(readerError.get() == null, "reader/writer threw: " + readerError.get());
		assertTrue(state.runs().size() > 0, "writer produced at least some runs");
		assertTrue(state.runs().size() <= state.historyMaxRuns(),
				"final size respects history cap (" + state.historyMaxRuns() + ")");
	}
}
