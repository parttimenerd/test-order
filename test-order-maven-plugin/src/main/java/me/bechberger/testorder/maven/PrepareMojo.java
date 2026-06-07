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
import me.bechberger.testorder.ops.AggregateOperation;
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
	// NOTE: includePackages and filterByGroupId are declared in
	// AbstractTestOrderMojo

	/**
	 * Instrumentation mode: MEMBER (default), CLASS, or METHOD.
	 */
	@Parameter(property = MavenPluginConfigKeys.INSTRUMENTATION_MODE, defaultValue = "MEMBER")
	private String instrumentationMode;

	/**
	 * Instrumentation strategy: "offline" (default, build-time — faster, no
	 * per-fork agent overhead) or "online" (agent-based, instruments at class load
	 * time in each fork).
	 */
	@Parameter(property = MavenPluginConfigKeys.INSTRUMENTATION, defaultValue = "offline")
	private String instrumentation;

	/**
	 * LZ4 compression level for index writes: "fast" (default — 10-50x faster
	 * writes, ~5-15% larger files) or "hc" (high compression, smallest files). The
	 * setting is passed as a system property so that the IndexCollectorServer in
	 * the build process uses it when writing the final index.
	 */
	@Parameter(property = MavenPluginConfigKeys.COMPRESSION, defaultValue = "fast")
	private String compression;

	/**
	 * In auto mode, forces a full re-learn after this many consecutive order-mode
	 * runs (0 = disabled, default: 10). Ensures the dependency index stays fresh as
	 * the codebase evolves.
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

	/**
	 * Enable TDD enforcement mode. When {@code true}, new test methods that pass on
	 * the first run are failed artificially with a TDD-violation message, enforcing
	 * the red-green-refactor cycle. Corresponds to setting the system property
	 * {@code testorder.tdd=true} in the Surefire JVM.
	 */
	@Parameter(property = "testorder.tdd", defaultValue = "false")
	private boolean tdd;

	private static final Set<String> VALID_MODES = Set.of("auto", "learn", "order", "skip");
	private static final Set<String> VALID_INSTR_MODES = Set.of("CLASS", "METHOD", "MEMBER");

	// REACTOR_MAP_INTRA_JVM_LOCK is inherited from AbstractTestOrderMojo — do NOT
	// redeclare it here. A local `private static final Object` in this subclass
	// would be a separate monitor from the one used by AbstractTestOrderMojo
	// (which also serializes runOfflineInstrumentation), defeating the intra-JVM
	// mutual exclusion needed for `mvn -T`.

	@Override
	protected String resolveEffectiveIncludePackages() {
		return resolveIncludePackages(includePackages, filterByGroupId, project, getLog());
	}

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		// Always restore previously instrumented classes (even when skip=true)
		// to prevent NoClassDefFoundError from stale instrumented bytecode.
		// Exception: when the 'learn' CLI goal is active, the learn mojo has already
		// instrumented the classes at process-test-classes phase. Restoring them here
		// would undo that instrumentation before tests run, causing class-load
		// failures.
		boolean learnCliActive = isLearnCliGoal();
		if (!learnCliActive) {
			restoreInstrumentedClasses();
		} else if (isLearnActiveOnlyViaSentinel()) {
			// Sentinel is present but no in-memory learn markers exist — we're inside a
			// nested Maven JVM (e.g. exec-maven-plugin running 'mvn package'). The outer
			// learn session already instrumented and configured everything; doing any work
			// here would start a duplicate collector or modify Surefire config incorrectly.
			getLog().debug("[test-order] Skipping prepare — nested Maven call during outer offline learn run.");
			return;
		}
		if (skip)
			return;
		// POM-packaging modules (reactor parents) have no tests — skip silently
		if ("pom".equals(project.getPackaging())) {
			getLog().debug("[test-order] Skipping prepare — POM module.");
			return;
		}
		if (hasCliWorkflowGoal()) {
			// Still perform deferred offline instrumentation if needed
			if ("true".equals(project.getProperties().getProperty("testorder.offline.pending"))) {
				performDeferredOfflineInstrumentation();
			}
			getLog().debug("[test-order] Skipping prepare — CLI test-order workflow already configured Surefire.");
			return;
		}

		// Allow CLI system property to override POM-level <configuration><mode>
		// (Maven parameter injection gives POM config precedence over system
		// properties,
		// but users expect -Dtestorder.mode=order to always win)
		if (session != null && session.getUserProperties() != null) {
			String cliMode = session.getUserProperties().getProperty(MavenPluginConfigKeys.MODE);
			if (cliMode != null && !cliMode.isBlank()) {
				mode = cliMode.trim();
			}
		}

		// Skip if a CLI goal (auto, select, run-remaining) already configured Surefire
		if ("true".equals(project.getProperties().getProperty("testorder.auto.active"))) {
			// But still perform deferred offline instrumentation if needed
			if ("true".equals(project.getProperties().getProperty("testorder.offline.pending"))) {
				performDeferredOfflineInstrumentation();
			}
			getLog().debug("[test-order] Skipping prepare — CLI goal already configured Surefire.");
			return;
		}

		mode = mode.toLowerCase(java.util.Locale.ROOT);
		if (!VALID_MODES.contains(mode)) {
			throw new TestOrderMojoException(ErrorCode.PLUGIN_MODE_INVALID, "Invalid mode '" + mode
					+ "'. Valid values: " + VALID_MODES + ". Use -Dtestorder.mode=skip to disable test-order.");
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
			// explicit learn mode — also order if an index exists (must run BEFORE
			// switchToLearnMode so that bytecode-hashes.lz4 is saved from original
			// (un-instrumented) bytecode; otherwise the next prepare compares restored
			// classes against instrumented hashes and falsely reports all 196+ classes as
			// changed).
			if (Files.exists(idxPath)) {
				executeOrderMode();
			}
			switchToLearnMode();
			return;
		}

		// Always check for fallback payload file — written by IndexCollectorServer
		// when the Maven JVM shuts down before stopAndMerge can complete.
		// Process unconditionally: even if index exists, the fallback carries data
		// from the most recent learn run that failed to merge.
		try {
			if (me.bechberger.testorder.IndexCollectorServer.processFallbackFile(idxPath)) {
				getLog().info(
						"[test-order] Processed collector fallback payloads from previous learn run → " + idxPath);
			}
		} catch (IOException e) {
			getLog().warn("[test-order] Failed to process collector fallback payloads: " + e.getMessage());
		}

		// For both "order" and "auto": ensure we have an aggregated index if only .deps
		// files exist.
		if (!Files.exists(idxPath)) {
			Path depsDirPath = ctx.resolveDepsDir(depsDir);
			if (Files.isDirectory(depsDirPath) && hasDepsFiles(depsDirPath)) {
				try {
					getLog().info("[test-order] No index found but .deps files exist — auto-aggregating.");
					autoAggregate(depsDirPath, idxPath);
				} catch (MojoExecutionException e) {
					getLog().warn("[test-order] Auto-aggregation failed: " + e.getMessage()
							+ " — will attempt other recovery options.");
				}
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
				getLog().warn(
						"[test-order] No dependency index found but mode is 'order' — tests will run in default order. "
								+ "Run in learn mode first to build the dependency index: mvn test -Dtestorder.mode=learn");
			}
			return;
		}

		// Index exists — check for new test classes and auto-learn thresholds
		if (shouldSwitchToLearn(idxPath)) {
			// Order using the existing index BEFORE instrumenting (same fix as explicit
			// learn mode) — if executeOrderMode() ran after switchToLearnMode(), it would
			// save instrumented bytecode hashes and the next prepare would falsely report
			// all source classes as changed.
			executeOrderMode();
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
				// R9-10: If this module has never been learned (no test hash),
				// suppress the alarming message — it's just a first-run scenario.
				if (!Files.exists(testHash)) {
					getLog().debug("[test-order] First learn for this module — " + newTests.size()
							+ " test class(es) not yet in index.");
				} else {
					getLog().info("[test-order] New test class(es) detected: " + names
							+ " — switching to learn mode automatically.");
				}
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

	/**
	 * Restores class files that were previously instrumented by offline learn mode.
	 * This prevents NoClassDefFoundError when subsequent runs (order, skip,
	 * baseline) encounter instrumented bytecode without UsageStore on the
	 * classpath.
	 */
	private void restoreInstrumentedClasses() {
		String buildDir = project.getBuild().getDirectory();
		if (buildDir == null) {
			return;
		}
		Path targetDir = Path.of(buildDir);
		Path backupDir = targetDir.resolve(".test-order").resolve("classes-backup");
		Path testBackupDir = targetDir.resolve(".test-order").resolve("classes-backup-test");
		try {
			boolean restored = me.bechberger.testorder.agent.OfflineInstrumentor.restore(backupDir);
			if (me.bechberger.testorder.agent.OfflineInstrumentor.restore(testBackupDir)) {
				restored = true;
			}
			if (restored) {
				getLog().info("[test-order] Restored original classes from backup (offline instrumentation reverted).");
			}
		} catch (IOException e) {
			getLog().warn("[test-order] Failed to restore instrumented classes: " + e.getMessage());
		}
	}

	/**
	 * Performs offline instrumentation that was deferred by a CLI goal (which runs
	 * before compile). At process-test-classes phase, classes are guaranteed to
	 * exist.
	 */
	private void performDeferredOfflineInstrumentation() throws MojoExecutionException {
		Path targetDir = Path.of(project.getBuild().getDirectory());
		// Reactor-wide class-id map: lives at
		// <reactorRoot>/.test-order/class-id-map.bin.
		// Single-module builds: reactor root == project root, so this is just
		// <project>/.test-order/class-id-map.bin (no behaviour change).
		Path mappingFile = ctx != null
				? ctx.resolveClassIdMapFile()
				: targetDir.resolve(".test-order").resolve("class-id-map.bin");
		Path lockFile = mappingFile.resolveSibling(mappingFile.getFileName() + ".lock");

		// The "skip if mapping exists" optimization no longer applies: the reactor
		// pre-pass writes a stub mapping in afterProjectsRead, but we still need to
		// instrument THIS module's classes. The instrumented bytecode itself is
		// guarded by the .instrumented marker in the backup dir.

		Path classesDir = resolveClassesDir();
		if (classesDir == null) {
			getLog().warn("[test-order] Classes still not found during deferred instrumentation"
					+ " — tests may fail to record dependencies.");
			project.getProperties().remove("testorder.offline.pending");
			return;
		}

		String instrMode = project.getProperties().getProperty("testorder.offline.instrMode", "CLASS");
		String includes = project.getProperties().getProperty("testorder.offline.includePackages", "");

		getLog().info("[test-order] Performing deferred offline instrumentation: " + classesDir);
		try {
			me.bechberger.testorder.agent.Agent.InstrumentationMode iMode = me.bechberger.testorder.agent.Agent.InstrumentationMode
					.fromString(instrMode);
			List<String> includeList = includes.isBlank() ? List.of() : List.of(includes.split(","));

			// Selective learn: compute uncertain classes if enabled and index exists
			java.util.Set<String> uncertainClasses = null;
			me.bechberger.testorder.changes.SelectiveLearnSupport.StaticAnalysisData saData = null;
			if (selectiveLearn) {
				Path idxPath = ctx != null ? ctx.resolveIndexFile(indexFile) : java.nio.file.Path.of(indexFile);
				boolean indexExists = java.nio.file.Files.exists(idxPath);
				if (indexExists) {
					me.bechberger.testorder.changes.ChangeDetector.Mode changeDetectorMode;
					try {
						Path hf = ctx != null ? ctx.resolveHashFile(hashFile) : null;
						changeDetectorMode = me.bechberger.testorder.changes.ChangeDetectionSupport
								.resolveMode(changeMode, hf);
					} catch (java.io.IOException e) {
						changeDetectorMode = me.bechberger.testorder.changes.ChangeDetector.Mode.UNCOMMITTED;
					}
					Path projectRoot = ctx != null ? ctx.gitRoot() : project.getBasedir().toPath();
					saData = me.bechberger.testorder.changes.SelectiveLearnSupport
							.computeStaticAnalysisData(projectRoot, classesDir, changeDetectorMode);
					uncertainClasses = saData != null ? saData.uncertainClasses() : null;
					if (uncertainClasses != null && !uncertainClasses.isEmpty()) {
						getLog().info("[test-order] Selective instrument: " + uncertainClasses.size()
								+ " uncertain class(es) will be instrumented");
					} else if (uncertainClasses != null) {
						getLog().info(
								"[test-order] Selective instrument: no source changes detected; skipping instrumentation");
					}
				} else {
					getLog().info(
							"[test-order] Selective instrument: no existing index — using full instrumentation for initial run");
				}
			}

			// Write uncertain-classes.txt for dashboard Static Analysis tab
			if (uncertainClasses != null) {
				String mid = computeCurrentModuleId();
				String fname = (mid == null || mid.isBlank())
						? "uncertain-classes.txt"
						: "uncertain-classes-" + mid.replaceAll("[^a-zA-Z0-9._-]", "_") + ".txt";
				try {
					Path depsDirPath = ctx != null ? ctx.resolveDepsDir(depsDir) : java.nio.file.Path.of(depsDir);
					Path uncertainFile = depsDirPath.resolve(fname);
					me.bechberger.testorder.changes.UncertainClassesStore.save(uncertainFile, uncertainClasses);
					if (saData != null) {
						me.bechberger.testorder.changes.StaticAnalysisDataStore.save(
								me.bechberger.testorder.changes.StaticAnalysisDataStore.sidecarPath(uncertainFile),
								saData);
					}
				} catch (java.io.IOException e2) {
					getLog().debug("[test-order] Could not write uncertain-classes file: " + e2.getMessage());
				}
			}

			me.bechberger.testorder.agent.OfflineInstrumentor instrumentor = new me.bechberger.testorder.agent.OfflineInstrumentor(
					iMode, includeList, List.of(), uncertainClasses);
			Path backupDir = targetDir.resolve(".test-order").resolve("classes-backup");

			// Reactor-wide ID space: serialize across modules. The intra-JVM monitor
			// covers Maven -T (parallel modules in one JVM, where FileLock would
			// throw OverlappingFileLockException). The FileLock inside covers
			// concurrent Maven CLIs (separate JVMs).
			Files.createDirectories(mappingFile.getParent());
			me.bechberger.testorder.agent.runtime.ClassIdMapping mapping;
			synchronized (REACTOR_MAP_INTRA_JVM_LOCK) {
				try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(lockFile.toFile(), "rw");
						java.nio.channels.FileLock fileLock = raf.getChannel().lock()) {
					if (Files.exists(mappingFile)) {
						try {
							me.bechberger.testorder.agent.runtime.ClassIdMapping reactor = me.bechberger.testorder.agent.runtime.ClassIdMapping
									.load(mappingFile);
							me.bechberger.testorder.agent.runtime.ClassIdMap.getInstance()
									.bulkLoadClasses(reactor.toClassMap());
							if (reactor.memberCount() > 0) {
								me.bechberger.testorder.agent.runtime.ClassIdMap.getInstance()
										.bulkLoadMembers(reactor.toMemberMap());
							}
						} catch (IOException e) {
							getLog().warn("[test-order] Could not load reactor class-id map (will rebuild): "
									+ e.getMessage());
						}
					}
					mapping = instrumentor.instrument(classesDir, backupDir);
					if (instrumentor.getTransformedCount() == 0 && instrumentor.getSkippedCount() > 0) {
						getLog().info("[test-order] Detected stale instrumentation (no mapping). Re-instrumenting...");
						instrumentor = new me.bechberger.testorder.agent.OfflineInstrumentor(iMode, includeList,
								List.of());
						instrumentor.setIgnoreMarker(true);
						mapping = instrumentor.instrument(classesDir, backupDir);
					}
					// Save the singleton's full state back so any IDs newly registered for
					// this module (inner classes, generated types) are visible to other
					// modules' prepares and to the test forks.
					me.bechberger.testorder.agent.runtime.ClassIdMap singleton = me.bechberger.testorder.agent.runtime.ClassIdMap
							.getInstance();
					me.bechberger.testorder.agent.runtime.ClassIdMapping fullMapping = me.bechberger.testorder.agent.runtime.ClassIdMapping
							.fromClassIdMap(singleton, singleton.getNextClassId(), singleton.getNextMemberId());
					fullMapping.save(mappingFile);
				}
			}
			getLog().info("[test-order] Instrumented " + instrumentor.getTransformedCount() + " classes" + " (skipped "
					+ instrumentor.getSkippedCount() + ")");
			AbstractTestOrderMojo.pendingRestores.add(backupDir);
			registerPendingRestoreInSession(backupDir);
			Path testBackupDir = backupDir.resolveSibling("classes-backup-test");
			AbstractTestOrderMojo.pendingRestores.add(testBackupDir);
			registerPendingRestoreInSession(testBackupDir);
		} catch (IOException e) {
			project.getProperties().remove("testorder.offline.pending");
			throw new MojoExecutionException("[test-order] Deferred offline instrumentation failed", e);
		}
		project.getProperties().remove("testorder.offline.pending");
	}

	private void switchToLearnMode() throws MojoExecutionException {
		SurefireHelper.rejectClassLevelParallelForLearn(project, getLog());
		SurefireHelper.warnForkCountInLearnMode(project, getLog());
		SurefireHelper.warnReuseForksFalseInLearnMode(project, getLog());
		SurefireHelper.warnRerunFailingTestsInLearnMode(project, getLog());
		SurefireHelper.forceClasspathModeIfNeeded(project, getLog());
		String effectiveInclude = resolveIncludePackages(includePackages, filterByGroupId, project, getLog());

		// Set compression level as a system property for IndexCollectorServer merge
		if (compression != null && !compression.isBlank()) {
			System.setProperty(MavenPluginConfigKeys.COMPRESSION, compression.strip());
		}

		if ("offline".equalsIgnoreCase(instrumentation)) {
			configureOfflineLearnMode(instrumentationMode, effectiveInclude);
		} else {
			configureLearnMode(instrumentationMode, effectiveInclude, true);
		}

		TestOrderState state = loadState();
		state.resetRunsSinceLearn();
		try {
			state.save(ctx.resolveStateFile(stateFile));
		} catch (IOException e) {
			getLog().warn("[test-order] Could not reset runsSinceLearn: " + e.getMessage());
		}
		// Prevent duplicate execution when CLI goal also triggers the POM-bound phase
		project.getProperties().setProperty("testorder.auto.active", "true");
	}

	private void executeOrderMode() throws MojoExecutionException {
		// R16-4: When user filters to specific tests via -Dtest, skip full ordering
		// as Surefire will only run the filtered set anyway.
		String testFilter = session != null && session.getUserProperties() != null
				? session.getUserProperties().getProperty("test")
				: null;
		if (testFilter != null && !testFilter.isBlank()) {
			getLog().info("[test-order] Skipping ordering overhead — -Dtest=" + testFilter + " filter active.");
			// Still inject classpath and write TDD config so TddEnforcementExtension is
			// loaded even when only a subset of tests runs.
			writeOrdererConfig(java.util.Collections.emptySet(), java.util.Collections.emptySet(),
					java.util.Collections.emptySet(), java.util.Collections.emptyMap());
			if (tdd) {
				appendRuntimeConfigProperty(me.bechberger.testorder.TestOrderConfig.TDD, "true");
				appendJunitPlatformProperty("junit.jupiter.extensions.autodetection.enabled", "true");
				getLog().info(
						"[test-order] TDD enforcement enabled — new/changed tests that pass on first run will fail.");
			}
			project.getProperties().setProperty("testorder.auto.active", "true");
			return;
		}

		// Detect which test framework is on the classpath to print correct class name
		String frameworkName = isTestNGOnTestClasspath() ? "TestNGPriorityInterceptor" : "PriorityClassOrderer";
		getLog().info("[test-order] Order mode: injecting " + frameworkName);

		// Warn if topN was explicitly set — it only applies to select/auto-select, not
		// pure order mode
		String topNProp = session != null && session.getUserProperties() != null
				? session.getUserProperties().getProperty(MavenPluginConfigKeys.SELECT_TOP_N)
				: null;
		if (topNProp != null) {
			getLog().warn(
					"[test-order] -Dtestorder.select.topN is ignored in 'order' mode (all tests run, just re-ordered). "
							+ "Did you mean: mvn test-order:affected test -Dtestorder.select.topN=" + topNProp + "?");
		}

		SurefireHelper.validateNoClassLevelParallel(project, getLog());
		SurefireHelper.warnListenerDeactivation(project, getLog());
		SurefireHelper.warnConflictingOrderers(project, getLog());
		SurefireHelper.warnConflictingRunOrder(project, getLog());
		SurefireHelper.warnForkCountInOrderMode(project, getLog());
		SurefireHelper.forceClasspathModeIfNeeded(project, getLog());
		Plugin surefire = SurefireHelper.findSurefirePlugin(project);
		if (surefire != null) {
			SurefireHelper.warnOldSurefireVersion(surefire, getLog());
		}

		// Auto-compact: rebuild index from .deps files periodically to remove stale
		// entries
		if (autoCompactEvery > 0) {
			TestOrderState compactState = loadState();
			int runsSinceLearn = compactState.runsSinceLearn();
			if (runsSinceLearn > 0 && runsSinceLearn % autoCompactEvery == 0) {
				Path depsDirPath = ctx.resolveDepsDir(depsDir);
				if (Files.isDirectory(depsDirPath) && hasDepsFiles(depsDirPath)) {
					getLog().info("[test-order] Auto-compacting index (every " + autoCompactEvery + " runs)");
					try {
						IndexCompactionOperation.compact(depsDirPath, resolveIndexPath(),
								MavenPluginLog.wrap(getLog()));
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

		// Warn if any explicitly specified changed classes are not found in the index.
		if ("explicit".equalsIgnoreCase(changeMode) && !mergedChanged.isEmpty()) {
			Path idxPath = resolveIndexPath();
			if (Files.exists(idxPath)) {
				try {
					warnUnknownChangedClasses(mergedChanged, DependencyMap.load(idxPath));
				} catch (IOException e) {
					getLog().debug("[test-order] Could not load index to validate explicit classes: " + e.getMessage());
				}
			}
		}

		// alwaysLearn pre-aggregation: fold .deps from prior runs into existing index
		if (alwaysLearn) {
			Path indexPath = resolveIndexPath();
			Path depsDirPath = ctx.resolveDepsDir(depsDir);
			if (Files.exists(indexPath) && Files.isDirectory(depsDirPath)) {
				try {
					AggregateOperation.aggregate(depsDirPath, indexPath, MavenPluginLog.wrap(getLog()), true);
				} catch (IOException e) {
					getLog().warn(
							"[test-order] alwaysLearn incremental aggregation failed (ignored): " + e.getMessage());
				}
			}
		}

		TestOrderState state = loadState();
		OrderWorkflow.OrderSetupResult result;
		try {
			result = OrderWorkflow.setup(buildPluginContextBuilder().changedClasses(mergedChangedCsv)
					.changedTestClasses(mergedChangedTestsCsv).build(), state);
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
							getLog().info(
									"[test-order] Rebuilt index from .deps (" + map.size() + " classes) → " + idxPath);
							// Retry ordering with the rebuilt index
							result = OrderWorkflow.setup(buildPluginContextBuilder().changedClasses(mergedChangedCsv)
									.changedTestClasses(mergedChangedTestsCsv).build(), state);
							writeOrdererConfig(result.changedClasses(), result.changedTests(), result.changedMethods(),
									buildScoreOverrides());
							project.getProperties().setProperty("testorder.auto.active", "true");
							return;
						}
					} catch (IOException rebuildEx) {
						getLog().debug("[test-order] .deps rebuild failed: " + rebuildEx.getMessage());
					}
				}

				// Try .bak recovery
				if (recoverIndexFromBackup()) {
					try {
						result = OrderWorkflow.setup(buildPluginContextBuilder().changedClasses(mergedChangedCsv)
								.changedTestClasses(mergedChangedTestsCsv).build(), state);
						writeOrdererConfig(result.changedClasses(), result.changedTests(), result.changedMethods(),
								buildScoreOverrides());
						project.getProperties().setProperty("testorder.auto.active", "true");
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

		if (alwaysLearn) {
			String effectiveInclude = resolveIncludePackages(includePackages, filterByGroupId, project, getLog());

			// When selective learn is also enabled, skip agent attach if there are no
			// structural changes — instrumenting nothing is wasteful and would incorrectly
			// reset the runsSinceLearn counter.
			boolean skipDueToEmptyUncertain = false;
			if (selectiveLearn) {
				Path classesDir = java.nio.file.Path.of(project.getBuild().getOutputDirectory());
				Path projectRoot = ctx != null ? ctx.gitRoot() : project.getBasedir().toPath();
				me.bechberger.testorder.changes.ChangeDetector.Mode changeDetectorMode;
				try {
					changeDetectorMode = me.bechberger.testorder.changes.ChangeDetectionSupport.resolveMode(changeMode,
							ctx != null ? ctx.resolveHashFile(hashFile) : null);
				} catch (IOException e) {
					changeDetectorMode = me.bechberger.testorder.changes.ChangeDetector.Mode.UNCOMMITTED;
				}
				Set<String> uncertain = me.bechberger.testorder.changes.SelectiveLearnSupport
						.computeUncertainClasses(projectRoot, classesDir, changeDetectorMode);
				if (uncertain != null && uncertain.isEmpty()) {
					skipDueToEmptyUncertain = true;
					getLog().info("[test-order] alwaysLearn=true but no structural changes detected"
							+ " — skipping agent attach (nothing to learn)");
				}
			}

			if (!skipDueToEmptyUncertain) {
				if ("offline".equalsIgnoreCase(instrumentation)) {
					configureOfflineLearnMode(instrumentationMode, effectiveInclude);
				} else {
					configureLearnMode(instrumentationMode, effectiveInclude, true);
				}
				getLog().info("[test-order] alwaysLearn=true — agent attached on top of ordered run");
			}
		}

		if (tdd) {
			appendRuntimeConfigProperty(me.bechberger.testorder.TestOrderConfig.TDD, "true");
			appendJunitPlatformProperty("junit.jupiter.extensions.autodetection.enabled", "true");
			getLog().info("[test-order] TDD enforcement enabled — new/changed tests that pass on first run will fail.");
		}

		// R17-12: Mark that prepare already ran to prevent duplicate execution
		// when 'mvn test-order:prepare test' triggers both CLI and lifecycle invocation
		project.getProperties().setProperty("testorder.auto.active", "true");
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
		return session.getGoals().stream()
				.anyMatch(goal -> isGoal(goal, "affected") || isGoal(goal, "auto") || isGoal(goal, "learn")
						|| isGoal(goal, "run-remaining") || isGoal(goal, "run-tier") || isGoal(goal, "tiered-select"));
	}

	/** Returns true when 'test-order:learn' is explicitly on the CLI. */
	private boolean isLearnCliGoal() {
		if (session == null || session.getGoals() == null) {
			return false;
		}
		if (session.getGoals().stream().anyMatch(goal -> isGoal(goal, "learn"))) {
			return true;
		}
		// Also treat offline learn as active when AutoMojo has configured it:
		// restoring classes before tests run would undo the instrumentation.
		if ("true".equals(project.getProperties().getProperty("testorder.offline.learnActive"))) {
			return true;
		}
		// File-based sentinel survives nested Maven JVM processes (e.g.
		// exec-maven-plugin
		// running 'mvn package' in a child JVM) where in-memory project properties are
		// not inherited. Use the classes-backup directory content as the sentinel: it
		// is non-empty only while a learn session is active (backup contents are
		// deleted by restoreInstrumentedClasses at session end), so empty residual
		// directories won't cause false positives.
		String buildDir = project.getBuild().getDirectory();
		if (buildDir != null) {
			java.nio.file.Path backupDir = java.nio.file.Path.of(buildDir).resolve(".test-order")
					.resolve("classes-backup");
			if (java.nio.file.Files.isDirectory(backupDir)) {
				try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(backupDir)) {
					if (stream.anyMatch(p -> p.toString().endsWith(".class"))) {
						return true;
					}
				} catch (java.io.IOException ignored) {
				}
			}
		}
		return false;
	}

	/**
	 * Returns true when learn is active only because of the classes-backup
	 * directory, meaning we are in a nested Maven JVM (not the outer learn
	 * session). The outer session has {@code testorder.offline.learnActive} in
	 * project properties or {@code test-order:learn} on the CLI; the nested JVM has
	 * neither.
	 */
	private boolean isLearnActiveOnlyViaSentinel() {
		if (session != null && session.getGoals() != null
				&& session.getGoals().stream().anyMatch(goal -> isGoal(goal, "learn"))) {
			return false;
		}
		if ("true".equals(project.getProperties().getProperty("testorder.offline.learnActive"))) {
			return false;
		}
		String buildDir = project.getBuild().getDirectory();
		if (buildDir != null) {
			java.nio.file.Path backupDir = java.nio.file.Path.of(buildDir).resolve(".test-order")
					.resolve("classes-backup");
			if (java.nio.file.Files.isDirectory(backupDir)) {
				try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(backupDir)) {
					return stream.anyMatch(p -> p.toString().endsWith(".class"));
				} catch (java.io.IOException ignored) {
				}
			}
		}
		return false;
	}

	/**
	 * Matches both shorthand (test-order:auto) and fully-qualified
	 * (me.bechberger:test-order-maven-plugin:auto) goal forms.
	 */
	private static boolean isGoal(String cliGoal, String goalName) {
		return cliGoal.equals("test-order:" + goalName) || cliGoal.endsWith("test-order-maven-plugin:" + goalName);
	}

	/**
	 * Attempts to recover the dependency index from a .bak backup file. Checks
	 * standard backup locations relative to the project root.
	 */
	private boolean recoverIndexFromBackup() {
		Path idxPath = resolveIndexPath();
		Path projectRoot = project.getBasedir().toPath().toAbsolutePath();
		List<Path> candidates = List.of(idxPath.resolveSibling(idxPath.getFileName() + ".bak"),
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
