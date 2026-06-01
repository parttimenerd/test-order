package me.bechberger.testorder.ops;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestOrderState.RunRecord;
import me.bechberger.testorder.TestOrderState.TestOutcome;
import me.bechberger.util.json.PrettyPrinter;

/**
 * Exports a binary dependency index ({@code test-dependencies.lz4}) as JSON,
 * optionally including history from the state file ({@code state.lz4}).
 * Framework-agnostic — used by both the Maven {@code export-json} mojo and the
 * Gradle {@code testOrderExportJson} task.
 */
public final class ExportJsonOperation {

	private ExportJsonOperation() {
	}

	/**
	 * Exports the dependency index as JSON to a file.
	 *
	 * @param indexPath
	 *            path to the binary index ({@code test-dependencies.lz4})
	 * @param outputPath
	 *            output JSON file path
	 * @param log
	 *            logger
	 * @throws IOException
	 *             on I/O failure
	 */
	public static void export(Path indexPath, Path outputPath, PluginLog log) throws IOException {
		export(indexPath, null, outputPath, log);
	}

	/**
	 * Exports the dependency index and state history as JSON to a file.
	 *
	 * @param indexPath
	 *            path to the binary index ({@code test-dependencies.lz4})
	 * @param statePath
	 *            path to the state file ({@code state.lz4}), or {@code null} to
	 *            skip history
	 * @param outputPath
	 *            output JSON file path
	 * @param log
	 *            logger
	 * @throws IOException
	 *             on I/O failure
	 */
	public static void export(Path indexPath, Path statePath, Path outputPath, PluginLog log) throws IOException {
		DependencyMap map = DependencyMap.load(indexPath);
		if (map.size() == 0) {
			log.info("[test-order] Dependency index is empty: " + indexPath);
			return;
		}
		TestOrderState state = loadStateIfPresent(statePath, log);
		String json = toJson(map, state);
		Path parent = outputPath.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path temp = me.bechberger.testorder.PersistenceSupport.temporarySibling(outputPath);
		Files.writeString(temp, json, StandardCharsets.UTF_8);
		me.bechberger.testorder.PersistenceSupport.moveIntoPlace(temp, outputPath);
		log.info("[test-order] Exported " + map.size() + " test classes as JSON → " + outputPath);
	}

	/**
	 * Exports the dependency index as JSON to a print stream (typically stdout).
	 *
	 * @param indexPath
	 *            path to the binary index ({@code test-dependencies.lz4})
	 * @param out
	 *            print stream to write to
	 * @param log
	 *            logger
	 * @throws IOException
	 *             on I/O failure
	 */
	public static void export(Path indexPath, PrintStream out, PluginLog log) throws IOException {
		export(indexPath, null, out, log);
	}

	/**
	 * Exports the dependency index and state history as JSON to a print stream.
	 *
	 * @param indexPath
	 *            path to the binary index ({@code test-dependencies.lz4})
	 * @param statePath
	 *            path to the state file ({@code state.lz4}), or {@code null} to
	 *            skip history
	 * @param out
	 *            print stream to write to
	 * @param log
	 *            logger
	 * @throws IOException
	 *             on I/O failure
	 */
	public static void export(Path indexPath, Path statePath, PrintStream out, PluginLog log) throws IOException {
		DependencyMap map = DependencyMap.load(indexPath);
		if (map.size() == 0) {
			log.info("[test-order] Dependency index is empty: " + indexPath);
			return;
		}
		TestOrderState state = loadStateIfPresent(statePath, log);
		log.info("[test-order] Exporting dependency index as JSON (" + map.size() + " test classes)");
		out.println(toJson(map, state));
	}

	private static TestOrderState loadStateIfPresent(Path statePath, PluginLog log) {
		if (statePath == null || !Files.exists(statePath)) {
			return null;
		}
		try {
			TestOrderState state = TestOrderState.load(statePath);
			log.info("[test-order] Including history from state file: " + statePath);
			return state;
		} catch (IOException e) {
			log.warn("[test-order] Failed to load state file (history will be omitted): " + e.getMessage());
			return null;
		}
	}

