package me.bechberger.testorder.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;

/**
 * Reactor-aware context for multi-module builds.
 * <p>
 * In a multi-module reactor build, redirects all data paths to a shared
 * {@code <reactor-root>/.test-order/} directory and provides cross-module
 * change propagation via the Maven project dependency graph.
 * <p>
 * In a single-module build, all paths are resolved under
 * {@code <project-root>/.test-order/}.
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

	boolean isMultiModule() {
		return multiModule;
	}
	Path reactorRoot() {
		return reactorRoot;
	}
	Path sharedDir() {
		return sharedDir;
	}
	/**
	 * Returns the .test-order directory (shared dir) for cache writability checks.
	 */
	Path resolveBaseDir() {
		return sharedDir;
	}
	MavenSession session() {
		return session;
	}
	MavenProject project() {
		return project;
	}
	String moduleId() {
		// Use groupId-artifactId to avoid collisions when different modules share
		// the same artifactId (e.g. multiple modules named "api" across groups)
		String gid = project.getGroupId();
		return (gid == null || gid.isEmpty()) ? project.getArtifactId() : gid + "-" + project.getArtifactId();
	}

	/**
	 * Returns the path prefix for the current module relative to the reactor root.
	 */
	String modulePrefix() {
		Path moduleDir = project.getBasedir().toPath();
		return reactorRoot.relativize(moduleDir).toString();
	}

	// --- Directory setup ---

	/**
	 * Creates shared directories if they don't exist.
	 */
	void ensureSharedDirectories() throws IOException {
		Files.createDirectories(sharedDir);
		if (multiModule) {
			Files.createDirectories(sharedDir.resolve("hashes"));
			Files.createDirectories(sharedDir.resolve("deps"));
		}
	}

	// --- Path resolution ---

	Path resolveIndexFile(String configured) {
		return multiModule
				? sharedDir.resolve("test-dependencies.lz4")
				: resolveConfiguredPath(configured,
						project.getBasedir().toPath().resolve(".test-order/test-dependencies.lz4"));
	}

	Path resolveStateFile(String configured) {
		return multiModule
				? sharedDir.resolve("state.lz4")
				: resolveConfiguredPath(configured, project.getBasedir().toPath().resolve(".test-order/state.lz4"));
	}

	Path resolveDepsDir(String configured) {
		if (multiModule) {
			return sharedDir.resolve("deps");
		}
		if (configured != null && !configured.isBlank()) {
			return Path.of(configured);
		}
		return Path.of(project.getBuild().getDirectory()).resolve("test-order-deps");
	}

	Path resolveHashFile(String configured) {
		return multiModule
				? sharedDir.resolve("hashes").resolve(moduleId() + "-hashes.lz4")
				: resolveConfiguredPath(configured, project.getBasedir().toPath().resolve(".test-order/hashes.lz4"));
	}

	Path resolveTestHashFile(String configured) {
		return multiModule
				? sharedDir.resolve("hashes").resolve(moduleId() + "-test-hashes.lz4")
				: resolveConfiguredPath(configured,
						project.getBasedir().toPath().resolve(".test-order/test-hashes.lz4"));
	}

	Path resolveMethodHashFile(String configured) {
		return multiModule
				? sharedDir.resolve("hashes").resolve(moduleId() + "-method-hashes.lz4")
				: resolveConfiguredPath(configured,
						project.getBasedir().toPath().resolve(".test-order/method-hashes.lz4"));
	}

	private Path resolveConfiguredPath(String configured, Path fallback) {
		if (configured == null || configured.isBlank()) {
			return fallback;
		}
		return Path.of(configured);
	}

	// --- Cross-module change propagation ---

	/**
	 * Stores this module's changed classes in the session for downstream modules.
	 */
	void storeChangedClasses(Set<String> changed) {
		if (multiModule && !changed.isEmpty()) {
			session.getUserProperties().put(CHANGED_CLASSES_KEY + moduleId(), String.join(",", changed));
		}
	}

	/**
	 * Stores this module's changed test classes in the session for downstream
	 * modules.
	 */
	void storeChangedTestClasses(Set<String> changedTests) {
		if (multiModule && !changedTests.isEmpty()) {
			session.getUserProperties().put(CHANGED_TEST_CLASSES_KEY + moduleId(), String.join(",", changedTests));
		}
	}

	/**
	 * Collects changed classes from all transitive upstream modules.
	 */
	Set<String> collectUpstreamChangedClasses() {
		if (!multiModule)
			return Set.of();
		return collectUpstreamProperty(CHANGED_CLASSES_KEY);
	}

	/**
	 * Collects changed test classes from all transitive upstream modules.
	 */
	Set<String> collectUpstreamChangedTestClasses() {
		if (!multiModule)
			return Set.of();
		return collectUpstreamProperty(CHANGED_TEST_CLASSES_KEY);
	}

	private Set<String> collectUpstreamProperty(String keyPrefix) {
		ProjectDependencyGraph graph = session.getProjectDependencyGraph();
		if (graph == null)
			return Set.of();

		Set<String> result = new LinkedHashSet<>();
		List<MavenProject> upstream = graph.getUpstreamProjects(project, true);
		for (MavenProject up : upstream) {
			String gid = up.getGroupId();
			String upId = (gid == null || gid.isEmpty()) ? up.getArtifactId() : gid + "-" + up.getArtifactId();
			String value = session.getUserProperties().getProperty(keyPrefix + upId);
			if (value != null && !value.isBlank()) {
				Collections.addAll(result, value.split(","));
			}
		}
		return result;
	}

	/**
	 * Returns the root for git operations. In multi-module mode, uses the reactor
	 * root (single git diff covers all modules). In single-module mode, uses the
	 * project basedir.
	 */
	Path gitRoot() {
		return multiModule ? reactorRoot : project.getBasedir().toPath();
	}
}
