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
import me.bechberger.testorder.ops.ChangeDetectionOps;
import me.bechberger.testorder.ops.HashSnapshotOperation;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.PluginLog;

/**
 * Tests for the widened select-path analysis
 * ({@link ChangeAnalysis.Options#FOR_SELECTION_DETAILED}).
 *
 * <p>
 * The selection pipeline used to run
 * {@link ChangeAnalysis.Options#FOR_SELECTION} — a reduced analysis (no
 * structural analysis, no method-level detection). The coverage-closure
 * selection needs a member-level change universe, so the select path now runs
 * {@code FOR_SELECTION_DETAILED}: structural analysis + method detection
 * enabled, still module-filtered and <b>read-only</b> (the bytecode baseline
 * must never advance before tests run).
 *
 * <p>
 * Layout simulated in every test (single module, its own git repo):
 *
 * <pre>
 *   tempDir/
 *     .git/
 *     src/main/java/com/example/Prod.java      ← production code
 *     src/test/java/com/example/ProdTest.java  ← test calling Prod
 *     classes/com/example/Prod.class
 *     test-classes/com/example/ProdTest.class
 *     .test-order/test-dependencies.lz4
 * </pre>
 */
class SelectionPathAnalysisOptionsTest {

	@TempDir
	Path repo;

	Path srcMain;
	Path srcTest;
	Path classesDir;
	Path testClassesDir;
	Path indexFile;
	Path bytecodeHashFile;
	Path methodHashFile;

	@BeforeEach
	void setup() throws Exception {
		git("init");
		git("config", "user.email", "test@test.com");
		git("config", "user.name", "Test");

		// Resolve symlinks so paths match what `git rev-parse --show-toplevel`
		// returns (macOS /var/folders → /private/var/folders).
		repo = repo.toRealPath();

		srcMain = repo.resolve("src/main/java");
		srcTest = repo.resolve("src/test/java");
		classesDir = repo.resolve("classes");
		testClassesDir = repo.resolve("test-classes");

		for (Path d : List.of(srcMain.resolve("com/example"), srcTest.resolve("com/example"), classesDir,
				testClassesDir, repo.resolve(".test-order"))) {
			Files.createDirectories(d);
		}

		writeJava(srcMain, "com.example", "Prod", "public class Prod { public int value() { return 1; } }");
		writeJava(srcTest, "com.example", "ProdTest",
				"public class ProdTest { public void run() { new com.example.Prod().value(); } }");

		compile(classesDir, List.of(), srcMain.resolve("com/example/Prod.java"));
		compile(testClassesDir, List.of(classesDir), srcTest.resolve("com/example/ProdTest.java"));

		git("add", ".");
		git("commit", "-m", "baseline");

		DependencyMap dm = new DependencyMap();
		dm.put("com.example.ProdTest", Set.of("com.example.Prod"));
		indexFile = repo.resolve(".test-order/test-dependencies.lz4");
		dm.save(indexFile);

		bytecodeHashFile = repo.resolve(".test-order/bytecode-hashes.lz4");
		methodHashFile = repo.resolve(".test-order/method-hashes.lz4");
	}

	// ── 1. Widened select-path analysis produces a member universe ──────────

	/**
	 * A method-body change must surface as a changed member key in
	 * {@code Result.changedMembers()} when the select path runs the detailed
	 * options — this is the universe the cover-core selection (T2) consumes.
	 */
	@Test
	void selectionDetailed_methodBodyChange_yieldsChangedMemberKeys() throws Exception {
		writeJava(srcMain, "com.example", "Prod", "public class Prod { public int value() { return 99; } }");
		compile(classesDir, List.of(), srcMain.resolve("com/example/Prod.java"));

		PluginContext ctx = baseContext().build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FOR_SELECTION_DETAILED);

