package me.bechberger.testorder.maven;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import me.bechberger.testorder.ops.JUnitPlatformValidator;

/**
 * Shared utility methods for locating and manipulating the Surefire plugin
 * configuration.
 */
final class SurefireHelper {

	private SurefireHelper() {
	}

	static Plugin findSurefirePlugin(MavenProject project) {
		for (Plugin plugin : project.getBuildPlugins()) {
			if ("maven-surefire-plugin".equals(plugin.getArtifactId())
					&& "org.apache.maven.plugins".equals(plugin.getGroupId())) {
				return plugin;
			}
		}
		// Fall back to Failsafe for integration-test projects that don't use Surefire
		for (Plugin plugin : project.getBuildPlugins()) {
			if ("maven-failsafe-plugin".equals(plugin.getArtifactId())
					&& "org.apache.maven.plugins".equals(plugin.getGroupId())) {
				return plugin;
			}
		}
		return null;
	}

	/**
	 * Returns all test-execution plugins (Surefire and Failsafe) so validation can
	 * be applied to both when they coexist.
	 */
	static List<Plugin> findAllTestPlugins(MavenProject project) {
		List<Plugin> result = new ArrayList<>();
		for (Plugin plugin : project.getBuildPlugins()) {
			String aid = plugin.getArtifactId();
			if (("maven-surefire-plugin".equals(aid) || "maven-failsafe-plugin".equals(aid))
					&& "org.apache.maven.plugins".equals(plugin.getGroupId())) {
				result.add(plugin);
			}
		}
		return result;
	}

