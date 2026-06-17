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
			String[] parts = version.split("[^0-9]+");
			if (parts.length == 0 || parts[0].isEmpty()) {
				log.debug("[test-order] Could not parse version: " + version);
				return;
			}
			int major = Integer.parseInt(parts[0]);
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

		// Allow the user to override via -Dparallel=none (mirrors the Surefire
		// property)
		// or -Dtestorder.learn.allowParallel=true. Downgrade hard errors to warnings so
		// third-party repos with class-level parallel can still be profiled.
		// Preferred source: Maven project/session properties (set via -D on the Maven
		// command line). Fall back to System.getProperty for non-Maven invocations.
		java.util.Properties projectProps = project.getProperties();
		String parallelProp = projectProps != null ? projectProps.getProperty("parallel") : null;
		if (parallelProp == null) {
			parallelProp = System.getProperty("parallel");
		}
		String allowParallelProp = projectProps != null
				? projectProps.getProperty("testorder.learn.allowParallel")
				: null;
		if (allowParallelProp == null) {
			allowParallelProp = System.getProperty("testorder.learn.allowParallel");
		}
		boolean parallelOverridden = "none".equalsIgnoreCase(parallelProp)
				|| "true".equalsIgnoreCase(allowParallelProp);

		String surefireParallel = childValue(config, "parallel");
		if (isClassLevelSurefireParallel(surefireParallel)) {
			String msg = "Class-level parallel execution (<parallel>" + surefireParallel
					+ "</parallel>) is not supported in learn mode — it would corrupt dependency tracking. "
					+ "Remove or change to <parallel>methods</parallel> during learn runs.";
			if (parallelOverridden) {
				log.warn("[test-order] " + msg + " Proceeding anyway because -Dparallel=none or "
						+ "-Dtestorder.learn.allowParallel=true was set.");
			} else {
				throw new MojoExecutionException(msg);
			}
		}

		String classesDefaultFromSysProps = childValue(child(config, "systemPropertyVariables"),
				"junit.jupiter.execution.parallel.mode.classes.default");
		if (isConcurrent(classesDefaultFromSysProps)) {
			String msg = "JUnit class-level parallel mode (mode.classes.default=concurrent) "
					+ "is not supported in learn mode — it would corrupt dependency tracking. "
					+ "Use same_thread or remove it during learn runs.";
			if (parallelOverridden) {
				log.warn("[test-order] " + msg + " Proceeding anyway because override is set.");
			} else {
				throw new MojoExecutionException(msg);
			}
		}

		String configurationParameters = childValue(child(child(config, "properties"), "configurationParameters"));
		if (JUnitPlatformValidator.isClassLevelConcurrentInConfigurationParameters(configurationParameters)) {
			String msg = "JUnit class-level parallel mode in <configurationParameters> "
					+ "is not supported in learn mode — it would corrupt dependency tracking. "
					+ "Use same_thread or remove mode.classes.default=concurrent during learn runs.";
			if (parallelOverridden) {
				log.warn("[test-order] " + msg + " Proceeding anyway because override is set.");
			} else {
				throw new MojoExecutionException(msg);
			}
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
		// Any ${...} or @{...} placeholder means the value is property-expanded at
		// fork time — not hardcoded. This covers @{argLine}, ${argLine},
		// ${jacoco.agent.argLine}, and any other plugin-managed property.
		return !argLine.contains("${") && !argLine.contains("@{") && !argLine.contains("test-order-agent");
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
	 * Logs when {@code forkCount > 1} in learn mode. In online
	 * (IndexCollectorServer) mode all forks stream data to a single server so there
	 * is no file-level concurrency concern. In offline mode each fork writes to its
	 * own {@code .deps} file; the aggregator merges them safely. No corruption risk
	 * in either case; a debug message is enough so users know multiple forks are
	 * active.
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
						+ "</forkCount> — multiple forks active in learn mode. "
						+ "Each fork streams data to the IndexCollectorServer; dependency tracking is unaffected.");
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
	 * <p>
	 * For JPMS patch-module builds where the test module has a {@code module-info}
	 * that the JVM still loads even in classpath mode (due to pre-existing
	 * {@code --add-exports/--add-opens} flags in argLine), also injects
	 * {@code --add-reads <module>=ALL-UNNAMED} so the test-order runtime (on the
	 * unnamed classpath) is accessible.
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
				// Surefire 3.x reads ${surefire.useModulePath} at fork time; set it here
				// so the property binding resolves before the fork is launched.
				project.getProperties().setProperty("surefire.useModulePath", "false");

				// Inject --add-reads for any named module referenced in argLine's
				// --add-exports/--add-opens flags. When useModulePath=false but the surefire
				// argLine contains --add-exports/--add-opens with module names, the JVM still
				// loads those modules from classpath JARs that have module-info.class. The
				// test-order runtime is on the unnamed classpath and therefore invisible to
				// those named modules unless we add --add-reads <module>=ALL-UNNAMED.
				String existingArgLine = childValue(config, "argLine");
				if (existingArgLine != null
						&& (existingArgLine.contains("--add-exports") || existingArgLine.contains("--add-opens"))) {
					java.util.Set<String> moduleNames = extractModuleNamesFromArgLine(existingArgLine);
					if (!moduleNames.isEmpty()) {
						String runtimeModuleName = "test.order.runtime";
						java.util.StringJoiner sj = new java.util.StringJoiner(" ");
						for (String m : moduleNames) {
							if (!m.equals("ALL-UNNAMED")) {
								sj.add("--add-reads " + m + "=ALL-UNNAMED");
								sj.add("--add-reads " + m + "=" + runtimeModuleName);
							}
						}
						String addReads = sj.toString();
						if (!addReads.isEmpty()) {
							String newArgLine = (existingArgLine.trim().isEmpty() ? "" : existingArgLine + " ")
									+ addReads;
							setChild(config, "argLine", newArgLine);
							log.info("[test-order] JPMS patch-module build — injected " + addReads
									+ " so named module(s) can read the test-order runtime.");
						}
					}
				}
			}
		}
	}

	/**
	 * Extracts module names from the "target" side of {@code --add-exports} and
	 * {@code --add-opens} flags in an argLine string. Returns only the module names
	 * that appear as the source (left-hand) side, i.e. the module whose packages
	 * are being opened — these are the modules that may need
	 * {@code --add-reads=ALL-UNNAMED} to access unnamed-classpath code.
	 *
	 * <p>
	 * Example:
	 * {@code --add-opens tools.jackson.core/pkg=tools.jackson.core.unittest} →
	 * extracts {@code tools.jackson.core.unittest} (the module doing the reading).
	 */
	private static java.util.Set<String> extractModuleNamesFromArgLine(String argLine) {
		java.util.Set<String> result = new java.util.LinkedHashSet<>();
		// Match --add-exports/--add-opens/--add-reads flags: format is
		// --add-exports module/package=target or --add-opens module/package=target
		java.util.regex.Pattern p = java.util.regex.Pattern
				.compile("--(?:add-exports|add-opens)\\s+[\\w.]+/[\\w.]+=(\\S+)");
		java.util.regex.Matcher m = p.matcher(argLine);
		while (m.find()) {
			String target = m.group(1);
			// Skip wildcard and ALL-UNNAMED — only add named modules
			if (!target.equals("ALL-UNNAMED") && !target.contains(",")) {
				result.add(target);
			}
		}
		return result;
	}

	/**
	 * Warns when Surefire has {@code <groups>}, {@code <excludedGroups>},
	 * {@code <includes>}, or {@code <excludes>} configured while test-order is in
	 * affected mode. These filters interact with test-order's selection:
	 * <ul>
	 * <li>{@code <groups>/<excludedGroups>} (JUnit 5 tags) still apply alongside
	 * {@code <test>}, so tests selected by test-order may be silently skipped by
	 * Surefire's tag filter.</li>
	 * <li>{@code <includes>/<excludes>} are overridden by {@code <test>}, so
	 * test-order may run classes the user explicitly excluded.</li>
	 * </ul>
	 * Call this once before {@link #configureIncludes} in affected-mode paths.
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
				String val = excludes.getChild(i).getValue();
				if (val == null || val.isBlank())
					continue;
				if (patterns.length() > 0)
					patterns.append(", ");
				patterns.append(val);
			}
			log.warn("[test-order] Surefire <excludes> is configured (" + patterns
					+ ") but test-order's affected mode overrides it via the <test> parameter. "
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
	/**
	 * Forces Surefire into single-fork, reuse-forks mode so the JUnit Platform
	 * {@code ClassOrderer} can actually reorder selected classes. Without this, a
	 * project with {@code forkCount>1} or {@code reuseForks=false} runs each class
	 * in a fresh JVM, defeating any priority order the plugin computed.
	 * <p>
	 * Always overrides existing values — when the user invokes
	 * {@code test-order:affected} they are opting into the plugin rewriting
	 * Surefire's test-execution config. A WARN names the previous value so the
	 * change is visible. Set {@code testorder.affected.preserveForkConfig=true} to
	 * skip the override.
	 */
	static void forceSingleForkForOrdering(MavenProject project, Log log) throws MojoExecutionException {
		if (Boolean.parseBoolean(project.getProperties().getProperty("testorder.affected.preserveForkConfig", "false"))
				|| Boolean.getBoolean("testorder.affected.preserveForkConfig")) {
			log.info("[test-order] testorder.affected.preserveForkConfig=true — leaving Surefire forkCount/reuseForks"
					+ " untouched. Selected tests may not execute in priority order if forkCount>1 or reuseForks=false.");
			return;
		}
		Plugin surefire = requireSurefirePlugin(project);
		Xpp3Dom config = getOrCreateConfiguration(surefire);

		String forkCount = childValue(config, "forkCount");
		String reuseForks = childValue(config, "reuseForks");

		boolean changedFork = forkCount != null && !"1".equals(forkCount);
		boolean changedReuse = reuseForks != null && !"true".equalsIgnoreCase(reuseForks);

		setChild(config, "forkCount", "1");
		setChild(config, "reuseForks", "true");

		if (changedFork || changedReuse) {
			StringBuilder msg = new StringBuilder("[test-order] Overriding Surefire");
			if (changedFork) {
				msg.append(" forkCount=").append(forkCount == null || forkCount.isBlank() ? "<unset>" : forkCount)
						.append("→1");
			}
			if (changedReuse) {
				if (changedFork)
					msg.append(",");
				msg.append(" reuseForks=").append(reuseForks == null || reuseForks.isBlank() ? "<unset>" : reuseForks)
						.append("→true");
			}
			msg.append(" so PriorityClassOrderer can reorder selected classes within one JVM.");
			msg.append(" Set -Dtestorder.affected.preserveForkConfig=true to keep your config.");
			log.info(msg.toString());
		}
	}

	/**
	 * Builds {@code --add-reads} flags for JPMS patch-module projects.
	 *
	 * <p>
	 * When a project uses {@code module-info.java} and Surefire's argLine contains
	 * {@code --add-exports/--add-opens} with named modules, those modules are still
	 * loaded from the module path. Test-order injects its runtime on the classpath
	 * via {@code maven.test.additionalClasspath}. Depending on whether
	 * {@code useModulePath} takes effect, the runtime jar may end up as:
	 * <ul>
	 * <li>The unnamed module (classpath mode) — needs
	 * {@code --add-reads X=ALL-UNNAMED}</li>
	 * <li>Automatic named module {@code test.order.runtime} (module path) — needs
	 * {@code --add-reads X=test.order.runtime}</li>
	 * </ul>
	 * Both flags are injected so the fix works regardless of which mode Surefire
	 * uses. {@code --add-reads} for a non-existent module is silently ignored by
	 * the JVM.
	 *
	 * <p>
	 * Reads the resolved {@code argLine} Maven property (not the XML element) so
	 * the check works when argLine is supplied via {@code @{argLine}} expansion
	 * (e.g. JaCoCo sets it as a property).
	 */
	static String buildJpmsAddReadsForProject(MavenProject project, Log log) {
		// Only applies when the project has a module-info.java
		java.io.File moduleInfo = new java.io.File(project.getBasedir(), "src/main/java/module-info.java");
		java.io.File testModuleInfo = new java.io.File(project.getBasedir(), "src/test/java/module-info.java");
		if (!moduleInfo.exists() && !testModuleInfo.exists()) {
			return "";
		}
		// Read both the Maven property AND the XML <argLine> element.
		// JaCoCo sets the argLine Maven property to just its javaagent (no
		// --add-opens),
		// so we must also check the Surefire XML <argLine> which may contain literal
		// --add-exports/--add-opens flags for JPMS patch-module builds.
		String argLine = project.getProperties() != null ? project.getProperties().getProperty("argLine", "") : "";
		Plugin surefire = findSurefirePlugin(project);
		if (surefire != null) {
			Xpp3Dom config = (Xpp3Dom) surefire.getConfiguration();
			if (config != null) {
				String xmlArgLine = childValue(config, "argLine");
				if (xmlArgLine != null) {
					argLine = argLine + " " + xmlArgLine;
				}
			}
		}
		if (!argLine.contains("--add-exports") && !argLine.contains("--add-opens")) {
			return "";
		}
		java.util.Set<String> moduleNames = extractModuleNamesFromArgLine(argLine);
		if (moduleNames.isEmpty()) {
			return "";
		}
		// The automatic module name for test-order-runtime.jar is "test.order.runtime"
		// (Java derives the module name by replacing hyphens with dots and stripping
		// version).
		// Inject --add-reads for both the unnamed classpath case and the automatic
		// module case.
		String runtimeModuleName = "test.order.runtime";
		StringBuilder addReads = new StringBuilder();
		for (String m : moduleNames) {
			if (m.equals("ALL-UNNAMED")) {
				continue;
			}
			if (!addReads.isEmpty()) {
				addReads.append(" ");
			}
			addReads.append("--add-reads ").append(m).append("=ALL-UNNAMED");
			addReads.append(" --add-reads ").append(m).append("=").append(runtimeModuleName);
		}
		String result = addReads.toString();
		if (!result.isEmpty()) {
			log.info("[test-order] JPMS patch-module build detected — injecting " + result
					+ " so named module(s) can access the test-order runtime.");
		}
		return result;
	}

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
