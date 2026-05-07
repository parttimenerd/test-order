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

class AggregateMojoTest {

	@TempDir
	Path tempDir;

	private AggregateMojo mojo;

	@BeforeEach
	void setUp() throws Exception {
		mojo = new AggregateMojo();

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
		inject(mojo, "depsDir", tempDir.resolve("deps").toString());
		inject(mojo, "indexFile", tempDir.resolve("index.lz4").toString());
	}

	@Test
	void missingDepsDirectoryFailsWithHelpfulMessage() {
		MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
		assertTrue(ex.getMessage().contains("Deps directory does not exist"));
		assertTrue(ex.getMessage().contains("mvn test-order:auto test"));
	}

	@Test
	void emptyDepsDoesNotOverwriteExistingIndex() throws Exception {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);

		DependencyMap existing = new DependencyMap();
		existing.put("com.example.ExistingTest", Set.of("com.app.A"));
		Path index = tempDir.resolve("index.lz4");
		existing.save(index);

		mojo.execute();

		DependencyMap loaded = DependencyMap.load(index);
		assertEquals(Set.of("com.example.ExistingTest"), loaded.testClasses());
	}

	@Test
	void aggregatesDepsFilesIntoIndex() throws Exception {
		Path depsDir = tempDir.resolve("deps");
		Files.createDirectories(depsDir);
		Files.writeString(depsDir.resolve("com.example.MyTest.deps"), "com.app.A\ncom.app.B\n");

		mojo.execute();

		DependencyMap loaded = DependencyMap.load(tempDir.resolve("index.lz4"));
		assertEquals(Set.of("com.example.MyTest"), loaded.testClasses());
		assertEquals(Set.of("com.app.A", "com.app.B"), loaded.get("com.example.MyTest"));
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
