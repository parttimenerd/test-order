package me.bechberger.testorder.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;

/**
 * Unit tests for the three multi-module Maven fixes:
 * <ol>
 * <li>findParentIndex is bounded by pom.xml presence and .git root</li>
 * <li>auto-discover parent index creates a relative symlink for submodules</li>
 * <li>child .collector-fallback files are processed before aggregation</li>
 * </ol>
 */
class FindParentIndexTest {

	@TempDir
	Path tempDir;

	// ── Fix 1: findParentIndex bounds ─────────────────────────────────────────

	/**
	 * Standard happy-path: the parent index lives at
	 * {@code repo/.test-order/test-dependencies.lz4}. The module is
	 * {@code repo/module-a}. The search starts from
	 * {@code repo/module-a/.test-order/test-dependencies.lz4} and should find the
	 * parent.
	 */
	@Test
	void findParentIndex_findsParentIndexInSameReactor() throws Exception {
		// repo/.git repo/pom.xml repo/.test-order/test-dependencies.lz4
		// repo/module-a/pom.xml repo/module-a/.test-order/test-dependencies.lz4
		Path repo = tempDir.resolve("repo");
		Files.createDirectories(repo.resolve(".git"));
		Files.writeString(repo.resolve("pom.xml"), "<project/>");

		Path parentIdx = repo.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(parentIdx.getParent());
		createMinimalIndex(parentIdx, "com.example.ParentTest");

		Path moduleA = repo.resolve("module-a");
		Files.createDirectories(moduleA);
		Files.writeString(moduleA.resolve("pom.xml"), "<project/>");

		// The child index file path is the starting point for findParentIndex
		Path childIdx = moduleA.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(childIdx.getParent());

		Path result = invokeFindParentIndex(childIdx);

		assertThat(result).isEqualTo(parentIdx);
	}

	/**
	 * If an index exists ABOVE the .git directory it must NOT be found — the search
	 * stops at the git root.
	 */
	@Test
	void findParentIndex_stopsAtGitRoot() throws Exception {
		// outer/.test-order/test-dependencies.lz4 ← must NOT be found
		// outer/pom.xml
		// outer/repo/.git
		// outer/repo/pom.xml
		// outer/repo/module-a/pom.xml
		// outer/repo/module-a/.test-order/test-dependencies.lz4 ← start here
		Path outer = tempDir.resolve("outer");
		Files.writeString(Files.createDirectories(outer).resolve("pom.xml"), "<project/>");
		Path outerIdx = outer.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(outerIdx.getParent());
		createMinimalIndex(outerIdx, "com.example.OuterTest");

		Path repo = outer.resolve("repo");
		Files.createDirectories(repo.resolve(".git"));
		Files.writeString(repo.resolve("pom.xml"), "<project/>");

		Path moduleA = repo.resolve("module-a");
		Files.createDirectories(moduleA);
		Files.writeString(moduleA.resolve("pom.xml"), "<project/>");

		Path childIdx = moduleA.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(childIdx.getParent());

		Path result = invokeFindParentIndex(childIdx);

		// The outer index is above .git — must not be returned
		assertThat(result).isNull();
	}

	/**
	 * If an intermediate directory has no pom.xml the search must stop — it has
	 * left the Maven reactor.
	 */
	@Test
	void findParentIndex_stopsWhenNoPomXmlInIntermediate() throws Exception {
		// root/pom.xml root/.test-order/test-dependencies.lz4
		// root/no-pom/ ← no pom.xml here
		// root/no-pom/module-a/pom.xml
		Path root = tempDir.resolve("root");
		Files.writeString(Files.createDirectories(root).resolve("pom.xml"), "<project/>");
		Path rootIdx = root.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(rootIdx.getParent());
		createMinimalIndex(rootIdx, "com.example.RootTest");

		// Intermediate directory without pom.xml
		Path noPom = root.resolve("no-pom");
		Files.createDirectories(noPom);
		// no pom.xml here

		Path moduleA = noPom.resolve("module-a");
		Files.createDirectories(moduleA);
		Files.writeString(moduleA.resolve("pom.xml"), "<project/>");

		Path childIdx = moduleA.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(childIdx.getParent());

		Path result = invokeFindParentIndex(childIdx);

		// Walks from module-a → no-pom → stops because no-pom has no pom.xml
		assertThat(result).isNull();
	}

