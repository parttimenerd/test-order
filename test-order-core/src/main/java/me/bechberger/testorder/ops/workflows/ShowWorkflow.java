package me.bechberger.testorder.ops.workflows;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.MethodOrderingEngine;
import me.bechberger.testorder.MethodOrderingEngine.ClassMethodOrder;
import me.bechberger.testorder.OrderReportPrinter;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestScorer;
import me.bechberger.testorder.TestSelector;
import me.bechberger.testorder.ml.MLHealthLoader;
import me.bechberger.testorder.ml.TestHealthReport;
import me.bechberger.testorder.ops.AlwaysRunScanner;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.ShowOrderOperation;

/**
 * Unified show workflow: replaces the separate show-order, show-method-order,
 * and analyze workflows with a single entry point that auto-detects available
 * data and produces a combined report.
 *
 * <p>
 * Usage: {@code mvn test-order:show}
 */
public final class ShowWorkflow {

	/**
	 * True once a preamble has been printed this JVM run — suppresses repeats in
	 * multi-module builds.
	 */
	private static final AtomicBoolean PREAMBLE_PRINTED = new AtomicBoolean(false);

	private ShowWorkflow() {
	}

	// ── Options ──────────────────────────────────────────────────────

	/**
	 * Configuration for the show workflow.
	 *
	 * @param classes
	 *            include class-level order section
	 * @param methods
	 *            include method-level order (null = auto-detect)
	 * @param ml
	 *            include ML health section (null = auto-detect)
	 * @param explain
	 *            verbose per-item score breakdown
	 * @param fullNames
	 *            full class names instead of abbreviated
	 * @param format
	 *            "text" or "json"
	 * @param filter
	 *            glob pattern to filter test names (null = no filter)
	 * @param topN
	 *            selection preview: top N tests (-1 = all)
	 * @param randomM
	 *            selection preview: random M diversity tests
	 * @param seed
	 *            selection preview: random seed (null = non-deterministic)
	 * @param displayLimit
	 *            max rows to display in the class-order table per module (default
	 *            20; use -1 to show all)
	 * @param excludePatterns
	 *            Surefire {@code <excludes>} glob/regex patterns; classes matching
	 *            any are dropped from the selection preview so it does not
	 *            advertise tests that will never run (BUG-172). Empty = no
	 *            filtering.
	 */
	public record Options(boolean classes, Boolean methods, Boolean ml, boolean explain, boolean fullNames,
			String format, String filter, int topN, int randomM, Long seed, int displayLimit,
			List<String> excludePatterns) {

		/** Default: show classes, auto-detect methods and ML, text format. */
		public static Options defaults() {
			return new Options(true, null, null, false, false, "text", null, -1, 10, null, 20, List.of());
		}

		/**
		 * Backward-compatible constructor without excludePatterns (defaults to empty).
		 */
		public Options(boolean classes, Boolean methods, Boolean ml, boolean explain, boolean fullNames, String format,
				String filter, int topN, int randomM, Long seed, int displayLimit) {
			this(classes, methods, ml, explain, fullNames, format, filter, topN, randomM, seed, displayLimit,
					List.of());
		}

		/**
		 * Backward-compatible constructor without displayLimit (defaults to 20).
		 */
		public Options(boolean classes, Boolean methods, Boolean ml, boolean explain, boolean fullNames, String format,
				String filter, int topN, int randomM, Long seed) {
			this(classes, methods, ml, explain, fullNames, format, filter, topN, randomM, seed, 20, List.of());
		}

		public Options {
			excludePatterns = excludePatterns == null ? List.of() : List.copyOf(excludePatterns);
		}

		public boolean isJson() {
			return "json".equalsIgnoreCase(format);
		}

		public boolean showAll() {
			return classes && Boolean.TRUE.equals(methods) && Boolean.TRUE.equals(ml);
		}
	}

	// ── Result ───────────────────────────────────────────────────────

	/**
	 * Combined result from all show sections.
	 */
	public record ShowResult(
			/** Class-level ordering (null if classes=false) */
			ShowOrderWorkflow.ShowOrderResult classOrder,
			/** Method-level ordering (null if not requested or no data) */
			ShowMethodOrderWorkflow.ShowMethodOrderResult methodOrder,
			/** ML health report (null if not requested or no history) */
			TestHealthReport healthReport,
			/** ML failure predictions (null if not available) */
			Map<String, Double> mlPredictions,
			/** The shared analysis result */
			ChangeAnalysis.Result analysis) {
	}

