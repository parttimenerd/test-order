package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link RepoChangeListings} (the repo-level git listing cache):
 * byte-identical results vs. the direct git paths, sound self-invalidation on
 * worktree/index/HEAD changes, and fail-safe behavior without git.
 */
class RepoChangeListingsTest {

	@TempDir
	Path tempDir;

	@BeforeAll
	static void requireGit() {
		assumeTrue(gitAvailable(), "git CLI unavailable");
	}

	private static boolean gitAvailable() {
		try {
			Process p = new ProcessBuilder("git", "--version").start();
			p.getInputStream().readAllBytes();
			return p.waitFor() == 0;
		} catch (IOException | InterruptedException e) {
			return false;
		}
	}

	private void git(String... args) throws IOException, InterruptedException {
		var cmd = new java.util.ArrayList<String>();
		cmd.add("git");
		java.util.Collections.addAll(cmd, args);
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.directory(tempDir.toFile());
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
		p.getInputStream().readAllBytes();
		int exitCode = p.waitFor();
		if (exitCode != 0) {
			throw new RuntimeException("git " + String.join(" ", args) + " failed with exit code " + exitCode);
		}
	}

	private void initRepo() throws Exception {
		git("init");
		git("config", "user.email", "test@test.com");
		git("config", "user.name", "Test");
	}

	/**
	 * module-a/ layout inside the repo: project root is a subdirectory. Returns the
	 * symlink-resolved path — git itself resolves symlinks in
	 * {@code rev-parse --show-toplevel}, and StructuralDiff filters changed files
	 * by {@code absFile.startsWith(absProjectRoot)} (pre-existing behavior shared
	 * by the direct and cached paths).
	 */
	private Path moduleA() throws IOException {
		return tempDir.resolve("module-a").toRealPath();
	}

	private void commitBase() throws Exception {
		Path srcA = tempDir.resolve("module-a/src/main/java/com/example");
		Path resA = tempDir.resolve("module-a/src/main/resources");
		Path srcB = tempDir.resolve("module-b/src/main/java/com/example");
		Files.createDirectories(srcA);
		Files.createDirectories(resA);
		Files.createDirectories(srcB);
		Files.writeString(srcA.resolve("Foo.java"),
				"package com.example; public class Foo { int f() { return 1; } }\n");
		Files.writeString(resA.resolve("app.properties"), "key=value");
		Files.writeString(srcB.resolve("Bar.java"),
				"package com.example; public class Bar { int g() { return 2; } }\n");
		Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
		git("add", ".");
		git("commit", "-m", "initial");
	}

	// ── StructuralDiff: cached path is byte-identical to the direct path ──

	@Test
	void structuralDiffCachedMatchesDirectForUncommittedChanges() throws Exception {
		initRepo();
		commitBase();
		// modify tracked, add untracked, stage an addition, touch a resource
		Files.writeString(tempDir.resolve("module-a/src/main/java/com/example/Foo.java"),
				"package com.example; public class Foo { int f() { return 11; } }\n");
		Files.writeString(tempDir.resolve("module-a/src/main/java/com/example/New.java"),
				"package com.example; public class New {}\n");
		Path staged = tempDir.resolve("module-b/src/main/java/com/example/Staged.java");
		Files.writeString(staged, "package com.example; public class Staged {}\n");
		git("add", ".");
		Files.writeString(tempDir.resolve("module-a/src/main/resources/app.properties"), "key=value2");
		// a changed file OUTSIDE the module must stay excluded from the module diff
		Files.writeString(tempDir.resolve("module-b/src/main/java/com/example/Bar.java"),
				"package com.example; public class Bar { int g() { return 22; } }\n");

		List<StructuralDiff.FileDiff> direct = StructuralDiff.diffGitChangesDirectForTest(moduleA(), false);
		List<StructuralDiff.FileDiff> cached = StructuralDiff.diffUncommitted(moduleA());

		assertEquals(direct.size(), cached.size(), "same number of diffs");
		for (int i = 0; i < direct.size(); i++) {
			assertEquals(direct.get(i).file(), cached.get(i).file(), "same files in same order");
			assertEquals(direct.get(i).changes(), cached.get(i).changes(), "same changes");
			assertEquals(direct.get(i).bodyChanges(), cached.get(i).bodyChanges(), "same body changes");
		}
		assertTrue(cached.stream().anyMatch(d -> d.file().toString().endsWith("Foo.java")));
		assertTrue(cached.stream().anyMatch(d -> d.file().toString().endsWith("New.java")));
		assertTrue(cached.stream().noneMatch(d -> d.file().toString().endsWith("Bar.java")),
				"sibling-module change must not leak into the module diff");
		assertTrue(cached.stream().noneMatch(d -> d.file().toString().endsWith("app.properties")),
				"non-source files are not structurally diffed");
	}

