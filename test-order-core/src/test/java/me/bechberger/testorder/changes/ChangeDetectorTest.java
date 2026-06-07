package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangeDetectorTest {

	@TempDir
	Path tempDir;

	@Test
	void explicitMode() throws IOException {
		Set<String> result = ChangeDetector.detect(ChangeDetector.Mode.EXPLICIT, tempDir, Path.of("src/main/java"),
				tempDir.resolve("hashes.gz"), "com.example.Foo,com.example.Bar");
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), result);
	}

	@Test
	void explicitModeAcceptsSemicolonFallback() throws IOException {
		Set<String> result = ChangeDetector.detect(ChangeDetector.Mode.EXPLICIT, tempDir, Path.of("src/main/java"),
				tempDir.resolve("hashes.gz"), "com.example.Foo;com.example.Bar");
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), result);
	}

	@Test
	void explicitModeEmpty() throws IOException {
		Set<String> result = ChangeDetector.detect(ChangeDetector.Mode.EXPLICIT, tempDir, Path.of("src/main/java"),
				tempDir.resolve("hashes.gz"), "");
		assertTrue(result.isEmpty());
	}

	@Test
	void sinceLastRunFirstTime() throws IOException {
		// no previous hash file → all files are "changed"
		Path srcRoot = tempDir.resolve("src/main/java");
		Files.createDirectories(srcRoot.resolve("com/example"));
		Files.writeString(srcRoot.resolve("com/example/Foo.java"), "public class Foo {}");

		Path hashFile = tempDir.resolve("hashes.gz");

		Set<String> result = ChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, tempDir,
				Path.of("src/main/java"), hashFile, null);
		assertTrue(result.contains("com.example.Foo"));
		// hash file should now exist
		assertTrue(Files.exists(hashFile));
	}

	@Test
	void sinceLastRunDetectsChange() throws IOException {
		Path srcRoot = tempDir.resolve("src/main/java");
		Files.createDirectories(srcRoot.resolve("com/example"));
		Files.writeString(srcRoot.resolve("com/example/Foo.java"), "public class Foo {}");
		Files.writeString(srcRoot.resolve("com/example/Bar.java"), "public class Bar {}");

		Path hashFile = tempDir.resolve("hashes.gz");

		// first run — creates snapshot
		ChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, tempDir, Path.of("src/main/java"), hashFile, null);

		// modify one file
		Files.writeString(srcRoot.resolve("com/example/Foo.java"), "public class Foo { int x; }");

		// second run — should detect the change
		Set<String> result = ChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, tempDir,
				Path.of("src/main/java"), hashFile, null);
		assertEquals(Set.of("com.example.Foo"), result);
	}

	@Test
	void sinceLastRunIgnoresTimestampOnlyChanges() throws IOException {
		Path srcRoot = tempDir.resolve("src/main/java");
		Files.createDirectories(srcRoot.resolve("com/example"));
		Path foo = srcRoot.resolve("com/example/Foo.java");
		Files.writeString(foo, "public class Foo {}");

		Path hashFile = tempDir.resolve("hashes.gz");

		ChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, tempDir, Path.of("src/main/java"), hashFile, null);

		FileTime originalTime = Files.getLastModifiedTime(foo);
		Files.setLastModifiedTime(foo, FileTime.fromMillis(originalTime.toMillis() + 5_000));

		Set<String> result = ChangeDetector.detectReadOnly(ChangeDetector.Mode.SINCE_LAST_RUN, tempDir,
				Path.of("src/main/java"), hashFile, null);

		assertTrue(result.isEmpty(), "Timestamp-only changes must not be reported as source changes");
	}

	@Test
	void sinceLastRunDetectsContentChangeEvenIfTimestampIsPreserved() throws IOException {
		Path srcRoot = tempDir.resolve("src/main/java");
		Files.createDirectories(srcRoot.resolve("com/example"));
		Path foo = srcRoot.resolve("com/example/Foo.java");
		Files.writeString(foo, "public class Foo {}");

		Path hashFile = tempDir.resolve("hashes.gz");

		ChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, tempDir, Path.of("src/main/java"), hashFile, null);

		FileTime originalTime = Files.getLastModifiedTime(foo);
		Files.writeString(foo, "public class Foo { int x; }");
		Files.setLastModifiedTime(foo, originalTime);

		Set<String> result = ChangeDetector.detectReadOnly(ChangeDetector.Mode.SINCE_LAST_RUN, tempDir,
				Path.of("src/main/java"), hashFile, null);

		assertEquals(Set.of("com.example.Foo"), result,
				"Content changes must still be detected even when timestamps are unchanged");
	}

	/**
	 * Helper to run git commands in a temp directory.
	 */
	private void git(Path dir, String... args) throws Exception {
		var cmd = new java.util.ArrayList<String>();
		cmd.add("git");
		java.util.Collections.addAll(cmd, args);
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.directory(dir.toFile());
		pb.redirectErrorStream(true);
		Process p = pb.start();
		p.getInputStream().readAllBytes();
		int exitCode = p.waitFor();
		if (exitCode != 0) {
			throw new RuntimeException("git " + String.join(" ", args) + " failed with exit code " + exitCode);
		}
	}

	@Test
	void sinceLastCommitMergesUncommittedChanges() throws Exception {
		// Set up a git repo in tempDir
		git(tempDir, "init");
		git(tempDir, "config", "user.email", "test@test.com");
		git(tempDir, "config", "user.name", "Test");

		Path srcDir = tempDir.resolve("src/main/java/com/example");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("Foo.java"), "public class Foo {}");
		Files.writeString(srcDir.resolve("Bar.java"), "public class Bar {}");
		git(tempDir, "add", ".");
		git(tempDir, "commit", "-m", "initial");

		// Modify Foo and commit
		Files.writeString(srcDir.resolve("Foo.java"), "public class Foo { int x; }");
		git(tempDir, "add", ".");
		git(tempDir, "commit", "-m", "modify Foo");

		// Now make an uncommitted change to Bar (unstaged)
		Files.writeString(srcDir.resolve("Bar.java"), "public class Bar { int y; }");

		// SINCE_LAST_COMMIT should detect both:
		// - Foo (from the last commit)
		// - Bar (from the uncommitted change, merged in)
		Set<String> result = ChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_COMMIT, tempDir,
				Path.of("src/main/java"), tempDir.resolve("hashes.gz"), null);
		assertTrue(result.contains("com.example.Foo"), "should contain committed change Foo");
		assertTrue(result.contains("com.example.Bar"), "should contain uncommitted change Bar");
	}

	@Test
	void detectReadOnlyDoesNotUpdateSnapshot() throws IOException {
		// set up two source files
		Path srcRoot = tempDir.resolve("src/main/java");
		Files.createDirectories(srcRoot.resolve("com/example"));
		Files.writeString(srcRoot.resolve("com/example/Foo.java"), "public class Foo {}");
		Files.writeString(srcRoot.resolve("com/example/Bar.java"), "public class Bar {}");

		Path hashFile = tempDir.resolve("hashes.lz4");

		// first run — creates snapshot (using detect which updates)
		ChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, tempDir, Path.of("src/main/java"), hashFile, null);
		assertTrue(Files.exists(hashFile));
		long snapshotSize = Files.size(hashFile);
		byte[] snapshotBytes = Files.readAllBytes(hashFile);

		// modify a file
		Files.writeString(srcRoot.resolve("com/example/Foo.java"), "public class Foo { int x; }");

		// detectReadOnly — should detect the change but NOT update snapshot
		Set<String> result = ChangeDetector.detectReadOnly(ChangeDetector.Mode.SINCE_LAST_RUN, tempDir,
				Path.of("src/main/java"), hashFile, null);
		assertEquals(Set.of("com.example.Foo"), result);

		// snapshot should be unchanged
		assertArrayEquals(snapshotBytes, Files.readAllBytes(hashFile),
				"detectReadOnly must not update the hash snapshot");

		// calling detectReadOnly again should still detect the same change
		Set<String> result2 = ChangeDetector.detectReadOnly(ChangeDetector.Mode.SINCE_LAST_RUN, tempDir,
				Path.of("src/main/java"), hashFile, null);
		assertEquals(Set.of("com.example.Foo"), result2, "repeated detectReadOnly should show same changes");

		// now use detect() (which updates) and verify snapshot changes
		ChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_RUN, tempDir, Path.of("src/main/java"), hashFile, null);
		assertFalse(java.util.Arrays.equals(snapshotBytes, Files.readAllBytes(hashFile)),
				"detect() should have updated the snapshot");
	}

	@Test
	void detectReadOnlyExplicitModePassesThrough() throws IOException {
		Set<String> result = ChangeDetector.detectReadOnly(ChangeDetector.Mode.EXPLICIT, tempDir,
				Path.of("src/main/java"), tempDir.resolve("hashes.lz4"), "com.example.A,com.example.B");
		assertEquals(Set.of("com.example.A", "com.example.B"), result);
	}

	@Test
	void gitModesFallBackToHashDetectionOutsideGitRepo() throws IOException {
		Path srcRoot = tempDir.resolve("src/main/java");
		Files.createDirectories(srcRoot.resolve("com/example"));
		Files.writeString(srcRoot.resolve("com/example/Foo.java"), "public class Foo {}");

		Set<String> result = ChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_COMMIT, tempDir,
				Path.of("src/main/java"), tempDir.resolve("hashes.lz4"), null);

		assertEquals(Set.of("com.example.Foo"), result);
	}

	// ═══════════════════════════════════════════════════════════════════
	// Regression: Mode.parse accepts hyphenated and uppercase formats
	// (BUG_REPORT_2 #10)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void modeParseAcceptsHyphenatedFormat() {
		assertEquals(ChangeDetector.Mode.SINCE_LAST_RUN, ChangeDetector.Mode.parse("since-last-run"));
		assertEquals(ChangeDetector.Mode.SINCE_LAST_COMMIT, ChangeDetector.Mode.parse("since-last-commit"));
		assertEquals(ChangeDetector.Mode.UNCOMMITTED, ChangeDetector.Mode.parse("uncommitted"));
		assertEquals(ChangeDetector.Mode.EXPLICIT, ChangeDetector.Mode.parse("explicit"));
	}

	@Test
	void modeParseAcceptsUppercaseFormat() {
		assertEquals(ChangeDetector.Mode.SINCE_LAST_RUN, ChangeDetector.Mode.parse("SINCE_LAST_RUN"));
		assertEquals(ChangeDetector.Mode.SINCE_LAST_COMMIT, ChangeDetector.Mode.parse("SINCE_LAST_COMMIT"));
		assertEquals(ChangeDetector.Mode.UNCOMMITTED, ChangeDetector.Mode.parse("UNCOMMITTED"));
		assertEquals(ChangeDetector.Mode.EXPLICIT, ChangeDetector.Mode.parse("EXPLICIT"));
	}

	@Test
	void modeParseDefaultsToSinceLastRun() {
		assertEquals(ChangeDetector.Mode.SINCE_LAST_RUN, ChangeDetector.Mode.parse(null));
		assertEquals(ChangeDetector.Mode.SINCE_LAST_RUN, ChangeDetector.Mode.parse(""));
		assertEquals(ChangeDetector.Mode.SINCE_LAST_RUN, ChangeDetector.Mode.parse("  "));
	}
	// ── Tier 3e ────────────────────────────────────────────────────────────

	@Test
	void sinceLastCommitWithSingleCommitRepoFallsBackGracefully() throws Exception {
		// When HEAD~1 doesn't exist (single-commit repo), the detector must not
		// crash; it should either return all tracked files or an empty set.
		git(tempDir, "init");
		git(tempDir, "config", "user.email", "test@test.com");
		git(tempDir, "config", "user.name", "Test");

		Path srcDir = tempDir.resolve("src/main/java/com/example");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("Foo.java"), "public class Foo {}");
		git(tempDir, "add", ".");
		git(tempDir, "commit", "-m", "initial");

		// Only one commit exists — HEAD~1 doesn't exist; must not throw
		assertDoesNotThrow(() -> {
			ChangeDetector.detect(ChangeDetector.Mode.SINCE_LAST_COMMIT, tempDir, Path.of("src/main/java"),
					tempDir.resolve("hashes.lz4"), null);
		}, "SINCE_LAST_COMMIT on a single-commit repo must not throw");
	}
}
