package me.bechberger.testorder.plugin;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.PersistenceSupport;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.ops.AlwaysRunScanner;
import me.bechberger.testorder.ops.ChangeDetectionOps;
import me.bechberger.testorder.ops.HashSnapshotOperation;
import me.bechberger.testorder.ops.OrdererConfigOperation;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.PluginLog;

/**
 * Base class for test-order Mojos that share common configuration parameters
 * and helper methods for state loading, change detection, and config writing.
 */
abstract class AbstractTestOrderMojo extends AbstractMojo {

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	protected MavenProject project;

	@Parameter(defaultValue = "${session}", readonly = true, required = true)
	protected MavenSession session;

	protected ReactorContext ctx;

	/** Returns a {@link PluginLog} backed by Maven's logger. */
	protected PluginLog pluginLog() {
		return MavenPluginLog.wrap(getLog());
	}

	/**
	 * Path to the dependency index file (LZ4 compressed binary format). Used by
	 * test-order to load and save test dependencies.
	 */
	@Parameter(property = MavenPluginConfigKeys.INDEX_PATH, defaultValue = "${project.basedir}/.test-order/test-dependencies.lz4")
	protected String indexFile;

	/**
	 * Path to the state file for persisting test execution history and weights.
	 * Used to track test performance over time.
	 */
	@Parameter(property = MavenPluginConfigKeys.STATE_PATH, defaultValue = "${project.basedir}/.test-order/state.lz4")
	protected String stateFile;

	/**
	 * Directory for storing individual .deps files during learn mode. Aggregated
	 * into the index file via 'aggregate' goal.
	 */
	@Parameter(property = MavenPluginConfigKeys.LEGACY_DEPS_DIR, defaultValue = "${project.build.directory}/test-order-deps")
	protected String depsDir;

	/**
	 * When true, skip all test-order processing. Useful for temporarily disabling
	 * the plugin.
	 */
	@Parameter(property = "testorder.skip", defaultValue = "false")
	protected boolean skip;

	/**
	 * Path to the hash file for main source files. Used for hash-based change
	 * detection when git is unavailable.
	 */
	@Parameter(property = MavenPluginConfigKeys.LEGACY_HASH_FILE, defaultValue = "${project.basedir}/.test-order/hashes.lz4")
	protected String hashFile;

	/**
	 * Path to the hash file for test source files. Used for hash-based change
	 * detection of test classes.
	 */
	@Parameter(property = MavenPluginConfigKeys.LEGACY_TEST_HASH_FILE, defaultValue = "${project.basedir}/.test-order/test-hashes.lz4")
	protected String testHashFile;

	/**
	 * Path to the hash file for test methods. Used for method-level change
	 * detection when enabled.
	 */
	@Parameter(property = MavenPluginConfigKeys.LEGACY_METHOD_HASH_FILE, defaultValue = "${project.basedir}/.test-order/method-hashes.lz4")
	protected String methodHashFile;

	/**
	 * Main source root directory. Defaults to the first compile source root from
	 * the Maven model.
	 */
	@Parameter(property = MavenPluginConfigKeys.SOURCE_ROOT)
	protected String sourceRoot;

	/**
	 * Test source root directory. Defaults to the first test compile source root
	 * from the Maven model.
	 */
	@Parameter(property = MavenPluginConfigKeys.LEGACY_TEST_SOURCE_ROOT)
	protected String testSourceRoot;

	/**
	 * Change detection mode: auto, since-last-run, since-last-commit, uncommitted,
	 * explicit
	 */
	@Parameter(property = MavenPluginConfigKeys.CHANGE_MODE, defaultValue = "uncommitted")
	protected String changeMode;

	/** Comma-separated changed class FQCNs (for explicit mode) */
	@Parameter(property = MavenPluginConfigKeys.CHANGED_CLASSES)
	protected String changedClasses;

	/** Optional path to a scoring weights file (overrides state-file weights) */
	@Parameter(property = MavenPluginConfigKeys.WEIGHTS_FILE)
	protected String weightsFile;

	/**
	 * Optional path for verbose agent log output. When set, the test-order agent
	 * will output detailed diagnostic information about instrumentation and
	 * dependency detection. Useful for debugging incorrect test ordering.
	 */
	@Parameter(property = MavenPluginConfigKeys.LEGACY_VERBOSE_FILE)
	protected String verboseFile;

	/**
	 * Enable method-level test ordering within classes (fail-fast on slow/flaky
	 * methods)
	 */
	@Parameter(property = MavenPluginConfigKeys.METHOD_ORDER_ENABLED, defaultValue = "false")
	protected boolean methodOrderingEnabled;

	/**
	 * Group Spring-context-sharing test classes together to reduce context reloads.
	 * Beneficial for Spring Boot projects where context creation is expensive.
	 */
	@Parameter(property = "testorder.score.springContextGrouping", defaultValue = "false")
	protected boolean springContextGrouping;

	// ── Score override parameters (shared by auto, select, prepare, show-order) ──

	/** Score bonus for new test classes not in the dependency index */
	@Parameter(property = MavenPluginConfigKeys.SCORE_NEW_TEST)
	protected Integer scoreNewTest;

	/** Score bonus for test classes whose source was modified */
	@Parameter(property = MavenPluginConfigKeys.SCORE_CHANGED_TEST)
	protected Integer scoreChangedTest;

	/** Maximum score bonus from failure frequency */
	@Parameter(property = MavenPluginConfigKeys.SCORE_MAX_FAILURE)
	protected Integer scoreMaxFailure;

	/** Score bonus for tests with below-median duration */
	@Parameter(property = MavenPluginConfigKeys.SCORE_SPEED)
	protected Integer scoreSpeed;

	/** Score penalty for tests with above-median duration */
	@Parameter(property = MavenPluginConfigKeys.SCORE_SPEED_PENALTY)
	protected Integer scoreSpeedPenalty;

	/** Max score from dependency overlap (ratio-based) */
	@Parameter(property = MavenPluginConfigKeys.SCORE_DEP_OVERLAP)
	protected Integer scoreDepOverlap;

	/** Score bonus based on change complexity of overlapping dependencies */
	@Parameter(property = MavenPluginConfigKeys.SCORE_CHANGE_COMPLEXITY)
	protected Integer scoreChangeComplexity;

	/** Optional fixed bonus when a test overlaps changed static field members */
	@Parameter(property = MavenPluginConfigKeys.SCORE_STATIC_FIELD_BONUS)
	protected Integer scoreStaticFieldBonus;

	/** Set-cover coverage bonus weight (0 = disabled, uses depOverlap instead) */
	@Parameter(property = MavenPluginConfigKeys.SCORE_COVERAGE_BONUS)
	protected Integer scoreCoverageBonus;

