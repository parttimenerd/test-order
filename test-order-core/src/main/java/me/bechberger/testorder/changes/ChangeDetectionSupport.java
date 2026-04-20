package me.bechberger.testorder.changes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared helpers for resolving and executing change-detection modes.
 *
 * <p>This utility keeps mode parsing and auto-mode behavior consistent across
 * Maven and Gradle adapters.</p>
 */
public final class ChangeDetectionSupport {

    private ChangeDetectionSupport() {}

    /**
     * Parse an explicit change-detection mode string.
     *
     * @throws IOException when the mode is unknown
     */
    public static ChangeDetector.Mode parseMode(String changeMode) throws IOException {
        return switch (changeMode) {
            case "since-last-run" -> ChangeDetector.Mode.SINCE_LAST_RUN;
            case "since-last-commit" -> ChangeDetector.Mode.SINCE_LAST_COMMIT;
            case "uncommitted" -> ChangeDetector.Mode.UNCOMMITTED;
            case "explicit" -> ChangeDetector.Mode.EXPLICIT;
            default -> throw new IOException("Unknown changeMode: " + changeMode);
        };
    }

    /**
     * Resolve change mode including {@code auto} behavior based on snapshot existence.
     */
    public static ChangeDetector.Mode resolveMode(String changeMode, Path hashFile) throws IOException {
        if ("auto".equalsIgnoreCase(changeMode)) {
            return Files.exists(hashFile)
                    ? ChangeDetector.Mode.SINCE_LAST_RUN
                    : ChangeDetector.Mode.SINCE_LAST_COMMIT;
        }
        return parseMode(changeMode);
    }

    /**
     * Detect changed production classes.
     *
     * <p>In {@code auto} mode, explicit changed classes (when provided) take precedence,
     * otherwise mode resolves from snapshot presence.</p>
     */
    public static Set<String> detectChangedClasses(String changeMode,
                                                   Path projectRoot,
                                                   Path sourceRoot,
                                                   Path hashFile,
                                                   String changedClasses,
                                                   boolean readOnly) throws IOException {
        if ("auto".equalsIgnoreCase(changeMode) && changedClasses != null && !changedClasses.isBlank()) {
            return parseExplicitClasses(changedClasses);
        }
        ChangeDetector.Mode mode = resolveMode(changeMode, hashFile);
        if (mode == ChangeDetector.Mode.EXPLICIT) {
            if (changedClasses == null || changedClasses.isBlank()) {
                return Set.of();
            }
            return parseExplicitClasses(changedClasses);
        }
        return invoke(mode, projectRoot, sourceRoot, hashFile, changedClasses, readOnly);
    }

    /**
     * Detect changed test classes.
     *
     * <p>Explicit mode is intentionally ignored for test sources and returns an empty set.</p>
     */
    public static Set<String> detectChangedTestClasses(String changeMode,
                                                       Path projectRoot,
                                                       Path testSourceRoot,
                                                       Path testHashFile,
                                                       boolean readOnly) throws IOException {
        if ("explicit".equalsIgnoreCase(changeMode)) {
            return Set.of();
        }
        ChangeDetector.Mode mode = resolveMode(changeMode, testHashFile);
        if (mode == ChangeDetector.Mode.EXPLICIT) {
            return Set.of();
        }
        return invoke(mode, projectRoot, testSourceRoot, testHashFile, null, readOnly);
    }

    /**
     * Parse comma-separated FQCNs into a stable-order set.
     */
    public static Set<String> parseExplicitClasses(String classes) {
        if (classes == null || classes.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(classes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> invoke(ChangeDetector.Mode mode,
                                      Path projectRoot,
                                      Path sourceRoot,
                                      Path hashFile,
                                      String changedClasses,
                                      boolean readOnly) throws IOException {
        if (readOnly) {
            return ChangeDetector.detectReadOnly(mode, projectRoot, sourceRoot, hashFile, changedClasses);
        }
        return ChangeDetector.detect(mode, projectRoot, sourceRoot, hashFile, changedClasses);
    }
}
