package me.bechberger.testorder.ml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import me.bechberger.testorder.PersistenceSupport;
import me.bechberger.testorder.TestOrderLogger;

/**
 * Per-run record of the skip-if-unchanged cache: which test classes the
 * selector chose <em>not</em> to run because their deps were unchanged AND they
 * had a sufficient pass-streak.
 *
 * <p>
 * Persisted at {@code <stateDir>/cache-runtime.txt} alongside the state file
 * and read by {@code GenerateDashboardOperation.autoLoadExtras} so the
 * dashboard's Cache tab can surface the data. Without this persistence the
 * dashboard had no way to know which tests the cache skipped — see fix 6 of
 * <code>nifty-beaming-bee.md</code>.
 * </p>
 *
 * <p>
 * Format (one record per line):
 * </p>
 *
 * <pre>
 * # test-order cache runtime
 * CACHED|com.example.FooTest|125
 * CACHED|com.example.BarTest|42
 * </pre>
 *
 * <p>
 * The number after the second pipe is the per-class EMA duration in
 * milliseconds at write time — used by the dashboard to display "time saved
 * this run". Zero is written when the duration is unknown.
 * </p>
 */
public final class CacheRuntimeReport {

	public static final String DEFAULT_FILENAME = "cache-runtime.txt";

	private final Map<String, Long> cachedTests;

	public CacheRuntimeReport(Map<String, Long> cachedTests) {
		this.cachedTests = Map.copyOf(cachedTests);
	}

	public static CacheRuntimeReport empty() {
		return new CacheRuntimeReport(Map.of());
	}

	public Map<String, Long> cachedTests() {
		return cachedTests;
	}

	public List<String> classes() {
		return new ArrayList<>(cachedTests.keySet());
	}

	public long totalDurationMs() {
		long sum = 0L;
		for (Long v : cachedTests.values()) {
			if (v != null && v > 0L) {
				sum += v;
			}
		}
		return sum;
	}

	public boolean isEmpty() {
		return cachedTests.isEmpty();
	}

	/**
	 * Reads the report at {@code reportFile}. Returns {@link #empty()} when the
	 * file is absent or unreadable (logged at warn level).
	 */
	public static CacheRuntimeReport load(Path reportFile) {
		if (reportFile == null || !Files.exists(reportFile)) {
			return empty();
		}
		Map<String, Long> entries = new LinkedHashMap<>();
		try {
			for (String raw : Files.readAllLines(reportFile, StandardCharsets.UTF_8)) {
				String line = raw.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split("\\|");
				if (parts.length >= 2 && "CACHED".equals(parts[0])) {
					long dur = 0L;
					if (parts.length >= 3) {
						try {
							dur = Long.parseLong(parts[2]);
						} catch (NumberFormatException ignored) {
							// keep dur=0 — best-effort parsing
						}
					}
					entries.put(parts[1], dur);
				}
			}
		} catch (IOException e) {
			TestOrderLogger.warn("[cache] Could not read {}: {} — treating as empty", reportFile, e.getMessage());
			return empty();
		}
		return new CacheRuntimeReport(entries);
	}

	/**
	 * Atomically writes the report to {@code reportFile} under a file lock so
	 * concurrent forks cannot lose each other's writes. When {@code cachedTests} is
	 * empty, removes any existing file (a cache run with zero skips is recorded as
	 * absence).
	 */
	public static void write(Path reportFile, Map<String, Long> cachedTests) throws IOException {
		if (reportFile == null) {
			return;
		}
		Map<String, Long> snapshot = cachedTests == null ? Map.of() : new TreeMap<>(cachedTests);
		if (reportFile.getParent() != null) {
			Files.createDirectories(reportFile.getParent());
		}
		PersistenceSupport.withFileLock(reportFile, () -> {
			if (snapshot.isEmpty()) {
				Files.deleteIfExists(reportFile);
				return null;
			}
			StringBuilder sb = new StringBuilder();
			sb.append("# test-order cache runtime\n");
			sb.append("# Format: CACHED|<class>|<durationMs>\n");
			for (Map.Entry<String, Long> e : snapshot.entrySet()) {
				sb.append("CACHED|").append(e.getKey()).append('|').append(e.getValue() == null ? 0L : e.getValue())
						.append('\n');
			}
			Path temp = PersistenceSupport.temporarySibling(reportFile);
			Files.writeString(temp, sb.toString(), StandardCharsets.UTF_8);
			PersistenceSupport.moveIntoPlace(temp, reportFile);
			return null;
		});
	}
}
