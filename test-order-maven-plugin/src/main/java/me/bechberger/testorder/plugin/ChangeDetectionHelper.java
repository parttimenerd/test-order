package me.bechberger.testorder.plugin;

import me.bechberger.testorder.changes.ChangeDetector;
import me.bechberger.testorder.changes.FileHashStore;
import me.bechberger.testorder.changes.MethodHashStore;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared change-detection logic used by all test-order Mojos.
 * Eliminates duplicated {@code detectChangedClasses()} /
 * {@code detectChangedTestClasses()} methods.
 */
final class ChangeDetectionHelper {

    private ChangeDetectionHelper() {}

    /**
     * Resolves the main source root to use.
     * Priority: explicit config → Maven model → fallback to src/main/java.
     */
    static Path resolveSourceRoot(MavenProject project, String configured) {
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        List<String> roots = project.getCompileSourceRoots();
        if (roots != null && !roots.isEmpty()) return Path.of(roots.get(0));
        return project.getBasedir().toPath().resolve("src/main/java");
    }

    /**
     * Resolves the test source root to use.
     * Priority: explicit config → Maven model → fallback to src/test/java.
     */
    static Path resolveTestSourceRoot(MavenProject project, String configured) {
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        List<String> roots = project.getTestCompileSourceRoots();
        if (roots != null && !roots.isEmpty()) return Path.of(roots.get(0));
        return project.getBasedir().toPath().resolve("src/test/java");
    }

    /**
     * Detects changed classes using the configured change mode.
     *
     * @param readOnly  true to use {@link ChangeDetector#detectReadOnly} (no snapshot update)
     */
    static Set<String> detectChangedClasses(MavenProject project, String changeMode,
                                            String changedClasses, Path sourceRoot,
                                            Path hashPath, boolean readOnly,
                                            Log log) {
        try {
            Path projectRoot = project.getBasedir().toPath();
            return detectChanges(changeMode, projectRoot, sourceRoot, hashPath,
                    changedClasses, readOnly, false);
        } catch (IOException e) {
            log.warn("[test-order] Change detection failed: " + e.getMessage()
                    + " — falling back to no changes");
            return Set.of();
        }
    }

    /**
     * Detects changed test classes using the configured change mode.
     * EXPLICIT mode always returns empty (explicit only applies to main sources).
     */
    static Set<String> detectChangedTestClasses(MavenProject project, String changeMode,
                                                Path testSourceRoot, Path testHashPath,
                                                boolean readOnly, Log log) {
        try {
            Path projectRoot = project.getBasedir().toPath();
            return detectChanges(changeMode, projectRoot, testSourceRoot, testHashPath,
                    null, readOnly, true);
        } catch (IOException e) {
            log.warn("[test-order] Test change detection failed: " + e.getMessage()
                    + " — falling back to no changes");
            return Set.of();
        }
    }

    /**
     * Snapshots file hashes for both main and test sources,
     * and per-method hashes for test sources.
     */
    static void snapshotHashes(Path sourceRoot, Path hashFile,
                               Path testSourceRoot, Path testHashFile,
                               Path methodHashFile, Log log) {
        snapshot(sourceRoot, hashFile, "source", log);
        snapshot(testSourceRoot, testHashFile, "test source", log);
        snapshotMethodHashes(testSourceRoot, methodHashFile, log);
    }

    private static void snapshotMethodHashes(Path testSourceRoot, Path methodHashFile, Log log) {
        try {
            if (Files.isDirectory(testSourceRoot)) {
                MethodHashStore store = MethodHashStore.scan(testSourceRoot);
                store.save(methodHashFile);
                log.info("[test-order] Saved method hash snapshot (" + store.getHashes().size()
                        + " methods): " + methodHashFile);
            }
        } catch (IOException e) {
            log.warn("[test-order] Failed to save method hash snapshot: " + e.getMessage());
        }
    }

    /**
     * Detects changed test methods by comparing current method hashes against a previous snapshot.
     * Returns a set of {@code className#methodName} keys.
     */
    static Set<String> detectChangedMethods(Path testSourceRoot, Path methodHashFile, Log log) {
        try {
            if (!Files.isDirectory(testSourceRoot)) return Set.of();
            MethodHashStore current = MethodHashStore.scan(testSourceRoot);
            if (Files.exists(methodHashFile)) {
                MethodHashStore previous = MethodHashStore.load(methodHashFile);
                return current.getChangedMethods(previous);
            }
            // no previous snapshot → all methods are "changed"
            return current.getHashes().keySet();
        } catch (IOException e) {
            log.warn("[test-order] Method change detection failed: " + e.getMessage());
            return Set.of();
        }
    }

