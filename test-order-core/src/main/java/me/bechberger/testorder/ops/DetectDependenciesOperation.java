package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.ops.detection.*;
import me.bechberger.util.json.PrettyPrinter;

/**
 * Orchestrator for order-dependent test detection. Loads dependencies and
 * state, selects and runs algorithms, and produces a report.
 */
public final class DetectDependenciesOperation {

	private DetectDependenciesOperation() {
	}

	/**
	 * Configuration for the detect-dependencies operation.
	 *
	 * @param indexFile
	 *            path to the dependency index (LZ4)
	 * @param stateFile
	 *            path to test-order state file
	 * @param outputDir
	 *            directory for writing reports
	 * @param algorithm
	 *            which algorithm to use ("combined", "reverse", "random", etc.)
	 * @param timeBudgetSeconds
	 *            time budget in seconds (0 = unlimited)
	 * @param stopOnFirst
	 *            stop after finding first OD pair
	 * @param randomSeed
	 *            seed for reproducibility
	 * @param moduleName
	 *            name of the module being analyzed (for report metadata)
	 * @param log
	 *            logger
	 */
	public record Config(Path indexFile, Path stateFile, Path outputDir, String algorithm, int timeBudgetSeconds,
			boolean stopOnFirst, long randomSeed, String moduleName, PluginLog log) {
	}

	/**
	 * Metadata about the detection run, included in reports.
	 */
	public record ReportMetadata(String moduleName, String algorithm, int testClassCount, int passingTestCount,
			int runsExecuted, long durationMillis, int conflictEdges, long randomSeed, int timeBudgetSeconds) {
	}

	/**
	 * Result of detection.
	 *
	 * @param results
	 *            all class-level OD findings
	 * @param methodResults
	 *            all method-level OD findings
	 * @param constraintManager
	 *            ordering constraints derived from findings
	 * @param runsExecuted
	 *            total test runs performed
	 * @param reportPath
	 *            path to the generated JSON report (if written)
	 * @param markdownReportPath
	 *            path to the generated Markdown report (if written)
	 */
	public record Result(List<ODResult> results, List<MethodLevelDetection.MethodODResult> methodResults,
			OrderConstraintManager constraintManager, int runsExecuted, Path reportPath, Path markdownReportPath) {

		public boolean hasFindings() {
			return !results.isEmpty() || !methodResults.isEmpty();
		}

		public int victimCount() {
			return (int) results.stream().filter(r -> r.type() == ODType.VICTIM).count();
		}

		public int brittleCount() {
			return (int) results.stream().filter(r -> r.type() == ODType.BRITTLE).count();
		}
	}

