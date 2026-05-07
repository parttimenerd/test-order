package me.bechberger.testorder.ops.workflows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestScorer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;
import me.bechberger.testorder.changes.StructuralDiff;
import me.bechberger.testorder.ops.ChangeDetectionOps;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.ShowOrderOperation;
import me.bechberger.testorder.ops.TestClassDiscovery;
import me.bechberger.testorder.ops.WeightResolverOperation;

/**
 * Shared analysis step used by all scoring workflows (order, select,
 * show-order, dashboard). Loads the index and state, detects changes, resolves
 * weights, and optionally performs structural analysis.
 *
 * <p>
 * Extracting this eliminates ~60 lines of duplicated orchestration that was
 * copy-pasted across every workflow.
 */
public final class ChangeAnalysis {

	private ChangeAnalysis() {
	}

	/** Full analysis result — everything a workflow needs to score tests. */
	public record Result(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
			Set<String> changedTests, Set<String> changedMethods, TestOrderState.ScoringWeights weights,
			TestOrderState.LoadedWeights loadedWeights, ChangedMembers changedMembers,
			Map<String, Double> changeComplexity, Set<String> allTests) {

		/** Builds a scorer from this analysis. */
		public TestScorer buildScorer() {
			return ShowOrderOperation.buildScorer(weights, depMap, state, changedClasses, changedTests, changedMembers,
					changeComplexity);
		}
	}

	/**
	 * Options that control which analysis steps to run. Workflows that don't need
	 * structural analysis or method-level changes can skip them.
	 */
	public record Options(boolean includeMethodChanges, boolean includeStructuralAnalysis, boolean includeAllTests,
			boolean filterToModule) {

		/** All analysis steps enabled. Used by show-order and dashboard. */
		public static final Options FULL = new Options(true, true, true, false);

		/** Change detection + weights only. Used by order mode. */
		public static final Options CHANGES_ONLY = new Options(true, false, false, false);

		/** Change detection + weights + module filtering. Used by select. */
		public static final Options FOR_SELECTION = new Options(false, false, false, true);

		/** Method changes + module filtering. Used by auto (combined select+order). */
		public static final Options FOR_AUTO = new Options(true, false, false, true);
	}

	/**
	 * Loads the dependency index and state, detects changes, resolves weights, and
	 * (optionally) runs structural analysis.
	 *
	 * @param ctx
	 *            plugin context
	 * @param opts
	 *            controls which analysis steps to perform
	 * @return analysis result
	 * @throws IOException
	 *             if loading index/state fails
	 */
	public static Result analyze(PluginContext ctx, Options opts) throws IOException {
		Path indexPath = ctx.indexFile();
		if (!Files.exists(indexPath)) {
			throw new IOException(
					"[test-order] Index file not found: " + indexPath + ". Run tests in learn mode first.");
		}

		DependencyMap depMap;
		try {
			depMap = DependencyMap.load(indexPath);
		} catch (IOException e) {
			throw new IOException("[test-order] Failed to load dependency index: " + indexPath
					+ " — the file may be corrupt. Run tests in learn mode to regenerate it.", e);
		}
		if (opts.filterToModule() && ctx.testClassesDir() != null && Files.isDirectory(ctx.testClassesDir())) {
			depMap = TestClassDiscovery.filterToModule(depMap, ctx.testClassesDir());
		}

		TestOrderState state;
		if (Files.exists(ctx.stateFile())) {
			try {
				state = TestOrderState.load(ctx.stateFile());
			} catch (IOException e) {
				ctx.log()
						.warn("[test-order] Failed to load state: " + e.getMessage() + " — starting with fresh state.");
				state = new TestOrderState();
			}
		} else {
			state = new TestOrderState();
		}

		// ── Change detection ────────────────────────────────────────
		Set<String> changed = ChangeDetectionOps.detectChangedClasses(ctx.changeMode(), ctx.projectRoot(),
				ctx.sourceRoot(), ctx.hashFile(), ctx.changedClasses(), true, ctx.log());

		Set<String> changedTests = ChangeDetectionOps.detectChangedTestClasses(ctx.changeMode(), ctx.projectRoot(),
				ctx.testSourceRoot(), ctx.testHashFile(), ctx.changedTestClasses(), true, ctx.log());

		Set<String> changedMethods = Set.of();
		if (opts.includeMethodChanges() && ctx.methodOrderingEnabled() && ctx.methodHashFile() != null) {
			changedMethods = ChangeDetectionOps.detectChangedMethods(ctx.testSourceRoot(), ctx.methodHashFile(),
					ctx.log());
		}

		// ── Weight resolution ───────────────────────────────────────
		TestOrderState.LoadedWeights lw = WeightResolverOperation.resolveLoadedWeights(ctx.weightsFile(), state,
				ctx.log());
		TestOrderState.ScoringWeights sw = applyOverrides(lw.weights(), ctx.scoreOverrides());

		// ── Structural analysis (optional) ──────────────────────────
		ChangedMembers changedMembers = null;
		List<StructuralDiff.FileDiff> structuralDiffs = null;
		if (opts.includeStructuralAnalysis() && !changed.isEmpty()) {
			String structMode = resolveStructuralDiffMode(ctx.changeMode(), ctx.changedClasses(), ctx.hashFile());
			StructuralChangeAnalyzer.AnalysisResult analysis = ShowOrderOperation
					.analyzeStructuralChanges(ctx.projectRoot(), structMode);
			if (analysis != null) {
				changedMembers = analysis.changedMembers();
				structuralDiffs = analysis.diffs();
			}
		}

		Map<String, Double> changeComplexityMap = !changed.isEmpty()
				? ShowOrderOperation.computeChangeComplexity(changed, ctx.allSourceRoots(), changedMembers,
						structuralDiffs)
				: Map.of();

		// ── Collect all tests (optional) ────────────────────────────
		Set<String> allTests = opts.includeAllTests()
				? ShowOrderOperation.collectAllTests(depMap, changedTests, ctx.testClassesDir())
				: Set.of();

		return new Result(depMap, state, changed, changedTests, changedMethods, sw, lw, changedMembers,
				changeComplexityMap, allTests);
	}

	// ── Internals ───────────────────────────────────────────────────

	/**
	 * Applies user score overrides to base weights.
	 */
	private static TestOrderState.ScoringWeights applyOverrides(TestOrderState.ScoringWeights base,
			Map<String, Integer> overrides) {
		if (overrides == null || overrides.isEmpty()) {
			return base;
		}
		return WeightResolverOperation.applyOverrides(base, overrides.get("newTest"), overrides.get("changedTest"),
				overrides.get("maxFailure"), overrides.get("speed"), overrides.get("speedPenalty"),
				overrides.get("depOverlap"), overrides.get("changeComplexity"), overrides.get("staticFieldBonus"),
				overrides.get("coverageBonus"));
	}

	/**
	 * Resolves which git-diff mode to use for structural analysis. Returns
	 * {@code null} when structural analysis is not applicable.
	 */
	static String resolveStructuralDiffMode(String changeMode, String changedClasses, Path hashFile) {
		if (changeMode == null || changeMode.isBlank()) {
			return null;
		}
		return switch (changeMode) {
			case "since-last-commit" -> "since-last-commit";
			case "uncommitted" -> "uncommitted";
			case "explicit", "since-last-run" -> null;
			case "auto" -> {
				if (changedClasses != null && !changedClasses.isBlank()) {
					yield null;
				}
				yield (hashFile != null && Files.exists(hashFile)) ? null : "since-last-commit";
			}
			default -> null;
		};
	}
}
