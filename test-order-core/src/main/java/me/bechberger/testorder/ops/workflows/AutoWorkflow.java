package me.bechberger.testorder.ops.workflows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestSelector;
import me.bechberger.testorder.ops.AlwaysRunScanner;
import me.bechberger.testorder.ops.ChangeDetectionOps;
import me.bechberger.testorder.ops.HashSnapshotOperation;
import me.bechberger.testorder.ops.ModeResolverOperation;
import me.bechberger.testorder.ops.OrdererConfigOperation;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.PluginLog;
import me.bechberger.testorder.ops.SelectOperation;

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

	private static final Set<String> VALID_MODES = Set.of("auto", "learn", "order", "skip");

	private final PluginContext ctx;
	private final String requestedMode;
	private final Runnable ciDownloadCallback;
	private final Path depsDir;

	/**
	 * @param ctx
	 *            fully-configured plugin context
	 * @param requestedMode
	 *            raw mode string (auto/learn/order/skip); {@code null} or blank
	 *            defaults to "auto"
	 * @param ciDownloadCallback
	 *            optional callback to download CI index
	 * @param depsDir
	 *            optional deps directory (may be {@code null})
	 * @throws IllegalArgumentException
	 *             if the mode is not one of the valid values
	 */
	public AutoWorkflow(PluginContext ctx, String requestedMode, Runnable ciDownloadCallback, Path depsDir) {
		this.ctx = ctx;
		this.requestedMode = normalizeMode(requestedMode);
		this.ciDownloadCallback = ciDownloadCallback;
		this.depsDir = depsDir;
	}

	private static String normalizeMode(String mode) {
		String m = (mode == null || mode.isBlank()) ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
		if (!VALID_MODES.contains(m)) {
			throw new IllegalArgumentException(
					"[test-order] Invalid mode '" + mode + "'. Valid values: auto, learn, order, skip");
		}
		return m;
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

		/** Plugin should configure ordering + test selection. */
		record OrderSelect(SelectOperation.SelectResult selectResult, Map<String, String> ordererConfigMap,
				Set<String> changedClasses, Set<String> changedTests, Set<String> changedMethods,
				TestOrderState.ScoringWeights weights) implements Result {

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

		// ── 1. Mode resolution ──────────────────────────────────────
		ModeResolverOperation.ModeDecision decision = resolveMode(ctx, requestedMode, ciDownloadCallback, depsDir);

		if ("skip".equals(decision.effectiveMode())) {
			return new Result.Skip(decision.reason());
		}
		if ("learn".equals(decision.effectiveMode())) {
			return new Result.Learn(decision.reason());
		}

		// ── 2. Order + select (single analysis pass) ────────────────
		ChangeAnalysis.Result a = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FOR_AUTO);

		var alwaysRun = ctx.testClassesDir() != null ? AlwaysRunScanner.scan(ctx.testClassesDir()) : Set.<String>of();

		SelectOperation.SelectResult selectResult;
		if (a.changedClasses().isEmpty() && a.changedTests().isEmpty() && ctx.topN() < 0 && ctx.randomM() < 0) {
			var allTests = new ArrayList<>(a.depMap().testClasses());
			selectResult = new SelectOperation.SelectResult(new TestSelector.Selection(allTests, java.util.List.of()),
					true);
			ctx.log().info("[test-order] No changed classes detected — running tests in default order.");
		} else {
			if (a.changedClasses().isEmpty() && a.changedTests().isEmpty()) {
				ctx.log().info("[test-order] No changed classes detected — applying topN/randomM selection only.");
			}
			selectResult = SelectOperation.select(new SelectOperation.SelectConfig(a.depMap(), a.state(),
					a.changedClasses(), a.changedTests(), a.weights(), ctx.topN(), ctx.randomM(), ctx.seed(), alwaysRun,
					ctx.selectedFile(), ctx.remainingFile(), ctx.log(), a.changeComplexity()));
			// G3: Warn if selection yields no tests in auto mode
			if (selectResult.selection().selected().isEmpty()) {
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
		if (!a.changedTests().isEmpty()) {
			ctx.log().info("[test-order] Detected " + a.changedTests().size() + " changed test classes");
		}

		// ── 3. Periodic weight optimisation ─────────────────────────
		optimizeIfDue(a.state(), ctx.stateFile(), ctx.optimizeEvery(), ctx.log());

		// ── 4. Snapshot hashes for next run ─────────────────────────
		snapshotHashes(ctx);

		return new Result.OrderSelect(selectResult, configMap, a.changedClasses(), a.changedTests(), a.changedMethods(),
				a.weights());
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

		String mode = normalizeMode(requestedMode);

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

		ModeResolverOperation.ModeConfig modeConfig = new ModeResolverOperation.ModeConfig(mode, ctx.indexFile(),
				ctx.stateFile(), ctx.autoLearnRunThreshold(), ctx.autoLearnDiffThreshold(), changedClassesSupplier,
				ciDownloadCallback, depsDir, ctx.testClassesDir(), ctx.testSourceRoot(), changedTestsSupplier,
				ctx.dependencyFingerprintSupplier(), ctx.log());

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
			state.save(stateFile);
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
}
