package me.bechberger.testorder.ops.workflows;

import java.io.IOException;

import me.bechberger.testorder.ops.AlwaysRunScanner;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.SelectOperation;

/**
 * Test selection workflow: load index → detect changes → resolve weights →
 * discover @AlwaysRun → select top-N + random-M tests.
 */
public final class SelectWorkflow {

	private SelectWorkflow() {
	}

	/**
	 * Result including both selection and the change analysis (avoids re-running
	 * analysis).
	 */
	public record SelectWithAnalysis(SelectOperation.SelectResult result, ChangeAnalysis.Result analysis) {
	}

	/**
	 * Runs the full selection workflow.
	 *
	 * @param ctx
	 *            plugin context (paths, scoring config, selection params)
	 * @return selection result with selected/remaining lists written to disk
	 * @throws IOException
	 *             if loading index/state or writing test lists fails
	 */
	public static SelectOperation.SelectResult select(PluginContext ctx) throws IOException {
		return selectWithAnalysis(ctx).result();
	}

	/**
	 * Runs the full selection workflow and returns both the selection result and
	 * the change analysis result (R7-13: avoids double change detection).
	 */
	public static SelectWithAnalysis selectWithAnalysis(PluginContext ctx) throws IOException {
		ChangeAnalysis.Result a = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FOR_SELECTION);

		var alwaysRun = ctx.testClassesDir() != null
				? AlwaysRunScanner.scan(ctx.testClassesDir())
				: java.util.Set.<String>of();

		SelectOperation.SelectResult result = SelectOperation.select(new SelectOperation.SelectConfig(a.depMap(),
				a.state(), a.changedClasses(), a.changedTests(), a.weights(), ctx.topN(), ctx.randomM(), ctx.seed(),
				alwaysRun, ctx.selectedFile(), ctx.remainingFile(), ctx.log(), a.changeComplexity()));
		return new SelectWithAnalysis(result, a);
	}
}