	/**
	 * Deeply nested module can still find the reactor root index when every
	 * intermediate directory has a pom.xml and there is no .git in the way.
	 */
	@Test
	void findParentIndex_findsIndexAcrossMultipleLevels() throws Exception {
		// repo/pom.xml repo/.test-order/test-dependencies.lz4
		// repo/parent/pom.xml
		// repo/parent/child/pom.xml
		// repo/parent/child/.test-order/test-dependencies.lz4 ← start
		Path repo = tempDir.resolve("repo");
		Files.writeString(Files.createDirectories(repo).resolve("pom.xml"), "<project/>");
		Path rootIdx = repo.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(rootIdx.getParent());
		createMinimalIndex(rootIdx, "com.example.RootTest");

		Path parent = repo.resolve("parent");
		Files.writeString(Files.createDirectories(parent).resolve("pom.xml"), "<project/>");

		Path child = parent.resolve("child");
		Files.createDirectories(child);
		Files.writeString(child.resolve("pom.xml"), "<project/>");

		Path childIdx = child.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(childIdx.getParent());

		Path result = invokeFindParentIndex(childIdx);

		assertThat(result).isEqualTo(rootIdx);
	}

	// ── Fix 2: autoAggregateOrFail creates a relative symlink ────────────────

	/**
	 * When a submodule has no local index but an ancestor has one, a symlink is
	 * created at the expected submodule path and it is relative (not absolute).
	 */
	@Test
	void autoAggregateOrFail_createsRelativeSymlinkToParentIndex() throws Exception {
		// repo/.git repo/pom.xml repo/.test-order/test-dependencies.lz4
		// repo/module-a/pom.xml (no local index)
		Path repo = tempDir.resolve("repo");
		Files.createDirectories(repo.resolve(".git"));
		Files.writeString(repo.resolve("pom.xml"), "<project/>");

		Path parentIdx = repo.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(parentIdx.getParent());
		createMinimalIndex(parentIdx, "com.example.ParentTest");

		Path moduleA = repo.resolve("module-a");
		Files.createDirectories(moduleA);
		Files.writeString(moduleA.resolve("pom.xml"), "<project/>");

		Path childIdx = moduleA.resolve(".test-order/test-dependencies.lz4");

		TestMojo mojo = buildMojoForProject(moduleA, childIdx);

		mojo.invokeAutoAggregateOrFail(childIdx);

		assertThat(childIdx).exists();
		assertThat(Files.isSymbolicLink(childIdx)).isTrue();

		// The symlink target must be relative, not absolute
		Path linkTarget = Files.readSymbolicLink(childIdx);
		assertThat(linkTarget.isAbsolute()).as("symlink target should be relative but was: " + linkTarget).isFalse();

		// The resolved path should point to the parent index
		Path resolved = childIdx.getParent().resolve(linkTarget).normalize();
		assertThat(resolved).isEqualTo(parentIdx.normalize());
	}

	/**
	 * The symlink is NOT created when the parent index is above a .git boundary:
	 * findParentIndex returns null, so autoAggregateOrFail does not create a
	 * symlink pointing to the out-of-bounds index. Verified directly via
	 * findParentIndex which is the gate for the symlink path.
	 */
	@Test
	void findParentIndex_returnsNullWhenOnlyCrossBoundaryIndexExists() throws Exception {
		// outer/pom.xml outer/.test-order/test-dependencies.lz4 ← above .git
		// outer/repo/.git
		// outer/repo/pom.xml
		// outer/repo/module-a/pom.xml (no local index) ← start here
		Path outer = tempDir.resolve("outer");
		Files.writeString(Files.createDirectories(outer).resolve("pom.xml"), "<project/>");
		Path outerIdx = outer.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(outerIdx.getParent());
		createMinimalIndex(outerIdx, "com.example.OuterTest");

		Path repo = outer.resolve("repo");
		Files.createDirectories(repo.resolve(".git"));
		Files.writeString(repo.resolve("pom.xml"), "<project/>");

		Path moduleA = repo.resolve("module-a");
		Files.createDirectories(moduleA);
		Files.writeString(moduleA.resolve("pom.xml"), "<project/>");

		Path childIdx = moduleA.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(childIdx.getParent());

		// findParentIndex must return null — the outer index is across a .git boundary
		Path result = invokeFindParentIndex(childIdx);
		assertThat(result).isNull();
	}

