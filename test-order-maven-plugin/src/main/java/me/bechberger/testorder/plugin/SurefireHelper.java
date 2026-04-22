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
		return null;
	}

	static Plugin requireSurefirePlugin(MavenProject project) throws MojoExecutionException {
		Plugin surefire = findSurefirePlugin(project);
		if (surefire == null) {
			throw new MojoExecutionException("maven-surefire-plugin not found in project");
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
	 * Fails when class-level parallel execution is enabled in Surefire/JUnit.
	 * Method-level parallelism is supported; class-level is currently not.
	 */
	static void validateNoClassLevelParallel(MavenProject project, Log log) throws MojoExecutionException {
		Plugin surefire = requireSurefirePlugin(project);
		Xpp3Dom config = getOrCreateConfiguration(surefire);

		String surefireParallel = childValue(config, "parallel");
		if (isClassLevelSurefireParallel(surefireParallel)) {
			throw new MojoExecutionException("Unsupported Surefire parallel mode '<parallel>" + surefireParallel
					+ "</parallel>': class-level parallel execution is not supported by test-order. "
					+ "Use method-level parallelism only (e.g. <parallel>methods</parallel>).\n"
					+ "Alternatively disable class-level JUnit parallel mode by setting "
					+ "junit.jupiter.execution.parallel.mode.classes.default=same_thread.");
		}

		String classesDefaultFromSysProps = childValue(child(config, "systemPropertyVariables"),
				"junit.jupiter.execution.parallel.mode.classes.default");
		if (isConcurrent(classesDefaultFromSysProps)) {
			throw new MojoExecutionException("Unsupported JUnit class-level parallel mode in Surefire "
					+ "<systemPropertyVariables>: junit.jupiter.execution.parallel.mode.classes.default="
					+ classesDefaultFromSysProps + ". Use same_thread or remove it.");
		}

		String configurationParameters = childValue(child(child(config, "properties"), "configurationParameters"));
		if (isClassLevelConcurrentInConfigurationParameters(configurationParameters)) {
			throw new MojoExecutionException("Unsupported JUnit class-level parallel mode in Surefire "
					+ "<properties><configurationParameters>. Found "
					+ "junit.jupiter.execution.parallel.mode.classes.default=concurrent. "
					+ "Use same_thread or remove the key.");
		}

		log.debug("[test-order] Surefire class-level parallel mode check passed.");
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
