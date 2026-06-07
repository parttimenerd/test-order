package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.changes.ChangeDetectionSupport;

/**
 * Validates and normalizes configuration parameters. Provides helpful error
 * messages with valid options. Framework-agnostic — throws
 * {@link IllegalArgumentException} for invalid values.
 */
public final class ParameterValidator {

	private final PluginLog log;

	public ParameterValidator(PluginLog log) {
		this.log = log;
	}

	/**
	 * Validates changeMode parameter value. Valid values: auto, since-last-run,
	 * since-last-commit, uncommitted, explicit
	 */
	public void validateChangeMode(String changeMode) {
		if (changeMode == null || changeMode.isBlank()) {
			return; // null is OK, will use default
		}

		Set<String> validModes = ChangeDetectionSupport.supportedModes();
		try {
			ChangeDetectionSupport.normalizeMode(changeMode);
		} catch (IOException e) {
			throw new IllegalArgumentException(
					String.format("[test-order] Invalid changeMode '%s'. Valid values are: %s", changeMode,
							String.join(", ", validModes)));
		}
	}

	/**
	 * Validates instrumentationMode parameter value. Valid values: CLASS, METHOD,
	 * MEMBER (and legacy aliases FULL, FULL_METHOD, FULL_MEMBER, METHOD_ENTRY)
	 */
	public void validateInstrumentationMode(String instrumentationMode) {
		if (instrumentationMode == null || instrumentationMode.isBlank()) {
			return; // null is OK, will use default
		}

		Set<String> validModes = Set.of("CLASS", "METHOD", "MEMBER", "FULL", "FULL_METHOD", "FULL_MEMBER",
				"METHOD_ENTRY");

		if (!validModes.contains(instrumentationMode.toUpperCase())) {
			throw new IllegalArgumentException(String.format(
					"[test-order] Invalid instrumentationMode '%s'. Valid values are: %s", instrumentationMode,
					"CLASS, METHOD, MEMBER (legacy aliases: FULL, FULL_METHOD, FULL_MEMBER, METHOD_ENTRY)"));
		}
	}

	/**
	 * Validates that a file path exists and is readable.
	 */
	public void validateFilePath(String filePath, String parameterName) {
		if (filePath == null || filePath.isBlank()) {
			return; // Optional parameter
		}

		Path path = Paths.get(filePath);
		if (!Files.exists(path)) {
			throw new IllegalArgumentException(
					String.format("[test-order] %s file not found: %s", parameterName, path.toAbsolutePath()));
		}

		if (!Files.isReadable(path)) {
			throw new IllegalArgumentException(
					String.format("[test-order] %s file is not readable: %s", parameterName, path.toAbsolutePath()));
		}
	}

	/**
	 * Validates that a directory path exists and is writable, creating it if
	 * necessary.
	 */
	public void validateOutputDirectory(String dirPath, String parameterName) {
		if (dirPath == null || dirPath.isBlank()) {
			return; // Optional parameter
		}

		Path path = Paths.get(dirPath);
		if (!Files.exists(path)) {
			try {
				Files.createDirectories(path);
				log.debug("[test-order] Created output directory: " + path.toAbsolutePath());
			} catch (Exception e) {
				throw new IllegalArgumentException(String.format("[test-order] Failed to create %s directory: %s",
						parameterName, path.toAbsolutePath()), e);
			}
		}

		if (!Files.isDirectory(path)) {
			throw new IllegalArgumentException(
					String.format("[test-order] %s is not a directory: %s", parameterName, path.toAbsolutePath()));
		}

		if (!Files.isWritable(path)) {
			throw new IllegalArgumentException(String.format("[test-order] %s directory is not writable: %s",
					parameterName, path.toAbsolutePath()));
		}
	}

	/**
	 * Validates numeric range parameters.
	 */
	public void validateIntRange(int value, int min, int max, String parameterName) {
		if (value < min || value > max) {
			throw new IllegalArgumentException(
					String.format("[test-order] %s value %d is out of range [%d, %d]", parameterName, value, min, max));
		}
	}

	/**
	 * Validates that a value is not negative.
	 */
	public void validateNonNegative(int value, String parameterName) {
		if (value < 0) {
			throw new IllegalArgumentException(
					String.format("[test-order] %s cannot be negative: %d", parameterName, value));
		}
	}

	/**
	 * Validates that a value is at least a given minimum.
	 */
	public void validateMinValue(int value, int min, String parameterName) {
		if (value < min) {
			throw new IllegalArgumentException(
					String.format("[test-order] %s cannot be less than %d: %d", parameterName, min, value));
		}
	}

	/**
	 * Validates select-mode parameters (topN and randomM).
	 *
	 * @throws IllegalArgumentException
	 *             if topN is less than -1 or if randomM is negative
	 */
	public void validateSelectParameters(int topN, int randomM) {
		validateMinValue(topN, -1, "selectTopN");
		validateNonNegative(randomM, "selectRandomM");

		if (topN == 0) {
			log.warn("[test-order] selectTopN=0 selects no top-scored tests. "
					+ "New tests and @AlwaysRun tests are still included. "
					+ "Use selectTopN=-1 to select all change-affected tests.");
		}
	}

