package me.bechberger.testorder.plugin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

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

class CombinedMojoTest {

	@TempDir
	Path tempDir;

	private TestableCombinedMojo mojo;
	private MavenProject project;

	@BeforeEach
	void setUp() throws Exception {
		mojo = new TestableCombinedMojo();
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
		inject(mojo, "optimizeEvery", 1);
		inject(mojo, "runRemaining", true);
		inject(mojo, "instrumentationMode", "FULL");
	}

	@Test
	void noIndexFallsBackToLearnMode() {
		assertDoesNotThrow(() -> mojo.execute());
		assertTrue(mojo.configureLearnModeCalled, "Combined mode should switch to learn when no index exists");
	}

	@Test
	void withIndexWritesSelectionAndSetsCombinedProperties() throws Exception {
		Path index = tempDir.resolve("test-dependencies.lz4");
		me.bechberger.testorder.DependencyMap dm = new me.bechberger.testorder.DependencyMap();
		dm.put("com.example.DummyTest", Set.of("com.example.Foo"));
		dm.save(index);

		assertDoesNotThrow(() -> mojo.execute());

		assertTrue(mojo.writeOrdererConfigCalled);
		assertTrue(mojo.snapshotHashesCalled);
		assertEquals("true", project.getProperties().getProperty("skipTests"));

		String remainingProp = project.getProperties().getProperty("testorder.remaining.file");
		assertNotNull(remainingProp);
		assertTrue(remainingProp.endsWith("remaining.txt"));
		assertEquals("true", project.getProperties().getProperty("testorder.combined.active"));

		assertTrue(Files.exists(tempDir.resolve("selected.txt")));
		assertTrue(Files.exists(tempDir.resolve("remaining.txt")));
	}

	@Test
	void optimizationTriggerUsesRunsSinceLearnCounter() throws Exception {
		Path index = tempDir.resolve("test-dependencies.lz4");
		me.bechberger.testorder.DependencyMap dm = new me.bechberger.testorder.DependencyMap();
		dm.put("com.example.DummyTest", Set.of("com.example.Foo"));
		dm.save(index);

		SpyState state = new SpyState();
		for (int i = 0; i < 4; i++) {
			state.incrementRunsSinceLearn();
		}
		mojo.forcedState = state;
		inject(mojo, "optimizeEvery", 5);

		assertDoesNotThrow(() -> mojo.execute());
		assertFalse(state.optimizeCalled, "Optimization must not run when runsSinceLearn is below threshold");

		state.incrementRunsSinceLearn(); // 5th run
		// Reset the double-execution guard so the second call proceeds
		project.getProperties().remove("testorder.combined.active");
		assertDoesNotThrow(() -> mojo.execute());
		assertTrue(state.optimizeCalled, "Optimization should run when runsSinceLearn reaches optimizeEvery multiple");
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

	private static final class TestableCombinedMojo extends CombinedMojo {
		boolean configureLearnModeCalled;
		boolean writeOrdererConfigCalled;
		boolean snapshotHashesCalled;
		TestOrderState forcedState;

		@Override
		protected void configureLearnMode(String instrumentationMode, String includePackages,
				boolean includeIndexInArgs) throws MojoExecutionException {
			configureLearnModeCalled = true;
		}

		@Override
		protected TestOrderState loadState() {
			if (forcedState != null) {
				return forcedState;
			}
			TestOrderState state = new TestOrderState();
			state.incrementRunsSinceLearn();
			return state;
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
		protected void writeOrdererConfig(Set<String> changed, Set<String> changedTests) throws MojoExecutionException {
			writeOrdererConfigCalled = true;
		}

		@Override
		protected void snapshotHashes() {
			snapshotHashesCalled = true;
		}
	}

	private static final class SpyState extends TestOrderState {
		boolean optimizeCalled;

		@Override
		public OptimizeResult optimize() {
			optimizeCalled = true;
			return null;
		}
	}
}
