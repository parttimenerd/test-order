package me.bechberger.testorder.ml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheRuntimeReportTest {

	@TempDir
	Path tempDir;

	@Test
	void loadFromMissingFile_returnsEmpty() {
		CacheRuntimeReport report = CacheRuntimeReport.load(tempDir.resolve("missing.txt"));
		assertTrue(report.isEmpty());
		assertEquals(Map.of(), report.cachedTests());
		assertEquals(0L, report.totalDurationMs());
	}

	@Test
	void loadFromNullPath_returnsEmpty() {
		assertTrue(CacheRuntimeReport.load(null).isEmpty());
	}

	@Test
	void roundTrip() throws IOException {
		Path file = tempDir.resolve("cache-runtime.txt");
		Map<String, Long> input = new HashMap<>();
		input.put("com.A", 125L);
		input.put("com.B", 42L);
		CacheRuntimeReport.write(file, input);

		CacheRuntimeReport loaded = CacheRuntimeReport.load(file);
		assertEquals(input, loaded.cachedTests());
		assertEquals(167L, loaded.totalDurationMs());
	}

	@Test
	void emptyWrite_deletesExistingFile() throws IOException {
		Path file = tempDir.resolve("cache-runtime.txt");
		CacheRuntimeReport.write(file, Map.of("com.A", 1L));
		assertTrue(Files.exists(file));

		CacheRuntimeReport.write(file, Map.of());
		assertFalse(Files.exists(file), "empty write removes prior file (absence = zero skips)");
	}

	@Test
	void nullCachedTests_treatedAsEmpty() throws IOException {
		Path file = tempDir.resolve("cache-runtime.txt");
		CacheRuntimeReport.write(file, null);
		assertFalse(Files.exists(file), "null map → no file created");
	}

	@Test
	void ignoresCommentsAndBlanks() throws IOException {
		Path file = tempDir.resolve("cache-runtime.txt");
		Files.writeString(file, "# header\n\n  \nCACHED|com.A|10\n");

		CacheRuntimeReport loaded = CacheRuntimeReport.load(file);
		assertEquals(Map.of("com.A", 10L), loaded.cachedTests());
	}

	@Test
	void corruptLines_areSkipped_survivorsKept() throws IOException {
		Path file = tempDir.resolve("cache-runtime.txt");
		Files.writeString(file, "# header\nCACHED|com.A|notanumber\nGARBAGE\nCACHED|com.B|3\nCACHED\n");

		CacheRuntimeReport loaded = CacheRuntimeReport.load(file);
		assertEquals(0L, loaded.cachedTests().get("com.A"), "unparseable duration → 0L fallback");
		assertEquals(3L, loaded.cachedTests().get("com.B"));
		assertFalse(loaded.cachedTests().containsKey("GARBAGE"));
	}

	@Test
	void totalDurationMs_excludesNonPositiveValues() {
		Map<String, Long> entries = new HashMap<>();
		entries.put("com.A", 100L);
		entries.put("com.B", 0L);
		entries.put("com.C", -5L);
		entries.put("com.D", 50L);
		CacheRuntimeReport report = new CacheRuntimeReport(entries);
		assertEquals(150L, report.totalDurationMs(), ">0L guard excludes zero and negative entries");
	}

	@Test
	void concurrentWriters_fileStaysReadable_noCorruption() throws Exception {
		Path file = tempDir.resolve("cache-runtime.txt");
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
							Map<String, Long> entry = Map.of("com.W" + writerId, (long) (i + 1));
							CacheRuntimeReport.write(file, entry);
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

		// CacheRuntimeReport.write is last-writer-wins under the lock (not merge
		// semantics like FlakyRuntimeReport). The invariant we check is "no
		// corruption" — the file must be readable and contain a valid entry from
		// one of the writers.
		CacheRuntimeReport loaded = CacheRuntimeReport.load(file);
		assertFalse(loaded.isEmpty(), "last writer left a non-empty file");
		assertEquals(1, loaded.cachedTests().size(), "single-shot write → exactly one entry");
		String onlyKey = loaded.cachedTests().keySet().iterator().next();
		assertTrue(onlyKey.startsWith("com.W"), "loaded key belongs to one of the writers");
	}
}
