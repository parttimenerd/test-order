package me.bechberger.testorder.plugin;

import java.util.List;

import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

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

	static Plugin requireSurefirePlugin(MavenProject project) throws MojoExecutionException {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null) {
			throw new MojoExecutionException("maven-surefire-plugin or maven-failsafe-plugin not found in project");
		}
		return surefire;
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
	 * guarantee start order when parallelism is active.
	 */
	static void validateNoClassLevelParallel(MavenProject project, Log log) throws MojoExecutionException {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null) {
			log.debug("[test-order] Surefire class-level parallel mode check passed.");
			return;
		}
		Xpp3Dom config = getOrCreateConfiguration(surefire);

		// Check Surefire's <parallel> (only applies to JUnit 4.7 and TestNG providers, but warn anyway)
		String surefireParallel = childValue(config, "parallel");
		if (isClassLevelSurefireParallel(surefireParallel)) {
			log.warn("[test-order] Class-level parallel execution (<parallel>" + surefireParallel
					+ "</parallel>) detected — test ordering guarantees are weakened. "
					+ "Tests will be sorted but may not start in priority order.");
		}

		// Check JUnit Platform parallel config via systemPropertyVariables
		String parallelEnabled = childValue(child(config, "systemPropertyVariables"),
				"junit.jupiter.execution.parallel.enabled");
		String classesDefault = childValue(child(config, "systemPropertyVariables"),
				"junit.jupiter.execution.parallel.mode.classes.default");
		if ("true".equalsIgnoreCase(parallelEnabled != null ? parallelEnabled.trim() : null)
				&& isConcurrent(classesDefault)) {
			log.warn("[test-order] JUnit class-level parallel mode (mode.classes.default=concurrent) "
					+ "detected — test ordering guarantees are weakened. "
					+ "Classes will be sorted but may not start in priority order.");
		}

		// Check JUnit Platform parallel config via <configurationParameters>
		String configurationParameters = childValue(child(child(config, "properties"), "configurationParameters"));
		if (isParallelEnabledInConfigurationParameters(configurationParameters)) {
			log.warn("[test-order] JUnit parallel execution detected in <configurationParameters> — "
					+ "test ordering guarantees are weakened. "
					+ "Classes will be sorted but may not start in priority order.");
		}

		// Check Vintage parallel config
		if (isVintageParallelInConfigurationParameters(configurationParameters)) {
			log.warn("[test-order] JUnit Vintage parallel execution detected — "
					+ "Vintage tests will not respect test-order's ordering.");
		}
		String vintageParallel = childValue(child(config, "systemPropertyVariables"),
				"junit.vintage.execution.parallel.enabled");
		if ("true".equalsIgnoreCase(vintageParallel != null ? vintageParallel.trim() : null)) {
			log.warn("[test-order] JUnit Vintage parallel execution detected — "
					+ "Vintage tests will not respect test-order's ordering.");
		}

		log.debug("[test-order] Surefire class-level parallel mode check passed.");
	}

	/**
	 * Fails when class-level parallel execution is enabled and we're in learn mode.
	 * Parallel class execution during learn mode corrupts dependency tracking because
	 * concurrent class loading blurs which test triggered which dependency.
	 */
	static void rejectClassLevelParallelForLearn(MavenProject project, Log log) throws MojoExecutionException {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null)
			return;
		Xpp3Dom config = getOrCreateConfiguration(surefire);

		String surefireParallel = childValue(config, "parallel");
		if (isClassLevelSurefireParallel(surefireParallel)) {
			throw new MojoExecutionException(
					"Class-level parallel execution (<parallel>" + surefireParallel
							+ "</parallel>) is not supported in learn mode — "
							+ "it would corrupt dependency tracking. "
							+ "Remove or change to <parallel>methods</parallel> during learn runs.");
		}

		String classesDefaultFromSysProps = childValue(child(config, "systemPropertyVariables"),
				"junit.jupiter.execution.parallel.mode.classes.default");
		if (isConcurrent(classesDefaultFromSysProps)) {
			throw new MojoExecutionException(
					"JUnit class-level parallel mode (mode.classes.default=concurrent) "
							+ "is not supported in learn mode — it would corrupt dependency tracking. "
							+ "Use same_thread or remove it during learn runs.");
		}

		String configurationParameters = childValue(child(child(config, "properties"), "configurationParameters"));
		if (isClassLevelConcurrentInConfigurationParameters(configurationParameters)) {
			throw new MojoExecutionException(
					"JUnit class-level parallel mode in <configurationParameters> "
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
		if (isVintageParallelInConfigurationParameters(configurationParameters)) {
			throw new MojoExecutionException(
					"JUnit Vintage parallel execution in <configurationParameters> "
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

	private static boolean isClassLevelConcurrentInConfigurationParameters(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		for (String rawLine : text.split("\\R")) {
			String line = rawLine.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			int idx = line.indexOf('=');
			if (idx <= 0) {
				continue;
			}
			String key = line.substring(0, idx).trim();
			String value = line.substring(idx + 1).trim();
			if ("junit.jupiter.execution.parallel.mode.classes.default".equals(key)
					&& "concurrent".equalsIgnoreCase(value)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns true when JUnit parallel execution is enabled in configurationParameters
	 * (both parallel.enabled=true AND classes.default=concurrent must be present).
	 */
	private static boolean isParallelEnabledInConfigurationParameters(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		boolean parallelEnabled = false;
		boolean classesConcurrent = false;
		for (String rawLine : text.split("\\R")) {
			String line = rawLine.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			int idx = line.indexOf('=');
			if (idx <= 0) {
				continue;
			}
			String key = line.substring(0, idx).trim();
			String value = line.substring(idx + 1).trim();
			if ("junit.jupiter.execution.parallel.enabled".equals(key) && "true".equalsIgnoreCase(value)) {
				parallelEnabled = true;
			}
			if ("junit.jupiter.execution.parallel.mode.classes.default".equals(key)
					&& "concurrent".equalsIgnoreCase(value)) {
				classesConcurrent = true;
			}
		}
		return parallelEnabled && classesConcurrent;
	}

	/**
	 * Returns true when JUnit Vintage parallel execution is enabled in configurationParameters.
	 */
	private static boolean isVintageParallelInConfigurationParameters(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		for (String rawLine : text.split("\\R")) {
			String line = rawLine.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			int idx = line.indexOf('=');
			if (idx <= 0) {
				continue;
			}
			String key = line.substring(0, idx).trim();
			String value = line.substring(idx + 1).trim();
			if ("junit.vintage.execution.parallel.enabled".equals(key) && "true".equalsIgnoreCase(value)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Warns when {@code junit.platform.execution.listeners.deactivate} is configured
	 * in a way that could disable test-order's TelemetryListener (M4).
	 */
	static void warnListenerDeactivation(MavenProject project, Log log) {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null)
			return;
		Xpp3Dom config = getOrCreateConfiguration(surefire);

		String deactivate = childValue(child(config, "systemPropertyVariables"),
				"junit.platform.execution.listeners.deactivate");
		if (deactivate != null && !deactivate.isBlank()) {
			String pattern = deactivate.trim();
			if ("*".equals(pattern) || pattern.contains("TelemetryListener")
					|| pattern.contains("me.bechberger.testorder")) {
				log.warn("[test-order] junit.platform.execution.listeners.deactivate=" + pattern
						+ " — this will disable test-order's TelemetryListener. "
						+ "No telemetry (durations, failures) will be recorded.");
			}
		}

		String configParams = childValue(child(child(config, "properties"), "configurationParameters"));
		if (configParams != null) {
			for (String rawLine : configParams.split("\\R")) {
				String line = rawLine.trim();
				if (line.isEmpty() || line.startsWith("#"))
					continue;
				int idx = line.indexOf('=');
				if (idx <= 0)
					continue;
				String key = line.substring(0, idx).trim();
				String value = line.substring(idx + 1).trim();
				if ("junit.platform.execution.listeners.deactivate".equals(key)
						&& ("*".equals(value) || value.contains("TelemetryListener")
								|| value.contains("me.bechberger.testorder"))) {
					log.warn("[test-order] junit.platform.execution.listeners.deactivate=" + value
							+ " in <configurationParameters> — this will disable test-order's TelemetryListener.");
				}
			}
		}
	}

	/**
	 * Warns when a conflicting ClassOrderer or MethodOrderer is configured globally
	 * via JUnit Platform config, which would override test-order's orderers (M12, M20).
	 */
	static void warnConflictingOrderers(MavenProject project, Log log) {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null)
			return;
		Xpp3Dom config = getOrCreateConfiguration(surefire);
		Xpp3Dom sysProps = child(config, "systemPropertyVariables");

		// Check via systemPropertyVariables
		String classOrderer = childValue(sysProps, "junit.jupiter.testclass.order.default");
		if (classOrderer != null && !classOrderer.isBlank()
				&& !classOrderer.contains("PriorityClassOrderer")) {
			log.warn("[test-order] A competing ClassOrderer is configured: " + classOrderer
					+ " — this will override test-order's PriorityClassOrderer.");
		}
		String methodOrderer = childValue(sysProps, "junit.jupiter.testmethod.order.default");
		if (methodOrderer != null && !methodOrderer.isBlank()
				&& !methodOrderer.contains("PriorityMethodOrderer")) {
			log.warn("[test-order] A global MethodOrderer is configured: " + methodOrderer
					+ " — this may conflict with test-order's PriorityMethodOrderer on classes "
					+ "without an explicit @TestMethodOrder annotation.");
		}

		// Check via configurationParameters
		String configParams = childValue(child(child(config, "properties"), "configurationParameters"));
		if (configParams != null) {
			for (String rawLine : configParams.split("\\R")) {
				String line = rawLine.trim();
				if (line.isEmpty() || line.startsWith("#"))
					continue;
				int idx = line.indexOf('=');
				if (idx <= 0)
					continue;
				String key = line.substring(0, idx).trim();
				String value = line.substring(idx + 1).trim();
				if ("junit.jupiter.testclass.order.default".equals(key)
						&& !value.contains("PriorityClassOrderer")) {
					log.warn("[test-order] A competing ClassOrderer in <configurationParameters>: " + value
							+ " — this will override test-order's PriorityClassOrderer.");
				}
				if ("junit.jupiter.testmethod.order.default".equals(key)
						&& !value.contains("PriorityMethodOrderer")) {
					log.warn("[test-order] A global MethodOrderer in <configurationParameters>: " + value
							+ " — this may conflict with test-order's PriorityMethodOrderer.");
				}
			}
		}
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

		// Build comma-separated list of FQCNs for Surefire's test parameter
		StringBuilder testParam = new StringBuilder();
		for (String tc : tests) {
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