	// ── Lifecycle helpers ─────────────────────────────────────────────

	protected void initContext() throws MojoExecutionException {
		if (skip) {
			getLog().info("[test-order] Skipping — testorder.skip=true");
			removeListenerServiceFiles();
			return;
		}
		suppressLz4UnsafeWarnings();
		warnUnknownProperties();
		warnJUnit4Unsupported();
		applyCanonicalUserPropertyOverrides();
		validateParameters();
		ctx = new ReactorContext(session, project);
		try {
			ctx.ensureSharedDirectories();
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to create shared directories", e);
		}
		validateCacheDirectoryWritable();
		PersistenceSupport.cleanupStaleTemps(ctx.resolveBaseDir());
	}

	/**
	 * Validates all parameters are valid and sensible.
	 */
	protected void validateParameters() throws MojoExecutionException {
		ParameterValidator validator = new ParameterValidator(getLog());

		// Validate changeMode
		validator.validateChangeMode(changeMode);

		// Validate explicit mode requirements
		validator.validateExplicitModeRequirements(changeMode, changedClasses);

		// Validate file paths if provided
		if (weightsFile != null && !weightsFile.isBlank()) {
			validator.validateFilePath(weightsFile, "weightsFile");
		}
	}

	/**
	 * Warn about unknown testorder.* properties (likely typos) with Levenshtein
	 * suggestions.
	 */
	private void warnUnknownProperties() {
		if (session == null || session.getUserProperties() == null) {
			return;
		}
		for (String warning : MavenPluginConfigKeys.findUnknownProperties(session.getUserProperties())) {
			getLog().warn("[test-order] " + warning);
		}
	}

	/**
	 * Accept legacy property keys as fallbacks when canonical keys are not set.
	 * This preserves backward compatibility for users still using the old property
	 * names (e.g. testorder.index instead of testorder.index.path).
	 */
	private void applyCanonicalUserPropertyOverrides() {
		if (session == null || session.getUserProperties() == null) {
			return;
		}
		java.util.Properties props = session.getUserProperties();

		// Legacy fallbacks — only apply if the canonical key was NOT explicitly set
		applyLegacyFallback(props, MavenPluginConfigKeys.INDEX_PATH, MavenPluginConfigKeys.LEGACY_INDEX,
				v -> indexFile = v);
		applyLegacyFallback(props, MavenPluginConfigKeys.STATE_PATH, MavenPluginConfigKeys.LEGACY_STATE_FILE,
				v -> stateFile = v);
		applyLegacyFallback(props, MavenPluginConfigKeys.SOURCE_ROOT, MavenPluginConfigKeys.LEGACY_SOURCE_ROOT,
				v -> sourceRoot = v);
		applyLegacyFallback(props, MavenPluginConfigKeys.METHOD_ORDER_ENABLED,
				MavenPluginConfigKeys.LEGACY_METHOD_ORDERING_ENABLED,
				v -> methodOrderingEnabled = Boolean.parseBoolean(v));

		// Warn about deprecated property usage
		for (var entry : java.util.Map.of(MavenPluginConfigKeys.LEGACY_INDEX, MavenPluginConfigKeys.INDEX_PATH,
				MavenPluginConfigKeys.LEGACY_STATE_FILE, MavenPluginConfigKeys.STATE_PATH,
				MavenPluginConfigKeys.LEGACY_SOURCE_ROOT, MavenPluginConfigKeys.SOURCE_ROOT,
				MavenPluginConfigKeys.LEGACY_METHOD_ORDERING_ENABLED, MavenPluginConfigKeys.METHOD_ORDER_ENABLED)
				.entrySet()) {
			String legacyVal = props.getProperty(entry.getKey());
			if (legacyVal != null && !legacyVal.isBlank()) {
				getLog().warn("[test-order] Deprecated property '" + entry.getKey() + "' — use '" + entry.getValue()
						+ "' instead.");
			}
		}
	}

	private void applyLegacyFallback(java.util.Properties props, String canonical, String legacy,
			java.util.function.Consumer<String> setter) {
		String canonicalVal = props.getProperty(canonical);
		if (canonicalVal != null && !canonicalVal.isBlank()) {
			return; // canonical key is set, skip legacy
		}
		String legacyVal = props.getProperty(legacy);
		if (legacyVal != null && !legacyVal.isBlank()) {
			setter.accept(legacyVal);
		}
	}

	protected Path resolveIndexPath() {
		return ctx.resolveIndexFile(indexFile);
	}

	/**
	 * Builds a framework-agnostic {@link PluginContext} from the Maven @Parameter
	 * fields. Subclasses can override to add goal-specific fields (topN, randomM,
	 * etc.).
	 */
	protected PluginContext.Builder buildPluginContextBuilder() {
		Path resolvedSourceRoot = resolveSourceRoot();
		Path resolvedTestSourceRoot = resolveTestSourceRoot();

		List<Path> additionalSourceRoots = new ArrayList<>();
		Path kotlinRoot = project.getBasedir().toPath().resolve("src/main/kotlin");
		if (Files.isDirectory(kotlinRoot)) {
			additionalSourceRoots.add(kotlinRoot);
		}

		return PluginContext.builder().projectRoot(project.getBasedir().toPath().toAbsolutePath())
				.sourceRoot(resolvedSourceRoot).testSourceRoot(resolvedTestSourceRoot)
				.additionalSourceRoots(additionalSourceRoots)
				.testClassesDir(Path.of(project.getBuild().getTestOutputDirectory()))
				.indexFile(ctx.resolveIndexFile(indexFile)).stateFile(ctx.resolveStateFile(stateFile))
				.depsDir(ctx.resolveDepsDir(depsDir)).hashFile(ctx.resolveHashFile(hashFile))
				.testHashFile(ctx.resolveTestHashFile(testHashFile))
				.methodHashFile(ctx.resolveMethodHashFile(methodHashFile)).changeMode(changeMode)
				.changedClasses(changedClasses)
				.weightsFile(weightsFile != null && !weightsFile.isBlank() ? Path.of(weightsFile) : null)
				.scoreOverrides(buildScoreOverrides()).methodOrderingEnabled(methodOrderingEnabled)
				.springContextGrouping(springContextGrouping).groupId(project.getGroupId())
				.verboseFile(verboseFile != null && !verboseFile.isBlank() ? Path.of(verboseFile) : null)
				.dependencyFingerprintSupplier(() -> computeMavenDependencyFingerprint())
				.projectName(project.getArtifactId()).log(pluginLog());
	}

	protected PluginContext buildPluginContext() {
		return buildPluginContextBuilder().build();
	}