	static Plugin requireSurefirePlugin(MavenProject project) throws MojoExecutionException {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null) {
			throw new MojoExecutionException(
					"[test-order] maven-surefire-plugin or maven-failsafe-plugin not found in project '"
							+ project.getArtifactId() + "'. "
							+ "test-order requires Surefire (or Failsafe) to configure test execution. "
							+ "Add maven-surefire-plugin to your <build><plugins> section.");
		}
		return surefire;
	}

	/**
	 * Warns if the Surefire/Failsafe version is older than 3.0, which lacks JUnit
	 * Platform support required by test-order.
	 */
	static void warnOldSurefireVersion(Plugin surefire, Log log) {
		String version = surefire.getVersion();
		if (version == null || version.isEmpty()) {
			log.debug("[test-order] " + surefire.getArtifactId()
					+ " version is managed (not explicitly set) — skipping version check.");
			return;
		}
		try {
			int major = Integer.parseInt(version.split("[^0-9]")[0]);
			if (major < 3) {
				log.warn("[test-order] " + surefire.getArtifactId() + " version " + version
						+ " is older than 3.0. test-order requires Surefire >= 3.0 for JUnit Platform support. "
						+ "Please upgrade to at least 3.2.5.");
			}
		} catch (NumberFormatException ignored) {
			// Non-standard version string, skip
		}
	}

	static Xpp3Dom getOrCreateConfiguration(Plugin plugin) {
		Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
		if (config == null) {
			config = new Xpp3Dom("configuration");
			plugin.setConfiguration(config);
		}
		return config;
	}

	static Xpp3Dom getOrCreateChild(Xpp3Dom parent, String name) {
		Xpp3Dom child = parent.getChild(name);
		if (child == null) {
			child = new Xpp3Dom(name);
			parent.addChild(child);
		}
		return child;
	}

	static void setChild(Xpp3Dom parent, String name, String value) {
		Xpp3Dom child = parent.getChild(name);
		if (child == null) {
			child = new Xpp3Dom(name);
			parent.addChild(child);
		}
		child.setValue(value);
	}

	static void addChild(Xpp3Dom parent, String name, String value) {
		Xpp3Dom child = new Xpp3Dom(name);
		child.setValue(value);
		parent.addChild(child);
	}

	/**
	 * Warns when parallel execution is enabled in order mode. Parallel execution
	 * undermines test ordering guarantees: JUnit Platform sorts but does not
	 * guarantee start order when parallelism is active. Delegates common checks to
	 * {@link JUnitPlatformValidator}, adds Surefire-specific {@code <parallel>}
	 * element check.
	 */
	static void validateNoClassLevelParallel(MavenProject project, Log log) throws MojoExecutionException {
		List<Plugin> testPlugins = findAllTestPlugins(project);
		if (testPlugins.isEmpty()) {
			log.debug("[test-order] Surefire class-level parallel mode check passed.");
			return;
		}
		for (Plugin surefire : testPlugins) {
			validateNoClassLevelParallelForPlugin(surefire, log);
		}
	}

	private static void validateNoClassLevelParallelForPlugin(Plugin surefire, Log log) {
		Xpp3Dom config = getOrCreateConfiguration(surefire);
		List<String> parallelSources = new ArrayList<>();

		// Check Surefire's <parallel> (only applies to JUnit 4.7 and TestNG providers,
		// but warn anyway)
		String surefireParallel = childValue(config, "parallel");
		if (isClassLevelSurefireParallel(surefireParallel)) {
			parallelSources.add("<parallel>" + surefireParallel + "</parallel>");
		}

		// Delegate JUnit Platform property checks to shared validator
		Map<String, String> resolvedProps = extractSystemPropertyVariables(config);
		String configurationParameters = childValue(child(child(config, "properties"), "configurationParameters"));
		JUnitPlatformValidator validator = new JUnitPlatformValidator(MavenPluginLog.wrap(log));
		JUnitPlatformValidator.ParallelCheckResult result = validator.detectParallelExecution(resolvedProps,
				configurationParameters);

		parallelSources.addAll(result.classLevelParallelSources());

		// Emit a single consolidated warning for class-level parallel
		if (!parallelSources.isEmpty()) {
			log.warn("[test-order] Class-level parallel execution detected (" + String.join(", ", parallelSources)
					+ ") — test ordering guarantees are weakened. "
					+ "Tests will be sorted but may not start in priority order.");
		}
		if (result.vintageParallel()) {
			log.warn("[test-order] JUnit Vintage parallel execution detected — "
					+ "Vintage tests will not respect test-order's ordering.");
		}

		if (parallelSources.isEmpty() && !result.vintageParallel()) {
			log.debug("[test-order] Surefire class-level parallel mode check passed.");
		}
	}

	/**
	 * Fails when class-level parallel execution is enabled and we're in learn mode.
	 * Parallel class execution during learn mode corrupts dependency tracking
	 * because concurrent class loading blurs which test triggered which dependency.
	 * Uses {@link JUnitPlatformValidator} for configurationParameters parsing.
	 */
	static void rejectClassLevelParallelForLearn(MavenProject project, Log log) throws MojoExecutionException {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null)
			return;
		Xpp3Dom config = getOrCreateConfiguration(surefire);

		String surefireParallel = childValue(config, "parallel");
		if (isClassLevelSurefireParallel(surefireParallel)) {
			throw new MojoExecutionException("Class-level parallel execution (<parallel>" + surefireParallel
					+ "</parallel>) is not supported in learn mode — " + "it would corrupt dependency tracking. "
					+ "Remove or change to <parallel>methods</parallel> during learn runs.");
		}

		String classesDefaultFromSysProps = childValue(child(config, "systemPropertyVariables"),
				"junit.jupiter.execution.parallel.mode.classes.default");
		if (isConcurrent(classesDefaultFromSysProps)) {
			throw new MojoExecutionException("JUnit class-level parallel mode (mode.classes.default=concurrent) "
					+ "is not supported in learn mode — it would corrupt dependency tracking. "
					+ "Use same_thread or remove it during learn runs.");
		}

		String configurationParameters = childValue(child(child(config, "properties"), "configurationParameters"));
		if (JUnitPlatformValidator.isClassLevelConcurrentInConfigurationParameters(configurationParameters)) {
			throw new MojoExecutionException("JUnit class-level parallel mode in <configurationParameters> "
					+ "is not supported in learn mode — it would corrupt dependency tracking. "
					+ "Use same_thread or remove mode.classes.default=concurrent during learn runs.");
		}

		// Also reject Vintage parallel in learn mode (M24)
		String vintageParallel = childValue(child(config, "systemPropertyVariables"),
				"junit.vintage.execution.parallel.enabled");
		if ("true".equalsIgnoreCase(vintageParallel != null ? vintageParallel.trim() : null)) {
			throw new MojoExecutionException(
					"JUnit Vintage parallel execution (junit.vintage.execution.parallel.enabled=true) "
							+ "is not supported in learn mode — it would corrupt dependency tracking.");
		}
		if (JUnitPlatformValidator.isVintageParallelInConfigurationParameters(configurationParameters)) {
			throw new MojoExecutionException("JUnit Vintage parallel execution in <configurationParameters> "
					+ "is not supported in learn mode — it would corrupt dependency tracking.");
		}

		log.debug("[test-order] Learn-mode parallel check passed.");
	}

	private static boolean isClassLevelSurefireParallel(String value) {
		if (value == null) {
			return false;
		}
		String v = value.trim().toLowerCase();
		return v.equals("all") || v.equals("both") || v.contains("class");
	}

	private static boolean isConcurrent(String value) {
		return value != null && "concurrent".equalsIgnoreCase(value.trim());
	}

	/**
	 * Warns when {@code junit.platform.execution.listeners.deactivate} is
	 * configured in a way that could disable test-order's TelemetryListener (M4).
	 * Delegates to {@link JUnitPlatformValidator}.
	 */
	static void warnListenerDeactivation(MavenProject project, Log log) {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null)
			return;
		Xpp3Dom config = getOrCreateConfiguration(surefire);
		Map<String, String> resolvedProps = extractSystemPropertyVariables(config);
		String configParams = childValue(child(child(config, "properties"), "configurationParameters"));
		JUnitPlatformValidator validator = new JUnitPlatformValidator(MavenPluginLog.wrap(log));
		validator.warnListenerDeactivation(resolvedProps, configParams);
	}

	/**
	 * Warns when a conflicting ClassOrderer or MethodOrderer is configured globally
	 * via JUnit Platform config, which would override test-order's orderers (M12,
	 * M20). Delegates to {@link JUnitPlatformValidator}.
	 */
	static void warnConflictingOrderers(MavenProject project, Log log) {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null)
			return;
		Xpp3Dom config = getOrCreateConfiguration(surefire);
		Map<String, String> resolvedProps = extractSystemPropertyVariables(config);
		String configParams = childValue(child(child(config, "properties"), "configurationParameters"));
		JUnitPlatformValidator validator = new JUnitPlatformValidator(MavenPluginLog.wrap(log));
		validator.warnConflictingOrderers(resolvedProps, configParams);
	}

	private static Xpp3Dom child(Xpp3Dom parent, String name) {
		return parent == null ? null : parent.getChild(name);
	}

	private static String childValue(Xpp3Dom parent, String name) {
		Xpp3Dom c = child(parent, name);
		return c == null ? null : c.getValue();
	}

	private static String childValue(Xpp3Dom node) {
		return node == null ? null : node.getValue();
	}

	/**
	 * Extracts all children of {@code <systemPropertyVariables>} as a
	 * {@code Map<String, String>}. Used to bridge Surefire XML to the shared
	 * {@link JUnitPlatformValidator}.
	 */
	static Map<String, String> extractSystemPropertyVariables(Xpp3Dom config) {
		Map<String, String> props = new LinkedHashMap<>();
		Xpp3Dom sysProps = child(config, "systemPropertyVariables");
		if (sysProps != null) {
			for (Xpp3Dom childNode : sysProps.getChildren()) {
				String value = childNode.getValue();
				if (value != null) {
					props.put(childNode.getName(), value);
				}
			}
		}
		return props;
	}

	/**
	 * Returns true when the argLine value is a hardcoded literal that does not
	 * contain a Maven property placeholder ({@code ${argLine}} or
	 * {@code @{argLine}}) and does not already contain the test-order agent. In
	 * this case the plugin must inject directly into the Xpp3Dom node instead of
	 * relying on property expansion.
	 */
	static boolean isHardcodedArgLine(String argLine) {
		if (argLine == null || argLine.isBlank())
			return false;
		return !argLine.contains("${argLine}") && !argLine.contains("@{argLine}")
				&& !argLine.contains("test-order-agent");
	}

	/**
	 * Extracts existing {@code <additionalClasspathElements>} from the Surefire (or
	 * Failsafe) XML configuration. These need to be preserved when the
	 * {@code maven.test.additionalClasspath} property is set, because the property
	 * overrides the XML value.
	 */
	static List<String> extractAdditionalClasspathElements(MavenProject project) {
		List<String> result = new ArrayList<>();
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null) {
			return result;
		}
		Xpp3Dom config = (Xpp3Dom) surefire.getConfiguration();
		if (config == null) {
			return result;
		}
		Xpp3Dom elements = config.getChild("additionalClasspathElements");
		if (elements == null) {
			return result;
		}
		for (Xpp3Dom child : elements.getChildren()) {
			String value = child.getValue();
			if (value != null && !value.isBlank()) {
				result.add(value.trim());
			}
		}
		return result;
	}

	/**
	 * Warns when Surefire's {@code <runOrder>} conflicts with test-order's
	 * {@code PriorityClassOrderer}. Surefire applies {@code runOrder} before JUnit
	 * Platform's ClassOrderer, so a non-default value like {@code random} or
	 * {@code failedfirst} partially overrides test-order's ordering.
	 */
	static void warnConflictingRunOrder(MavenProject project, Log log) {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null)
			return;
		Xpp3Dom config = (Xpp3Dom) surefire.getConfiguration();
		if (config == null)
			return;
		String runOrder = childValue(config, "runOrder");
		if (runOrder != null && !runOrder.isBlank() && !"filesystem".equalsIgnoreCase(runOrder.trim())) {
			log.warn("[test-order] Surefire <runOrder>" + runOrder
					+ "</runOrder> conflicts with test-order's PriorityClassOrderer. "
					+ "Surefire applies runOrder before JUnit Platform ordering — test-order's priority "
					+ "ordering may be partially overridden. Remove <runOrder> or set it to 'filesystem'.");
		}
	}

	/**
	 * Warns when {@code forkCount > 1} in learn mode. Multiple parallel forks write
	 * to the same {@code .deps/} directory concurrently, which can corrupt
	 * dependency data. In order mode, each fork gets its own ClassOrderer instance,
	 * so global ordering is weakened but not broken.
	 */
	static void warnForkCountInLearnMode(MavenProject project, Log log) {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null)
			return;
		Xpp3Dom config = (Xpp3Dom) surefire.getConfiguration();
		if (config == null)
			return;
		String forkCount = childValue(config, "forkCount");
		if (forkCount == null || forkCount.isBlank())
			return;
		// Parse numeric value (may end with "C" for core-multiplied)
		String numPart = forkCount.trim().replaceAll("[Cc]$", "");
		try {
			double value = Double.parseDouble(numPart);
			if (value > 1 || (forkCount.trim().endsWith("C") && value > 0)) {
			log.debug("[test-order] Surefire <forkCount>" + forkCount
						+ "</forkCount> — multiple forks may write .deps files concurrently in learn mode. "
						+ "This can corrupt the dependency index. Consider using forkCount=1 for learn runs.");
			}
		} catch (NumberFormatException ignored) {
			// Non-standard forkCount, skip
		}
	}

	/**
	 * Warns when {@code forkCount > 1} in order mode. Each fork gets a separate
	 * ClassOrderer instance, so class-level ordering within each fork is correct
	 * but cross-fork ordering is not guaranteed.
	 */
	static void warnForkCountInOrderMode(MavenProject project, Log log) {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null)
			return;
		Xpp3Dom config = (Xpp3Dom) surefire.getConfiguration();
		if (config == null)
			return;
		String forkCount = childValue(config, "forkCount");
		if (forkCount == null || forkCount.isBlank())
			return;
		String numPart = forkCount.trim().replaceAll("[Cc]$", "");
		try {
			double value = Double.parseDouble(numPart);
			if (value > 1 || (forkCount.trim().endsWith("C") && value > 0)) {
				log.warn("[test-order] Surefire <forkCount>" + forkCount
						+ "</forkCount> — each fork orders classes independently. "
						+ "Global fail-first ordering is weakened across forks.");
			}
		} catch (NumberFormatException ignored) {
			// Non-standard forkCount, skip
		}
	}

	/**
	 * Warns when {@code reuseForks=false} in learn mode. Each test class runs in a
	 * fresh JVM. Dependency data is written to individual {@code .deps} files on
	 * JVM exit, so collection still works correctly — but spawning a new JVM per
	 * test class significantly increases total learn-phase duration.
	 */
	static void warnReuseForksFalseInLearnMode(MavenProject project, Log log) {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null)
			return;
		Xpp3Dom config = (Xpp3Dom) surefire.getConfiguration();
		if (config == null)
			return;
		String reuseForks = childValue(config, "reuseForks");
		if ("false".equalsIgnoreCase(reuseForks != null ? reuseForks.trim() : null)) {
			log.debug("[test-order] Surefire <reuseForks>false</reuseForks> — "
					+ "each test class runs in a new JVM. Learn mode works correctly but is significantly slower "
					+ "due to per-class JVM startup overhead. Consider setting "
					+ "<reuseForks>true</reuseForks> to speed up the learn phase.");
		}
	}

	/**
	 * Warns when {@code rerunFailingTestsCount > 0} in learn mode. Surefire reruns
	 * failing tests, and the agent records dependencies for both the failing and
	 * passing runs. This creates duplicate/inconsistent entries in the .deps files.
	 */
	static void warnRerunFailingTestsInLearnMode(MavenProject project, Log log) {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null)
			return;
		Xpp3Dom config = (Xpp3Dom) surefire.getConfiguration();
		if (config == null)
			return;
		String rerunCount = childValue(config, "rerunFailingTestsCount");
		if (rerunCount != null && !rerunCount.isBlank()) {
			try {
				int count = Integer.parseInt(rerunCount.trim());
				if (count > 0) {
					log.warn("[test-order] Surefire <rerunFailingTestsCount>" + count
							+ "</rerunFailingTestsCount> — re-run failures may produce duplicate "
							+ "dependency entries in learn mode. The dependency index will still "
							+ "be usable but may contain redundant data.");
				}
			} catch (NumberFormatException ignored) {
				// Non-standard value, skip
			}
		}
	}

	/**
	 * Forces {@code <useModulePath>false</useModulePath>} when test-order injects
	 * classpath entries. JPMS module path mode ignores
	 * {@code maven.test.additionalClasspath}, so test-order JARs would not be
	 * visible to the forked JVM.
	 */
	static void forceClasspathModeIfNeeded(MavenProject project, Log log) {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null)
			return;
		Xpp3Dom config = getOrCreateConfiguration(surefire);
		String useModulePath = childValue(config, "useModulePath");
		// Only force if not already explicitly set to false
		if (useModulePath == null || "true".equalsIgnoreCase(useModulePath.trim())) {
			// Check if project actually uses module-info.java
			java.io.File moduleInfo = new java.io.File(project.getBasedir(), "src/main/java/module-info.java");
			java.io.File testModuleInfo = new java.io.File(project.getBasedir(), "src/test/java/module-info.java");
			if (moduleInfo.exists() || testModuleInfo.exists()) {
				log.info("[test-order] JPMS module-info.java detected — forcing useModulePath=false "
						+ "to ensure test-order classpath injection works.");
				setChild(config, "useModulePath", "false");
			}
		}
	}

	/**
	 * Warns when Surefire has {@code <groups>}, {@code <excludedGroups>},
	 * {@code <includes>}, or {@code <excludes>} configured while test-order is in
	 * select mode. These filters interact with test-order's selection:
	 * <ul>
	 * <li>{@code <groups>/<excludedGroups>} (JUnit 5 tags) still apply alongside
	 * {@code <test>}, so tests selected by test-order may be silently skipped by
	 * Surefire's tag filter.</li>
	 * <li>{@code <includes>/<excludes>} are overridden by {@code <test>}, so
	 * test-order may run classes the user explicitly excluded.</li>
	 * </ul>
	 * Call this once before {@link #configureIncludes} in select-mode paths.
	 */
	static void warnSelectModeFilters(MavenProject project, Log log) {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null)
			return;
		Xpp3Dom config = (Xpp3Dom) surefire.getConfiguration();
		if (config == null)
			return;

		String groups = childValue(config, "groups");
		if (groups != null && !groups.isBlank()) {
			log.warn("[test-order] Surefire <groups>" + groups
					+ "</groups> is configured — Surefire's tag filter still applies alongside "
					+ "test-order's selection. Selected tests without matching tags will be skipped by Surefire.");
		}
		String excludedGroups = childValue(config, "excludedGroups");
		if (excludedGroups != null && !excludedGroups.isBlank()) {
			log.warn("[test-order] Surefire <excludedGroups>" + excludedGroups
					+ "</excludedGroups> is configured — tests matching this tag filter will be "
					+ "skipped even if selected by test-order.");
		}
		Xpp3Dom excludes = config.getChild("excludes");
		if (excludes != null && excludes.getChildCount() > 0) {
			StringBuilder patterns = new StringBuilder();
			for (int i = 0; i < excludes.getChildCount(); i++) {
				if (i > 0)
					patterns.append(", ");
				patterns.append(excludes.getChild(i).getValue());
			}
			log.warn("[test-order] Surefire <excludes> is configured (" + patterns
					+ ") but test-order's select mode overrides it via the <test> parameter. "
					+ "File-based exclusions of non-test helpers are usually harmless. "
					+ "Tag-based filtering should use <excludedGroups> (JUnit 5 @Tag) for consistent behaviour.");
		}
	}

	/**
	 * Configures Surefire to run only the given test class FQCNs by setting the
	 * {@code test} property on both the plugin configuration and the project
	 * properties. This maps to Surefire's {@code -Dtest=...} parameter which is
	 * always honored.
	 *
	 * @param clearExisting
	 *            if true, replaces any previous test filter; if false, appends
	 */
	static void configureIncludes(MavenProject project, List<String> tests, boolean clearExisting)
			throws MojoExecutionException {
		if (tests.isEmpty())
			return;

		// Normalize inner/nested class names (e.g. OuterTest$InnerTests) to their
		// top-level enclosing class. Surefire's -Dtest parameter does not support
		// the '$' inner-class notation; @Nested classes run as part of their outer
		// class when Surefire includes the outer class in the test run.
		java.util.LinkedHashSet<String> normalizedTests = new java.util.LinkedHashSet<>();
		for (String tc : tests) {
			int dollar = tc.indexOf('$');
			normalizedTests.add(dollar > 0 ? tc.substring(0, dollar) : tc);
		}

		// Build comma-separated list of FQCNs for Surefire's test parameter
		StringBuilder testParam = new StringBuilder();
		for (String tc : normalizedTests) {
			if (!testParam.isEmpty())
				testParam.append(",");
			testParam.append(tc);
		}

		// Set via project property (Surefire picks this up as -Dtest=...)
		String existing = clearExisting ? null : project.getProperties().getProperty("test");
		if (existing != null && !existing.isBlank()) {
			project.getProperties().setProperty("test", existing + "," + testParam);
		} else {
			project.getProperties().setProperty("test", testParam.toString());
		}

		// Also set in Surefire plugin configuration for completeness
		Plugin surefire = requireSurefirePlugin(project);
		Xpp3Dom config = getOrCreateConfiguration(surefire);
		setChild(config, "test", project.getProperties().getProperty("test"));
	}
}