	@Test
	void structuralDiffCachedMatchesDirectForSinceLastCommit() throws Exception {
		initRepo();
		commitBase();
		Files.writeString(tempDir.resolve("module-a/src/main/java/com/example/Foo.java"),
				"package com.example; public class Foo { int f() { return 2; } }\n");
		git("add", ".");
		git("commit", "-m", "second");
		// plus an uncommitted change on top
		Files.writeString(tempDir.resolve("module-a/src/main/java/com/example/New.java"),
				"package com.example; public class New {}\n");

		List<StructuralDiff.FileDiff> direct = StructuralDiff.diffGitChangesDirectForTest(moduleA(), true);
		List<StructuralDiff.FileDiff> cached = StructuralDiff.diffSinceLastCommit(moduleA());

		assertEquals(direct.size(), cached.size());
		for (int i = 0; i < direct.size(); i++) {
			assertEquals(direct.get(i).file(), cached.get(i).file());
			assertEquals(direct.get(i).changes(), cached.get(i).changes());
			assertEquals(direct.get(i).bodyChanges(), cached.get(i).bodyChanges());
		}
	}

	@Test
	void structuralDiffCachedMissingHeadParentFallsBackLikeDirect() throws Exception {
		// single-commit repo: HEAD~1 unavailable
		initRepo();
		commitBase();
		Files.writeString(tempDir.resolve("module-a/src/main/java/com/example/Foo.java"),
				"package com.example; public class Foo { int f() { return 3; } }\n");

		List<StructuralDiff.FileDiff> direct = StructuralDiff.diffGitChangesDirectForTest(moduleA(), true);
		List<StructuralDiff.FileDiff> cached = StructuralDiff.diffSinceLastCommit(moduleA());
		assertEquals(direct.size(), cached.size());
		for (int i = 0; i < direct.size(); i++) {
			assertEquals(direct.get(i).file(), cached.get(i).file());
			assertEquals(direct.get(i).changes(), cached.get(i).changes());
		}
	}

	// ── Self-invalidation ────────────────────────────────────────────

	@Test
	void cacheInvalidatesWhenWorktreeStateChanges() throws Exception {
		initRepo();
		commitBase();
		Files.writeString(tempDir.resolve("module-a/src/main/java/com/example/Foo.java"),
				"package com.example; public class Foo { int f() { return 9; } }\n");

		RepoChangeListings.Snapshot first = RepoChangeListings.acquire(RepoChangeListings.resolveGitRoot(tempDir));
		assertTrue(first.uncommittedPaths().contains("module-a/src/main/java/com/example/Foo.java"));

		// content-only change of an already-modified file: listings stay valid
		Files.writeString(tempDir.resolve("module-a/src/main/java/com/example/Foo.java"),
				"package com.example; public class Foo { int f() { return 10; } }\n");
		RepoChangeListings.Snapshot second = RepoChangeListings.acquire(RepoChangeListings.resolveGitRoot(tempDir));
		assertEquals(first.uncommittedPaths(), second.uncommittedPaths());

		// a NEW path appears: the fingerprint must change and the listing must grow
		Files.writeString(tempDir.resolve("module-a/src/main/java/com/example/Another.java"),
				"package com.example; public class Another {}\n");
		RepoChangeListings.Snapshot third = RepoChangeListings.acquire(RepoChangeListings.resolveGitRoot(tempDir));
		TreeSet<String> paths = new TreeSet<>(third.uncommittedPaths());
		assertTrue(paths.contains("module-a/src/main/java/com/example/Another.java"));
		assertTrue(paths.contains("module-a/src/main/java/com/example/Foo.java"));

		// staging the untracked file keeps it listed
		git("add", ".");
		RepoChangeListings.Snapshot fourth = RepoChangeListings.acquire(RepoChangeListings.resolveGitRoot(tempDir));
		assertTrue(fourth.uncommittedPaths().contains("module-a/src/main/java/com/example/Another.java"));

		// committing removes it from the uncommitted listing but moves it to
		// HEAD~1..HEAD
		git("commit", "-m", "third");
		RepoChangeListings.Snapshot fifth = RepoChangeListings.acquire(RepoChangeListings.resolveGitRoot(tempDir));
		assertFalse(fifth.uncommittedPaths().contains("module-a/src/main/java/com/example/Another.java"));
		assertTrue(fifth.lastCommit().hasHeadParent);
		assertTrue(fifth.lastCommit().diffPaths.contains("module-a/src/main/java/com/example/Another.java"));
	}

