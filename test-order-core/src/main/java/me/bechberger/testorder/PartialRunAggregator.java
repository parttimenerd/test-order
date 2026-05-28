package me.bechberger.testorder;

import java.io.*;
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
 * {@code <buildId>-<randomUUID>.part} in the pending-runs directory. The format
 * is line-based:
 *
 * <pre>
 * buildId=&lt;uuid&gt;
 * timestamp=&lt;millis&gt;
 * isLearnRun=true|false
 * testClass=&lt;fqcn&gt; failed=true|false score=&lt;n&gt; isNew=true|false isChanged=true|false depOverlap=&lt;n&gt; depTotal=&lt;n&gt; failScore=&lt;d&gt; isFast=true|false isSlow=true|false complexityOverlap=&lt;d&gt; speedRatio=&lt;d&gt; hasStaticFieldOverlap=true|false
 * ...
 * </pre>
 */
public final class PartialRunAggregator {

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

		try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
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
	}

	private static String serializeOutcome(TestOrderState.TestOutcome o) {
		return "testClass=" + o.testClass() + " failed=" + o.failed() + " score=" + o.totalScore() + " isNew="
				+ o.isNew() + " isChanged=" + o.isChanged() + " depOverlap=" + o.depOverlap() + " depTotal="
				+ o.depTotal() + " failScore=" + o.failScore() + " isFast=" + o.isFast() + " isSlow=" + o.isSlow()
				+ " complexityOverlap=" + o.complexityOverlap() + " speedRatio=" + o.speedRatio()
				+ " hasStaticFieldOverlap=" + o.hasStaticFieldOverlap();
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
			deletePartFiles(partFiles);
			return false;
		}

		// Merge: combine all outcomes; use latest timestamp; preserve learn-run flag
		boolean isLearnRun = partials.stream().anyMatch(p -> p.isLearnRun);
		long timestamp = partials.stream().mapToLong(p -> p.timestamp).max().orElse(System.currentTimeMillis());

		// Build merged outcome list ordered by score desc (best approximation
		// of priority order when we don't have a single global ordering).
		List<TestOrderState.TestOutcome> allOutcomes = new ArrayList<>();
		for (ParsedPartial p : partials) {
			allOutcomes.addAll(p.outcomes);
		}
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
			return state;
		});

		deletePartFiles(partFiles);

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
			if (line.startsWith("buildId=")) {
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

		if (buildId == null || outcomes.isEmpty()) {
			return null;
		}
		return new ParsedPartial(buildId, timestamp, isLearnRun, outcomes);
	}

	private static TestOrderState.TestOutcome parseOutcomeLine(String line) {
		Map<String, String> kv = parseKeyValues(line);
		String testClass = kv.get("testClass");
		if (testClass == null) {
			return null;
		}
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
			TestOrderState.ScoreBreakdown bd = new TestOrderState.ScoreBreakdown(score, isNew, isChanged, depOverlap,
					depTotal, failScore, isFast, isSlow, complexityOverlap, speedRatio, hasStaticFieldOverlap);
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
