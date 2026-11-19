package me.bechberger.testorder.ops.workflows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.PluginLog;

/**
 * Tests for the fail-safe select-all wiring in {@link AffectedWorkflow}:
 * non-source changes (resources/build files) make the module unselectable and
 * run ALL of its tests; degraded static analysis selects all; the normal path
 * keeps the capped selection and threads the member universe + enforce flag.
 */
class AffectedWorkflowSelectAllTest {

	@TempDir
	Path repo;

	Path srcMain;
	Path indexFile;
	Path selectedFile;
	Path remainingFile;

	@BeforeEach
	void setup() throws Exception {
		git("init");
		git("config", "user.email", "test@test.com");
		git("config", "user.name", "Test");

		// Resolve symlinks so paths match what `git rev-parse --show-toplevel`
		// returns (macOS /var/folders → /private/var/folders).
		repo = repo.toRealPath();

		srcMain = repo.resolve("src/main/java");
		Files.createDirectories(srcMain.resolve("com/example"));
		Files.createDirectories(repo.resolve(".test-order"));

		writeJava("Prod", "public class Prod { public int value() { return 1; } }");
		writeJava("Other", "public class Other { public int value() { return 2; } }");

		git("add", ".");
		git("commit", "-m", "baseline");

		DependencyMap dm = new DependencyMap();
		dm.put("com.example.ProdTest", Set.of("com.example.Prod"));
		dm.put("com.example.OtherTest", Set.of("com.example.Other"));
		indexFile = repo.resolve(".test-order/test-dependencies.lz4");
		dm.save(indexFile);

		selectedFile = repo.resolve("target/test-order-selected.txt");
		remainingFile = repo.resolve("target/test-order-remaining.txt");
	}

	private PluginContext.Builder baseContext() {
		return PluginContext.builder().projectRoot(repo).repoRoot(repo).sourceRoot(srcMain).indexFile(indexFile)
				.stateFile(repo.resolve(".test-order/state.lz4")).hashFile(repo.resolve(".test-order/hashes.lz4"))
				.changeMode("uncommitted").changedClasses(null).changedTestClasses(null).staticAnalysisEnabled(false)
				.bytecodeChangeDetectionEnabled(false).bytecodeAugmentDependencyMapEnabled(false).topN(1).randomM(0)
				.coverageEnforce(false).selectedFile(selectedFile).remainingFile(remainingFile).log(PluginLog.NOOP);
	}

	private void writeJava(String simpleName, String body) throws IOException {
		Files.writeString(srcMain.resolve("com/example/" + simpleName + ".java"), "package com.example; " + body);
	}

	// ── 1. Non-source change → module select-all ────────────────────────────

	/**
	 * An untracked file under {@code src/main/resources} is invisible to
	 * source-based change detection but may change test behavior — the module must
	 * become unselectable: ALL of its tests run, with an explicit WARN naming the
	 * matched files.
	 */
	@Test
	void nonSourceResourceChange_selectsAllModuleTests() throws Exception {
		Files.createDirectories(repo.resolve("src/main/resources"));
		Files.writeString(repo.resolve("src/main/resources/config.properties"), "key=changed\n");

		PluginContext ctx = baseContext().build();
		AffectedWorkflow.SelectWithAnalysis combined = AffectedWorkflow.selectWithAnalysis(ctx);

		var selection = combined.result().selection();
		assertEquals(Set.of("com.example.ProdTest", "com.example.OtherTest"), Set.copyOf(selection.selected()),
				"non-source change must run ALL module tests, selected=" + selection.selected());
		assertTrue(selection.remaining().isEmpty(), "nothing may be deferred on select-all");
		assertTrue(combined.result().allSelected(), "select-all must report allSelected=true");

		List<String> lines = combined.result().summary().format().lines().toList();
		assertTrue(lines.stream().anyMatch(l -> l.contains("Selected 2 / 2")),
				"summary must show the full selection, summary=" + lines);
	}

