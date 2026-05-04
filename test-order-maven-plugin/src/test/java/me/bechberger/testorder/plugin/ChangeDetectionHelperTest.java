package me.bechberger.testorder.plugin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.changes.ChangeDetectionSupport;
import me.bechberger.testorder.changes.ChangeDetector;
import me.bechberger.testorder.ops.ChangeDetectionOps;
import me.bechberger.testorder.ops.PluginLog;

/**
 * Tests for change detection operations (formerly ChangeDetectionHelper). Now
 * tests core APIs directly.
 */
class ChangeDetectionHelperTest {

	@TempDir
	Path tempDir;

	private static final PluginLog LOG = new PluginLog() {
		@Override
		public void info(String msg) {
		}

		@Override
		public void debug(String msg) {
		}

		@Override
		public void warn(String msg) {
		}
	};

	@Test
	void parseModeSupportsKnownValues() throws IOException {
		assertEquals(ChangeDetector.Mode.SINCE_LAST_RUN, ChangeDetectionSupport.parseMode("since-last-run"));
		assertEquals(ChangeDetector.Mode.SINCE_LAST_COMMIT, ChangeDetectionSupport.parseMode("since-last-commit"));
		assertEquals(ChangeDetector.Mode.UNCOMMITTED, ChangeDetectionSupport.parseMode("uncommitted"));
		assertEquals(ChangeDetector.Mode.EXPLICIT, ChangeDetectionSupport.parseMode("explicit"));
	}

	@Test
	void parseModeRejectsUnknownValue() {
		IOException ex = assertThrows(IOException.class, () -> ChangeDetectionSupport.parseMode("bogus"));
		assertTrue(ex.getMessage().contains("Unknown changeMode"));
	}

	@Test
	void detectChangedClassesExplicitModeReturnsConfiguredClasses() throws Exception {
		Path sourceRoot = tempDir.resolve("src/main/java");
		Files.createDirectories(sourceRoot);

		Set<String> changed = ChangeDetectionOps.detectChangedClasses("explicit", tempDir, sourceRoot,
				tempDir.resolve("hashes.lz4"), "com.example.Foo,com.example.Bar", true, LOG);

		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), changed);
	}

	@Test
	void detectChangedTestClassesSkipsExplicitMode() throws Exception {
		Path testRoot = tempDir.resolve("src/test/java");
		Files.createDirectories(testRoot);

		Set<String> changedTests = ChangeDetectionOps.detectChangedTestClasses("explicit", tempDir, testRoot,
				tempDir.resolve("test-hashes.lz4"), true, LOG);

		assertEquals(Set.of(), changedTests);
	}

	@Test
	void reactorContextUsesGitRootForMultiModuleGitDetection() throws Exception {
		git("init");
		git("config", "user.email", "test@test.com");
		git("config", "user.name", "Test");

		Path moduleDir = tempDir.resolve("module-a");
		Path sourceRoot = moduleDir.resolve("src/main/java/com/example");
		Files.createDirectories(sourceRoot);
		Files.writeString(sourceRoot.resolve("Foo.java"), "package com.example; public class Foo {}\n",
				StandardCharsets.UTF_8);
		git("add", ".");
		git("commit", "-m", "initial");

		Files.writeString(sourceRoot.resolve("Foo.java"), "package com.example; public class Foo { int x; }\n",
				StandardCharsets.UTF_8);

		MavenProject topLevel = mock(MavenProject.class);
		when(topLevel.getBasedir()).thenReturn(tempDir.toFile());
		when(topLevel.getArtifactId()).thenReturn("root");

		MavenProject module = mock(MavenProject.class);
		when(module.getBasedir()).thenReturn(moduleDir.toFile());
		when(module.getArtifactId()).thenReturn("module-a");
		when(module.getProperties()).thenReturn(new Properties());

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(topLevel, module));
		when(session.getTopLevelProject()).thenReturn(topLevel);
		when(session.getUserProperties()).thenReturn(new Properties());
		when(session.getProjectDependencyGraph()).thenReturn(null);

		ReactorContext ctx = new ReactorContext(session, module);

		// Test that ChangeDetectionOps uses git root (tempDir) not module basedir
		Set<String> changed = ChangeDetectionOps.detectChangedClassesWithKotlin("uncommitted", ctx.gitRoot(),
				moduleDir.resolve("src/main/java"), tempDir.resolve("hashes.lz4"), null, true, LOG);

		assertTrue(changed.contains("com.example.Foo"), "changed classes: " + changed);
	}

	private void git(String... args) throws IOException, InterruptedException {
		var cmd = new java.util.ArrayList<String>();
		cmd.add("git");
		java.util.Collections.addAll(cmd, args);
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.directory(tempDir.toFile());
		pb.redirectErrorStream(true);
		Process p = pb.start();
		p.getInputStream().readAllBytes();
		int exitCode = p.waitFor();
		if (exitCode != 0) {
			throw new RuntimeException("git " + String.join(" ", args) + " failed with exit code " + exitCode);
		}
	}
}