	/**
	 * Computes a dependency fingerprint from the project's resolved artifacts.
	 * Detects SNAPSHOT rebuilds, version bumps, and transitive changes.
	 */
	private String computeMavenDependencyFingerprint() {
		var artifacts = project.getArtifacts();
		if (artifacts == null || artifacts.isEmpty()) {
			// Fallback to build-file fingerprinting
			return me.bechberger.testorder.ops.BuildFileFingerprint.computeFromBuildFiles(
					project.getBasedir().toPath());
		}
		var classpathEntries = artifacts.stream()
				.filter(a -> a.getFile() != null)
				.map(a -> a.getFile().toPath())
				.toList();
		return me.bechberger.testorder.ops.BuildFileFingerprint.compute(classpathEntries,
				project.getBasedir().toPath());
	}

	/**
	 * Checks that the cache directory (.test-order/) is writable. Provides a clear
	 * error message with permission details if not.
	 */
	private void validateCacheDirectoryWritable() throws MojoExecutionException {
		Path baseDir = ctx.resolveBaseDir();
		if (baseDir != null && Files.exists(baseDir) && !Files.isWritable(baseDir)) {
			String perms = "";
			try {
				perms = java.nio.file.Files.getPosixFilePermissions(baseDir).toString();
			} catch (UnsupportedOperationException | IOException ignored) {
				// Non-POSIX or can't read
			}
			throw new MojoExecutionException("Cannot write to cache directory: " + baseDir
					+ (perms.isEmpty() ? "" : " (permissions: " + perms + ")") + ". Fix: chmod 755 " + baseDir);
		}
	}

	// ── Source root resolution ────────────────────────────────────────

	/**
	 * Resolves the main source root. Priority: explicit config → Maven model →
	 * fallback to src/main/java.
	 */
	protected Path resolveSourceRoot() {
		if (sourceRoot != null && !sourceRoot.isBlank())
			return Path.of(sourceRoot);
		List<String> roots = project.getCompileSourceRoots();
		if (roots != null && !roots.isEmpty())
			return Path.of(roots.get(0));
		return project.getBasedir().toPath().resolve("src/main/java");
	}

	/**
	 * Resolves the test source root. Priority: explicit config → Maven model →
	 * fallback to src/test/java.
	 */
	protected Path resolveTestSourceRoot() {
		if (testSourceRoot != null && !testSourceRoot.isBlank())
			return Path.of(testSourceRoot);
		List<String> roots = project.getTestCompileSourceRoots();
		if (roots != null && !roots.isEmpty())
			return Path.of(roots.get(0));
		return project.getBasedir().toPath().resolve("src/test/java");
	}

	// ── State and change detection ────────────────────────────────────

	protected TestOrderState loadState() {
		Path path = ctx.resolveStateFile(stateFile);
		if (!Files.exists(path))
			return new TestOrderState();
		try {
			return TestOrderState.load(path);
		} catch (IOException e) {
			getLog().warn("[test-order] Failed to load state: " + e.getMessage());
			return new TestOrderState();
		}
	}

	protected Set<String> detectChangedClasses() {
		return detectChangedClasses(true);
	}

	protected Set<String> detectChangedClasses(boolean readOnly) {
		PluginLog plog = pluginLog();
		Set<String> own = ChangeDetectionOps.detectChangedClassesWithKotlin(changeMode, ctx.gitRoot(),
				resolveSourceRoot(), ctx.resolveHashFile(hashFile), changedClasses, readOnly, plog);
		ctx.storeChangedClasses(own);
		Set<String> upstream = ctx.collectUpstreamChangedClasses();
		if (upstream.isEmpty())
			return own;
		Set<String> merged = new LinkedHashSet<>(own);
		merged.addAll(upstream);
		return merged;
	}

	/**
	 * Validates explicitly specified changed classes against the dependency index.
	 * Logs a warning for each class not found in the index and throws if none
	 * match. Protects against silently wrong test selection (M-CRIT-1).
	 */
	protected void warnUnknownChangedClasses(Set<String> changed, DependencyMap depMap) throws MojoExecutionException {
		try {
			new me.bechberger.testorder.ops.ParameterValidator(pluginLog()).warnUnknownChangedClasses(changed, depMap,
					changeMode);
		} catch (IllegalArgumentException e) {
			throw new MojoExecutionException(e.getMessage());
		}
	}

	protected Set<String> detectChangedTestClasses() {
		return detectChangedTestClasses(true);
	}

	protected Set<String> detectChangedTestClasses(boolean readOnly) {
		PluginLog plog = pluginLog();
		Set<String> own = ChangeDetectionOps.detectChangedTestClassesWithKotlin(changeMode, ctx.gitRoot(),
				resolveTestSourceRoot(), ctx.resolveTestHashFile(testHashFile), readOnly, plog);
		ctx.storeChangedTestClasses(own);
		Set<String> upstream = ctx.collectUpstreamChangedTestClasses();
		if (upstream.isEmpty())
			return own;
		Set<String> merged = new LinkedHashSet<>(own);
		merged.addAll(upstream);
		return merged;
	}

	/**
	 * Detects changed test methods by comparing per-method hashes against the
	 * previous snapshot. Returns {@code className#methodName} keys.
	 */
	protected Set<String> detectChangedMethods() {
		return ChangeDetectionOps.detectChangedMethods(resolveTestSourceRoot(),
				ctx.resolveMethodHashFile(methodHashFile), pluginLog());
	}

	// ── Aggregation ───────────────────────────────────────────────────

	protected boolean hasDepsFiles(Path depsDirPath) {
		try (var stream = Files.list(depsDirPath)) {
			return stream.anyMatch(f -> f.toString().endsWith(".deps"));
		} catch (IOException e) {
			return false;
		}
	}

	protected void autoAggregate(Path depsDirPath, Path idxPath) throws MojoExecutionException {
		try {
			DependencyMap map = DependencyMap.aggregate(depsDirPath);
			if (map.size() == 0) {
				getLog().info(
						"[test-order] No test dependencies found in " + depsDirPath + " — skipping index creation.");
				return;
			}
			map.save(idxPath);
			getLog().info("[test-order] Auto-aggregated " + map.size() + " test classes → " + idxPath);
			warnIfNoDeps(map);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to auto-aggregate deps", e);
		}
	}

	/**
	 * Returns the set of compiled test class FQCNs (non-inner, i.e. no {@code $})
	 * that are present in {@code target/test-classes} but absent from the given
	 * dependency index. An empty set means the index is up-to-date with the
	 * compiled test output.
	 */
	protected Set<String> findNewTestClasses(Path idxPath) {
		if (!Files.exists(idxPath))
			return Set.of();
		DependencyMap depMap;
		try {
			depMap = DependencyMap.load(idxPath);
		} catch (Exception e) {
			getLog().debug("[test-order] Could not load index to check for new tests: " + e.getMessage());
			return Set.of();
		}
		return me.bechberger.testorder.ops.TestClassDiscovery.findNewTestClasses(depMap,
				Path.of(project.getBuild().getTestOutputDirectory()), pluginLog());
	}