	// ── Fix 3: child fallback files are processed before aggregation ──────────

	/**
	 * When a child module has an existing index AND a pending .collector-fallback
	 * file, the fallback data is merged into the child before aggregation at the
	 * reactor root.
	 */
	@Test
	void autoAggregateOrFail_processesFallbackBeforeAggregatingChildIndex() throws Exception {
		Path root = tempDir.resolve("root");
		Files.createDirectories(root);
		Files.writeString(root.resolve("pom.xml"), "<project/>");

		Path rootIdx = root.resolve(".test-order/test-dependencies.lz4");
		// Root index does NOT exist yet — this triggers child-scan path

		Path moduleA = root.resolve("module-a");
		Files.createDirectories(moduleA);
		Files.writeString(moduleA.resolve("pom.xml"), "<project/>");

		Path childIdx = moduleA.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(childIdx.getParent());

		// Child has a minimal existing index with one test class
		DependencyMap existing = new DependencyMap();
		existing.put("com.example.ExistingTest", Set.of("com.app.Existing"));
		existing.save(childIdx);

		// Write a .collector-fallback file alongside the child index
		// Format: testClass\tdep1\tdep2\n---\n---\n---\n===\n
		Path fallbackFile = childIdx.resolveSibling(childIdx.getFileName() + ".collector-fallback");
		Files.writeString(fallbackFile,
				"com.example.FallbackTest\tcom.app.FromFallback\n" + "---\n" + "---\n" + "---\n" + "===\n");

		// Set up a root mojo with module-a as a child project
		MavenProject childProject = mockProject(moduleA);
		MavenProject rootProject = mockProject(root);

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(rootProject, childProject));
		when(session.getTopLevelProject()).thenReturn(rootProject);

		TestMojo mojo = new TestMojo();
		mojo.project = rootProject;
		mojo.session = session;
		mojo.indexFile = rootIdx.toString();
		mojo.stateFile = root.resolve(".test-order/state.lz4").toString();
		mojo.depsDir = root.resolve("target/test-order-deps").toString();
		mojo.hashFile = root.resolve(".test-order/hashes.lz4").toString();
		mojo.testHashFile = root.resolve(".test-order/test-hashes.lz4").toString();
		mojo.methodHashFile = root.resolve(".test-order/method-hashes.lz4").toString();

		mojo.invokeAutoAggregateOrFail(rootIdx);

		// Root index should now exist and contain both test classes
		assertThat(rootIdx).exists();
		DependencyMap merged = DependencyMap.load(rootIdx);
		assertThat(merged.testClasses()).as("merged index should contain ExistingTest from the child index")
				.contains("com.example.ExistingTest");
		assertThat(merged.testClasses()).as("merged index should contain FallbackTest from the collector-fallback")
				.contains("com.example.FallbackTest");

