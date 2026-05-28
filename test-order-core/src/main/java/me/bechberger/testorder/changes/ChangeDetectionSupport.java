package me.bechberger.testorder.changes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared helpers for resolving and executing change-detection modes.
 *
 * <p>
 * This utility keeps mode parsing and auto-mode behavior consistent across
 * Maven and Gradle adapters.
 * </p>
 */
public final class ChangeDetectionSupport {

	private static final Set<String> SUPPORTED_CHANGE_MODES = Set.of("auto", "since-last-run", "since-last-commit",
			"uncommitted", "explicit");

	private ChangeDetectionSupport() {
	}

	/**
	 * Returns all supported change-mode names.
	 */
	public static Set<String> supportedModes() {
		return SUPPORTED_CHANGE_MODES;
	}

	/**
	 * Returns true when the provided value is a known change mode.
	 */
	public static boolean isSupportedMode(String changeMode) {
		if (changeMode == null || changeMode.isBlank()) {
			return false;
		}
		return SUPPORTED_CHANGE_MODES.contains(changeMode.toLowerCase(Locale.ROOT));
	}

	/**
	 * Normalizes and validates a change mode value.
	 *
	 * @return lower-case mode name; defaults to {@code uncommitted} when blank or
	 *         null
	 * @throws IOException
	 *             when mode is unknown
	 */
	public static String normalizeMode(String changeMode) throws IOException {
		if (changeMode == null || changeMode.isBlank()) {
			return "uncommitted";
		}
		String normalized = changeMode.toLowerCase(Locale.ROOT);
		if (!SUPPORTED_CHANGE_MODES.contains(normalized)) {
			throw new IOException("Unknown changeMode: " + changeMode);
		}
		return normalized;
	}

	/**
	 * Parse an explicit change-detection mode string.
	 *
	 * @throws IOException
	 *             when the mode is unknown
	 */
	public static ChangeDetector.Mode parseMode(String changeMode) throws IOException {
		return switch (normalizeMode(changeMode)) {
			case "since-last-run" -> ChangeDetector.Mode.SINCE_LAST_RUN;
			case "since-last-commit" -> ChangeDetector.Mode.SINCE_LAST_COMMIT;
			case "uncommitted" -> ChangeDetector.Mode.UNCOMMITTED;
			case "explicit" -> ChangeDetector.Mode.EXPLICIT;
			default -> throw new IOException("Unknown changeMode: " + changeMode);
		};
	}

	/**
	 * Resolve change mode including {@code auto} behavior based on snapshot
	 * existence.
	 */
	public static ChangeDetector.Mode resolveMode(String changeMode, Path hashFile) throws IOException {
		String normalized = normalizeMode(changeMode);
		if ("auto".equals(normalized)) {
			return (hashFile != null && Files.exists(hashFile))
					? ChangeDetector.Mode.SINCE_LAST_RUN
					: ChangeDetector.Mode.SINCE_LAST_COMMIT;
		}
		return parseMode(normalized);
	}

	/**
	 * Detect changed production classes.
	 *
	 * <p>
	 * In {@code explicit} mode, the {@code changedClasses} parameter is used
	 * directly. In {@code auto} mode, explicit changed classes (when provided) take
	 * precedence over snapshot-based detection, otherwise mode resolves from
	 * snapshot presence. In all other modes ({@code uncommitted},
	 * {@code since-last-commit}, {@code since-last-run}), the
	 * {@code changedClasses} parameter is ignored and git/hash-based detection is
	 * used.
	 * </p>
	 */
	public static Set<String> detectChangedClasses(String changeMode, Path projectRoot, Path sourceRoot, Path hashFile,
			String changedClasses, boolean readOnly) throws IOException {
		String normalized = normalizeMode(changeMode);
		// Explicit classes always take precedence over git/hash-based detection,
		// regardless of changeMode. This lets users override detection in any mode.
		if (changedClasses != null && !changedClasses.isBlank()) {
			return parseExplicitClasses(changedClasses);
		}
		if (sourceRoot == null) {
			return Set.of();
		}
		ChangeDetector.Mode mode = resolveMode(normalized, hashFile);
		if (mode == ChangeDetector.Mode.EXPLICIT) {
			return Set.of();
		}
		return invoke(mode, projectRoot, sourceRoot, hashFile, changedClasses, readOnly);
	}

	/**
	 * Detect changed test classes.
	 *
	 * <p>
	 * Explicit mode is intentionally ignored for test sources and returns an empty
	 * set.
	 * </p>
	 */
	public static Set<String> detectChangedTestClasses(String changeMode, Path projectRoot, Path testSourceRoot,
			Path testHashFile, boolean readOnly) throws IOException {
		String normalized = normalizeMode(changeMode);
		if ("explicit".equals(normalized)) {
			return Set.of();
		}
		if (testSourceRoot == null) {
			return Set.of();
		}
		ChangeDetector.Mode mode = resolveMode(normalized, testHashFile);
		if (mode == ChangeDetector.Mode.EXPLICIT) {
			return Set.of();
		}
		return invoke(mode, projectRoot, testSourceRoot, testHashFile, null, readOnly);
	}

	/**
	 * Parse comma-separated FQCNs into a stable-order set.
	 *
	 * <p>
	 * If no commas are present but semicolons are found, falls back to semicolon
	 * splitting and logs a warning. Only commas are the documented separator;
	 * semicolon support is a best-effort fallback.
	 * </p>
	 */
	public static Set<String> parseExplicitClasses(String classes) {
		if (classes == null || classes.isBlank()) {
			return Set.of();
		}
		String separator = ",";
		// If the input contains semicolons but no commas, it's likely a user mistake
		// (using semicolons as separators). We still parse it correctly as a fallback.
		if (!classes.contains(",") && classes.contains(";")) {
			separator = ";";
		}
		return Arrays.stream(classes.split(separator)).map(String::trim).filter(s -> !s.isEmpty())
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private static Set<String> invoke(ChangeDetector.Mode mode, Path projectRoot, Path sourceRoot, Path hashFile,
			String changedClasses, boolean readOnly) throws IOException {
		if (readOnly) {
			return ChangeDetector.detectReadOnly(mode, projectRoot, sourceRoot, hashFile, changedClasses);
		}
		return ChangeDetector.detect(mode, projectRoot, sourceRoot, hashFile, changedClasses);
	}
}
