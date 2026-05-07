package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Framework-agnostic validation of JUnit Platform configuration that could
 * conflict with test-order. Both the Maven and Gradle plugins delegate to this
 * class after resolving their respective system property maps.
 */
public final class JUnitPlatformValidator {

	private final PluginLog log;

	public JUnitPlatformValidator(PluginLog log) {
		this.log = log;
	}

	// ── Listener deactivation (M4) ───────────────────────────────────

	/**
	 * Warns when {@code junit.platform.execution.listeners.deactivate} is
	 * configured in a way that could disable test-order's TelemetryListener.
	 *
	 * @param systemProperties resolved system properties (from Surefire XML or
	 *                         Gradle Test task)
	 * @param configurationParameters text content of JUnit Platform
	 *                                configurationParameters (may be null)
	 */
	public void warnListenerDeactivation(Map<String, String> systemProperties,
			String configurationParameters) {
		String deactivate = systemProperties.get("junit.platform.execution.listeners.deactivate");
		if (deactivate != null && !deactivate.isBlank()) {
			String pattern = deactivate.trim();
			if ("*".equals(pattern) || pattern.contains("TelemetryListener")
					|| pattern.contains("me.bechberger.testorder")) {
				log.warn("[test-order] junit.platform.execution.listeners.deactivate=" + pattern
						+ " — this will disable test-order's TelemetryListener. "
						+ "No telemetry (durations, failures) will be recorded.");
			}
		}

		if (configurationParameters != null) {
			for (ConfigEntry entry : parseConfigurationParameters(configurationParameters)) {
				if ("junit.platform.execution.listeners.deactivate".equals(entry.key)
						&& ("*".equals(entry.value) || entry.value.contains("TelemetryListener")
								|| entry.value.contains("me.bechberger.testorder"))) {
					log.warn("[test-order] junit.platform.execution.listeners.deactivate=" + entry.value
							+ " in configurationParameters — this will disable test-order's TelemetryListener.");
				}
			}
		}
	}

	// ── Conflicting orderers (M12, M20) ──────────────────────────────

	/**
	 * Warns when a conflicting ClassOrderer or MethodOrderer is configured that
	 * would override test-order's orderers.
	 *
	 * @param systemProperties resolved system properties
	 * @param configurationParameters text content (may be null)
	 */
	public void warnConflictingOrderers(Map<String, String> systemProperties,
			String configurationParameters) {
		// Check system properties
		String classOrderer = systemProperties.get("junit.jupiter.testclass.order.default");
		if (classOrderer != null && !classOrderer.isBlank()
				&& !classOrderer.contains("PriorityClassOrderer")) {
			log.warn("[test-order] A competing ClassOrderer is configured: " + classOrderer
					+ " — this will override test-order's PriorityClassOrderer.");
		}
		String methodOrderer = systemProperties.get("junit.jupiter.testmethod.order.default");
		if (methodOrderer != null && !methodOrderer.isBlank()
				&& !methodOrderer.contains("PriorityMethodOrderer")) {
			log.warn("[test-order] A global MethodOrderer is configured: " + methodOrderer
					+ " — this may conflict with test-order's PriorityMethodOrderer on classes "
					+ "without an explicit @TestMethodOrder annotation.");
		}

		// Check auto-detection (M12)
		String autoDetect = systemProperties.get("junit.jupiter.extensions.autodetection.enabled");
		if ("true".equalsIgnoreCase(autoDetect)) {
			log.warn("[test-order] junit.jupiter.extensions.autodetection.enabled=true — "
					+ "a third-party ClassOrderer/MethodOrderer on the classpath could override "
					+ "test-order's PriorityClassOrderer/PriorityMethodOrderer.");
		}

		// Check configurationParameters
		if (configurationParameters != null) {
			for (ConfigEntry entry : parseConfigurationParameters(configurationParameters)) {
				if ("junit.jupiter.testclass.order.default".equals(entry.key)
						&& !entry.value.contains("PriorityClassOrderer")) {
					log.warn("[test-order] A competing ClassOrderer in configurationParameters: "
							+ entry.value
							+ " — this will override test-order's PriorityClassOrderer.");
				}
				if ("junit.jupiter.testmethod.order.default".equals(entry.key)
						&& !entry.value.contains("PriorityMethodOrderer")) {
					log.warn("[test-order] A global MethodOrderer in configurationParameters: "
							+ entry.value
							+ " — this may conflict with test-order's PriorityMethodOrderer.");
				}
			}
		}
	}

