package me.bechberger.testorder.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactorContextTest {

	@TempDir
	Path tempDir;
	private MavenSession session;
	private Properties userProps;

	@BeforeEach
	void setUp() {
		session = mock(MavenSession.class);
		userProps = new Properties();
		when(session.getUserProperties()).thenReturn(userProps);
	}

	// --- Single-module tests ---

	private ReactorContext singleModuleContext() {
		MavenProject project = mockProject("my-app", tempDir.resolve("my-app"));
		when(session.getProjects()).thenReturn(List.of(project));
		when(session.getTopLevelProject()).thenReturn(project);
		return new ReactorContext(session, project);
	}

	@Test
	void singleModule_isNotMultiModule() {
		ReactorContext ctx = singleModuleContext();
		assertThat(ctx.isMultiModule()).isFalse();
	}

	@Test
	void singleModule_resolvesPathsDirectly() {
		ReactorContext ctx = singleModuleContext();
		assertThat(ctx.resolveIndexFile("/some/path/idx")).isEqualTo(Path.of("/some/path/idx"));
		assertThat(ctx.resolveStateFile("/some/path/state")).isEqualTo(Path.of("/some/path/state"));
		assertThat(ctx.resolveDepsDir("/some/path/deps")).isEqualTo(Path.of("/some/path/deps"));
		assertThat(ctx.resolveHashFile("/some/path/hash")).isEqualTo(Path.of("/some/path/hash"));
		assertThat(ctx.resolveTestHashFile("/some/path/thash")).isEqualTo(Path.of("/some/path/thash"));
	}

	@Test
	void singleModule_gitRootIsProjectDir() {
		ReactorContext ctx = singleModuleContext();
		assertThat(ctx.gitRoot()).isEqualTo(tempDir.resolve("my-app"));
	}

	@Test
	void singleModule_upstreamChangesAreEmpty() {
		ReactorContext ctx = singleModuleContext();
		assertThat(ctx.collectUpstreamChangedClasses()).isEmpty();
		assertThat(ctx.collectUpstreamChangedTestClasses()).isEmpty();
	}

	@Test
	void singleModule_storeChangedClassesDoesNothing() {
		ReactorContext ctx = singleModuleContext();
		ctx.storeChangedClasses(Set.of("com.Foo"));
		assertThat(userProps).isEmpty();
	}

	// --- Multi-module tests ---

	private record MultiModuleSetup(ReactorContext rootCtx, ReactorContext moduleACtx, ReactorContext moduleBCtx,
			MavenProject root, MavenProject moduleA, MavenProject moduleB) {
	}

	private MultiModuleSetup multiModuleSetup() {
		Path rootDir = tempDir.resolve("root");
		MavenProject root = mockProject("root", rootDir);
		MavenProject moduleA = mockProject("module-a", rootDir.resolve("module-a"));
		MavenProject moduleB = mockProject("module-b", rootDir.resolve("module-b"));

		when(session.getProjects()).thenReturn(List.of(root, moduleA, moduleB));
		when(session.getTopLevelProject()).thenReturn(root);

		ProjectDependencyGraph graph = mock(ProjectDependencyGraph.class);
		when(session.getProjectDependencyGraph()).thenReturn(graph);
		// module-b depends on module-a transitively
		when(graph.getUpstreamProjects(moduleB, true)).thenReturn(List.of(moduleA));
		when(graph.getUpstreamProjects(moduleA, true)).thenReturn(List.of());
		when(graph.getUpstreamProjects(root, true)).thenReturn(List.of());

		return new MultiModuleSetup(new ReactorContext(session, root), new ReactorContext(session, moduleA),
				new ReactorContext(session, moduleB), root, moduleA, moduleB);
	}

	@Test
	void multiModule_isMultiModule() {
		var mm = multiModuleSetup();
		assertThat(mm.rootCtx.isMultiModule()).isTrue();
		assertThat(mm.moduleACtx.isMultiModule()).isTrue();
		assertThat(mm.moduleBCtx.isMultiModule()).isTrue();
	}

	@Test
	void multiModule_sharedIndexFile() {
		var mm = multiModuleSetup();
		Path expected = tempDir.resolve("root/.test-order/test-dependencies.lz4");
		// Both modules resolve to the same shared index
		assertThat(mm.moduleACtx.resolveIndexFile("ignored")).isEqualTo(expected);
		assertThat(mm.moduleBCtx.resolveIndexFile("ignored")).isEqualTo(expected);
	}

	@Test
	void multiModule_sharedStateFile() {
		var mm = multiModuleSetup();
		Path expected = tempDir.resolve("root/.test-order/state.lz4");
		assertThat(mm.moduleACtx.resolveStateFile("ignored")).isEqualTo(expected);
		assertThat(mm.moduleBCtx.resolveStateFile("ignored")).isEqualTo(expected);
	}

	@Test
	void multiModule_sharedDepsDir() {
		var mm = multiModuleSetup();
		Path expected = tempDir.resolve("root/.test-order/deps");
		assertThat(mm.moduleACtx.resolveDepsDir("ignored")).isEqualTo(expected);
		assertThat(mm.moduleBCtx.resolveDepsDir("ignored")).isEqualTo(expected);
	}

	@Test
	void multiModule_perModuleHashFiles() {
		var mm = multiModuleSetup();
		assertThat(mm.moduleACtx.resolveHashFile("ignored"))
				.isEqualTo(tempDir.resolve("root/.test-order/hashes/module-a-hashes.lz4"));
		assertThat(mm.moduleBCtx.resolveHashFile("ignored"))
				.isEqualTo(tempDir.resolve("root/.test-order/hashes/module-b-hashes.lz4"));
	}

	@Test
	void multiModule_perModuleTestHashFiles() {
		var mm = multiModuleSetup();
		assertThat(mm.moduleACtx.resolveTestHashFile("ignored"))
				.isEqualTo(tempDir.resolve("root/.test-order/hashes/module-a-test-hashes.lz4"));
		assertThat(mm.moduleBCtx.resolveTestHashFile("ignored"))
				.isEqualTo(tempDir.resolve("root/.test-order/hashes/module-b-test-hashes.lz4"));
	}

	@Test
	void multiModule_perModuleMethodHashFiles() {
		var mm = multiModuleSetup();
		assertThat(mm.moduleACtx.resolveMethodHashFile("ignored"))
				.isEqualTo(tempDir.resolve("root/.test-order/hashes/module-a-method-hashes.lz4"));
		assertThat(mm.moduleBCtx.resolveMethodHashFile("ignored"))
				.isEqualTo(tempDir.resolve("root/.test-order/hashes/module-b-method-hashes.lz4"));
	}

	@Test
	void multiModule_distinctModulesHaveSeparateHashFilePaths() {
		// Verify that different modules never resolve to the same hash file path,
		// ensuring no cross-module hash file collision in parallel builds.
		var mm = multiModuleSetup();
		assertThat(mm.moduleACtx.resolveHashFile("ignored")).isNotEqualTo(mm.moduleBCtx.resolveHashFile("ignored"));
		assertThat(mm.moduleACtx.resolveTestHashFile("ignored"))
				.isNotEqualTo(mm.moduleBCtx.resolveTestHashFile("ignored"));
		assertThat(mm.moduleACtx.resolveMethodHashFile("ignored"))
				.isNotEqualTo(mm.moduleBCtx.resolveMethodHashFile("ignored"));
	}

	@Test
	void multiModule_gitRootIsReactorRoot() {
		var mm = multiModuleSetup();
		Path rootDir = tempDir.resolve("root");
		assertThat(mm.moduleACtx.gitRoot()).isEqualTo(rootDir);
		assertThat(mm.moduleBCtx.gitRoot()).isEqualTo(rootDir);
	}

	@Test
	void multiModule_moduleIdAndPrefix() {
		var mm = multiModuleSetup();
		assertThat(mm.moduleACtx.moduleId()).isEqualTo("module-a");
		assertThat(mm.moduleACtx.modulePrefix()).isEqualTo("module-a");
		assertThat(mm.moduleBCtx.moduleId()).isEqualTo("module-b");
	}

	// --- Cross-module change propagation ---

	@Test
	void multiModule_changePropagation() {
		var mm = multiModuleSetup();

		// module-a detects changes and stores them
		mm.moduleACtx.storeChangedClasses(Set.of("com.a.Foo", "com.a.Bar"));
		mm.moduleACtx.storeChangedTestClasses(Set.of("com.a.FooTest"));

		// module-b (which depends on A) collects upstream changes
		Set<String> upstreamClasses = mm.moduleBCtx.collectUpstreamChangedClasses();
		assertThat(upstreamClasses).containsExactlyInAnyOrder("com.a.Foo", "com.a.Bar");

		Set<String> upstreamTests = mm.moduleBCtx.collectUpstreamChangedTestClasses();
		assertThat(upstreamTests).containsExactlyInAnyOrder("com.a.FooTest");
	}

	@Test
	void multiModule_noUpstreamForRootModule() {
		var mm = multiModuleSetup();
		mm.moduleACtx.storeChangedClasses(Set.of("com.a.Foo"));
		// module-a has no upstream
		assertThat(mm.moduleACtx.collectUpstreamChangedClasses()).isEmpty();
	}

	@Test
	void multiModule_ensureSharedDirectories() throws IOException {
		var mm = multiModuleSetup();
		Path rootDir = tempDir.resolve("root");
		Files.createDirectories(rootDir);

		mm.moduleACtx.ensureSharedDirectories();

		assertThat(Files.isDirectory(rootDir.resolve(".test-order"))).isTrue();
		assertThat(Files.isDirectory(rootDir.resolve(".test-order/hashes"))).isTrue();
		assertThat(Files.isDirectory(rootDir.resolve(".test-order/deps"))).isTrue();
	}

	@Test
	void singleModule_ensureSharedDirectoriesCreatesBaseDir() throws IOException {
		ReactorContext ctx = singleModuleContext();
		ctx.ensureSharedDirectories();
		assertThat(Files.isDirectory(tempDir.resolve("my-app/.test-order"))).isTrue();
		// single-module should NOT create hashes/ or deps/ subdirectories
		assertThat(Files.exists(tempDir.resolve("my-app/.test-order/hashes"))).isFalse();
		assertThat(Files.exists(tempDir.resolve("my-app/.test-order/deps"))).isFalse();
	}

	// --- Inferred multi-module tests (-pl without -am) ---

	@Test
	void inferredMultiModule_detectsSubmoduleFromExistingSharedDir() throws IOException {
		// Simulate: `mvn test-order:dashboard -pl module-a` (without -am)
		// The reactor only has 1 project, but the execution root has .test-order/
		Path rootDir = tempDir.resolve("root");
		Path sharedDir = rootDir.resolve(".test-order");
		Files.createDirectories(sharedDir);

		MavenProject moduleA = mockProject("module-a", rootDir.resolve("module-a"));
		when(session.getProjects()).thenReturn(List.of(moduleA));
		when(session.getTopLevelProject()).thenReturn(moduleA);
		when(session.getExecutionRootDirectory()).thenReturn(rootDir.toString());

		// Mock getRequest().getMultiModuleProjectDirectory() → rootDir
		var request = mock(org.apache.maven.execution.MavenExecutionRequest.class);
		when(session.getRequest()).thenReturn(request);
		when(request.getMultiModuleProjectDirectory()).thenReturn(rootDir.toFile());

		ReactorContext ctx = new ReactorContext(session, moduleA);
		assertThat(ctx.isMultiModule()).isTrue();
		assertThat(ctx.resolveStateFile(null)).isEqualTo(sharedDir.resolve("state.lz4"));
		assertThat(ctx.resolveIndexFile(null)).isEqualTo(sharedDir.resolve("test-dependencies.lz4"));
	}

	@Test
	void inferredMultiModule_fallsBackToSingleWhenNoSharedDir() {
		// Same as above but .test-order/ does NOT exist at the root
		Path rootDir = tempDir.resolve("root2");
		MavenProject moduleA = mockProject("module-a", rootDir.resolve("module-a"));
		when(session.getProjects()).thenReturn(List.of(moduleA));
		when(session.getTopLevelProject()).thenReturn(moduleA);
		when(session.getExecutionRootDirectory()).thenReturn(rootDir.toString());

		var request = mock(org.apache.maven.execution.MavenExecutionRequest.class);
		when(session.getRequest()).thenReturn(request);
		when(request.getMultiModuleProjectDirectory()).thenReturn(rootDir.toFile());

		ReactorContext ctx = new ReactorContext(session, moduleA);
		assertThat(ctx.isMultiModule()).isFalse();
		// Falls back to module-local paths
		assertThat(ctx.resolveStateFile(null)).isEqualTo(rootDir.resolve("module-a/.test-order/state.lz4"));
	}

	@Test
	void inferredMultiModule_doesNotFireForUnrelatedNestedProject() throws IOException {
		// Simulate a third-party project physically nested inside another Maven project
		// (e.g. third-party/jsoup inside a repo whose root has .mvn/ and .test-order/).
		// Maven walks up and finds the outer .mvn/, so mmDir points to the outer root.
		// But the user invoked mvn from jsoup's directory, so executionRootDirectory
		// points to jsoup — not to the outer root. inferredMulti must NOT fire.
		Path outerRoot = tempDir.resolve("outer-root");
		Files.createDirectories(outerRoot.resolve(".test-order")); // outer project has shared dir
		Path jsoupDir = outerRoot.resolve("third-party").resolve("jsoup");

		MavenProject jsoup = mockProject("jsoup", jsoupDir);
		when(session.getProjects()).thenReturn(List.of(jsoup));
		when(session.getTopLevelProject()).thenReturn(jsoup);
		when(session.getExecutionRootDirectory()).thenReturn(jsoupDir.toString()); // invoked from jsoup dir

		var request = mock(org.apache.maven.execution.MavenExecutionRequest.class);
		when(session.getRequest()).thenReturn(request);
		when(request.getMultiModuleProjectDirectory()).thenReturn(outerRoot.toFile()); // Maven found outer .mvn/

		ReactorContext ctx = new ReactorContext(session, jsoup);
		assertThat(ctx.isMultiModule()).isFalse();
		assertThat(ctx.resolveStateFile(null)).isEqualTo(jsoupDir.resolve(".test-order/state.lz4"));
	}

	@Test
	void inferredMultiModule_walksUpToReactorRootWithoutMvnDir() throws IOException {
		// Simulate a multi-module repo without `.mvn/` (e.g. cloud-sdk-java demo):
		// running `mvn -pl <module> test-order:affected test` from the reactor root.
		// Maven falls back to setting multiModuleProjectDirectory to the module dir,
		// hiding the reactor. We must walk up to find the real root (which has
		// pom.xml + .test-order/) and treat it as multi-module.
		Path rootDir = tempDir.resolve("sdk-root");
		Path moduleDir = rootDir.resolve("module-x");
		Files.createDirectories(rootDir.resolve(".test-order"));
		Files.writeString(rootDir.resolve("pom.xml"), "<project/>");
		Files.createDirectories(moduleDir);

		MavenProject moduleX = mockProject("module-x", moduleDir);
		when(session.getProjects()).thenReturn(List.of(moduleX));
		when(session.getTopLevelProject()).thenReturn(moduleX);
		when(session.getExecutionRootDirectory()).thenReturn(rootDir.toString());

		var request = mock(org.apache.maven.execution.MavenExecutionRequest.class);
		when(session.getRequest()).thenReturn(request);
		// No .mvn/ → Maven falls back to module dir as multiModuleProjectDirectory
		when(request.getMultiModuleProjectDirectory()).thenReturn(moduleDir.toFile());

		ReactorContext ctx = new ReactorContext(session, moduleX);
		assertThat(ctx.isMultiModule()).isTrue();
		assertThat(ctx.resolveIndexFile(null)).isEqualTo(rootDir.resolve(".test-order/test-dependencies.lz4"));
		assertThat(ctx.resolveStateFile(null)).isEqualTo(rootDir.resolve(".test-order/state.lz4"));
	}

	@Test
	void inferredMultiModule_walkUpDoesNotFireWhenInvokedFromModuleDir() throws IOException {
		// Same physical layout as above, but the user `cd`s into module-x and runs
		// mvn from there. executionRootDir is then the module dir, NOT the root.
		// However, because module-x's Maven parent chain leads to root-dir, we DO
		// treat it as a multi-module reactor — this is the actual demo flow
		// (cd into a submodule, run `mvn test-order:affected test`).
		Path rootDir = tempDir.resolve("sdk-root2");
		Path moduleDir = rootDir.resolve("module-x");
		Files.createDirectories(rootDir.resolve(".test-order"));
		Files.writeString(rootDir.resolve("pom.xml"), "<project/>");
		Files.createDirectories(moduleDir);

		MavenProject root = mockProject("sdk-root2", rootDir);
		MavenProject moduleX = mockProject("module-x", moduleDir);
		when(moduleX.getParent()).thenReturn(root);
		when(session.getProjects()).thenReturn(List.of(moduleX));
		when(session.getTopLevelProject()).thenReturn(moduleX);
		when(session.getExecutionRootDirectory()).thenReturn(moduleDir.toString());

		var request = mock(org.apache.maven.execution.MavenExecutionRequest.class);
		when(session.getRequest()).thenReturn(request);
		when(request.getMultiModuleProjectDirectory()).thenReturn(moduleDir.toFile());

		ReactorContext ctx = new ReactorContext(session, moduleX);
		assertThat(ctx.isMultiModule()).isTrue();
		assertThat(ctx.resolveIndexFile(null)).isEqualTo(rootDir.resolve(".test-order/test-dependencies.lz4"));
	}

	@Test
	void inferredMultiModule_walkUpDoesNotFireWithoutParentChainOrExecRootMatch() throws IOException {
		// Module physically inside an outer dir that has pom.xml + .test-order/, but
		// the module's Maven parent is unrelated AND the user invoked mvn from the
		// module dir. Must NOT treat the outer as a reactor root — same shape as the
		// third-party-nested-project case but with executionRootDir == module.
		Path rootDir = tempDir.resolve("sdk-root3");
		Path moduleDir = rootDir.resolve("module-x");
		Files.createDirectories(rootDir.resolve(".test-order"));
		Files.writeString(rootDir.resolve("pom.xml"), "<project/>");
		Files.createDirectories(moduleDir);

		MavenProject moduleX = mockProject("module-x", moduleDir);
		// No parent → not part of the outer reactor
		when(moduleX.getParent()).thenReturn(null);
		when(session.getProjects()).thenReturn(List.of(moduleX));
		when(session.getTopLevelProject()).thenReturn(moduleX);
		when(session.getExecutionRootDirectory()).thenReturn(moduleDir.toString());

		var request = mock(org.apache.maven.execution.MavenExecutionRequest.class);
		when(session.getRequest()).thenReturn(request);
		when(request.getMultiModuleProjectDirectory()).thenReturn(moduleDir.toFile());

		ReactorContext ctx = new ReactorContext(session, moduleX);
		assertThat(ctx.isMultiModule()).isFalse();
	}

	// --- helper ---

	private MavenProject mockProject(String artifactId, Path basedir) {
		MavenProject project = mock(MavenProject.class);
		when(project.getArtifactId()).thenReturn(artifactId);
		when(project.getBasedir()).thenReturn(basedir.toFile());
		return project;
	}
}