	// ── Compute ──────────────────────────────────────────────────────

	/**
	 * Computes all requested sections, auto-detecting available data for
	 * unspecified sections. Individual sections that fail are logged and skipped
	 * rather than aborting the entire report.
	 *
	 * @param ctx
	 *            plugin context
	 * @param opts
	 *            show options
	 * @param mlHistoryDir
	 *            path to ML history directory (null if unknown)
	 * @return combined result
	 */
	public static ShowResult compute(PluginContext ctx, Options opts, Path mlHistoryDir) throws IOException {
		// Single shared analysis pass. When a specific module is targeted
		// (testClassesDir
		// or testSourceRoot is set), filter the shared reactor index to this module's
		// tests only so that `show` from a submodule doesn't display every module's
		// tests.
		boolean hasModuleScope = (ctx.testClassesDir() != null && java.nio.file.Files.isDirectory(ctx.testClassesDir()))
				|| (ctx.testSourceRoot() != null && java.nio.file.Files.isDirectory(ctx.testSourceRoot()));
		// Warn when invoked from a module context but no test sources are found.
		// This typically means the module has only production code; showing the full
		// project index would be misleading.
		if (!hasModuleScope && ctx.currentModuleId() != null && !ctx.currentModuleId().isEmpty()) {
			ctx.log()
					.info("[test-order] No test sources found in this module — showing full project test index."
							+ " Run '" + ctx.buildSystem().showCommand()
							+ "' at the project root for a complete cross-module view.");
		}
		ChangeAnalysis.Options analysisOpts = hasModuleScope
				? ChangeAnalysis.Options.FULL_FILTERED
				: ChangeAnalysis.Options.FULL;
		ChangeAnalysis.Result analysis = ChangeAnalysis.analyze(ctx, analysisOpts);

		// ── Class order ──────────────────────────────────────────────
		ShowOrderWorkflow.ShowOrderResult classOrder = null;
		if (opts.classes()) {
			classOrder = tryCompute("Class order computation", () -> {
				TestScorer scorer = analysis.buildScorer();
				// Filter out classes that have never been observed running and are not in the
				// dep map — these are typically abstract base classes or classes excluded by
				// Surefire that will always appear as [NEW] (B24). Only apply the filter when
				// at least one class has been observed (i.e., this is not a fresh project).
				Set<String> allTests = analysis.allTests();
				if (!analysis.state().getClassDurations().isEmpty()) {
					allTests = allTests.stream()
							.filter(cls -> analysis.depMap() != null && analysis.depMap().testClasses().contains(cls)
									|| analysis.state().getDuration(cls, -1L) >= 0)
							.collect(Collectors.toSet());
				}
				List<OrderReportPrinter.RankedTest> ranked = OrderReportPrinter.rankTests(allTests, scorer,
						analysis.state());
				return new ShowOrderWorkflow.ShowOrderResult(ranked, scorer, analysis.changedClasses(),
						analysis.changedTests(), analysis.weights(), analysis.state(), analysis.depMap(),
						analysis.changeComplexity());
			});
		}

		// ── Method order ─────────────────────────────────────────────
		ShowMethodOrderWorkflow.ShowMethodOrderResult methodOrder = null;
		boolean showMethods = Boolean.TRUE.equals(opts.methods());
		if (opts.methods() == null) {
			// auto-detect: show if method duration data exists in state
			showMethods = !analysis.state().getMethodDurations().isEmpty();
		}
		if (showMethods) {
			methodOrder = tryCompute("Method order computation", () -> {
				TestOrderState.MethodScoringWeights weights = analysis.state().methodScoringWeights();
				List<ClassMethodOrder> classOrders = MethodOrderingEngine.orderAllMethods(analysis.state(),
						analysis.depMap(), analysis.changedClasses(), analysis.changedMethods(), weights);
				// When showing from a submodule, filter method order to only classes in scope.
				// The analysis.allTests() set is the authoritative list for this module.
				if (hasModuleScope && !analysis.allTests().isEmpty()) {
					classOrders = classOrders.stream().filter(co -> analysis.allTests().contains(co.className()))
							.collect(java.util.stream.Collectors.toList());
				}
				return classOrders.isEmpty()
						? null
						: new ShowMethodOrderWorkflow.ShowMethodOrderResult(classOrders, analysis.changedClasses(),
								analysis.changedMethods(), weights);
			});
		}

		// ── ML health ────────────────────────────────────────────────
		TestHealthReport healthReport = null;
		Map<String, Double> mlPredictions = null;
		boolean showMl = Boolean.TRUE.equals(opts.ml());
		if (opts.ml() == null && mlHistoryDir != null) {
			// auto-detect: show if ML history file exists
			showMl = Files.exists(mlHistoryDir.resolve("history.lz4"));
		}
		if (showMl && mlHistoryDir != null) {
			final Path histDir = mlHistoryDir;
			healthReport = tryCompute("ML health loading", () -> MLHealthLoader.load(histDir).healthReport());
		}

		return new ShowResult(classOrder, methodOrder, healthReport, mlPredictions, analysis);
	}