    private static void snapshot(Path root, Path hashFile, String label, Log log) {
        try {
            if (Files.isDirectory(root)) {
                FileHashStore store = FileHashStore.scan(root);
                store.save(hashFile);
                log.info("[test-order] Saved " + label + " hash snapshot: " + hashFile);
            }
        } catch (IOException e) {
            log.warn("[test-order] Failed to save " + label + " hash snapshot: " + e.getMessage());
        }
    }

    private static Set<String> detectChanges(String changeMode, Path projectRoot,
                                             Path sourceRoot, Path hashPath,
                                             String changedClasses, boolean readOnly,
                                             boolean skipExplicit) throws IOException {
        if ("auto".equals(changeMode)) {
            if (!skipExplicit && changedClasses != null && !changedClasses.isBlank()) {
                return invoke(ChangeDetector.Mode.EXPLICIT, projectRoot, sourceRoot,
                        hashPath, changedClasses, readOnly);
            }
            if (Files.exists(hashPath)) {
                return invoke(ChangeDetector.Mode.SINCE_LAST_RUN, projectRoot, sourceRoot,
                        hashPath, null, readOnly);
            }
            return invoke(ChangeDetector.Mode.SINCE_LAST_COMMIT, projectRoot, sourceRoot,
                    hashPath, null, readOnly);
        }

        ChangeDetector.Mode mode = parseMode(changeMode);
        if (skipExplicit && mode == ChangeDetector.Mode.EXPLICIT) return Set.of();
        return invoke(mode, projectRoot, sourceRoot, hashPath, changedClasses, readOnly);
    }

    private static Set<String> invoke(ChangeDetector.Mode mode, Path projectRoot,
                                      Path sourceRoot, Path hashPath,
                                      String changedClasses, boolean readOnly) throws IOException {
        if (readOnly) {
            return ChangeDetector.detectReadOnly(mode, projectRoot, sourceRoot, hashPath, changedClasses);
        }
        return ChangeDetector.detect(mode, projectRoot, sourceRoot, hashPath, changedClasses);
    }

    // --- ReactorContext-aware overloads ---

    /**
     * Detects changed classes using ReactorContext for path resolution and
     * cross-module change propagation. Merges upstream module changes and
     * stores this module's results in the session for downstream modules.
     */
    static Set<String> detectChangedClasses(ReactorContext ctx, String changeMode,
                                            String changedClasses, Path sourceRoot,
                                            Path hashPath, boolean readOnly,
                                            Log log) {
        Set<String> own = detectChangedClasses(ctx.project(), changeMode, changedClasses,
                sourceRoot, hashPath, readOnly, log);
        ctx.storeChangedClasses(own);
        Set<String> upstream = ctx.collectUpstreamChangedClasses();
        if (upstream.isEmpty()) return own;
        Set<String> merged = new LinkedHashSet<>(own);
        merged.addAll(upstream);
        return merged;
    }

    /**
     * Detects changed test classes using ReactorContext for path resolution and
     * cross-module change propagation.
     */
    static Set<String> detectChangedTestClasses(ReactorContext ctx, String changeMode,
                                                Path testSourceRoot, Path testHashPath,
                                                boolean readOnly, Log log) {
        Set<String> own = detectChangedTestClasses(ctx.project(), changeMode,
                testSourceRoot, testHashPath, readOnly, log);
        ctx.storeChangedTestClasses(own);
        Set<String> upstream = ctx.collectUpstreamChangedTestClasses();
        if (upstream.isEmpty()) return own;
        Set<String> merged = new LinkedHashSet<>(own);
        merged.addAll(upstream);
        return merged;
    }

    static ChangeDetector.Mode parseMode(String changeMode) throws IOException {
        return switch (changeMode) {
            case "since-last-run" -> ChangeDetector.Mode.SINCE_LAST_RUN;
            case "since-last-commit" -> ChangeDetector.Mode.SINCE_LAST_COMMIT;
            case "uncommitted" -> ChangeDetector.Mode.UNCOMMITTED;
            case "explicit" -> ChangeDetector.Mode.EXPLICIT;
            default -> throw new IOException("Unknown changeMode: " + changeMode);
        };
    }
}
