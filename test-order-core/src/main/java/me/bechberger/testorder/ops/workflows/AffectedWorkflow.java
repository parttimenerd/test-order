package me.bechberger.testorder.ops.workflows;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

import me.bechberger.testorder.ops.AffectedOperation;
import me.bechberger.testorder.ops.AlwaysRunScanner;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.ShowOrderOperation;

/**
 * Test selection workflow: load index → detect changes → resolve weights →
 * discover @AlwaysRun → select top-N + random-M tests.
 */
public final class AffectedWorkflow {

	private AffectedWorkflow() {
	}

	/**
	 * Result including both selection and the change analysis (avoids re-running
	 * analysis).
	 */
	public record SelectWithAnalysis(AffectedOperation.SelectResult result, ChangeAnalysis.Result analysis) {
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
	public static AffectedOperation.SelectResult select(PluginContext ctx) throws IOException {
		return selectWithAnalysis(ctx).result();
	}

	/**
	 * Runs the full selection workflow and returns both the selection result and
	 * the change analysis result (R7-13: avoids double change detection).
	 */
	public static SelectWithAnalysis selectWithAnalysis(PluginContext ctx) throws IOException {
		ChangeAnalysis.Result a = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FOR_SELECTION);

		var alwaysRun = AlwaysRunScanner.scanOrEmpty(ctx.testClassesDir());

		// Include tests discovered from testClassesDir that are not yet in the dep map
		// as "changedTests" so the selector treats them as new and selects them.
		// This handles modules whose tests haven't been learned yet (e.g., a submodule
		// that was never included in a learn phase but still has compiled test
		// classes).
		Set<String> changedAndNew = new LinkedHashSet<>(a.changedTests());
		Set<String> depMapTests = a.depMap().testClasses();
		for (String t : ShowOrderOperation.collectAllTests(a.depMap(), a.changedTests(), ctx.testClassesDir())) {
			if (!depMapTests.contains(t))
				changedAndNew.add(t);
		}

		AffectedOperation.SelectResult result = AffectedOperation.select(new AffectedOperation.SelectConfig(a.depMap(),
				a.state(), a.changedClasses(), changedAndNew, a.weights(), ctx.topN(), ctx.randomM(), ctx.seed(),
				alwaysRun, ctx.selectedFile(), ctx.remainingFile(), ctx.log(), a.changeComplexity()));
		return new SelectWithAnalysis(result, a);
	}
}