	/**
	 * Validates auto-learn / optimize threshold parameters. Each must be
	 * non-negative; 0 disables the corresponding behaviour.
	 */
	public void validateAutoLearnThresholds(int runThreshold, int diffThreshold, int optimizeEvery) {
		if (runThreshold < 0) {
			throw new IllegalArgumentException(
					"[test-order] testorder.autoLearnRunThreshold must be >= 0 (0 disables); got " + runThreshold);
		}
		if (diffThreshold < 0) {
			throw new IllegalArgumentException(
					"[test-order] testorder.autoLearnDiffThreshold must be >= 0 (0 disables); got " + diffThreshold);
		}
		if (optimizeEvery < 0) {
			throw new IllegalArgumentException(
					"[test-order] testorder.auto.optimizeEvery must be >= 0 (0 disables); got " + optimizeEvery);
		}
	}

	/**
	 * Validates that explicit mode has required changedClasses parameter.
	 */
	public void validateExplicitModeRequirements(String changeMode, String changedClasses) {
		validateExplicitModeRequirements(changeMode, changedClasses, null);
	}

	/**
	 * Validates that explicit mode has required changed classes/tests input.
	 */
	public void validateExplicitModeRequirements(String changeMode, String changedClasses, String changedTestClasses) {
		if ("explicit".equalsIgnoreCase(changeMode)) {
			boolean hasChangedClasses = changedClasses != null && !changedClasses.isBlank();
			boolean hasChangedTestClasses = changedTestClasses != null && !changedTestClasses.isBlank();
			if (!hasChangedClasses && !hasChangedTestClasses) {
				throw new IllegalArgumentException(
						"[test-order] changeMode is 'explicit' but no changed classes were specified. "
								+ "Provide at least one of: "
								+ "-Dtestorder.changed.classes=com.example.A,com.example.B "
								+ "or -Dtestorder.changed.test.classes=com.example.MyTest");
			}
		}
	}

	/**
	 * Logs a warning for each scoring weight that is negative, since negative
	 * weights invert the intended scoring semantics.
	 */
	public void warnNegativeWeights(Map<String, Integer> weights) {
		for (var entry : weights.entrySet()) {
			if (entry.getValue() != null && entry.getValue() < 0) {
				log.warn("[test-order] Negative scoring weight " + entry.getKey() + "=" + entry.getValue()
						+ " — this inverts the scoring for that component.");
			}
		}
	}

	/**
	 * Validates explicitly specified changed classes against the dependency index.
	 * Logs a warning for each class not found in the index.
	 *
	 * <p>
	 * Also detects common user mistakes such as semicolon-separated class names
	 * (only commas are the documented separator) and warns about them explicitly.
	 * </p>
	 *
	 * @throws IllegalArgumentException
	 *             if ALL specified classes are unknown (protects against silently
	 *             wrong test selection)
	 */
	public void warnUnknownChangedClasses(Set<String> changed, DependencyMap depMap, String changeMode) {
		if (changed.isEmpty() || !"explicit".equalsIgnoreCase(changeMode)) {
			return;
		}
		Set<String> allKnown = new java.util.HashSet<>(depMap.testClasses());
		for (String tc : depMap.testClasses()) {
			allKnown.addAll(depMap.get(tc));
		}
		Set<String> unknown = new java.util.LinkedHashSet<>();
		for (String cls : changed) {
			if (!allKnown.contains(cls)) {
				unknown.add(cls);
			}
		}
		if (!unknown.isEmpty()) {
			log.warn("[test-order] The following explicitly changed classes are not in the dependency index: "
					+ String.join(", ", unknown)
					+ ". The class may exist in source but have no test coverage yet, or the index"
					+ " may be out of date (re-run learn mode).");
			if (unknown.size() == changed.size()) {
				throw new IllegalArgumentException(
						"[test-order] None of the explicitly specified changed classes exist in the "
								+ "dependency index. Check for typos or run learn mode first. " + "Changed classes: "
								+ String.join(", ", changed));
			}
		}
	}

	/**
	 * Validates the raw changed-classes string for common mistakes. Should be
	 * called before parsing the string into a set of class names. Logs a warning if
	 * semicolons are detected (only commas are the documented separator, though
	 * semicolons are accepted as a fallback).
	 */
	public void warnChangedClassesFormat(String changedClasses) {
		warnChangedClassesFormat(changedClasses, "testorder.changed.classes");
	}

	/**
	 * Variant that allows the caller to supply the property name used in the
	 * warning message (e.g. {@code "testorder.changed.test.classes"}).
	 */
	public void warnChangedClassesFormat(String changedClasses, String propertyName) {
		if (changedClasses == null || changedClasses.isBlank()) {
			return;
		}
		if (!changedClasses.contains(",") && changedClasses.contains(";")) {
			log.warn("[test-order] " + propertyName + " uses semicolons as separators, but only commas are"
					+ " documented. Semicolons are accepted as a fallback. Prefer:" + " -D" + propertyName + "="
					+ changedClasses.replace(';', ','));
		}
	}
}
