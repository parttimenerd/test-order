package me.bechberger.testorder.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import me.bechberger.testorder.changes.ChangeDetectionSupport;

/**
 * Validates and normalizes configuration parameters. Provides helpful error
 * messages with valid options.
 */
public class ParameterValidator {

	private final Log log;

	public ParameterValidator(Log log) {
		this.log = log;
	}

	/**
	 * Validates changeMode parameter value. Valid values: auto, since-last-run,
	 * since-last-commit, uncommitted, explicit
	 */
	public void validateChangeMode(String changeMode) throws MojoExecutionException {
		if (changeMode == null || changeMode.isBlank()) {
			return; // null is OK, will use default
		}

		Set<String> validModes = ChangeDetectionSupport.supportedModes();
		try {
			ChangeDetectionSupport.normalizeMode(changeMode);
		} catch (IOException e) {
			throw new MojoExecutionException(String.format("[test-order] Invalid changeMode '%s'. Valid values are: %s",
					changeMode, String.join(", ", validModes)));
		}
	}

	/**
	 * Validates instrumentationMode parameter value. Valid values: METHOD_ENTRY,
	 * FULL, FULL_METHOD, FULL_MEMBER
	 */
	public void validateInstrumentationMode(String instrumentationMode) throws MojoExecutionException {
		if (instrumentationMode == null || instrumentationMode.isBlank()) {
			return; // null is OK, will use default
		}

		Set<String> validModes = Set.of("METHOD_ENTRY", "FULL", "FULL_METHOD", "FULL_MEMBER");

		if (!validModes.contains(instrumentationMode.toUpperCase())) {
			throw new MojoExecutionException(
					String.format("[test-order] Invalid instrumentationMode '%s'. Valid values are: %s",
							instrumentationMode, String.join(", ", validModes)));
		}
	}

	/**
	 * Validates outputFormat parameter value. Valid values: comprehensive,
	 * markdown, json
	 */
	public void validateOutputFormat(String outputFormat) throws MojoExecutionException {
		if (outputFormat == null || outputFormat.isBlank()) {
			return; // null is OK, will use default
		}

		Set<String> validFormats = new HashSet<>(Arrays.asList("comprehensive", "markdown", "json"));

		if (!validFormats.contains(outputFormat.toLowerCase())) {
			throw new MojoExecutionException(
					String.format("[test-order] Invalid outputFormat '%s'. Valid values are: %s", outputFormat,
							String.join(", ", validFormats)));
		}
	}

	/**
	 * Validates that a file path exists and is readable.
	 */
	public void validateFilePath(String filePath, String parameterName) throws MojoExecutionException {
		if (filePath == null || filePath.isBlank()) {
			return; // Optional parameter
		}

		Path path = Paths.get(filePath);
		if (!Files.exists(path)) {
			throw new MojoExecutionException(
					String.format("[test-order] %s file not found: %s", parameterName, path.toAbsolutePath()));
		}

		if (!Files.isReadable(path)) {
			throw new MojoExecutionException(
					String.format("[test-order] %s file is not readable: %s", parameterName, path.toAbsolutePath()));
		}
	}

	/**
	 * Validates that a directory path exists and is writable.
	 */
	public void validateOutputDirectory(String dirPath, String parameterName) throws MojoExecutionException {
		if (dirPath == null || dirPath.isBlank()) {
			return; // Optional parameter
		}

		Path path = Paths.get(dirPath);
		if (!Files.exists(path)) {
			try {
				Files.createDirectories(path);
				log.debug("[test-order] Created output directory: " + path.toAbsolutePath());
			} catch (Exception e) {
				throw new MojoExecutionException(String.format("[test-order] Failed to create %s directory: %s",
						parameterName, path.toAbsolutePath()), e);
			}
		}

		if (!Files.isDirectory(path)) {
			throw new MojoExecutionException(
					String.format("[test-order] %s is not a directory: %s", parameterName, path.toAbsolutePath()));
		}

		if (!Files.isWritable(path)) {
			throw new MojoExecutionException(String.format("[test-order] %s directory is not writable: %s",
					parameterName, path.toAbsolutePath()));
		}
	}

	/**
	 * Validates numeric range parameters.
	 */
	public void validateIntRange(int value, int min, int max, String parameterName) throws MojoExecutionException {
		if (value < min || value > max) {
			throw new MojoExecutionException(
					String.format("[test-order] %s value %d is out of range [%d, %d]", parameterName, value, min, max));
		}
	}

	/**
	 * Validates that explicit mode has required changedClasses parameter.
	 */
	public void validateExplicitModeRequirements(String changeMode, String changedClasses)
			throws MojoExecutionException {
		if ("explicit".equalsIgnoreCase(changeMode)) {
			if (changedClasses == null || changedClasses.isBlank()) {
				throw new MojoExecutionException(
						"[test-order] changeMode is 'explicit' but changedClasses parameter is not specified. "
								+ "Provide comma-separated fully qualified class names: "
								+ "-Dtestorder.changed.classes=com.example.A,com.example.B");
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
}