	/**
	 * Runs {@code fn} and returns the result, or returns {@code null} if any
	 * exception is thrown — logging a standard warning in that case.
	 */
	@SuppressWarnings("unchecked")
	private static <T> T tryCompute(String component, java.util.concurrent.Callable<T> fn) {
		try {
			return fn.call();
		} catch (Exception e) {
			System.err.println("[test-order] WARN: " + component + " failed: " + e.getMessage());
			return null;
		}
	}

	// ── Print (text) ─────────────────────────────────────────────────

	/**
	 * Prints the combined text report to the output stream.
	 */
	public static void printReport(PrintStream out, ShowResult result, Options opts, PluginContext ctx) {
		Predicate<String> filter = compileFilter(opts.filter());

		// ── Preamble — only print once per JVM to avoid noise in multi-module builds
		// ──
		if (PREAMBLE_PRINTED.compareAndSet(false, true)) {
			out.println(buildPreamble(result, opts));
			out.println(
					"[test-order] Score legend: higher score = higher priority; Deps=changed/total, Fail=failure signal.");
			out.println();
		}

		// ── Class order section ──────────────────────────────────────
		if (result.classOrder() != null) {
			out.println("═══ Class Order ══════════════════════════════════════");
			List<OrderReportPrinter.RankedTest> ranked = result.classOrder().ranked();
			if (filter != null) {
				ranked = ranked.stream().filter(r -> filter.test(r.name())).toList();
			}

			if (ranked.isEmpty()) {
				if (opts.filter() != null && !opts.filter().isBlank()) {
					out.println("[test-order] No test classes match filter: '" + opts.filter() + "'.");
					out.println(
							"[test-order] Tip: use a broader pattern (for example '*Test') or omit testorder.show.filter.");
				} else {
					out.println("[test-order] No test classes available for display.");
				}
			} else {
				DependencyMap depMap = result.classOrder().depMap();
				if (depMap != null && depMap.hasModuleMap()) {
					printClassOrderByModule(out, ranked, result, opts);
				} else {
					ShowOrderOperation.printReport(out, ranked, result.classOrder().scorer(),
							result.classOrder().changedClasses(), result.classOrder().changedTests(),
							result.classOrder().weights(), opts.explain(), true, true, opts.fullNames(),
							opts.displayLimit());
				}

				// Hint when all scores are zero — users see a wall of zeros with no guidance
				if (result.classOrder().changedClasses().isEmpty() && result.classOrder().changedTests().isEmpty()
						&& ranked.stream().allMatch(r -> r.score().score() == 0)) {
					out.println("[test-order] All scores are 0 because no source changes were detected.");
					out.println("[test-order] Scores increase when you modify source files that tests depend on.");
					out.println(
							"[test-order] Try: modify a source file, or use -Dtestorder.changeMode=since-last-commit");
				}
			}

			// ── Selection preview ────────────────────────────────────
			if (!ranked.isEmpty() && (opts.topN() >= 0 || opts.randomM() > 0)) {
				out.println();
				out.println("═══ Selection Preview ════════════════════════════════");
				Set<String> alwaysRun = AlwaysRunScanner.scanOrEmpty(ctx.testClassesDir());
				// Include tests discovered from testClassesDir that aren't in the dep map yet
				// (they are "new" from the selector's perspective and should always be
				// selected).
				Set<String> changedAndNew = new java.util.LinkedHashSet<>(result.analysis().changedTests());
				Set<String> depMapTests = result.analysis().depMap().testClasses();
				for (String t : result.analysis().allTests()) {
					if (!depMapTests.contains(t))
						changedAndNew.add(t);
				}
				TestSelector.Selection selection = new TestSelector(result.analysis().depMap(),
						result.analysis().state(), result.analysis().changedClasses(), changedAndNew,
						result.analysis().weights(), new TestSelector.Config(opts.topN(), opts.randomM(), opts.seed()),
						alwaysRun, result.analysis().changeComplexity()).select();
				// BUG-172: drop Surefire <excludes>'d classes from the preview so it matches
				// what `affected`/`auto` actually run (configureIncludes filters them there).
				selection = filterExcluded(selection, opts.excludePatterns());
				ShowOrderWorkflow.printSelectionPreview(out, selection, opts.fullNames(), opts.topN(), opts.randomM());
			}
		} else if (opts.classes()) {
			out.println("[test-order] Class order unavailable: no learned dependency index data found.");
			out.println("Run: " + (ctx != null ? ctx.learnCommand() : "mvn test -Dtestorder.mode=learn"));
		}

		// ── Method order section ─────────────────────────────────────
		if (result.methodOrder() != null) {
			out.println();
			out.println("═══ Method Order ═════════════════════════════════════");
			ShowMethodOrderWorkflow.ShowMethodOrderResult mo = result.methodOrder();
			List<ClassMethodOrder> classOrders = mo.classOrders();
			if (filter != null) {
				classOrders = classOrders.stream().filter(co -> filter.test(co.className())).toList();
			}
			if (!classOrders.isEmpty()) {
				ShowMethodOrderWorkflow.printMethodOrderReport(out,
						new ShowMethodOrderWorkflow.ShowMethodOrderResult(classOrders, mo.changedClasses(),
								mo.changedMethods(), mo.weights()),
						opts.explain(), opts.displayLimit());
			}
		} else if (Boolean.TRUE.equals(opts.methods())) {
			// Only show "unavailable" noise when the user explicitly requested methods=true
			out.println();
			out.println("[test-order] Method order unavailable: no method telemetry found yet.");
			out.println("[test-order] Run tests again after enabling method ordering to collect per-method data.");
		}

		// ── ML health section ────────────────────────────────────────
		if (result.healthReport() != null) {
			out.println();
			out.println("═══ ML Health ════════════════════════════════════════");
			out.println(result.healthReport().formatSummary());
		} else if (Boolean.TRUE.equals(opts.ml())) {
			// Only show "unavailable" noise when the user explicitly requested ml=true
			out.println();
			out.println("[test-order] ML health unavailable: no ML history found.");
			out.println("[test-order] To enable: run tests with -Dtestorder.ml.enabled=true and collect a few runs.");
		}
	}

