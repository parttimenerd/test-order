package me.bechberger.testorder.plugin;

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

import org.apache.maven.artifact.repository.ArtifactRepository;
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
		Files.createFile(tempDir.resolve("test-order-core-0.1.0-SNAPSHOT.jar"));
		Files.createFile(tempDir.resolve("test-order-core-0.1.0-SNAPSHOT-all.jar"));
		Files.createFile(tempDir.resolve("test-order-core-0.1.0-SNAPSHOT-jar-with-dependencies.jar"));

		Path resolved = invokeFindBestArtifactJar(tempDir, "test-order-core", "0.1.0-SNAPSHOT");

		assertThat(resolved).isEqualTo(tempDir.resolve("test-order-core-0.1.0-SNAPSHOT.jar"));
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
		mojo.configureLearnModeForTest("FULL", null, false);

		assertThat(userProps.getProperty("maven.surefire.debug")).isEqualTo("SESSION-GLOBAL");
		String moduleDebug = projectProps.getProperty("maven.surefire.debug");
		assertThat(moduleDebug).contains("-agentlib:jdwp=transport=dt_socket");
		assertThat(moduleDebug).contains("-javaagent:");
	}

	private Path invokeFindBestArtifactJar(Path baseDir, String artifactId, String version)
			throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
		Method method = AbstractTestOrderMojo.class.getDeclaredMethod("findBestArtifactJar", Path.class, String.class,
				String.class);
		method.setAccessible(true);
		return (Path) method.invoke(new TestMojo(), baseDir, artifactId, version);
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

		@Override
		protected Path resolveArtifact(String artifactId) throws MojoExecutionException {
			if (fakeAgentJar != null && "test-order-agent".equals(artifactId)) {
				return fakeAgentJar;
			}
			return super.resolveArtifact(artifactId);
		}

		@Override
		protected void injectTestClasspath(Path... jars) {
			// no-op for focused unit tests
		}

		@Override
		protected void ensureListenerServiceFile() {
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