	// ── Parallel execution detection ─────────────────────────────────

	/**
	 * Result of parallel execution analysis.
	 *
	 * @param classLevelParallelSources descriptions of detected parallel config
	 * @param vintageParallel whether JUnit Vintage parallel is enabled
	 */
	public record ParallelCheckResult(List<String> classLevelParallelSources, boolean vintageParallel) {
		public boolean hasClassLevelParallel() {
			return !classLevelParallelSources.isEmpty();
		}
	}

	/**
	 * Detects class-level parallel execution configuration from system properties
	 * and configurationParameters text. Does NOT check framework-specific
	 * configuration (e.g. Maven's {@code <parallel>} XML element) — callers must
	 * add those separately.
	 *
	 * @param systemProperties resolved system properties
	 * @param configurationParameters text content (may be null)
	 * @return analysis result with detected parallel sources
	 */
	public ParallelCheckResult detectParallelExecution(Map<String, String> systemProperties,
			String configurationParameters) {
		List<String> sources = new ArrayList<>();
		boolean vintageParallel = false;

		// Check system properties for JUnit Jupiter parallel
		String parallelEnabled = systemProperties.get("junit.jupiter.execution.parallel.enabled");
		String classesDefault = systemProperties.get(
				"junit.jupiter.execution.parallel.mode.classes.default");
		if ("true".equalsIgnoreCase(nullSafeTrim(parallelEnabled))
				&& isConcurrent(classesDefault)) {
			sources.add("junit.jupiter.execution.parallel.mode.classes.default=concurrent");
		}

		// Check Vintage parallel via system properties
		String vintageParallelProp = systemProperties.get(
				"junit.vintage.execution.parallel.enabled");
		if ("true".equalsIgnoreCase(nullSafeTrim(vintageParallelProp))) {
			vintageParallel = true;
		}

		// Check configurationParameters text
		if (configurationParameters != null) {
			if (isParallelEnabledInConfigurationParameters(configurationParameters)) {
				sources.add("configurationParameters parallel config");
			}
			if (isVintageParallelInConfigurationParameters(configurationParameters)) {
				vintageParallel = true;
			}
		}

		return new ParallelCheckResult(sources, vintageParallel);
	}

	/**
	 * Checks JVM args (e.g. from Gradle's {@code jvmArgs}) for parallel execution
	 * settings passed as {@code -D} flags.
	 *
	 * @param jvmArgs list of JVM arguments (may be null)
	 * @return the offending arg, or null if no parallel config found
	 */
	public static String findParallelInJvmArgs(List<String> jvmArgs) {
		if (jvmArgs == null) {
			return null;
		}
		for (String arg : jvmArgs) {
			if (arg.contains("junit.jupiter.execution.parallel.mode.classes.default=concurrent")) {
				return arg;
			}
		}
		return null;
	}

	// ── junit-platform.properties file check (L6) ────────────────────

	/**
	 * Checks {@code src/test/resources/junit-platform.properties} for conflicting
	 * orderer or listener configuration.
	 *
	 * @param projectDir project root directory
	 */
	public void checkJunitPlatformPropertiesFile(Path projectDir) {
		Path userProps = projectDir.resolve("src/test/resources/junit-platform.properties");
		if (!Files.exists(userProps)) {
			return;
		}
		try {
			String content = Files.readString(userProps);
			if (content.contains("junit.jupiter.testclass.order.default")
					|| content.contains("junit.jupiter.testmethod.order.default")) {
				log.warn("[test-order] src/test/resources/junit-platform.properties "
						+ "contains orderer configuration that may conflict with test-order. "
						+ "System properties set by test-order take precedence.");
			}
			if (content.contains("junit.platform.execution.listeners.deactivate")) {
				log.warn("[test-order] src/test/resources/junit-platform.properties "
						+ "contains listener deactivation config — this may disable "
						+ "test-order's TelemetryListener.");
			}
		} catch (IOException ignored) {
			// best-effort check
		}
	}

