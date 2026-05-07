package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunRemainingMojoTest {

	@TempDir
	Path tempDir;

	private TestableRunRemainingMojo mojo;
	private MavenProject project;

	@BeforeEach
	void setUp() throws Exception {
		mojo = new TestableRunRemainingMojo();
		project = projectWithSurefire(tempDir);

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(project));
		when(session.getTopLevelProject()).thenReturn(project);

		inject(mojo, "project", project);
		inject(mojo, "session", session);
		inject(mojo, "remainingFile", tempDir.resolve("remaining.txt").toString());
		inject(mojo, "skip", false);
	}

	@Test
	void missingRemainingFileSkipsTests() {
		assertDoesNotThrow(() -> mojo.execute());
		assertEquals("true", project.getProperties().getProperty("skipTests"));
	}

	@Test
	void emptyRemainingFileSkipsTests() throws Exception {
		Files.writeString(tempDir.resolve("remaining.txt"), "");

		assertDoesNotThrow(() -> mojo.execute());
		assertEquals("true", project.getProperties().getProperty("skipTests"));
	}

	@Test
	void nonEmptyRemainingFileConfiguresSurefireIncludes() throws Exception {
		Files.writeString(tempDir.resolve("remaining.txt"), "com.example.FooTest\ncom.example.BarTest\n");

		assertDoesNotThrow(() -> mojo.execute());
		assertNull(project.getProperties().getProperty("skipTests"));

		String testProp = project.getProperties().getProperty("test");
		assertNotNull(testProp);
		assertTrue(testProp.contains("com.example.FooTest"));
		assertTrue(testProp.contains("com.example.BarTest"));
	}

	@Test
	void malformedRemainingFileReadErrorIsWrapped() throws Exception {
		// Use a directory path to force read failure via TestSelector.readTestList
		Path dir = tempDir.resolve("remaining-dir");
		Files.createDirectories(dir);
		inject(mojo, "remainingFile", dir.toString());

		MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> mojo.execute());
		assertTrue(ex.getMessage().contains("Failed to read remaining tests file"));
	}

	@Test
	void skipsReactorDependencyModuleIncludedByAlsoMake() throws Exception {
		MavenProject parentProject = projectWithSurefire(tempDir.resolve("parent"));
		when(parentProject.getArtifactId()).thenReturn("spring-ai-parent");
		when(parentProject.getGroupId()).thenReturn("org.springframework.ai");

		MavenProject selectedModule = projectWithSurefire(tempDir.resolve("spring-ai-commons"));
		when(selectedModule.getArtifactId()).thenReturn("spring-ai-commons");
		when(selectedModule.getGroupId()).thenReturn("org.springframework.ai");

		MavenExecutionRequest request = mock(MavenExecutionRequest.class);
		when(request.getSelectedProjects()).thenReturn(List.of("spring-ai-commons"));

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(parentProject, selectedModule));
		when(session.getTopLevelProject()).thenReturn(parentProject);
		when(session.getRequest()).thenReturn(request);

		TestableRunRemainingMojo reactorMojo = new TestableRunRemainingMojo();
		inject(reactorMojo, "project", parentProject);
		inject(reactorMojo, "session", session);
		inject(reactorMojo, "remainingFile", tempDir.resolve("missing-remaining.txt").toString());
		inject(reactorMojo, "skip", false);

		assertDoesNotThrow(reactorMojo::execute);
		assertNull(parentProject.getProperties().getProperty("skipTests"));
		assertNull(parentProject.getProperties().getProperty("testorder.auto.active"));
	}

	private static MavenProject projectWithSurefire(Path baseDir) {
		MavenProject project = mock(MavenProject.class);
		when(project.getBasedir()).thenReturn(baseDir.toFile());
		when(project.getProperties()).thenReturn(new Properties());

		Build build = new Build();
		build.setDirectory(baseDir.resolve("target").toString());
		build.setTestOutputDirectory(baseDir.resolve("test-classes").toString());
		when(project.getBuild()).thenReturn(build);

		Plugin surefire = new Plugin();
		surefire.setGroupId("org.apache.maven.plugins");
		surefire.setArtifactId("maven-surefire-plugin");
		surefire.setConfiguration(new Xpp3Dom("configuration"));
		when(project.getBuildPlugins()).thenReturn(List.of(surefire));

		return project;
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception {
		Class<?> clazz = target.getClass();
		while (clazz != null) {
			try {
				Field field = clazz.getDeclaredField(fieldName);
				field.setAccessible(true);
				field.set(target, value);
				return;
			} catch (NoSuchFieldException e) {
				clazz = clazz.getSuperclass();
			}
		}
		throw new NoSuchFieldException("Field not found in class hierarchy: " + fieldName);
	}

	private static final class TestableRunRemainingMojo extends RunRemainingMojo {
		@Override
		protected Path[] resolveOrdererClasspath() throws MojoExecutionException {
			return new Path[0];
		}

		@Override
		protected void injectTestClasspath(Path... jars) {
			// no-op for test
		}

		@Override
		protected void ensureListenerServiceFile(Path classpathRoot) throws MojoExecutionException {
			// no-op for test
		}

		@Override
		protected boolean isTestNGOnTestClasspath() {
			return false;
		}

		@Override
		protected void ensureTestNGListenerServiceFile(Path classpathRoot) throws MojoExecutionException {
			// no-op for test
		}
	}
}
