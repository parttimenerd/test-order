package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.ErrorCode;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.changes.ChangeDetectionSupport;
import me.bechberger.testorder.ops.IndexCompactionOperation;
import me.bechberger.testorder.ops.workflows.OrderWorkflow;

/**
 * Prepares the test execution environment by configuring Surefire for either
 * learn mode (agent attachment) or order mode (ClassOrderer injection).
 */
@Mojo(name = "prepare", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class PrepareMojo extends AbstractTestOrderMojo {

	/**
	 * Operation mode:
	 * <ul>
	 * <li><b>learn</b> – always instrument and build the dependency index.</li>
	 * <li><b>order</b> – always run in priority order; warns if new test classes
	 * are found that are not yet in the index.</li>
	 * <li><b>auto</b> (default) – runs learn mode automatically when the dependency
	 * index is missing or when compiled test classes are detected that are not yet
	 * indexed; otherwise runs in order mode.</li>
	 * </ul>
	 */
	@Parameter(property = MavenPluginConfigKeys.MODE, defaultValue = "auto")
	private String mode;

	/**
	 * Comma-separated additional package prefixes to instrument (merged with
	 * auto-detected source packages)
	 */
	@Parameter(property = MavenPluginConfigKeys.INCLUDE_PACKAGES)
	private String includePackages;

	/**
	 * When true (default) and no source packages are detected, fall back to the
	 * project groupId as an instrumentation filter. Source packages from
	 * src/main/java are always auto-detected.
	 */
	@Parameter(property = MavenPluginConfigKeys.FILTER_BY_GROUP_ID, defaultValue = "true")
	private boolean filterByGroupId;

	/**
	 * Instrumentation mode: FULL (default), METHOD_ENTRY, FULL_METHOD, or
	 * FULL_MEMBER
	 */
	@Parameter(property = MavenPluginConfigKeys.INSTRUMENTATION_MODE, defaultValue = "FULL")
	private String instrumentationMode;

	/**
	 * In auto mode, forces a full re-learn after this many consecutive order-mode
	 * runs (0 = disabled, default: 10). Ensures the dependency index stays fresh
	 * as the codebase evolves.
	 */
	@Parameter(property = MavenPluginConfigKeys.AUTO_LEARN_RUN_THRESHOLD, defaultValue = "10")
	private int autoLearnRunThreshold;

	/**
	 * Auto mode: switch to learn when changed-class count reaches this threshold (0
	 * = disabled).
	 */
	@Parameter(property = MavenPluginConfigKeys.AUTO_LEARN_DIFF_THRESHOLD, defaultValue = "0")
	private int autoLearnDiffThreshold;

	/**
	 * Auto-compact the index every N order-mode runs by rebuilding from .deps files
	 * (0 = disabled). Removes stale entries for deleted test classes.
	 */
	@Parameter(property = MavenPluginConfigKeys.AUTO_COMPACT_EVERY, defaultValue = "50")
	private int autoCompactEvery;

	private static final Set<String> VALID_MODES = Set.of("auto", "learn", "order", "skip");
	private static final Set<String> VALID_INSTR_MODES = Set.of("METHOD_ENTRY", "FULL", "FULL_METHOD", "FULL_MEMBER");

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		if (hasCliWorkflowGoal()) {
			getLog().debug("[test-order] Skipping prepare — CLI test-order workflow already configured Surefire.");
			return;
		}

		// Allow CLI system property to override POM-level <configuration><mode>
		// (Maven parameter injection gives POM config precedence over system properties,
		// but users expect -Dtestorder.mode=order to always win)
		if (session != null && session.getUserProperties() != null) {
			String cliMode = session.getUserProperties().getProperty(MavenPluginConfigKeys.MODE);
			if (cliMode != null && !cliMode.isBlank()) {
				mode = cliMode.trim();
			}
		}

		// Skip if a CLI goal (auto, select, run-remaining) already configured Surefire
		if ("true".equals(project.getProperties().getProperty("testorder.auto.active"))) {
			getLog().debug("[test-order] Skipping prepare — CLI goal already configured Surefire.");
			return;
		}

		if (!VALID_MODES.contains(mode)) {
			throw new TestOrderMojoException(ErrorCode.CHANGE_MODE_INVALID,
					"Invalid mode '" + mode + "'. Valid values: " + VALID_MODES
					+ ". Use -Dtestorder.mode=skip to disable test-order.");
		}
		if ("skip".equals(mode)) {
			getLog().info("[test-order] Mode is 'skip' — no Surefire configuration changes.");
			return;
		}
		if (!VALID_INSTR_MODES.contains(instrumentationMode.toUpperCase())) {
			throw new TestOrderMojoException(ErrorCode.INSTRUMENTATION_MODE_INVALID,
					"Invalid instrumentationMode '" + instrumentationMode + "'. Valid values: " + VALID_INSTR_MODES);
		}
		try {
			changeMode = ChangeDetectionSupport.normalizeMode(changeMode);
		} catch (IOException e) {
			throw new MojoExecutionException("Invalid changeMode '" + changeMode + "'. Valid values: "
					+ ChangeDetectionSupport.supportedModes());
		}
		Path idxPath = resolveIndexPath();

		if ("learn".equals(mode)) {
			// explicit learn mode — always instrument
			switchToLearnMode();
			return;
		}

		// For both "order" and "auto": ensure we have an aggregated index if only .deps
		// files exist
		if (!Files.exists(idxPath)) {
			Path depsDirPath = ctx.resolveDepsDir(depsDir);
			if (Files.isDirectory(depsDirPath) && hasDepsFiles(depsDirPath)) {
				getLog().info("[test-order] No index found but .deps files exist — auto-aggregating.");
				autoAggregate(depsDirPath, idxPath);
			}
		}

		if (!Files.exists(idxPath)) {
			if ("auto".equals(mode)) {
				// Check if there are actually test classes to learn from — avoid
				// endless re-learn cycles in projects with no tests.
				Path testClassesDir = Path.of(project.getBuild().getTestOutputDirectory());
				if (!Files.exists(testClassesDir) || !Files.isDirectory(testClassesDir)) {
					getLog().warn("[test-order] No compiled test classes in " + testClassesDir
							+ " — test-order has nothing to order. Ensure 'test-compile' ran before this goal.");
					return;
				}
				if (me.bechberger.testorder.ops.TestClassDiscovery.scanTestClasses(testClassesDir).isEmpty()) {
					getLog().warn("[test-order] No test classes found in " + testClassesDir
							+ " — test-order has nothing to order.");
					return;
				}
				getLog().info("[test-order] No dependency index found — switching to learn mode automatically.");
				switchToLearnMode();
			} else {
				getLog().info("[test-order] No index found and mode is 'order' — skipping.");
			}
			return;
		}

		// Index exists — check for new test classes and auto-learn thresholds
		if (shouldSwitchToLearn(idxPath)) {
			switchToLearnMode();
			return;
		}

		executeOrderMode();
	}

	/**
	 * Checks whether auto mode should switch to learn based on new test classes,
	 * run-count threshold, or changed-class threshold. In explicit 'order' mode,
	 * only logs warnings about unindexed tests.
	 */
	private boolean shouldSwitchToLearn(Path idxPath) {
		Set<String> changedTestsNow = detectChangedTestClasses();
		Set<String> newTests = new java.util.LinkedHashSet<>(findNewTestClasses(idxPath));
		// Only treat changed test sources as "new" to avoid repeatedly flagging
		// old/non-runnable compiled test classes — BUT if no test hash snapshot
		// exists yet, this module has never been learned, so all its test classes
		// not in the index are genuinely new (multi-module first-run scenario).
		Path testHash = ctx.resolveTestHashFile(testHashFile);
		if (Files.exists(testHash)) {
			newTests.retainAll(changedTestsNow);
		}
		if (!newTests.isEmpty()) {
			String names = newTests.stream().sorted().limit(5).reduce((a, b) -> a + ", " + b).orElse("");
			if (newTests.size() > 5)
				names += " (... " + (newTests.size() - 5) + " more)";
			if ("auto".equals(mode)) {
				getLog().info("[test-order] New test class(es) detected: " + names
						+ " — switching to learn mode automatically.");
				return true;
			}
			getLog().warn("[test-order] New test class(es) not yet in the dependency index: " + names);
			getLog().warn("[test-order] Run 'mvn test -D" + MavenPluginConfigKeys.MODE
					+ "=learn' to index them for accurate ordering.");
		}

		if (!"auto".equals(mode)) {
			return false;
		}

		// Check auto-learn run threshold
		if (autoLearnRunThreshold > 0) {
			TestOrderState state = loadState();
			int runsSince = state.runsSinceLearn();
			if (runsSince >= autoLearnRunThreshold) {
				getLog().info("[test-order] Run count since last learn (" + runsSince + ") reached threshold ("
						+ autoLearnRunThreshold + ") — switching to learn mode automatically to refresh index.");
				return true;
			}
		}

		// Check changed-class diff threshold
		if (autoLearnDiffThreshold > 0) {
			Set<String> changedNow = detectChangedClasses();
			if (changedNow.size() >= autoLearnDiffThreshold) {
				getLog().info("[test-order] Changed-class count (" + changedNow.size() + ") reached threshold ("
						+ autoLearnDiffThreshold + ") — switching to learn mode automatically to refresh index.");
				return true;
			}
		}

		return false;
	}

	private void switchToLearnMode() throws MojoExecutionException {
		SurefireHelper.rejectClassLevelParallelForLearn(project, getLog());
		String effectiveInclude = resolveIncludePackages(includePackages, filterByGroupId, project, getLog());
		configureLearnMode(instrumentationMode, effectiveInclude, true);
		TestOrderState state = loadState();
		state.resetRunsSinceLearn();
		try {
			state.save(ctx.resolveStateFile(stateFile));
		} catch (IOException e) {
			getLog().warn("[test-order] Could not reset runsSinceLearn: " + e.getMessage());
		}
	}

	private void executeOrderMode() throws MojoExecutionException {
		// Detect which test framework is on the classpath to print correct class name
		String frameworkName = isTestNGOnTestClasspath() ? "TestNGPriorityInterceptor" : "PriorityClassOrderer";
		getLog().info("[test-order] Order mode: injecting " + frameworkName);

		// Warn if topN was explicitly set — it only applies to select/auto-select, not pure order mode
		String topNProp = System.getProperty(MavenPluginConfigKeys.SELECT_TOP_N);
		if (topNProp != null) {
			getLog().warn("[test-order] -Dtestorder.select.topN is ignored in 'order' mode (all tests run, just re-ordered). "
					+ "Did you mean: mvn test-order:select test -Dtestorder.select.topN=" + topNProp + "?");
		}

		SurefireHelper.validateNoClassLevelParallel(project, getLog());
		SurefireHelper.warnListenerDeactivation(project, getLog());
		SurefireHelper.warnConflictingOrderers(project, getLog());
		Plugin surefire = SurefireHelper.findSurefirePlugin(project);
		if (surefire != null) {
			SurefireHelper.warnOldSurefireVersion(surefire, getLog());
		}

		// Auto-compact: rebuild index from .deps files periodically to remove stale entries
		if (autoCompactEvery > 0) {
			TestOrderState compactState = loadState();
			int runsSince = compactState.runsSinceLearn();
			if (runsSince > 0 && runsSince % autoCompactEvery == 0) {
				Path depsDirPath = ctx.resolveDepsDir(depsDir);
				if (Files.isDirectory(depsDirPath) && hasDepsFiles(depsDirPath)) {
					getLog().info("[test-order] Auto-compacting index (every " + autoCompactEvery + " runs)");
					try {
						IndexCompactionOperation.compact(depsDirPath, resolveIndexPath(),
								MavenPluginLog.wrap(getLog()));
						// Increment runsSinceLearn by 1 so subsequent runs don't re-trigger
						// until autoCompactEvery more runs pass (R7-11)
						compactState.incrementRunsSinceLearn();
						compactState.save(ctx.resolveStateFile(stateFile));
					} catch (IOException e) {
						getLog().warn("[test-order] Auto-compact failed: " + e.getMessage());
					}
				}
			}
		}

		// Detect changes including upstream modules in reactor builds.
		// This ensures cross-module changes (e.g. core module change affecting
		// web module tests) are visible to the OrderWorkflow.
		Set<String> mergedChanged = detectChangedClasses();
		Set<String> mergedChangedTests = detectChangedTestClasses();
		String mergedChangedCsv = mergedChanged.isEmpty() ? null : String.join(",", mergedChanged);
		String mergedChangedTestsCsv = mergedChangedTests.isEmpty() ? null : String.join(",", mergedChangedTests);

		TestOrderState state = loadState();
		OrderWorkflow.OrderSetupResult result;
		try {
			result = OrderWorkflow.setup(
					buildPluginContextBuilder().changedClasses(mergedChangedCsv)
							.changedTestClasses(mergedChangedTestsCsv).build(),
					state);
		} catch (IOException e) {
			if ("auto".equals(mode) && isRecoverableIndexLoadFailure(e)) {
				getLog().warn("[test-order] " + e.getMessage());
				getLog().warn("[test-order] Dependency index is missing/corrupt — attempting recovery.");

				// Try rebuilding from .deps first
				Path depsDirPath = ctx.resolveDepsDir(depsDir);
				if (Files.isDirectory(depsDirPath) && hasDepsFiles(depsDirPath)) {
					try {
						DependencyMap map = DependencyMap.aggregate(depsDirPath);
						if (map.size() > 0) {
							Path idxPath = resolveIndexPath();
							Files.createDirectories(idxPath.getParent());
							map.save(idxPath);
							getLog().info("[test-order] Rebuilt index from .deps (" + map.size() + " classes) → " + idxPath);
							// Retry ordering with the rebuilt index
							result = OrderWorkflow.setup(
									buildPluginContextBuilder().changedClasses(mergedChangedCsv)
											.changedTestClasses(mergedChangedTestsCsv).build(),
									state);
							writeOrdererConfig(result.changedClasses(), result.changedTests(), result.changedMethods(),
									buildScoreOverrides());
							return;
						}
					} catch (IOException rebuildEx) {
						getLog().debug("[test-order] .deps rebuild failed: " + rebuildEx.getMessage());
					}
				}

				// Try .bak recovery
				if (recoverIndexFromBackup()) {
					try {
						result = OrderWorkflow.setup(
								buildPluginContextBuilder().changedClasses(mergedChangedCsv)
										.changedTestClasses(mergedChangedTestsCsv).build(),
								state);
						writeOrdererConfig(result.changedClasses(), result.changedTests(), result.changedMethods(),
								buildScoreOverrides());
						return;
					} catch (IOException retryEx) {
						getLog().debug("[test-order] Recovered index still unreadable: " + retryEx.getMessage());
					}
				}

				// All recovery failed — delete and switch to learn
				getLog().warn("[test-order] Recovery failed — switching to learn mode.");
				try {
					Files.deleteIfExists(resolveIndexPath());
				} catch (IOException deleteEx) {
					getLog().debug("[test-order] Could not delete corrupt index: " + deleteEx.getMessage());
				}
				switchToLearnMode();
				return;
			}
			throw new TestOrderMojoException(ErrorCode.INDEX_READ_ERROR,
					"Failed to set up test ordering: " + e.getMessage(), e);
		}

		writeOrdererConfig(result.changedClasses(), result.changedTests(), result.changedMethods(),
				buildScoreOverrides());
	}

	private boolean isRecoverableIndexLoadFailure(IOException e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			String msg = t.getMessage();
			if (msg == null)
				continue;
			if (msg.contains("Failed to load dependency index") || msg.contains("Index file not found")
					|| msg.contains("Unsupported index format")) {
				return true;
			}
		}
		return false;
	}

	private boolean hasCliWorkflowGoal() {
		if (session == null || session.getGoals() == null) {
			return false;
		}
		return session.getGoals().stream().anyMatch(goal -> "test-order:select".equals(goal)
				|| "test-order:auto".equals(goal) || "test-order:learn".equals(goal)
				|| "test-order:run-remaining".equals(goal) || "test-order:run-tier".equals(goal)
				|| "test-order:tiered-select".equals(goal));
	}

	/**
	 * Attempts to recover the dependency index from a .bak backup file.
	 * Checks standard backup locations relative to the project root.
	 */
	private boolean recoverIndexFromBackup() {
		Path idxPath = resolveIndexPath();
		Path projectRoot = project.getBasedir().toPath().toAbsolutePath();
		List<Path> candidates = List.of(
				idxPath.resolveSibling(idxPath.getFileName() + ".bak"),
				projectRoot.resolve(".test-order/test-dependencies.lz4.bak"),
				projectRoot.resolve("test-dependencies.lz4.bak"));

		for (Path candidate : candidates) {
			if (!Files.exists(candidate)) {
				continue;
			}
			try {
				Files.createDirectories(idxPath.getParent());
				Files.copy(candidate, idxPath, StandardCopyOption.REPLACE_EXISTING);
				DependencyMap.load(idxPath); // validate it's readable
				getLog().info("[test-order] Recovered dependency index from backup: " + candidate);
				return true;
			} catch (IOException loadErr) {
				getLog().debug("[test-order] Backup candidate unusable (" + candidate + "): " + loadErr.getMessage());
			}
		}
		return false;
	}
}
