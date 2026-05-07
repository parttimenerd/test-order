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

class DumpMojoTest {

	@TempDir
	Path tempDir;

	private DumpMojo mojo;

	@BeforeEach
	void setUp() throws Exception {
		mojo = new DumpMojo();

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

		inject(mojo, "session", session);
		inject(mojo, "project", project);
		inject(mojo, "indexFile", tempDir.resolve("index.lz4").toString());
	}

	@Test
	void missingIndexThrows() {
		MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> mojo.execute());
		assertTrue(ex.getMessage().contains("Dependency index not found"));
	}

	@Test
	void emptyIndexIsHandledGracefully() throws Exception {
		new DependencyMap().save(tempDir.resolve("index.lz4"));
		assertDoesNotThrow(() -> mojo.execute());
	}

	@Test
	void writesTextDumpWhenOutputFileIsConfigured() throws Exception {
		DependencyMap map = new DependencyMap();
		map.put("com.example.MyTest", Set.of("com.app.A", "com.app.B"));
		map.save(tempDir.resolve("index.lz4"));

		Path out = tempDir.resolve("dump.txt");
		inject(mojo, "outputFile", out.toString());

		assertDoesNotThrow(() -> mojo.execute());
		assertTrue(Files.exists(out));
		String text = Files.readString(out);
		assertTrue(text.contains("com.example.MyTest"));
		assertTrue(text.contains("com.app.A"));
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
