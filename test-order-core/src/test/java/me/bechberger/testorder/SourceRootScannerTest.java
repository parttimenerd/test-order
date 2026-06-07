package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceRootScannerTest {

	@TempDir
	Path tempDir;

	@Test
	void missingRootReturnsEmpty() {
		Set<String> result = SourceRootScanner.scanFqns(tempDir.resolve("does-not-exist"));
		assertTrue(result.isEmpty());
	}

	@Test
	void nullRootReturnsEmpty() {
		assertTrue(SourceRootScanner.scanFqns(null).isEmpty());
	}

	@Test
	void emptyRootReturnsEmpty() throws IOException {
		Path root = tempDir.resolve("src/main/java");
		Files.createDirectories(root);
		assertTrue(SourceRootScanner.scanFqns(root).isEmpty());
	}

	@Test
	void scansStandardLayout() throws IOException {
		Path root = tempDir.resolve("src/main/java");
		Files.createDirectories(root.resolve("com/example/foo"));
		Files.writeString(root.resolve("com/example/Top.java"), "package com.example;");
		Files.writeString(root.resolve("com/example/foo/Nested.java"), "package com.example.foo;");

		Set<String> result = SourceRootScanner.scanFqns(root);
		assertEquals(Set.of("com.example.Top", "com.example.foo.Nested"), result);
	}

	@Test
	void skipsPackageInfoAndModuleInfo() throws IOException {
		Path root = tempDir.resolve("src");
		Files.createDirectories(root.resolve("com/example"));
		Files.writeString(root.resolve("com/example/package-info.java"), "");
		Files.writeString(root.resolve("module-info.java"), "");
		Files.writeString(root.resolve("com/example/Real.java"), "package com.example;");

		Set<String> result = SourceRootScanner.scanFqns(root);
		assertEquals(Set.of("com.example.Real"), result);
	}

	@Test
	void ignoresNonJavaFiles() throws IOException {
		Path root = tempDir.resolve("src");
		Files.createDirectories(root.resolve("com/example"));
		Files.writeString(root.resolve("com/example/A.java"), "");
		Files.writeString(root.resolve("com/example/B.kt"), "");
		Files.writeString(root.resolve("com/example/notes.txt"), "");

		Set<String> result = SourceRootScanner.scanFqns(root);
		assertEquals(Set.of("com.example.A"), result);
	}

	@Test
	void filesAtRootGetSimpleName() throws IOException {
		Path root = tempDir.resolve("src");
		Files.createDirectories(root);
		Files.writeString(root.resolve("Floating.java"), "");

		Set<String> result = SourceRootScanner.scanFqns(root);
		assertEquals(Set.of("Floating"), result);
	}

	@Test
	void resultIsDeterministicAcrossRuns() throws IOException {
		Path root = tempDir.resolve("src");
		Files.createDirectories(root.resolve("a/b/c"));
		Files.writeString(root.resolve("a/A.java"), "");
		Files.writeString(root.resolve("a/b/B.java"), "");
		Files.writeString(root.resolve("a/b/c/C.java"), "");

		Set<String> first = SourceRootScanner.scanFqns(root);
		Set<String> second = SourceRootScanner.scanFqns(root);
		assertEquals(first, second);
		assertTrue(first.contains("a.A"));
		assertTrue(first.contains("a.b.B"));
		assertTrue(first.contains("a.b.c.C"));
		assertFalse(first.contains("a"));
	}

	@Test
	void notADirectoryReturnsEmpty() throws IOException {
		Path file = tempDir.resolve("file.java");
		Files.writeString(file, "");
		// Pointing at a regular file, not a directory — should not crash.
		assertTrue(SourceRootScanner.scanFqns(file).isEmpty());
	}

	@Test
	void filesEndingInDotJavaButNotJavaFiles() throws IOException {
		// A file literally named ".java" (5 chars) — not a real class. Skip.
		Path root = tempDir.resolve("src");
		Files.createDirectories(root);
		Files.writeString(root.resolve(".java"), "");
		Files.writeString(root.resolve("Real.java"), "");

		Set<String> result = SourceRootScanner.scanFqns(root);
		assertEquals(Set.of("Real"), result);
	}

	@Test
	void scansGeneratedSourcesLayout() throws IOException {
		// target/generated-sources/annotations is a typical Maven generated-sources
		// root; the scanner must treat it identically to src/main/java.
		Path root = tempDir.resolve("target/generated-sources/annotations");
		Files.createDirectories(root.resolve("com/example/generated"));
		Files.writeString(root.resolve("com/example/generated/Gen.java"), "package com.example.generated;");

		Set<String> result = SourceRootScanner.scanFqns(root);
		assertEquals(Set.of("com.example.generated.Gen"), result);
	}

	@Test
	void deeplyNestedPackages() throws IOException {
		Path root = tempDir.resolve("src");
		Path deep = root.resolve("a/b/c/d/e/f/g/h");
		Files.createDirectories(deep);
		Files.writeString(deep.resolve("Deep.java"), "");

		Set<String> result = SourceRootScanner.scanFqns(root);
		assertEquals(Set.of("a.b.c.d.e.f.g.h.Deep"), result);
	}

	@Test
	void multipleClassesInSamePackage() throws IOException {
		Path root = tempDir.resolve("src");
		Files.createDirectories(root.resolve("p"));
		Files.writeString(root.resolve("p/A.java"), "");
		Files.writeString(root.resolve("p/B.java"), "");
		Files.writeString(root.resolve("p/C.java"), "");

		Set<String> result = SourceRootScanner.scanFqns(root);
		assertEquals(Set.of("p.A", "p.B", "p.C"), result);
	}

	@Test
	void doesNotFollowFilesUnrelatedToJava() throws IOException {
		Path root = tempDir.resolve("src");
		Files.createDirectories(root.resolve("com/example"));
		// jar, class, properties — common files in a real build dir, all ignored.
		Files.writeString(root.resolve("com/example/X.class"), "");
		Files.writeString(root.resolve("com/example/y.properties"), "");
		Files.writeString(root.resolve("com/example/lib.jar"), "");
		Files.writeString(root.resolve("com/example/Real.java"), "");

		Set<String> result = SourceRootScanner.scanFqns(root);
		assertEquals(Set.of("com.example.Real"), result);
	}

	@Test
	void scanIsIdempotentAndDoesNotMutateFilesystem() throws IOException {
		Path root = tempDir.resolve("src");
		Files.createDirectories(root.resolve("p"));
		Files.writeString(root.resolve("p/A.java"), "package p;");

		long mtimeBefore = Files.getLastModifiedTime(root.resolve("p/A.java")).toMillis();
		Set<String> first = SourceRootScanner.scanFqns(root);
		Set<String> second = SourceRootScanner.scanFqns(root);
		long mtimeAfter = Files.getLastModifiedTime(root.resolve("p/A.java")).toMillis();

		assertEquals(first, second);
		assertEquals(mtimeBefore, mtimeAfter);
	}

	@Test
	void relativePathInputIsHandled() throws IOException {
		// Maven sometimes gives compileSourceRoots as relative paths. The scanner
		// must normalize internally rather than failing on relativize().
		Path absRoot = tempDir.resolve("src");
		Files.createDirectories(absRoot.resolve("p"));
		Files.writeString(absRoot.resolve("p/Rel.java"), "");

		Path cwd = Path.of("").toAbsolutePath();
		Path rel = cwd.relativize(absRoot);
		Set<String> result = SourceRootScanner.scanFqns(rel);
		assertEquals(Set.of("p.Rel"), result);
	}

	@Test
	void hiddenFilesAreSkipped() throws IOException {
		// A file like ".foo.java" would yield a malformed FQN starting with a dot.
		// The scanner must skip it rather than registering ".foo".
		Path root = tempDir.resolve("src");
		Files.createDirectories(root.resolve("p"));
		Files.writeString(root.resolve("p/.swp.java"), "");
		Files.writeString(root.resolve("p/.bak.java"), "");
		Files.writeString(root.resolve("p/Real.java"), "");

		Set<String> result = SourceRootScanner.scanFqns(root);
		assertEquals(Set.of("p.Real"), result);
	}

	@Test
	void hiddenDirectoriesAreSkipped() throws IOException {
		// .git, .idea, .archived, etc. — common hidden directories that may live
		// under a source root if the user pointed it at a project root by mistake.
		// The scanner must NOT descend into them.
		Path root = tempDir.resolve("src");
		Files.createDirectories(root.resolve(".git/refs/heads"));
		Files.createDirectories(root.resolve(".idea/workspace"));
		Files.createDirectories(root.resolve(".archived/p"));
		Files.createDirectories(root.resolve("p"));
		// Plant some .java files in hidden dirs that should be ignored.
		Files.writeString(root.resolve(".git/refs/heads/Foo.java"), "");
		Files.writeString(root.resolve(".idea/workspace/Bar.java"), "");
		Files.writeString(root.resolve(".archived/p/Old.java"), "");
		Files.writeString(root.resolve("p/Real.java"), "");

		Set<String> result = SourceRootScanner.scanFqns(root);
		assertEquals(Set.of("p.Real"), result);
	}

	@Test
	void rootItselfMayBeHidden() throws IOException {
		// If the caller legitimately points at a hidden directory as the root,
		// honor it — only DESCENDANT hidden dirs are skipped.
		Path root = tempDir.resolve(".hidden-root");
		Files.createDirectories(root.resolve("p"));
		Files.writeString(root.resolve("p/Foo.java"), "");

		Set<String> result = SourceRootScanner.scanFqns(root);
		assertEquals(Set.of("p.Foo"), result);
	}

	@Test
	void brokenSymlinkDoesNotAbortScan() throws IOException {
		// A broken symlink would make Files.walk's stream throw on the bad
		// entry — the scanner's visitFileFailed handler must keep going so
		// good files alongside the bad symlink are still registered.
		Path root = tempDir.resolve("src");
		Files.createDirectories(root.resolve("p"));
		Files.writeString(root.resolve("p/Good.java"), "");
		try {
			Files.createSymbolicLink(root.resolve("p/Broken.java"), tempDir.resolve("nope/missing.java"));
		} catch (UnsupportedOperationException | IOException e) {
			// Some platforms (Windows without admin, certain CI envs) reject
			// symlink creation. The other tests still cover the happy paths.
			return;
		}

		Set<String> result = SourceRootScanner.scanFqns(root);
		assertTrue(result.contains("p.Good"), "Good.java should still be registered: " + result);
	}
}
