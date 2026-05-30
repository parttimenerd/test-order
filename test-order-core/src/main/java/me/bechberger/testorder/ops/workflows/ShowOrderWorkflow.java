package me.bechberger.testorder.ops.workflows;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.OrderReportPrinter;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestScorer;
import me.bechberger.testorder.TestSelector;
import me.bechberger.testorder.ops.AlwaysRunScanner;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.ShowOrderOperation;

/**
 * Show-order workflow: load index → detect changes → structural analysis →
 * build scorer → rank tests → (optionally) print report and selection preview.
 */
public final class ShowOrderWorkflow {

	private ShowOrderWorkflow() {
	}

	/**
	 * Scored and ranked test data, exposed for callers that need to process the
	 * results further (e.g. selection preview in show-order mojo).
	 */
	public record ShowOrderResult(List<OrderReportPrinter.RankedTest> ranked, TestScorer scorer,
			Set<String> changedClasses, Set<String> changedTests, TestOrderState.ScoringWeights weights,
			TestOrderState state, DependencyMap depMap, Map<String, Double> changeComplexity) {
	}

	/**
	 * Computes the predicted test order. Does not print anything.
	 */
	public static ShowOrderResult compute(PluginContext ctx) throws IOException {
		ChangeAnalysis.Result a = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FULL);

		TestScorer scorer = a.buildScorer();
		List<OrderReportPrinter.RankedTest> ranked = OrderReportPrinter.rankTests(a.allTests(), scorer, a.state());

		return new ShowOrderResult(ranked, scorer, a.changedClasses(), a.changedTests(), a.weights(), a.state(),
				a.depMap(), a.changeComplexity());
	}

	/**
	 * Computes and prints the predicted test order report.
	 */
	public static ShowOrderResult printReport(PluginContext ctx, PrintStream out, boolean explain, boolean includeTags,
			boolean showDepTotals) throws IOException {
		return printReport(ctx, out, explain, false, includeTags, showDepTotals);
	}

	/**
	 * Computes and prints the predicted test order report.
	 */
	public static ShowOrderResult printReport(PluginContext ctx, PrintStream out, boolean explain, boolean fullNames,
			boolean includeTags, boolean showDepTotals) throws IOException {
		ShowOrderResult result = compute(ctx);

		if (result.ranked().isEmpty()) {
			ctx.log().info("[test-order] No test classes found in" + " dependency index or test output.");
			return result;
		}

		// Detect all-zero scores with no changes and provide guidance
		if (result.changedClasses().isEmpty() && result.changedTests().isEmpty()
				&& result.ranked().stream().allMatch(r -> r.score().score() == 0)) {
			ctx.log().info("[test-order] No changed classes detected (changeMode="
					+ (ctx.changeMode() != null ? ctx.changeMode() : "uncommitted")
					+ "). All scores are 0. Modify a source file or try -Dtestorder.changeMode=since-last-commit.");
		}

		ShowOrderOperation.printReport(out, result.ranked(), result.scorer(), result.changedClasses(),
				result.changedTests(), result.weights(), explain, includeTags, showDepTotals, fullNames);

		return result;
	}

	/**
	 * Computes the test order, prints the report, and appends a selection preview
	 * showing which tests {@code :select} would pick with the current configuration
	 * (topN / randomM / seed from {@link PluginContext}).
	 *
	 * @param fullNames
	 *            if {@code true}, print fully-qualified class names instead of
	 *            abbreviated package prefixes
	 */
	public static ShowOrderResult printReportWithSelectionPreview(PluginContext ctx, PrintStream out, boolean explain,
			boolean fullNames, boolean includeTags, boolean showDepTotals) throws IOException {
		ShowOrderResult result = printReport(ctx, out, explain, fullNames, includeTags, showDepTotals);
		if (result.ranked().isEmpty()) {
			return result;
		}

		Set<String> alwaysRun = AlwaysRunScanner.scanOrEmpty(ctx.testClassesDir());

		// Derive a stable seed from the changed-class set when none is explicitly set,
		// so selection preview is deterministic for the same inputs.
		Long effectiveSeed = ctx.seed();
		if (effectiveSeed == null && ctx.randomM() > 0) {
			effectiveSeed = (long) result.changedClasses().stream().sorted()
					.collect(java.util.stream.Collectors.joining(",")).hashCode();
		}

		TestSelector.Selection selection = new TestSelector(result.depMap(), result.state(), result.changedClasses(),
				result.changedTests(), result.weights(),
				new TestSelector.Config(ctx.topN(), ctx.randomM(), effectiveSeed), alwaysRun, result.changeComplexity())
				.select();

		printSelectionPreview(out, selection, fullNames, ctx.topN(), ctx.randomM());
		return result;
	}

	/**
	 * Prints a selection preview showing which tests would be selected and which
	 * would remain.
	 */
	public static void printSelectionPreview(PrintStream out, TestSelector.Selection selection, boolean fullNames,
			int topN, int randomM) {
		out.println("--- select preview (topN=" + topN + ", randomM=" + randomM + ") ---");
		out.println();
		if (!selection.selected().isEmpty()) {
			out.println("  Selected (" + selection.selected().size() + "):");
			for (int i = 0; i < selection.selected().size(); i++) {
				String name = fullNames
						? selection.selected().get(i)
						: OrderReportPrinter.shortenClassName(selection.selected().get(i));
				out.println("    " + (i + 1) + ". " + name);
			}
		} else {
			out.println("  Selected: (none)");
		}
		out.println();
		if (!selection.remaining().isEmpty()) {
			out.println("  Remaining (" + selection.remaining().size() + "):");
			for (int i = 0; i < selection.remaining().size(); i++) {
				String name = fullNames
						? selection.remaining().get(i)
						: OrderReportPrinter.shortenClassName(selection.remaining().get(i));
				out.println("    " + (i + 1) + ". " + name);
			}
		} else {
			out.println("  Remaining: (none)");
		}
		out.println();
	}
}
