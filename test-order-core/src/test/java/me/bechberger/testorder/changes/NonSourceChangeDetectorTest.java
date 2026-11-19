package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link NonSourceChangeDetector} using a real git CLI, mirroring the
 * setup conventions of {@link GitChangeDetectorTest}.
 */
class NonSourceChangeDetectorTest {

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

	private void initRepo() throws Exception {
		git("init");
		git("config", "user.email", "test@test.com");
		git("config", "user.name", "Test");
	}

	@Test
	void resourceChangeSinceLastCommitIsChanged() throws Exception {
		initRepo();
		Path resources = tempDir.resolve("src/main/resources");
		Files.createDirectories(resources);
		Files.writeString(resources.resolve("app.properties"), "key=value");
		git("add", ".");
		git("commit", "-m", "initial");

		Files.writeString(resources.resolve("app.properties"), "key=value2");
		git("add", ".");
		git("commit", "-m", "change resource");

		NonSourceChangeDetector.Detection detection = NonSourceChangeDetector.detect(tempDir, tempDir, true);
		assertEquals(NonSourceChangeDetector.Result.CHANGED, detection.result());
		assertTrue(detection.files().contains("src/main/resources/app.properties"),
				() -> "files: " + detection.files());
	}

	@Test
	void uncommittedResourceChangeIsChanged() throws Exception {
		initRepo();
		Path resources = tempDir.resolve("src/main/resources");
		Files.createDirectories(resources);
		Files.writeString(resources.resolve("app.properties"), "key=value");
		git("add", ".");
		git("commit", "-m", "initial");

		// unstaged modification
		Files.writeString(resources.resolve("app.properties"), "key=value2");

		NonSourceChangeDetector.Detection detection = NonSourceChangeDetector.detect(tempDir, tempDir, false);
		assertEquals(NonSourceChangeDetector.Result.CHANGED, detection.result());
		assertTrue(detection.files().contains("src/main/resources/app.properties"));
	}

	@Test
	void untrackedResourceFileIsChanged() throws Exception {
		initRepo();
		Path resources = tempDir.resolve("src/test/resources");
		Files.createDirectories(resources);
		Files.writeString(resources.resolve("existing.properties"), "a=b");
		git("add", ".");
		git("commit", "-m", "initial");

		// untracked file inside a fresh untracked subdirectory
		Files.createDirectories(resources.resolve("newdir"));
		Files.writeString(resources.resolve("newdir/extra.yaml"), "x: 1");

		NonSourceChangeDetector.Detection detection = NonSourceChangeDetector.detect(tempDir, tempDir, false);
		assertEquals(NonSourceChangeDetector.Result.CHANGED, detection.result());
		assertTrue(detection.files().contains("src/test/resources/newdir/extra.yaml"),
				() -> "files: " + detection.files());
	}

	@Test
	void onlyJavaChangeIsUnchanged() throws Exception {
		initRepo();
		Path srcDir = tempDir.resolve("src/main/java/com/example");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("Foo.java"), "public class Foo {}");
		Path resources = tempDir.resolve("src/main/resources");
		Files.createDirectories(resources);
		Files.writeString(resources.resolve("app.properties"), "key=value");
		git("add", ".");
		git("commit", "-m", "initial");

		// committed java-only change
		Files.writeString(srcDir.resolve("Foo.java"), "public class Foo { int x; }");
		git("add", ".");
		git("commit", "-m", "change Foo");

		NonSourceChangeDetector.Detection committed = NonSourceChangeDetector.detect(tempDir, tempDir, true);
		assertEquals(NonSourceChangeDetector.Result.UNCHANGED, committed.result());
		assertTrue(committed.files().isEmpty());