	/**
	 * Returns a copy of {@code selection} with any class matching a Surefire
	 * {@code <excludes>} pattern removed from all three lists (BUG-172). Returns
	 * the input unchanged when there are no patterns.
	 */
	static TestSelector.Selection filterExcluded(TestSelector.Selection selection, List<String> excludePatterns) {
		if (excludePatterns == null || excludePatterns.isEmpty()) {
			return selection;
		}
		return new TestSelector.Selection(dropExcluded(selection.selected(), excludePatterns),
				dropExcluded(selection.remaining(), excludePatterns), selection.randomFastCount(),
				dropExcluded(selection.cached(), excludePatterns));
	}

	private static List<String> dropExcluded(List<String> names, List<String> excludePatterns) {
		List<String> out = new ArrayList<>(names.size());
		for (String n : names) {
			if (!me.bechberger.testorder.SurefireExcludeMatcher.matches(n, excludePatterns)) {
				out.add(n);
			}
		}
		return out;
	}

	// ── Module-grouped class order ────────────────────────────────────

	/**
	 * Per-module aggregate computed from the ranked test list, used for priority
	 * ordering and section headers.
	 */
	private record ModuleAggregate(String moduleId, List<OrderReportPrinter.RankedTest> tests, int affectedCount,
			long sumScores, int maxScore) {

		/**
		 * Sort key: affectedCount desc → sumScores desc → maxScore desc → moduleId asc.
		 * Mirrors
		 * {@link me.bechberger.testorder.ops.ReactorOrderOperation.ModuleScore#compareTo}
		 * and {@code ReactorReorderer.reorder} so the show output matches actual Maven
		 * module execution order.
		 */
		static int compareByPriority(ModuleAggregate a, ModuleAggregate b) {
			int cmp = Integer.compare(b.affectedCount, a.affectedCount);
			if (cmp != 0)
				return cmp;
			cmp = Long.compare(b.sumScores, a.sumScores);
			if (cmp != 0)
				return cmp;
			cmp = Integer.compare(b.maxScore, a.maxScore);
			if (cmp != 0)
				return cmp;
			return a.moduleId.compareTo(b.moduleId);
		}
	}

