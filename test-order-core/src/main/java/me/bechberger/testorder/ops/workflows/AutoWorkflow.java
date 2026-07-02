package me.bechberger.testorder.ops.workflows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestSelector;
import me.bechberger.testorder.ops.AffectedOperation;
import me.bechberger.testorder.ops.AlwaysRunScanner;
import me.bechberger.testorder.ops.ChangeDetectionOps;
import me.bechberger.testorder.ops.HashSnapshotOperation;
import me.bechberger.testorder.ops.ModeResolverOperation;
import me.bechberger.testorder.ops.OrdererConfigOperation;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.PluginLog;
import me.bechberger.testorder.ops.ShowOrderOperation;

/**
 * Complete auto-mode workflow shared by Maven and Gradle.
 *
 * <p>
 * Create an instance with the fully-configured {@link PluginContext} and
 * callbacks, then call {@link #execute()} to run the full pipeline: resolve
 * mode → (learn | select+order+optimize+snapshot | skip). The returned
 * {@link Result} tells the plugin what happened so it can wire up the
 * framework-specific test runner.
 */
public final class AutoWorkflow {

	private final PluginContext ctx;
	private final String requestedMode;
	private final Runnable ciDownloadCallback;
	private final Path depsDir;

	/**
	 * @param ctx
	 *            fully-configured plugin context
	 * @param requestedMode
	 *            raw mode string (auto/learn/order/optimize/skip); {@code null} or
	 *            blank defaults to "auto"
	 * @param ciDownloadCallback
	 *            optional callback to download CI index
	 * @param depsDir
	 *            optional deps directory (may be {@code null})
	 * @throws IllegalArgumentException
	 *             if the mode is not one of the valid values
	 */
	public AutoWorkflow(PluginContext ctx, String requestedMode, Runnable ciDownloadCallback, Path depsDir) {
		this.ctx = ctx;
		// Validate early by delegating to ModeResolverOperation — single source of
		// truth
		// for accepted mode strings. Store the original (non-null, trimmed) string so
		// ModeResolverOperation.normalizeMode() can apply its own mapping downstream.
		ModeResolverOperation.normalizeMode(requestedMode); // throws on invalid input
		this.requestedMode = (requestedMode == null || requestedMode.isBlank())
				? "auto"
				: requestedMode.trim().toLowerCase(Locale.ROOT);
		this.ciDownloadCallback = ciDownloadCallback;
		this.depsDir = depsDir;
	}

	// ═══════════════════════════════════════════════════════════════
	// Result types
	// ═══════════════════════════════════════════════════════════════

	/** Outcome of {@link #execute()}. */
	public sealed interface Result permits Result.Skip, Result.Learn, Result.OrderSelect {

		/** Plugin should do nothing. */
		record Skip(String reason) implements Result {
		}

		/** Plugin should configure learn/instrumentation mode. */
		record Learn(String reason) implements Result {
		}

		/**
		 * Plugin should configure ordering + test selection.
		 *
		 * <p>
		 * When {@code attachLearnAgent} is true, the plugin should additionally
		 * configure the learn-mode agent on top of the ordered run so that fresh
		 * dependency data is recorded into the deps directory and merged incrementally
		 * on the next run. Triggered by {@link PluginContext#alwaysLearn()}.
		 */
		record OrderSelect(AffectedOperation.SelectResult selectResult, Map<String, String> ordererConfigMap,
				Set<String> changedClasses, Set<String> changedTests, Set<String> changedMethods,
				TestOrderState.ScoringWeights weights, boolean attachLearnAgent) implements Result {

			public TestSelector.Selection selection() {
				return selectResult.selection();
			}
		}
	}

	// ═══════════════════════════════════════════════════════════════
	// Main entry point
	// ═══════════════════════════════════════════════════════════════

