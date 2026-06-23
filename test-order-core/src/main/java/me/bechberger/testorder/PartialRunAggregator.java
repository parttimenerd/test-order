package me.bechberger.testorder;

import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Writes per-fork partial run records to a staging directory and merges them
 * into a single per-build RunRecord after all forks complete.
 * <p>
 * When {@code testorder.build.id} is set,
 * {@link me.bechberger.testorder.junit.TelemetryListener} calls
 * {@link #writePartial} instead of adding a RunRecord directly to the state
 * file. The Maven plugin calls {@link #mergeAndApply} after surefire finishes
 * to collapse all per-fork records into one.
 * <p>
 * File format: each partial record is a plain-text file named
 * {@code <buildId>-<randomUUID>.part} in the pending-runs directory. Format:
 *
 * <pre>
 * version=1
 * buildId=&lt;uuid&gt;
 * timestamp=&lt;millis&gt;
 * isLearnRun=true|false
 * testClass=&lt;fqcn&gt; failed=true|false score=&lt;n&gt; ... (outcome line)
 * ...
 * </pre>
 */
public final class PartialRunAggregator {
	private static final int PARTIAL_FILE_VERSION = 1;

	private PartialRunAggregator() {
	}

	/**
	 * Writes a partial run record to the pending-runs directory.
	 *
	 * @param pendingRunsDir
	 *            directory where partial files are staged
	 * @param buildId
	 *            the build session ID (same for all forks in one build)
	 * @param record
	 *            the per-fork RunRecord
	 * @param isLearnRun
	 *            whether this was a learn-mode run
	 */
	public static void writePartial(Path pendingRunsDir, String buildId, TestOrderState.RunRecord record,
			boolean isLearnRun) throws IOException {
		Files.createDirectories(pendingRunsDir);
		String fileName = buildId + "-" + UUID.randomUUID() + ".part";
		Path file = pendingRunsDir.resolve(fileName);
		// Write to a temp file first then atomically rename so concurrent readers
		// (mergeAndApply) never see a partially-written .part file.
		Path temp = pendingRunsDir.resolve(fileName + ".tmp");
		try (BufferedWriter w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
			w.write("version=" + PARTIAL_FILE_VERSION);
			w.newLine();
			w.write("buildId=" + buildId);
			w.newLine();
			w.write("timestamp=" + record.timestamp());
			w.newLine();
			w.write("isLearnRun=" + isLearnRun);
			w.newLine();
			for (TestOrderState.TestOutcome outcome : record.outcomes()) {
				w.write(serializeOutcome(outcome));
				w.newLine();
			}
		}
		PersistenceSupport.moveIntoPlace(temp, file);
	}

	private static String serializeOutcome(TestOrderState.TestOutcome o) {
		// URL-encode testClass to safely handle names containing spaces or '='.
		// All other fields are primitives and need no encoding.
		String encodedClass = URLEncoder.encode(o.testClass(), StandardCharsets.UTF_8);
		return "testClass=" + encodedClass + " failed=" + o.failed() + " score=" + o.totalScore() + " isNew="
				+ o.isNew() + " isChanged=" + o.isChanged() + " depOverlap=" + o.depOverlap() + " depTotal="
				+ o.depTotal() + " failScore=" + o.failScore() + " isFast=" + o.isFast() + " isSlow=" + o.isSlow()
				+ " complexityOverlap=" + o.complexityOverlap() + " speedRatio=" + o.speedRatio()
				+ " hasStaticFieldOverlap=" + o.hasStaticFieldOverlap() + " weightedDepOverlap="
				+ o.weightedDepOverlap();
	}

	/**
	 * Reads all partial run records for the given buildId from the staging
	 * directory, merges them into a single RunRecord, applies it to the state, and
	 * deletes the partial files.
	 *
	 * @param pendingRunsDir
	 *            directory containing partial files
	 * @param buildId
	 *            the build session ID to aggregate
	 * @param stateFile
	 *            the state file to update
	 * @return true if any records were merged
	 */
	public static boolean mergeAndApply(Path pendingRunsDir, String buildId, Path stateFile) throws IOException {
		if (!Files.isDirectory(pendingRunsDir)) {
			return false;
		}

		List<Path> partFiles;
		try (Stream<Path> stream = Files.list(pendingRunsDir)) {
			partFiles = stream.filter(p -> p.getFileName().toString().startsWith(buildId + "-")
					&& p.getFileName().toString().endsWith(".part")).toList();
		}

		if (partFiles.isEmpty()) {
			return false;
		}

		// Parse all partial records
		List<ParsedPartial> partials = new ArrayList<>();
		for (Path f : partFiles) {
			try {
				ParsedPartial p = readPartial(f);
				if (p != null) {
					partials.add(p);
				}
			} catch (IOException e) {
				TestOrderLogger.warn("[run-aggregator] Could not read partial record {}: {}", f, e.getMessage());
			}
		}

		if (partials.isEmpty()) {
			// All files failed to parse — don't delete them; they may be salvageable or
			// useful for diagnostics.
			return false;
		}

		// Merge: combine all outcomes; use latest timestamp; preserve learn-run flag
		boolean isLearnRun = partials.stream().anyMatch(p -> p.isLearnRun);
		long timestamp = partials.stream().mapToLong(p -> p.timestamp).max().orElse(System.currentTimeMillis());

		// Build merged outcome list ordered by score desc (best approximation
		// of priority order when we don't have a single global ordering).
		// Deduplicate by test class — when multiple forks record the same class
		// (e.g. shared base class or accidental overlap), prefer the worst-case
		// outcome (failed > passed) to be conservative.
		Map<String, TestOrderState.TestOutcome> byClass = new java.util.LinkedHashMap<>();
		for (ParsedPartial p : partials) {
			for (TestOrderState.TestOutcome o : p.outcomes) {
				byClass.merge(o.testClass(), o, (existing, incoming) -> {
					// Keep the failed outcome when statuses differ (conservative).
					if (!existing.failed() && incoming.failed())
						return incoming;
					if (existing.failed() && !incoming.failed())
						return existing;
					// Same failure status: keep the higher-scored outcome for better APFD.
					return incoming.totalScore() > existing.totalScore() ? incoming : existing;
				});
			}
		}
		List<TestOrderState.TestOutcome> allOutcomes = new ArrayList<>(byClass.values());
		// Sort by descending score as a proxy for execution order within the build
		allOutcomes.sort((a, b) -> Integer.compare(b.totalScore(), a.totalScore()));

		// Compute merged RunRecord fields
		int totalTests = allOutcomes.size();
		int totalFailures = 0;
		int firstFailPos = -1;
		for (int i = 0; i < allOutcomes.size(); i++) {
			if (allOutcomes.get(i).failed()) {
				totalFailures++;
				if (firstFailPos < 0) {
					firstFailPos = i;
				}
			}
		}
		double apfd = APFDCalculator.computeAPFD(allOutcomes);
		TestOrderState.RunRecord merged = new TestOrderState.RunRecord(timestamp, totalTests, totalFailures,
				firstFailPos, apfd, allOutcomes);

		// Apply to state under file lock
		PersistenceSupport.withFileLock(stateFile, () -> {
			TestOrderState state = TelemetryPersistence.loadStateOrEmpty(stateFile);
			TelemetryPersistence.applyHistoryMaxRuns(state);
			state.addRunRecord(merged);
			if (!isLearnRun) {
				state.incrementRunsSinceLearn();
			}
			state.save(stateFile);
			deletePartFiles(partFiles);
			return state;
		});

		TestOrderLogger.info("[run-aggregator] Merged {} per-fork records into one RunRecord: {} tests, {} failures",
				partials.size(), totalTests, totalFailures);
		return true;
	}

	/**
	 * Deletes stale partial files older than the given threshold (in milliseconds).
	 * Called at the start of each build to clean up leftovers from interrupted
	 * runs.
	 */
	public static void cleanStalePartials(Path pendingRunsDir, long olderThanMs) {
		if (!Files.isDirectory(pendingRunsDir)) {
			return;
		}
		long cutoff = System.currentTimeMillis() - olderThanMs;
		try (Stream<Path> stream = Files.list(pendingRunsDir)) {
			stream.filter(p -> p.getFileName().toString().endsWith(".part")).forEach(p -> {
				try {
					if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
						Files.deleteIfExists(p);
					}
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}

	private static void deletePartFiles(List<Path> files) {
		for (Path f : files) {
			try {
				Files.deleteIfExists(f);
			} catch (IOException ignored) {
			}
		}
	}

	private record ParsedPartial(String buildId, long timestamp, boolean isLearnRun,
			List<TestOrderState.TestOutcome> outcomes) {
	}

	private static ParsedPartial readPartial(Path file) throws IOException {
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		String buildId = null;
		long timestamp = 0;
		boolean isLearnRun = false;
		List<TestOrderState.TestOutcome> outcomes = new ArrayList<>();

		for (String line : lines) {
			if (line.startsWith("version=")) {
				try {
					int fileVersion = Integer.parseInt(line.substring("version=".length()).trim());
					if (fileVersion > PARTIAL_FILE_VERSION) {
						// File was written by a newer plugin version — skip rather than
						// misparse fields that may have shifted position or changed meaning.
						return null;
					}
				} catch (NumberFormatException ignored) {
				}
			} else if (line.startsWith("buildId=")) {
				buildId = line.substring("buildId=".length());
			} else if (line.startsWith("timestamp=")) {
				try {
					timestamp = Long.parseLong(line.substring("timestamp=".length()));
				} catch (NumberFormatException ignored) {
				}
			} else if (line.startsWith("isLearnRun=")) {
				isLearnRun = "true".equals(line.substring("isLearnRun=".length()).trim());
			} else if (line.startsWith("testClass=")) {
				TestOrderState.TestOutcome outcome = parseOutcomeLine(line);
				if (outcome != null) {
					outcomes.add(outcome);
				}
			}
		}

		if (buildId == null) {
			return null;
		}
		return new ParsedPartial(buildId, timestamp, isLearnRun, outcomes);
	}

	private static TestOrderState.TestOutcome parseOutcomeLine(String line) {
		Map<String, String> kv = parseKeyValues(line);
		String rawTestClass = kv.get("testClass");
		if (rawTestClass == null) {
			return null;
		}
		// URL-decode testClass to reverse the encoding applied in serializeOutcome.
		String testClass = URLDecoder.decode(rawTestClass, StandardCharsets.UTF_8);
		try {
			boolean failed = "true".equals(kv.get("failed"));
			int score = parseInt(kv.get("score"), 0);
			boolean isNew = "true".equals(kv.get("isNew"));
			boolean isChanged = "true".equals(kv.get("isChanged"));
			int depOverlap = parseInt(kv.get("depOverlap"), 0);
			int depTotal = parseInt(kv.get("depTotal"), 0);
			double failScore = parseDouble(kv.get("failScore"), 0.0);
			boolean isFast = "true".equals(kv.get("isFast"));
			boolean isSlow = "true".equals(kv.get("isSlow"));
			double complexityOverlap = parseDouble(kv.get("complexityOverlap"), 0.0);
			double speedRatio = parseDouble(kv.get("speedRatio"), 0.0);
			boolean hasStaticFieldOverlap = "true".equals(kv.get("hasStaticFieldOverlap"));
			double weightedDepOverlap = parseDouble(kv.get("weightedDepOverlap"), depOverlap);
			TestOrderState.ScoreBreakdown bd = new TestOrderState.ScoreBreakdown(score, isNew, isChanged, depOverlap,
					depTotal, failScore, isFast, isSlow, complexityOverlap, speedRatio, hasStaticFieldOverlap,
					weightedDepOverlap);
			return new TestOrderState.TestOutcome(testClass, bd, failed);
		} catch (Exception e) {
			return null;
		}
	}

	private static Map<String, String> parseKeyValues(String line) {
		Map<String, String> map = new LinkedHashMap<>();
		// Split on spaces that are followed by a key= pattern
		// e.g. "testClass=foo.Bar failed=true score=42 ..."
		String[] tokens = line.split(" (?=\\w+=)");
		for (String token : tokens) {
			int eq = token.indexOf('=');
			if (eq > 0) {
				map.put(token.substring(0, eq), token.substring(eq + 1));
			}
		}
		return map;
	}

	private static int parseInt(String s, int defaultVal) {
		if (s == null)
			return defaultVal;
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return defaultVal;
		}
	}

	private static double parseDouble(String s, double defaultVal) {
		if (s == null)
			return defaultVal;
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return defaultVal;
		}
	}
}
