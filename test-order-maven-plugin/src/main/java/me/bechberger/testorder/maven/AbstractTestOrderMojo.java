package me.bechberger.testorder.maven;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import me.bechberger.testorder.agent.Agent;
import me.bechberger.testorder.agent.OfflineInstrumentor;
import me.bechberger.testorder.agent.runtime.ClassIdMapping;
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

	/**
	 * Active IndexCollectorServer instances keyed by index file path. Stored
	 * statically so collectors survive across Mojo instances within the same Maven
	 * JVM and can be explicitly stopped when tests complete or when a new collector
	 * is needed.
	 */
	static final ConcurrentHashMap<Path, me.bechberger.testorder.IndexCollectorServer> activeCollectors = new ConcurrentHashMap<>();

	/**
	 * Pending partial-run aggregation entries: maps build session ID to the
	 * corresponding (pendingRunsDir, stateFile) pair. Populated when the plugin
	 * passes a build ID to forked JVMs; consumed by
	 * {@link CollectorLifecycleParticipant} after the Maven session ends.
	 */
	record PendingAggregation(Path pendingRunsDir, Path stateFile) {
	}

	static final ConcurrentHashMap<String, PendingAggregation> pendingAggregations = new ConcurrentHashMap<>();

	/**
	 * Backup directories of class trees that were offline-instrumented during this
	 * Maven session. Drained by {@link CollectorLifecycleParticipant} at session
	 * end so subsequent {@code mvn} invocations (without {@code clean}) see
	 * pristine bytecode — important for projects that re-process compiled classes
	 * via annotation processors (e.g. log4j2's {@code generate-plugin-descriptors}
	 * pass).
	 */
	static final java.util.Set<Path> pendingRestores = java.util.concurrent.ConcurrentHashMap.newKeySet();

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	protected MavenProject project;

	@Parameter(defaultValue = "${session}", readonly = true, required = true)
	protected MavenSession session;

	protected ReactorContext ctx;

	// Cache for resolved artifacts to avoid repeated filesystem lookups
	private final Map<String, Path> resolvedArtifactCache = new java.util.HashMap<>();

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
	 * Path to the hash file for compiled bytecode. Stores per-class and per-method
	 * SHA-256 fingerprints so source-invisible bytecode changes (annotation
	 * processors, generated code, dependency-version bumps) are detected.
	 */
	@Parameter(property = MavenPluginConfigKeys.BYTECODE_HASH_FILE, defaultValue = "${project.basedir}/.test-order/bytecode-hashes.lz4")
	protected String bytecodeHashFile;

	/**
	 * Enable bytecode-level change detection at order time. When enabled, compiled
	 * .class files are hashed and compared against a persisted snapshot to catch
	 * source-invisible changes (Lombok regeneration, annotation processors, etc.).
	 */
	@Parameter(property = MavenPluginConfigKeys.BYTECODE_CHANGE_DETECTION_ENABLED, defaultValue = "true")
	protected boolean bytecodeChangeDetectionEnabled = true;

	/**
	 * Augment the recorded dependency map with edges derived from test bytecode.
	 * Augment-only: never removes existing edges (which may be from reflection or
	 * dynamic loading), only adds missing test → prod-class edges.
	 */
	@Parameter(property = MavenPluginConfigKeys.BYTECODE_AUGMENT_DEPENDENCY_MAP_ENABLED, defaultValue = "true")
	protected boolean bytecodeAugmentDependencyMapEnabled = true;

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
	 * Change detection mode:
	 * <ul>
	 * <li><b>uncommitted</b> (default) — detects staged, unstaged, and untracked
	 * changes in working tree</li>
	 * <li><b>since-last-run</b> — compares source hashes against the last
	 * test-order run</li>
	 * <li><b>since-last-commit</b> — detects changes since the last git commit
	 * (HEAD~1..HEAD + uncommitted)</li>
	 * <li><b>explicit</b> — uses only the classes specified in
	 * changedClasses/changedTestClasses</li>
	 * <li><b>auto</b> — uses since-last-run if hash snapshot exists, otherwise
	 * since-last-commit</li>
	 * </ul>
	 */
	@Parameter(property = MavenPluginConfigKeys.CHANGE_MODE, defaultValue = "uncommitted")
	protected String changeMode;

	/**
	 * When true, only instrument classes that the static call graph identifies as
	 * potentially affected by current changes — that is, changed classes plus their
	 * transitive callees (up to 4 hops). Requires source/class directories to be
	 * set. Default false.
	 */
	@Parameter(property = MavenPluginConfigKeys.SELECTIVE_LEARN, defaultValue = "false")
	protected boolean selectiveLearn;

	/**
	 * When {@code true} (default {@code false}), the auto goal attaches the
	 * learn-mode agent on every run that would otherwise just be ordered, so the
	 * dependency index is incrementally refined over time without needing an
	 * explicit {@code learn} invocation. Combine with {@link #selectiveLearn} to
	 * limit instrumentation to changed classes.
	 */
	@Parameter(property = MavenPluginConfigKeys.ALWAYS_LEARN, defaultValue = "false")
	protected boolean alwaysLearn;

	/**
	 * Comma-separated fully-qualified class names of <b>production source</b>
	 * classes that changed (for explicit change mode). These are used for
	 * dependency-overlap scoring: tests whose dependencies include one of these
	 * classes receive a higher priority score.
	 * <p>
	 * Example:
	 * {@code -Dtestorder.changed.classes=com.example.service.UserService,com.example.repo.UserRepo}
	 */
	@Parameter(property = MavenPluginConfigKeys.CHANGED_CLASSES)
	protected String changedClasses;

	/**
	 * Comma-separated fully-qualified class names of <b>test source</b> files that
	 * changed (for explicit change mode). These receive a "changed test" bonus
	 * score and are always included in selection results.
	 * <p>
	 * Example: {@code -Dtestorder.changed.test.classes=com.example.UserServiceTest}
	 */
	@Parameter(property = MavenPluginConfigKeys.CHANGED_TEST_CLASSES)
	protected String changedTestClasses;

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

	/**
	 * Enable static call-graph analysis at order time. When enabled, the set of
	 * changed members is expanded transitively via bytecode-level caller lookup so
	 * that tests which indirectly call a changed method are not missed.
	 */
	@Parameter(property = "testorder.staticAnalysis.enabled", defaultValue = "true")
	protected boolean staticAnalysisEnabled = true;

	/**
	 * Maximum BFS depth for static call-graph expansion. Depth 1 = direct callers
	 * only; depth 2 = callers of callers, etc.
	 */
	@Parameter(property = "testorder.staticAnalysis.depth", defaultValue = "2")
	protected int staticAnalysisDepth = 2;

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

	/** Kill-rate bonus weight (0 = disabled; requires analyze-mutations data) */
	@Parameter(property = MavenPluginConfigKeys.SCORE_KILL_RATE_BONUS)
	protected Integer scoreKillRateBonus;

	// ── Lifecycle helpers ─────────────────────────────────────────────

	protected void initContext() throws MojoExecutionException {
		if (skip) {
			// R16-5: If this goal was explicitly invoked on the CLI (not lifecycle-bound),
			// ignore testorder.skip so that diagnostic/info goals still work.
			if (isExplicitlyInvokedOnCli()) {
				getLog().info(
						"[test-order] testorder.skip=true is set, but this goal was explicitly invoked on CLI — proceeding.");
				skip = false;
			} else {
				getLog().info("[test-order] Skipping — testorder.skip=true");
				return;
			}
		}
		removeLegacyGeneratedOrdererFiles();
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
		validateOutputDirectoriesWritable();
		PersistenceSupport.cleanupStaleTemps(ctx.resolveBaseDir());
		// Always process any fallback payload file written by IndexCollectorServer
		// when the Maven JVM shut down before stopAndMerge could complete. This
		// merges previously-unprocessed test-dependency data into the index so that
		// show, select, and diagnose mojos see a complete, up-to-date index even when
		// they are invoked after a learn run that left a fallback file behind.
		processPendingFallback();
	}

	/**
	 * Processes the fallback payload file (if any) written by
	 * {@link me.bechberger.testorder.IndexCollectorServer} when the Maven JVM shut
	 * down before {@code stopAndMerge} completed. Called unconditionally in
	 * {@link #initContext()} so that every mojo sees a complete index.
	 */
	private void processPendingFallback() {
		Path idxPath = ctx.resolveIndexFile(indexFile);
		try {
			if (me.bechberger.testorder.IndexCollectorServer.processFallbackFile(idxPath)) {
				getLog().info(
						"[test-order] Processed collector fallback payloads from previous learn run → " + idxPath);
			}
		} catch (IOException e) {
			getLog().warn("[test-order] Failed to process collector fallback payloads: " + e.getMessage());
		}
	}

	/**
	 * Validates all parameters are valid and sensible.
	 */
	protected void validateParameters() throws MojoExecutionException {
		ParameterValidator validator = new ParameterValidator(getLog());

		// Validate changeMode
		validator.validateChangeMode(changeMode);

		// Validate explicit mode requirements
		validator.validateExplicitModeRequirements(changeMode, changedClasses, changedTestClasses);

		// Warn about semicolons in changedClasses (semicolons are accepted as a
		// fallback separator, but commas are the documented format)
		validator.warnChangedClassesFormat(changedClasses);

		// Validate file paths if provided — weightsFile is an output of 'optimize' that
		// may not exist yet, so warn (don't fail) if missing.
		if (weightsFile != null && !weightsFile.isBlank()) {
			java.nio.file.Path wPath = java.nio.file.Paths.get(weightsFile);
			if (!java.nio.file.Files.exists(wPath)) {
				getLog().warn("[test-order] weightsFile not found: " + wPath.toAbsolutePath()
						+ " — using default weights. Run 'mvn test-order:optimize' to generate it,"
						+ " or check the path specified by -Dtestorder.weights.file.");
			} else if (!java.nio.file.Files.isReadable(wPath)) {
				throw new MojoExecutionException("[test-order] weightsFile is not readable: " + wPath.toAbsolutePath());
			}
		}
	}

	/**
	 * Warn about unknown testorder.* properties (likely typos) with Levenshtein
	 * suggestions. Checks both user properties (-D on CLI) and system properties.
	 * Deduplicates per session to avoid repeating warnings across mojo invocations.
	 */
	private static final Set<String> WARNED_PROPERTIES = java.util.Collections
			.synchronizedSet(new java.util.HashSet<>());

	private void warnUnknownProperties() {
		if (session == null) {
			return;
		}
		if (session.getUserProperties() != null) {
			for (String info : MavenPluginConfigKeys.findAliasedProperties(session.getUserProperties())) {
				if (WARNED_PROPERTIES.add(info)) {
					getLog().info("[test-order] " + info);
				}
			}
			for (String warning : MavenPluginConfigKeys.findUnknownProperties(session.getUserProperties())) {
				if (WARNED_PROPERTIES.add(warning)) {
					getLog().warn("[test-order] " + warning);
				}
			}
		}
		if (session.getSystemProperties() != null) {
			for (String warning : MavenPluginConfigKeys.findUnknownProperties(session.getSystemProperties())) {
				// Only warn if not already covered by user properties
				if (session.getUserProperties() == null || !session.getUserProperties().stringPropertyNames().stream()
						.anyMatch(k -> warning.contains("'" + k + "'"))) {
					if (WARNED_PROPERTIES.add(warning)) {
						getLog().warn("[test-order] " + warning);
					}
				}
			}
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

		// CamelCase alias fallbacks — apply alias value when canonical key is absent.
		// These match the ALIASES map in MavenPluginConfigKeys; Maven parameter
		// injection uses the @Parameter(property=...) key exactly, so an alias passed
		// on the command line never reaches the field unless we copy it here.
		applyLegacyFallback(props, MavenPluginConfigKeys.CHANGED_CLASSES, "testorder.changedClasses",
				v -> changedClasses = v);

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

	/** Resolves the ML history directory (inside .test-order/). */
	protected Path resolveMLHistoryDir() {
		return ctx.resolveBaseDir().resolve("ml-history");
	}

	/**
	 * Returns true if the currently executing goal was explicitly invoked on the
	 * Maven CLI (e.g., {@code mvn test-order:diagnose}). Lifecycle-bound goals
	 * triggered by a phase (e.g., {@code mvn test}) return false. (R16-5)
	 */
	private boolean isExplicitlyInvokedOnCli() {
		if (session == null || session.getGoals() == null) {
			return false;
		}
		for (String goal : session.getGoals()) {
			if (goal.contains("test-order:") || goal.contains("test-order-maven-plugin:")) {
				return true;
			}
		}
		return false;
	}

	protected boolean skipIfNotExplicitlySelectedReactorProject(String goalName) {
		if (session == null || project == null || session.getProjects() == null || session.getProjects().size() <= 1
				|| session.getRequest() == null || session.getRequest().getSelectedProjects() == null) {
			return false;
		}
		List<String> selectors = session.getRequest().getSelectedProjects().stream()
				.filter(s -> s != null && !s.isBlank() && !s.startsWith("!")).toList();
		if (selectors.isEmpty()) {
			return false;
		}
		Path reactorRoot = session.getTopLevelProject() != null
				? session.getTopLevelProject().getBasedir().toPath()
				: project.getBasedir().toPath();
		if (matchesSelectedProject(selectors, project, reactorRoot)) {
			return false;
		}
		getLog().info("[test-order] Skipping " + goalName + " for reactor dependency module " + project.getArtifactId()
				+ " — it was included by -am but not selected explicitly.");
		return true;
	}

	static boolean matchesSelectedProject(List<String> selectors, MavenProject project, Path reactorRoot) {
		return selectors.stream().anyMatch(selector -> matchesSelectedProject(selector, project, reactorRoot));
	}

	private static boolean matchesSelectedProject(String selector, MavenProject project, Path reactorRoot) {
		String trimmed = selector.trim();
		String artifactId = project.getArtifactId();
		String groupId = project.getGroupId();
		String ga = (groupId == null || groupId.isBlank()) ? artifactId : groupId + ":" + artifactId;
		Path moduleDir = project.getBasedir().toPath().toAbsolutePath().normalize();
		Path normalizedRoot = reactorRoot.toAbsolutePath().normalize();
		String relative = normalizedRoot.relativize(moduleDir).toString().replace('\\', '/');
		String absolute = moduleDir.toString().replace('\\', '/');
		String normalizedSelector = trimmed.replace('\\', '/');

		return normalizedSelector.equals(artifactId) || normalizedSelector.equals(":" + artifactId)
				|| normalizedSelector.equals(ga) || normalizedSelector.equals(relative)
				|| normalizedSelector.equals("./" + relative) || normalizedSelector.equals(absolute)
				|| (relative.isEmpty() && (normalizedSelector.equals(".") || normalizedSelector.equals("")));
	}

	protected boolean ensureReadableIndex(Path idxPath, String goalName, boolean allowLearnFallback)
			throws MojoExecutionException {
		if (!Files.exists(idxPath)) {
			return false;
		}
		try {
			DependencyMap.load(idxPath);
			return false;
		} catch (IOException e) {
			Path depsDirPath = ctx.resolveDepsDir(depsDir);
			if (Files.isDirectory(depsDirPath) && hasDepsFiles(depsDirPath)) {
				getLog().warn("[test-order] Dependency index is unreadable at " + idxPath
						+ " — re-aggregating available .deps files.");
				autoAggregate(depsDirPath, idxPath);
				return false;
			}
			if (allowLearnFallback) {
				try {
					Files.deleteIfExists(idxPath);
				} catch (IOException deleteError) {
					throw new MojoExecutionException("[test-order] Dependency index is unreadable at " + idxPath
							+ " and could not be deleted for recovery.", deleteError);
				}
				getLog().warn("[test-order] Dependency index is unreadable at " + idxPath
						+ " — deleting it and falling back to learn mode.");
				return true;
			}
			throw new MojoExecutionException("[test-order] Dependency index is unreadable at " + idxPath
					+ ". Run 'mvn test-order:clean' or 'mvn test -Dtestorder.mode=learn' to regenerate it."
					+ "\n  For more details: mvn test-order:diagnose", e);
		}
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

		// In multi-module builds, include source roots from all reactor modules
		// so that changeComplexity can find source files for cross-module classes
		if (ctx.isMultiModule() && session != null && session.getProjects() != null) {
			for (MavenProject p : session.getProjects()) {
				List<String> roots = p.getCompileSourceRoots();
				if (roots != null) {
					for (String root : roots) {
						if (root == null)
							continue;
						Path rootPath = Path.of(root);
						if (Files.isDirectory(rootPath)) {
							additionalSourceRoots.add(rootPath);
						}
					}
				}
			}
		}

		return PluginContext.builder().projectRoot(project.getBasedir().toPath().toAbsolutePath())
				.repoRoot(ctx.gitRoot().toAbsolutePath()).sourceRoot(resolvedSourceRoot)
				.testSourceRoot(resolvedTestSourceRoot).additionalSourceRoots(additionalSourceRoots)
				.testClassesDir(toPathOrNull(project.getBuild().getTestOutputDirectory()))
				.classesDir(toPathOrNull(project.getBuild().getOutputDirectory()))
				.indexFile(ctx.resolveIndexFile(indexFile)).stateFile(ctx.resolveStateFile(stateFile))
				.depsDir(ctx.resolveDepsDir(depsDir)).hashFile(ctx.resolveHashFile(hashFile))
				.testHashFile(ctx.resolveTestHashFile(testHashFile))
				.methodHashFile(ctx.resolveMethodHashFile(methodHashFile))
				.bytecodeHashFile(ctx.resolveBytecodeHashFile(bytecodeHashFile))
				.bytecodeChangeDetectionEnabled(bytecodeChangeDetectionEnabled)
				.bytecodeAugmentDependencyMapEnabled(bytecodeAugmentDependencyMapEnabled).changeMode(changeMode)
				.changedClasses(changedClasses).changedTestClasses(changedTestClasses)
				.weightsFile(weightsFile != null && !weightsFile.isBlank() ? Path.of(weightsFile) : null)
				.scoreOverrides(buildScoreOverrides()).methodOrderingEnabled(methodOrderingEnabled)
				.springContextGrouping(springContextGrouping).staticAnalysisEnabled(staticAnalysisEnabled)
				.staticAnalysisDepth(staticAnalysisDepth).groupId(project.getGroupId())
				.currentModuleId(computeCurrentModuleId())
				.verboseFile(verboseFile != null && !verboseFile.isBlank() ? Path.of(verboseFile) : null)
				.dependencyFingerprintSupplier(() -> computeMavenDependencyFingerprint())
				.projectName(project.getArtifactId()).selectiveLearn(selectiveLearn).alwaysLearn(alwaysLearn)
				.log(pluginLog());
	}

	protected PluginContext buildPluginContext() {
		return buildPluginContextBuilder().build();
	}

	/**
	 * Computes the canonical module id for this project, matching
	 * {@code ReactorContext.moduleId()}: {@code groupId-artifactId} (dash
	 * separator). Used to filter the shared dependency index to only this module's
	 * tests.
	 */
	protected String computeCurrentModuleId() {
		return ModuleIds.of(project);
	}

	private static Path toPathOrNull(String s) {
		return (s == null || s.isBlank()) ? null : Path.of(s);
	}

	/**
	 * Writes a {@code module.id} sidecar file in the given deps directory so the
	 * offline learn aggregation path can stamp every test class found there with
	 * this module's id. The online
	 * {@link me.bechberger.testorder.IndexCollectorServer} path stamps via the
	 * v3/v4 wire protocol; this sidecar covers projects that write {@code .deps}
	 * files instead.
	 */
	protected void writeModuleIdSidecar(Path depsDirPath) {
		String mid = computeCurrentModuleId();
		if (mid == null || mid.isEmpty() || depsDirPath == null) {
			return;
		}
		try {
			Files.createDirectories(depsDirPath);
			Files.writeString(depsDirPath.resolve("module.id"), mid, java.nio.charset.StandardCharsets.UTF_8);
		} catch (IOException e) {
			getLog().debug("[test-order] Could not write module.id sidecar in " + depsDirPath + ": " + e.getMessage());
		}
	}

	/**
	 * Computes a dependency fingerprint from the project's resolved artifacts.
	 * Detects SNAPSHOT rebuilds, version bumps, and transitive changes. In
	 * multi-module mode, uses build-file (pom.xml) content hashing across all
	 * reactor modules to produce a stable fingerprint.
	 */
	private String computeMavenDependencyFingerprint() {
		if (ctx.isMultiModule() && session != null && session.getProjects() != null) {
			java.security.MessageDigest digest;
			try {
				digest = java.security.MessageDigest.getInstance("SHA-256");
			} catch (java.security.NoSuchAlgorithmException e) {
				return null;
			}
			boolean anyFile = false;
			for (org.apache.maven.project.MavenProject p : session.getProjects()) {
				java.nio.file.Path pomFile = p.getFile() != null ? p.getFile().toPath() : null;
				if (pomFile != null && java.nio.file.Files.isRegularFile(pomFile)) {
					try {
						byte[] content = java.nio.file.Files.readAllBytes(pomFile);
						digest.update(
								pomFile.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
						digest.update(content);
						anyFile = true;
					} catch (java.io.IOException ignored) {
					}
				}
			}
			return anyFile ? java.util.HexFormat.of().formatHex(digest.digest()) : null;
		}
		var artifacts = project.getArtifacts();
		if (artifacts == null || artifacts.isEmpty()) {
			// Fallback to build-file fingerprinting
			return me.bechberger.testorder.ops.BuildFileFingerprint
					.computeFromBuildFiles(project.getBasedir().toPath());
		}
		var classpathEntries = artifacts.stream().filter(a -> a.getFile() != null).map(a -> a.getFile().toPath())
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

	/**
	 * Validates that common output directories (depsDir parent, indexFile parent)
	 * are writable or can be created.
	 */
	private void validateOutputDirectoriesWritable() throws MojoExecutionException {
		// Validate depsDir parent
		Path depsDirPath = ctx.resolveDepsDir(depsDir);
		validateDirectoryWritable(depsDirPath.getParent(), "depsDir parent");
		// Validate index file parent
		Path idxParent = ctx.resolveIndexFile(indexFile).getParent();
		validateDirectoryWritable(idxParent, "indexFile parent");
	}

	private void validateDirectoryWritable(Path dir, String label) throws MojoExecutionException {
		if (dir == null)
			return;
		if (Files.exists(dir) && !Files.isWritable(dir)) {
			String perms = "";
			try {
				perms = java.nio.file.Files.getPosixFilePermissions(dir).toString();
			} catch (UnsupportedOperationException | IOException ignored) {
			}
			throw new MojoExecutionException("[test-order] " + label + " directory is not writable: " + dir
					+ (perms.isEmpty() ? "" : " (permissions: " + perms + ")"));
		}
	}

	// ── Source root resolution ────────────────────────────────────────

	/**
	 * Resolves the main source root. Priority: explicit config → Maven model →
	 * fallback to src/main/java.
	 */
	protected Path resolveSourceRoot() {
		if (sourceRoot != null && !sourceRoot.isBlank()) {
			Path p = Path.of(sourceRoot);
			return p.isAbsolute() ? p : project.getBasedir().toPath().resolve(p).toAbsolutePath();
		}
		List<String> roots = project.getCompileSourceRoots();
		if (roots != null && !roots.isEmpty())
			return Path.of(roots.get(0));
		Path fallback = project.getBasedir().toPath().resolve("src/main/java");
		if (!Files.isDirectory(fallback)) {
			getLog().info("[test-order] Source root '" + fallback
					+ "' does not exist — depOverlap scoring will be disabled for this module.");
		}
		return fallback;
	}

	/**
	 * Resolves the test source root. Priority: explicit config → Maven model →
	 * fallback to src/test/java.
	 */
	protected Path resolveTestSourceRoot() {
		if (testSourceRoot != null && !testSourceRoot.isBlank()) {
			Path p = Path.of(testSourceRoot);
			return p.isAbsolute() ? p : project.getBasedir().toPath().resolve(p).toAbsolutePath();
		}
		List<String> roots = project.getTestCompileSourceRoots();
		if (roots != null && !roots.isEmpty())
			return Path.of(roots.get(0));
		return project.getBasedir().toPath().resolve("src/test/java");
	}

	protected List<Path> resolveSourceRoots() {
		LinkedHashSet<Path> roots = new LinkedHashSet<>();
		roots.add(resolveSourceRoot());
		Path kotlinRoot = project.getBasedir().toPath().toAbsolutePath().resolve("src/main/kotlin");
		if (Files.isDirectory(kotlinRoot)) {
			roots.add(kotlinRoot);
		}
		return roots.stream().filter(java.util.Objects::nonNull).filter(Files::isDirectory).toList();
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
		if (changedClasses != null && !changedClasses.isBlank() && !"explicit".equalsIgnoreCase(changeMode)) {
			getLog().warn("[test-order] testorder.changed.classes is set but changeMode='" + changeMode
					+ "' — explicit class list overrides git/hash-based detection entirely."
					+ " To make this explicit, add -Dtestorder.changeMode=explicit");
		}
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
		if (changedTestClasses != null && !changedTestClasses.isBlank()) {
			Set<String> explicit = new LinkedHashSet<>();
			for (String cls : changedTestClasses.split(",")) {
				String trimmed = cls.trim();
				if (!trimmed.isEmpty()) {
					explicit.add(trimmed);
				}
			}
			if ("explicit".equalsIgnoreCase(changeMode)) {
				own = explicit;
			} else {
				Set<String> mergedOwn = new LinkedHashSet<>(own);
				mergedOwn.addAll(explicit);
				own = mergedOwn;
			}
		}
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

	/**
	 * Start an IndexCollectorServer for the given index file, stopping any
	 * previously running collector for the same path. Returns the started
	 * collector, or null on failure.
	 */
	protected me.bechberger.testorder.IndexCollectorServer startCollector(Path indexFilePath) {
		// Stop any existing collector for this index (e.g. from a previous module or
		// re-run)
		stopCollectorForIndex(indexFilePath);
		try {
			// Always pass the mapping file path so the collector can lazily load it
			// after deferred offline instrumentation completes (CLI goals run before
			// compile).
			Path targetDir = Path.of(project.getBuild().getDirectory());
			Path mappingFile = targetDir.resolve(".test-order").resolve("class-id-map.bin");

			me.bechberger.testorder.IndexCollectorServer collector = new me.bechberger.testorder.IndexCollectorServer(
					indexFilePath, mappingFile);
			activeCollectors.put(indexFilePath.toAbsolutePath().normalize(), collector);
			registerCollectorInSession(collector.getPort(), indexFilePath);
			getLog().info("[test-order] IndexCollectorServer started on port " + collector.getPort()
					+ (java.nio.file.Files.exists(mappingFile)
							? " (v2 binary protocol enabled)"
							: " (v2 pending mapping)"));
			return collector;
		} catch (java.io.IOException e) {
			getLog().warn("[test-order] Failed to start IndexCollectorServer, falling back to .deps files: "
					+ e.getMessage());
			return null;
		}
	}

	static final String SESSION_ACTIVE_COLLECTORS_KEY = "testorder.activeCollectors";

	/**
	 * Stores a running collector's port and index path in Maven session user
	 * properties so the lifecycle participant (extension classloader) can drain it
	 * at session end — the plugin and extension classloader realms each have their
	 * own static field, so the static map cannot be shared directly.
	 */
	void registerCollectorInSession(int port, Path indexFilePath) {
		if (session == null) {
			return;
		}
		java.util.Properties props = session.getUserProperties();
		String entry = port + ":" + indexFilePath.toAbsolutePath().normalize();
		synchronized (props) {
			String existing = props.getProperty(SESSION_ACTIVE_COLLECTORS_KEY, "");
			props.setProperty(SESSION_ACTIVE_COLLECTORS_KEY, existing.isEmpty() ? entry : existing + "|" + entry);
		}
	}

	/**
	 * Stop and merge the collector for the given index file path, if one is
	 * running. Called before aggregation or when starting a new collector for the
	 * same path.
	 *
	 * @return the number of test classes merged, or 0 if no collector was running
	 */
	protected int stopCollectorForIndex(Path indexFilePath) {
		me.bechberger.testorder.IndexCollectorServer collector = activeCollectors
				.remove(indexFilePath.toAbsolutePath().normalize());
		if (collector != null) {
			int merged = collector.stopAndMerge();
			if (merged > 0) {
				getLog().info("[test-order] IndexCollectorServer merged " + merged + " test classes via socket");
			}
			return merged;
		}
		return 0;
	}

	protected void autoAggregate(Path depsDirPath, Path idxPath) throws MojoExecutionException {
		// Stop any running socket collector first — its data is merged directly into
		// the index
		int collectorMerged = stopCollectorForIndex(idxPath);

		// Process any fallback payload file from a previous run's failed shutdown hook
		try {
			if (me.bechberger.testorder.IndexCollectorServer.processFallbackFile(idxPath)) {
				getLog().info("[test-order] Processed fallback collector payloads from previous run");
				collectorMerged++; // count as having existing data
			}
		} catch (IOException e) {
			getLog().warn("[test-order] Failed to process fallback payloads: " + e.getMessage());
		}

		try {
			DependencyMap map = DependencyMap.aggregate(depsDirPath);
			if (map.size() == 0) {
				if (collectorMerged > 0) {
					getLog().info("[test-order] Socket collector already merged " + collectorMerged
							+ " test classes — no additional .deps files to aggregate.");
				} else {
					getLog().info("[test-order] No test dependencies found in " + depsDirPath
							+ " — skipping index creation.");
				}
				return;
			}
			// If the collector already wrote data to the index, merge .deps into it
			// rather than overwriting (handles mixed socket + file fallback scenario)
			if (collectorMerged > 0 && Files.exists(idxPath)) {
				DependencyMap existing = DependencyMap.load(idxPath);
				existing.mergeWith(map);
				map = existing;
			}
			final DependencyMap finalMap = map;
			PersistenceSupport.withFileLock(idxPath, () -> {
				finalMap.save(idxPath);
				return null;
			});
			getLog().info("[test-order] Auto-aggregated " + finalMap.size() + " test classes → " + idxPath);
			warnIfNoDeps(finalMap);
		} catch (IOException e) {
			throw new MojoExecutionException(
					"Failed to auto-aggregate deps" + "\n  For more details: mvn test-order:diagnose", e);
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
		// Always check for fallback payload file written by IndexCollectorServer
		// shutdown hook — this is the normal path for offline learn mode.
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
		if (Files.exists(idxPath)) {
			return;
		}

		Path depsDirPath = ctx.resolveDepsDir(depsDir);
		if (Files.isDirectory(depsDirPath) && hasDepsFiles(depsDirPath)) {
			autoAggregate(depsDirPath, idxPath);
			if (!Files.exists(idxPath)) {
				throw new MojoExecutionException("Dependency .deps files exist in " + depsDirPath
						+ " but contain no test dependencies — the index could not be created. "
						+ "Check your instrumentation filter: -D" + MavenPluginConfigKeys.INCLUDE_PACKAGES
						+ "=<package>" + "\n  For more details: mvn test-order:diagnose");
			}
		} else {
			throw new MojoExecutionException("No dependency index at " + idxPath + " and no .deps files found in "
					+ depsDirPath + ". Run learn mode first: mvn test -Dtestorder.mode=learn"
					+ "\n  For more details: mvn test-order:diagnose");
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
				scoreStaticFieldBonus, scoreCoverageBonus, scoreKillRateBonus);
	}

	/**
	 * Builds a map of score overrides from the user-specified score parameters.
	 * Returns null if no overrides are set.
	 */
	protected Map<String, Integer> buildScoreOverrides() {
		return me.bechberger.testorder.ops.WeightResolverOperation.buildScoreOverrides(scoreNewTest, scoreChangedTest,
				scoreMaxFailure, scoreSpeed, scoreSpeedPenalty, scoreDepOverlap, scoreChangeComplexity,
				scoreStaticFieldBonus, scoreCoverageBonus, scoreKillRateBonus);
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
		Path runtimeDir = runtimeConfigDir();
		// Remove stale testorder config files from target/test-classes/ written by
		// old plugin versions. target/test-classes/ is earlier on the Surefire
		// classpath than target/test-order-runtime/, so if a stale file exists there
		// it silently shadows the fresh config we write to runtimeDir.
		removeStaleTestClassesConfig();
		// Ensure test-order-junit (PriorityClassOrderer + TelemetryListener) is on the
		// test classpath
		injectTestClasspath(resolveOrdererClasspath());
		injectTestClasspath(runtimeDir);
		injectOfflineRuntimeJarIfPresent();
		ensureListenerServiceFile(runtimeDir);
		if (isTestNGOnTestClasspath()) {
			ensureTestNGListenerServiceFile(runtimeDir);
		}

		// L6: Warn if user's junit-platform.properties contains orderer config that
		// we'll overwrite
		warnConflictingJUnitPlatformProperties();

		Path resolvedSourceRoot = resolveSourceRoot();

		// Build framework-agnostic config map via shared operation
		Map<String, String> configMap = OrdererConfigOperation.buildConfig(
				new OrdererConfigOperation.OrdererInput(ctx.resolveIndexFile(indexFile).toAbsolutePath().toString(),
						ctx.resolveStateFile(stateFile).toAbsolutePath().toString(), weightsFile, changed, changedTests,
						changedMethods, scoreOverrides, methodOrderingEnabled, springContextGrouping,
						project.getBasedir().toPath().toAbsolutePath().toString(),
						resolvedSourceRoot.toAbsolutePath().toString(), changeMode));

		// Add build session ID so all forked JVMs write partial records to the same
		// pending-runs dir, enabling post-run aggregation into one RunRecord.
		String buildId = getOrCreateBuildId();
		Path pendingRunsDir = ctx.resolveBaseDir().resolve("pending-runs");
		configMap = new java.util.LinkedHashMap<>(configMap);
		configMap.put(me.bechberger.testorder.TestOrderConfig.BUILD_ID, buildId);
		configMap.put(me.bechberger.testorder.TestOrderConfig.PENDING_RUNS_DIR,
				pendingRunsDir.toAbsolutePath().toString());

		try {
			Files.createDirectories(runtimeDir);
			Path propsFile = runtimeDir.resolve("junit-platform.properties");
			try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(propsFile))) {
				pw.println("junit.jupiter.testclass.order.default=me.bechberger.testorder.junit.PriorityClassOrderer");
			}

			Path configFile = runtimeDir.resolve("testorder-config.properties");
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
	 * Appends a single {@code key=value} line to the runtime
	 * {@code testorder-config.properties} file. Call after
	 * {@link #writeOrdererConfig} to inject extra properties that are not part of
	 * the standard config map.
	 */
	protected void appendRuntimeConfigProperty(String key, String value) throws MojoExecutionException {
		Path configFile = runtimeConfigDir().resolve("testorder-config.properties");
		if (!Files.exists(configFile))
			return;
		try {
			Files.writeString(configFile, key + "=" + escapePropertyValue(value) + "\n",
					java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to append runtime config property " + key, e);
		}
	}

	/**
	 * Appends a single {@code key=value} line to the runtime
	 * {@code junit-platform.properties} file.
	 */
	protected void appendJunitPlatformProperty(String key, String value) throws MojoExecutionException {
		Path propsFile = runtimeConfigDir().resolve("junit-platform.properties");
		if (!Files.exists(propsFile))
			return;
		try {
			Files.writeString(propsFile, key + "=" + escapePropertyValue(value) + "\n",
					java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to append junit-platform property " + key, e);
		}
	}

	/**
	 * Writes a pre-built config map to {@code target/test-classes}
	 * (junit-platform.properties + testorder-config.properties) and injects the
	 * test-order runtime classpath. Use this when the config map has already been
	 * computed by a shared workflow.
	 */
	protected void writeOrdererConfigFromMap(Map<String, String> configMap) throws MojoExecutionException {
		Path runtimeDir = runtimeConfigDir();
		removeStaleTestClassesConfig();
		injectTestClasspath(resolveOrdererClasspath());
		injectTestClasspath(runtimeDir);
		injectOfflineRuntimeJarIfPresent();
		ensureListenerServiceFile(runtimeDir);
		if (isTestNGOnTestClasspath()) {
			ensureTestNGListenerServiceFile(runtimeDir);
		}

		// Inject build session ID for per-fork partial record aggregation
		String buildId = getOrCreateBuildId();
		Path pendingRunsDir = ctx.resolveBaseDir().resolve("pending-runs");
		configMap = new java.util.LinkedHashMap<>(configMap);
		configMap.put(me.bechberger.testorder.TestOrderConfig.BUILD_ID, buildId);
		configMap.put(me.bechberger.testorder.TestOrderConfig.PENDING_RUNS_DIR,
				pendingRunsDir.toAbsolutePath().toString());

		try {
			Files.createDirectories(runtimeDir);
			Path propsFile = runtimeDir.resolve("junit-platform.properties");
			try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(propsFile))) {
				pw.println("junit.jupiter.testclass.order.default=me.bechberger.testorder.junit.PriorityClassOrderer");
			}
			Path configFile = runtimeDir.resolve("testorder-config.properties");
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
	 *            CLASS, METHOD, or MEMBER
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
		boolean hasCliArgLine = session != null && session.getUserProperties() != null
				&& session.getUserProperties().getProperty("argLine") != null;

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
		getLog().info("[test-order] Instrumentation packages: "
				+ (includePackages != null && !includePackages.isBlank() ? includePackages : "(all)"));

		Path agentJar = resolveArtifact("test-order-agent");

		// Pre-extract runtime jar once so forked JVMs skip per-fork extraction
		Path targetDir = Path.of(project.getBuild().getDirectory());
		Path runtimeJar = me.bechberger.testorder.AgentArgsBuilder.preExtractRuntimeJar(agentJar, targetDir);

		String agentArgs = me.bechberger.testorder.AgentArgsBuilder.buildArgs(ctx.resolveDepsDir(depsDir),
				instrumentationMode, includeIndexInArgs ? ctx.resolveIndexFile(indexFile) : null, includePackages,
				verboseFile, true, runtimeJar);
		writeModuleIdSidecar(ctx.resolveDepsDir(depsDir));

		String statPathStr = ctx.resolveStateFile(stateFile).toAbsolutePath().toString();
		String agentString = "\"-javaagent:" + agentJar.toAbsolutePath() + "=" + agentArgs + "\"";
		// Pass system properties via -D flags; using <systemPropertyVariables> XML
		// modifications to an already-planned MojoExecution are not picked up by
		// Surefire.
		String collectorPortProp = "";
		Path indexFilePath = ctx.resolveIndexFile(indexFile);
		me.bechberger.testorder.IndexCollectorServer collector = startCollector(indexFilePath);
		if (collector != null) {
			collectorPortProp = " -D" + me.bechberger.testorder.TestOrderConfig.COLLECTOR_PORT + "="
					+ collector.getPort();
		}
		String sysProps = " -D" + MavenPluginConfigKeys.LEARN + "=true" + " -D"
				+ MavenPluginConfigKeys.INSTRUMENTATION_MODE + "=" + instrumentationMode.toUpperCase() + " -D"
				+ MavenPluginConfigKeys.STATE_PATH + "=" + statPathStr + collectorPortProp;
		String mid = computeCurrentModuleId();
		if (mid != null && !mid.isEmpty()) {
			sysProps += " -Dtestorder.moduleId=" + mid;
		}
		String buildId = getOrCreateBuildId();
		Path pendingRunsDir = ctx.resolveBaseDir().resolve("pending-runs");
		sysProps += " -D" + me.bechberger.testorder.TestOrderConfig.BUILD_ID + "=" + buildId + " -D"
				+ me.bechberger.testorder.TestOrderConfig.PENDING_RUNS_DIR + "=" + pendingRunsDir.toAbsolutePath();

		// Selective learn: compute uncertain classes and pass to the agent so it only
		// instruments classes reachable from the current change set.
		if (selectiveLearn && java.nio.file.Files.exists(indexFilePath)) {
			try {
				Path classesDir = resolveClassesDir();
				if (classesDir != null) {
					Path changeRoot = ctx.gitRoot();
					Path hashFilePath = ctx.resolveHashFile(hashFile);
					me.bechberger.testorder.changes.ChangeDetector.Mode changeDetectorMode;
					try {
						changeDetectorMode = me.bechberger.testorder.changes.ChangeDetectionSupport
								.resolveMode(changeMode, hashFilePath);
					} catch (java.io.IOException e2) {
						changeDetectorMode = me.bechberger.testorder.changes.ChangeDetector.Mode.UNCOMMITTED;
					}
					me.bechberger.testorder.changes.SelectiveLearnSupport.StaticAnalysisData saData = me.bechberger.testorder.changes.SelectiveLearnSupport
							.computeStaticAnalysisData(changeRoot, classesDir, changeDetectorMode);
					java.util.Set<String> uncertainClasses = saData != null ? saData.uncertainClasses() : null;
					if (uncertainClasses != null) {
						String fname = (mid == null || mid.isBlank())
								? "uncertain-classes.txt"
								: "uncertain-classes-" + mid.replaceAll("[^a-zA-Z0-9._-]", "_") + ".txt";
						Path uncertainFile = ctx.resolveDepsDir(depsDir).resolve(fname);
						me.bechberger.testorder.changes.UncertainClassesStore.save(uncertainFile, uncertainClasses);
						me.bechberger.testorder.changes.StaticAnalysisDataStore.save(
								me.bechberger.testorder.changes.StaticAnalysisDataStore.sidecarPath(uncertainFile),
								saData);
						sysProps += " -Dtestorder.learn.uncertainClassesFile=" + uncertainFile.toAbsolutePath();
						if (uncertainClasses.isEmpty()) {
							getLog().info(
									"[test-order] Selective learn: no source changes detected; no classes will be instrumented");
						} else {
							getLog().info("[test-order] Selective learn: " + uncertainClasses.size()
									+ " uncertain class(es) will be instrumented");
						}
					}
				}
			} catch (java.io.IOException e) {
				getLog().warn(
						"[test-order] Selective learn: failed to compute uncertain classes — using full instrumentation: "
								+ e.getMessage());
			}
		}

		if (hasHardcodedSurefireArgLine || hasCliArgLine) {
			// The Surefire argLine is a hardcoded literal with no Maven property
			// placeholder.
			// Modifying the Xpp3Dom in-memory is ineffective because Surefire captured its
			// MojoExecution configuration before this goal ran. Instead, use the
			// maven.surefire.debug user property: Surefire reads this at fork-JVM time and
			// appends the value verbatim to the JVM command line — exactly what we need.
			boolean isFailsafe = "maven-failsafe-plugin".equals(surefire.getArtifactId());
			String debugProperty = isFailsafe ? "maven.failsafe.debug" : "maven.surefire.debug";
			if (hasHardcodedSurefireArgLine) {
				getLog().warn("[test-order] Surefire <argLine> is hardcoded (no @{argLine} or ${argLine} placeholder). "
						+ "Injecting agent via " + debugProperty + " user property. "
						+ "Consider using @{argLine} in your POM to chain agents safely.");
			} else {
				getLog().info("[test-order] Detected CLI -DargLine override. Injecting agent via " + debugProperty
						+ " to preserve existing argLine.");
			}
			String existingDebugValue = project.getProperties() != null
					? project.getProperties().getProperty(debugProperty)
					: null;
			String debugValue = ((existingDebugValue == null ? "" : existingDebugValue + " ") + agentString + sysProps)
					.trim();
			project.getProperties().setProperty(debugProperty, debugValue);
		} else {
			// R10-9: Detect if Failsafe uses a custom argLine property (e.g.
			// ${failsafe.argLine})
			boolean isFailsafe = "maven-failsafe-plugin".equals(surefire.getArtifactId());
			String argLineProperty = detectArgLinePropertyName(surefireArgLine, isFailsafe);
			String mergedProjectArgLine = (projectArgLine + " " + agentString + sysProps).trim();
			project.getProperties().setProperty(argLineProperty, mergedProjectArgLine);
			if (session != null && session.getUserProperties() != null) {
				session.getUserProperties().setProperty(argLineProperty, mergedProjectArgLine);
			}
			// Only append the @{property} placeholder if the Surefire argLine doesn't
			// already reference it — avoids double-expansion when users have
			// <argLine>@{argLine} ...</argLine> (e.g. for JaCoCo compatibility)
			String placeholder = "@{" + argLineProperty + "}";
			String dollarPlaceholder = "${" + argLineProperty + "}";
			if (!surefireArgLine.contains(placeholder) && !surefireArgLine.contains(dollarPlaceholder)) {
				String mergedSurefireArgLine = (surefireArgLine + " " + placeholder).trim();
				SurefireHelper.setChild(config, "argLine", mergedSurefireArgLine);
			}
			if (isFailsafe && !"argLine".equals(argLineProperty)) {
				getLog().info("[test-order] Failsafe detected with custom argLine property '" + argLineProperty
						+ "'. Injecting agent via that property.");
			}
		}

		injectTestClasspath(resolveOrdererClasspath());
		Path runtimeDir = runtimeConfigDir();
		injectTestClasspath(runtimeDir);
		ensureListenerServiceFile(runtimeDir);
		if (isTestNGOnTestClasspath()) {
			ensureTestNGListenerServiceFile(runtimeDir);
		}

		snapshotHashes();
	}

	/**
	 * Configures Surefire for offline learn mode: no agent attachment, instead
	 * passes the mapping file path and output dirs via system properties so that
	 * TelemetryListener can bootstrap UsageStore at test startup.
	 * <p>
	 * Requires that {@code test-order:instrument} has already run to produce the
	 * mapping file at {@code target/.test-order/class-id-map.bin}.
	 */
	protected void configureOfflineLearnMode(String instrumentationMode, String includePackages)
			throws MojoExecutionException {
		Path targetDir = Path.of(project.getBuild().getDirectory());
		Path mappingFile = targetDir.resolve(".test-order").resolve("class-id-map.bin");

		if (!java.nio.file.Files.exists(mappingFile)) {
			// Auto-instrument: run offline instrumentation inline
			Path classesDir = resolveClassesDir();
			if (classesDir == null) {
				// Classes not compiled yet (CLI goal runs before compile phase).
				// Defer instrumentation to the 'prepare' mojo at process-test-classes.
				getLog().info("[test-order] Classes not yet compiled — deferring offline instrumentation to "
						+ "process-test-classes phase.");
				project.getProperties().setProperty("testorder.offline.pending", "true");
				project.getProperties().setProperty("testorder.offline.instrMode", instrumentationMode);
				project.getProperties().setProperty("testorder.offline.includePackages",
						includePackages == null ? "" : includePackages);
			} else {
				runOfflineInstrumentation(instrumentationMode, includePackages, classesDir, targetDir, mappingFile);
			}
		} else {
			// Mapping file exists from a prior learn run. Check if backup is present;
			// if not, the classes may be stale-instrumented (from a benchmark or previous
			// run) without a restore path. Re-instrument to create a fresh backup so
			// restoreInstrumentedClasses() can cleanly undo the instrumentation after
			// this learn run completes.
			Path backupDir = targetDir.resolve(".test-order").resolve("classes-backup");
			boolean backupHasContent;
			try (java.util.stream.Stream<java.nio.file.Path> walkStream = java.nio.file.Files.isDirectory(backupDir)
					? java.nio.file.Files.walk(backupDir)
					: java.util.stream.Stream.empty()) {
				backupHasContent = walkStream.anyMatch(p -> p.toString().endsWith(".class"));
			} catch (IOException e) {
				backupHasContent = false;
			}
			if (!backupHasContent) {
				Path classesDir = resolveClassesDir();
				if (classesDir != null) {
					getLog().info(
							"[test-order] Re-instrumenting for offline learn mode (no backup found): " + classesDir);
					try {
						// Delete stale mapping so re-instrumentation starts fresh
						java.nio.file.Files.deleteIfExists(mappingFile);
					} catch (IOException e) {
						getLog().warn("[test-order] Could not delete stale mapping: " + e.getMessage());
					}
					runOfflineInstrumentation(instrumentationMode, includePackages, classesDir, targetDir, mappingFile);
				}
			} else {
				// Backup exists from a prior learn run that wasn't cleaned up (e.g. the
				// previous Maven session ended abnormally or afterSessionEnd ran before
				// this mojo registered the backup). Register it now so the current
				// session's afterSessionEnd restores pristine bytecode.
				pendingRestores.add(backupDir);
				pendingRestores.add(backupDir.resolveSibling("classes-backup-test"));
				registerPendingRestoreInSession(backupDir);
				registerPendingRestoreInSession(backupDir.resolveSibling("classes-backup-test"));
			}
		}

		getLog().info("[test-order] Offline learn mode (" + instrumentationMode.toUpperCase()
				+ "): no agent, using pre-instrumented classes");

		// Signal PrepareMojo to skip class restoration — restoring would undo the
		// instrumentation before tests run and prevent dependency tracking.
		project.getProperties().setProperty("testorder.offline.learnActive", "true");

		// Resolve output paths
		Path depsDir = ctx.resolveDepsDir(this.depsDir);
		Path indexFile = ctx.resolveIndexFile(this.indexFile);
		String statPathStr = ctx.resolveStateFile(stateFile).toAbsolutePath().toString();
		writeModuleIdSidecar(depsDir);

		// Start IndexCollectorServer for socket-based dep collection (eliminates .deps
		// file I/O and classloader issues in forked JVMs)
		String collectorPortProp = "";
		me.bechberger.testorder.IndexCollectorServer collector = startCollector(indexFile);
		if (collector != null) {
			collectorPortProp = " -D" + me.bechberger.testorder.TestOrderConfig.COLLECTOR_PORT + "="
					+ collector.getPort();
		}

		// Build system properties for forked JVMs
		String buildId = getOrCreateBuildId();
		Path pendingRunsDir = ctx.resolveBaseDir().resolve("pending-runs");
		String sysProps = " -D" + MavenPluginConfigKeys.LEARN + "=true" + " -D"
				+ MavenPluginConfigKeys.INSTRUMENTATION_MODE + "=" + instrumentationMode.toUpperCase() + " -D"
				+ me.bechberger.testorder.TestOrderConfig.OFFLINE_MAPPING + "=" + mappingFile.toAbsolutePath() + " -D"
				+ me.bechberger.testorder.TestOrderConfig.OFFLINE_OUTPUT + "=" + depsDir.toAbsolutePath() + " -D"
				+ me.bechberger.testorder.TestOrderConfig.OFFLINE_INDEX_FILE + "=" + indexFile.toAbsolutePath() + " -D"
				+ MavenPluginConfigKeys.STATE_PATH + "=" + statPathStr + collectorPortProp + " -D"
				+ me.bechberger.testorder.TestOrderConfig.BUILD_ID + "=" + buildId + " -D"
				+ me.bechberger.testorder.TestOrderConfig.PENDING_RUNS_DIR + "=" + pendingRunsDir.toAbsolutePath();
		String mid = computeCurrentModuleId();
		if (mid != null && !mid.isEmpty()) {
			sysProps += " -Dtestorder.moduleId=" + mid;
		}

		// Inject system props into argLine (same strategy as online mode but without
		// agent)
		Plugin surefire = SurefireHelper.requireSurefirePlugin(project);
		org.codehaus.plexus.util.xml.Xpp3Dom config = SurefireHelper.getOrCreateConfiguration(surefire);
		String surefireArgLine = "";
		org.codehaus.plexus.util.xml.Xpp3Dom argLineNode = config.getChild("argLine");
		if (argLineNode != null && argLineNode.getValue() != null) {
			surefireArgLine = argLineNode.getValue().trim();
		}
		boolean hasHardcodedSurefireArgLine = SurefireHelper.isHardcodedArgLine(surefireArgLine);
		boolean hasCliArgLine = session != null && session.getUserProperties() != null
				&& session.getUserProperties().getProperty("argLine") != null;

		if (hasHardcodedSurefireArgLine || hasCliArgLine) {
			boolean isFailsafe = "maven-failsafe-plugin".equals(surefire.getArtifactId());
			String debugProperty = isFailsafe ? "maven.failsafe.debug" : "maven.surefire.debug";
			String existingDebugValue = project.getProperties() != null
					? project.getProperties().getProperty(debugProperty)
					: null;
			String debugValue = ((existingDebugValue == null ? "" : existingDebugValue + " ") + sysProps).trim();
			project.getProperties().setProperty(debugProperty, debugValue);
		} else {
			boolean isFailsafe = "maven-failsafe-plugin".equals(surefire.getArtifactId());
			String argLineProperty = detectArgLinePropertyName(surefireArgLine, isFailsafe);
			String projectArgLine = project.getProperties().getProperty(argLineProperty, "").trim();
			String mergedProjectArgLine = (projectArgLine + " " + sysProps).trim();
			project.getProperties().setProperty(argLineProperty, mergedProjectArgLine);
			if (session != null && session.getUserProperties() != null) {
				session.getUserProperties().setProperty(argLineProperty, mergedProjectArgLine);
			}
			String placeholder = "@{" + argLineProperty + "}";
			String dollarPlaceholder = "${" + argLineProperty + "}";
			if (!surefireArgLine.contains(placeholder) && !surefireArgLine.contains(dollarPlaceholder)) {
				String mergedSurefireArgLine = (surefireArgLine + " " + placeholder).trim();
				SurefireHelper.setChild(config, "argLine", mergedSurefireArgLine);
			}
		}

		// Inject runtime jar on test classpath (UsageStore needs to be accessible)
		Path agentJar = resolveArtifact("test-order-agent");
		Path runtimeJar = me.bechberger.testorder.AgentArgsBuilder.preExtractRuntimeJar(agentJar, targetDir);
		injectTestClasspath(runtimeJar);

		injectTestClasspath(resolveOrdererClasspath());
		Path runtimeDir = runtimeConfigDir();
		injectTestClasspath(runtimeDir);
		ensureListenerServiceFile(runtimeDir);
		if (isTestNGOnTestClasspath()) {
			ensureTestNGListenerServiceFile(runtimeDir);
		}

		// Skip API-compatibility checkers that scan target/classes — they would flag
		// the UsageStore call-sites injected by offline instrumentation as unknown
		// references (e.g. animal-sniffer's Java 8 API check).
		skipPostInstrumentCheckers();

		snapshotHashes();
	}

	/**
	 * Returns the directory to instrument for offline learn mode: main classes if
	 * they exist, otherwise test classes (for test-only modules with no src/main).
	 * Returns null if neither directory exists yet (classes not compiled).
	 */
	protected Path resolveClassesDir() {
		Path mainClasses = Path.of(project.getBuild().getOutputDirectory());
		if (java.nio.file.Files.isDirectory(mainClasses)) {
			return mainClasses;
		}
		Path testClasses = Path.of(project.getBuild().getTestOutputDirectory());
		if (java.nio.file.Files.isDirectory(testClasses)) {
			return testClasses;
		}
		return null;
	}

	private void runOfflineInstrumentation(String instrumentationMode, String includePackages, Path classesDir,
			Path targetDir, Path mappingFile) throws MojoExecutionException {
		getLog().info("[test-order] Auto-instrumenting classes for offline learn mode: " + classesDir);
		try {
			Agent.InstrumentationMode iMode = Agent.InstrumentationMode.fromString(instrumentationMode);
			List<String> includes = includePackages == null || includePackages.isBlank()
					? List.of()
					: List.of(includePackages.split(","));

			// Selective learn: only instrument changed/uncertain classes when enabled
			Set<String> uncertainClasses = null;
			me.bechberger.testorder.changes.SelectiveLearnSupport.StaticAnalysisData saData = null;
			if (selectiveLearn) {
				Path idxPath = ctx != null ? ctx.resolveIndexFile(indexFile) : Path.of(indexFile);
				boolean indexExists = java.nio.file.Files.exists(idxPath);
				if (indexExists) {
					me.bechberger.testorder.changes.ChangeDetector.Mode mode;
					try {
						mode = me.bechberger.testorder.changes.ChangeDetectionSupport.resolveMode(changeMode,
								ctx != null ? ctx.resolveHashFile(hashFile) : null);
					} catch (java.io.IOException e) {
						mode = me.bechberger.testorder.changes.ChangeDetector.Mode.UNCOMMITTED;
					}
					Path projectRoot = ctx != null ? ctx.gitRoot() : project.getBasedir().toPath();
					saData = me.bechberger.testorder.changes.SelectiveLearnSupport
							.computeStaticAnalysisData(projectRoot, classesDir, mode);
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
				String mid2 = computeCurrentModuleId();
				String fname = (mid2 == null || mid2.isBlank())
						? "uncertain-classes.txt"
						: "uncertain-classes-" + mid2.replaceAll("[^a-zA-Z0-9._-]", "_") + ".txt";
				try {
					Path depsDirPath = ctx != null ? ctx.resolveDepsDir(depsDir) : Path.of(depsDir);
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

			OfflineInstrumentor instrumentor = new OfflineInstrumentor(iMode, includes, List.of(), uncertainClasses);
			Path backupRoot = targetDir.resolve(".test-order").resolve("classes-backup");
			ClassIdMapping mapping = instrumentor.instrument(classesDir, backupRoot);
			boolean ignoreMarker = false;
			if (instrumentor.getTransformedCount() == 0 && instrumentor.getSkippedCount() > 0) {
				// All classes have stale marker from prior instrumentation without
				// a matching mapping. Force re-instrument by ignoring the marker.
				getLog().info("[test-order] Detected stale instrumentation (no mapping). Re-instrumenting...");
				instrumentor = new OfflineInstrumentor(iMode, includes, List.of()); // full re-instrument, no selective
				instrumentor.setIgnoreMarker(true);
				ignoreMarker = true;
				mapping = instrumentor.instrument(classesDir, backupRoot);
			}
			int mainCount = instrumentor.getTransformedCount();
			int mainSkipped = instrumentor.getSkippedCount();

			// Some build configurations (notably JPMS projects compiling tests on
			// module-path with --patch-module — e.g. jackson-databind) recompile main
			// sources into target/test-classes/ during test-compile. Surefire then
			// loads the test-classes/ copies first, shadowing our instrumented main
			// classes. Detect and instrument those duplicates too, sharing the same
			// class/member id map so the runtime sees consistent IDs.
			Path testClassesDir = Path.of(project.getBuild().getTestOutputDirectory());
			if (java.nio.file.Files.isDirectory(testClassesDir) && !testClassesDir.equals(classesDir)
					&& hasShadowingMainClasses(classesDir, testClassesDir)) {
				Path testBackupDir = backupRoot.resolveSibling("classes-backup-test");
				getLog().info("[test-order] Detected main classes duplicated in test-classes "
						+ "(JPMS patch-module build) — also instrumenting: " + testClassesDir);
				OfflineInstrumentor testInstrumentor = new OfflineInstrumentor(iMode, includes, List.of(),
						uncertainClasses);
				if (ignoreMarker) {
					testInstrumentor.setIgnoreMarker(true);
				}
				ClassIdMapping testMapping = testInstrumentor.instrument(testClassesDir, testBackupDir);
				// Merge: testMapping comes from the same singleton ClassIdMap, so its
				// class/member maps already include all entries from the main pass.
				// We only need to keep the latest mapping (it's a snapshot of the singleton).
				mapping = testMapping;
				getLog().info("[test-order] Also instrumented " + testInstrumentor.getTransformedCount()
						+ " classes in test-classes (skipped " + testInstrumentor.getSkippedCount() + ")");
			}

			mapping.save(mappingFile);
			getLog().info("[test-order] Instrumented " + mainCount + " classes" + " (skipped " + mainSkipped + ")");
			// Register backup dirs so CollectorLifecycleParticipant restores pristine
			// bytecode at session end. Without this, a subsequent `mvn test` (no clean)
			// would re-run plugins like log4j2's `generate-plugin-descriptors` against
			// instrumented classes and fail with NoClassDefFoundError on UsageStore.
			pendingRestores.add(backupRoot);
			pendingRestores.add(backupRoot.resolveSibling("classes-backup-test"));
			// Also register via Maven session user properties so the lifecycle participant
			// (which runs in a different ClassLoader realm) can read them.
			registerPendingRestoreInSession(backupRoot);
			registerPendingRestoreInSession(backupRoot.resolveSibling("classes-backup-test"));
		} catch (IOException e) {
			throw new MojoExecutionException("[test-order] Offline instrumentation failed", e);
		}
	}

	/**
	 * Sets project properties to skip QA plugins that scan {@code target/classes}
	 * after offline instrumentation. Offline instrumentation injects
	 * {@code UsageStore} call-sites into the bytecode, which makes tools like
	 * animal-sniffer (Java 8 API compatibility check) and Checkstyle's bytecode
	 * analyser report false positives — they see method references that are only
	 * valid at test runtime, not in a Java 8 environment.
	 * <p>
	 * These skips are project-scoped and only affect the current Maven session.
	 */
	private void skipPostInstrumentCheckers() {
		java.util.Properties props = project.getProperties();
		props.setProperty("animal.sniffer.skip", "true");
		props.setProperty("checkstyle.skip", "true");
		props.setProperty("pmd.skip", "true");
		props.setProperty("cpd.skip", "true");
		props.setProperty("spotbugs.skip", "true");
		props.setProperty("findbugs.skip", "true");
		getLog().debug(
				"[test-order] Disabled API/QA checkers for learn run (animal-sniffer, checkstyle, pmd, spotbugs)");
	}

	static final String SESSION_PENDING_RESTORES_KEY = "testorder.pendingRestores";

	/**
	 * Registers a backup directory path in the Maven session user properties so the
	 * lifecycle participant (which runs in a different ClassLoader realm) can find
	 * it and restore pristine bytecode at session end. Also delegates to
	 * {@link me.bechberger.testorder.ClassBackupRestorer#register(Path)}, which
	 * installs a JVM shutdown hook using only classes from {@code test-order-core}
	 * (not the plugin classloader) so the hook reliably fires after Maven shuts
	 * down.
	 */
	void registerPendingRestoreInSession(Path backupDir) {
		if (session == null) {
			return;
		}
		java.util.Properties props = session.getUserProperties();
		String path = backupDir.toAbsolutePath().toString();
		synchronized (props) {
			String existing = props.getProperty(SESSION_PENDING_RESTORES_KEY, "");
			if (!existing.isEmpty()) {
				props.setProperty(SESSION_PENDING_RESTORES_KEY, existing + "|" + path);
			} else {
				props.setProperty(SESSION_PENDING_RESTORES_KEY, path);
			}
		}
		me.bechberger.testorder.ClassBackupRestorer.register(backupDir);
	}

	/**
	 * Returns true if {@code testClassesDir} contains class files at the same
	 * relative paths as {@code mainClassesDir}, indicating that the build copied or
	 * recompiled main sources into test-classes (JPMS patch-module pattern). We
	 * only need to find a single duplicate to confirm the pattern.
	 */
	private static boolean hasShadowingMainClasses(Path mainClassesDir, Path testClassesDir) {
		try {
			java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean(false);
			Files.walkFileTree(mainClassesDir, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (file.toString().endsWith(".class")) {
						Path relative = mainClassesDir.relativize(file);
						if (Files.exists(testClassesDir.resolve(relative))) {
							found.set(true);
							return FileVisitResult.TERMINATE;
						}
					}
					return FileVisitResult.CONTINUE;
				}
			});
			return found.get();
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Detects which Maven property the Surefire/Failsafe argLine configuration
	 * references. For example, if argLine is {@code ${failsafe.argLine}}, returns
	 * "failsafe.argLine". Falls back to "argLine" if no custom property is found.
	 * (R10-9)
	 */
	private static String detectArgLinePropertyName(String surefireArgLine, boolean isFailsafe) {
		if (surefireArgLine != null && !surefireArgLine.isBlank()) {
			// Check for ${propertyName} or @{propertyName} patterns
			java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[$@]\\{([^}]+)}")
					.matcher(surefireArgLine);
			boolean hasArgLine = false;
			String customProp = null;
			while (matcher.find()) {
				String prop = matcher.group(1);
				if (prop.contains(":"))
					continue;
				if (prop.equals("argLine")) {
					hasArgLine = true;
				} else if (customProp == null) {
					customProp = prop;
				}
			}
			// If both argLine and a custom prop appear (e.g. "${jacoco.argLine}
			// @{argLine}"),
			// prefer argLine to avoid overwriting the custom property set by other plugins
			if (hasArgLine) {
				return "argLine";
			}
			if (customProp != null) {
				return customProp;
			}
		}
		return "argLine";
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
		// Preserve any <additionalClasspathElements> from Surefire/Failsafe XML
		// config. Setting the maven.test.additionalClasspath property overrides
		// the XML value, so we must merge them to avoid breaking projects that
		// rely on XML-configured classpath entries (e.g., multi-release JARs).
		if (entries.isEmpty()) {
			for (String xmlEntry : SurefireHelper.extractAdditionalClasspathElements(project)) {
				entries.add(xmlEntry);
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
		if (session != null && session.getUserProperties() != null) {
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
		// Use the shaded (-all) jar for test-order-core so that bundled dependencies
		// (femtojson, femtocli, …) are relocated and cannot clash with the project's
		// own version of those libraries on the Surefire test classpath.
		entries.add(resolveShadedCoreJar());
		return entries.toArray(Path[]::new);
	}

	/**
	 * Resolves the shaded fat-jar for test-order-core (the {@code -all}
	 * classifier). The shaded jar is required because it bundles LZ4 and other
	 * dependencies; using the reactor classes dir alone causes
	 * ClassNotFoundException for those transitive deps at test runtime.
	 * <p>
	 * Priority: shaded -all jar from local repo &gt; reactor classes dir
	 * (pre-install development only) &gt; unshaded plain jar (last resort).
	 */
	private Path resolveShadedCoreJar() throws MojoExecutionException {
		// Prefer the shaded -all jar from the local repo — it bundles LZ4 etc.
		String repoPath = session != null && session.getLocalRepository() != null
				? session.getLocalRepository().getBasedir()
				: System.getProperty("user.home", "") + "/.m2/repository";
		String pluginVersion = null;
		for (Plugin p : project.getBuildPlugins()) {
			if ("test-order-maven-plugin".equals(p.getArtifactId()) && "me.bechberger".equals(p.getGroupId())) {
				pluginVersion = p.getVersion();
				break;
			}
		}
		List<Path> checked = new ArrayList<>();
		for (String version : new String[]{pluginVersion, project.getVersion()}) {
			if (version == null)
				continue;
			Path baseDir = Path.of(repoPath).resolve("me/bechberger/test-order-core").resolve(version);
			Path shadedJar = baseDir.resolve("test-order-core-" + version + "-all.jar");
			checked.add(shadedJar);
			if (Files.exists(shadedJar)) {
				return shadedJar;
			}
		}
		// Fall back to reactor classes dir when the shaded jar hasn't been built yet
		// (e.g. first compile before mvn install). Note: this path is missing LZ4 and
		// other binary deps, so ClassNotFoundException may occur. Run mvn install first
		// for reliable behaviour.
		Path reactorClassesDir = project.getBasedir().toPath().getParent().resolve("test-order-core").resolve("target")
				.resolve("classes");
		if (Files.isDirectory(reactorClassesDir)) {
			return reactorClassesDir;
		}
		// Also try the shaded jar using the resolveArtifact path (handles non-standard
		// repo layouts, classifier installs, etc.)
		Path shadedViaResolver = resolveArtifactShaded("test-order-core");
		if (shadedViaResolver != null) {
			return shadedViaResolver;
		}
		// Fallback to unshaded plain jar — risks classpath conflicts but better than
		// nothing.
		getLog().warn("[test-order] Shaded test-order-core-all.jar not found (checked: " + checked
				+ ") — falling back to unshaded jar. "
				+ "If you see NoSuchMethodError or similar, run: mvn install on the test-order project.");
		return resolveArtifact("test-order-core");
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
	private void removeLegacyGeneratedOrdererFiles() {
		removeLegacyJunitOrdererProperty();
		removeListenerServiceFiles();
	}

	private void removeListenerServiceFiles() {
		String testOutputDir = project.getBuild().getTestOutputDirectory();
		if (testOutputDir == null)
			return;
		Path serviceDir = Path.of(testOutputDir).resolve("META-INF").resolve("services");
		removeTestOrderEntryFromServiceFile(serviceDir.resolve("org.junit.platform.launcher.TestExecutionListener"),
				"me.bechberger.testorder.junit.TelemetryListener");
		removeTestOrderEntryFromServiceFile(serviceDir.resolve("org.testng.ITestNGListener"),
				"me.bechberger.testorder.");
	}

	private void removeLegacyJunitOrdererProperty() {
		String testOutputDir = project.getBuild().getTestOutputDirectory();
		if (testOutputDir == null)
			return;
		Path propsFile = Path.of(testOutputDir).resolve("junit-platform.properties");
		if (!Files.exists(propsFile))
			return;
		try {
			List<String> lines = Files.readAllLines(propsFile);
			List<String> kept = lines.stream()
					.filter(l -> !l.contains("me.bechberger.testorder.junit.PriorityClassOrderer")).toList();
			if (kept.isEmpty() || kept.stream().allMatch(String::isBlank)) {
				Files.delete(propsFile);
			} else if (kept.size() < lines.size()) {
				Files.writeString(propsFile, String.join("\n", kept) + "\n");
			}
		} catch (IOException e) {
			getLog().debug("Could not clean up junit-platform.properties in test output: " + e.getMessage());
		}
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
	protected void ensureListenerServiceFile(Path classpathRoot) throws MojoExecutionException {
		Path serviceDir = classpathRoot.resolve("META-INF").resolve("services");
		Path serviceFile = serviceDir.resolve("org.junit.platform.launcher.TestExecutionListener");
		try {
			Files.createDirectories(serviceDir);
			String listenerFqcn = "me.bechberger.testorder.junit.TelemetryListener";
			// Read-check-write: not fully atomic under parallel reactor builds,
			// but duplicate SPI entries are harmless (ServiceLoader deduplicates)
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
		return project.getDependencies().stream().anyMatch(d -> "org.junit.jupiter".equals(d.getGroupId()));
	}

	/**
	 * Warns when JUnit 4 tests are detected on the classpath, since test-order only
	 * supports JUnit 5 (Jupiter) and TestNG.
	 */
	protected void warnJUnit4Unsupported() {
		if (isJUnit4OnTestClasspath()) {
			if (isJUnit5OnTestClasspath()) {
				getLog().warn("[test-order] JUnit 4 dependency detected alongside JUnit 5. "
						+ "test-order only supports JUnit 5 (Jupiter) and TestNG — "
						+ "JUnit 4 tests will not be reordered or tracked. "
						+ "Consider migrating to JUnit 5 or using the JUnit Vintage engine.");
			} else if (isJUnit4VintageEngineOnTestClasspath()) {
				// JUnit Vintage engine bridges JUnit 4 tests onto the JUnit Platform —
				// test-order's TelemetryListener can track them via the platform.
				getLog().info("[test-order] JUnit 4 + JUnit Vintage engine detected. "
						+ "JUnit 4 tests will run via the JUnit Platform and will be tracked by test-order.");
			} else {
				getLog().warn("[test-order] JUnit 4 dependency detected but no JUnit 5 (Jupiter) found. "
						+ "test-order does NOT support JUnit 4 — tests will not be reordered or tracked. "
						+ "Please migrate to JUnit 5 or add the JUnit Vintage engine with "
						+ "junit-jupiter-engine on the test classpath.");
			}
		}
	}

	/**
	 * Returns {@code true} when the project declares {@code junit-vintage-engine}
	 * as a dependency, which allows JUnit 4 tests to run on the JUnit Platform
	 * where test-order's TelemetryListener can observe them.
	 */
	protected boolean isJUnit4VintageEngineOnTestClasspath() {
		return project.getDependencies().stream().anyMatch(
				d -> "org.junit.vintage".equals(d.getGroupId()) && "junit-vintage-engine".equals(d.getArtifactId()));
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
		new me.bechberger.testorder.ops.JUnitPlatformValidator(pluginLog())
				.checkJunitPlatformPropertiesFile(project.getBasedir().toPath());
	}

	/**
	 * Writes the {@code META-INF/services/org.testng.ITestNGListener} service file
	 * into {@code target/test-classes} so that TestNG's ServiceLoader discovers our
	 * {@code TestNGTelemetryListener} and {@code TestNGPriorityInterceptor}.
	 */
	protected void ensureTestNGListenerServiceFile(Path classpathRoot) throws MojoExecutionException {
		Path serviceDir = classpathRoot.resolve("META-INF").resolve("services");
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

	/**
	 * Injects the offline runtime jar
	 * ({@code target/.test-order/test-order-runtime.jar}) onto the Surefire test
	 * classpath when it exists.
	 *
	 * <p>
	 * This is needed in apply mode when classes were previously instrumented by
	 * offline learn mode and the backup/restore cycle left them in an instrumented
	 * state (e.g. because the mapping file existed from a prior run and
	 * re-instrumentation was skipped, so no backup was created for this run).
	 * Instrumented bytecode directly invokes
	 * {@code me.bechberger.testorder.agent.runtime.UsageStore} — if that class
	 * isn't on the classpath, every test throws {@code NoClassDefFoundError}.
	 */
	protected void injectOfflineRuntimeJarIfPresent() throws MojoExecutionException {
		String buildDir = project.getBuild().getDirectory();
		if (buildDir == null)
			return;
		Path runtimeJar = Path.of(buildDir).resolve(".test-order").resolve("test-order-runtime.jar");
		if (Files.exists(runtimeJar)) {
			injectTestClasspath(runtimeJar);
		}
	}

	/**
	 * Returns a stable build session ID for this Maven invocation. The ID is
	 * generated once per Maven JVM process and stored in a session-scoped project
	 * property so all modules in the same reactor share it.
	 * <p>
	 * Also registers the (pendingRunsDir, stateFile) pair in
	 * {@link #pendingAggregations} so the {@link CollectorLifecycleParticipant} can
	 * merge partial records after all forks complete.
	 */
	protected String getOrCreateBuildId() {
		// Use the top-level project (reactor root) to store the shared build ID
		// across all modules so all modules in one reactor build share the same ID.
		org.apache.maven.project.MavenProject rootProject = session != null && session.getTopLevelProject() != null
				? session.getTopLevelProject()
				: project;

		String buildId;
		synchronized (rootProject.getProperties()) {
			buildId = rootProject.getProperties().getProperty("testorder.internal.buildId");
			if (buildId == null || buildId.isBlank()) {
				buildId = java.util.UUID.randomUUID().toString();
				rootProject.getProperties().setProperty("testorder.internal.buildId", buildId);
			}
		}

		// Register this module's pending-runs dir + state file for post-merge
		Path baseDir = ctx.resolveBaseDir();
		Path pendingRunsDir = baseDir.resolve("pending-runs");
		Path stateFilePath = ctx.resolveStateFile(stateFile);
		pendingAggregations.put(buildId + "|" + stateFilePath.toAbsolutePath().normalize(),
				new PendingAggregation(pendingRunsDir, stateFilePath));

		// Clean up stale .part files from previous crashed/interrupted builds (B19).
		// Use a 30-minute cutoff so we don't remove files from a concurrent parallel
		// run.
		me.bechberger.testorder.PartialRunAggregator.cleanStalePartials(pendingRunsDir, 30 * 60 * 1000L);

		return buildId;
	}

	protected void removeStaleTestClassesConfig() {
		String testClassesDir = project.getBuild().getTestOutputDirectory();
		if (testClassesDir == null || testClassesDir.isBlank())
			return;
		Path testClasses = Path.of(testClassesDir);
		for (String name : new String[]{"testorder-config.properties", "junit-platform.properties"}) {
			Path stale = testClasses.resolve(name);
			if (!Files.exists(stale))
				continue;
			try {
				String contents = Files.readString(stale);
				// Only delete if this looks like a test-order-generated file
				if (contents.contains("testorder.index.path") || contents.contains("testorder.state.path")
						|| contents.contains("PriorityClassOrderer")) {
					Files.delete(stale);
					getLog().debug("[test-order] Removed stale config from test-classes: " + stale);
				}
			} catch (IOException e) {
				getLog().warn("[test-order] Could not remove stale config " + stale + ": " + e.getMessage());
			}
		}
	}

	protected Path runtimeConfigDir() {
		String buildDir = project.getBuild().getDirectory();
		if (buildDir == null || buildDir.isBlank()) {
			return project.getBasedir().toPath().resolve("target").resolve("test-order-runtime");
		}
		return Path.of(buildDir).resolve("test-order-runtime");
	}

	/**
	 * Removes TDD-specific entries from runtime config files left by a previous
	 * run. Strips {@code testorder.tdd=...} from
	 * {@code testorder-config.properties} and
	 * {@code junit.jupiter.extensions.autodetection.enabled=...} from
	 * {@code junit-platform.properties}.
	 */
	protected void cleanStaleTddConfig() {
		Path runtimeDir = runtimeConfigDir();
		if (!Files.isDirectory(runtimeDir))
			return;
		stripLines(runtimeDir.resolve("testorder-config.properties"), "testorder.tdd");
		stripLines(runtimeDir.resolve("junit-platform.properties"), "junit.jupiter.extensions.autodetection.enabled");
	}

	private void stripLines(Path file, String keyPrefix) {
		if (!Files.exists(file))
			return;
		try {
			List<String> lines = Files.readAllLines(file);
			List<String> filtered = lines.stream()
					.filter(l -> !l.startsWith(keyPrefix + "=") && !l.startsWith(keyPrefix + " ")).toList();
			if (filtered.size() < lines.size()) {
				Files.writeString(file, String.join("\n", filtered) + (filtered.isEmpty() ? "" : "\n"));
			}
		} catch (IOException e) {
			getLog().debug("[test-order] Could not clean TDD config from " + file + ": " + e.getMessage());
		}
	}

	// ── Artifact resolution ───────────────────────────────────────────

	/**
	 * Resolves only the shaded (-all.jar) variant of an artifact using the same
	 * version-lookup logic as {@link #resolveArtifact}. Returns null when not
	 * found.
	 */
	private Path resolveArtifactShaded(String artifactId) {
		String repoPath = session != null && session.getLocalRepository() != null
				? session.getLocalRepository().getBasedir()
				: System.getProperty("user.home", "") + "/.m2/repository";
		Path localRepo = Path.of(repoPath);
		String pluginVersion = null;
		for (Plugin p : project.getBuildPlugins()) {
			if ("test-order-maven-plugin".equals(p.getArtifactId()) && "me.bechberger".equals(p.getGroupId())) {
				pluginVersion = p.getVersion();
				break;
			}
		}
		for (String version : new String[]{pluginVersion, project.getVersion()}) {
			if (version == null)
				continue;
			Path baseDir = localRepo.resolve("me/bechberger").resolve(artifactId).resolve(version);
			Path shadedJar = baseDir.resolve(artifactId + "-" + version + "-all.jar");
			if (Files.exists(shadedJar))
				return shadedJar;
		}
		// Try scanning all installed versions, preferring newest
		Path artifactRepoDir = localRepo.resolve("me/bechberger").resolve(artifactId);
		if (Files.isDirectory(artifactRepoDir)) {
			try (Stream<Path> versions = Files.list(artifactRepoDir)) {
				return versions.filter(Files::isDirectory).sorted(java.util.Comparator.<Path, Long>comparing(d -> {
					try {
						return Files.getLastModifiedTime(d).toMillis();
					} catch (IOException e) {
						return 0L;
					}
				}).reversed()).map(dir -> dir.resolve(artifactId + "-" + dir.getFileName() + "-all.jar"))
						.filter(Files::exists).findFirst().orElse(null);
			} catch (IOException e) {
				// fall through
			}
		}
		return null;
	}

	protected Path resolveArtifact(String artifactId) throws MojoExecutionException {
		// Check cache first to avoid repeated filesystem lookups in multi-module builds
		Path cachedResult = resolvedArtifactCache.get(artifactId);
		if (cachedResult != null) {
			if (Files.exists(cachedResult))
				return cachedResult;
			// Cache entry stale (file was deleted), remove and continue
			resolvedArtifactCache.remove(artifactId);
		}

		String repoPath = null;
		if (session != null && session.getLocalRepository() != null) {
			repoPath = session.getLocalRepository().getBasedir();
		}
		if (repoPath == null || repoPath.isBlank()) {
			String userHome = System.getProperty("user.home");
			if (userHome == null || userHome.isBlank()) {
				throw new MojoExecutionException(
						"[test-order] Cannot resolve Maven local repository: session.getLocalRepository() is null "
								+ "and 'user.home' system property is not set.");
			}
			repoPath = userHome + "/.m2/repository";
		}
		Path localRepo = Path.of(repoPath);

		String pluginVersion = null;
		for (Plugin p : project.getBuildPlugins()) {
			if ("test-order-maven-plugin".equals(p.getArtifactId()) && "me.bechberger".equals(p.getGroupId())) {
				pluginVersion = p.getVersion();
				break;
			}
		}

		for (String version : new String[]{pluginVersion, project.getVersion()}) {
			if (version == null)
				continue;
			Path baseDir = localRepo.resolve("me/bechberger").resolve(artifactId).resolve(version);
			Path match = findBestArtifactJar(baseDir, artifactId, version);
			if (match != null) {
				resolvedArtifactCache.put(artifactId, match);
				return match;
			}
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
						getLog().debug("[test-order] Could not compare modification times: " + e.getMessage());
						// Prefer whichever file we can actually read
						boolean aReadable = Files.isReadable(a);
						boolean bReadable = Files.isReadable(b);
						return Boolean.compare(aReadable, bReadable);
					}
				}).orElse(null);
				if (newest != null) {
					getLog().debug("[test-order] Resolved " + artifactId + " via local repo fallback: " + newest);
					resolvedArtifactCache.put(artifactId, newest);
					return newest;
				}
			} catch (IOException ignored) {
				// Handled by final error path below.
			}
		}

		Path reactorFatJar = project.getBasedir().toPath().getParent().resolve(artifactId).resolve("target")
				.resolve(artifactId + "-jar-with-dependencies.jar");
		if (Files.exists(reactorFatJar)) {
			resolvedArtifactCache.put(artifactId, reactorFatJar);
			return reactorFatJar;
		}

		Path reactorPath = project.getBasedir().toPath().getParent().resolve(artifactId).resolve("target")
				.resolve(artifactId + ".jar");
		if (Files.exists(reactorPath)) {
			resolvedArtifactCache.put(artifactId, reactorPath);
			return reactorPath;
		}

		throw new MojoExecutionException("Cannot find " + artifactId + " jar. Build the parent project first.");
	}

	private Path findBestArtifactJar(Path baseDir, String artifactId, String version) {
		// Prefer shaded -all.jar (bundles LZ4 etc.), then jar-with-dependencies, then
		// plain jar.
		Path shadedJarPath = baseDir.resolve(artifactId + "-" + version + "-all.jar");
		if (Files.exists(shadedJarPath))
			return shadedJarPath;
		Path fatJarPath = baseDir.resolve(artifactId + "-" + version + "-jar-with-dependencies.jar");
		if (Files.exists(fatJarPath))
			return fatJarPath;
		Path artifactPath = baseDir.resolve(artifactId + "-" + version + ".jar");
		if (Files.exists(artifactPath))
			return artifactPath;
		return null;
	}
}
