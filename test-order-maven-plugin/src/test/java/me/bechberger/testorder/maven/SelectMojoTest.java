package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

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

import me.bechberger.testorder.TestOrderState;

class SelectMojoTest {

	@TempDir
	Path tempDir;

	private TestableSelectMojo mojo;
	private MavenProject project;

	@BeforeEach
	void setUp() throws Exception {
		mojo = new TestableSelectMojo();
		project = projectWithSurefire(tempDir);

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(project));
		when(session.getTopLevelProject()).thenReturn(project);

		inject(mojo, "session", session);
		inject(mojo, "project", project);
		inject(mojo, "indexFile", tempDir.resolve("test-dependencies.lz4").toString());
		inject(mojo, "stateFile", tempDir.resolve(".test-order-state").toString());
		inject(mojo, "depsDir", tempDir.resolve("test-order-deps").toString());
		inject(mojo, "hashFile", tempDir.resolve(".test-order-hashes.lz4").toString());
		inject(mojo, "testHashFile", tempDir.resolve(".test-order-test-hashes.lz4").toString());
		inject(mojo, "methodHashFile", tempDir.resolve(".test-order-method-hashes.lz4").toString());
		inject(mojo, "changeMode", "auto");
		inject(mojo, "changedClasses", "");
		inject(mojo, "topN", 0);
		inject(mojo, "randomM", 0);
		inject(mojo, "selectedFile", tempDir.resolve("selected.txt").toString());
		inject(mojo, "remainingFile", tempDir.resolve("remaining.txt").toString());
	}

	@Test
	void noIndexAndNoDepsFailsWithHelpfulMessage() throws Exception {
		inject(mojo, "topN", -1);
		MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
		assertTrue(ex.getMessage().contains("No dependency index"));
		assertTrue(ex.getMessage().contains("Run learn mode first"));
	}

	@Test
	void emptySelectionWritesListsAndSkipsTests() throws Exception {
		inject(mojo, "topN", -1);
		Path index = tempDir.resolve("test-dependencies.lz4");
		new me.bechberger.testorder.DependencyMap().save(index);

		assertDoesNotThrow(() -> mojo.execute());

		assertTrue(mojo.writeOrdererConfigCalled, "Orderer config should still be written");
		assertEquals("true", project.getProperties().getProperty("skipTests"));

		Path selected = tempDir.resolve("selected.txt");
		Path remaining = tempDir.resolve("remaining.txt");
		assertTrue(Files.exists(selected));
		// remaining file is only written when there are deferred tests
		assertEquals(List.of(), Files.readAllLines(selected));
	}

	@Test
	void corruptIndexFailsWithRecoveryGuidance() throws Exception {
		inject(mojo, "topN", -1);
		Files.writeString(tempDir.resolve("test-dependencies.lz4"), "XXXXXXXXXXXXXXXX");

		MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
		assertTrue(ex.getMessage().contains("Dependency index is unreadable"));
		assertTrue(ex.getMessage().contains("test-order:clean"));
	}

	@Test
	void reactorDependencyModuleIsSkippedWhenNotExplicitlySelected() throws Exception {
		MavenProject dependencyProject = projectWithSurefire(tempDir.resolve("spring-ai-commons"));
		when(dependencyProject.getArtifactId()).thenReturn("spring-ai-commons");

		MavenProject topLevelProject = projectWithSurefire(tempDir);
		when(topLevelProject.getArtifactId()).thenReturn("spring-ai");

		MavenExecutionRequest request = mock(MavenExecutionRequest.class);
		when(request.getSelectedProjects()).thenReturn(List.of("document-readers/jsoup-reader"));

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(topLevelProject, dependencyProject));
		when(session.getTopLevelProject()).thenReturn(topLevelProject);
		when(session.getRequest()).thenReturn(request);

		TestableSelectMojo dependencyMojo = new TestableSelectMojo();
		inject(dependencyMojo, "session", session);
		inject(dependencyMojo, "project", dependencyProject);
		inject(dependencyMojo, "indexFile", tempDir.resolve("test-dependencies.lz4").toString());
		inject(dependencyMojo, "stateFile", tempDir.resolve(".test-order-state").toString());
		inject(dependencyMojo, "depsDir", tempDir.resolve("test-order-deps").toString());
		inject(dependencyMojo, "hashFile", tempDir.resolve(".test-order-hashes.lz4").toString());
		inject(dependencyMojo, "testHashFile", tempDir.resolve(".test-order-test-hashes.lz4").toString());
		inject(dependencyMojo, "methodHashFile", tempDir.resolve(".test-order-method-hashes.lz4").toString());
		inject(dependencyMojo, "changeMode", "auto");
		inject(dependencyMojo, "changedClasses", "");
		inject(dependencyMojo, "topN", 0);
		inject(dependencyMojo, "randomM", 0);
		inject(dependencyMojo, "selectedFile", tempDir.resolve("selected.txt").toString());
		inject(dependencyMojo, "remainingFile", tempDir.resolve("remaining.txt").toString());

		assertDoesNotThrow(dependencyMojo::execute);
		assertFalse(dependencyMojo.writeOrdererConfigCalled,
				"Skipped reactor dependency module should not configure Surefire");
		assertFalse(Files.exists(tempDir.resolve("selected.txt")),
				"Skipped reactor dependency module should not write selection files");
	}

	@Test
	void autoRunRemainingPublishesRemainingFilePropertiesWhenDeferredTestsExist() throws Exception {
		Path index = tempDir.resolve("test-dependencies.lz4");
		me.bechberger.testorder.DependencyMap depMap = new me.bechberger.testorder.DependencyMap();
		depMap.put("com.example.AFastTest", Set.of("com.example.ServiceA"));
		depMap.put("com.example.BFastTest", Set.of("com.example.ServiceB"));
		depMap.save(index);

		inject(mojo, "topN", 1);
		inject(mojo, "randomM", 0);
		inject(mojo, "runRemaining", true);

		assertDoesNotThrow(() -> mojo.execute());

		String remainingPath = project.getProperties().getProperty("testorder.select.remainingFile");
		assertNotNull(remainingPath, "remaining file property should be published when deferred tests exist");
		assertEquals(remainingPath, project.getProperties().getProperty("testorder.remaining.file"));
		assertTrue(Files.exists(Path.of(remainingPath)), "remaining file should exist on disk");
		assertFalse(Files.readAllLines(Path.of(remainingPath)).isEmpty(),
				"remaining file should contain deferred tests");
	}

	private static MavenProject projectWithSurefire(Path baseDir) {
		MavenProject project = mock(MavenProject.class);
		when(project.getBasedir()).thenReturn(baseDir.toFile());
		when(project.getProperties()).thenReturn(new Properties());
		when(project.getArtifactId()).thenReturn("test-artifact");

		Build build = new Build();
		build.setDirectory(baseDir.resolve("target").toString());
		build.setTestOutputDirectory(baseDir.resolve("test-classes").toString());
		when(project.getBuild()).thenReturn(build);

		Plugin surefire = new Plugin();
		surefire.setGroupId("org.apache.maven.plugins");
		surefire.setArtifactId("maven-surefire-plugin");
		surefire.setConfiguration(new Xpp3Dom("configuration"));
		when(project.getBuildPlugins()).thenReturn(List.of(surefire));
		when(project.getCompileSourceRoots()).thenReturn(List.of(baseDir.resolve("src/main/java").toString()));
		when(project.getTestCompileSourceRoots()).thenReturn(List.of(baseDir.resolve("src/test/java").toString()));
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

	private static final class TestableSelectMojo extends SelectMojo {
		boolean writeOrdererConfigCalled;

		@Override
		protected TestOrderState loadState() {
			return new TestOrderState();
		}

		@Override
		protected Set<String> detectChangedClasses() {
			return Set.of();
		}

		@Override
		protected Set<String> detectChangedTestClasses() {
			return Set.of();
		}

		@Override
		protected void writeOrdererConfig(Set<String> changed, Set<String> changedTests, Set<String> changedMethods,
				Map<String, Integer> scoreOverrides) throws MojoExecutionException {
			writeOrdererConfigCalled = true;
		}

		@Override
		protected void writeOrdererConfigFromMap(Map<String, String> configMap) throws MojoExecutionException {
			writeOrdererConfigCalled = true;
		}
	}

	/**
	 * Testable subclass that returns explicit changed classes to test M-CRIT-1.
	 */
	private static final class ExplicitChangedSelectMojo extends SelectMojo {
		private final Set<String> explicitChanged;
		boolean writeOrdererConfigCalled;

		ExplicitChangedSelectMojo(Set<String> explicitChanged) {
			this.explicitChanged = explicitChanged;
		}

		@Override
		protected TestOrderState loadState() {
			return new TestOrderState();
		}

		@Override
		protected Set<String> detectChangedClasses() {
			return explicitChanged;
		}

		@Override
		protected Set<String> detectChangedTestClasses() {
			return Set.of();
		}

		@Override
		protected void writeOrdererConfig(Set<String> changed, Set<String> changedTests, Set<String> changedMethods,
				Map<String, Integer> scoreOverrides) throws MojoExecutionException {
			writeOrdererConfigCalled = true;
		}
	}

	/**
	 * Reproducer: M-CRIT-1 — explicitly specified changed classes that don't exist
	 * in the dependency index should fail, not silently select all tests.
	 */
	@Test
	void explicitNonExistentChangedClassesThrows() throws Exception {
		Path index = tempDir.resolve("test-dependencies.lz4");
		me.bechberger.testorder.DependencyMap depMap = new me.bechberger.testorder.DependencyMap();
		depMap.put("com.example.RealTest", Set.of("com.example.Service"));
		depMap.save(index);

		ExplicitChangedSelectMojo explicitMojo = new ExplicitChangedSelectMojo(Set.of("com.example.NonExistent"));

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(project));
		when(session.getTopLevelProject()).thenReturn(project);

		inject(explicitMojo, "session", session);
		inject(explicitMojo, "project", project);
		inject(explicitMojo, "indexFile", index.toString());
		inject(explicitMojo, "stateFile", tempDir.resolve(".test-order-state").toString());
		inject(explicitMojo, "depsDir", tempDir.resolve("test-order-deps").toString());
		inject(explicitMojo, "hashFile", tempDir.resolve(".test-order-hashes.lz4").toString());
		inject(explicitMojo, "testHashFile", tempDir.resolve(".test-order-test-hashes.lz4").toString());
		inject(explicitMojo, "methodHashFile", tempDir.resolve(".test-order-method-hashes.lz4").toString());
		inject(explicitMojo, "changeMode", "explicit");
		inject(explicitMojo, "changedClasses", "com.example.NonExistent");
		inject(explicitMojo, "topN", 5);
		inject(explicitMojo, "randomM", 3);
		inject(explicitMojo, "selectedFile", tempDir.resolve("selected.txt").toString());
		inject(explicitMojo, "remainingFile", tempDir.resolve("remaining.txt").toString());

		MojoExecutionException ex = assertThrows(MojoExecutionException.class, explicitMojo::execute);
		assertTrue(ex.getMessage().contains("None of the explicitly specified changed classes"),
				"Should fail with informative message about non-existent classes, got: " + ex.getMessage());
	}
}
