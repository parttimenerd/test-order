package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.TestOrderState;

class OptimizeMojoTest {

	@TempDir
	Path tempDir;

	private OptimizeMojo mojo;

	@BeforeEach
	void setUp() throws Exception {
		mojo = new OptimizeMojo();

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
		inject(mojo, "stateFile", tempDir.resolve(".test-order-state").toString());
	}

	@Test
	void missingStateFileThrowsHelpfulException() {
		MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> mojo.execute());
		assertTrue(ex.getMessage().contains("No state file found"));
		assertTrue(ex.getMessage().contains("Run some test-order test runs first"));
	}

	@Test
	void malformedStateFileIsWrappedAsMojoExecutionException() throws Exception {
		Files.writeString(tempDir.resolve(".test-order-state"), "not-valid-state-content\n");

		MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> mojo.execute());
		assertTrue(ex.getMessage().contains("Failed to load state file"));
	}

	@Test
	void insufficientFailureRunsReturnsWithoutThrowing() throws Exception {
		// A fresh state has too few runs-with-failures for optimization.
		TestOrderState state = new TestOrderState();
		state.save(tempDir.resolve(".test-order-state"));

		assertDoesNotThrow(() -> mojo.execute());
		assertTrue(Files.exists(tempDir.resolve(".test-order-state")));
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
}