	/**
	 * Run the detect-dependencies operation.
	 */
	public static Result run(Config config, TestRunner runner) throws IOException {
		PluginLog log = config.log();
		long startTime = System.currentTimeMillis();

		// Incremental: load known victims from prior report to skip re-confirmation
		Set<String> knownVictims = loadKnownVictims(config.outputDir(), log);
		List<ODResult> carryForward = new ArrayList<>();
		if (!knownVictims.isEmpty()) {
			carryForward = loadPriorResults(config.outputDir(), log);
		}

		// Load dependencies
		DependencyMap depMap = null;
		if (config.indexFile() != null && Files.exists(config.indexFile())) {
			depMap = DependencyMap.load(config.indexFile());
			log.info("Loaded dependency map: " + depMap.testClasses().size() + " test classes");
		}

		// Auto-learn: if no dependency data or missing method-level deps, run a learn
		// phase
		boolean needsLearn = (depMap == null) || !depMap.hasMethodDeps();
		if (needsLearn && runner.supportsLearnPhase()) {
			String reason = depMap == null
					? "no dependency index found"
					: "dependency index lacks method-level data (need MEMBER mode)";
			log.info("Auto-learn triggered: " + reason);
			log.info("Running learn phase with MEMBER instrumentation...");
			boolean learnOk = runner.runLearnPhase("MEMBER");
			if (learnOk && config.indexFile() != null && Files.exists(config.indexFile())) {
				depMap = DependencyMap.load(config.indexFile());
				log.info("Reloaded dependency map after learn: " + depMap.testClasses().size() + " test classes, "
						+ "method deps: " + depMap.hasMethodDeps());
			} else if (!learnOk) {
				log.warn("Learn phase failed — continuing without full dependency data");
			}
		} else if (needsLearn) {
			log.warn("No dependency index found and runner does not support learn phase "
					+ "— running without dependency awareness");
		}

		// Load state
		TestOrderState state = null;
		if (config.stateFile() != null && Files.exists(config.stateFile())) {
			state = TestOrderState.load(config.stateFile());
			int usableRuns = (int) state.runs().stream().filter(r -> !r.outcomes().isEmpty()).count();
			if (usableRuns > 0) {
				log.info("Loaded state: " + state.runs().size() + " historical runs (" + usableRuns
						+ " with test outcomes)");
			} else {
				log.info("Loaded state: " + state.runs().size() + " historical runs (none with usable test outcomes)");
			}
		}

		// Determine reference order (from last passing run or depMap test classes)
		List<String> referenceOrder = determineReferenceOrder(state, depMap);
		if (referenceOrder.isEmpty()) {
			// Try discovery: run all tests to find what exists
			log.info("No test order from state or dependency map — running discovery test run...");
			referenceOrder = discoverTestClasses(runner, log);
		}
		if (referenceOrder.isEmpty()) {
			log.error("Discovery failed: no test classes found. " + "Possible causes:\n"
					+ "  (1) The project has no tests\n" + "  (2) Tests use JUnit 4 without the Vintage engine\n"
					+ "  (3) test-order-junit is not on the test classpath\n"
					+ "  (4) Subprocess `mvn surefire:test` failed (check project builds independently)");
			return new Result(List.of(), List.of(), new OrderConstraintManager(), 0, null, null);
		}

		log.info("Detecting OD bugs among " + referenceOrder.size() + " test classes");

		// Compute deadline from the start of the operation (includes discovery time)
		long deadline = config.timeBudgetSeconds() > 0
				? startTime + config.timeBudgetSeconds() * 1000L
				: Long.MAX_VALUE;

		// Determine passing tests (run in reference order)
		log.info("Running reference test run (" + referenceOrder.size()
				+ " classes) to determine baseline pass/fail...");
		long refRunStart = System.currentTimeMillis();
		PassingTestsResult ptr = determinePassingTests(referenceOrder, runner, log);
		long perRunMs = System.currentTimeMillis() - refRunStart;
		log.info("Reference run complete in " + formatDuration(perRunMs) + " — " + ptr.passing().size()
				+ " of " + referenceOrder.size() + " classes pass");
		if (config.timeBudgetSeconds() > 0 && perRunMs > 0) {
			long estimatedRunsHint = Math.max(1,
					selectAlgorithm(config.algorithm(), log).estimatedRuns(referenceOrder.size(), 0));
			long suggestedBudgetHint = (perRunMs * estimatedRunsHint) / 1000;
			if (suggestedBudgetHint > config.timeBudgetSeconds()) {
				log.info("Each run takes ~" + (perRunMs / 1000) + "s. For full coverage (~" + estimatedRunsHint
						+ " runs), consider: -Dtestorder.detect.timeBudget=" + suggestedBudgetHint);
			}
		}
		Set<String> passingTests = ptr.passing();
		referenceOrder = ptr.effectiveOrder();
		if (passingTests.isEmpty()) {
			log.warn("No tests pass in reference order — cannot detect OD bugs. " + "All " + referenceOrder.size()
					+ " test classes had failures. "
					+ "If only a few methods per class fail, consider using --add-opens "
					+ "or fixing the failing tests so the class is not excluded entirely.");
			return new Result(List.of(), List.of(), new OrderConstraintManager(), 0, null, null);
		}

		// Incremental: exclude known victims from detection targets
		if (!knownVictims.isEmpty()) {
			passingTests.removeAll(knownVictims);
			log.info("Incremental: skipping " + knownVictims.size() + " previously confirmed findings, "
					+ passingTests.size() + " tests remain for detection");
		}

		// Build conflict graph
		ConflictGraph graph = ConflictGraph.empty();
		if (depMap != null) {
			graph = ConflictGraphBuilder.build(depMap, state, new ArrayList<>(referenceOrder));
			log.info("Conflict graph: " + graph.edgeCount() + " edges");
		}

		// Build detection context
		DetectionContext ctx = new DetectionContext(graph, depMap, state, referenceOrder, passingTests, runner,
				deadline, config.randomSeed(), log, new java.util.concurrent.atomic.AtomicInteger(0));

		// Select and run algorithm
		DetectionAlgorithm algorithm = selectAlgorithm(config.algorithm(), log);
		int estimatedRuns = algorithm.estimatedRuns(referenceOrder.size(), graph.edgeCount());
		log.info("Running algorithm: " + algorithm.name() + " (estimated " + estimatedRuns + " runs, budget: "
				+ (config.timeBudgetSeconds() > 0 ? config.timeBudgetSeconds() + "s" : "unlimited") + ")");

		// Install shutdown hook for partial results on interrupt
		final List<ODResult> partialResults = new ArrayList<>();
		Thread shutdownHook = new Thread(() -> {
			if (!partialResults.isEmpty()) {
				log.info("[test-order] Interrupted — " + partialResults.size() + " findings detected before shutdown.");
			}
		});
		Runtime.getRuntime().addShutdownHook(shutdownHook);

		try {
			List<ODResult> rawResults = algorithm.detect(ctx);
			partialResults.addAll(rawResults);
		} finally {
			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException ignored) {
				// JVM shutting down
			}
		}