	/**
	 * The WARN carrying the matched files must be visible on the plugin log (the
	 * summary marker route). NOOP log swallows it, so use a capturing log.
	 */
	@Test
	void nonSourceChange_logsExplicitWarnWithMatchedFiles() throws Exception {
		Files.createDirectories(repo.resolve("src/main/resources"));
		Files.writeString(repo.resolve("src/main/resources/config.properties"), "key=changed\n");

		CapturingLog log = new CapturingLog();
		PluginContext ctx = baseContext().log(log).build();
		AffectedWorkflow.selectWithAnalysis(ctx);

		assertTrue(log.warns.stream().anyMatch(w -> w.contains("Non-source change detected")),
				"select-all trigger must be logged explicitly, warns=" + log.warns);
		assertTrue(log.warns.stream().anyMatch(w -> w.contains("src/main/resources/config.properties")),
				"matched files must be named in the WARN, warns=" + log.warns);
		assertTrue(log.warns.stream().anyMatch(w -> w.contains("select-all")),
				"the select-all decision must be logged, warns=" + log.warns);
		// selected list persisted, stale remaining file removed (run-remaining parity)
		assertTrue(Files.exists(selectedFile), "select-all must persist the selected list");
		List<String> written = Files.readAllLines(selectedFile);
		assertEquals(2, written.size(), "selected.txt must contain all module tests: " + written);
	}

	/**
	 * A build-file change (pom.xml) is a non-source input too.
	 */
	@Test
	void pomXmlChange_selectsAllModuleTests() throws Exception {
		Files.writeString(repo.resolve("pom.xml"), "<project/>");

		PluginContext ctx = baseContext().build();
		AffectedWorkflow.SelectWithAnalysis combined = AffectedWorkflow.selectWithAnalysis(ctx);

		assertTrue(combined.result().allSelected(),
				"pom.xml change must select all module tests, selected=" + combined.result().selection().selected());
	}

	/**
	 * NOT_DETERMINED (git inspection impossible — here: repoRoot points outside any
	 * git work tree) is treated as unchanged: the capped normal path runs.
	 */
	@Test
	void notDeterminedGit_treatedAsUnchanged_normalSelection() throws Exception {
		Files.createDirectories(repo.resolve("src/main/resources"));
		Files.writeString(repo.resolve("src/main/resources/config.properties"), "key=changed\n");

		// A directory that is not a git work tree → the detector's
		// `rev-parse --is-inside-work-tree` check fails → NOT_DETERMINED.
		Path nonGit = Files.createTempDirectory("non-git-root");

		CapturingLog log = new CapturingLog();
		PluginContext ctx = baseContext().repoRoot(nonGit).log(log).build();
		AffectedWorkflow.SelectWithAnalysis combined = AffectedWorkflow.selectWithAnalysis(ctx);

		assertFalse(log.warns.stream().anyMatch(w -> w.contains("Non-source change detected")),
				"NOT_DETERMINED must be treated as unchanged, warns=" + log.warns);
		assertFalse(combined.result().allSelected(),
				"normal capped selection must run, selected=" + combined.result().selection().selected());
		// topN=1 with no change signal → 1 scored test selected, 1 deferred
		assertEquals(1, combined.result().selection().selected().size());
		assertEquals(1, combined.result().selection().remaining().size());
	}

	// ── 2. Only-source change keeps the normal capped selection ─────────────

	/**
	 * A .java-only change must NOT trigger select-all — the selection stays capped
	 * (topN=1 → 1 selected, 1 deferred).
	 */
	@Test
	void javaOnlyChange_normalCappedSelection() throws Exception {
		writeJava("Prod", "public class Prod { public int value() { return 99; } }");

		CapturingLog log = new CapturingLog();
		PluginContext ctx = baseContext().log(log).build();
		AffectedWorkflow.SelectWithAnalysis combined = AffectedWorkflow.selectWithAnalysis(ctx);

		assertFalse(log.warns.stream().anyMatch(w -> w.contains("Non-source change detected")),
				"java-only changes must not trigger select-all, warns=" + log.warns);
		assertFalse(combined.result().allSelected(), "capped selection must run");
		assertEquals(1, combined.result().selection().selected().size(),
				"topN=1 must cap the selection, selected=" + combined.result().selection().selected());
		assertEquals(1, combined.result().selection().remaining().size());
		// the member universe flows into the selector: the changed element is covered
		assertNotNull(combined.result().coverageStatus());
		assertEquals(1, combined.result().coverageStatus().totalElements());
		assertTrue(combined.result().coverageStatus().allCovered(),
				"the changed element must be exercised by the selected subset");
	}

