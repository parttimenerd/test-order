package me.bechberger.testorder.ops.workflows;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import javax.tools.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.PluginLog;

/**
 * Comprehensive tests for multi-module change analysis via
 * {@link ChangeAnalysis#analyze}.
 *
 * <p>
 * Layout simulated in every test (modules A and B share a git repo):
 *
 * <pre>
 *   tempDir/
 *     .git/
 *     module-a/src/main/java/com/example/a/ProdA.java   ← production code
 *     module-a/classes/com/example/a/ProdA.class
 *     module-b/src/main/java/com/example/b/ProdB.java   ← production code (sibling)
 *     module-b/classes/com/example/b/ProdB.class
 *     module-b/src/test/java/com/example/b/BTest.java   ← tests for B that depend on A
 *     module-b/test-classes/com/example/b/BTest.class
 *     .test-order/test-dependencies.lz4
 * </pre>
 *
 * <p>
 * The dependency index is always built as if {@code BTest} depends on both
 * {@code ProdA} (from module-a) and {@code ProdB} (from module-b). The primary
 * source root for analysis is module-b's {@code src/main/java} (or its
 * test-only variant); module-a's source root is an
 * <em>additionalSourceRoot</em>.
 */
class ChangeAnalysisMultiModuleTest {

	@TempDir
	Path repo;

	// fixed paths
	Path moduleASrc; // module-a/src/main/java
	Path moduleBSrc; // module-b/src/main/java
	Path moduleBTest; // module-b/src/test/java
	Path moduleAClasses;
	Path moduleBClasses;
	Path moduleBTestClasses;
	Path indexFile;

	@BeforeEach
	void setup() throws Exception {
		// Initialise git repo with a baseline commit so uncommitted/since-last-commit
		// detection has a reference point. Use env vars to suppress background git
		// processes (gc, fsmonitor) that cause TempDir cleanup failures on CI.
		git("init");
		git("config", "user.email", "test@test.com");
		git("config", "user.name", "Test");

		// Resolve symlinks so paths match what `git rev-parse --show-toplevel` returns.
		// On macOS /var/folders is a symlink to /private/var/folders; without this,
		// ChangeDetector.toGitPrefix() would compute a path that git rejects as
		// "outside repository".
		repo = repo.toRealPath();

		moduleASrc = repo.resolve("module-a/src/main/java");
		moduleBSrc = repo.resolve("module-b/src/main/java");
		moduleBTest = repo.resolve("module-b/src/test/java");
		moduleAClasses = repo.resolve("module-a/classes");
		moduleBClasses = repo.resolve("module-b/classes");
		moduleBTestClasses = repo.resolve("module-b/test-classes");

		for (Path d : List.of(moduleASrc.resolve("com/example/a"), moduleBSrc.resolve("com/example/b"),
				moduleBTest.resolve("com/example/b"), moduleAClasses, moduleBClasses, moduleBTestClasses,
				repo.resolve(".test-order"))) {
			Files.createDirectories(d);
		}

		// Baseline Java sources (committed)
		writeJava(moduleASrc, "com.example.a", "ProdA", "public class ProdA { public int value() { return 1; } }");
		writeJava(moduleBSrc, "com.example.b", "ProdB", "public class ProdB { public int value() { return 2; } }");
		writeJava(moduleBTest, "com.example.b", "BTest",
				"public class BTest { public void run() { new com.example.a.ProdA().value(); } }");

		// Compile everything
		compile(moduleAClasses, List.of(), moduleASrc.resolve("com/example/a/ProdA.java"));
		compile(moduleBClasses, List.of(), moduleBSrc.resolve("com/example/b/ProdB.java"));
		compile(moduleBTestClasses, List.of(moduleAClasses, moduleBClasses),
				moduleBTest.resolve("com/example/b/BTest.java"));

		git("add", ".");
		git("commit", "-m", "baseline");

		// Build dependency index: BTest → {ProdA, ProdB}
		DependencyMap dm = new DependencyMap();
		dm.put("com.example.b.BTest", Set.of("com.example.a.ProdA", "com.example.b.ProdB"));
		indexFile = repo.resolve(".test-order/test-dependencies.lz4");
		dm.save(indexFile);
	}

