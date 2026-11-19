package me.bechberger.testorder.ops.workflows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * Tests the auto-mode coverage-closure gating: with {@code runRemaining=false}
 * the coverage closure applies fully (coverage-enforce can fail the run); with
 * {@code runRemaining=true} (default, full suite runs) the closure check only
 * reports covered X/Y and never fails.
 */
class AutoWorkflowCoverageClosureTest {

	@TempDir
	Path tempDir;

	Path indexFile;

	/** Log collector. */
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

	@BeforeEach
	void setup() throws IOException {
		Files.createDirectories(tempDir.resolve("src/main/java"));
		Files.createDirectories(tempDir.resolve("src/test/java"));
		DependencyMap seed = new DependencyMap();
		seed.put("com.example.OldTest", Set.of("com.example.Old"));
		seed.put("com.example.PlusTest", Set.of("com.example.Plus"));
		indexFile = tempDir.resolve(".test-order/test-dependencies.lz4");
		Files.createDirectories(indexFile.getParent());
		seed.save(indexFile);
	}

	/**
	 * Explicit changed class no index test exercises (stale-index scenario) — the
	 * universe contains {@code com.example.Missing}, uncovered by any test.
	 */
	private PluginContext.Builder baseContext() {
		return PluginContext.builder().projectRoot(tempDir).sourceRoot(tempDir.resolve("src/main/java"))
				.testSourceRoot(tempDir.resolve("src/test/java")).indexFile(indexFile)
				.stateFile(tempDir.resolve(".test-order/state.lz4")).hashFile(tempDir.resolve(".test-order/hashes.lz4"))
				.changeMode("explicit").changedClasses("com.example.Missing").changedTestClasses("")
				.staticAnalysisEnabled(false).bytecodeChangeDetectionEnabled(false)
				.bytecodeAugmentDependencyMapEnabled(false).topN(1).randomM(0).coverageEnforce(true)
				.selectedFile(tempDir.resolve("selected.txt")).remainingFile(tempDir.resolve("remaining.txt"))
				.log(PluginLog.NOOP);
	}

	@Test
	void runRemainingFalse_enforceFailsTheRun() {
		PluginContext ctx = baseContext().build();

		// runRemaining=false: the deferred tests do NOT run, so an uncovered changed
		// element must fail the run when enforcement is on.
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> new AutoWorkflow(ctx, "order", null, null, false).execute());
		assertTrue(ex.getMessage().contains("Coverage closure failed"), ex.getMessage());
		assertTrue(ex.getMessage().contains("com.example.Missing"), ex.getMessage());
	}

	@Test
	void runRemainingTrue_reportsButNeverFails() throws IOException {
		CapturingLog log = new CapturingLog();
		PluginContext ctx = baseContext().log(log).build();

		// runRemaining=true: the full suite runs anyway — the closure check must
		// only REPORT covered X/Y, never fail.
		AutoWorkflow.Result result = new AutoWorkflow(ctx, "order", null, null, true).execute();

		AutoWorkflow.Result.OrderSelect os = assertInstanceOf(AutoWorkflow.Result.OrderSelect.class, result);
		assertEquals(1, os.selectResult().summary().elementsTotal(), "universe must be reported");
		assertEquals(0, os.selectResult().summary().elementsCovered());
		assertTrue(
				log.warns.stream().anyMatch(w -> w.contains("Coverage closure") && w.contains("com.example.Missing")),
				"uncovered element must be reported, warns=" + log.warns);
	}

	@Test
	void defaultConstructor_behavesLikeRunRemainingTrue_neverFails() throws IOException {
		PluginContext ctx = baseContext().build();

		// Historical 4-arg constructor: auto defaults to full-suite semantics —
		// the closure check must not fail the run.
		AutoWorkflow.Result result = new AutoWorkflow(ctx, "order", null, null).execute();
		assertInstanceOf(AutoWorkflow.Result.OrderSelect.class, result);
	}

	@Test
	void enforceOffWithRunRemainingFalse_warnsOnly() throws IOException {
		CapturingLog log = new CapturingLog();
		PluginContext ctx = baseContext().coverageEnforce(false).log(log).build();

		AutoWorkflow.Result result = new AutoWorkflow(ctx, "order", null, null, false).execute();
		assertInstanceOf(AutoWorkflow.Result.OrderSelect.class, result);
		assertTrue(log.warns.stream().anyMatch(w -> w.contains("Coverage closure")),
				"without enforcement the uncovered element is only WARNed, warns=" + log.warns);
	}

	/**
	 * Degraded static analysis → fail-safe select-all must behave like
	 * {@code AffectedWorkflow.fullSelection}: persist the selected list, DELETE a
	 * stale remaining file (so {@code run-remaining} does not re-execute old
	 * deferrals), and attach the coverage status over the full set so the CI
	 * summary keeps the "Changed elements covered" row.
	 */
	@Test
	void degradedSelectAll_persistsSelectedListDeletesStaleRemainingAndReportsCoverage() throws IOException {
		// Stale artifacts from a previous affected run
		Files.writeString(tempDir.resolve("selected.txt"), "com.example.StaleTest\n");
		Files.writeString(tempDir.resolve("remaining.txt"), "com.example.StaleTest\n");

		CapturingLog log = new CapturingLog();
		PluginContext ctx = baseContext().log(log).build();
		ChangeAnalysis.Result degraded = new ChangeAnalysis.Result(DependencyMap.load(indexFile), new TestOrderState(),
				Set.of("com.example.Missing"), Set.of(), Set.of(), new TestOrderState().weights(), null, null, Map.of(),
				Set.of(), true, null);

		AutoWorkflow.Result result = new AutoWorkflow(ctx, "order", null, null, false).execute(degraded);

		AutoWorkflow.Result.OrderSelect os = assertInstanceOf(AutoWorkflow.Result.OrderSelect.class, result);
		assertTrue(os.selectResult().allSelected(), "degraded analysis must select all");
		assertEquals(Set.of("com.example.OldTest", "com.example.PlusTest"), Set.copyOf(os.selection().selected()),
				"ALL module tests must be selected, selected=" + os.selection().selected());
		List<String> selected = Files.readAllLines(tempDir.resolve("selected.txt"));
		assertEquals(Set.of("com.example.OldTest", "com.example.PlusTest"), Set.copyOf(selected),
				"selected.txt must be overwritten with the full set: " + selected);
		assertFalse(Files.exists(tempDir.resolve("remaining.txt")),
				"stale remaining file must be deleted so run-remaining does not re-execute old deferrals");
		// coverage status over the select-all set stays visible
		assertEquals(1, os.selectResult().summary().elementsTotal(), "universe must be reported");
		assertEquals(0, os.selectResult().summary().elementsCovered());
		assertTrue(
				log.warns.stream().anyMatch(w -> w.contains("Coverage closure") && w.contains("com.example.Missing")),
				"uncovered element must be WARNed on select-all too, warns=" + log.warns);
	}
}
