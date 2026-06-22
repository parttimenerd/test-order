package me.bechberger.testorder.ml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlakyRuntimeReportTest {

	@TempDir
	Path tempDir;

	@Test
	void loadFromMissingFile_returnsEmpty() {
		FlakyRuntimeReport report = FlakyRuntimeReport.load(tempDir.resolve("missing.txt"));
		assertTrue(report.isEmpty());
		assertEquals(Map.of(), report.retryCounts());
		assertEquals(Set.of(), report.quarantined());
	}

	@Test
	void roundTrip() throws IOException {
		Path file = tempDir.resolve("flaky-runtime.txt");
		FlakyRuntimeReport.write(file, Map.of("com.A", 2, "com.B", 1), Set.of("com.A"));

		FlakyRuntimeReport loaded = FlakyRuntimeReport.load(file);
		assertEquals(Map.of("com.A", 2, "com.B", 1), loaded.retryCounts());
		assertEquals(Set.of("com.A"), loaded.quarantined());
	}

	@Test
	void mergeRetryCounts_takesMax() throws IOException {
		Path file = tempDir.resolve("flaky-runtime.txt");
		FlakyRuntimeReport.write(file, Map.of("com.A", 2), Set.of());
		FlakyRuntimeReport.write(file, Map.of("com.A", 1), Set.of());

		FlakyRuntimeReport loaded = FlakyRuntimeReport.load(file);
		assertEquals(2, loaded.retryCounts().get("com.A"), "max wins across writes");
	}

	@Test
	void mergeQuarantines_union() throws IOException {
		Path file = tempDir.resolve("flaky-runtime.txt");
		FlakyRuntimeReport.write(file, Map.of(), Set.of("com.A"));
		FlakyRuntimeReport.write(file, Map.of(), Set.of("com.B"));

		FlakyRuntimeReport loaded = FlakyRuntimeReport.load(file);
		assertEquals(Set.of("com.A", "com.B"), loaded.quarantined());
	}

	@Test
	void writeNothing_whenOtherIsEmpty() throws IOException {
		Path file = tempDir.resolve("flaky-runtime.txt");
		FlakyRuntimeReport.mergeAndWrite(file, FlakyRuntimeReport.empty());
		assertFalse(Files.exists(file), "no file written for empty input");
	}

	@Test
	void ignoresCommentsAndBlanks() throws IOException {
		Path file = tempDir.resolve("flaky-runtime.txt");
		Files.writeString(file, "# header\n\n  \nRETRY|com.A|3\nQUARANTINE|com.B\n");

		FlakyRuntimeReport loaded = FlakyRuntimeReport.load(file);
		assertEquals(3, loaded.retryCounts().get("com.A"));
		assertEquals(Set.of("com.B"), loaded.quarantined());
	}

	@Test
	void entriesForTesting_sortedDeterministically() throws IOException {
		Path file = tempDir.resolve("flaky-runtime.txt");
		FlakyRuntimeReport.write(file, Map.of("com.Z", 1, "com.A", 2), Set.of("com.Q", "com.B"));

		List<String> entries = FlakyRuntimeReport.load(file).entriesForTesting();
		assertEquals(List.of("RETRY|com.A|2", "RETRY|com.Z|1", "QUARANTINE|com.B", "QUARANTINE|com.Q"), entries);
	}

	@Test
	void corruptLines_areSkipped_survivorsKept() throws IOException {
		Path file = tempDir.resolve("flaky-runtime.txt");
		Files.writeString(file, "# header\nRETRY|com.A|notanumber\nGARBAGE\nRETRY|com.B|3\nQUARANTINE|com.C\n");

		FlakyRuntimeReport loaded = FlakyRuntimeReport.load(file);
		assertEquals(Map.of("com.B", 3), loaded.retryCounts());
		assertEquals(Set.of("com.C"), loaded.quarantined());
	}

	@Test
	void mergeAndWrite_dropsEntriesNotInCurrentFlakySet() throws IOException {
		Path file = tempDir.resolve("flaky-runtime.txt");
		FlakyRuntimeReport.write(file, Map.of("com.Stale", 2, "com.Still", 1),
				Set.of("com.OldQuarantine", "com.Still"));

		FlakyRuntimeReport.mergeAndWrite(file, FlakyRuntimeReport.empty(), Set.of("com.Still"));

		FlakyRuntimeReport loaded = FlakyRuntimeReport.load(file);
		assertEquals(Map.of("com.Still", 1), loaded.retryCounts(),
				"retry entry for class no longer FLAKY should be dropped");
		assertEquals(Set.of("com.Still"), loaded.quarantined(),
				"quarantine entry for class no longer FLAKY should be dropped");
	}

	@Test
	void mergeAndWrite_nullCurrentFlakySet_keepsAllEntries() throws IOException {
		Path file = tempDir.resolve("flaky-runtime.txt");
		FlakyRuntimeReport.write(file, Map.of("com.A", 2), Set.of("com.B"));

		FlakyRuntimeReport.mergeAndWrite(file, FlakyRuntimeReport.empty(), null);

		FlakyRuntimeReport loaded = FlakyRuntimeReport.load(file);
		assertEquals(Map.of("com.A", 2), loaded.retryCounts());
		assertEquals(Set.of("com.B"), loaded.quarantined());
	}

	@Test
	void concurrentWriters_noLostUpdates() throws Exception {
		Path file = tempDir.resolve("flaky-runtime.txt");
		int writers = 8;
		int perWriter = 25;
		ExecutorService pool = Executors.newFixedThreadPool(writers);
		CountDownLatch start = new CountDownLatch(1);
		List<Throwable> errors = new ArrayList<>();
		try {
			for (int w = 0; w < writers; w++) {
				final int writerId = w;
				pool.submit(() -> {
					try {
						start.await();
						for (int i = 0; i < perWriter; i++) {
							Map<String, Integer> retries = new HashMap<>();
							retries.put("com.W" + writerId, i + 1);
							FlakyRuntimeReport.write(file, retries, Set.of("com.W" + writerId));
						}
					} catch (Throwable t) {
						synchronized (errors) {
							errors.add(t);
						}
					}
				});
			}
			start.countDown();
			pool.shutdown();
			assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "writers did not finish");
		} finally {
			pool.shutdownNow();
		}
		assertTrue(errors.isEmpty(), "writer threads threw: " + errors);

		FlakyRuntimeReport loaded = FlakyRuntimeReport.load(file);
		for (int w = 0; w < writers; w++) {
			String key = "com.W" + w;
			assertEquals(perWriter, loaded.retryCounts().get(key),
					"max attempt for " + key + " survived (no lost updates)");
			assertTrue(loaded.quarantined().contains(key), "quarantine for " + key + " survived");
		}
	}
}