	protected Set<String> currentModuleTestClasses() {
		return me.bechberger.testorder.ops.TestClassDiscovery
				.scanTestClasses(Path.of(project.getBuild().getTestOutputDirectory()));
	}

	protected DependencyMap currentModuleDependencyMap(DependencyMap dependencyMap) {
		return me.bechberger.testorder.ops.TestClassDiscovery.filterToModule(dependencyMap,
				Path.of(project.getBuild().getTestOutputDirectory()));
	}

	/**
	 * Warns if the loaded dependency map has no meaningful dependencies (common
	 * with groupId/package mismatch). Detects both truly empty deps and deps that
	 * contain only framework/plugin classes.
	 */
	protected void warnIfNoDeps(DependencyMap depMap) {
		me.bechberger.testorder.ops.TestClassDiscovery.warnIfNoDeps(depMap,
				"-D" + MavenPluginConfigKeys.INCLUDE_PACKAGES, pluginLog());
	}

	/**
	 * Attempts auto-aggregation from the deps directory; throws if no deps
	 * directory exists.
	 */
	protected void autoAggregateOrFail(Path idxPath) throws MojoExecutionException {
		Path depsDirPath = ctx.resolveDepsDir(depsDir);
		if (Files.isDirectory(depsDirPath) && hasDepsFiles(depsDirPath)) {
			autoAggregate(depsDirPath, idxPath);
		} else {
			throw new MojoExecutionException("No dependency index at " + idxPath + " and no .deps files found in "
					+ depsDirPath + ". Run learn mode first: mvn test -Dtestorder.mode=learn");
		}
	}

	// ── Hashes ────────────────────────────────────────────────────────

	protected void snapshotHashes() {
		PluginLog plog = pluginLog();
		HashSnapshotOperation.snapshot(resolveSourceRoot(), ctx.resolveHashFile(hashFile), resolveTestSourceRoot(),
				ctx.resolveTestHashFile(testHashFile),
				(label, path) -> plog.info("[test-order] Saved " + label + " hash snapshot: " + path),
				(label, msg) -> plog.warn("[test-order] Failed to save " + label + " hash snapshot: " + msg));
		ChangeDetectionOps.snapshotMethodHashes(resolveTestSourceRoot(), ctx.resolveMethodHashFile(methodHashFile),
				plog);
	}

	// ── @AlwaysRun discovery ──────────────────────────────────────────

	/**
	 * Scans compiled test classes in {@code target/test-classes} for the
	 * {@code @AlwaysRun} annotation descriptor in the constant pool. Returns the
	 * set of fully-qualified class names that carry the annotation.
	 */
	protected Set<String> discoverAlwaysRunClasses() {
		Path testClassesDir = Path.of(project.getBuild().getTestOutputDirectory());
		Set<String> result = AlwaysRunScanner.scan(testClassesDir);
		if (!result.isEmpty()) {
			getLog().info("[test-order] Discovered @AlwaysRun classes: " + result);
		}
		return result;
	}

	// ── Weights ───────────────────────────────────────────────────────

	protected TestOrderState.ScoringWeights resolveWeights(TestOrderState state) {
		return me.bechberger.testorder.ops.WeightResolverOperation.resolveWeights(
				weightsFile != null && !weightsFile.isBlank() ? Path.of(weightsFile) : null, state, pluginLog());
	}

	protected TestOrderState.LoadedWeights resolveLoadedWeights(TestOrderState state) {
		return me.bechberger.testorder.ops.WeightResolverOperation.resolveLoadedWeights(
				weightsFile != null && !weightsFile.isBlank() ? Path.of(weightsFile) : null, state, pluginLog());
	}

	/**
	 * Resolves weights from state/file and applies any user-specified score
	 * overrides.
	 */
	protected TestOrderState.ScoringWeights buildWeightsWithOverrides(TestOrderState state) {
		TestOrderState.ScoringWeights sw = resolveWeights(state);
		return me.bechberger.testorder.ops.WeightResolverOperation.applyOverrides(sw, scoreNewTest, scoreChangedTest,
				scoreMaxFailure, scoreSpeed, scoreSpeedPenalty, scoreDepOverlap, scoreChangeComplexity,
				scoreStaticFieldBonus, scoreCoverageBonus);
	}

	/**
	 * Builds a map of score overrides from the user-specified score parameters.
	 * Returns null if no overrides are set.
	 */
	protected Map<String, Integer> buildScoreOverrides() {
		return me.bechberger.testorder.ops.WeightResolverOperation.buildScoreOverrides(scoreNewTest, scoreChangedTest,
				scoreMaxFailure, scoreSpeed, scoreSpeedPenalty, scoreDepOverlap, scoreChangeComplexity,
				scoreStaticFieldBonus, scoreCoverageBonus);
	}

	// ── Orderer config ────────────────────────────────────────────────

	/**
	 * Escapes a value for use in a Java {@code .properties} file. Backslashes must
	 * be doubled because {@link java.util.Properties#load} treats them as escape
	 * characters (e.g. {@code \t} → tab).
	 */
	private static String escapePropertyValue(String value) {
		return value.replace("\\", "\\\\");
	}

	/**
	 * Writes {@code junit-platform.properties} and
	 * {@code testorder-config.properties} to {@code target/test-classes}.
	 *
	 * @param scoreOverrides
	 *            if non-null, writes {@code testorder.score.*} properties
	 */
	protected void writeOrdererConfig(Set<String> changed, Set<String> changedTests,
			Map<String, Integer> scoreOverrides) throws MojoExecutionException {
		writeOrdererConfig(changed, changedTests, Set.of(), scoreOverrides);
	}

