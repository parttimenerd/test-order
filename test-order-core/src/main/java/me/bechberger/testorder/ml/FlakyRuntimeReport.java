package me.bechberger.testorder.ml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import me.bechberger.testorder.PersistenceSupport;
import me.bechberger.testorder.TestOrderLogger;

/**
 * Per-run record of the {@link FlakyRetryExtension}'s activity: which test
 * classes were retried (and how many attempts) and which were quarantined.
 *
 * <p>
 * Stored as a small text file at {@code .test-order/flaky-runtime.txt} by the
 * runtime extension, then read by the Maven/Gradle plugin to surface the data
 * in the CI summary and dashboard.
 * </p>
 *
 * <p>
 * Format (one record per line):
 * </p>
 *
 * <pre>
 * # test-order flaky runtime
 * RETRY|com.example.FooTest|2
 * QUARANTINE|com.example.BarTest
 * </pre>
 *
 * <p>
 * {@link #mergeAndWrite(Path, FlakyRuntimeReport, Set)} serialises the
 * read-merge-write cycle under {@link PersistenceSupport#withFileLock} so that
 * parallel Surefire forks and {@code gradle test --parallel} writers do not
 * lose updates. Retry counts take the max across forks; quarantines union.
 * </p>
 */
public final class FlakyRuntimeReport {

	public static final String DEFAULT_FILENAME = "flaky-runtime.txt";

	private final Map<String, Integer> retryCounts;
	private final Set<String> quarantined;

	public FlakyRuntimeReport(Map<String, Integer> retryCounts, Set<String> quarantined) {
		this.retryCounts = Map.copyOf(retryCounts);
		this.quarantined = Set.copyOf(quarantined);
	}

	public static FlakyRuntimeReport empty() {
		return new FlakyRuntimeReport(Map.of(), Set.of());
	}

	public Map<String, Integer> retryCounts() {
		return retryCounts;
	}

	public Set<String> quarantined() {
		return quarantined;
	}

	public boolean isEmpty() {
		return retryCounts.isEmpty() && quarantined.isEmpty();
	}

	/**
	 * Loads the report at {@code reportFile}. Returns {@link #empty()} if the file
	 * does not exist. On IO failure, logs a warning and still returns
	 * {@link #empty()} so callers can continue — flaky-runtime data is best-effort.
	 */
	public static FlakyRuntimeReport load(Path reportFile) {
		if (reportFile == null || !Files.exists(reportFile)) {
			return empty();
		}
		Map<String, Integer> retries = new HashMap<>();
		Set<String> quarantined = new LinkedHashSet<>();
		boolean sawMalformed = false;
		try {
			for (String raw : Files.readAllLines(reportFile, StandardCharsets.UTF_8)) {
				String line = raw.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split("\\|");
				if (parts.length >= 3 && "RETRY".equals(parts[0])) {
					try {
						int n = Integer.parseInt(parts[2]);
						retries.merge(parts[1], n, Math::max);
					} catch (NumberFormatException ignored) {
						sawMalformed = true;
					}
				} else if (parts.length >= 2 && "QUARANTINE".equals(parts[0])) {
					quarantined.add(parts[1]);
				} else {
					sawMalformed = true;
				}
			}
		} catch (IOException e) {
			TestOrderLogger.warn("[flaky] Could not read {}: {} — treating as empty", reportFile, e.getMessage());
			return empty();
		}
		if (sawMalformed) {
			TestOrderLogger.warn("[flaky] Skipped malformed lines in {} — surviving entries kept", reportFile);
		}
		return new FlakyRuntimeReport(retries, quarantined);
	}

	/**
	 * @see #mergeAndWrite(Path, FlakyRuntimeReport, Set)
	 */
	public static void mergeAndWrite(Path reportFile, FlakyRuntimeReport other) throws IOException {
		mergeAndWrite(reportFile, other, null);
	}

	/**
	 * Merges {@code other} into the on-disk report at {@code reportFile} and
	 * rewrites it atomically under a file lock so concurrent forks/threads cannot
	 * lose each other's writes.
	 *
	 * <p>
	 * When {@code currentFlakyClasses} is non-null, retry and quarantine entries
	 * whose class is <em>not</em> in that set are dropped from the rewritten file.
	 * This is how stale quarantines age out: once the ML report no longer
	 * classifies a test as FLAKY, the next persist drops it.
	 * </p>
	 */
	public static void mergeAndWrite(Path reportFile, FlakyRuntimeReport other, Set<String> currentFlakyClasses)
			throws IOException {
		if (reportFile == null) {
			return;
		}
		FlakyRuntimeReport effectiveOther = other == null ? empty() : other;
		if (effectiveOther.isEmpty() && currentFlakyClasses == null) {
			return;
		}
		if (reportFile.getParent() != null) {
			Files.createDirectories(reportFile.getParent());
		}
		PersistenceSupport.withFileLock(reportFile, () -> {
			FlakyRuntimeReport existing = load(reportFile);
			Map<String, Integer> merged = new TreeMap<>(existing.retryCounts);
			for (Map.Entry<String, Integer> e : effectiveOther.retryCounts.entrySet()) {
				merged.merge(e.getKey(), e.getValue(), Math::max);
			}
			Set<String> quarantined = new TreeSet<>(existing.quarantined);
			quarantined.addAll(effectiveOther.quarantined);

			if (currentFlakyClasses != null) {
				merged.keySet().retainAll(currentFlakyClasses);
				quarantined.retainAll(currentFlakyClasses);
			}

			if (merged.isEmpty() && quarantined.isEmpty() && !Files.exists(reportFile)) {
				return null;
			}

			StringBuilder sb = new StringBuilder();
			sb.append("# test-order flaky runtime\n");
			sb.append("# Format: RETRY|<class>|<attempts>  or  QUARANTINE|<class>\n");
			for (Map.Entry<String, Integer> e : merged.entrySet()) {
				sb.append("RETRY|").append(e.getKey()).append('|').append(e.getValue()).append('\n');
			}
			for (String q : quarantined) {
				sb.append("QUARANTINE|").append(q).append('\n');
			}

			Path temp = PersistenceSupport.temporarySibling(reportFile);
			Files.writeString(temp, sb.toString(), StandardCharsets.UTF_8);
			PersistenceSupport.moveIntoPlace(temp, reportFile);
			return null;
		});
	}

	/**
	 * Convenience: write a snapshot of the runtime maps to the report path.
	 */
	public static void write(Path reportFile, Map<String, Integer> retries, Set<String> quarantined)
			throws IOException {
		mergeAndWrite(reportFile, new FlakyRuntimeReport(retries == null ? Map.of() : retries,
				quarantined == null ? Set.of() : quarantined), null);
	}

	/**
	 * Convenience: write a snapshot and simultaneously drop any entries whose class
	 * is not in {@code currentFlakyClasses}.
	 */
	public static void write(Path reportFile, Map<String, Integer> retries, Set<String> quarantined,
			Set<String> currentFlakyClasses) throws IOException {
		mergeAndWrite(reportFile, new FlakyRuntimeReport(retries == null ? Map.of() : retries,
				quarantined == null ? Set.of() : quarantined), currentFlakyClasses);
	}

	/**
	 * Test-only: list the entries as "RETRY|class|n" / "QUARANTINE|class" lines,
	 * sorted.
	 */
	List<String> entriesForTesting() {
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, Integer> e : new TreeMap<>(retryCounts).entrySet()) {
			out.add("RETRY|" + e.getKey() + "|" + e.getValue());
		}
		for (String q : new TreeSet<>(quarantined)) {
			out.add("QUARANTINE|" + q);
		}
		return out;
	}
}
