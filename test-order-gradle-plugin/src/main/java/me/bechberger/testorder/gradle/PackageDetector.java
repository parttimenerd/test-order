package me.bechberger.testorder.gradle;

import org.gradle.api.logging.Logger;

import java.nio.file.Path;
import java.util.List;

import me.bechberger.testorder.PackageDetectorSupport;

/**
 * Delegates to {@link PackageDetectorSupport} from test-order-core and adds
 * Gradle-specific logging.
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
        return PackageDetectorSupport.detectSourcePackages(sourceRoot);
    }

    /**
     * Resolves the effective include-packages string, combining auto-detection,
     * user-specified packages, and optional groupId fallback.
     */
    static String resolveIncludePackages(String includePackages, boolean filterByGroupId,
                                         String groupId, Path sourceRoot, Logger logger) {
        String result = PackageDetectorSupport.resolveIncludePackages(sourceRoot, includePackages,
                filterByGroupId, groupId);
        if (result != null) {
            logger.lifecycle("[test-order] Instrumentation packages: {}", result);
        }
        return result;
    }

    /** Remove prefixes that are already covered by a shorter prefix in the list. */
    static List<String> minimisePrefixes(List<String> prefixes) {
        return PackageDetectorSupport.minimisePrefixes(prefixes);
    }
}
