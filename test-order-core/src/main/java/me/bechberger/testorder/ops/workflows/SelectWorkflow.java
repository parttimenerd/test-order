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
	 * Runs the full selection workflow.
	 *
	 * @param ctx
	 *            plugin context (paths, scoring config, selection params)
	 * @return selection result with selected/remaining lists written to disk
	 * @throws IOException
	 *             if loading index/state or writing test lists fails
	 */
	public static SelectOperation.SelectResult select(PluginContext ctx) throws IOException {
		ChangeAnalysis.Result a = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FOR_SELECTION);

		var alwaysRun = ctx.testClassesDir() != null
				? AlwaysRunScanner.scan(ctx.testClassesDir())
				: java.util.Set.<String>of();

		return SelectOperation.select(new SelectOperation.SelectConfig(a.depMap(), a.state(), a.changedClasses(),
				a.changedTests(), a.weights(), ctx.topN(), ctx.randomM(), ctx.seed(), alwaysRun, ctx.selectedFile(),
				ctx.remainingFile(), ctx.log(), a.changeComplexity()));
	}
}