	@Test
	void cacheInvalidatesWhenUntrackedGitignoreChanges() throws Exception {
		initRepo();
		commitBase();
		Files.writeString(tempDir.resolve("module-a/gen.txt"), "generated\n");

		RepoChangeListings.Snapshot first = RepoChangeListings.acquire(RepoChangeListings.resolveGitRoot(tempDir));
		assertTrue(first.uncommittedPaths().contains("module-a/gen.txt"));

		// an untracked .gitignore now hides gen.txt from the untracked listing —
		// its v2 status line ("? .gitignore") is unchanged, so the fingerprint must
		// cover .gitignore content itself
		Files.writeString(tempDir.resolve("module-a/.gitignore"), "gen.txt\n");
		RepoChangeListings.Snapshot second = RepoChangeListings.acquire(RepoChangeListings.resolveGitRoot(tempDir));
		assertFalse(second.uncommittedPaths().contains("module-a/gen.txt"),
				"untracked listing must respect the new ignore rule");
	}

	// ── NonSourceChangeDetector: cached path matches the direct path ──

	@Test
	void nonSourceDetectionCachedMatchesDirectAcrossModules() throws Exception {
		initRepo();
		commitBase();
		Files.writeString(tempDir.resolve("module-a/src/main/resources/app.properties"), "key=changed");

		Path realRepoRoot = tempDir.toRealPath();
		RepoChangeListings.Snapshot snapshot = RepoChangeListings.acquire(realRepoRoot);
		NonSourceChangeDetector.Detection cached = NonSourceChangeDetector.detect(moduleA(), realRepoRoot, false,
				snapshot);
		NonSourceChangeDetector.Detection direct = NonSourceChangeDetector.detect(moduleA(), realRepoRoot, false, null);

		assertEquals(direct.result(), cached.result());
		assertEquals(direct.files(), cached.files());
		assertEquals(NonSourceChangeDetector.Result.CHANGED, cached.result());

		// sibling module: the change is out of scope for module-b
		NonSourceChangeDetector.Detection cachedB = NonSourceChangeDetector.detect(realRepoRoot.resolve("module-b"),
				realRepoRoot, false, snapshot);
		assertEquals(NonSourceChangeDetector.Result.UNCHANGED, cachedB.result());
	}