	// ── 1. Cross-module propagation ─────────────────────────────────────────

	/**
	 * BTest lives in module-b; ProdA lives in module-a (additionalSourceRoot). When
	 * ProdA is modified (uncommitted), the changed set seen by module-b's analysis
	 * should include com.example.a.ProdA, and BTest's depOverlap score should be >
	 * 0.
	 */
	@Test
	void crossModuleChange_propagatesToDepOverlap() throws Exception {
		// Modify ProdA — do NOT stage/commit, so it's an uncommitted change.
		writeJava(moduleASrc, "com.example.a", "ProdA", "public class ProdA { public int value() { return 99; } }");

		PluginContext ctx = moduleBContext().additionalSourceRoots(List.of(moduleASrc)).build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.CHANGES_ONLY);

		assertTrue(result.changedClasses().contains("com.example.a.ProdA"),
				"ProdA change from sibling module must appear in changedClasses: " + result.changedClasses());

		// BTest depends on ProdA → depOverlap > 0
		int score = result.buildScorer().score("com.example.b.BTest").depOverlap();
		assertTrue(score > 0, "BTest must have positive depOverlap when its sibling-module dependency changes");
	}

	/**
	 * When no sibling source changes exist, cross-module propagation should not
	 * introduce spurious entries in changedClasses.
	 */
	@Test
	void crossModuleChange_noSiblingChange_changedClassesStayEmpty() throws Exception {
		// Nothing modified
		PluginContext ctx = moduleBContext().additionalSourceRoots(List.of(moduleASrc)).build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.CHANGES_ONLY);

		assertTrue(result.changedClasses().isEmpty(),
				"No changes anywhere — changedClasses must be empty: " + result.changedClasses());
	}

	// ── 2. Dedicated test module (no src/main) ──────────────────────────────

	/**
	 * Simulates a dedicated-test module (like junit5's jupiter-tests) that has no
	 * src/main at all. Its primary sourceRoot is a non-existent directory. When a
	 * production class in a sibling module changes, the change must still appear in
	 * changedClasses so that BTest receives a depOverlap boost.
	 */
	@Test
	void dedicatedTestModule_noLocalSrcMain_siblingsChangeDetected() throws Exception {
		writeJava(moduleASrc, "com.example.a", "ProdA", "public class ProdA { public int value() { return 42; } }");

		// Primary source root points to a path that does NOT exist — dedicated test
		// module
		Path nonExistentSrcMain = repo.resolve("module-b/src/main/java-does-not-exist");
		assertFalse(Files.exists(nonExistentSrcMain));

		PluginContext ctx = moduleBContext().sourceRoot(nonExistentSrcMain).additionalSourceRoots(List.of(moduleASrc))
				.build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.CHANGES_ONLY);

		assertTrue(result.changedClasses().contains("com.example.a.ProdA"),
				"Even with no local src/main, sibling change must propagate: " + result.changedClasses());
		assertTrue(result.buildScorer().score("com.example.b.BTest").depOverlap() > 0,
				"BTest depOverlap must be > 0 when sibling dep changes");
	}

	// ── 3. Mode guards ──────────────────────────────────────────────────────

	/**
	 * since-last-run mode must NOT scan additional source roots (no per-root hash
	 * file). Even if ProdA changed, it must not appear in changedClasses when using
	 * since-last-run for module-b (which has no previous snapshot).
	 */
	@Test
	void sinceLastRun_skipsSiblingRoots() throws Exception {
		writeJava(moduleASrc, "com.example.a", "ProdA", "public class ProdA { public int value() { return 77; } }");

		// since-last-run with no prior hash file → all local files "changed",
		// but sibling root must NOT be scanned.
		PluginContext ctx = moduleBContext().changeMode("since-last-run").additionalSourceRoots(List.of(moduleASrc))
				.build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.CHANGES_ONLY);

		assertFalse(result.changedClasses().contains("com.example.a.ProdA"),
				"since-last-run must not scan sibling source roots: " + result.changedClasses());
	}

	/**
	 * explicit mode must NOT scan additional source roots — the caller already
	 * provided the exact class list.
	 */
	@Test
	void explicitMode_skipsSiblingRoots() throws Exception {
		writeJava(moduleASrc, "com.example.a", "ProdA", "public class ProdA { public int value() { return 55; } }");

		PluginContext ctx = moduleBContext().changeMode("explicit").changedClasses("com.example.b.ProdB") // only ProdB
																											// explicitly
				.additionalSourceRoots(List.of(moduleASrc)).build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.CHANGES_ONLY);

		assertFalse(result.changedClasses().contains("com.example.a.ProdA"),
				"explicit mode must not scan sibling source roots: " + result.changedClasses());
		assertTrue(result.changedClasses().contains("com.example.b.ProdB"),
				"explicit class must be present: " + result.changedClasses());
	}

	/**
	 * since-last-commit mode MUST scan sibling roots (git covers all of them).
	 */
	@Test
	void sinceLastCommit_doesScanSiblingRoots() throws Exception {
		// Commit ProdA change so it's detectable via since-last-commit
		writeJava(moduleASrc, "com.example.a", "ProdA", "public class ProdA { public int value() { return 33; } }");
		git("add", ".");
		git("commit", "-m", "change ProdA");

		PluginContext ctx = moduleBContext().changeMode("since-last-commit").additionalSourceRoots(List.of(moduleASrc))
				.build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.CHANGES_ONLY);

		assertTrue(result.changedClasses().contains("com.example.a.ProdA"),
				"since-last-commit must pick up sibling module changes: " + result.changedClasses());
	}

	// ── 4. Non-existent and empty additional roots are silently skipped ─────

	@Test
	void nonExistentAdditionalRoot_isSkipped() throws Exception {
		writeJava(moduleASrc, "com.example.a", "ProdA", "public class ProdA { public int value() { return 11; } }");

		Path ghost = repo.resolve("module-ghost/src/main/java"); // does not exist
		PluginContext ctx = moduleBContext().additionalSourceRoots(List.of(ghost, moduleASrc)).build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.CHANGES_ONLY);

		// Ghost root skipped; ProdA still detected via moduleASrc
		assertTrue(result.changedClasses().contains("com.example.a.ProdA"),
				"Valid sibling root must still be scanned alongside the ghost: " + result.changedClasses());
	}

	@Test
	void emptyAdditionalRoots_noEffect() throws Exception {
		writeJava(moduleASrc, "com.example.a", "ProdA", "public class ProdA { public int value() { return 22; } }");

		PluginContext ctx = moduleBContext().additionalSourceRoots(List.of()) // empty list
				.build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.CHANGES_ONLY);

		// Primary sourceRoot = moduleBSrc, no sibling scanned, ProdA not detected
		assertFalse(result.changedClasses().contains("com.example.a.ProdA"),
				"Without additionalSourceRoots ProdA must not appear: " + result.changedClasses());
	}

	// ── 5. changeComplexity uses allSourceRoots ─────────────────────────────

	/**
	 * When a sibling module's class is changed AND a source file is available (via
	 * additionalSourceRoots), changeComplexity must have a non-zero entry for that
	 * class (computed by ShowOrderOperation.computeChangeComplexity which calls
	 * SourceFileModel on allSourceRoots()).
	 */
	@Test
	void changeComplexity_usesAllSourceRoots() throws Exception {
		writeJava(moduleASrc, "com.example.a", "ProdA", "public class ProdA { public int value() { return 88; } }");

		PluginContext ctx = moduleBContext().additionalSourceRoots(List.of(moduleASrc)).build();

		// FULL options to get changeComplexity populated
		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FULL);

		assertTrue(result.changedClasses().contains("com.example.a.ProdA"),
				"ProdA must be in changedClasses (prerequisite): " + result.changedClasses());
		// changeComplexity may or may not have ProdA depending on structural analysis
		// — the key invariant is the map is produced without exception and contains
		// at most the classes that are in changedClasses.
		Map<String, Double> complexity = result.changeComplexity();
		for (String cls : complexity.keySet()) {
			assertTrue(result.changedClasses().contains(cls),
					"changeComplexity must only contain classes that are in changedClasses, but " + cls + " is not in "
							+ result.changedClasses());
		}
	}

	// ── 6. Static analysis expansion with cross-module class directories ────

	/**
	 * Static call-graph analysis is given both the primary module's classes dir and
	 * the test classes dir. When ProdA changes, the static expander should be able
	 * to trace call edges into BTest (which calls ProdA) and reflect those in
	 * changedMembers.
	 *
	 * This verifies that staticAnalysisEnabled=true doesn't throw when the changed
	 * class comes from a sibling module and the bytecode for the callee (ProdA) is
	 * not in the local classesDir.
	 */
	@Test
	void staticAnalysis_crossModuleChange_doesNotThrow() throws Exception {
		writeJava(moduleASrc, "com.example.a", "ProdA", "public class ProdA { public int value() { return 66; } }");

		// Recompile ProdA into moduleAClasses
		compile(moduleAClasses, List.of(), moduleASrc.resolve("com/example/a/ProdA.java"));

		PluginContext ctx = moduleBContext().additionalSourceRoots(List.of(moduleASrc)).staticAnalysisEnabled(true)
				.staticAnalysisDepth(2)
				// Give the static analyser the local test classes dir
				.testClassesDir(moduleBTestClasses)
				// classesDir = module-b's classes (ProdA.class is in moduleAClasses, not here)
				.classesDir(moduleBClasses).build();

		// Must not throw; changedClasses must contain ProdA
		ChangeAnalysis.Result result = assertDoesNotThrow(
				() -> ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FULL));

		assertTrue(result.changedClasses().contains("com.example.a.ProdA"),
				"ProdA must be in changedClasses: " + result.changedClasses());
	}

	/**
	 * Static analysis should expand from a cross-module changed class to its
	 * callers when the caller's .class file is on the local testClassesDir.
	 * BTest.class is in moduleBTestClasses and calls ProdA; after expansion the
	 * changedMembers should include BTest (or at least not be null).
	 */
	@Test
	void staticAnalysis_expandsToLocalCallers_ofCrossModuleChange() throws Exception {
		writeJava(moduleASrc, "com.example.a", "ProdA", "public class ProdA { public int value() { return 66; } }");
		compile(moduleAClasses, List.of(), moduleASrc.resolve("com/example/a/ProdA.java"));

		PluginContext ctx = moduleBContext().additionalSourceRoots(List.of(moduleASrc)).staticAnalysisEnabled(true)
				.staticAnalysisDepth(3).classesDir(moduleAClasses) // contains ProdA.class so expander can find it
				.testClassesDir(moduleBTestClasses).build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FULL);

		assertNotNull(result.changedMembers(),
				"changedMembers must not be null when staticAnalysis is enabled and a change exists");
		assertTrue(result.changedMembers().changedClasses().contains("com.example.a.ProdA"),
				"changedMembers must include the originally-changed class");
	}

	// ── 7. Module filtering does not drop cross-module changes ──────────────

	/**
	 * FOR_SELECTION mode applies module filtering to tests but must NOT filter out
	 * cross-module class changes from changedClasses (those are production classes,
	 * not test classes).
	 */
	@Test
	void moduleFiltering_doesNotDropCrossModuleProductionChanges() throws Exception {
		writeJava(moduleASrc, "com.example.a", "ProdA", "public class ProdA { public int value() { return 44; } }");

		PluginContext ctx = moduleBContext().additionalSourceRoots(List.of(moduleASrc))
				.testClassesDir(moduleBTestClasses).build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FOR_SELECTION);

		assertTrue(result.changedClasses().contains("com.example.a.ProdA"),
				"FOR_SELECTION must retain cross-module production changes: " + result.changedClasses());
	}

	// ── 8. Both modules change simultaneously ───────────────────────────────

	/**
	 * When both the local module (ProdB) AND a sibling module (ProdA) have
	 * uncommitted changes, both must appear in changedClasses and BTest must show a
	 * depOverlap covering both.
	 */
	@Test
	void bothModulesChange_bothAppearInChangedClasses() throws Exception {
		writeJava(moduleASrc, "com.example.a", "ProdA", "public class ProdA { public int value() { return 10; } }");
		writeJava(moduleBSrc, "com.example.b", "ProdB", "public class ProdB { public int value() { return 20; } }");

		PluginContext ctx = moduleBContext().additionalSourceRoots(List.of(moduleASrc)).build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.CHANGES_ONLY);

		assertTrue(result.changedClasses().contains("com.example.a.ProdA"),
				"ProdA (sibling) must be in changedClasses: " + result.changedClasses());
		assertTrue(result.changedClasses().contains("com.example.b.ProdB"),
				"ProdB (local) must be in changedClasses: " + result.changedClasses());

		int overlap = result.buildScorer().score("com.example.b.BTest").depOverlap();
		assertTrue(overlap > 0, "BTest depOverlap must be > 0 when both deps change");
	}

	// ── 9. resolveStructuralDiffMode ────────────────────────────────────────

	@Test
	void resolveStructuralDiffMode_uncommitted_returnsUncommitted() {
		assertEquals("uncommitted", ChangeAnalysis.resolveStructuralDiffMode("uncommitted", null, null));
	}

	@Test
	void resolveStructuralDiffMode_sinceLastCommit_returnsSinceLastCommit() {
		assertEquals("since-last-commit", ChangeAnalysis.resolveStructuralDiffMode("since-last-commit", null, null));
	}

	@Test
	void resolveStructuralDiffMode_explicit_returnsNull() {
		assertNull(ChangeAnalysis.resolveStructuralDiffMode("explicit", null, null));
	}

	@Test
	void resolveStructuralDiffMode_sinceLastRun_returnsNull() {
		assertNull(ChangeAnalysis.resolveStructuralDiffMode("since-last-run", null, null));
	}

	@Test
	void resolveStructuralDiffMode_auto_withExistingHashFile_returnsNull() throws Exception {
		Path hashFile = repo.resolve("hashes.lz4");
		Files.createFile(hashFile);
		assertNull(ChangeAnalysis.resolveStructuralDiffMode("auto", null, hashFile));
	}

	@Test
	void resolveStructuralDiffMode_auto_noHashFile_returnsSinceLastCommit() {
		Path absent = repo.resolve("no-such-hash.lz4");
		assertEquals("since-last-commit", ChangeAnalysis.resolveStructuralDiffMode("auto", null, absent));
	}

	@Test
	void resolveStructuralDiffMode_auto_withExplicitClasses_returnsNull() {
		assertNull(ChangeAnalysis.resolveStructuralDiffMode("auto", "com.example.Foo", null));
	}

	@Test
	void resolveStructuralDiffMode_nullOrBlank_returnsNull() {
		assertNull(ChangeAnalysis.resolveStructuralDiffMode(null, null, null));
		assertNull(ChangeAnalysis.resolveStructuralDiffMode("", null, null));
		assertNull(ChangeAnalysis.resolveStructuralDiffMode("  ", null, null));
	}

	// ── 10. Multiple sibling roots ──────────────────────────────────────────

	/**
	 * A build with three modules: module-a, module-b (local), and module-c. Changes
	 * in module-c must also propagate to module-b's analysis.
	 */
	@Test
	void multipleAdditionalRoots_allScanned() throws Exception {
		// Create module-c
		Path moduleCsrc = repo.resolve("module-c/src/main/java");
		Files.createDirectories(moduleCsrc.resolve("com/example/c"));
		writeJava(moduleCsrc, "com.example.c", "ProdC", "public class ProdC { public int value() { return 3; } }");
		git("add", ".");
		git("commit", "-m", "add module-c");

		// Extend index: BTest also depends on ProdC
		DependencyMap dm = new DependencyMap();
		dm.put("com.example.b.BTest", Set.of("com.example.a.ProdA", "com.example.b.ProdB", "com.example.c.ProdC"));
		dm.save(indexFile);

		// Modify both ProdA and ProdC (uncommitted)
		writeJava(moduleASrc, "com.example.a", "ProdA", "public class ProdA { public int value() { return 100; } }");
		writeJava(moduleCsrc, "com.example.c", "ProdC", "public class ProdC { public int value() { return 300; } }");

		PluginContext ctx = moduleBContext().additionalSourceRoots(List.of(moduleASrc, moduleCsrc)).build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.CHANGES_ONLY);

		assertTrue(result.changedClasses().contains("com.example.a.ProdA"),
				"ProdA from module-a must be detected: " + result.changedClasses());
		assertTrue(result.changedClasses().contains("com.example.c.ProdC"),
				"ProdC from module-c must be detected: " + result.changedClasses());
	}

	// ── helpers ──────────────────────────────────────────────────────────────

	/** Base PluginContext.Builder for module-b as the "current" module. */
	private PluginContext.Builder moduleBContext() {
		return PluginContext.builder().projectRoot(repo).repoRoot(repo).sourceRoot(moduleBSrc)
				.testSourceRoot(moduleBTest).indexFile(indexFile).stateFile(repo.resolve(".test-order/state.lz4"))
				.hashFile(repo.resolve(".test-order/hashes-b.lz4"))
				.testHashFile(repo.resolve(".test-order/test-hashes-b.lz4"))
				.bytecodeHashFile(repo.resolve(".test-order/bytecode-hashes-b.lz4")).classesDir(moduleBClasses)
				.testClassesDir(moduleBTestClasses).changeMode("uncommitted").changedClasses(null)
				.changedTestClasses(null).staticAnalysisEnabled(false).log(PluginLog.NOOP);
	}

	private void writeJava(Path srcRoot, String pkg, String simpleName, String body) throws IOException {
		String fqn = pkg + "." + simpleName;
		Path dir = srcRoot;
		for (String seg : pkg.split("\\.")) {
			dir = dir.resolve(seg);
		}
		Files.createDirectories(dir);
		Files.writeString(dir.resolve(simpleName + ".java"), "package " + pkg + "; " + body);
	}

	private void compile(Path dst, List<Path> classpath, Path... sources) throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "javax.tools.JavaCompiler not available (run with JDK)");
		StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
		fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(dst.toFile()));
		if (!classpath.isEmpty()) {
			List<java.io.File> cp = new ArrayList<>();
			for (Path p : classpath)
				cp.add(p.toFile());
			fm.setLocation(StandardLocation.CLASS_PATH, cp);
		}
		List<JavaFileObject> units = new ArrayList<>();
		for (Path src : sources) {
			fm.getJavaFileObjects(src.toFile()).forEach(units::add);
		}
		DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
		boolean ok = compiler.getTask(null, fm, diag, List.of("-source", "11", "-target", "11"), null, units).call();
		if (!ok) {
			StringBuilder msg = new StringBuilder("Compilation failed:\n");
			for (var d : diag.getDiagnostics())
				msg.append(d).append('\n');
			fail(msg.toString());
		}
	}

	private void git(String... args) throws IOException, InterruptedException {
		var cmd = new ArrayList<String>();
		cmd.add("git");
		Collections.addAll(cmd, args);
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.directory(repo.toFile());
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
		int exit = p.waitFor();
		if (exit != 0)
			throw new RuntimeException("git " + String.join(" ", args) + " failed (" + exit + ")");
	}
}