	/**
	 * Writes {@code junit-platform.properties} and
	 * {@code testorder-config.properties} to {@code target/test-classes}.
	 *
	 * @param changedMethods
	 *            className#methodName keys for changed test methods
	 * @param scoreOverrides
	 *            if non-null, writes {@code testorder.score.*} properties
	 */
	protected void writeOrdererConfig(Set<String> changed, Set<String> changedTests, Set<String> changedMethods,
			Map<String, Integer> scoreOverrides) throws MojoExecutionException {
		// Ensure test-order-junit (PriorityClassOrderer + TelemetryListener) is on the
		// test classpath
		injectTestClasspath(resolveOrdererClasspath());
		ensureListenerServiceFile();
		if (isTestNGOnTestClasspath()) {
			ensureTestNGListenerServiceFile();
		}
		injectNativeAccessFlag();

		// L6: Warn if user's junit-platform.properties contains orderer config that we'll overwrite
		warnConflictingJUnitPlatformProperties();

		Path resolvedSourceRoot = resolveSourceRoot();

		// Build framework-agnostic config map via shared operation
		Map<String, String> configMap = OrdererConfigOperation.buildConfig(
				new OrdererConfigOperation.OrdererInput(ctx.resolveIndexFile(indexFile).toAbsolutePath().toString(),
						ctx.resolveStateFile(stateFile).toAbsolutePath().toString(), weightsFile, changed, changedTests,
						changedMethods, scoreOverrides, methodOrderingEnabled, springContextGrouping,
						project.getBasedir().toPath().toAbsolutePath().toString(),
						resolvedSourceRoot.toAbsolutePath().toString(), changeMode));

		Path testClassesDir = Path.of(project.getBuild().getTestOutputDirectory());
		try {
			Files.createDirectories(testClassesDir);
			Path propsFile = testClassesDir.resolve("junit-platform.properties");
			try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(propsFile))) {
				pw.println("junit.jupiter.testclass.order.default=me.bechberger.testorder.PriorityClassOrderer");
			}

