package me.bechberger.testorder.maven;

import java.io.File;
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
 * <b>Important:</b> In multi-module mode, the dependency index, state file, and
 * hash files are stored at the reactor root level (e.g.
 * {@code my-project/.test-order/test-dependencies.lz4}), NOT per-module. This
 * means all modules share a single index and state file. Changes detected in
 * upstream modules are automatically propagated to downstream modules.
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

		ResolvedRoot resolved = resolveReactorRoot(session, project);
		this.multiModule = resolved.multiModule;
		this.reactorRoot = resolved.root;
		this.sharedDir = reactorRoot.resolve(SHARED_DIR_NAME);
	}

	/**
	 * Pure helper that runs the same primary/secondary/tertiary detection used by
	 * the constructor. Exposed so other build participants (e.g.
	 * {@link CollectorLifecycleParticipant}) can resolve the reactor root without
	 * instantiating a {@code ReactorContext} for every project.
	 */
	static ResolvedRoot resolveReactorRoot(MavenSession session, MavenProject project) {
		// Primary detection: multiple projects in the reactor
		boolean explicitMulti = session.getProjects().size() > 1;

		// Secondary detection: running -pl <module> without -am.
		// In this case the reactor has only 1 project but the project is still
		// a submodule. We detect this by comparing the project basedir to
		// getMultiModuleProjectDirectory() and checking if the reactor root
		// already has a .test-order/ directory from a prior multi-module run.
		boolean inferredMulti = false;
		Path mmDir = resolveMultiModuleRoot(session);
		Path projectDir = project.getBasedir().toPath().normalize();
		// Use the invocation directory as discriminator: for -pl <module> without -am,
		// the user runs mvn from the reactor root so executionRootDirectory == mmDir.
		// When a third-party project sits inside an unrelated Maven project tree, Maven
		// may walk up to that project's .mvn/, but executionRootDirectory stays at the
		// project where mvn was actually invoked, so mmDir != executionRootDir.
		Path executionRootDir = resolveExecutionRootDir(session);
		if (!explicitMulti && mmDir != null && !projectDir.equals(mmDir) && mmDir.equals(executionRootDir)
				&& Files.isDirectory(mmDir.resolve(SHARED_DIR_NAME))) {
			inferredMulti = true;
		}

		// Tertiary detection: -pl <module> in a project without `.mvn/`, OR running
		// mvn directly from a module directory. Maven then sets
		// multiModuleProjectDirectory to the module dir, hiding the reactor. Walk up
		// from the module looking for an ancestor that has both a pom.xml and a
		// `.test-order/` directory. To avoid firing for a third-party project nested
		// inside an unrelated repo, only treat the walked root as the reactor root
		// when this project's Maven parent chain leads back to it (the parent POM's
		// basedir matches the walked root) OR the user invoked mvn from there.
		if (!explicitMulti && !inferredMulti) {
			Path walkedRoot = findReactorRootWithSharedDir(projectDir);
			if (walkedRoot != null && (walkedRoot.equals(executionRootDir) || isParentChain(project, walkedRoot))) {
				inferredMulti = true;
				mmDir = walkedRoot;
			}
		}

		// Normalize the reactor root so all derived paths are canonical. Without this,
		// `<reactorRoot>/.test-order/class-id-map.bin` resolved by different modules
		// could differ in surface form (e.g. with/without `..` segments) — fine for
		// `Files.exists` but potentially trips up `Path.equals` comparisons or
		// FileLock identity in `mvn -T`.
		Path topLevel = session.getTopLevelProject() != null
				? session.getTopLevelProject().getBasedir().toPath()
				: project.getBasedir().toPath();
		Path root = (inferredMulti ? mmDir : topLevel).normalize();
		return new ResolvedRoot(explicitMulti || inferredMulti, root);
	}

	/** Result of {@link #resolveReactorRoot(MavenSession, MavenProject)}. */
	static final class ResolvedRoot {
		final boolean multiModule;
		final Path root;
		ResolvedRoot(boolean multiModule, Path root) {
			this.multiModule = multiModule;
			this.root = root;
		}
	}

	/**
	 * Walks up from {@code start} looking for an ancestor directory that contains
	 * both a {@code pom.xml} and a {@code .test-order/} directory. Returns the
	 * deepest such ancestor (the closest reactor root with an existing index), or
	 * {@code null} if none is found before hitting the filesystem root.
	 */
	private static Path findReactorRootWithSharedDir(Path start) {
		Path dir = start.getParent();
		while (dir != null) {
			if (Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve(SHARED_DIR_NAME))) {
				return dir.normalize();
			}
			dir = dir.getParent();
		}
		return null;
	}

	/**
	 * Returns {@code true} if the project's Maven parent chain leads to a project
	 * whose basedir is {@code candidate}. This confirms the project is genuinely a
	 * submodule of the candidate root (not a third-party project nested inside an
	 * unrelated Maven tree).
	 */
	private static boolean isParentChain(MavenProject project, Path candidate) {
		MavenProject p = project.getParent();
		int safety = 32;
		while (p != null && safety-- > 0) {
			File base = p.getBasedir();
			if (base != null && base.toPath().normalize().equals(candidate)) {
				return true;
			}
			p = p.getParent();
		}
		return false;
	}

	private static Path resolveExecutionRootDir(MavenSession session) {
		try {
			String dir = session.getExecutionRootDirectory();
			return dir != null ? Path.of(dir).normalize() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static Path resolveMultiModuleRoot(MavenSession session) {
		try {
			File dir = session.getRequest().getMultiModuleProjectDirectory();
			return dir != null ? dir.toPath().normalize() : null;
		} catch (Exception e) {
			// Fallback for older Maven versions or mocked sessions
			return null;
		}
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

	Path resolveBytecodeHashFile(String configured) {
		return multiModule
				? sharedDir.resolve("hashes").resolve(moduleId() + "-bytecode-hashes.lz4")
				: resolveConfiguredPath(configured,
						project.getBasedir().toPath().resolve(".test-order/bytecode-hashes.lz4"));
	}

	/**
	 * Returns the path of the reactor-wide class-id map. Lives at
	 * {@code <reactorRoot>/.test-order/class-id-map.bin} so every module's prepare
	 * and every test fork share a single ID space — required for cross-module edge
	 * capture, where a classId baked into module-A's instrumented bytecode must
	 * mean the same FQN when module-B's fork records it.
	 */
	Path resolveClassIdMapFile() {
		return sharedDir.resolve("class-id-map.bin");
	}

	private Path resolveConfiguredPath(String configured, Path fallback) {
		if (configured == null || configured.isBlank()) {
			return fallback;
		}
		Path p = Path.of(configured);
		return p.isAbsolute() ? p : project.getBasedir().toPath().resolve(p).toAbsolutePath().normalize();
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
			String upId = ModuleIds.of(up);
			String value = session.getUserProperties().getProperty(keyPrefix + upId);
			if (value != null && !value.isBlank()) {
				// Split and trim to handle potential spaces (though join uses no spaces)
				for (String item : value.split(",")) {
					String trimmed = item.trim();
					if (!trimmed.isEmpty()) {
						result.add(trimmed);
					}
				}
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
