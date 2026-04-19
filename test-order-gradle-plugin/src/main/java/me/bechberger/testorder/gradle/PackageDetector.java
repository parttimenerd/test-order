package me.bechberger.testorder.gradle;

import org.gradle.api.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Detects top-level package prefixes from a Java source root.
 * Walks down single-child directory chains to find a stable prefix.
 * <p>
 * Ported from {@code AbstractTestOrderMojo.detectSourcePackages()}.
 */
final class PackageDetector {

    private PackageDetector() {}

    /**
     * Scans the given source root for top-level package prefixes.
     *
     * @param sourceRoot path to {@code src/main/java} (or similar)
     * @param logger     Gradle logger for debug output
     * @return list of detected package prefixes (e.g. {@code ["com.example.app"]})
     */
    static List<String> detectSourcePackages(Path sourceRoot, Logger logger) {
        List<String> result = new ArrayList<>();
        if (!Files.isDirectory(sourceRoot)) return result;
        try (Stream<Path> topDirs = Files.list(sourceRoot)) {
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
            logger.debug("[test-order] Failed to scan source root {}: {}", sourceRoot, e.getMessage());
        }
        return result;
    }

    /**
     * Resolves the effective include-packages string, combining auto-detection,
     * user-specified packages, and optional groupId fallback.
     *
     * @param includePackages user-specified comma-separated packages (may be empty)
     * @param filterByGroupId whether to fall back to groupId
     * @param groupId         project groupId (for fallback)
     * @param sourceRoot      path to main source root
     * @param logger          Gradle logger
     * @return resolved packages string (semicolon-separated for agent), or null if nothing detected
     */
    static String resolveIncludePackages(String includePackages, boolean filterByGroupId,
                                         String groupId, Path sourceRoot, Logger logger) {
        List<String> prefixes = new ArrayList<>();

        // 1. Scan source root for actual packages
        List<String> sourcePackages = detectSourcePackages(sourceRoot, logger);
        prefixes.addAll(sourcePackages);

        // 2. Add user-specified packages (additive)
        if (includePackages != null && !includePackages.isBlank()) {
            for (String pkg : includePackages.split(",")) {
                String trimmed = pkg.trim();
                if (!trimmed.isEmpty()) prefixes.add(trimmed);
            }
        }

        // 3. Always include groupId when filterByGroupId is true — this ensures
        //    cross-module dependencies are captured in multi-module projects
        //    (e.g. service module also instruments core module classes).
        //    minimisePrefixes() deduplicates overlapping prefixes.
        if (filterByGroupId && groupId != null && !groupId.isBlank()) {
            prefixes.add(groupId);
        }

        if (prefixes.isEmpty()) return null;

        List<String> minimal = minimisePrefixes(prefixes);
        String result = String.join(",", minimal);
        logger.lifecycle("[test-order] Instrumentation packages: {}", result);
        return result;
    }

    /** Remove prefixes that are already covered by a shorter prefix in the list. */
    static List<String> minimisePrefixes(List<String> prefixes) {
        List<String> sorted = prefixes.stream().distinct().sorted().toList();
        List<String> result = new ArrayList<>();
        for (String p : sorted) {
            boolean covered = result.stream().anyMatch(r -> p.startsWith(r + ".") || p.equals(r));
            if (!covered) result.add(p);
        }
        return result;
    }
}