	/**
	 * Converts a {@link DependencyMap} to a JSON string, optionally including state
	 * history.
	 */
	static String toJson(DependencyMap map, TestOrderState state) {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("exportVersion", 2);
		root.put("depFormatVersion", (int) DependencyMap.FORMAT_VERSION);
		root.put("testClassCount", map.size());
		root.put("hasMethodDeps", map.hasMethodDeps());
		root.put("hasMemberDeps", map.hasMemberDeps());

		// Class-level dependencies
		List<Object> tests = new ArrayList<>();
		for (String testClass : map.testClasses()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("testClass", testClass);
			entry.put("dependencies", new ArrayList<>(map.get(testClass)));

			// Method-level dependencies (if available)
			if (map.hasMethodDeps()) {
				List<Object> methods = new ArrayList<>();
				String prefix = testClass + "#";
				for (String methodKey : map.methodKeys()) {
					if (methodKey.startsWith(prefix)) {
						String methodName = methodKey.substring(prefix.length());
						Map<String, Object> methodEntry = new LinkedHashMap<>();
						methodEntry.put("method", methodName);
						methodEntry.put("dependencies", new ArrayList<>(map.getMethodDeps(methodKey)));
						methods.add(methodEntry);
					}
				}
				if (!methods.isEmpty()) {
					entry.put("methodDependencies", methods);
				}
			}

			// Member-level dependencies (if available)
			if (map.hasMemberDeps()) {
				Set<String> memberDeps = map.getMemberDeps(testClass);
				if (!memberDeps.isEmpty()) {
					entry.put("memberDependencies", new ArrayList<>(memberDeps));
				}
			}

			tests.add(entry);
		}
		root.put("tests", tests);

		// History from state file (if available)
		if (state != null) {
			Map<String, Object> history = new LinkedHashMap<>();

			// Weights
			history.put("weights", state.weights().toMap());

			// Durations (EMA-smoothed, in ms)
			Map<String, Long> durations = state.getClassDurations();
			if (!durations.isEmpty()) {
				history.put("durations", new LinkedHashMap<>(durations));
			}

			// Failure scores (decayed)
			Map<String, Double> failures = state.getFailureScores();
			if (!failures.isEmpty()) {
				history.put("failureScores", new LinkedHashMap<>(failures));
			}

			// Method durations
			Map<String, Map<String, Double>> methodDurations = state.getMethodDurations();
			if (!methodDurations.isEmpty()) {
				Map<String, Object> methodDurMap = new LinkedHashMap<>();
				for (var me : methodDurations.entrySet()) {
					methodDurMap.put(me.getKey(), new LinkedHashMap<>(me.getValue()));
				}
				history.put("methodDurations", methodDurMap);
			}

			// Method failure scores
			Map<String, Double> methodFailures = state.getMethodFailureScores();
			if (!methodFailures.isEmpty()) {
				history.put("methodFailureScores", new LinkedHashMap<>(methodFailures));
			}

			// Run records
			List<RunRecord> runs = state.runs();
			if (!runs.isEmpty()) {
				List<Object> runList = new ArrayList<>();
				for (RunRecord run : runs) {
					Map<String, Object> runMap = new LinkedHashMap<>();
					runMap.put("timestamp", run.timestamp());
					runMap.put("totalTests", run.totalTests());
					runMap.put("totalFailures", run.totalFailures());
					runMap.put("firstFailurePosition", run.firstFailurePosition());
					runMap.put("apfd", run.apfd());
					if (run.outcomes() != null && !run.outcomes().isEmpty()) {
						List<Object> outcomeList = new ArrayList<>();
						for (TestOutcome outcome : run.outcomes()) {
							Map<String, Object> oMap = new LinkedHashMap<>();
							oMap.put("testClass", outcome.testClass());
							oMap.put("score", outcome.totalScore());
							oMap.put("failed", outcome.failed());
							oMap.put("isNew", outcome.isNew());
							oMap.put("isChanged", outcome.isChanged());
							oMap.put("isFast", outcome.isFast());
							oMap.put("isSlow", outcome.isSlow());
							oMap.put("depOverlap", outcome.depOverlap());
							oMap.put("depTotal", outcome.depTotal());
							oMap.put("failScore", outcome.failScore());
							oMap.put("speedRatio", outcome.speedRatio());
							oMap.put("complexityOverlap", outcome.complexityOverlap());
							oMap.put("hasStaticFieldOverlap", outcome.hasStaticFieldOverlap());
							outcomeList.add(oMap);
						}
						runMap.put("outcomes", outcomeList);
					}
					runList.add(runMap);
				}
				history.put("runs", runList);
			}

			root.put("history", history);
		}

		return PrettyPrinter.prettyPrint(root);
	}
}
