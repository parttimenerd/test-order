package me.bechberger.testorder.plugin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

class SnapshotMojoTest {

	@TempDir
	Path tempDir;

	private SnapshotMojo mojo;

	@BeforeEach
	void setUp() throws Exception {
		mojo = new SnapshotMojo();

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
		inject(mojo, "hashFile", tempDir.resolve("main-hashes.lz4").toString());
		inject(mojo, "testHashFile", tempDir.resolve("test-hashes.lz4").toString());
	}

	@Test
	void missingSourceRootsIsNoOp() {
		assertDoesNotThrow(() -> mojo.execute());
		assertFalse(Files.exists(tempDir.resolve("main-hashes.lz4")));
		assertFalse(Files.exists(tempDir.resolve("test-hashes.lz4")));
	}

	@Test
	void existingSourceRootsCreateSnapshots() throws Exception {
		Path mainRoot = tempDir.resolve("src/main/java/com/example");
		Path testRoot = tempDir.resolve("src/test/java/com/example");
		Files.createDirectories(mainRoot);
		Files.createDirectories(testRoot);
		Files.writeString(mainRoot.resolve("Foo.java"), "package com.example; class Foo {}\n");
		Files.writeString(testRoot.resolve("FooTest.java"), "package com.example; class FooTest {}\n");

		assertDoesNotThrow(() -> mojo.execute());

		Path mainHashes = tempDir.resolve("main-hashes.lz4");
		Path testHashes = tempDir.resolve("test-hashes.lz4");
		assertTrue(Files.exists(mainHashes));
		assertTrue(Files.exists(testHashes));
		assertTrue(Files.size(mainHashes) > 0);
		assertTrue(Files.size(testHashes) > 0);
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
