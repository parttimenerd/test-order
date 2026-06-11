package me.bechberger.testorder.ops.workflows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestScorer;
import me.bechberger.testorder.changes.BytecodeDependencyAugmenter;
import me.bechberger.testorder.changes.BytecodeHashStore;
import me.bechberger.testorder.changes.StaticCallGraphAnalyzer;
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
			Map<String, Double> changeComplexity, Set<String> allTests, boolean staticAnalysisDegraded,
			ChangedMembers preSaChangedMembers) {

		/**
		 * Convenience constructor without the static-analysis degradation flag or
		 * pre-SA members. Defaults to {@code false} (not degraded) and {@code null} (no
		 * pre-SA snapshot).
		 */
		public Result(DependencyMap depMap, TestOrderState state, Set<String> changedClasses, Set<String> changedTests,
				Set<String> changedMethods, TestOrderState.ScoringWeights weights,
				TestOrderState.LoadedWeights loadedWeights, ChangedMembers changedMembers,
				Map<String, Double> changeComplexity, Set<String> allTests) {
			this(depMap, state, changedClasses, changedTests, changedMethods, weights, loadedWeights, changedMembers,
					changeComplexity, allTests, false, null);
		}

		/**
		 * Convenience constructor without pre-SA members snapshot.
		 */
		public Result(DependencyMap depMap, TestOrderState state, Set<String> changedClasses, Set<String> changedTests,
				Set<String> changedMethods, TestOrderState.ScoringWeights weights,
				TestOrderState.LoadedWeights loadedWeights, ChangedMembers changedMembers,
				Map<String, Double> changeComplexity, Set<String> allTests, boolean staticAnalysisDegraded) {
			this(depMap, state, changedClasses, changedTests, changedMethods, weights, loadedWeights, changedMembers,
					changeComplexity, allTests, staticAnalysisDegraded, null);
		}

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

		/**
		 * All analysis steps enabled. Used by show-order and dashboard from reactor
		 * root.
		 */
		public static final Options FULL = new Options(true, true, true, false);

		/**
		 * All analysis steps enabled, filtered to the current module. Used by
		 * show-order and dashboard when invoked from a submodule so that only that
		 * module's tests are shown instead of the entire reactor's test set.
		 */
		public static final Options FULL_FILTERED = new Options(true, true, true, true);

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
		if (opts.filterToModule() && ctx.currentModuleId() != null && !ctx.currentModuleId().isEmpty()
				&& depMap.hasModuleMap()) {
			int beforeCount = depMap.testClasses().size();
			DependencyMap byModuleId = TestClassDiscovery.filterToModuleId(depMap, ctx.currentModuleId());
			if (byModuleId.testClasses().size() > 0) {
				depMap = byModuleId;
				ctx.log().debug("[test-order] filtered index by moduleId=" + ctx.currentModuleId() + ": " + beforeCount
						+ " -> " + depMap.testClasses().size() + " test classes");
			} else {
				// moduleId not found in module map (e.g. submodule not yet learned) —
				// fall through to testClassesDir / testSourceRoot filter below.
				ctx.log().debug("[test-order] moduleId=" + ctx.currentModuleId()
						+ " matched 0 tests in module map — falling through to dir-based filter");
			}
		}
		if (opts.filterToModule()) {
			int beforeCount = depMap.testClasses().size();
			boolean filteredByClasses = false;
			// Try compiled-classes filter first (most precise match).
			if (ctx.testClassesDir() != null && Files.isDirectory(ctx.testClassesDir())) {
				DependencyMap filtered = TestClassDiscovery.filterToModule(depMap, ctx.testClassesDir());
				if (filtered.testClasses().size() > 0 && filtered.testClasses().size() < beforeCount) {
					depMap = filtered;
					filteredByClasses = true;
					ctx.log().debug("[test-order] filtered index by testClassesDir: " + beforeCount + " -> "
							+ depMap.testClasses().size() + " test classes");
				} else if (filtered.testClasses().size() == 0) {
					// Compiled classes exist but none matched the dep map — stale index hint.
					long compiledCount = TestClassDiscovery.scanTestClasses(ctx.testClassesDir()).size();
					if (compiledCount > 0) {
						ctx.log()
								.warn("[test-order] This module has " + compiledCount
										+ " compiled test class(es) not yet in the dependency index."
										+ " Run learn mode to include them: " + ctx.learnCommand()
										+ " (showing all indexed tests as fallback)");
					}
				}
			}
			// Fall back to source-root filter when compiled classes unavailable or no
			// match.
			// This handles: (a) uncompiled modules (Scala/Groovy primary, Java secondary),
			// (b) mixed-language projects where the compiled dir was empty.
			if (!filteredByClasses && ctx.testSourceRoot() != null && Files.isDirectory(ctx.testSourceRoot())) {
				DependencyMap sourcFiltered = TestClassDiscovery.filterToModuleBySourceRoot(depMap,
						ctx.testSourceRoot());
				if (sourcFiltered.testClasses().size() > 0 && sourcFiltered.testClasses().size() < beforeCount) {
					depMap = sourcFiltered;
					ctx.log().debug("[test-order] filtered index by testSourceRoot: " + beforeCount + " -> "
							+ depMap.testClasses().size() + " test classes");
				}
			}
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
		// Parallelize production and test source detection (independent git operations)
		var prodChangeFuture = java.util.concurrent.CompletableFuture
				.supplyAsync(() -> ChangeDetectionOps.detectChangedClassesWithKotlin(ctx.changeMode(),
						ctx.projectRoot(), ctx.sourceRoot(), ctx.hashFile(), ctx.changedClasses(), true, ctx.log()));
		var testChangeFuture = java.util.concurrent.CompletableFuture.supplyAsync(
				() -> ChangeDetectionOps.detectChangedTestClassesWithKotlin(ctx.changeMode(), ctx.projectRoot(),
						ctx.testSourceRoot(), ctx.testHashFile(), ctx.changedTestClasses(), true, ctx.log()));

		Set<String> changed = prodChangeFuture.join();
		Set<String> changedTests = testChangeFuture.join();

		// ── Cross-module change propagation ─────────────────────────
		// In multi-module builds, test modules (e.g. jupiter-tests) or modules with
		// tests that cover sibling production modules must also see changes from those
		// sibling modules. additionalSourceRoots contains sibling production source
		// roots added by the Gradle/Maven plugin. We scan them here and merge any
		// detected changes into `changed` so that tests depending on changed sibling
		// classes receive the correct depOverlap score boost.
		//
		// since-last-run mode is supported for per-module entries (moduleHashEntries),
		// where each entry has its own hash file. For additional roots without a hash
		// file, since-last-run is still skipped (no snapshot available).
		// For git-based modes (uncommitted, since-last-commit) git covers all roots.

		// First: per-module entries (since-last-run supported via per-module hash
		// files)
		if (!ctx.moduleHashEntries().isEmpty()) {
			List<java.util.concurrent.CompletableFuture<Set<String>>> moduleFutures = new ArrayList<>();
			for (var entry : ctx.moduleHashEntries()) {
				Path mRoot = entry.sourceRoot();
				Path mHash = entry.hashFile();
				if (!Files.isDirectory(mRoot))
					continue;
				moduleFutures.add(java.util.concurrent.CompletableFuture
						.supplyAsync(() -> ChangeDetectionOps.detectChangedClassesWithKotlin(ctx.changeMode(),
								ctx.projectRoot(), mRoot, mHash, null, true, ctx.log())));
			}
			if (!moduleFutures.isEmpty()) {
				Set<String> mergedChanged = new java.util.LinkedHashSet<>(changed);
				for (var f : moduleFutures) {
					mergedChanged.addAll(f.join());
				}
				if (mergedChanged.size() > changed.size()) {
					ctx.log()
							.debug("[test-order] cross-module change propagation (per-module hash) added "
									+ (mergedChanged.size() - changed.size()) + " class(es) from "
									+ moduleFutures.size() + " module(s).");
					changed = mergedChanged;
				}
			}
		}

		// Second: additional roots without per-module hash files (git-based modes only)
		if (!ctx.additionalSourceRoots().isEmpty() && !"since-last-run".equalsIgnoreCase(ctx.changeMode())
				&& !"explicit".equalsIgnoreCase(ctx.changeMode())) {
			List<java.util.concurrent.CompletableFuture<Set<String>>> siblingFutures = new ArrayList<>();
			for (Path siblingRoot : ctx.additionalSourceRoots()) {
				if (!Files.isDirectory(siblingRoot))
					continue;
				siblingFutures.add(java.util.concurrent.CompletableFuture
						.supplyAsync(() -> ChangeDetectionOps.detectChangedClassesWithKotlin(ctx.changeMode(),
								ctx.projectRoot(), siblingRoot, null, null, true, ctx.log())));
			}
			if (!siblingFutures.isEmpty()) {
				Set<String> mergedChanged = new java.util.LinkedHashSet<>(changed);
				for (var f : siblingFutures) {
					mergedChanged.addAll(f.join());
				}
				if (mergedChanged.size() > changed.size()) {
					ctx.log()
							.debug("[test-order] cross-module change propagation added "
									+ (mergedChanged.size() - changed.size()) + " class(es) from "
									+ siblingFutures.size() + " sibling source root(s).");
					changed = mergedChanged;
				}
			}
		}

		// ── Bytecode change detection (Step 1) ──────────────────────
		// Cross-checks compiled .class files for source-invisible changes
		// (annotation processors, generated code, dependency-version bumps).
		Set<String> bytecodeChangedMethodKeys = Set.of();
		if (ctx.bytecodeChangeDetectionEnabled() && ctx.classesDir() != null && Files.isDirectory(ctx.classesDir())
				&& ctx.bytecodeHashFile() != null) {
			try {
				BytecodeHashStore curr = BytecodeHashStore.scan(ctx.classesDir());
				BytecodeHashStore prev;
				if (Files.exists(ctx.bytecodeHashFile())) {
					try {
						prev = BytecodeHashStore.load(ctx.bytecodeHashFile());
					} catch (IOException e) {
						ctx.log().debug("[test-order] could not load bytecode hash file (" + e.getMessage()
								+ ") — treating as first run.");
						prev = new BytecodeHashStore();
					}
				} else {
					prev = new BytecodeHashStore();
				}
				if (!prev.isEmpty()) {
					Set<String> bytecodeChangedClasses = curr.getChangedClasses(prev);
					if (!bytecodeChangedClasses.isEmpty()) {
						int before = changed.size();
						Set<String> merged = new java.util.LinkedHashSet<>(changed);
						merged.addAll(bytecodeChangedClasses);
						changed = merged;
						ctx.log().debug("[test-order] bytecode change detection added " + (changed.size() - before)
								+ " classes (total bytecode-changed: " + bytecodeChangedClasses.size() + ").");
					}
					bytecodeChangedMethodKeys = curr.getChangedMethodKeys(prev);
				}
				try {
					curr.save(ctx.bytecodeHashFile());
				} catch (IOException e) {
					ctx.log().debug("[test-order] failed to save bytecode hash file: " + e.getMessage());
				}
			} catch (IOException e) {
				ctx.log().debug("[test-order] bytecode scan failed: " + e.getMessage());
			}
		}

		if (opts.filterToModule() && ctx.testClassesDir() != null && Files.isDirectory(ctx.testClassesDir())) {
			Set<String> moduleTests = TestClassDiscovery.scanTestClasses(ctx.testClassesDir());
			if (!moduleTests.isEmpty()) {
				changedTests = changedTests.stream().filter(moduleTests::contains)
						.collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
			}
		}

		Set<String> changedMethods = Set.of();
		if (opts.includeMethodChanges() && ctx.methodOrderingEnabled() && ctx.methodHashFile() != null
				&& ctx.testSourceRoot() != null) {
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

		// ── Member-precision merge (Step 2) ─────────────────────────
		// Fold bytecode-derived per-method changes into the structural ChangedMembers
		// so the static call-graph expander has more starting points.
		if (!bytecodeChangedMethodKeys.isEmpty()) {
			Set<String> mergedClasses = new java.util.LinkedHashSet<>();
			Set<String> mergedKeys = new java.util.LinkedHashSet<>();
			Map<String, Set<String>> mergedByClass = new java.util.LinkedHashMap<>();
			Set<String> typeChanges = Set.of();
			Set<String> staticFieldKeys = Set.of();
			if (changedMembers != null) {
				mergedClasses.addAll(changedMembers.changedClasses());
				mergedKeys.addAll(changedMembers.changedMemberKeys());
				for (var e : changedMembers.membersByClass().entrySet()) {
					mergedByClass.put(e.getKey(), new java.util.LinkedHashSet<>(e.getValue()));
				}
				typeChanges = changedMembers.classesWithTypeChanges();
				staticFieldKeys = changedMembers.changedStaticFieldKeys();
			}
			for (String memberKey : bytecodeChangedMethodKeys) {
				int hash = memberKey.indexOf('#');
				if (hash <= 0) {
					continue;
				}
				String cls = memberKey.substring(0, hash);
				String member = memberKey.substring(hash + 1);
				mergedClasses.add(cls);
				mergedKeys.add(memberKey);
				mergedByClass.computeIfAbsent(cls, k -> new java.util.LinkedHashSet<>()).add(member);
			}
			Map<String, Set<String>> unmodifiableByClass = new java.util.LinkedHashMap<>();
			for (var e : mergedByClass.entrySet()) {
				unmodifiableByClass.put(e.getKey(), java.util.Collections.unmodifiableSet(e.getValue()));
			}
			changedMembers = new ChangedMembers(java.util.Collections.unmodifiableSet(mergedClasses),
					java.util.Collections.unmodifiableSet(mergedKeys),
					java.util.Collections.unmodifiableMap(unmodifiableByClass), typeChanges, staticFieldKeys);
		}

		// ── Static call-graph expansion (optional) ──────────────────────
		ChangedMembers preSaChangedMembers = changedMembers;
		boolean staticAnalysisDegraded = false;
		if (ctx.staticAnalysisEnabled() && !changed.isEmpty() && ctx.classesDir() != null) {
			var classDirs = new java.util.ArrayList<java.nio.file.Path>(2);
			classDirs.add(ctx.classesDir());
			if (ctx.testClassesDir() != null) {
				classDirs.add(ctx.testClassesDir());
			}
			if (changedMembers == null) {
				// Build a minimal ChangedMembers from the class-level changes so that the
				// call-graph expander has member keys to start from. We use the class name
				// itself as a synthetic member key ("<class>") so the BFS can find callers.
				Set<String> syntheticKeys = new java.util.LinkedHashSet<>();
				Map<String, Set<String>> syntheticByClass = new java.util.LinkedHashMap<>();
				for (String cls : changed) {
					String key = cls + "#<class>";
					syntheticKeys.add(key);
					syntheticByClass.put(cls, java.util.Collections.singleton("<class>"));
				}
				changedMembers = new ChangedMembers(new java.util.LinkedHashSet<>(changed),
						java.util.Collections.unmodifiableSet(syntheticKeys),
						java.util.Collections.unmodifiableMap(syntheticByClass), java.util.Collections.emptySet());
			}
			StaticCallGraphAnalyzer.Report report = StaticCallGraphAnalyzer.expandWithReport(changedMembers, classDirs,
					ctx.staticAnalysisDepth(), StaticCallGraphAnalyzer.DEFAULT_DEGRADATION_RATIO);

			// ── Two-phase degradation gate ──────────────────────────────────
			// Static expansion ratio alone isn't enough — we ALSO require the dynamic
			// dependency map to indicate that a large fraction of tests are affected.
			// This avoids spurious "re-learn" warnings on small projects where a handful
			// of changed methods naturally pulls in many callers (high ratio, low
			// absolute impact).
			boolean dynamicHighImpact = false;
			double affectedFraction = 0.0;
			if (report.degraded()) {
				int totalTests = depMap.testClasses().size();
				if (totalTests > 0) {
					Set<String> affectedTests = depMap.getAffectedTests(changed);
					affectedFraction = (double) affectedTests.size() / totalTests;
					dynamicHighImpact = affectedFraction > 0.30;
				}
			}

			if (report.degraded() && dynamicHighImpact) {
				changedMembers = report.expanded(); // class-level fallback
				staticAnalysisDegraded = true;
				ctx.log()
						.warn("[test-order] static analysis: " + report.reason() + " AND "
								+ String.format("%.0f%%", affectedFraction * 100)
								+ " of tests already touch the changed classes. Falling back to class-level matching. "
								+ "Consider re-running learn mode (`" + ctx.learnCommand()
								+ "`) to refresh the dependency index.");
			} else {
				if (report.degraded()) {
					ctx.log()
							.debug("[test-order] static analysis ratio threshold tripped (" + report.reason()
									+ ") but dynamic impact is only " + String.format("%.1f%%", affectedFraction * 100)
									+ " of tests — keeping detailed expansion (no fallback).");
				}
				// Re-run expansion without the degradation cap so we get the precise expansion.
				changedMembers = StaticCallGraphAnalyzer
						.expandWithReport(changedMembers, classDirs, ctx.staticAnalysisDepth(), 0).expanded();
			}
		}

		Map<String, Double> changeComplexityMap = !changed.isEmpty()
				? ShowOrderOperation.computeChangeComplexity(changed, ctx.allSourceRoots(), changedMembers,
						structuralDiffs)
				: Map.of();

		// ── Dependency-map augmentation (Step 3) ────────────────────
		// Augment-only — adds missing test → prod edges that the agent didn't observe
		// at learn time but that are visible in the test bytecode. Never removes.
		if (ctx.bytecodeAugmentDependencyMapEnabled() && ctx.testClassesDir() != null
				&& Files.isDirectory(ctx.testClassesDir()) && !depMap.testClasses().isEmpty()) {
			try {
				StaticCallGraphAnalyzer.ScanResult testScan = StaticCallGraphAnalyzer
						.scanPublic(java.util.List.of(ctx.testClassesDir()));
				Map<String, Set<String>> aug = BytecodeDependencyAugmenter.computeAugmentation(testScan, depMap);
				// Filter augmented deps by includePackages so framework classes (JUnit etc.)
				// aren't re-added
				String incPkgs = ctx.includePackages();
				if (incPkgs != null && !incPkgs.isBlank()) {
					String[] prefixes = java.util.Arrays.stream(incPkgs.split("[,;]+"))
							.map(p -> p.endsWith(".") ? p.substring(0, p.length() - 1) : p).toArray(String[]::new);
					aug = aug.entrySet().stream().collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey,
							e -> e.getValue().stream().filter(dep -> java.util.Arrays.stream(prefixes)
									.anyMatch(pfx -> dep.startsWith(pfx) && (dep.length() == pfx.length()
											|| dep.charAt(pfx.length()) == '.' || dep.charAt(pfx.length()) == '$')))
									.collect(java.util.stream.Collectors.toSet())));
				}
				if (!aug.isEmpty()) {
					int total = 0;
					for (Set<String> v : aug.values()) {
						total += v.size();
					}
					ctx.log().debug("[test-order] dependency-map augmentation added " + total + " edge(s) across "
							+ aug.size() + " test class(es).");
					depMap = depMap.withAugmentation(aug);
				}
			} catch (RuntimeException e) {
				ctx.log().debug("[test-order] dependency-map augmentation failed: " + e.getMessage());
			}
		}

		// ── Collect all tests (optional) ────────────────────────────
		Set<String> allTests = opts.includeAllTests()
				? ShowOrderOperation.collectAllTests(depMap, changedTests, ctx.testClassesDir())
				: Set.of();

		return new Result(depMap, state, changed, changedTests, changedMethods, sw, lw, changedMembers,
				changeComplexityMap, allTests, staticAnalysisDegraded, preSaChangedMembers);
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
				overrides.get("coverageBonus"), overrides.get("killRateBonus"));
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