	/**
	 * Prints the class order grouped by Maven module. Tests are bucketed by their
	 * owning module (from {@code DependencyMap.getModule()}), then each bucket is
	 * printed in priority order. Modules themselves are ordered by the same key the
	 * reactor reorderer uses (affected count desc → sum desc → max desc). Note this
	 * preview is <em>not</em> a literal preview of the reactor execution order —
	 * the real reactor must also respect the inter-module dependency DAG, so a
	 * lower-priority module may run earlier if a higher-priority module depends on
	 * it. Tests with no recorded module are collected in a trailing "(unknown
	 * module)" group.
	 */
	private static void printClassOrderByModule(PrintStream out, List<OrderReportPrinter.RankedTest> ranked,
			ShowResult result, Options opts) {
		DependencyMap depMap = result.classOrder().depMap();

		// Bucket tests by module, preserving priority order within each bucket
		Map<String, List<OrderReportPrinter.RankedTest>> byModule = new LinkedHashMap<>();
		List<OrderReportPrinter.RankedTest> unknown = new ArrayList<>();
		for (OrderReportPrinter.RankedTest rt : ranked) {
			String mod = depMap.getModule(rt.name());
			if (mod == null || mod.isBlank()) {
				unknown.add(rt);
			} else {
				byModule.computeIfAbsent(mod, k -> new ArrayList<>()).add(rt);
			}
		}

		// Compute per-module aggregates and sort by priority (matches reactor reorder)
		List<ModuleAggregate> aggregates = new ArrayList<>();
		for (Map.Entry<String, List<OrderReportPrinter.RankedTest>> entry : byModule.entrySet()) {
			List<OrderReportPrinter.RankedTest> tests = entry.getValue();
			int affected = 0;
			long sum = 0;
			int max = 0;
			for (OrderReportPrinter.RankedTest rt : tests) {
				int s = rt.score().score();
				if (s > 0) {
					// Only positive scores feed urgency. Negative SLOW penalties on otherwise
					// unaffected tests would produce misleading "sum=-1" headers.
					affected++;
					sum += s;
				}
				if (s > max) {
					max = s;
				}
			}
			aggregates.add(new ModuleAggregate(entry.getKey(), tests, affected, sum, max));
		}
		aggregates.sort(ModuleAggregate::compareByPriority);

		// Header: how many modules have affected tests, how many run last
		int activeCount = (int) aggregates.stream().filter(a -> a.affectedCount > 0).count();
		int deferredCount = aggregates.size() - activeCount;
		if (activeCount > 0) {
			out.println("[test-order] Module priority: " + activeCount + " affected, " + deferredCount
					+ " with no affected tests (would run last in `mvn test-order:affected`).");
			out.println("[test-order] Module sort: affected count desc → sum of scores desc → max score desc.");
			out.println(
					"[test-order] Note: real `mvn` execution further interleaves these by inter-module dependencies.");
		}

		int rank = 0;
		for (ModuleAggregate agg : aggregates) {
			out.println();
			rank++;
			String status = agg.affectedCount == 0 ? " [no affected — deferred]" : "";
			String displayId = shortenModuleId(agg.moduleId);
			out.println(String.format("── #%d Module: %s (%d tests, affected=%d, sum=%d, max=%d)%s ──", rank, displayId,
					agg.tests.size(), agg.affectedCount, agg.sumScores, agg.maxScore, status));
			ShowOrderOperation.printReport(out, agg.tests, result.classOrder().scorer(),
					result.classOrder().changedClasses(), result.classOrder().changedTests(),
					result.classOrder().weights(), opts.explain(), true, true, opts.fullNames(), opts.displayLimit());
		}

		if (!unknown.isEmpty()) {
			out.println();
			out.println("── Module: (unknown) (" + unknown.size() + " tests) ──");
			ShowOrderOperation.printReport(out, unknown, result.classOrder().scorer(),
					result.classOrder().changedClasses(), result.classOrder().changedTests(),
					result.classOrder().weights(), opts.explain(), true, true, opts.fullNames(), opts.displayLimit());
		}
	}

