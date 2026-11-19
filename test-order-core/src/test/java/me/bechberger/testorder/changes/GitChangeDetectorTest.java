package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
	void readFilesFromGitBatchReturnsEmptyForEmptyFileList() throws Exception {
		Method method = GitChangeDetector.class.getDeclaredMethod("readFilesFromGitBatch", Path.class, String.class,
				List.class);
		method.setAccessible(true);

		@SuppressWarnings("unchecked")
		Map<String, String> result = (Map<String, String>) method.invoke(null, tempDir, "HEAD", List.of());
		assertTrue(result.isEmpty());
	}

	// ── Snapshot-based overloads (RepoChangeListings wiring) ─────────

	/**
	 * Fixture: initial commit, then a second commit that modifies, renames and adds
	 * Java files (plus, when {@code deleteFile} is set, deletes a multi-class
	 * file), plus uncommitted work on top (modification + untracked file in a
	 * tracked directory).
	 */
	private void commitHistory(boolean deleteFile) throws Exception {
		git("init");
		git("config", "user.email", "test@test.com");
		git("config", "user.name", "Test");
		Path srcDir = tempDir.resolve("src/main/java/com/example");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("Multi.java"),
				"package com.example;\npublic class Multi {}\nclass MultiHelper {}");
		Files.writeString(srcDir.resolve("Old.java"), "package com.example;\npublic class Old {}");
		Files.writeString(srcDir.resolve("Foo.java"), "package com.example;\npublic class Foo {}");
		git("add", ".");
		git("commit", "-m", "initial");

		Files.writeString(srcDir.resolve("Foo.java"), "package com.example;\npublic class Foo { int x; }");
		if (deleteFile) {
			Files.delete(srcDir.resolve("Multi.java"));
			git("add", "src/main/java/com/example/Multi.java");
		}
		git("mv", "src/main/java/com/example/Old.java", "src/main/java/com/example/Renamed.java");
		Files.writeString(srcDir.resolve("Added.java"), "package com.example;\npublic class Added {}");
		git("add", ".");
		git("commit", "-m", "second");

		// uncommitted work on top
		Files.writeString(srcDir.resolve("Renamed.java"), "package com.example;\npublic class Renamed { int y; }");
		Files.writeString(srcDir.resolve("Untracked.java"), "package com.example;\npublic class Untracked {}");
	}

	private void commitRichHistory() throws Exception {
		commitHistory(true);
	}

	private void commitHistoryWithoutDeletion() throws Exception {
		commitHistory(false);
	}

	@Test
	void snapshotPathMatchesDirectForAddedModifiedDeletedRenamed() throws Exception {
		commitRichHistory();
		RepoChangeListings.Snapshot snapshot = RepoChangeListings.acquire(RepoChangeListings.resolveGitRoot(tempDir));
		assumeTrue(snapshot != null, "git state could not be validated");

		// since-last-commit: added/modified/deleted/renamed committed in one go
		Set<String> directCommit = GitChangeDetector.changedSinceLastCommit(tempDir, "src/main/java/");
		Set<String> snapshotCommit = GitChangeDetector.changedSinceLastCommit(tempDir, "src/main/java/", snapshot);
		assertEquals(directCommit, snapshotCommit);
		assertTrue(directCommit.contains("com.example.Foo"), "modified: " + directCommit);
		assertTrue(directCommit.contains("com.example.Multi") && directCommit.contains("com.example.MultiHelper"),
				"deleted file classes read from HEAD~1: " + directCommit);
		assertTrue(directCommit.contains("com.example.Renamed"), "renamed: " + directCommit);
		assertTrue(directCommit.contains("com.example.Added"), "added: " + directCommit);

		// uncommitted: modification + untracked file, snapshot acquired AFTER the
		// change
		Set<String> directUncommitted = GitChangeDetector.uncommittedChanges(tempDir, "src/main/java/");
		Set<String> snapshotUncommitted = GitChangeDetector.uncommittedChanges(tempDir, "src/main/java/", snapshot);
		assertEquals(directUncommitted, snapshotUncommitted);
		assertTrue(directUncommitted.contains("com.example.Renamed"));
		assertTrue(directUncommitted.contains("com.example.Untracked"));
	}

	@Test
	void changeDetectorSnapshotOverloadMatchesNullSnapshotOverload() throws Exception {
		commitRichHistory();
		RepoChangeListings.Snapshot snapshot = RepoChangeListings.acquire(RepoChangeListings.resolveGitRoot(tempDir));
		assumeTrue(snapshot != null, "git state could not be validated");

		Path srcRoot = tempDir.resolve("src/main/java");
		assertEquals(ChangeDetector.detectReadOnly(ChangeDetector.Mode.UNCOMMITTED, tempDir, srcRoot, null, null),
				ChangeDetector.detectReadOnly(ChangeDetector.Mode.UNCOMMITTED, tempDir, srcRoot, null, null, snapshot));
		assertEquals(ChangeDetector.detectReadOnly(ChangeDetector.Mode.SINCE_LAST_COMMIT, tempDir, srcRoot, null, null),
				ChangeDetector.detectReadOnly(ChangeDetector.Mode.SINCE_LAST_COMMIT, tempDir, srcRoot, null, null,
						snapshot));
	}

	/**
	 * The snapshot path's own filtering must not spawn git. A PATH-shim git wrapper
	 * cannot intercept {@code ProcessBuilder("git")} inside the JVM (the process
	 * environment is immutable), so the honest seam is GitSupport's spawn counter.
	 * Deletions are excluded here by design: reading a deleted file's old content
	 * via {@code git cat-file --batch} is post-filtering work shared with the
	 * direct path, not snapshot filtering.
	 */
	@Test
	void snapshotFilteringSpawnsNoGit() throws Exception {
		commitHistoryWithoutDeletion();
		// Deletion-free history by design: reading a deleted file's old content via
		// git cat-file --batch is post-filtering work shared with the direct path,
		// not snapshot filtering, and would legitimately spawn git in both paths.
		RepoChangeListings.Snapshot snapshot = RepoChangeListings.acquire(RepoChangeListings.resolveGitRoot(tempDir));
		assumeTrue(snapshot != null, "git state could not be validated");
		// warm the lazily computed listings the snapshot path reads
		snapshot.statusPorcelainPaths();
		snapshot.lastCommit();
		snapshot.trackedPaths();

		long before = GitSupport.spawnCount();
		GitChangeDetector.uncommittedChanges(tempDir, "src/main/java/", snapshot);
		GitChangeDetector.changedSinceLastCommit(tempDir, "src/main/java/", snapshot);
		assertEquals(before, GitSupport.spawnCount(), "snapshot-based filtering must not spawn git");
	}
}