	// ── 3. Degraded static analysis → fail-safe select-all ──────────────────

	/**
	 * When the analysis reports static-analysis degradation the universe is
	 * unreliable — the module must select ALL tests (fail-safe), with an explicit
	 * log and visible coverage counts in the summary. Uses the package-private
	 * precomputed-analysis seam (orchestrating the real degradation gate end-to-end
	 * would require a hand-crafted class-file explosion).
	 */
	@Test
	void staticAnalysisDegraded_selectsAllModuleTests() throws Exception {
		DependencyMap depMap = DependencyMap.load(indexFile);
		TestOrderState state = new TestOrderState();
		ChangeAnalysis.Result degraded = new ChangeAnalysis.Result(depMap, state, Set.of("com.example.Prod"), Set.of(),
				Set.of(), state.weights(), null, null, Map.of(), Set.of(), true, null);

		CapturingLog log = new CapturingLog();
		PluginContext ctx = baseContext().log(log).build();
		AffectedWorkflow.SelectWithAnalysis combined = AffectedWorkflow.selectWithAnalysis(ctx, degraded);

		var selection = combined.result().selection();
		assertEquals(Set.of("com.example.ProdTest", "com.example.OtherTest"), Set.copyOf(selection.selected()),
				"degraded static analysis must run ALL module tests, selected=" + selection.selected());
		assertTrue(combined.result().allSelected());
		assertTrue(log.warns.stream().anyMatch(w -> w.contains("fail-safe select-all")),
				"select-all must be logged explicitly, warns=" + log.warns);
		// summary visibility: the changed element is covered by the select-all set
		assertEquals(1, combined.result().summary().elementsTotal());
		assertEquals(1, combined.result().summary().elementsCovered());
	}

	// ── helpers ──────────────────────────────────────────────────────────────

	/**
	 * Pins the degraded seam: the select-all test above fabricates a
	 * {@link ChangeAnalysis.Result} passing {@code true} as the 11th positional
	 * argument. This pin asserts that position 10 (0-based) really is the
	 * {@code staticAnalysisDegraded} record component — the field the degradation
	 * gate in {@code ChangeAnalysis.analyze} writes — so a re-ordering or rename of
	 * the record cannot silently detach the seam from the real trigger.
	 */
	@Test
	void degradedSeamFlag_targetsTheAnalyzerWrittenStaticAnalysisDegradedComponent() {
		java.lang.reflect.RecordComponent[] components = ChangeAnalysis.Result.class.getRecordComponents();
		int degradedIndex = -1;
		for (int i = 0; i < components.length; i++) {
			if (components[i].getName().equals("staticAnalysisDegraded")) {
				degradedIndex = i;
				break;
			}
		}
		assertTrue(degradedIndex >= 0,
				"ChangeAnalysis.Result must expose a staticAnalysisDegraded component (the degradation gate writes it)");
		assertEquals(10, degradedIndex,
				"the hand-fabricated Result in staticAnalysisDegraded_selectsAllModuleTests passes the flag as the "
						+ "11th positional argument — if the record component order changed, update the seam tests");
	}

	private static final class CapturingLog implements PluginLog {
		final List<String> infos = new ArrayList<>();
		final List<String> warns = new ArrayList<>();
		final List<String> debugs = new ArrayList<>();

		@Override
		public void info(String message) {
			infos.add(message);
		}

		@Override
		public void warn(String message) {
			warns.add(message);
		}

		@Override
		public void debug(String message) {
			debugs.add(message);
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