	@Test
	void nonSourceDetectionCachedMatchesDirectForBuildFilesAndRenames() throws Exception {
		initRepo();
		commitBase();
		// staged rename of a resource from module-a into module-b
		Files.createDirectories(tempDir.resolve("module-b/src/main/resources"));
		git("mv", "module-a/src/main/resources/app.properties", "module-b/src/main/resources/renamed.properties");

		Path realRepoRoot = tempDir.toRealPath();
		RepoChangeListings.Snapshot snapshot = RepoChangeListings.acquire(realRepoRoot);
		NonSourceChangeDetector.Detection cachedA = NonSourceChangeDetector.detect(moduleA(), realRepoRoot, false,
				snapshot);
		NonSourceChangeDetector.Detection directA = NonSourceChangeDetector.detect(moduleA(), realRepoRoot, false,
				null);
		assertEquals(directA.result(), cachedA.result());
		assertEquals(directA.files(), cachedA.files());

		Path moduleB = realRepoRoot.resolve("module-b");
		NonSourceChangeDetector.Detection cachedB = NonSourceChangeDetector.detect(moduleB, realRepoRoot, false,
				snapshot);
		NonSourceChangeDetector.Detection directB = NonSourceChangeDetector.detect(moduleB, realRepoRoot, false, null);
		assertEquals(directB.result(), cachedB.result());
		assertEquals(directB.files(), cachedB.files());
		assertTrue(cachedB.files().contains("module-b/src/main/resources/renamed.properties"),
				"rename destination must be detected for module-b: " + cachedB.files());
	}

	@Test
	void nonSourceDetectionCachedSinceLastCommitMatchesDirect() throws Exception {
		initRepo();
		commitBase();
		Files.writeString(tempDir.resolve("module-a/src/main/resources/app.properties"), "key=v2");
		git("add", ".");
		git("commit", "-m", "resource change");

		Path realRepoRoot = tempDir.toRealPath();
		RepoChangeListings.Snapshot snapshot = RepoChangeListings.acquire(realRepoRoot);
		NonSourceChangeDetector.Detection cached = NonSourceChangeDetector.detect(moduleA(), realRepoRoot, true,
				snapshot);
		NonSourceChangeDetector.Detection direct = NonSourceChangeDetector.detect(moduleA(), realRepoRoot, true, null);
		assertEquals(direct.result(), cached.result());
		assertEquals(direct.files(), cached.files());
		assertEquals(NonSourceChangeDetector.Result.CHANGED, cached.result());
		assertTrue(cached.files().contains("module-a/src/main/resources/app.properties"));
	}

	// ── Fail-safe ────────────────────────────────────────────────────

	@Test
	void acquireReturnsNullOutsideGitRepository() throws Exception {
		Path plain = Files.createTempDirectory("plain-no-git");
		try {
			assertNull(RepoChangeListings.acquire(plain));
			// detectors still work through the direct path (NOT_DETERMINED)
			assertEquals(NonSourceChangeDetector.Result.NOT_DETERMINED,
					NonSourceChangeDetector.detect(plain, plain, true).result());
		} finally {
			Files.deleteIfExists(plain);
		}
	}

	@Test
	void gitRootResolutionIsCachedAndRevalidated() throws Exception {
		initRepo();
		commitBase();
		Path root = RepoChangeListings.resolveGitRoot(tempDir);
		assertEquals(tempDir.toRealPath(), root, "git resolves symlinks in rev-parse --show-toplevel");
		// second resolution returns the cached (equal) root
		assertEquals(root, RepoChangeListings.resolveGitRoot(tempDir));
	}

	/**
	 * Symlinked spellings of the same worktree (e.g. macOS /tmp/x vs
	 * /private/tmp/x) must map to the same cache entries — otherwise two spellings
	 * produce two listings and defeat cross-module sharing.
	 */
	@Test
	void acquireAndResolveGitRootCanonicalizeSymlinkedPaths() throws Exception {
		initRepo();
		commitBase();
		Path real = tempDir.toRealPath();
		Path link = Files.createSymbolicLink(tempDir.resolveSibling(tempDir.getFileName() + "-link"), real);
		try {
			assertEquals(RepoChangeListings.resolveGitRoot(real), RepoChangeListings.resolveGitRoot(link),
					"resolveGitRoot must return the canonical root for both spellings");
			RepoChangeListings.Snapshot viaReal = RepoChangeListings.acquire(real);
			RepoChangeListings.Snapshot viaLink = RepoChangeListings.acquire(link);
			assertEquals(viaReal.gitRoot(), viaLink.gitRoot(),
					"acquire must canonicalize the entry key so both spellings share one entry");
		} finally {
			Files.deleteIfExists(link);
		}
	}
}