	/**
	 * Checks {@code src/test/resources/junit-platform.properties} for parallel
	 * execution configuration that conflicts with learn mode.
	 *
	 * @param projectDir project root directory
	 */
	public void checkJunitPlatformPropertiesParallel(Path projectDir) {
		Path userProps = projectDir.resolve("src/test/resources/junit-platform.properties");
		if (!Files.exists(userProps)) {
			return;
		}
		try {
			String content = Files.readString(userProps);
			if (content.contains("junit.jupiter.execution.parallel.mode.classes.default=concurrent")
					|| content.contains("junit.vintage.execution.parallel.enabled=true")) {
				log.warn("[test-order] src/test/resources/junit-platform.properties "
						+ "contains parallel configuration that may conflict with learn mode "
						+ "dependency tracking.");
			}
		} catch (IOException ignored) {
			// best-effort
		}
	}

	// ── Configuration parameter text parsing ─────────────────────────

	/**
	 * Parses JUnit Platform configurationParameters text (key=value lines,
	 * comments with #). This is a shared utility for both Maven and Gradle plugins.
	 *
	 * @param text configurationParameters content
	 * @return list of parsed key-value entries
	 */
	public static List<ConfigEntry> parseConfigurationParameters(String text) {
		List<ConfigEntry> entries = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return entries;
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
			entries.add(new ConfigEntry(line.substring(0, idx).trim(), line.substring(idx + 1).trim()));
		}
		return entries;
	}

	/**
	 * Returns true when JUnit parallel execution is enabled in
	 * configurationParameters (both parallel.enabled=true AND
	 * classes.default=concurrent must be present).
	 */
	public static boolean isParallelEnabledInConfigurationParameters(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		boolean parallelEnabled = false;
		boolean classesConcurrent = false;
		for (ConfigEntry entry : parseConfigurationParameters(text)) {
			if ("junit.jupiter.execution.parallel.enabled".equals(entry.key)
					&& "true".equalsIgnoreCase(entry.value)) {
				parallelEnabled = true;
			}
			if ("junit.jupiter.execution.parallel.mode.classes.default".equals(entry.key)
					&& "concurrent".equalsIgnoreCase(entry.value)) {
				classesConcurrent = true;
			}
		}
		return parallelEnabled && classesConcurrent;
	}

	/**
	 * Returns true when class-level concurrent mode is set in
	 * configurationParameters (regardless of parallel.enabled flag). Used for
	 * learn-mode hard rejection.
	 */
	public static boolean isClassLevelConcurrentInConfigurationParameters(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		for (ConfigEntry entry : parseConfigurationParameters(text)) {
			if ("junit.jupiter.execution.parallel.mode.classes.default".equals(entry.key)
					&& "concurrent".equalsIgnoreCase(entry.value)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns true when JUnit Vintage parallel execution is enabled in
	 * configurationParameters.
	 */
	public static boolean isVintageParallelInConfigurationParameters(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		for (ConfigEntry entry : parseConfigurationParameters(text)) {
			if ("junit.vintage.execution.parallel.enabled".equals(entry.key)
					&& "true".equalsIgnoreCase(entry.value)) {
				return true;
			}
		}
		return false;
	}

	// ── Internal helpers ─────────────────────────────────────────────

	private static boolean isConcurrent(String value) {
		return value != null && "concurrent".equalsIgnoreCase(value.trim());
	}

	private static String nullSafeTrim(String value) {
		return value != null ? value.trim() : null;
	}

	/** A parsed key-value entry from configurationParameters text. */
	public record ConfigEntry(String key, String value) {
	}
}