		// Normalize classification: run isolation to confirm type (#23)
		List<ODResult> results = normalizeClassification(partialResults, runner, ctx.passingTests());

		// Merge carry-forward results from prior incremental runs
		if (!carryForward.isEmpty()) {
			results.addAll(carryForward);
		}

		// Deduplicate results
		results = deduplicateResults(results);

		long elapsed = System.currentTimeMillis() - startTime;
		int actualRuns = ctx.totalRuns() > 0 ? ctx.totalRuns() : estimatedRuns;
		log.info("Detection complete: " + results.size() + " findings ("
				+ results.stream().filter(r -> r.type() == ODType.VICTIM).count() + " victims, "
				+ results.stream().filter(r -> r.type() == ODType.BRITTLE).count() + " brittles) " + "in "
				+ formatDuration(elapsed) + " (budget: "
				+ (config.timeBudgetSeconds() > 0 ? config.timeBudgetSeconds() + "s" : "unlimited") + ")");

		// Budget recommendation for time-limited runs
		if (config.timeBudgetSeconds() > 0 && actualRuns > 0) {
			long perRunMs = elapsed / actualRuns;
			if (perRunMs > 0) {
				long suggestedBudget = (perRunMs * estimatedRuns) / 1000;
				if (suggestedBudget > config.timeBudgetSeconds()) {
					log.info("Each detection run takes ~" + (perRunMs / 1000) + "s. " + "For full coverage ("
							+ estimatedRuns + " runs), set " + "testorder.detect.timeBudget=" + suggestedBudget);
				}
			}
		}
		// Console summary table (#21)
		if (!results.isEmpty()) {
			log.info("┌─────────────────────────────────────────────────────────────┐");
			log.info("│ OD Finding Summary                                          │");
			log.info("├────┬──────────┬────────────────────────────────────────────┤");
			for (int i = 0; i < results.size(); i++) {
				ODResult r = results.get(i);
				String shortVictim = r.victim().contains(".")
						? r.victim().substring(r.victim().lastIndexOf('.') + 1)
						: r.victim();
				String polluterRaw = r.polluter();
				boolean polluterKnown = polluterRaw != null && !polluterRaw.contains("unknown")
						&& !polluterRaw.equals(r.victim());
				String polluter = polluterKnown
						? (polluterRaw.contains(".")
								? polluterRaw.substring(polluterRaw.lastIndexOf('.') + 1)
								: polluterRaw)
						: "?";
				String arrow = r.type() == ODType.VICTIM
						? polluter + " → " + shortVictim
						: shortVictim + " needs " + polluter;
				log.info("│ " + (i + 1) + "  │ " + String.format("%-8s", r.type().name()) + " │ " + arrow);
			}
			log.info("└────┴──────────┴────────────────────────────────────────────┘");
		}

		// ── Method-level detection phase ──────────────────────────────────
		List<MethodLevelDetection.MethodODResult> methodResults = new ArrayList<>();
		if (runner.supportsMethodOrdering() && !ctx.timeBudgetExhausted()) {
			log.info("Starting method-level OD detection...");
			methodResults = runMethodLevelDetection(referenceOrder, depMap, runner, deadline, config.randomSeed(), log);
			if (!methodResults.isEmpty()) {
				log.info("Method-level detection: " + methodResults.size() + " findings across "
						+ methodResults.stream().map(MethodLevelDetection.MethodODResult::testClass).distinct().count()
						+ " classes");
			}
		}

		// Build ordering constraints
		OrderConstraintManager constraints = new OrderConstraintManager();
		constraints.applyResults(results);