			Path configFile = testClassesDir.resolve("testorder-config.properties");
			try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(configFile))) {
				for (var entry : configMap.entrySet()) {
					pw.println(entry.getKey() + "=" + escapePropertyValue(entry.getValue()));
				}
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to write orderer config", e);
		}
		suggestSpringContextGrouping();
	}

	protected void writeOrdererConfig(Set<String> changed, Set<String> changedTests) throws MojoExecutionException {
		writeOrdererConfig(changed, changedTests, null);
	}

	/**
	 * Writes a pre-built config map to {@code target/test-classes}
	 * (junit-platform.properties + testorder-config.properties) and injects the
	 * test-order runtime classpath. Use this when the config map has already been
	 * computed by a shared workflow.
	 */
	protected void writeOrdererConfigFromMap(Map<String, String> configMap) throws MojoExecutionException {
		injectTestClasspath(resolveOrdererClasspath());
		ensureListenerServiceFile();
		if (isTestNGOnTestClasspath()) {
			ensureTestNGListenerServiceFile();
		}
		injectNativeAccessFlag();

		Path testClassesDir = Path.of(project.getBuild().getTestOutputDirectory());
		try {
			Files.createDirectories(testClassesDir);
			Path propsFile = testClassesDir.resolve("junit-platform.properties");
			try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(propsFile))) {
				pw.println("junit.jupiter.testclass.order.default=me.bechberger.testorder.PriorityClassOrderer");
			}
			Path configFile = testClassesDir.resolve("testorder-config.properties");
			try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(configFile))) {
				for (var entry : configMap.entrySet()) {
					pw.println(entry.getKey() + "=" + escapePropertyValue(entry.getValue()));
				}
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to write orderer config", e);
		}
		suggestSpringContextGrouping();
	}

	// ── Learn mode ────────────────────────────────────────────────────

	/**
	 * Configures Surefire for learn mode: attaches the agent, sets up the
	 * classpath, and configures system properties for the forked JVM.
	 *
	 * @param instrumentationMode
	 *            METHOD_ENTRY, FULL, FULL_METHOD, or FULL_MEMBER
	 * @param includePackages
	 *            effective package filter (may be null)
	 * @param includeIndexInArgs
	 *            whether to pass the index file path to the agent
	 */
	protected void configureLearnMode(String instrumentationMode, String includePackages, boolean includeIndexInArgs)
			throws MojoExecutionException {
		Plugin surefire = SurefireHelper.requireSurefirePlugin(project);
		Xpp3Dom config = SurefireHelper.getOrCreateConfiguration(surefire);
		String surefireArgLine = "";
		Xpp3Dom argLineNode = config.getChild("argLine");
		if (argLineNode != null && argLineNode.getValue() != null) {
			surefireArgLine = argLineNode.getValue().trim();
		}
		boolean hasHardcodedSurefireArgLine = SurefireHelper.isHardcodedArgLine(surefireArgLine);

		String projectArgLine = project.getProperties().getProperty("argLine", "").trim();

		// Guard against double attachment (e.g. prepare bound in POM and also invoked
		// on CLI)
		String existingArgLine = (surefireArgLine + " " + projectArgLine).trim();
		String existingDebug = project.getProperties().getProperty("maven.surefire.debug", "").trim();
		if (existingArgLine.contains("test-order-agent") || existingDebug.contains("test-order-agent")) {
			getLog().info("[test-order] Learn mode agent already configured — skipping duplicate attachment.");
			return;
		}

		getLog().info("[test-order] Learn mode (" + instrumentationMode.toUpperCase()
				+ "): attaching agent, default fork mode");

		Path agentJar = resolveArtifact("test-order-agent");

		String agentArgs = me.bechberger.testorder.AgentArgsBuilder.buildArgs(ctx.resolveDepsDir(depsDir),
				instrumentationMode, includeIndexInArgs ? ctx.resolveIndexFile(indexFile) : null, includePackages,
				verboseFile);

		String statPathStr = ctx.resolveStateFile(stateFile).toAbsolutePath().toString();
		// -Xshare:off suppresses the CDS warning caused by -javaagent appending
		// to the bootstrap classpath.
		String agentString = "-Xshare:off \"-javaagent:" + agentJar.toAbsolutePath() + "=" + agentArgs + "\"";
		// Pass system properties via -D flags; using <systemPropertyVariables> XML
		// modifications to an already-planned MojoExecution are not picked up by
		// Surefire.
		String sysProps = " -D" + MavenPluginConfigKeys.LEARN + "=true" + " -D"
				+ MavenPluginConfigKeys.INSTRUMENTATION_MODE + "=" + instrumentationMode.toUpperCase() + " -D"
				+ MavenPluginConfigKeys.STATE_PATH + "=" + statPathStr;

		// Suppress "restricted method" warnings from bundled lz4 native access
		// (System.loadLibrary, sun.misc.Unsafe). Flag supported on JDK 16+.
		String nativeAccess = nativeAccessFlag(existingArgLine);

		if (hasHardcodedSurefireArgLine) {
			// The Surefire argLine is a hardcoded literal with no Maven property
			// placeholder.
			// Modifying the Xpp3Dom in-memory is ineffective because Surefire captured its
			// MojoExecution configuration before this goal ran. Instead, use the
			// maven.surefire.debug user property: Surefire reads this at fork-JVM time and
			// appends the value verbatim to the JVM command line — exactly what we need.
			getLog().warn("[test-order] Surefire <argLine> is hardcoded (no @{argLine} or ${argLine} placeholder). "
					+ "Injecting agent via maven.surefire.debug user property. "
					+ "Consider using @{argLine} in your POM to chain agents safely.");
			String existingDebugValue = project.getProperties() != null
					? project.getProperties().getProperty("maven.surefire.debug")
					: null;
			String debugValue = ((existingDebugValue == null ? "" : existingDebugValue + " ") + agentString + sysProps
					+ nativeAccess).trim();
			project.getProperties().setProperty("maven.surefire.debug", debugValue);
		} else {
			String mergedProjectArgLine = (projectArgLine + " " + agentString + sysProps + nativeAccess).trim();
			String mergedSurefireArgLine = (surefireArgLine + " " + agentString + sysProps + nativeAccess).trim();
			project.getProperties().setProperty("argLine", mergedProjectArgLine);
			SurefireHelper.setChild(config, "argLine", mergedSurefireArgLine);
		}

		injectTestClasspath(resolveOrdererClasspath());
		ensureListenerServiceFile();
		if (isTestNGOnTestClasspath()) {
			ensureTestNGListenerServiceFile();
		}

		snapshotHashes();
	}

	/**
	 * Resolves the effective includePackages value by combining:
	 * <ol>
	 * <li>Source packages auto-detected from {@code src/main/java} (always
	 * included)</li>
	 * <li>User-specified {@code includePackages} (additive)</li>
	 * <li>groupId-based filter (fallback when nothing else is found and
	 * filterByGroupId is true)</li>
	 * </ol>
	 * Redundant prefixes are removed (e.g. if {@code com.example} and
	 * {@code com.example.app} are both detected, only {@code com.example} is kept).
	 */
	protected static String resolveIncludePackages(String includePackages, boolean filterByGroupId,
			MavenProject project, Log log) {
		List<String> roots = project.getCompileSourceRoots();
		Path sourceRoot = (roots != null && !roots.isEmpty())
				? Path.of(roots.get(0))
				: project.getBasedir().toPath().resolve("src/main/java");
		String result = me.bechberger.testorder.PackageDetectorSupport.resolveIncludePackages(sourceRoot,
				includePackages, filterByGroupId, project.getGroupId());
		if (result != null) {
			log.info("[test-order] Instrumentation packages: " + result);
		}
		return result;
	}

	/**
	 * Returns {@code " --enable-native-access=ALL-UNNAMED"} unless the given
	 * existing argLine already contains the flag.
	 */
	static String nativeAccessFlag(String existingArgLine) {
		if (existingArgLine != null && existingArgLine.contains("--enable-native-access")) {
			return "";
		}
		return " --enable-native-access=ALL-UNNAMED";
	}

	/**
	 * Ensures that {@code --enable-native-access=ALL-UNNAMED} is present in the
	 * Surefire argLine for the forked test JVM. This suppresses JDK 22+ warnings
	 * from bundled lz4 native access ({@code System.loadLibrary},
	 * {@code sun.misc.Unsafe}). Called from order-mode config paths that don't
	 * otherwise modify the argLine.
	 */
	protected void injectNativeAccessFlag() {
		Plugin surefire = SurefireHelper.findSurefirePlugin(project);
		if (surefire == null)
			return;
		Xpp3Dom config = SurefireHelper.getOrCreateConfiguration(surefire);

		String surefireArgLine = "";
		Xpp3Dom argLineNode = config.getChild("argLine");
		if (argLineNode != null && argLineNode.getValue() != null) {
			surefireArgLine = argLineNode.getValue().trim();
		}
		String projectArgLine = project.getProperties().getProperty("argLine", "").trim();
		String combined = surefireArgLine + " " + projectArgLine;

		if (combined.contains("--enable-native-access"))
			return;

		String flag = " --enable-native-access=ALL-UNNAMED";
		String newProjectArgLine = (projectArgLine + flag).trim();
		project.getProperties().setProperty("argLine", newProjectArgLine);
		if (!surefireArgLine.isEmpty()) {
			SurefireHelper.setChild(config, "argLine", (surefireArgLine + flag).trim());
		}
	}

	/**
	 * Adds the given jar to Surefire's test classpath via the
	 * {@code maven.test.additionalClasspath} project property.
	 * <p>
	 * Using the property avoids the issue where Xpp3Dom modifications to the
	 * Surefire plugin configuration are not picked up by already-planned
	 * MojoExecution objects.
	 */
	protected void injectTestClasspath(Path... jars) {
		String existing = project.getProperties().getProperty("maven.test.additionalClasspath", "");
		LinkedHashSet<String> entries = new LinkedHashSet<>();
		if (!existing.isBlank()) {
			for (String part : existing.split(",")) {
				String trimmed = part.trim();
				if (!trimmed.isEmpty()) {
					entries.add(trimmed);
				}
			}
		}
		for (Path jar : jars) {
			if (jar != null) {
				entries.add(jar.toAbsolutePath().toString());
			}
		}
		String classpath = String.join(",", entries);
		project.getProperties().setProperty("maven.test.additionalClasspath", classpath);
		// Use session user properties (not System.setProperty) to ensure Surefire
		// sees this for already-planned executions without leaking JVM-globally.
		if (session.getUserProperties() != null) {
			session.getUserProperties().setProperty("maven.test.additionalClasspath", classpath);
		}
	}

	protected Path[] resolveOrdererClasspath() throws MojoExecutionException {
		LinkedHashSet<Path> entries = new LinkedHashSet<>();
		entries.add(resolveModuleOutputOrArtifact("test-order-junit"));
		entries.add(resolveModuleOutputOrArtifact("test-order-annotations"));
		if (isTestNGOnTestClasspath()) {
			entries.add(resolveModuleOutputOrArtifact("test-order-testng"));
		}
		// Anchor on DependencyMap to locate test-order-core (and its bundled
		// dependencies)
		entries.add(codeSourcePath(DependencyMap.class));
		// Dynamically resolve additional runtime jars by class name to avoid
		// compile-time dependencies
		for (String className : new String[] { "me.bechberger.femtocli.FemtoCli", "net.jpountz.lz4.LZ4FrameInputStream",
				"org.roaringbitmap.RoaringBitmap", "io.jenetics.Genotype", "com.github.javaparser.JavaParser",
				"com.electronwill.nightconfig.core.CommentedConfig", "com.electronwill.nightconfig.toml.TomlParser",
				"me.bechberger.util.json.Util" }) {
			try {
				Class<?> cls = Class.forName(className);
				Path jar = codeSourcePath(cls);
				if (jar != null)
					entries.add(jar);
			} catch (ClassNotFoundException ignored) {
				getLog().debug("[test-order] Class not found on plugin classpath: " + className);
			}
		}
		return entries.toArray(Path[]::new);
	}

	private Path resolveModuleOutputOrArtifact(String artifactId) throws MojoExecutionException {
		Path reactorClassesDir = project.getBasedir().toPath().getParent().resolve(artifactId).resolve("target")
				.resolve("classes");
		if (Files.isDirectory(reactorClassesDir)) {
			return reactorClassesDir;
		}
		return resolveArtifact(artifactId);
	}

	private Path codeSourcePath(Class<?> anchor) throws MojoExecutionException {
		try {
			var codeSource = anchor.getProtectionDomain().getCodeSource();
			if (codeSource == null || codeSource.getLocation() == null) {
				throw new MojoExecutionException("Cannot resolve runtime location for " + anchor.getName());
			}
			URI uri = codeSource.getLocation().toURI();
			return Path.of(uri).toAbsolutePath();
		} catch (URISyntaxException e) {
			throw new MojoExecutionException("Cannot resolve runtime location for " + anchor.getName(), e);
		}
	}

	/**
	 * Removes service files written by previous runs so that skipping test-order
	 * does not leave stale SPI registrations that cause
	 * {@code ServiceConfigurationError} when the test-order jars are no longer on
	 * the classpath.
	 */
	private void removeListenerServiceFiles() {
		String testOutputDir = project.getBuild().getTestOutputDirectory();
		if (testOutputDir == null)
			return;
		Path serviceDir = Path.of(testOutputDir).resolve("META-INF").resolve("services");
		removeTestOrderEntryFromServiceFile(serviceDir.resolve("org.junit.platform.launcher.TestExecutionListener"),
				"me.bechberger.testorder.TelemetryListener");
		removeTestOrderEntryFromServiceFile(serviceDir.resolve("org.testng.ITestNGListener"),
				"me.bechberger.testorder.");
	}

	private void removeTestOrderEntryFromServiceFile(Path serviceFile, String prefix) {
		if (!Files.exists(serviceFile))
			return;
		try {
			List<String> lines = Files.readAllLines(serviceFile);
			List<String> kept = lines.stream().filter(l -> !l.trim().startsWith(prefix)).toList();
			if (kept.isEmpty() || kept.stream().allMatch(String::isBlank)) {
				Files.delete(serviceFile);
			} else if (kept.size() < lines.size()) {
				Files.writeString(serviceFile, String.join("\n", kept) + "\n");
			}
		} catch (IOException e) {
			getLog().debug("Could not clean up service file " + serviceFile + ": " + e.getMessage());
		}
	}

	/**
	 * Writes the
	 * {@code META-INF/services/org.junit.platform.launcher.TestExecutionListener}
	 * service file directly into {@code target/test-classes} so that the JUnit
	 * Platform's ServiceLoader discovers our {@code TelemetryListener} regardless
	 * of classloader hierarchy or auto-detection configuration.
	 */
	protected void ensureListenerServiceFile() throws MojoExecutionException {
		Path testClassesDir = Path.of(project.getBuild().getTestOutputDirectory());
		Path serviceDir = testClassesDir.resolve("META-INF").resolve("services");
		Path serviceFile = serviceDir.resolve("org.junit.platform.launcher.TestExecutionListener");
		try {
			Files.createDirectories(serviceDir);
			String listenerFqcn = "me.bechberger.testorder.TelemetryListener";
			// Use a single atomic read-check-write to avoid TOCTOU races in
			// parallel reactor builds (CWE-367)
			String existing = "";
			try {
				existing = Files.readString(serviceFile);
			} catch (java.nio.file.NoSuchFileException ignored) {
				// File doesn't exist yet — will create below
			}
			if (existing.contains(listenerFqcn)) {
				return;
			}
			// Append to existing content (user may have their own listeners)
			String content = existing.isEmpty()
					? listenerFqcn + "\n"
					: existing.stripTrailing() + "\n" + listenerFqcn + "\n";
			Files.writeString(serviceFile, content);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to write TelemetryListener service file", e);
		}
	}

	/**
	 * Returns {@code true} when the project declares {@code org.testng:testng} as a
	 * test dependency.
	 */
	protected boolean isTestNGOnTestClasspath() {
		return project.getDependencies().stream()
				.anyMatch(d -> "org.testng".equals(d.getGroupId()) && "testng".equals(d.getArtifactId()));
	}

	/**
	 * Returns {@code true} when the project declares {@code junit:junit} (JUnit 4)
	 * as a dependency.
	 */
	protected boolean isJUnit4OnTestClasspath() {
		return project.getDependencies().stream()
				.anyMatch(d -> "junit".equals(d.getGroupId()) && "junit".equals(d.getArtifactId()));
	}

	/**
	 * Returns {@code true} when the project declares a JUnit Jupiter dependency.
	 */
	protected boolean isJUnit5OnTestClasspath() {
		return project.getDependencies().stream()
				.anyMatch(d -> "org.junit.jupiter".equals(d.getGroupId()));
	}

	/**
	 * Warns when JUnit 4 tests are detected on the classpath, since test-order
	 * only supports JUnit 5 (Jupiter) and TestNG.
	 */
	protected void warnJUnit4Unsupported() {
		if (isJUnit4OnTestClasspath()) {
			if (isJUnit5OnTestClasspath()) {
				getLog().warn("[test-order] JUnit 4 dependency detected alongside JUnit 5. "
						+ "test-order only supports JUnit 5 (Jupiter) and TestNG — "
						+ "JUnit 4 tests will not be reordered or tracked. "
						+ "Consider migrating to JUnit 5 or using the JUnit Vintage engine.");
			} else {
				getLog().warn("[test-order] JUnit 4 dependency detected but no JUnit 5 (Jupiter) found. "
						+ "test-order does NOT support JUnit 4 — tests will not be reordered or tracked. "
						+ "Please migrate to JUnit 5 or add the JUnit Vintage engine with "
						+ "junit-jupiter-engine on the test classpath.");
			}
		}
	}

	/**
	 * Returns {@code true} when the project declares {@code spring-boot-test} or
	 * {@code spring-test} as a dependency.
	 */
	protected boolean isSpringTestOnClasspath() {
		return project.getDependencies().stream().anyMatch(
				d -> "org.springframework.boot".equals(d.getGroupId()) && "spring-boot-test".equals(d.getArtifactId())
						|| "org.springframework".equals(d.getGroupId()) && "spring-test".equals(d.getArtifactId()));
	}

	/**
	 * Logs a suggestion to enable {@code springContextGrouping} when Spring test
	 * dependencies are detected but the option is not enabled.
	 */
	protected void suggestSpringContextGrouping() {
		if (!springContextGrouping && isSpringTestOnClasspath()) {
			getLog().info("[test-order] Spring test dependency detected. Consider enabling "
					+ "-Dtestorder.score.springContextGrouping=true to reduce Spring context reloads "
					+ "caused by test reordering.");
		}
	}

	/**
	 * L6: Warns if the user has a {@code junit-platform.properties} in
	 * {@code src/test/resources} that configures a ClassOrderer or MethodOrderer,
	 * since test-order overwrites this file in {@code target/test-classes}.
	 */
	protected void warnConflictingJUnitPlatformProperties() {
		Path srcTestResources = project.getBasedir().toPath().resolve("src/test/resources/junit-platform.properties");
		if (Files.exists(srcTestResources)) {
			try {
				String content = Files.readString(srcTestResources);
				if (content.contains("junit.jupiter.testclass.order.default")
						|| content.contains("junit.jupiter.testmethod.order.default")) {
					getLog().warn("[test-order] src/test/resources/junit-platform.properties contains orderer config — "
							+ "test-order will overwrite it in target/test-classes. Remove orderer settings "
							+ "from your junit-platform.properties to avoid conflicts.");
				}
				if (content.contains("junit.platform.execution.listeners.deactivate")) {
					getLog().warn("[test-order] src/test/resources/junit-platform.properties contains "
							+ "listener deactivation config — this may disable test-order's TelemetryListener.");
				}
			} catch (IOException ignored) { }
		}
	}

	/**
	 * Writes the {@code META-INF/services/org.testng.ITestNGListener} service file
	 * into {@code target/test-classes} so that TestNG's ServiceLoader discovers our
	 * {@code TestNGTelemetryListener} and {@code TestNGPriorityInterceptor}.
	 */
	protected void ensureTestNGListenerServiceFile() throws MojoExecutionException {
		Path testClassesDir = Path.of(project.getBuild().getTestOutputDirectory());
		Path serviceDir = testClassesDir.resolve("META-INF").resolve("services");
		Path serviceFile = serviceDir.resolve("org.testng.ITestNGListener");
		try {
			Files.createDirectories(serviceDir);
			String listener = "me.bechberger.testorder.testng.TestNGTelemetryListener";
			String interceptor = "me.bechberger.testorder.testng.TestNGPriorityInterceptor";
			if (Files.exists(serviceFile)) {
				String existing = Files.readString(serviceFile);
				StringBuilder sb = new StringBuilder(existing.stripTrailing());
				if (!existing.contains(listener)) {
					sb.append("\n").append(listener);
				}
				if (!existing.contains(interceptor)) {
					sb.append("\n").append(interceptor);
				}
				sb.append("\n");
				Files.writeString(serviceFile, sb.toString());
			} else {
				Files.writeString(serviceFile, listener + "\n" + interceptor + "\n");
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to write TestNG listener service file", e);
		}
	}

	// ── Artifact resolution ───────────────────────────────────────────

	protected Path resolveArtifact(String artifactId) throws MojoExecutionException {
		String repoPath = null;
		if (session != null && session.getLocalRepository() != null) {
			repoPath = session.getLocalRepository().getBasedir();
		}
		if (repoPath == null || repoPath.isBlank()) {
			repoPath = System.getProperty("user.home") + "/.m2/repository";
		}
		Path localRepo = Path.of(repoPath);

		String pluginVersion = null;
		for (Plugin p : project.getBuildPlugins()) {
			if ("test-order-maven-plugin".equals(p.getArtifactId()) && "me.bechberger".equals(p.getGroupId())) {
				pluginVersion = p.getVersion();
				break;
			}
		}

		for (String version : new String[] { pluginVersion, project.getVersion() }) {
			if (version == null)
				continue;
			Path baseDir = localRepo.resolve("me/bechberger").resolve(artifactId).resolve(version);
			Path match = findBestArtifactJar(baseDir, artifactId, version);
			if (match != null)
				return match;
		}

		// Fallback for standalone/external projects: pick the most recently installed
		// local version.
		Path artifactRepoDir = localRepo.resolve("me/bechberger").resolve(artifactId);
		if (Files.isDirectory(artifactRepoDir)) {
			try (Stream<Path> versions = Files.list(artifactRepoDir)) {
				Path newest = versions.filter(Files::isDirectory).map(dir -> {
					String version = dir.getFileName().toString();
					return findBestArtifactJar(dir, artifactId, version);
				}).filter(p -> p != null && Files.exists(p)).max((a, b) -> {
					try {
						return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
					} catch (IOException e) {
						return 0;
					}
				}).orElse(null);
				if (newest != null) {
					getLog().debug("[test-order] Resolved " + artifactId + " via local repo fallback: " + newest);
					return newest;
				}
			} catch (IOException ignored) {
				// Handled by final error path below.
			}
		}

		Path reactorFatJar = project.getBasedir().toPath().getParent().resolve(artifactId).resolve("target")
				.resolve(artifactId + "-jar-with-dependencies.jar");
		if (Files.exists(reactorFatJar))
			return reactorFatJar;

		Path reactorPath = project.getBasedir().toPath().getParent().resolve(artifactId).resolve("target")
				.resolve(artifactId + ".jar");
		if (Files.exists(reactorPath))
			return reactorPath;

		throw new MojoExecutionException("Cannot find " + artifactId + " jar. Build the parent project first.");
	}

	private Path findBestArtifactJar(Path baseDir, String artifactId, String version) {
		Path artifactPath = baseDir.resolve(artifactId + "-" + version + ".jar");
		if (Files.exists(artifactPath))
			return artifactPath;
		Path fatJarPath = baseDir.resolve(artifactId + "-" + version + "-jar-with-dependencies.jar");
		if (Files.exists(fatJarPath))
			return fatJarPath;
		Path shadedJarPath = baseDir.resolve(artifactId + "-" + version + "-all.jar");
		if (Files.exists(shadedJarPath))
			return shadedJarPath;
		return null;
	}

	/**
	 * Eagerly triggers LZ4 class loading while suppressing stderr output. The
	 * bundled lz4-java library prints warnings about {@code sun.misc.Unsafe} access
	 * on first use, which clutters the Maven output on every invocation.
	 * <p>
	 * Uses a thread-local approach: captures and filters the warning output rather
	 * than globally redirecting System.err (which is unsafe in multi-threaded
	 * builds).
	 */
	private static volatile boolean lz4Initialized;

	private void suppressLz4UnsafeWarnings() {
		if (lz4Initialized) {
			return;
		}
		synchronized (AbstractTestOrderMojo.class) {
			if (lz4Initialized) {
				return;
			}
			java.io.PrintStream origErr = System.err;
			try {
				// Brief, synchronized suppression — only runs once per JVM
				System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
				Class.forName("net.jpountz.lz4.LZ4Factory");
			} catch (ClassNotFoundException ignored) {
				// LZ4 not on classpath — nothing to suppress
			} finally {
				System.setErr(origErr);
				lz4Initialized = true;
			}
		}
	}
}