		// uncommitted java-only change
		Files.writeString(srcDir.resolve("Foo.java"), "public class Foo { int y; }");
		NonSourceChangeDetector.Detection uncommitted = NonSourceChangeDetector.detect(tempDir, tempDir, false);
		assertEquals(NonSourceChangeDetector.Result.UNCHANGED, uncommitted.result());
		assertTrue(uncommitted.files().isEmpty());
	}

	@Test
	void buildFileChangeIsChanged() throws Exception {
		initRepo();
		Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
		git("add", ".");
		git("commit", "-m", "initial");

		Files.writeString(tempDir.resolve("pom.xml"), "<project><build/></project>");
		git("add", ".");
		git("commit", "-m", "change pom");

		NonSourceChangeDetector.Detection detection = NonSourceChangeDetector.detect(tempDir, tempDir, true);
		assertEquals(NonSourceChangeDetector.Result.CHANGED, detection.result());
		assertTrue(detection.files().contains("pom.xml"));
	}

	@Test
	void deletedResourceIsChanged() throws Exception {
		initRepo();
		Path resources = tempDir.resolve("src/main/resources");
		Files.createDirectories(resources);
		Files.writeString(resources.resolve("gone.properties"), "key=value");
		Files.writeString(resources.resolve("kept.properties"), "key=value");
		git("add", ".");
		git("commit", "-m", "initial");

		Files.delete(resources.resolve("gone.properties"));
		git("add", ".");
		git("commit", "-m", "delete resource");

		NonSourceChangeDetector.Detection sinceLastCommit = NonSourceChangeDetector.detect(tempDir, tempDir, true);
		assertEquals(NonSourceChangeDetector.Result.CHANGED, sinceLastCommit.result());
		assertTrue(sinceLastCommit.files().contains("src/main/resources/gone.properties"));

		// uncommitted deletion is caught by the working-tree path too
		Files.delete(resources.resolve("kept.properties"));
		NonSourceChangeDetector.Detection uncommitted = NonSourceChangeDetector.detect(tempDir, tempDir, false);
		assertEquals(NonSourceChangeDetector.Result.CHANGED, uncommitted.result());
		assertTrue(uncommitted.files().contains("src/main/resources/kept.properties"));
	}

	@Test
	void arbitraryResourceFileTypeIsChanged() throws Exception {
		initRepo();
		Path resources = tempDir.resolve("src/main/resources");
		Files.createDirectories(resources);
		Files.writeString(resources.resolve("data.bin"), "raw");
		git("add", ".");
		git("commit", "-m", "initial");

		Files.writeString(resources.resolve("data.bin"), "raw-changed");
		git("add", ".");
		git("commit", "-m", "change binary resource");

		NonSourceChangeDetector.Detection detection = NonSourceChangeDetector.detect(tempDir, tempDir, true);
		assertEquals(NonSourceChangeDetector.Result.CHANGED, detection.result());
		assertTrue(detection.files().contains("src/main/resources/data.bin"));
	}

	@Test
	void noGitRepoIsNotDetermined() throws Exception {
		// tempDir without git init in a nested, never-initialized directory
		Path plain = tempDir.resolve("plain");
		Files.createDirectories(plain.resolve("src/main/resources"));
		Files.writeString(plain.resolve("src/main/resources/app.properties"), "key=value");

		NonSourceChangeDetector.Detection sinceLastCommit = NonSourceChangeDetector.detect(plain, plain, true);
		assertEquals(NonSourceChangeDetector.Result.NOT_DETERMINED, sinceLastCommit.result());
		assertTrue(sinceLastCommit.files().isEmpty());

		NonSourceChangeDetector.Detection workingTree = NonSourceChangeDetector.detect(plain, plain, false);
		assertEquals(NonSourceChangeDetector.Result.NOT_DETERMINED, workingTree.result());
	}

	@Test
	void monorepoModuleResourceChangeIsChanged() throws Exception {
		initRepo();
		Path module = tempDir.resolve("module-a");
		Path moduleResources = module.resolve("src/main/resources");
		Files.createDirectories(moduleResources);
		Files.writeString(moduleResources.resolve("app.properties"), "key=value");
		Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
		git("add", ".");
		git("commit", "-m", "initial");

		Files.writeString(moduleResources.resolve("app.properties"), "key=value2");
		git("add", ".");
		git("commit", "-m", "change module resource");

		NonSourceChangeDetector.Detection detection = NonSourceChangeDetector.detect(module, tempDir, true);
		assertEquals(NonSourceChangeDetector.Result.CHANGED, detection.result());
		assertTrue(detection.files().contains("module-a/src/main/resources/app.properties"),
				() -> "files: " + detection.files());
	}

	@Test
	void siblingModuleChangeIsUnchanged() throws Exception {
		initRepo();
		Path moduleA = tempDir.resolve("module-a");
		Path moduleB = tempDir.resolve("module-b");
		for (Path module : List.of(moduleA, moduleB)) {
			Files.createDirectories(module.resolve("src/main/resources"));
			Files.writeString(module.resolve("src/main/resources/app.properties"), "key=value");
		}
		git("add", ".");
		git("commit", "-m", "initial");

		Files.writeString(moduleB.resolve("src/main/resources/app.properties"), "key=value2");
		git("add", ".");
		git("commit", "-m", "change sibling module resource");

		NonSourceChangeDetector.Detection detection = NonSourceChangeDetector.detect(moduleA, tempDir, true);
		assertEquals(NonSourceChangeDetector.Result.UNCHANGED, detection.result());
		assertTrue(detection.files().isEmpty());
	}

	@Test
	void ancestorBuildFileChangeIsChanged() throws Exception {
		initRepo();
		Path module = tempDir.resolve("module-a");
		Files.createDirectories(module.resolve("src/main/java"));
		Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
		Files.writeString(module.resolve("pom.xml"), "<project/>");
		Files.writeString(module.resolve("src/main/java/Foo.java"), "public class Foo {}");
		git("add", ".");
		git("commit", "-m", "initial");

		// parent pom change affects the module
		Files.writeString(tempDir.resolve("pom.xml"), "<project><parent/></project>");
		git("add", ".");
		git("commit", "-m", "change parent pom");

		NonSourceChangeDetector.Detection detection = NonSourceChangeDetector.detect(module, tempDir, true);
		assertEquals(NonSourceChangeDetector.Result.CHANGED, detection.result());
		assertTrue(detection.files().contains("pom.xml"));
	}

	@Test
	void siblingBuildFileChangeIsUnchanged() throws Exception {
		initRepo();
		Path moduleA = tempDir.resolve("module-a");
		Path moduleB = tempDir.resolve("module-b");
		Files.createDirectories(moduleA.resolve("src/main/java"));
		Files.createDirectories(moduleB.resolve("src/main/java"));
		Files.writeString(moduleA.resolve("pom.xml"), "<project/>");
		Files.writeString(moduleB.resolve("pom.xml"), "<project/>");
		git("add", ".");
		git("commit", "-m", "initial");

		Files.writeString(moduleB.resolve("pom.xml"), "<project><x/></project>");
		git("add", ".");
		git("commit", "-m", "change sibling pom");

		NonSourceChangeDetector.Detection detection = NonSourceChangeDetector.detect(moduleA, tempDir, true);
		assertEquals(NonSourceChangeDetector.Result.UNCHANGED, detection.result());
		assertTrue(detection.files().isEmpty());
	}

	@Test
	void headParentMissingTreatsTrackedResourcesAsChanged() throws Exception {
		initRepo();
		Path resources = tempDir.resolve("src/main/resources");
		Files.createDirectories(resources);
		Files.writeString(resources.resolve("app.properties"), "key=value");
		git("add", ".");
		git("commit", "-m", "initial");

		// only one commit → HEAD~1 unavailable → safe over-detection fallback
		NonSourceChangeDetector.Detection detection = NonSourceChangeDetector.detect(tempDir, tempDir, true);
		assertEquals(NonSourceChangeDetector.Result.CHANGED, detection.result());
		assertTrue(detection.files().contains("src/main/resources/app.properties"));
	}

	@Test
	void filesListIsSortedAndDeduplicated() throws Exception {
		initRepo();
		Path resources = tempDir.resolve("src/main/resources");
		Files.createDirectories(resources);
		Files.writeString(resources.resolve("b.properties"), "1");
		Files.writeString(resources.resolve("a.properties"), "1");
		git("add", ".");
		git("commit", "-m", "initial");

		Files.writeString(resources.resolve("b.properties"), "2");
		Files.writeString(resources.resolve("a.properties"), "2");
		git("add", ".");
		git("commit", "-m", "change both");

		NonSourceChangeDetector.Detection detection = NonSourceChangeDetector.detect(tempDir, tempDir, true);
		assertEquals(NonSourceChangeDetector.Result.CHANGED, detection.result());
		assertEquals(List.of("src/main/resources/a.properties", "src/main/resources/b.properties"), detection.files());
	}
}