	/**
	 * Strips the dotted groupId prefix from a {@code groupId-artifactId} moduleId
	 * for display, e.g. {@code com.sap.cloud.sdk.cloudplatform-cloudplatform-core →
	 * cloudplatform-core}. Also handles the case where groupId == artifactId (e.g.
	 * {@code commons-codec-commons-codec → commons-codec}) by detecting the
	 * repeated-suffix pattern. Conservative: returns the input unchanged if there
	 * is no dot (single-level group) or no dash after the last dot, unless the
	 * repeated-suffix pattern applies.
	 */
	public static String shortenModuleId(String moduleId) {
		if (moduleId == null) {
			return "";
		}
		// Handle dotted groupId prefix: org.jsoup-jsoup → jsoup
		int lastDot = moduleId.lastIndexOf('.');
		if (lastDot >= 0) {
			int dash = moduleId.indexOf('-', lastDot);
			if (dash >= 0) {
				return moduleId.substring(dash + 1);
			}
			return moduleId;
		}
		// Handle single-word groupId that equals the artifactId:
		// commons-codec-commons-codec → commons-codec
		// Strategy: find the shortest prefix P such that moduleId == P + "-" + P
		int len = moduleId.length();
		// The repeated part would be moduleId[0..mid-1] where mid = (len+1)/2 at most
		// i.e. moduleId = X + "-" + X, so len = 2*|X|+1
		if (len >= 3 && (len % 2) == 1) {
			int mid = (len - 1) / 2;
			String first = moduleId.substring(0, mid);
			if (moduleId.charAt(mid) == '-' && moduleId.substring(mid + 1).equals(first)) {
				return first;
			}
		}
		return moduleId;
	}

	// ── Preamble ────────────────────────────────────────────────────

	private static String buildPreamble(ShowResult result, Options opts) {
		StringJoiner sections = new StringJoiner(", ");
		if (result.classOrder() != null) {
			sections.add("classes");
		}
		if (result.methodOrder() != null) {
			sections.add("methods" + (opts.methods() == null ? " (auto-detected)" : ""));
		}
		if (result.healthReport() != null) {
			sections.add("ML health" + (opts.ml() == null ? " (auto-detected)" : ""));
		}
		if (sections.length() == 0) {
			return "[test-order] Showing: none (no class/method/ML data detected yet).";
		}
		return "[test-order] Showing: " + sections;
	}

	// ── Filter ───────────────────────────────────────────────────────

	/**
	 * Compiles a glob pattern (or comma-separated list of patterns) into a
	 * predicate. Returns null if pattern is null/blank. Supports {@code *} (any
	 * chars) and {@code ?} (single char). Multiple patterns can be separated by
	 * commas and are combined with OR semantics. Matching is case-insensitive.
	 */
	public static Predicate<String> compileFilter(String glob) {
		if (glob == null || glob.isBlank()) {
			return null;
		}
		// Support comma-separated patterns with OR semantics
		String[] patterns = glob.split(",");
		List<Predicate<String>> predicates = new ArrayList<>(patterns.length);
		for (String part : patterns) {
			String trimmed = part.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			predicates.add(compileSingleGlob(trimmed));
		}
		if (predicates.isEmpty()) {
			return null;
		}
		return name -> predicates.stream().anyMatch(p -> p.test(name));
	}

	private static Predicate<String> compileSingleGlob(String glob) {
		StringBuilder regex = new StringBuilder();
		for (int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);
			switch (c) {
				case '*' -> regex.append(".*");
				case '?' -> regex.append(".");
				case '.' -> regex.append("\\.");
				case '$' -> regex.append("\\$");
				case '(' -> regex.append("\\(");
				case ')' -> regex.append("\\)");
				case '[' -> regex.append("\\[");
				case ']' -> regex.append("\\]");
				case '{' -> regex.append("\\{");
				case '}' -> regex.append("\\}");
				case '\\' -> regex.append("\\\\");
				case '^' -> regex.append("\\^");
				case '+' -> regex.append("\\+");
				case '|' -> regex.append("\\|");
				default -> regex.append(c);
			}
		}
		Pattern pattern = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
		return name -> pattern.matcher(name).matches();
	}
}
