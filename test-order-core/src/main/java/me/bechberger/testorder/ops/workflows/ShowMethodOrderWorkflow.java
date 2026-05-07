package me.bechberger.testorder.ops.workflows;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.MethodOrderingEngine;
import me.bechberger.testorder.MethodOrderingEngine.ClassMethodOrder;
import me.bechberger.testorder.MethodOrderingEngine.OrderedMethod;
import me.bechberger.testorder.MethodScorer.MethodScoreResult;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.ops.PluginContext;

/**
 * Show-method-order workflow: compute and display the predicted method
 * execution order within each test class. Similar to ShowOrderWorkflow but
 * operates at method-level granularity.
 */
public final class ShowMethodOrderWorkflow {

	private ShowMethodOrderWorkflow() {
	}

	/**
	 * Result of computing method order across all classes.
	 */
	public record ShowMethodOrderResult(List<ClassMethodOrder> classOrders, Set<String> changedClasses,
			Set<String> changedMethods, TestOrderState.MethodScoringWeights weights) {
	}

	/**
	 * Computes the predicted method order for all test classes with telemetry.
	 */
	public static ShowMethodOrderResult compute(PluginContext ctx) throws IOException {
		ChangeAnalysis.Result a = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FULL);

		TestOrderState.MethodScoringWeights weights = a.state().methodScoringWeights();

		List<ClassMethodOrder> classOrders = MethodOrderingEngine.orderAllMethods(a.state(), a.depMap(),
				a.changedClasses(), a.changedMethods(), weights);

		return new ShowMethodOrderResult(classOrders, a.changedClasses(), a.changedMethods(), weights);
	}

	/**
	 * Computes and prints the predicted method order report.
	 *
	 * @param ctx
	 *            plugin context
	 * @param out
	 *            output stream
	 * @param explain
	 *            if true, show per-method score breakdown
	 * @return the computed result
	 */
	public static ShowMethodOrderResult printReport(PluginContext ctx, PrintStream out, boolean explain)
			throws IOException {
		ShowMethodOrderResult result = compute(ctx);

		if (result.classOrders().isEmpty()) {
			ctx.log().info("[test-order] No method telemetry found. Run tests with method ordering enabled first.");
			return result;
		}

		printMethodOrderReport(out, result, explain);
		return result;
	}

	/**
	 * Prints the method order report in a table format grouped by class.
	 */
	public static void printMethodOrderReport(PrintStream out, ShowMethodOrderResult result, boolean explain) {
		out.println("=== Test Method Execution Order ===");
		out.println();

		for (ClassMethodOrder classOrder : result.classOrders()) {
			String shortClass = shortenClassName(classOrder.className());
			out.println("  " + shortClass + " (" + classOrder.methods().size() + " methods):");

			for (int i = 0; i < classOrder.methods().size(); i++) {
				OrderedMethod m = classOrder.methods().get(i);
				if (explain) {
					printExplainLine(out, i + 1, m, result);
				} else {
					out.printf(Locale.US, "    %3d. %-40s  score=%.2f%n", i + 1, m.methodName(), m.score());
				}
			}
			out.println();
		}

		out.println("Total: " + result.classOrders().size() + " classes with method ordering");
	}

	private static void printExplainLine(PrintStream out, int rank, OrderedMethod m, ShowMethodOrderResult result) {
		MethodScoreResult d = m.details();
		out.printf(Locale.US, "    %3d. %-40s  score=%.2f%n", rank, m.methodName(), m.score());

		StringBuilder flags = new StringBuilder("         ");
		if (d.isNew())
			flags.append("[NEW] ");
		if (d.isChanged())
			flags.append("[CHANGED] ");
		if (d.isRecent())
			flags.append("[RECENT_FAIL] ");
		if (d.isFast())
			flags.append("[FAST] ");
		if (d.isSlow())
			flags.append("[SLOW] ");
		if (flags.length() > 9) {
			out.println(flags);
		}

		out.printf("         failRecency=%.2f  speed=%.2f  depOverlap=%.2f  coverage=%.2f  new=%.2f  changed=%.2f%n",
				d.failureRecencyBonus(), d.speedBonus(), d.depOverlapBonus(), d.coverageBonus(), d.newMethodBonus(),
				d.changedMethodBonus());
	}

	private static String shortenClassName(String fqcn) {
		int lastDot = fqcn.lastIndexOf('.');
		if (lastDot < 0)
			return fqcn;
		// Keep last package segment + class name
		int prevDot = fqcn.lastIndexOf('.', lastDot - 1);
		if (prevDot < 0)
			return fqcn;
		return "..." + fqcn.substring(prevDot);
	}
}