	/**
	 * Executes the full auto-mode pipeline.
	 *
	 * <ol>
	 * <li>Resolve mode (auto / learn / order / skip)</li>
	 * <li>If order: run combined select + order in one {@link ChangeAnalysis} pass,
	 * then optimise weights if due, then snapshot hashes for next run.</li>
	 * </ol>
	 *
	 * @return a {@link Result} that tells the plugin what action was taken
	 * @throws IOException
	 *             if index/state loading or file I/O fails
	 */
	public Result execute() throws IOException {

		// ── 1a. alwaysLearn pre-aggregation ────────────────────────────
		// When alwaysLearn=true and an index already exists, fold any .deps files
		// recorded during prior runs into the existing index incrementally. This runs
		// before mode resolution so it works even in skip mode. When mode resolves to
		// "learn", the learn workflow will rebuild the full index anyway, so running
		// this first is harmless (not a double-aggregate — learn replaces, not merges).
		if (ctx.alwaysLearn() && depsDir != null && ctx.indexFile() != null && Files.exists(ctx.indexFile())
				&& Files.isDirectory(depsDir)) {
			try {
				me.bechberger.testorder.ops.AggregateOperation.aggregate(depsDir, ctx.indexFile(), ctx.log(), true);
			} catch (IOException e) {
				ctx.log().warn("[test-order] alwaysLearn incremental aggregation failed (ignored): " + e.getMessage());
			}
		}

		// ── 1b. Mode resolution ─────────────────────────────────────────
		ModeResolverOperation.ModeDecision decision = resolveMode(ctx, requestedMode, ciDownloadCallback, depsDir);

		if ("skip".equals(decision.effectiveMode())) {
			return new Result.Skip(decision.reason());
		}
		if ("learn".equals(decision.effectiveMode())) {
			return new Result.Learn(decision.reason());
		}
		if ("optimize".equals(decision.effectiveMode())) {
			// "optimize" mode was requested explicitly but the workflow always performs
			// ordering. Weight optimization is handled by optimizeIfDue() below on every
			// order run when the schedule is due. Log so the user knows it was seen.
			ctx.log().info("[test-order] Mode 'optimize' resolved to order+select with weight optimisation.");
		}

		// ── 2. Order + select (single analysis pass) ──────────────────
		ChangeAnalysis.Result a = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FOR_AUTO);

		var alwaysRun = AlwaysRunScanner.scanOrEmpty(ctx.testClassesDir());

		// Augment changedTests with unindexed tests from testClassesDir (same logic as
		// AffectedWorkflow). Handles modules whose tests haven't been learned yet.
		Set<String> changedAndNew = new LinkedHashSet<>(a.changedTests());
		Set<String> depMapTests = a.depMap().testClasses();
		for (String t : ShowOrderOperation.collectAllTests(a.depMap(), a.changedTests(), ctx.testClassesDir())) {
			if (!depMapTests.contains(t))
				changedAndNew.add(t);
		}

		// Module filter: restrict dep map (and changedAndNew) to the current module so
		// tests from sibling modules aren't passed to the wrong module's test runner.
		DependencyMap effectiveDepMap = a.depMap();
		String currentModule = ctx.currentModuleId();
		if (currentModule != null && !currentModule.isEmpty() && a.depMap().hasModuleMap()) {
			DependencyMap filtered = a.depMap().filterForModule(currentModule, ctx.log());
			if (!filtered.testClasses().isEmpty()) {
				effectiveDepMap = filtered;
				changedAndNew.removeIf(t -> {
					String m = a.depMap().getModule(t);
					return m != null && !m.equals(currentModule);
				});
			} else {
				ctx.log().debug("[test-order] Module filter for '" + currentModule
						+ "' produced an empty dep map — skipping module filter to avoid empty selection.");
			}
		}

		AffectedOperation.SelectResult selectResult;
		me.bechberger.testorder.TestSelector.CacheConfig cacheConfig = readCacheConfig(ctx.stateFile());
		if (a.changedClasses().isEmpty() && changedAndNew.isEmpty() && ctx.topN() < 0 && ctx.randomM() == 0
				&& !cacheConfig.enabled()) {
			var allTests = new ArrayList<>(effectiveDepMap.testClasses());
			selectResult = AffectedOperation.SelectResult
					.of(new TestSelector.Selection(allTests, java.util.List.of(), 0), true);
			ctx.log().info("[test-order] No changed classes detected — running tests in default order.");
		} else {
			if (a.changedClasses().isEmpty() && changedAndNew.isEmpty() && (ctx.topN() >= 0 || ctx.randomM() > 0)) {
				ctx.log().info("[test-order] No changed classes detected — applying topN/randomM selection only.");
			}
			selectResult = AffectedOperation.select(new AffectedOperation.SelectConfig(effectiveDepMap, a.state(),
					a.changedClasses(), changedAndNew, a.weights(), ctx.topN(), ctx.randomM(), ctx.seed(), alwaysRun,
					ctx.selectedFile(), ctx.remainingFile(), ctx.log(), a.changeComplexity(), cacheConfig));
			// G3: Warn if selection yields no tests in auto mode, but only when the depMap
			// has tests — modules with no test classes legitimately select nothing.
			if (selectResult.selection().selected().isEmpty() && !a.depMap().testClasses().isEmpty()
					&& (!a.changedClasses().isEmpty() || !changedAndNew.isEmpty())) {
				ctx.log().warn("[test-order] Selection yielded 0 tests despite detected changes. "
						+ "Check scoring weights or selectTopN/selectRandomM configuration.");
			}
		}

