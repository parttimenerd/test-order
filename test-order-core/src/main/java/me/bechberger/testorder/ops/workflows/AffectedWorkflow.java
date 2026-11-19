package me.bechberger.testorder.ops.workflows;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import me.bechberger.testorder.CoverageClosure;
import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestSelector;
import me.bechberger.testorder.changes.ChangeDetectionSupport;
import me.bechberger.testorder.changes.ChangeDetector;
import me.bechberger.testorder.changes.NonSourceChangeDetector;
import me.bechberger.testorder.changes.RepoChangeListings;
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
	 *
	 * <p>
	 * Coverage-enforce gate: {@code runRemaining=true} means the caller also runs
	 * the deferred tests (full suite), so the closure check must never fail —
	 * parity with {@link AutoWorkflow}. The affected default is
	 * {@code runRemaining=false}: the deferred tests do NOT run in the same build,
	 * so the enforce fail path applies.
	 */
	public static SelectWithAnalysis selectWithAnalysis(PluginContext ctx) throws IOException {
		return selectWithAnalysis(ctx, false);
	}

	/**
	 * As {@link #selectWithAnalysis(PluginContext)} with the run-remaining flag for
	 * the coverage-enforce gate.
	 */
	public static SelectWithAnalysis selectWithAnalysis(PluginContext ctx, boolean runRemaining) throws IOException {
		// ── Fail-safe select-all trigger (cheap check FIRST) ──────
		// One repo-level git state validation (single spawn, RepoChangeListings)
		// is acquired up front and shared by BOTH git consumers of this module's
		// analysis: the non-source check below and the structural diff inside
		// ChangeAnalysis. When the state cannot be validated, the snapshot is null
		// and both consumers fall back to their direct git behavior.
		RepoChangeListings.Snapshot repoSnapshot = sharedRepoSnapshot(ctx);
		NonSourceChangeDetector.Detection nonSource = detectNonSourceChanges(ctx, repoSnapshot);
		if (nonSource != null && nonSource.changed()) {
			ctx.log().warn("[test-order] Non-source change detected — module behavior may change without a "
					+ "source-level change signal. Matched files: " + String.join(", ", nonSource.files()));
			// Reduced analysis: class-level universe only — enough for the closure
			// status and the orderer config, without the structural/member work the
			// select-all path would discard.
			ChangeAnalysis.Result a = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FOR_SELECTION);
			ModuleScope scope = moduleScope(ctx, a);
			return new SelectWithAnalysis(fullSelection(ctx, a, scope.effectiveDepMap(), scope.changedAndNew(),
					"selecting ALL " + fullTestCount(scope.effectiveDepMap(), scope.changedAndNew())
							+ " tests of this module (select-all; module is unselectable via source-based "
							+ "change detection)"),
					a);
		}

		// FOR_SELECTION_DETAILED: member-level change universe for cover-core
		// selection (structural analysis + method detection), still module-filtered
		// and read-only. See ChangeAnalysis.Options.FOR_SELECTION_DETAILED.
		ChangeAnalysis.Result a = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FOR_SELECTION_DETAILED,
				repoSnapshot);
		return selectWithAnalysis(ctx, a, runRemaining);
	}

	static SelectWithAnalysis selectWithAnalysis(PluginContext ctx, ChangeAnalysis.Result a) throws IOException {
		return selectWithAnalysis(ctx, a, false);
	}

	/**
	 * Selection workflow over a precomputed change analysis. Package-private seam
	 * for tests (lets a test fabricate a {@link ChangeAnalysis.Result}, e.g. with
	 * {@code staticAnalysisDegraded=true}, without orchestrating the degradation
	 * gate end-to-end).
	 */
	static SelectWithAnalysis selectWithAnalysis(PluginContext ctx, ChangeAnalysis.Result a, boolean runRemaining)
			throws IOException {
		ModuleScope scope = moduleScope(ctx, a);

		// ── Fail-safe select-all trigger ───────────────────────────
		// The degraded trigger bypasses TestSelector entirely: the changed-element
		// universe is unreliable (static analysis degraded), so the only safe subset
		// is ALL tests of the module. (The non-source trigger runs earlier — before
		// the analysis — see selectWithAnalysis(PluginContext, boolean).)
		if (a.staticAnalysisDegraded()) {
			ctx.log().warn("[test-order] Static analysis degraded — the changed-element universe is unreliable "
					+ "(class-level fallback).\nRun: " + ctx.learnCommand());
			return new SelectWithAnalysis(fullSelection(ctx, a, scope.effectiveDepMap(), scope.changedAndNew(),
					"selecting ALL " + fullTestCount(scope.effectiveDepMap(), scope.changedAndNew())
							+ " tests of this module (fail-safe select-all)"),
					a);
		}

		var alwaysRun = AlwaysRunScanner.scanOrEmpty(ctx.testClassesDir());

		me.bechberger.testorder.TestSelector.CacheConfig cacheConfig = AutoWorkflow.readCacheConfig(ctx.stateFile());
		// Coverage-enforce gate (parity with AutoWorkflow): when the caller also runs
		// the deferred tests (runRemaining=true, full suite) the closure check must
		// never fail — it only reports covered X/Y.
		boolean coverageEnforce = ctx.coverageEnforce() && !runRemaining;
		AffectedOperation.SelectResult result = AffectedOperation.select(new AffectedOperation.SelectConfig(
				scope.effectiveDepMap(), a.state(), a.changedClasses(), scope.changedAndNew(), a.weights(), ctx.topN(),
				ctx.randomM(), ctx.seed(), alwaysRun, ctx.selectedFile(), ctx.remainingFile(), ctx.log(),
				a.changeComplexity(), cacheConfig, a.changedMembers(), coverageEnforce));
		return new SelectWithAnalysis(result, a);
	}

	/** Module-filtered dep map plus the augmented changed/new test set. */
	private record ModuleScope(DependencyMap effectiveDepMap, Set<String> changedAndNew) {
	}

	/**
	 * Augments {@code changedTests} with unindexed tests from
	 * {@code testClassesDir} and applies the multi-module filter — shared by every
	 * selection path.
	 */
	private static ModuleScope moduleScope(PluginContext ctx, ChangeAnalysis.Result a) {
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

		// In multi-module reactors, restrict test selection to the current module.
		// When the dep map carries module information, filter out tests that belong
		// to a different module so they are not passed to Surefire in the wrong module,
		// which would cause "No tests matching pattern" errors.
		DependencyMap effectiveDepMap = a.depMap();
		String currentModule = ctx.currentModuleId();
		if (currentModule != null && !currentModule.isEmpty() && a.depMap().hasModuleMap()) {
			DependencyMap filtered = a.depMap().filterForModule(currentModule, ctx.log());
			if (!filtered.testClasses().isEmpty()) {
				// Normal case: module filter narrowed to this module's tests.
				effectiveDepMap = filtered;
				changedAndNew.removeIf(t -> {
					String m = a.depMap().getModule(t);
					return m != null && !m.equals(currentModule);
				});
			} else {
				// The module map exists but no tests match currentModule — this happens when
				// the dep map was built with an older module-ID format (e.g. "g-a" instead of
				// "g:a"). Fall back to the full dep map so selection still works correctly.
				// The changedAndNew removeIf is also skipped because getModule() would return
				// old-format IDs that never equal currentModule, silently emptying the set.
				ctx.log().debug("[test-order] Module filter for '" + currentModule
						+ "' produced an empty dep map — skipping module filter to avoid empty selection.");
			}
		}
		return new ModuleScope(effectiveDepMap, changedAndNew);
	}

	/** Number of tests a select-all run would execute (indexed + new). */
	private static int fullTestCount(DependencyMap effectiveDepMap, Set<String> changedAndNew) {
		return fullTestSet(effectiveDepMap, changedAndNew).size();
	}

	/** Indexed tests of the effective dep map plus unindexed new/changed tests. */
	private static Set<String> fullTestSet(DependencyMap effectiveDepMap, Set<String> changedAndNew) {
		Set<String> all = new LinkedHashSet<>(effectiveDepMap.testClasses());
		all.addAll(changedAndNew);
		return all;
	}

	/**
	 * Synthesizes a full-module selection (like the no-change topN=-1 path): every
	 * test of the module is selected, nothing is deferred or cached. The summary
	 * carries the coverage-closure counts over the select-all set so the "Changed
	 * elements covered" line stays visible in the end-of-run summary.
	 */
	private static AffectedOperation.SelectResult fullSelection(PluginContext ctx, ChangeAnalysis.Result a,
			DependencyMap effectiveDepMap, Set<String> changedAndNew, String logDetail) throws IOException {
		Set<String> all = fullTestSet(effectiveDepMap, changedAndNew);
		ctx.log().warn("[test-order] " + logDetail + ".");
		CoverageClosure closure = CoverageClosure.compute(effectiveDepMap, a.changedClasses(), changedAndNew,
				a.changedMembers());
		TestSelector.Selection selection = new TestSelector.Selection(new java.util.ArrayList<>(all),
				java.util.List.of(), 0, java.util.List.of(), closure.status(all));
		// Same uncovered-elements WARN as AffectedOperation: even a select-all set can
		// leave changed elements unexercised when the dependency index is stale.
		warnUncoveredElements(ctx, selection.coverageStatus());
		// Parity with AffectedOperation.select: persist the selected list and remove a
		// stale remaining file so run-remaining does not re-execute old deferrals.
		if (ctx.selectedFile() != null) {
			TestSelector.writeTestList(selection.selected(), ctx.selectedFile());
		}
		if (ctx.remainingFile() != null) {
			java.nio.file.Files.deleteIfExists(ctx.remainingFile());
		}
		return AffectedOperation.SelectResult.of(selection, true);
	}

	/**
	 * Same uncovered-elements WARN as {@code AffectedOperation.select} — shared by
	 * every select-all synthesis path in this package.
	 */
	static void warnUncoveredElements(PluginContext ctx, CoverageClosure.Status status) {
		if (status == null || status.totalElements() <= 0 || status.allCovered()) {
			return;
		}
		List<String> uncovered = status.uncoveredElements();
		String sample = uncovered.stream().limit(3).collect(java.util.stream.Collectors.joining(", "));
		String fullList = String.join(", ", uncovered);
		ctx.log()
				.warn("[test-order] Coverage closure: " + status.coveredCount() + "/" + status.totalElements()
						+ " changed elements are exercised by the selected test subset; " + uncovered.size()
						+ " uncovered" + " (e.g. " + sample + "). Uncovered: " + fullList
						+ ". Consider a learn phase (mvn test -Dtestorder.mode=learn) or running the full suite.");
	}

	/**
	 * Detects non-source changes (resources, build files) for the current module,
	 * reusing the change mode and paths the context already resolves.
	 *
	 * <p>
	 * Only git-based change modes carry a non-source signal: {@code uncommitted}
	 * inspects the working tree, {@code since-last-commit} diffs {@code HEAD~1}.
	 * Hash-based ({@code since-last-run}) and {@code explicit} modes keep the
	 * pre-existing behavior (no detection — treated as unchanged), mirroring the
	 * contract "git unavailable without hash file → existing behavior".
	 * NOT_DETERMINED (git unavailable/failed) is treated as unchanged by the
	 * caller.
	 */
	static NonSourceChangeDetector.Detection detectNonSourceChanges(PluginContext ctx) {
		return detectNonSourceChanges(ctx, null);
	}

	/**
	 * As {@link #detectNonSourceChanges(PluginContext)} with a pre-validated
	 * repo-level snapshot (see {@link RepoChangeListings}): avoids the per-call
	 * state validation when the caller already validated the same git root.
	 */
	static NonSourceChangeDetector.Detection detectNonSourceChanges(PluginContext ctx,
			RepoChangeListings.Snapshot shared) {
		boolean sinceLastCommit;
		try {
			ChangeDetector.Mode mode = ChangeDetectionSupport.resolveMode(ctx.changeMode(), ctx.hashFile());
			if (mode != ChangeDetector.Mode.SINCE_LAST_COMMIT && mode != ChangeDetector.Mode.UNCOMMITTED) {
				// Hash-based or explicit mode: no git baseline to inspect — keep the
				// pre-existing behavior (treated as unchanged).
				ctx.log().debug("[test-order] Non-source change detection skipped for change mode " + mode);
				return NonSourceChangeDetector.Detection.NOT_DETERMINED;
			}
			sinceLastCommit = mode == ChangeDetector.Mode.SINCE_LAST_COMMIT;
		} catch (IOException e) {
			ctx.log().debug("[test-order] Non-source change detection skipped: " + e.getMessage());
			return NonSourceChangeDetector.Detection.NOT_DETERMINED;
		}
		java.nio.file.Path gitRoot = resolveGitRoot(ctx);
		return NonSourceChangeDetector.detect(ctx.projectRoot(), gitRoot, sinceLastCommit, shared);
	}

	/**
	 * Acquires one validated repo-level snapshot for this module's git consumers —
	 * only when at least one of them will actually run (git-based structural
	 * analysis or non-source detection); hash-based and explicit change modes keep
	 * the pre-existing no-git-inspection behavior.
	 */
	static RepoChangeListings.Snapshot sharedRepoSnapshot(PluginContext ctx) {
		boolean structuralApplicable = ChangeAnalysis.resolveStructuralDiffMode(ctx.changeMode(), ctx.changedClasses(),
				ctx.hashFile()) != null;
		boolean nonSourceApplicable = false;
		try {
			ChangeDetector.Mode mode = ChangeDetectionSupport.resolveMode(ctx.changeMode(), ctx.hashFile());
			nonSourceApplicable = mode == ChangeDetector.Mode.SINCE_LAST_COMMIT
					|| mode == ChangeDetector.Mode.UNCOMMITTED;
		} catch (IOException e) {
			nonSourceApplicable = false;
		}
		if (!structuralApplicable && !nonSourceApplicable) {
			return null;
		}
		java.nio.file.Path gitRoot = resolveGitRoot(ctx);
		if (gitRoot == null) {
			return null;
		}
		return RepoChangeListings.acquire(gitRoot);
	}

	/**
	 * Resolves the git work-tree root for non-source detection: the context's
	 * {@code repoRoot} when set, otherwise the project root (the detector then
	 * fails safe to NOT_DETERMINED when git is unavailable).
	 */
	private static java.nio.file.Path resolveGitRoot(PluginContext ctx) {
		if (ctx.repoRoot() != null) {
			return ctx.repoRoot();
		}
		if (ctx.projectRoot() == null) {
			return null;
		}
		// Fall back to ChangeDetector-style resolution so submodule builds get
		// repo-root-relative path matching.
		Process process = null;
		Thread drainer = null;
		try {
			ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--show-toplevel");
			pb.directory(ctx.projectRoot().toFile());
			pb.redirectErrorStream(true);
			process = pb.start();
			final Process proc = process;
			java.util.concurrent.atomic.AtomicReference<String> outputRef = new java.util.concurrent.atomic.AtomicReference<>(
					"");
			drainer = new Thread(() -> {
				try (var is = proc.getInputStream()) {
					outputRef.set(new String(is.readAllBytes()).trim());
				} catch (IOException ignored) {
				}
			}, "test-order-non-source-git-root-drain");
			drainer.setDaemon(true);
			drainer.start();
			if (!process.waitFor(me.bechberger.testorder.changes.GitTimeout.seconds(),
					java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
				drainer.interrupt();
				return null;
			}
			drainer.join(2000);
			if (drainer.isAlive()) {
				// Drain thread still running: output may be truncated — fail safe.
				return null;
			}
			String output = outputRef.get();
			if (process.exitValue() == 0 && !output.isEmpty()) {
				return java.nio.file.Path.of(output).toAbsolutePath().normalize();
			}
			return null;
		} catch (IOException e) {
			return null;
		} catch (InterruptedException e) {
			// Mirror GitSupport.runGit: kill the process and unblock the drainer so a
			// cancelled build does not leak an orphaned git process holding a pipe.
			if (process != null) {
				process.destroyForcibly();
			}
			if (drainer != null) {
				drainer.interrupt();
			}
			Thread.currentThread().interrupt();
			return null;
		}
	}
}
