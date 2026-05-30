package me.bechberger.testorder.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AbstractTestOrderMojoTest {

	@TempDir
	Path tempDir;

	@Test
	void resolveIncludePackages_usesGroupIdOnlyAsFallbackWhenNoSourcePackages() {
		MavenProject project = mock(MavenProject.class);
		when(project.getGroupId()).thenReturn("me.bechberger");
		when(project.getBasedir()).thenReturn(tempDir.toFile());

		Path missingSourceRoot = tempDir.resolve("missing-src-main-java");
		when(project.getCompileSourceRoots()).thenReturn(List.of(missingSourceRoot.toString()));

		Log log = mock(Log.class);

		String include = AbstractTestOrderMojo.resolveIncludePackages(null, true, project, log);

		assertThat(include).isEqualTo("me.bechberger");
	}

	@Test
	void resolveIncludePackages_doesNotAppendGroupIdWhenSourcePackagesDetected() throws IOException {
		MavenProject project = mock(MavenProject.class);
		when(project.getGroupId()).thenReturn("me.bechberger");
		when(project.getBasedir()).thenReturn(tempDir.toFile());

		Path sourceRoot = tempDir.resolve("src/main/java");
		Files.createDirectories(sourceRoot.resolve("com/example/app"));
		Files.writeString(sourceRoot.resolve("com/example/app/App.java"), "package com.example.app; class App {}\n");
		when(project.getCompileSourceRoots()).thenReturn(List.of(sourceRoot.toString()));

		Log log = mock(Log.class);

		String include = AbstractTestOrderMojo.resolveIncludePackages(null, true, project, log);

		assertThat(include).isEqualTo("com.example.app");
	}

	@Test
	void resolveIncludePackages_mergesUserPackagesWithDetectedPackagesWithoutRedundantPrefixes() throws IOException {
		MavenProject project = mock(MavenProject.class);
		when(project.getGroupId()).thenReturn("me.bechberger");
		when(project.getBasedir()).thenReturn(tempDir.toFile());

		Path sourceRoot = tempDir.resolve("src/main/java");
		Files.createDirectories(sourceRoot.resolve("com/example/app"));
		Files.writeString(sourceRoot.resolve("com/example/app/App.java"), "package com.example.app; class App {}\n");
		when(project.getCompileSourceRoots()).thenReturn(List.of(sourceRoot.toString()));

		Log log = mock(Log.class);

		String include = AbstractTestOrderMojo.resolveIncludePackages("com.example,org.lib.extra", true, project, log);

		// com.example.app is covered by com.example and should be collapsed.
		assertThat(include).isEqualTo("com.example,org.lib.extra");
	}

	@Test
	void findBestArtifactJar_prefersPlainJarBeforeFatJarVariants() throws Exception {
		Files.createFile(tempDir.resolve("test-order-core-0.0.1-SNAPSHOT.jar"));
		Files.createFile(tempDir.resolve("test-order-core-0.0.1-SNAPSHOT-all.jar"));
		Files.createFile(tempDir.resolve("test-order-core-0.0.1-SNAPSHOT-jar-with-dependencies.jar"));

		Path resolved = invokeFindBestArtifactJar(tempDir, "test-order-core", "0.0.1-SNAPSHOT");

		assertThat(resolved).isEqualTo(tempDir.resolve("test-order-core-0.0.1-SNAPSHOT-all.jar"));
	}

	@Test
	void findBestArtifactJar_plainJarWhenNoShadedPresent() throws Exception {
		Files.createFile(tempDir.resolve("test-order-core-0.0.1-SNAPSHOT.jar"));

		Path resolved = invokeFindBestArtifactJar(tempDir, "test-order-core", "0.0.1-SNAPSHOT");

		assertThat(resolved).isEqualTo(tempDir.resolve("test-order-core-0.0.1-SNAPSHOT.jar"));
	}

	@Test
	void resolveArtifactUsesSessionLocalRepository() throws Exception {
		Path customRepo = tempDir.resolve("custom-repo");
		Path jar = customRepo.resolve("me/bechberger/test-order-agent/1.2.3/test-order-agent-1.2.3.jar");
		Files.createDirectories(jar.getParent());
		Files.writeString(jar, "jar");

		MavenProject project = mock(MavenProject.class);
		when(project.getBuildPlugins()).thenReturn(List.of());
		when(project.getVersion()).thenReturn("1.2.3");
		when(project.getBasedir()).thenReturn(tempDir.toFile());

		ArtifactRepository localRepo = mock(ArtifactRepository.class);
		when(localRepo.getBasedir()).thenReturn(customRepo.toString());

		MavenSession session = mock(MavenSession.class);
		when(session.getLocalRepository()).thenReturn(localRepo);

		TestMojo mojo = new TestMojo();
		mojo.project = project;
		mojo.session = session;

		Path resolved = mojo.resolveArtifact("test-order-agent");
		assertThat(resolved).isEqualTo(jar);
	}

	@Test
	void hardcodedSurefireArgLineDoesNotMutateSessionGlobalDebugProperty() throws Exception {
		Path fakeAgentJar = tempDir.resolve("test-order-agent.jar");
		Files.writeString(fakeAgentJar, "jar");

		Plugin surefire = new Plugin();
		surefire.setGroupId("org.apache.maven.plugins");
		surefire.setArtifactId("maven-surefire-plugin");
		surefire.setVersion("3.2.5");
		Xpp3Dom config = new Xpp3Dom("configuration");
		Xpp3Dom argLine = new Xpp3Dom("argLine");
		argLine.setValue("-Xmx512m"); // hardcoded literal
		config.addChild(argLine);
		surefire.setConfiguration(config);

		Properties projectProps = new Properties();
		projectProps.setProperty("maven.surefire.debug", "-agentlib:jdwp=transport=dt_socket");

		Build build = new Build();
		build.setTestOutputDirectory(tempDir.resolve("target/test-classes").toString());
		build.setDirectory(tempDir.resolve("target").toString());

		MavenProject project = mock(MavenProject.class);
		when(project.getBuildPlugins()).thenReturn(List.of(surefire));
		when(project.getProperties()).thenReturn(projectProps);
		when(project.getBuild()).thenReturn(build);
		when(project.getBasedir()).thenReturn(tempDir.toFile());
		when(project.getCompileSourceRoots()).thenReturn(List.of(tempDir.resolve("src/main/java").toString()));

		Properties userProps = new Properties();
		userProps.setProperty("maven.surefire.debug", "SESSION-GLOBAL");

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(project));
		when(session.getTopLevelProject()).thenReturn(project);
		when(session.getUserProperties()).thenReturn(userProps);

		TestMojo mojo = new TestMojo(fakeAgentJar);
		mojo.project = project;
		mojo.session = session;
		mojo.indexFile = tempDir.resolve("index.lz4").toString();
		mojo.stateFile = tempDir.resolve("state.lz4").toString();
		mojo.depsDir = tempDir.resolve("deps").toString();
		mojo.hashFile = tempDir.resolve("hashes.lz4").toString();
		mojo.testHashFile = tempDir.resolve("test-hashes.lz4").toString();
		mojo.methodHashFile = tempDir.resolve("method-hashes.lz4").toString();

		mojo.initContext();
		mojo.configureLearnModeForTest("CLASS", null, false);

		assertThat(userProps.getProperty("maven.surefire.debug")).isEqualTo("SESSION-GLOBAL");
		String moduleDebug = projectProps.getProperty("maven.surefire.debug");
		assertThat(moduleDebug).contains("-agentlib:jdwp=transport=dt_socket");
		assertThat(moduleDebug).contains("-javaagent:");
	}

	@Test
	void explicitModeValidationAcceptsChangedTestClassesWithoutChangedClasses() {
		ParameterValidator validator = new ParameterValidator(mock(Log.class));
		assertThatCode(() -> validator.validateExplicitModeRequirements("explicit", null, "com.example.MyTest"))
				.doesNotThrowAnyException();
	}

	@Test
	void detectChangedTestClassesReturnsExplicitChangedTestClassesInExplicitMode() throws Exception {
		MavenProject project = mock(MavenProject.class);
		when(project.getBasedir()).thenReturn(tempDir.toFile());
		when(project.getCompileSourceRoots()).thenReturn(List.of(tempDir.resolve("src/main/java").toString()));
		when(project.getTestCompileSourceRoots()).thenReturn(List.of(tempDir.resolve("src/test/java").toString()));
		when(project.getBuildPlugins()).thenReturn(List.of());
		when(project.getProperties()).thenReturn(new Properties());

		Build build = new Build();
		build.setDirectory(tempDir.resolve("target").toString());
		build.setTestOutputDirectory(tempDir.resolve("target/test-classes").toString());
		when(project.getBuild()).thenReturn(build);

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(project));
		when(session.getTopLevelProject()).thenReturn(project);
		when(session.getUserProperties()).thenReturn(new Properties());

		TestMojo mojo = new TestMojo();
		mojo.project = project;
		mojo.session = session;
		mojo.changeMode = "explicit";
		mojo.changedTestClasses = "com.example.FirstTest, com.example.SecondTest";
		mojo.indexFile = tempDir.resolve("index.lz4").toString();
		mojo.stateFile = tempDir.resolve("state.lz4").toString();
		mojo.depsDir = tempDir.resolve("deps").toString();
		mojo.hashFile = tempDir.resolve("hashes.lz4").toString();
		mojo.testHashFile = tempDir.resolve("test-hashes.lz4").toString();
		mojo.methodHashFile = tempDir.resolve("method-hashes.lz4").toString();

		mojo.initContext();

		assertThat(mojo.detectChangedTestClasses()).containsExactlyInAnyOrder("com.example.FirstTest",
				"com.example.SecondTest");
	}

	@Test
	void matchesSelectedProjectAcceptsRelativePathSelectors() {
		MavenProject project = mock(MavenProject.class);
		when(project.getArtifactId()).thenReturn("jsoup-reader");
		when(project.getGroupId()).thenReturn("org.springframework.ai");
		when(project.getBasedir()).thenReturn(tempDir.resolve("document-readers/jsoup-reader").toFile());

		assertThat(AbstractTestOrderMojo.matchesSelectedProject(List.of("document-readers/jsoup-reader"), project,
				tempDir)).isTrue();
	}

	@Test
	void staleTestorderConfigInTestClassesIsRemovedBeforeWritingFreshConfig() throws Exception {
		Path testClassesDir = tempDir.resolve("target/test-classes");
		Files.createDirectories(testClassesDir);

		// Simulate stale files left by an old plugin version
		Path staleConfig = testClassesDir.resolve("testorder-config.properties");
		Path staleJunitProps = testClassesDir.resolve("junit-platform.properties");
		Files.writeString(staleConfig,
				"testorder.index.path=/old/path/.test-order/test-dependencies.lz4\ntestorder.state.path=/old/path/.test-order/state.lz4\n");
		Files.writeString(staleJunitProps,
				"junit.jupiter.testclass.order.default=me.bechberger.testorder.junit.PriorityClassOrderer\n");

		Build build = new Build();
		build.setDirectory(tempDir.resolve("target").toString());
		build.setTestOutputDirectory(testClassesDir.toString());

		MavenProject project = mock(MavenProject.class);
		when(project.getBuild()).thenReturn(build);
		when(project.getBasedir()).thenReturn(tempDir.toFile());
		when(project.getProperties()).thenReturn(new Properties());
		when(project.getBuildPlugins()).thenReturn(List.of());
		when(project.getCompileSourceRoots()).thenReturn(List.of());

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(project));
		when(session.getTopLevelProject()).thenReturn(project);

		TestMojo mojo = new TestMojo();
		mojo.project = project;
		mojo.session = session;
		mojo.indexFile = tempDir.resolve("index.lz4").toString();
		mojo.stateFile = tempDir.resolve("state.lz4").toString();
		mojo.depsDir = tempDir.resolve("deps").toString();
		mojo.hashFile = tempDir.resolve("hashes.lz4").toString();
		mojo.testHashFile = tempDir.resolve("test-hashes.lz4").toString();
		mojo.methodHashFile = tempDir.resolve("method-hashes.lz4").toString();
		mojo.initContext();

		mojo.invokeRemoveStaleTestClassesConfig();

		assertThat(staleConfig).doesNotExist();
		assertThat(staleJunitProps).doesNotExist();
	}

	@Test
	void staleTestorderConfigIsNotRemovedIfNotGeneratedByPlugin() throws Exception {
		Path testClassesDir = tempDir.resolve("target/test-classes");
		Files.createDirectories(testClassesDir);

		// junit-platform.properties that is NOT generated by test-order
		Path userJunitProps = testClassesDir.resolve("junit-platform.properties");
		Files.writeString(userJunitProps,
				"junit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer$ClassName\n");

		Build build = new Build();
		build.setDirectory(tempDir.resolve("target").toString());
		build.setTestOutputDirectory(testClassesDir.toString());

		MavenProject project = mock(MavenProject.class);
		when(project.getBuild()).thenReturn(build);
		when(project.getBasedir()).thenReturn(tempDir.toFile());
		when(project.getProperties()).thenReturn(new Properties());
		when(project.getBuildPlugins()).thenReturn(List.of());
		when(project.getCompileSourceRoots()).thenReturn(List.of());

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(project));
		when(session.getTopLevelProject()).thenReturn(project);

		TestMojo mojo = new TestMojo();
		mojo.project = project;
		mojo.session = session;
		mojo.indexFile = tempDir.resolve("index.lz4").toString();
		mojo.stateFile = tempDir.resolve("state.lz4").toString();
		mojo.depsDir = tempDir.resolve("deps").toString();
		mojo.hashFile = tempDir.resolve("hashes.lz4").toString();
		mojo.testHashFile = tempDir.resolve("test-hashes.lz4").toString();
		mojo.methodHashFile = tempDir.resolve("method-hashes.lz4").toString();
		mojo.initContext();

		mojo.invokeRemoveStaleTestClassesConfig();

		assertThat(userJunitProps).exists(); // must NOT be deleted
	}

	@Test
	void cleanStaleTddConfig_removesTddAndAutodetectionFromRuntimeFiles() throws IOException {
		// Simulate runtime config files left by a previous order-mode run with TDD
		// enabled
		Path runtimeDir = tempDir.resolve("target/test-order-runtime");
		Files.createDirectories(runtimeDir);

		Path configFile = runtimeDir.resolve("testorder-config.properties");
		Files.writeString(configFile,
				"testorder.learn=true\ntestorder.tdd=true\ntestorder.state.path=/tmp/state.lz4\n");

		Path junitProps = runtimeDir.resolve("junit-platform.properties");
		Files.writeString(junitProps,
				"junit.jupiter.testclass.order.default=me.bechberger.Orderer\njunit.jupiter.extensions.autodetection.enabled=true\n");

		Build build = new Build();
		build.setDirectory(tempDir.resolve("target").toString());

		MavenProject project = mock(MavenProject.class);
		when(project.getBuild()).thenReturn(build);
		when(project.getBasedir()).thenReturn(tempDir.toFile());

		TestMojo mojo = new TestMojo();
		mojo.project = project;

		mojo.invokeCleanStaleTddConfig();

		// TDD line should be removed, other lines preserved
		String configContent = Files.readString(configFile);
		assertThat(configContent).doesNotContain("testorder.tdd");
		assertThat(configContent).contains("testorder.learn=true");
		assertThat(configContent).contains("testorder.state.path=/tmp/state.lz4");

		// Autodetection line should be removed, other lines preserved
		String junitContent = Files.readString(junitProps);
		assertThat(junitContent).doesNotContain("junit.jupiter.extensions.autodetection.enabled");
		assertThat(junitContent).contains("junit.jupiter.testclass.order.default");
	}

	@Test
	void cleanStaleTddConfig_noOpWhenFilesDoNotExist() {
		Build build = new Build();
		build.setDirectory(tempDir.resolve("target").toString());

		MavenProject project = mock(MavenProject.class);
		when(project.getBuild()).thenReturn(build);
		when(project.getBasedir()).thenReturn(tempDir.toFile());

		TestMojo mojo = new TestMojo();
		mojo.project = project;

		// Should not throw when runtime dir doesn't exist
		assertThatCode(() -> mojo.invokeCleanStaleTddConfig()).doesNotThrowAnyException();
	}

	@Test
	void cleanStaleTddConfig_noOpWhenNoTddEntriesPresent() throws IOException {
		Path runtimeDir = tempDir.resolve("target/test-order-runtime");
		Files.createDirectories(runtimeDir);

		Path configFile = runtimeDir.resolve("testorder-config.properties");
		String originalConfig = "testorder.learn=true\ntestorder.state.path=/tmp/state.lz4\n";
		Files.writeString(configFile, originalConfig);

		Path junitProps = runtimeDir.resolve("junit-platform.properties");
		String originalJunit = "junit.jupiter.testclass.order.default=me.bechberger.Orderer\n";
		Files.writeString(junitProps, originalJunit);

		Build build = new Build();
		build.setDirectory(tempDir.resolve("target").toString());

		MavenProject project = mock(MavenProject.class);
		when(project.getBuild()).thenReturn(build);
		when(project.getBasedir()).thenReturn(tempDir.toFile());

		TestMojo mojo = new TestMojo();
		mojo.project = project;

		mojo.invokeCleanStaleTddConfig();

		// Files should remain unchanged
		assertThat(Files.readString(configFile)).isEqualTo(originalConfig);
		assertThat(Files.readString(junitProps)).isEqualTo(originalJunit);
	}

	@Test
	void skipIfNotExplicitlySelectedReactorProjectSkipsDependencyModule() {
		MavenProject project = mock(MavenProject.class);
		when(project.getArtifactId()).thenReturn("spring-ai-commons");
		when(project.getGroupId()).thenReturn("org.springframework.ai");
		when(project.getBasedir()).thenReturn(tempDir.resolve("spring-ai-commons").toFile());

		MavenProject topLevel = mock(MavenProject.class);
		when(topLevel.getBasedir()).thenReturn(tempDir.toFile());

		MavenExecutionRequest request = mock(MavenExecutionRequest.class);
		when(request.getSelectedProjects()).thenReturn(List.of("document-readers/jsoup-reader"));

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(project, topLevel));
		when(session.getTopLevelProject()).thenReturn(topLevel);
		when(session.getRequest()).thenReturn(request);

		TestMojo mojo = new TestMojo();
		mojo.project = project;
		mojo.session = session;

		assertThat(mojo.skipIfNotExplicitlySelectedReactorProject("select")).isTrue();
	}

	@Test
	void resolveShadedCoreJar_prefersReactorClassesDirWhenPresent() throws Exception {
		// Simulate a reactor build where test-order-core/target/classes exists as a
		// sibling of the project basedir
		Path projectBase = tempDir.resolve("my-project");
		Files.createDirectories(projectBase);
		Path reactorClasses = tempDir.resolve("test-order-core/target/classes");
		Files.createDirectories(reactorClasses);

		MavenProject project = mock(MavenProject.class);
		when(project.getBasedir()).thenReturn(projectBase.toFile());
		when(project.getBuildPlugins()).thenReturn(List.of());
		when(project.getVersion()).thenReturn("1.0.0");

		TestMojo mojo = new TestMojo();
		mojo.project = project;
		// no session needed — reactor path is found first

		Path result = invokeResolveShadedCoreJar(mojo);
		assertThat(result).isEqualTo(reactorClasses);
	}

	@Test
	void resolveShadedCoreJar_prefersPluginVersionShadedJar() throws Exception {
		Path repoRoot = tempDir.resolve("repo");
		Path shadedJar = repoRoot.resolve("me/bechberger/test-order-core/2.0.0/test-order-core-2.0.0-all.jar");
		Files.createDirectories(shadedJar.getParent());
		Files.writeString(shadedJar, "shaded");

		// plain jar also exists — shaded must win
		Path plainJar = repoRoot.resolve("me/bechberger/test-order-core/2.0.0/test-order-core-2.0.0.jar");
		Files.writeString(plainJar, "plain");

		Plugin plugin = new Plugin();
		plugin.setGroupId("me.bechberger");
		plugin.setArtifactId("test-order-maven-plugin");
		plugin.setVersion("2.0.0");

		MavenProject project = mock(MavenProject.class);
		when(project.getBasedir()).thenReturn(tempDir.resolve("project").toFile());
		when(project.getBuildPlugins()).thenReturn(List.of(plugin));
		when(project.getVersion()).thenReturn("1.9.0");

		ArtifactRepository localRepo = mock(ArtifactRepository.class);
		when(localRepo.getBasedir()).thenReturn(repoRoot.toString());
		MavenSession session = mock(MavenSession.class);
		when(session.getLocalRepository()).thenReturn(localRepo);

		TestMojo mojo = new TestMojo();
		mojo.project = project;
		mojo.session = session;

		Path result = invokeResolveShadedCoreJar(mojo);
		assertThat(result).isEqualTo(shadedJar);
	}

	@Test
	void resolveShadedCoreJar_fallsBackToProjectVersionWhenPluginVersionJarMissing() throws Exception {
		Path repoRoot = tempDir.resolve("repo");
		// Only the project-version shaded jar is present (no plugin-version jar)
		Path shadedJar = repoRoot.resolve("me/bechberger/test-order-core/1.9.0/test-order-core-1.9.0-all.jar");
		Files.createDirectories(shadedJar.getParent());
		Files.writeString(shadedJar, "shaded");

		Plugin plugin = new Plugin();
		plugin.setGroupId("me.bechberger");
		plugin.setArtifactId("test-order-maven-plugin");
		plugin.setVersion("2.0.0"); // 2.0.0 jar absent

		MavenProject project = mock(MavenProject.class);
		when(project.getBasedir()).thenReturn(tempDir.resolve("project").toFile());
		when(project.getBuildPlugins()).thenReturn(List.of(plugin));
		when(project.getVersion()).thenReturn("1.9.0");

		ArtifactRepository localRepo = mock(ArtifactRepository.class);
		when(localRepo.getBasedir()).thenReturn(repoRoot.toString());
		MavenSession session = mock(MavenSession.class);
		when(session.getLocalRepository()).thenReturn(localRepo);

		TestMojo mojo = new TestMojo();
		mojo.project = project;
		mojo.session = session;

		Path result = invokeResolveShadedCoreJar(mojo);
		assertThat(result).isEqualTo(shadedJar);
	}

	private Path invokeFindBestArtifactJar(Path baseDir, String artifactId, String version)
			throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
		Method method = AbstractTestOrderMojo.class.getDeclaredMethod("findBestArtifactJar", Path.class, String.class,
				String.class);
		method.setAccessible(true);
		return (Path) method.invoke(new TestMojo(), baseDir, artifactId, version);
	}

	private Path invokeResolveShadedCoreJar(AbstractTestOrderMojo mojo)
			throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
		Method method = AbstractTestOrderMojo.class.getDeclaredMethod("resolveShadedCoreJar");
		method.setAccessible(true);
		return (Path) method.invoke(mojo);
	}

	private static final class TestMojo extends AbstractTestOrderMojo {
		private final Path fakeAgentJar;

		private TestMojo() {
			this.fakeAgentJar = null;
		}

		private TestMojo(Path fakeAgentJar) {
			this.fakeAgentJar = fakeAgentJar;
		}

		void configureLearnModeForTest(String instrumentationMode, String includePackages, boolean includeIndexInArgs)
				throws MojoExecutionException {
			configureLearnMode(instrumentationMode, includePackages, includeIndexInArgs);
		}

		void invokeRemoveStaleTestClassesConfig() throws MojoExecutionException {
			removeStaleTestClassesConfig();
		}

		void invokeCleanStaleTddConfig() {
			cleanStaleTddConfig();
		}

		@Override
		protected Path resolveArtifact(String artifactId) throws MojoExecutionException {
			if (fakeAgentJar != null && "test-order-agent".equals(artifactId)) {
				return fakeAgentJar;
			}
			return super.resolveArtifact(artifactId);
		}

		@Override
		protected Path[] resolveOrdererClasspath() throws MojoExecutionException {
			return new Path[0];
		}

		@Override
		protected void injectTestClasspath(Path... jars) {
			// no-op for focused unit tests
		}

		@Override
		protected void ensureListenerServiceFile(Path classpathRoot) {
			// no-op for focused unit tests
		}

		@Override
		protected void snapshotHashes() {
			// no-op for focused unit tests
		}

		@Override
		public void execute() throws MojoExecutionException {
		}
	}
}
