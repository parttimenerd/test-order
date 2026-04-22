package me.bechberger.testorder.plugin;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

	/**
	 * Path to the dependency index file (LZ4 compressed binary format). Used by
	 * test-order to load and save test dependencies.
	 */
	@Parameter(property = MavenPluginConfigKeys.LEGACY_INDEX, defaultValue = "${project.basedir}/.test-order/test-dependencies.lz4")
	protected String indexFile;

	/**
	 * Path to the state file for persisting test execution history and weights.
	 * Used to track test performance over time.
	 */
	@Parameter(property = MavenPluginConfigKeys.LEGACY_STATE_FILE, defaultValue = "${project.basedir}/.test-order/state.lz4")
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
	@Parameter(property = MavenPluginConfigKeys.LEGACY_SOURCE_ROOT)
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
	@Parameter(property = MavenPluginConfigKeys.CHANGE_MODE, defaultValue = "auto")
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
	@Parameter(property = MavenPluginConfigKeys.LEGACY_METHOD_ORDERING_ENABLED, defaultValue = "false")
	protected boolean methodOrderingEnabled;

	// ── Lifecycle helpers ─────────────────────────────────────────────

	protected void initContext() throws MojoExecutionException {
		if (skip) {
			getLog().info("[test-order] Skipping — testorder.skip=true");
			removeListenerServiceFiles();
			return;
		}
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
	 * Accept canonical property keys while preserving legacy Maven user-property
	 * compatibility.
	 */
	private void applyCanonicalUserPropertyOverrides() {
		if (session == null || session.getUserProperties() == null) {
			return;
		}
		String value = session.getUserProperties().getProperty(MavenPluginConfigKeys.INDEX_PATH);
		if (value != null && !value.isBlank())
			indexFile = value;
		value = session.getUserProperties().getProperty(MavenPluginConfigKeys.STATE_PATH);
		if (value != null && !value.isBlank())
			stateFile = value;
		value = session.getUserProperties().getProperty(MavenPluginConfigKeys.SOURCE_ROOT);
		if (value != null && !value.isBlank())
			sourceRoot = value;
		value = session.getUserProperties().getProperty(MavenPluginConfigKeys.CHANGE_MODE);
		if (value != null && !value.isBlank())
			changeMode = value;
		value = session.getUserProperties().getProperty(MavenPluginConfigKeys.METHOD_ORDER_ENABLED);
		if (value != null && !value.isBlank())
			methodOrderingEnabled = Boolean.parseBoolean(value);
	}

	protected Path resolveIndexPath() {
		return ctx.resolveIndexFile(indexFile);
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
		return ChangeDetectionHelper.detectChangedClasses(ctx, changeMode, changedClasses,
				ChangeDetectionHelper.resolveSourceRoot(project, sourceRoot), ctx.resolveHashFile(hashFile), readOnly,
				getLog());
	}

	/**
	 * Validates explicitly specified changed classes against the dependency index.
	 * Logs a warning for each class not found in the index and throws if none
	 * match. Protects against silently wrong test selection (M-CRIT-1).
	 */
	protected void warnUnknownChangedClasses(Set<String> changed, DependencyMap depMap) throws MojoExecutionException {
		if (changed.isEmpty() || !"explicit".equalsIgnoreCase(changeMode))
			return;
		Set<String> known = depMap.testClasses();
		// Check against both the dependency map keys (test classes) and their dep
		// values (production classes)
		Set<String> allKnown = new java.util.HashSet<>(known);
		for (String tc : known) {
			allKnown.addAll(depMap.get(tc));
		}
		Set<String> unknown = new java.util.LinkedHashSet<>();
		for (String cls : changed) {
			if (!allKnown.contains(cls)) {
				unknown.add(cls);
			}
		}
		if (!unknown.isEmpty()) {
			getLog().warn("[test-order] The following explicitly changed classes are not in the dependency index: "
					+ String.join(", ", unknown));
			if (unknown.size() == changed.size()) {
				throw new MojoExecutionException(
						"[test-order] None of the explicitly specified changed classes exist in the "
								+ "dependency index. Check for typos or run learn mode first. " + "Changed classes: "
								+ String.join(", ", changed));
			}
		}
	}

	protected Set<String> detectChangedTestClasses() {
		return detectChangedTestClasses(true);
	}

	protected Set<String> detectChangedTestClasses(boolean readOnly) {
		return ChangeDetectionHelper.detectChangedTestClasses(ctx, changeMode,
				ChangeDetectionHelper.resolveTestSourceRoot(project, testSourceRoot),
				ctx.resolveTestHashFile(testHashFile), readOnly, getLog());
	}

	/**
	 * Detects changed test methods by comparing per-method hashes against the
	 * previous snapshot. Returns {@code className#methodName} keys.
	 */
	protected Set<String> detectChangedMethods() {
		return ChangeDetectionHelper.detectChangedMethods(
				ChangeDetectionHelper.resolveTestSourceRoot(project, testSourceRoot),
				ctx.resolveMethodHashFile(methodHashFile), getLog());
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
		Set<String> indexed;
		try {
			indexed = DependencyMap.load(idxPath).testClasses();
		} catch (Exception e) {
			getLog().debug("[test-order] Could not load index to check for new tests: " + e.getMessage());
			return Set.of();
		}
		Path testClassesDir = Path.of(project.getBuild().getTestOutputDirectory());
		if (!Files.isDirectory(testClassesDir))
			return Set.of();
		Set<String> newTests = new LinkedHashSet<>();
		try (var walk = Files.walk(testClassesDir)) {
			walk.filter(p -> p.toString().endsWith(".class") && !p.toString().contains("$")).forEach(p -> {
				String relative = testClassesDir.relativize(p).toString();
				String fqcn = relative.replace('/', '.').replace('\\', '.').replaceAll("\\.class$", "");
				if (!indexed.contains(fqcn)) {
					newTests.add(fqcn);
				}
			});
		} catch (IOException e) {
			getLog().debug("[test-order] Could not scan test-classes: " + e.getMessage());
		}
		return newTests;
	}

	protected Set<String> currentModuleTestClasses() {
		Path testClassesDir = Path.of(project.getBuild().getTestOutputDirectory());
		if (!Files.isDirectory(testClassesDir)) {
			return Set.of();
		}
		Set<String> tests = new LinkedHashSet<>();
		try (Stream<Path> walk = Files.walk(testClassesDir)) {
			walk.filter(path -> path.toString().endsWith(".class") && !path.toString().contains("$")).forEach(path -> {
				String relative = testClassesDir.relativize(path).toString();
				tests.add(relative.replace('/', '.').replace('\\', '.').replaceAll("\\.class$", ""));
			});
		} catch (IOException e) {
			getLog().debug("[test-order] Could not scan current module test-classes: " + e.getMessage());
		}
		return tests;
	}

	protected DependencyMap currentModuleDependencyMap(DependencyMap dependencyMap) {
		Set<String> moduleTests = currentModuleTestClasses();
		if (moduleTests.isEmpty()) {
			return dependencyMap;
		}
		DependencyMap filtered = new DependencyMap();
		for (String testClass : moduleTests) {
			if (dependencyMap.testClasses().contains(testClass)) {
				filtered.put(testClass, dependencyMap.get(testClass));
				if (dependencyMap.hasMethodDeps()) {
					for (String methodKey : dependencyMap.methodKeys()) {
						if (methodKey.startsWith(testClass + "#")) {
							filtered.putMethodDeps(methodKey, dependencyMap.getMethodDeps(methodKey));
							if (dependencyMap.hasMemberDeps()) {
								filtered.putMethodMemberDeps(methodKey, dependencyMap.getMethodMemberDeps(methodKey));
							}
						}
					}
				}
				if (dependencyMap.hasMemberDeps()) {
					filtered.putMemberDeps(testClass, dependencyMap.getMemberDeps(testClass));
				}
			}
		}
		return filtered;
	}

	/**
	 * Warns if the loaded dependency map has no meaningful dependencies (common
	 * with groupId/package mismatch). Detects both truly empty deps and deps that
	 * contain only framework/plugin classes.
	 */
	protected void warnIfNoDeps(DependencyMap depMap) {
		if (depMap.size() > 0) {
			boolean noAppDeps = depMap.testClasses().stream().allMatch(tc -> {
				var deps = depMap.get(tc);
				if (deps == null || deps.isEmpty())
					return true;
				// Filter out self-references and known framework classes
				return deps.stream().allMatch(dep -> dep.equals(tc) || dep.startsWith("me.bechberger.testorder.")
						|| dep.startsWith("org.junit.") || dep.startsWith("org.opentest4j."));
			});
			if (noAppDeps) {
				getLog().warn("[test-order] No application-class dependencies found in the dependency index. "
						+ "Test ordering will not be effective.");
				getLog().warn("[test-order] If your source packages differ from the Maven groupId, " + "set -D"
						+ MavenPluginConfigKeys.INCLUDE_PACKAGES + "=your.package.prefix");
			}
		}
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
		ChangeDetectionHelper.snapshotHashes(ChangeDetectionHelper.resolveSourceRoot(project, sourceRoot),
				ctx.resolveHashFile(hashFile), ChangeDetectionHelper.resolveTestSourceRoot(project, testSourceRoot),
				ctx.resolveTestHashFile(testHashFile), ctx.resolveMethodHashFile(methodHashFile), getLog());
	}

	// ── Weights ───────────────────────────────────────────────────────

	protected TestOrderState.ScoringWeights resolveWeights(TestOrderState state) {
		TestOrderState.ScoringWeights sw = state.weights();
		if (weightsFile != null && !weightsFile.isBlank()) {
			Path wf = Path.of(weightsFile);
			if (Files.exists(wf)) {
				try {
					sw = TestOrderState.ScoringWeights.loadFromFile(wf).weights();
				} catch (IOException e) {
					getLog().warn("[test-order] Failed to load weights file: " + e.getMessage());
				}
			} else {
				getLog().warn("[test-order] Weights file not found: " + wf.toAbsolutePath());
			}
		}
		return sw;
	}

	// ── Orderer config ────────────────────────────────────────────────

	/**
	 * Escapes a path string for use in a Java {@code .properties} file. Backslashes
	 * must be doubled because {@link java.util.Properties#load} treats them as
	 * escape characters (e.g. {@code \t} → tab).
	 */
	private static String escapePropertyPath(Path path) {
		return path.toAbsolutePath().toString().replace("\\", "\\\\");
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

		Path testClassesDir = Path.of(project.getBuild().getTestOutputDirectory());
		try {
			Files.createDirectories(testClassesDir);
			Path propsFile = testClassesDir.resolve("junit-platform.properties");
			try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(propsFile))) {
				pw.println("junit.jupiter.testclass.order.default=me.bechberger.testorder.PriorityClassOrderer");
			}

			Path configFile = testClassesDir.resolve("testorder-config.properties");
			try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(configFile))) {
				pw.println(
						MavenPluginConfigKeys.INDEX_PATH + "=" + escapePropertyPath(ctx.resolveIndexFile(indexFile)));
				pw.println(
						MavenPluginConfigKeys.STATE_PATH + "=" + escapePropertyPath(ctx.resolveStateFile(stateFile)));
				if (weightsFile != null && !weightsFile.isBlank()) {
					pw.println(MavenPluginConfigKeys.WEIGHTS_FILE + "=" + escapePropertyPath(Path.of(weightsFile)));
				}
				if (!changed.isEmpty()) {
					pw.println(MavenPluginConfigKeys.CHANGED_CLASSES + "=" + String.join(",", changed));
				}
				if (!changedTests.isEmpty()) {
					pw.println(MavenPluginConfigKeys.CHANGED_TEST_CLASSES + "=" + String.join(",", changedTests));
				}
				if (changedMethods != null && !changedMethods.isEmpty()) {
					pw.println(MavenPluginConfigKeys.CHANGED_METHODS + "=" + String.join(",", changedMethods));
				}
				if (scoreOverrides != null) {
					for (var e : scoreOverrides.entrySet()) {
						pw.println("testorder.score." + e.getKey() + "=" + e.getValue());
					}
				}
				if (methodOrderingEnabled) {
					pw.println(MavenPluginConfigKeys.METHOD_ORDER_ENABLED + "=true");
				}
				// Pass project root and source root for structural diff / complexity
				// computation
				pw.println(
						MavenPluginConfigKeys.PROJECT_ROOT + "=" + escapePropertyPath(project.getBasedir().toPath()));
				Path resolvedSourceRoot = ChangeDetectionHelper.resolveSourceRoot(project, sourceRoot);
				pw.println(MavenPluginConfigKeys.SOURCE_ROOT + "=" + escapePropertyPath(resolvedSourceRoot));
				pw.println(MavenPluginConfigKeys.CHANGE_MODE + "=" + changeMode);
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to write orderer config", e);
		}
	}

	protected void writeOrdererConfig(Set<String> changed, Set<String> changedTests) throws MojoExecutionException {
		writeOrdererConfig(changed, changedTests, null);
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

		StringBuilder agentArgs = new StringBuilder();
		agentArgs.append("outputDir=").append(ctx.resolveDepsDir(depsDir).toAbsolutePath());
		agentArgs.append(",mode=").append(instrumentationMode.toUpperCase());
		if (includeIndexInArgs) {
			agentArgs.append(",indexFile=").append(ctx.resolveIndexFile(indexFile).toAbsolutePath());
		}
		if (includePackages != null) {
			agentArgs.append(",includePackages=").append(includePackages.replace(",", ";"));
		}
		if (verboseFile != null && !verboseFile.isEmpty()) {
			agentArgs.append(",verboseFile=").append(Path.of(verboseFile).toAbsolutePath());
		}

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
			getLog().info("[test-order] Surefire <argLine> is hardcoded; injecting agent "
					+ "via maven.surefire.debug user property.");
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
		List<String> prefixes = new ArrayList<>();

		// 1. Always scan source root for actual packages
		List<String> sourcePackages = detectSourcePackages(project, log);
		prefixes.addAll(sourcePackages);

		// 2. Add user-specified packages (additive)
		if (includePackages != null && !includePackages.isBlank()) {
			for (String pkg : includePackages.split(",")) {
				String trimmed = pkg.trim();
				if (!trimmed.isEmpty())
					prefixes.add(trimmed);
			}
		}

		// 3. Fall back to groupId only when nothing else was detected/configured.
		if (filterByGroupId && prefixes.isEmpty()) {
			String groupId = project.getGroupId();
			if (groupId != null && !groupId.isBlank()) {
				prefixes.add(groupId);
			}
		}

		if (prefixes.isEmpty())
			return null;

		// Remove redundant prefixes (a prefix covered by a shorter one)
		List<String> minimal = minimisePrefixes(prefixes);
		String result = String.join(",", minimal);
		log.info("[test-order] Instrumentation packages: " + result);
		return result;
	}

	/** Remove prefixes that are already covered by a shorter prefix in the list. */
	static List<String> minimisePrefixes(List<String> prefixes) {
		List<String> sorted = prefixes.stream().distinct().sorted().toList();
		List<String> result = new ArrayList<>();
		for (String p : sorted) {
			boolean covered = result.stream().anyMatch(r -> p.startsWith(r + ".") || p.equals(r));
			if (!covered)
				result.add(p);
		}
		return result;
	}

	/**
	 * Scans the main source root(s) to detect top-level package prefixes. Walks
	 * down single-child directories to find a stable package prefix (e.g.
	 * {@code src/main/java/com/example/app} → {@code com.example.app}).
	 */
	static List<String> detectSourcePackages(MavenProject project, Log log) {
		List<String> result = new ArrayList<>();
		Path sourceRoot = ChangeDetectionHelper.resolveSourceRoot(project, null);
		scanPackagePrefixes(sourceRoot, result, log);
		return result;
	}

	/**
	 * Scans a root directory for top-level package prefixes, walking down
	 * single-child directory chains until a branching point or .java files are
	 * found.
	 */
	private static void scanPackagePrefixes(Path root, List<String> result, Log log) {
		if (!Files.isDirectory(root))
			return;
		try (Stream<Path> topDirs = Files.list(root)) {
			topDirs.filter(Files::isDirectory).forEach(dir -> {
				String topPkg = dir.getFileName().toString();
				Path current = dir;
				StringBuilder pkg = new StringBuilder(topPkg);
				while (true) {
					try (Stream<Path> children = Files.list(current)) {
						List<Path> childDirs = children.filter(Files::isDirectory).toList();
						boolean hasJavaFiles;
						try (Stream<Path> files = Files.list(current)) {
							hasJavaFiles = files.anyMatch(f -> f.toString().endsWith(".java"));
						}
						if (childDirs.size() == 1 && !hasJavaFiles) {
							current = childDirs.get(0);
							pkg.append('.').append(current.getFileName().toString());
						} else {
							break;
						}
					} catch (IOException e) {
						break;
					}
				}
				result.add(pkg.toString());
			});
		} catch (IOException e) {
			log.debug("[test-order] Failed to scan source root " + root + ": " + e.getMessage());
		}
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
			if (Files.exists(serviceFile)) {
				String existing = Files.readString(serviceFile);
				if (existing.contains(listenerFqcn)) {
					return;
				}
				// append to existing file (user may have their own listeners)
				Files.writeString(serviceFile, existing.stripTrailing() + "\n" + listenerFqcn + "\n");
			} else {
				Files.writeString(serviceFile, listenerFqcn + "\n");
			}
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
}