		assertTrue(result.changedClasses().contains("com.example.Prod"),
				"changed class must be present: " + result.changedClasses());
		assertNotNull(result.changedMembers(),
				"selection-detailed analysis must produce changedMembers for a source change");
		assertTrue(result.changedMembers().changedClasses().contains("com.example.Prod"),
				"changedMembers must carry the changed class: " + result.changedMembers().changedClasses());
		assertTrue(result.changedMembers().changedMemberKeys().contains("com.example.Prod#value"),
				"changed member key for the modified method must be present: "
						+ result.changedMembers().changedMemberKeys());
		assertFalse(result.staticAnalysisDegraded(), "small project must not trip the degradation gate");
		// includeAllTests=false — the select path collects tests itself.
		assertTrue(result.allTests().isEmpty(), "selection options must not collect allTests");
		// The pre-static-analysis snapshot must be carried alongside the expanded set.
		assertNotNull(result.preSaChangedMembers(), "pre-SA member snapshot must be exposed");
	}

	// ── 2. since-last-run mode: structural analysis unavailable → no crash ──

	/**
	 * In {@code since-last-run} mode {@code resolveStructuralDiffMode} returns
	 * {@code null}, so structural analysis cannot run. The analysis must degrade
	 * gracefully (class-level universe + bytecode method keys), never crash, and
	 * never write a structural-analysis artifact.
	 */
	@Test
	void selectionDetailed_sinceLastRun_degradesWithoutCrash() throws Exception {
		// Seed the source/test hash snapshots (ChangeAnalysis never writes them —
		// detection always runs read-only) so that since-last-run detection sees
		// only the newly modified file.
		Path hashFile = repo.resolve(".test-order/hashes.lz4");
		Path testHashFile = repo.resolve(".test-order/test-hashes.lz4");
		HashSnapshotOperation.snapshot(srcMain, hashFile, srcTest, testHashFile, null, null);

		// Seed the bytecode snapshot (CHANGES_ONLY is not read-only).
		PluginContext seeding = baseContext().changeMode("since-last-run").staticAnalysisEnabled(false).build();
		ChangeAnalysis.analyze(seeding, ChangeAnalysis.Options.CHANGES_ONLY);
		assertTrue(Files.exists(bytecodeHashFile), "seeding run must write the bytecode snapshot");

		writeJava(srcMain, "com.example", "Prod", "public class Prod { public int value() { return 42; } }");

		PluginContext ctx = baseContext().changeMode("since-last-run").staticAnalysisEnabled(false).build();

		// resolveStructuralDiffMode("since-last-run", ...) is null — must not throw.
		ChangeAnalysis.Result result = assertDoesNotThrow(
				() -> ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FOR_SELECTION_DETAILED));

		assertTrue(result.changedClasses().contains("com.example.Prod"),
				"source change must still be detected at class level: " + result.changedClasses());
		assertNull(result.changedMembers(),
				"without structural analysis and without bytecode keys, changedMembers must degrade to null "
						+ "(consumers fall back to the class-level universe)");
		assertFalse(result.staticAnalysisDegraded(), "no static analysis ran — no degradation");
	}

	// ── 3. readOnly: the bytecode baseline must not advance ─────────────────

	/**
	 * The select path is a pure pre-test filter: running the detailed selection
	 * analysis must NOT write the bytecode-hashes snapshot. Otherwise a broken
	 * build would look clean on the next invocation.
	 */
	@Test
	void selectionDetailed_readOnly_bytecodeBaselineNotAdvanced() throws Exception {
		compile(classesDir, List.of(), srcMain.resolve("com/example/Prod.java"));

		// Seed the bytecode snapshot with a non-read-only options set.
		PluginContext seeding = baseContext().changeMode("explicit").changedClasses("").changedTestClasses("")
				.staticAnalysisEnabled(false).build();
		ChangeAnalysis.analyze(seeding, ChangeAnalysis.Options.CHANGES_ONLY);
		assertTrue(Files.exists(bytecodeHashFile), "seeding run must write the bytecode snapshot");
		byte[] baseline = Files.readAllBytes(bytecodeHashFile);

		// Bytecode-only change. The source file also changes on disk, but
		// changeMode=explicit with an EMPTY changedClasses list keeps source-side
		// detection empty — the signal must come from the bytecode path.
		writeJava(srcMain, "com.example", "Prod", "public class Prod { public int value() { return 999; } }");
		compile(classesDir, List.of(), srcMain.resolve("com/example/Prod.java"));

		PluginContext ctx = baseContext().changeMode("explicit").changedClasses("").changedTestClasses("")
				.staticAnalysisEnabled(false).build();

		ChangeAnalysis.Result first = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FOR_SELECTION_DETAILED);
		assertTrue(first.changedClasses().contains("com.example.Prod"),
				"bytecode-only change must be detected: " + first.changedClasses());
		assertTrue(first.changedMembers().changedMemberKeys().contains("com.example.Prod#value"),
				"bytecode-derived method key must flow into the select-path universe: "
						+ first.changedMembers().changedMemberKeys());

		byte[] afterFirst = Files.readAllBytes(bytecodeHashFile);
		assertArrayEquals(baseline, afterFirst, "read-only selection analysis must not advance the bytecode baseline");

		// A second selection run must still see the change — the baseline was not
		// consumed by the first (read-only) run.
		ChangeAnalysis.Result second = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FOR_SELECTION_DETAILED);
		assertTrue(second.changedClasses().contains("com.example.Prod"),
				"baseline was not advanced, so the change must still be detected on the next run: "
						+ second.changedClasses());
		byte[] afterSecond = Files.readAllBytes(bytecodeHashFile);
		assertArrayEquals(baseline, afterSecond, "baseline must remain untouched after repeated read-only runs");
	}

	// ── 4. FOR_SELECTION stays reduced for its other callers ────────────────

	/**
	 * The pre-existing reduced variant must keep its behavior for the remaining
	 * callers (tiered mojos, Gradle plugin until T3): no structural analysis, no
	 * method detection.
	 */
	@Test
	void forSelection_staysReduced_forSameChange() throws Exception {
		// Seed the bytecode snapshot so the bytecode path yields no changed method
		// keys (on a cold first run every current method is treated as changed —
		// pre-existing FOR_SELECTION behavior, independent of this task).
		PluginContext seeding = baseContext().staticAnalysisEnabled(false).build();
		ChangeAnalysis.analyze(seeding, ChangeAnalysis.Options.CHANGES_ONLY);

		writeJava(srcMain, "com.example", "Prod", "public class Prod { public int value() { return 7; } }");

		PluginContext ctx = baseContext().staticAnalysisEnabled(false).build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FOR_SELECTION);

		assertTrue(result.changedClasses().contains("com.example.Prod"),
				"class-level detection must still work: " + result.changedClasses());
		assertNull(result.changedMembers(), "FOR_SELECTION must not produce member data (unchanged reduced behavior)");
		assertTrue(result.changedMethods().isEmpty(), "FOR_SELECTION must not run method-level detection");
	}

	// ── 5. includeMethodChanges: changed test methods detected ──────────────

	/**
	 * The detailed variant enables method-level test-change detection: after
	 * seeding the method-hash snapshot, a modified test method body must appear in
	 * {@code Result.changedMethods()}.
	 */
	@Test
	void selectionDetailed_changedTestMethodDetected() throws Exception {
		PluginContext seeding = baseContext().methodOrderingEnabled(true).staticAnalysisEnabled(false).build();
		ChangeDetectionOps.snapshotMethodHashes(seeding.testSourceRoot(), methodHashFile, PluginLog.NOOP);
		assertTrue(Files.exists(methodHashFile), "seeded method-hash snapshot must exist");

		writeJava(srcTest, "com.example", "ProdTest",
				"public class ProdTest { public void run() { int v = new com.example.Prod().value(); v++; } }");

		PluginContext ctx = baseContext().methodOrderingEnabled(true).staticAnalysisEnabled(false).build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FOR_SELECTION_DETAILED);

		assertFalse(result.changedMethods().isEmpty(),
				"detailed selection analysis must detect changed test methods: " + result.changedMethods());
	}

	// ── 6. Fresh-learn first run: explicit list stays authoritative ─────────

	/**
	 * Regression (T3b repro): a fresh learn does not write the bytecode-hashes
	 * baseline, so the first selection run hit the "first run — no prior snapshot"
	 * branch that treats EVERY current method as changed. That flooded
	 * {@code changedMembers.changedClasses()} with all production classes, which
	 * made every test a sole coverer in the coverage-closure cover core and
	 * defeated topN capping. When the user supplies an explicit changed-class list
	 * (BUG-165: the list is authoritative), the first run must NOT flood the member
	 * universe.
	 */
	@Test
	void selectionDetailed_explicitFirstRun_doesNotFloodMemberUniverse() throws Exception {
		// Unrelated second production class — pre-fix its methods were all marked
		// changed on the first run.
		writeJava(srcMain, "com.example", "Other", "public class Other { public int id() { return 2; } }");
		compile(classesDir, List.of(), srcMain.resolve("com/example/Other.java"));
		git("add", ".");
		git("commit", "-m", "second class");

		// Fresh-learn state: no bytecode baseline yet.
		assertFalse(Files.exists(bytecodeHashFile), "scenario check: no bytecode baseline after fresh learn");

		PluginContext ctx = baseContext().changeMode("explicit").changedClasses("com.example.Prod")
				.staticAnalysisEnabled(false).build();

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FOR_SELECTION_DETAILED);

		assertEquals(Set.of("com.example.Prod"), result.changedClasses(),
				"the explicit changed-class list must stay authoritative (BUG-165)");
		assertNull(result.changedMembers(),
				"first run with an authoritative explicit list must not treat every method as changed "
						+ "(flood would make every test a sole coverer and defeat topN), got: "
						+ (result.changedMembers() == null ? "null" : result.changedMembers().changedClasses()));
	}

	// ── helpers ──────────────────────────────────────────────────────────────

	private PluginContext.Builder baseContext() {
		return PluginContext.builder().projectRoot(repo).repoRoot(repo).sourceRoot(srcMain).testSourceRoot(srcTest)
				.indexFile(indexFile).stateFile(repo.resolve(".test-order/state.lz4"))
				.hashFile(repo.resolve(".test-order/hashes.lz4"))
				.testHashFile(repo.resolve(".test-order/test-hashes.lz4")).bytecodeHashFile(bytecodeHashFile)
				.methodHashFile(methodHashFile).classesDir(classesDir).testClassesDir(testClassesDir)
				.changeMode("uncommitted").changedClasses(null).changedTestClasses(null).staticAnalysisEnabled(true)
				.staticAnalysisDepth(2).log(PluginLog.NOOP);
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
			for (Path p : classpath) {
				cp.add(p.toFile());
			}
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
			for (var d : diag.getDiagnostics()) {
				msg.append(d).append('\n');
			}
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
		if (exit != 0) {
			throw new RuntimeException("git " + String.join(" ", args) + " failed (" + exit + ")");
		}
	}
}
