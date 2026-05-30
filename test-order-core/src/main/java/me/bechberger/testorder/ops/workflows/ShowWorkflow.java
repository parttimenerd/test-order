package me.bechberger.testorder.ops.workflows;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
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
	 */
	public record Options(boolean classes, Boolean methods, Boolean ml, boolean explain, boolean fullNames,
			String format, String filter, int topN, int randomM, Long seed) {

		/** Default: show classes, auto-detect methods and ML, text format. */
		public static Options defaults() {
			return new Options(true, null, null, false, false, "text", null, -1, 10, null);
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
		// Single shared analysis pass
		ChangeAnalysis.Result analysis = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FULL);

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

		// ── Preamble ─────────────────────────────────────────────────
		out.println(buildPreamble(result, opts));
		out.println();

		// ── Class order section ──────────────────────────────────────
		if (result.classOrder() != null) {
			out.println("═══ Class Order ══════════════════════════════════════");
			out.println(
					"[test-order] Score legend: higher score = higher priority; Deps=changed/total, Fail=failure signal.");
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
				ShowOrderOperation.printReport(out, ranked, result.classOrder().scorer(),
						result.classOrder().changedClasses(), result.classOrder().changedTests(),
						result.classOrder().weights(), opts.explain(), true, true, opts.fullNames());

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
				TestSelector.Selection selection = new TestSelector(result.analysis().depMap(),
						result.analysis().state(), result.analysis().changedClasses(), result.analysis().changedTests(),
						result.analysis().weights(), new TestSelector.Config(opts.topN(), opts.randomM(), opts.seed()),
						alwaysRun, result.analysis().changeComplexity()).select();
				ShowOrderWorkflow.printSelectionPreview(out, selection, opts.fullNames(), opts.topN(), opts.randomM());
			}
		} else if (opts.classes()) {
			out.println("[test-order] Class order unavailable: no learned dependency index data found.");
			out.println("[test-order] Next step: run tests once in learn mode, then run show again.");
			out.println("[test-order] Example: mvn test -Dtestorder.mode=learn");
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
				ShowMethodOrderWorkflow.printMethodOrderReport(out, new ShowMethodOrderWorkflow.ShowMethodOrderResult(
						classOrders, mo.changedClasses(), mo.changedMethods(), mo.weights()), opts.explain());
			}
		} else if (!Boolean.FALSE.equals(opts.methods())) {
			out.println();
			out.println("[test-order] Method order unavailable: no method telemetry found yet.");
			out.println("[test-order] Run tests again after enabling method ordering to collect per-method data.");
		}

		// ── ML health section ────────────────────────────────────────
		if (result.healthReport() != null) {
			out.println();
			out.println("═══ ML Health ════════════════════════════════════════");
			out.println(result.healthReport().formatSummary());
		} else if (!Boolean.FALSE.equals(opts.ml())) {
			out.println();
			out.println("[test-order] ML health unavailable: no ML history found.");
			out.println("[test-order] To enable: run tests with -Dtestorder.ml.enabled=true and collect a few runs.");
		}
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