		// Fallback file must have been consumed
		assertThat(fallbackFile).doesNotExist();
	}

	/**
	 * When multiple children have indexes, all of them are aggregated into the root
	 * index (basic aggregation sanity check).
	 */
	@Test
	void autoAggregateOrFail_mergesMultipleChildIndexes() throws Exception {
		Path root = tempDir.resolve("root");
		Files.createDirectories(root);
		Files.writeString(root.resolve("pom.xml"), "<project/>");
		Path rootIdx = root.resolve(".test-order/test-dependencies.lz4");

		Path moduleA = root.resolve("module-a");
		Files.createDirectories(moduleA);
		Files.writeString(moduleA.resolve("pom.xml"), "<project/>");
		Path childIdxA = moduleA.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(childIdxA.getParent());
		DependencyMap mapA = new DependencyMap();
		mapA.put("com.example.ATest", Set.of("com.app.A"));
		mapA.save(childIdxA);

		Path moduleB = root.resolve("module-b");
		Files.createDirectories(moduleB);
		Files.writeString(moduleB.resolve("pom.xml"), "<project/>");
		Path childIdxB = moduleB.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(childIdxB.getParent());
		DependencyMap mapB = new DependencyMap();
		mapB.put("com.example.BTest", Set.of("com.app.B"));
		mapB.save(childIdxB);

		MavenProject rootProject = mockProject(root);
		MavenProject projA = mockProject(moduleA);
		MavenProject projB = mockProject(moduleB);

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(rootProject, projA, projB));
		when(session.getTopLevelProject()).thenReturn(rootProject);

		TestMojo mojo = new TestMojo();
		mojo.project = rootProject;
		mojo.session = session;
		mojo.indexFile = rootIdx.toString();
		mojo.stateFile = root.resolve(".test-order/state.lz4").toString();
		mojo.depsDir = root.resolve("target/test-order-deps").toString();
		mojo.hashFile = root.resolve(".test-order/hashes.lz4").toString();
		mojo.testHashFile = root.resolve(".test-order/test-hashes.lz4").toString();
		mojo.methodHashFile = root.resolve(".test-order/method-hashes.lz4").toString();

		mojo.invokeAutoAggregateOrFail(rootIdx);

		assertThat(rootIdx).exists();
		DependencyMap merged = DependencyMap.load(rootIdx);
		assertThat(merged.testClasses()).containsExactlyInAnyOrder("com.example.ATest", "com.example.BTest");
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private void createMinimalIndex(Path path, String testClass) throws IOException {
		DependencyMap map = new DependencyMap();
		map.put(testClass, Set.of("com.app.SomeClass"));
		map.save(path);
	}

	private MavenProject mockProject(Path baseDir) {
		MavenProject project = mock(MavenProject.class);
		when(project.getBasedir()).thenReturn(baseDir.toAbsolutePath().toFile());
		when(project.getGroupId()).thenReturn("com.example");
		when(project.getProperties()).thenReturn(new Properties());
		when(project.getCompileSourceRoots()).thenReturn(List.of(baseDir.resolve("src/main/java").toString()));
		when(project.getTestCompileSourceRoots()).thenReturn(List.of(baseDir.resolve("src/test/java").toString()));
		Build build = new Build();
		build.setDirectory(baseDir.resolve("target").toString());
		build.setTestOutputDirectory(baseDir.resolve("target/test-classes").toString());
		when(project.getBuild()).thenReturn(build);
		when(project.getBuildPlugins()).thenReturn(List.of());
		return project;
	}

	private TestMojo buildMojoForProject(Path moduleBase, Path childIdx) {
		MavenProject project = mockProject(moduleBase);

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(project));
		when(session.getTopLevelProject()).thenReturn(project);

		TestMojo mojo = new TestMojo();
		mojo.project = project;
		mojo.session = session;
		mojo.indexFile = childIdx.toString();
		mojo.stateFile = moduleBase.resolve(".test-order/state.lz4").toString();
		mojo.depsDir = moduleBase.resolve("target/test-order-deps").toString();
		mojo.hashFile = moduleBase.resolve(".test-order/hashes.lz4").toString();
		mojo.testHashFile = moduleBase.resolve(".test-order/test-hashes.lz4").toString();
		mojo.methodHashFile = moduleBase.resolve(".test-order/method-hashes.lz4").toString();
		return mojo;
	}

	private Path invokeFindParentIndex(Path idxPath)
			throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
		Method method = AbstractTestOrderMojo.class.getDeclaredMethod("findParentIndex", Path.class);
		method.setAccessible(true);
		return (Path) method.invoke(new TestMojo(), idxPath);
	}

	// ── Minimal TestMojo subclass ─────────────────────────────────────────────

	private static final class TestMojo extends AbstractTestOrderMojo {

		void invokeAutoAggregateOrFail(Path idxPath) throws MojoExecutionException {
			autoAggregateOrFail(idxPath);
		}

		@Override
		protected Path resolveArtifact(String artifactId) throws MojoExecutionException {
			throw new MojoExecutionException("resolveArtifact not supported in test");
		}

		@Override
		protected Path[] resolveOrdererClasspath() throws MojoExecutionException {
			return new Path[0];
		}

		@Override
		protected void injectTestClasspath(Path... jars) {
			// no-op
		}

		@Override
		protected void ensureListenerServiceFile(Path classpathRoot) {
			// no-op
		}

		@Override
		protected void snapshotHashes() {
			// no-op
		}

		@Override
		public void execute() throws MojoExecutionException {
			// no-op
		}
	}
}