		// Compute metadata
		long durationMillis = System.currentTimeMillis() - startTime;
		ReportMetadata metadata = new ReportMetadata(config.moduleName() != null ? config.moduleName() : "unknown",
				algorithm.name(), referenceOrder.size(), passingTests.size(), actualRuns, durationMillis,
				graph != null ? graph.edgeCount() : 0, config.randomSeed(), config.timeBudgetSeconds());

		// Write report
		Path reportPath = null;
		Path mdReportPath = null;
		if (config.outputDir() != null) {
			Files.createDirectories(config.outputDir());
			Path[] paths = writeReport(results, methodResults, constraints, metadata, config.outputDir());
			reportPath = paths[0];
			mdReportPath = paths[1];
			log.info("Reports written to: " + reportPath + " and " + mdReportPath);
		}

		return new Result(results, methodResults, constraints, actualRuns, reportPath, mdReportPath);
	}

	private static List<String> determineReferenceOrder(TestOrderState state, DependencyMap depMap) {
		// Prefer last run with outcomes (search backwards for one that has test
		// results)
		if (state != null && !state.runs().isEmpty()) {
			for (int i = state.runs().size() - 1; i >= 0; i--) {
				TestOrderState.RunRecord run = state.runs().get(i);
				if (!run.outcomes().isEmpty()) {
					return run.outcomes().stream().map(TestOrderState.TestOutcome::testClass).toList();
				}
			}
		}
		// Fall back to dependency map's test classes
		if (depMap != null) {
			return new ArrayList<>(depMap.testClasses());
		}
		return List.of();
	}

	/**
	 * Discover test classes by running all tests (no filter) and parsing results.
	 * Used as a fallback when no dependency index or state exists.
	 */
	private static List<String> discoverTestClasses(TestRunner runner, PluginLog log) {
		TestRunner.TestRunResult discovery = runner.run(List.of("*"));
		Set<String> all = new HashSet<>();
		all.addAll(discovery.passedTests());
		all.addAll(discovery.failedTests());
		if (!all.isEmpty()) {
			log.info("Discovered " + all.size() + " test classes via initial run");
		}
		// Sort alphabetically for a deterministic reference order
		List<String> sorted = new ArrayList<>(all);
		Collections.sort(sorted);
		return sorted;
	}

	private record PassingTestsResult(Set<String> passing, List<String> effectiveOrder) {
	}

	private static PassingTestsResult determinePassingTests(List<String> referenceOrder, TestRunner runner,
			PluginLog log) {
		TestRunner.TestRunResult result = runner.run(referenceOrder);
		Set<String> passing = new HashSet<>(result.passedTests());
		List<String> effectiveOrder = new ArrayList<>(referenceOrder);
		if (!result.failedTests().isEmpty()) {
			// Some tests fail in the reference order. Try putting them at the end
			// (after passing tests) to check if they are order-dependent rather than
			// genuinely broken. This catches the common case where a test depends on
			// another test running first.
			List<String> reordered = new ArrayList<>(result.passedTests());
			reordered.addAll(result.failedTests());
			TestRunner.TestRunResult retryResult = runner.run(reordered);
			Set<String> recovered = new HashSet<>(retryResult.passedTests());
			recovered.removeAll(passing);
			if (!recovered.isEmpty()) {
				log.info(recovered.size() + " test(s) pass when run after others — "
						+ "these are likely order-dependent (included in detection)");
				passing.addAll(recovered);
				effectiveOrder = reordered;
			}
			Set<String> stillFailing = new HashSet<>(retryResult.failedTests());
			if (!stillFailing.isEmpty()) {
				log.warn(stillFailing.size() + " of " + effectiveOrder.size()
						+ " test classes fail regardless of order (ignored for detection)");
			}
		}
		return new PassingTestsResult(passing, effectiveOrder);
	}

	private static final Set<String> VALID_ALGORITHMS = Set.of("reverse", "reverse-order", "random",
			"random-reordering", "history", "history-mining", "pfast", "pfast-exclusion", "iterative",
			"iterative-refinement", "bounded", "dependence-aware-bounded", "tuscan", "tuscan-systematic", "combined",
			"combined-adaptive");

	private static DetectionAlgorithm selectAlgorithm(String name, PluginLog log) throws IOException {
		String normalized = name != null ? name.toLowerCase(Locale.ROOT) : "combined";
		if (!VALID_ALGORITHMS.contains(normalized)) {
			throw new IOException("Unknown algorithm '" + name + "'. Valid options: "
					+ "combined, reverse, random, history, pfast, iterative, bounded, tuscan");
		}
		return switch (normalized) {
			case "reverse", "reverse-order" -> new ReverseOrderAlgorithm();
			case "random", "random-reordering" -> new RandomReorderingAlgorithm();
			case "history", "history-mining" -> new HistoryMiningAlgorithm();
			case "pfast", "pfast-exclusion" -> new PFASTAlgorithm();
			case "iterative", "iterative-refinement" -> new IterativeRefinementAlgorithm();
			case "bounded", "dependence-aware-bounded" -> new DependenceAwareBoundedAlgorithm();
			case "tuscan", "tuscan-systematic" -> new TuscanSystematicAlgorithm();
			case "combined", "combined-adaptive" -> new CombinedAdaptiveAlgorithm();
			default -> new CombinedAdaptiveAlgorithm();
		};
	}

	private static List<ODResult> deduplicateResults(List<ODResult> results) {
		Map<String, ODResult> byVictim = new LinkedHashMap<>();
		for (ODResult result : results) {
			byVictim.merge(result.victim(), result,
					(existing, incoming) -> incoming.confidence() > existing.confidence() ? incoming : existing);
		}
		return new ArrayList<>(byVictim.values());
	}

	/**
	 * Normalize OD classification: a test is VICTIM if it passes alone, BRITTLE if
	 * it fails alone. This ensures consistent classification regardless of which
	 * algorithm discovered the finding.
	 */
	private static List<ODResult> normalizeClassification(List<ODResult> results, TestRunner runner,
			Set<String> passingTests) {
		List<ODResult> normalized = new ArrayList<>();
		for (ODResult r : results) {
			// Run the victim in isolation to determine its true type
			TestRunner.TestRunResult isolation = runner.run(List.of(r.victim()));
			ODType correctType;
			if (isolation.passed(r.victim())) {
				correctType = ODType.VICTIM; // passes alone → needs a polluter to fail
			} else {
				correctType = ODType.BRITTLE; // fails alone → needs a setter to pass
			}
			if (correctType != r.type()) {
				normalized.add(
						new ODResult(r.victim(), correctType, r.dependencyChain(), r.description(), r.confidence()));
			} else {
				normalized.add(r);
			}
		}
		return normalized;
	}

	/**
	 * Load known victims from a prior detection report (incremental mode). Returns
	 * an empty set if no prior report exists.
	 */
	private static Set<String> loadKnownVictims(Path outputDir, PluginLog log) {
		Set<String> known = new HashSet<>();
		if (outputDir == null)
			return known;
		Path priorReport = outputDir.resolve("od-detection-report.json");
		if (!Files.exists(priorReport))
			return known;

		try {
			String content = Files.readString(priorReport);
			// Simple extraction of victim fields from JSON
			int idx = 0;
			while (true) {
				int victimIdx = content.indexOf("\"victim\"", idx);
				if (victimIdx < 0)
					break;
				int colonIdx = content.indexOf(':', victimIdx);
				if (colonIdx < 0)
					break;
				int quoteStart = content.indexOf('"', colonIdx + 1);
				if (quoteStart < 0)
					break;
				int quoteEnd = content.indexOf('"', quoteStart + 1);
				if (quoteEnd < 0)
					break;
				known.add(content.substring(quoteStart + 1, quoteEnd));
				idx = quoteEnd + 1;
			}
			if (!known.isEmpty()) {
				log.info(
						"Incremental mode: " + known.size() + " previously confirmed findings will be carried forward");
			}
		} catch (IOException e) {
			// Ignore — treat as no prior results
		}
		return known;
	}

	/**
	 * Load prior ODResults from a previous report for carry-forward in incremental
	 * mode.
	 */
	private static List<ODResult> loadPriorResults(Path outputDir, PluginLog log) {
		List<ODResult> results = new ArrayList<>();
		if (outputDir == null)
			return results;
		Path priorReport = outputDir.resolve("od-detection-report.json");
		if (!Files.exists(priorReport))
			return results;

		try {
			String content = Files.readString(priorReport);
			// Parse each finding block from the JSON
			int findingsIdx = content.indexOf("\"findings\"");
			if (findingsIdx < 0)
				return results;

			int arrStart = content.indexOf('[', findingsIdx);
			if (arrStart < 0)
				return results;

			// Find each victim/type/confidence/description/dependencyChain in the findings
			// array
			int idx = arrStart;
			while (true) {
				int victimIdx = content.indexOf("\"victim\"", idx);
				if (victimIdx < 0)
					break;
				// Check if we're still in "findings" array (before "constraints" or
				// "methodFindings")
				int constraintsIdx = content.indexOf("\"constraints\"", arrStart);
				if (constraintsIdx > 0 && victimIdx > constraintsIdx)
					break;

				String victim = extractJsonString(content, victimIdx);
				int typeIdx = content.indexOf("\"type\"", victimIdx);
				String typeStr = typeIdx > 0 ? extractJsonString(content, typeIdx) : "VICTIM";
				int descIdx = content.indexOf("\"description\"", typeIdx > 0 ? typeIdx : victimIdx);
				String desc = descIdx > 0 ? extractJsonString(content, descIdx) : "Carried from prior run";

				ODType type;
				try {
					type = ODType.valueOf(typeStr);
				} catch (Exception e) {
					type = ODType.VICTIM;
				}

				results.add(new ODResult(victim, type, List.of(victim), desc + " [carried from prior run]", 0.9));
				idx = victimIdx + 10;
			}
		} catch (IOException e) {
			// Ignore
		}
		return results;
	}

	private static String extractJsonString(String json, int keyIdx) {
		int colonIdx = json.indexOf(':', keyIdx);
		if (colonIdx < 0)
			return "";
		int quoteStart = json.indexOf('"', colonIdx + 1);
		if (quoteStart < 0)
			return "";
		int quoteEnd = json.indexOf('"', quoteStart + 1);
		if (quoteEnd < 0)
			return "";
		return json.substring(quoteStart + 1, quoteEnd);
	}

	/**
	 * Run method-level OD detection for each test class that has method-level dep
	 * data. Prioritizes classes with more methods and known shared-member
	 * conflicts.
	 */
	private static List<MethodLevelDetection.MethodODResult> runMethodLevelDetection(List<String> testClasses,
			DependencyMap depMap, TestRunner runner, long deadline, long seed, PluginLog log) {
		List<MethodLevelDetection.MethodODResult> allResults = new ArrayList<>();
		Random rng = new Random(seed);

		// Determine which classes to probe: those with method-level data or multiple
		// methods
		List<ClassMethodInfo> candidates = new ArrayList<>();
		for (String testClass : testClasses) {
			List<String> methods = getMethodsForClass(testClass, depMap);
			if (methods.size() >= 2) {
				candidates.add(new ClassMethodInfo(testClass, methods));
			}
		}

		if (candidates.isEmpty()) {
			return allResults;
		}

		// Sort by number of methods descending (more methods = higher chance of OD)
		candidates.sort(Comparator.comparingInt((ClassMethodInfo c) -> c.methods.size()).reversed());

		log.info("Method-level detection: probing " + candidates.size() + " classes with 2+ test methods");

		for (ClassMethodInfo candidate : candidates) {
			if (System.currentTimeMillis() >= deadline)
				break;

			List<MethodLevelDetection.MethodODResult> classResults = MethodLevelDetection.detect(candidate.className,
					candidate.methods, runner, depMap, deadline, rng);
			allResults.addAll(classResults);
		}

		return allResults;
	}

	private record ClassMethodInfo(String className, List<String> methods) {
	}

	/**
	 * Get test method names for a class from the dependency map. Falls back to
	 * method keys matching the class prefix.
	 */
	private static List<String> getMethodsForClass(String testClass, DependencyMap depMap) {
		if (depMap == null || !depMap.hasMethodDeps()) {
			return List.of();
		}
		String prefix = testClass + "#";
		List<String> methods = new ArrayList<>();
		for (String key : depMap.methodKeys()) {
			if (key.startsWith(prefix)) {
				methods.add(key.substring(prefix.length()));
			}
		}
		return methods;
	}

	private static Path[] writeReport(List<ODResult> results, List<MethodLevelDetection.MethodODResult> methodResults,
			OrderConstraintManager constraints, ReportMetadata metadata, Path outputDir) throws IOException {
		// Write JSON report using femtojson
		Path jsonPath = outputDir.resolve("od-detection-report.json");
		Map<String, Object> root = new LinkedHashMap<>();

		// Metadata section
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("module", metadata.moduleName());
		meta.put("algorithm", metadata.algorithm());
		meta.put("testClassCount", metadata.testClassCount());
		meta.put("passingTestCount", metadata.passingTestCount());
		meta.put("runsExecuted", metadata.runsExecuted());
		meta.put("durationMs", metadata.durationMillis());
		meta.put("durationFormatted", formatDuration(metadata.durationMillis()));
		meta.put("conflictEdges", metadata.conflictEdges());
		meta.put("randomSeed", metadata.randomSeed());
		meta.put("timeBudgetSeconds", metadata.timeBudgetSeconds());
		meta.put("timestamp", java.time.Instant.now().toString());
		root.put("metadata", meta);

		// Summary section
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("totalFindings", results.size());
		summary.put("victims", results.stream().filter(r -> r.type() == ODType.VICTIM).count());
		summary.put("brittles", results.stream().filter(r -> r.type() == ODType.BRITTLE).count());
		summary.put("methodLevelFindings", methodResults.size());
		root.put("summary", summary);

		// Findings
		List<Object> findingsList = new ArrayList<>();
		for (ODResult r : results) {
			Map<String, Object> finding = new LinkedHashMap<>();
			finding.put("victim", r.victim());
			finding.put("type", r.type().name());
			finding.put("confidence", r.confidence());
			finding.put("description", r.description());
			finding.put("dependencyChain", r.dependencyChain());
			findingsList.add(finding);
		}
		root.put("findings", findingsList);

		// Constraints
		List<Object> constraintsList = new ArrayList<>();
		List<OrderConstraintManager.Constraint> cons = constraints.constraints();
		for (OrderConstraintManager.Constraint c : cons) {
			Map<String, Object> constraint = new LinkedHashMap<>();
			constraint.put("testA", c.testA());
			constraint.put("testB", c.testB());
			constraint.put("type", c.type().name());
			constraint.put("reason", c.reason());
			constraintsList.add(constraint);
		}
		root.put("constraints", constraintsList);

		// Method-level findings
		List<Object> methodFindingsList = new ArrayList<>();
		for (MethodLevelDetection.MethodODResult mr : methodResults) {
			Map<String, Object> mf = new LinkedHashMap<>();
			mf.put("testClass", mr.testClass());
			mf.put("victimMethod", mr.victimMethod());
			mf.put("type", mr.type().name());
			mf.put("confidence", mr.confidence());
			mf.put("description", mr.description());
			mf.put("dependencyChain", mr.dependencyChain());
			methodFindingsList.add(mf);
		}
		root.put("methodFindings", methodFindingsList);

		Files.writeString(jsonPath, PrettyPrinter.prettyPrint(root));

		// Write markdown summary
		Path mdPath = outputDir.resolve("od-detection-report.md");
		StringBuilder md = new StringBuilder();
		md.append("# Order-Dependent Test Detection Report\n\n");

		// Metadata table
		md.append("| Property | Value |\n");
		md.append("|----------|-------|\n");
		md.append("| **Module** | `").append(metadata.moduleName()).append("` |\n");
		md.append("| **Algorithm** | `").append(metadata.algorithm()).append("` |\n");
		md.append("| **Test classes** | ").append(metadata.testClassCount()).append(" |\n");
		md.append("| **Passing tests** | ").append(metadata.passingTestCount()).append(" |\n");
		md.append("| **Runs executed** | ").append(metadata.runsExecuted()).append(" |\n");
		md.append("| **Duration** | ").append(formatDuration(metadata.durationMillis())).append(" |\n");
		md.append("| **Conflict edges** | ").append(metadata.conflictEdges()).append(" |\n");
		md.append("| **Seed** | ").append(metadata.randomSeed()).append(" |\n");
		md.append("| **Time budget** | ")
				.append(metadata.timeBudgetSeconds() > 0 ? metadata.timeBudgetSeconds() + "s" : "unlimited")
				.append(" |\n");
		md.append("\n");

		// Summary
		md.append("## Summary\n\n");
		if (results.isEmpty() && methodResults.isEmpty()) {
			md.append("**No order-dependent tests detected.** ✓\n\n");
		} else {
			md.append("| Finding Type | Count |\n");
			md.append("|--------------|-------|\n");
			md.append("| Victims (polluted by another test) | ")
					.append(results.stream().filter(r -> r.type() == ODType.VICTIM).count()).append(" |\n");
			md.append("| Brittles (depend on a setter test) | ")
					.append(results.stream().filter(r -> r.type() == ODType.BRITTLE).count()).append(" |\n");
			if (!methodResults.isEmpty()) {
				md.append("| Method-level OD (intra-class) | ").append(methodResults.size()).append(" |\n");
			}
			md.append("| **Total** | **").append(results.size() + methodResults.size()).append("** |\n\n");
		}

		if (!results.isEmpty()) {
			md.append("## Findings\n\n");
			md.append("| # | Victim | Type | Confidence | Chain |\n");
			md.append("|---|--------|------|------------|-------|\n");
			for (int i = 0; i < results.size(); i++) {
				ODResult r = results.get(i);
				md.append("| ").append(i + 1).append(" | `").append(r.victim()).append("` | ").append(r.type().name())
						.append(" | ").append(String.format("%.0f%%", r.confidence() * 100)).append(" | ")
						.append(r.dependencyChain().stream().map(s -> "`" + s + "`").collect(Collectors.joining(" → ")))
						.append(" |\n");
			}

			// Detailed findings
			md.append("\n### Details\n\n");
			for (int i = 0; i < results.size(); i++) {
				ODResult r = results.get(i);
				md.append("#### ").append(i + 1).append(". `").append(r.victim()).append("`\n\n");
				md.append("- **Type**: ").append(r.type().name()).append("\n");
				md.append("- **Confidence**: ").append(String.format("%.0f%%", r.confidence() * 100)).append("\n");
				md.append("- **Description**: ").append(r.description()).append("\n");
				if (!r.dependencyChain().isEmpty()) {
					md.append("- **Dependency chain**: ");
					md.append(r.dependencyChain().stream().map(s -> "`" + s + "`").collect(Collectors.joining(" → ")));
					md.append("\n");
				}
				md.append("\n");
			}
		}

		if (!cons.isEmpty()) {
			md.append("## Ordering Constraints\n\n");
			md.append("These constraints can be applied to prevent OD failures:\n\n");
			md.append("| # | Type | Constraint | Reason |\n");
			md.append("|---|------|------------|--------|\n");
			for (int i = 0; i < cons.size(); i++) {
				OrderConstraintManager.Constraint c = cons.get(i);
				md.append("| ").append(i + 1).append(" | ").append(c.type().name()).append(" | `").append(c.testA())
						.append("` → `").append(c.testB()).append("` | ").append(c.reason()).append(" |\n");
			}
		}

		// Method-level findings
		if (!methodResults.isEmpty()) {
			md.append("\n## Method-Level Findings\n\n");
			md.append("Order-dependent test methods detected within individual test classes:\n\n");
			md.append("| # | Class | Victim Method | Type | Confidence | Chain |\n");
			md.append("|---|-------|---------------|------|------------|-------|\n");
			for (int i = 0; i < methodResults.size(); i++) {
				MethodLevelDetection.MethodODResult mr = methodResults.get(i);
				String shortClass = mr.testClass().contains(".")
						? mr.testClass().substring(mr.testClass().lastIndexOf('.') + 1)
						: mr.testClass();
				md.append("| ").append(i + 1).append(" | `").append(shortClass).append("` | `")
						.append(mr.victimMethod()).append("` | ").append(mr.type().name()).append(" | ")
						.append(String.format("%.0f%%", mr.confidence() * 100)).append(" | ").append(mr
								.dependencyChain().stream().map(s -> "`" + s + "`").collect(Collectors.joining(" → ")))
						.append(" |\n");
			}

			// Group by class for details
			md.append("\n### Method-Level Details\n\n");
			Map<String, List<MethodLevelDetection.MethodODResult>> byClass = new LinkedHashMap<>();
			for (MethodLevelDetection.MethodODResult mr : methodResults) {
				byClass.computeIfAbsent(mr.testClass(), k -> new ArrayList<>()).add(mr);
			}
			for (var entry : byClass.entrySet()) {
				md.append("#### `").append(entry.getKey()).append("`\n\n");
				for (MethodLevelDetection.MethodODResult mr : entry.getValue()) {
					md.append("- **").append(mr.type().name()).append("**: `").append(mr.victimMethod()).append("` — ")
							.append(mr.description()).append("\n");
				}
				md.append("\n");
			}
		}

		md.append("\n---\n*Generated by test-order detect-dependencies*\n");

		Files.writeString(mdPath, md.toString());
		return new Path[]{jsonPath, mdPath};
	}

	private static String formatDuration(long millis) {
		if (millis < 1000)
			return millis + "ms";
		long seconds = millis / 1000;
		if (seconds < 60)
			return seconds + "s";
		long minutes = seconds / 60;
		seconds = seconds % 60;
		return minutes + "m " + seconds + "s";
	}
}