		Map<String, String> configMap = OrdererConfigOperation.buildConfig(new OrdererConfigOperation.OrdererInput(
				ctx.indexFile().toAbsolutePath().toString(), ctx.stateFile().toAbsolutePath().toString(),
				ctx.weightsFile() != null ? ctx.weightsFile().toAbsolutePath().toString() : null, a.changedClasses(),
				a.changedTests(), a.changedMethods(), ctx.scoreOverrides(), ctx.methodOrderingEnabled(),
				ctx.springContextGrouping(), ctx.projectRoot().toAbsolutePath().toString(),
				ctx.sourceRoot() != null ? ctx.sourceRoot().toAbsolutePath().toString() : null, ctx.changeMode()));

		if (!a.changedClasses().isEmpty()) {
			ctx.log().info("[test-order] Detected " + a.changedClasses().size() + " changed classes");
		}
		if (!changedAndNew.isEmpty()) {
			ctx.log().info("[test-order] Detected " + changedAndNew.size() + " changed/new test classes");
		}

		// ── 3. Periodic weight optimisation ─────────────────────────
		optimizeIfDue(a.state(), ctx.stateFile(), ctx.optimizeEvery(), ctx.log());

		// ── 4. Snapshot hashes for next run ─────────────────────────
		// NOTE: Ideally this runs after tests complete so a mid-run crash does not
		// advance the baseline. It is called here as a best-effort — the framework
		// plugin should also call snapshotHashes(ctx) in a post-test hook when
		// possible.
		snapshotHashes(ctx);

		// ── 5. Persist cache-runtime.txt so the dashboard can surface the
		// skip-if-unchanged set. Reading the duration from state at write time
		// avoids changing the autoLoadExtras signature.
		writeCacheRuntime(ctx, a.state(), selectResult.selection().cached());

