package me.bechberger.testorder.maven;

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
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;

class ShowOrderMojoTest {

	@TempDir
	Path tempDir;

	private TestableShowOrderMojo mojo;

	@BeforeEach
	void setUp() throws Exception {
		mojo = new TestableShowOrderMojo();

		MavenProject project = mock(MavenProject.class);
		when(project.getBasedir()).thenReturn(tempDir.toFile());
		when(project.getProperties()).thenReturn(new Properties());
		when(project.getCompileSourceRoots()).thenReturn(List.of(tempDir.resolve("src/main/java").toString()));
		when(project.getTestCompileSourceRoots()).thenReturn(List.of(tempDir.resolve("src/test/java").toString()));

		Build build = new Build();
		build.setDirectory(tempDir.resolve("target").toString());
		build.setTestOutputDirectory(tempDir.resolve("test-classes").toString());
		when(project.getBuild()).thenReturn(build);

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(project));
		when(session.getTopLevelProject()).thenReturn(project);

		inject(mojo, "project", project);
		inject(mojo, "session", session);
		inject(mojo, "indexFile", tempDir.resolve("index.lz4").toString());
		inject(mojo, "stateFile", tempDir.resolve(".test-order-state").toString());
		inject(mojo, "depsDir", tempDir.resolve("deps").toString());
	}

	@Test
	void noIndexAndNoDepsFailsWithHelpfulMessage() {
		MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> mojo.execute());
		assertTrue(ex.getMessage().contains("No dependency index"));
		assertTrue(ex.getMessage().contains("Run: mvn test -Dtestorder.mode=learn"));
	}

	@Test
	void withSimpleIndexExecutesSuccessfully() throws Exception {
		DependencyMap map = new DependencyMap();
		map.put("com.example.MyTest", Set.of("com.app.A"));
		map.save(tempDir.resolve("index.lz4"));

		Files.createDirectories(tempDir.resolve("test-classes/com/example"));
		Files.writeString(tempDir.resolve("test-classes/com/example/MyTest.class"), "not-a-real-class");

		assertDoesNotThrow(() -> mojo.execute());
	}

	@Test
	void autoModeUsesSinceLastCommitWhenNoHashSnapshotExists() throws Exception {
		inject(mojo, "changeMode", "auto");
		inject(mojo, "hashFile", tempDir.resolve("missing-hashes.lz4").toString());
		mojo.initForTest();

		assertEquals("since-last-commit", mojo.resolveStructuralDiffModeForTest());
	}

	@Test
	void autoModeDisablesStructuralDiffWhenHashSnapshotExists() throws Exception {
		Path hashFile = tempDir.resolve("existing-hashes.lz4");
		Files.writeString(hashFile, "hashes");
		inject(mojo, "changeMode", "auto");
		inject(mojo, "hashFile", hashFile.toString());
		mojo.initForTest();

		assertNull(mojo.resolveStructuralDiffModeForTest());
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

	private static final class TestableShowOrderMojo extends ShowOrderMojo {
		void initForTest() throws MojoExecutionException {
			initContext();
		}

		String resolveStructuralDiffModeForTest() {
			return resolveStructuralDiffMode();
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
		protected TestOrderState loadState() {
			return new TestOrderState();
		}
	}
}
