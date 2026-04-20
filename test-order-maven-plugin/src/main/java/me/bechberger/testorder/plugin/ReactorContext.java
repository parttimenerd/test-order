package me.bechberger.testorder.plugin;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.execution.ProjectDependencyGraph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Reactor-aware context for multi-module builds.
 * <p>
 * In a multi-module reactor build, redirects all data paths to a shared
 * {@code <reactor-root>/.test-order/} directory and provides cross-module
 * change propagation via the Maven project dependency graph.
 * <p>
 * In a single-module build, all paths are passed through unchanged for
 * backward compatibility.
 */
final class ReactorContext {

    private static final String SHARED_DIR_NAME = ".test-order";
    private static final String CHANGED_CLASSES_KEY = MavenPluginConfigKeys.CHANGED_CLASSES + ".";
    private static final String CHANGED_TEST_CLASSES_KEY = MavenPluginConfigKeys.CHANGED_TEST_CLASSES + ".";

    private final MavenSession session;
    private final MavenProject project;
    private final boolean multiModule;
    private final Path reactorRoot;
    private final Path sharedDir;

    ReactorContext(MavenSession session, MavenProject project) {
        this.session = session;
        this.project = project;
        this.multiModule = session.getProjects().size() > 1;
        this.reactorRoot = session.getTopLevelProject().getBasedir().toPath();
        this.sharedDir = reactorRoot.resolve(SHARED_DIR_NAME);
    }

    boolean isMultiModule() { return multiModule; }
    Path reactorRoot() { return reactorRoot; }
    Path sharedDir() { return sharedDir; }
    MavenSession session() { return session; }
    MavenProject project() { return project; }
    String moduleId() { return project.getArtifactId(); }

    /**
     * Returns the path prefix for the current module relative to the reactor root.
     */
    String modulePrefix() {
        Path moduleDir = project.getBasedir().toPath();
        return reactorRoot.relativize(moduleDir).toString();
    }

    // --- Directory setup ---

    /**
     * Creates shared directories for multi-module builds if they don't exist.
     */
    void ensureSharedDirectories() throws IOException {
        if (multiModule) {
            Files.createDirectories(sharedDir.resolve("hashes"));
            Files.createDirectories(sharedDir.resolve("deps"));
        }
    }

    // --- Path resolution ---

    Path resolveIndexFile(String configured) {
        return multiModule ? sharedDir.resolve("test-dependencies.lz4") : Path.of(configured);
    }

    Path resolveStateFile(String configured) {
        return multiModule ? sharedDir.resolve(".test-order-state") : Path.of(configured);
    }

    Path resolveDepsDir(String configured) {
        return multiModule ? sharedDir.resolve("deps") : Path.of(configured);
    }

    Path resolveHashFile(String configured) {
        return multiModule
                ? sharedDir.resolve("hashes").resolve(moduleId() + "-hashes.lz4")
                : Path.of(configured);
    }

    Path resolveTestHashFile(String configured) {
        return multiModule
                ? sharedDir.resolve("hashes").resolve(moduleId() + "-test-hashes.lz4")
                : Path.of(configured);
    }

    Path resolveMethodHashFile(String configured) {
        return multiModule
                ? sharedDir.resolve("hashes").resolve(moduleId() + "-method-hashes.lz4")
                : Path.of(configured);
    }

    // --- Cross-module change propagation ---

    /**
     * Stores this module's changed classes in the session for downstream modules.
     */
    void storeChangedClasses(Set<String> changed) {
        if (multiModule && !changed.isEmpty()) {
            session.getUserProperties().put(
                    CHANGED_CLASSES_KEY + moduleId(),
                    String.join(",", changed));
        }
    }

    /**
     * Stores this module's changed test classes in the session for downstream modules.
     */
    void storeChangedTestClasses(Set<String> changedTests) {
        if (multiModule && !changedTests.isEmpty()) {
            session.getUserProperties().put(
                    CHANGED_TEST_CLASSES_KEY + moduleId(),
                    String.join(",", changedTests));
        }
    }

    /**
     * Collects changed classes from all transitive upstream modules.
     */
    Set<String> collectUpstreamChangedClasses() {
        if (!multiModule) return Set.of();
        return collectUpstreamProperty(CHANGED_CLASSES_KEY);
    }

    /**
     * Collects changed test classes from all transitive upstream modules.
     */
    Set<String> collectUpstreamChangedTestClasses() {
        if (!multiModule) return Set.of();
        return collectUpstreamProperty(CHANGED_TEST_CLASSES_KEY);
    }

    private Set<String> collectUpstreamProperty(String keyPrefix) {
        ProjectDependencyGraph graph = session.getProjectDependencyGraph();
        if (graph == null) return Set.of();

        Set<String> result = new LinkedHashSet<>();
        List<MavenProject> upstream = graph.getUpstreamProjects(project, true);
        for (MavenProject up : upstream) {
            String value = session.getUserProperties().getProperty(
                    keyPrefix + up.getArtifactId());
            if (value != null && !value.isBlank()) {
                Collections.addAll(result, value.split(","));
            }
        }
        return result;
    }

    /**
     * Returns the root for git operations.
     * In multi-module mode, uses the reactor root (single git diff covers all modules).
     * In single-module mode, uses the project basedir.
     */
    Path gitRoot() {
        return multiModule ? reactorRoot : project.getBasedir().toPath();
    }
}
