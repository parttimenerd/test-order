package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitChangeDetectorTest {

	@TempDir
	Path tempDir;

	private void git(String... args) throws IOException, InterruptedException {
		var cmd = new java.util.ArrayList<String>();
		cmd.add("git");
		java.util.Collections.addAll(cmd, args);
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.directory(tempDir.toFile());
		// Prevent background git processes (gc, maintenance, fsmonitor, credential
		// helpers) that hold file locks and cause @TempDir cleanup failures on CI.
		pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		pb.environment().put("GIT_TERMINAL_PROMPT", "0");
		pb.environment().put("GIT_CONFIG_COUNT", "5");
		pb.environment().put("GIT_CONFIG_KEY_0", "gc.auto");
		pb.environment().put("GIT_CONFIG_VALUE_0", "0");
		pb.environment().put("GIT_CONFIG_KEY_1", "core.fsmonitor");
		pb.environment().put("GIT_CONFIG_VALUE_1", "false");
		pb.environment().put("GIT_CONFIG_KEY_2", "gc.autoDetach");
		pb.environment().put("GIT_CONFIG_VALUE_2", "false");
		pb.environment().put("GIT_CONFIG_KEY_3", "maintenance.auto");
		pb.environment().put("GIT_CONFIG_VALUE_3", "0");
		pb.environment().put("GIT_CONFIG_KEY_4", "credential.helper");
		pb.environment().put("GIT_CONFIG_VALUE_4", "");
		pb.redirectErrorStream(true);
		Process p = pb.start();
		p.getInputStream().readAllBytes(); // consume output
		int exitCode = p.waitFor();
		if (exitCode != 0) {
			throw new RuntimeException("git " + String.join(" ", args) + " failed with exit code " + exitCode);
		}
	}

	@Test
	void changedSinceLastCommit() throws Exception {
		// init repo
		git("init");
		git("config", "user.email", "test@test.com");
		git("config", "user.name", "Test");

		// create initial file and commit
		Path srcDir = tempDir.resolve("src/main/java/com/example");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("Foo.java"), "public class Foo {}");
		git("add", ".");
		git("commit", "-m", "initial");

		// modify file and commit
		Files.writeString(srcDir.resolve("Foo.java"), "public class Foo { int x; }");
		git("add", ".");
		git("commit", "-m", "modify Foo");

		Set<String> changed = GitChangeDetector.changedSinceLastCommit(tempDir, "src/main/java/");
		assertTrue(changed.contains("com.example.Foo"));
	}

	@Test
	void uncommittedChanges() throws Exception {
		git("init");
		git("config", "user.email", "test@test.com");
		git("config", "user.name", "Test");

		Path srcDir = tempDir.resolve("src/main/java/com/example");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("Foo.java"), "public class Foo {}");
		git("add", ".");
		git("commit", "-m", "initial");

		// unstaged change
		Files.writeString(srcDir.resolve("Foo.java"), "public class Foo { int x; }");

		Set<String> changed = GitChangeDetector.uncommittedChanges(tempDir, "src/main/java/");
		assertTrue(changed.contains("com.example.Foo"));
	}

	@Test
	void stagedChanges() throws Exception {
		git("init");
		git("config", "user.email", "test@test.com");
		git("config", "user.name", "Test");

		Path srcDir = tempDir.resolve("src/main/java/com/example");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("Foo.java"), "public class Foo {}");
		git("add", ".");
		git("commit", "-m", "initial");

		// staged change
		Files.writeString(srcDir.resolve("Bar.java"), "public class Bar {}");
		git("add", ".");

		Set<String> changed = GitChangeDetector.uncommittedChanges(tempDir, "src/main/java/");
		assertTrue(changed.contains("com.example.Bar"));
	}

	@Test
	void noChanges() throws Exception {
		git("init");
		git("config", "user.email", "test@test.com");
		git("config", "user.name", "Test");

		Path srcDir = tempDir.resolve("src/main/java/com/example");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("Foo.java"), "public class Foo {}");
		git("add", ".");
		git("commit", "-m", "initial");

		// no changes since last commit — need a second commit so HEAD~1 exists
		Files.writeString(srcDir.resolve("Foo.java"), "public class Foo { }");
		git("add", ".");
		git("commit", "-m", "second");

		Set<String> uncommitted = GitChangeDetector.uncommittedChanges(tempDir, "src/main/java/");
		assertTrue(uncommitted.isEmpty());
	}

	@Test
	void deletedFileExtractsClassNamesFromGit() throws Exception {
		git("init");
		git("config", "user.email", "test@test.com");
		git("config", "user.name", "Test");

		Path srcDir = tempDir.resolve("src/main/java/com/example");
		Files.createDirectories(srcDir);
		// File with multiple top-level classes
		Files.writeString(srcDir.resolve("Multi.java"),
				"package com.example;\npublic class Multi {}\nclass MultiHelper {}");
		git("add", ".");
		git("commit", "-m", "initial");

		// Delete the file and commit the deletion
		Files.delete(srcDir.resolve("Multi.java"));
		git("add", ".");
		git("commit", "-m", "delete Multi");

		Set<String> changed = GitChangeDetector.changedSinceLastCommit(tempDir, "src/main/java/");
		// Both classes should be detected even though the file no longer exists on disk
		assertTrue(changed.contains("com.example.Multi"), "should contain Multi");
		assertTrue(changed.contains("com.example.MultiHelper"), "should contain MultiHelper");
	}

	@Test
	void changedSinceLastCommitFallsBackWhenHeadParentIsMissing() throws Exception {
		git("init");
		git("config", "user.email", "test@test.com");
		git("config", "user.name", "Test");

		Path srcDir = tempDir.resolve("src/main/java/com/example");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("Foo.java"), "public class Foo {}");
		git("add", ".");
		git("commit", "-m", "initial");

		Set<String> changed = GitChangeDetector.changedSinceLastCommit(tempDir, "src/main/java/");
		assertEquals(Set.of("com.example.Foo"), changed);
	}

	@Test
	void readFileFromGitReturnsNullWhenCommitRefMissing() throws Exception {
		Method method = GitChangeDetector.class.getDeclaredMethod("readFileFromGit", Path.class, String.class,
				String.class);
		method.setAccessible(true);

		Object result = method.invoke(null, tempDir, null, "src/main/java/com/example/Foo.java");
		assertNull(result);
	}
}