		return new Result.OrderSelect(selectResult, configMap, a.changedClasses(), changedAndNew, a.changedMethods(),
				a.weights(), ctx.alwaysLearn());
	}

	private static void writeCacheRuntime(PluginContext ctx, TestOrderState state, java.util.List<String> cached) {
		Path stateFile = ctx.stateFile();
		if (stateFile == null) {
			return;
		}
		Path stateDir = stateFile.getParent();
		if (stateDir == null) {
			return;
		}
		Path target = stateDir.resolve(me.bechberger.testorder.ml.CacheRuntimeReport.DEFAULT_FILENAME);
		java.util.Map<String, Long> durations = new java.util.LinkedHashMap<>();
		for (String name : cached) {
			durations.put(name, state.getDuration(name, 0L));
		}
		try {
			me.bechberger.testorder.ml.CacheRuntimeReport.write(target, durations);
		} catch (java.io.IOException e) {
			ctx.log().warn("[test-order] Failed to write cache-runtime report: " + e.getMessage());
		}
	}

	// ═══════════════════════════════════════════════════════════════
	// Static helpers (used by Gradle resolveMode outside execute)
	// ═══════════════════════════════════════════════════════════════

	/**
	 * Resolves the effective mode (learn / order / skip) without running the full
	 * pipeline. Used by Gradle's configuration-phase mode resolution where
	 * learn/order wiring happens separately.
	 */
	public static ModeResolverOperation.ModeDecision resolveMode(PluginContext ctx, String requestedMode,
			Runnable ciDownloadCallback, Path depsDir) {

		Supplier<Set<String>> changedClassesSupplier = () -> ChangeDetectionOps.detectChangedClasses(ctx.changeMode(),
				ctx.projectRoot(), ctx.sourceRoot(), ctx.hashFile(), ctx.changedClasses(), true, ctx.log());

		// Only filter new tests by change-detection if this module has been learned
		// before
		// (test hash file exists). On first run in multi-module builds, the hash file
		// won't
		// exist yet, so all test classes not in the index are genuinely new.
		Supplier<Set<String>> changedTestsSupplier = ctx.testHashFile() != null && Files.exists(ctx.testHashFile())
				? () -> ChangeDetectionOps.detectChangedTestClasses(ctx.changeMode(), ctx.projectRoot(),
						ctx.testSourceRoot(), ctx.testHashFile(), ctx.changedTestClasses(), true, ctx.log())
				: null;

		ModeResolverOperation.ModeConfig modeConfig = new ModeResolverOperation.ModeConfig(requestedMode,
				ctx.indexFile(), ctx.stateFile(), ctx.autoLearnRunThreshold(), ctx.autoLearnDiffThreshold(),
				changedClassesSupplier, ciDownloadCallback, depsDir, ctx.testClassesDir(), ctx.testSourceRoot(),
				changedTestsSupplier, ctx.dependencyFingerprintSupplier(), ctx.log(), ctx.buildSystem());

		ModeResolverOperation.ModeDecision decision = ModeResolverOperation.resolve(modeConfig);
		ctx.log().debug("[test-order] Mode decision: " + decision.effectiveMode() + " (" + decision.reason() + ")");
		return decision;
	}

	static boolean optimizeIfDue(TestOrderState state, Path stateFile, int optimizeEvery, PluginLog log)
			throws IOException {
		if (optimizeEvery <= 0 || state.runsSinceLearn() <= 0 || state.runsSinceLearn() % optimizeEvery != 0) {
			return false;
		}
		log.info("[test-order] Triggering periodic weight optimisation" + " (every " + optimizeEvery + " runs)…");
		TestOrderState.OptimizeResult optimized = state.optimize();
		if (optimized != null) {
			state.setWeights(optimized.weights());
			state.resetRunsSinceLearn();
			if (stateFile != null) {
				state.save(stateFile);
			}
			log.info("[test-order] Optimised weights saved: " + optimized.weights().format());
			return true;
		}
		return false;
	}

	static void snapshotHashes(PluginContext ctx) {
		HashSnapshotOperation.snapshot(ctx.sourceRoot(), ctx.hashFile(), ctx.testSourceRoot(), ctx.testHashFile(),
				(label, path) -> ctx.log().info("[test-order] Saved " + label + " hash snapshot: " + path),
				(label, msg) -> ctx.log().warn("[test-order] Failed to save " + label + " hash snapshot: " + msg));
		if (ctx.methodOrderingEnabled() && ctx.methodHashFile() != null) {
			ChangeDetectionOps.snapshotMethodHashes(ctx.testSourceRoot(), ctx.methodHashFile(), ctx.log());
		}
	}

	static me.bechberger.testorder.TestSelector.CacheConfig readCacheConfig(java.nio.file.Path stateFile) {
		boolean enabled = Boolean.parseBoolean(
				System.getProperty(me.bechberger.testorder.TestOrderConfig.CACHE_SKIP_UNCHANGED, "false"));
		if (!enabled)
			return me.bechberger.testorder.TestSelector.CacheConfig.DISABLED;
		int minStreak = parseIntOr(System.getProperty(me.bechberger.testorder.TestOrderConfig.CACHE_MIN_PASS_STREAK),
				3);
		double maxFrac = parseDoubleOr(
				System.getProperty(me.bechberger.testorder.TestOrderConfig.CACHE_MAX_SKIP_FRACTION), 0.9);
		java.util.Set<String> quarantined = java.util.Set.of();
		if (stateFile != null) {
			java.nio.file.Path stateDir = stateFile.toAbsolutePath().getParent();
			if (stateDir != null) {
				java.nio.file.Path reportFile = stateDir
						.resolve(me.bechberger.testorder.ml.FlakyRuntimeReport.DEFAULT_FILENAME);
				me.bechberger.testorder.ml.FlakyRuntimeReport report = me.bechberger.testorder.ml.FlakyRuntimeReport
						.load(reportFile);
				if (!report.isEmpty()) {
					quarantined = report.quarantined();
				}
			}
		}
		return new me.bechberger.testorder.TestSelector.CacheConfig(true, minStreak, maxFrac, quarantined);
	}

	private static int parseIntOr(String s, int fallback) {
		try {
			return s == null ? fallback : Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static double parseDoubleOr(String s, double fallback) {
		try {
			return s == null ? fallback : Double.parseDouble(s.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
