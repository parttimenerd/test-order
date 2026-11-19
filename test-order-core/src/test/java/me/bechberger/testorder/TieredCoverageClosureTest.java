package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangeKind;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;
import me.bechberger.testorder.ops.PluginLog;
import me.bechberger.testorder.ops.TieredSelectOperation;

/**
 * Tests for the tiered coverage-closure wiring: cover-core retention in tier 1,
 * tier-1 closure status, uncovered-element reporting and the enforce fail path
 * via {@link TieredSelectOperation}.
 */
class TieredCoverageClosureTest {

	@TempDir
	Path tempDir;

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

	/**
	 * Structural-only change: {@code p.U#m} changed (visible only to structural
	 * analysis, NOT in the git-level changed classes), exercised only by
	 * {@code t.UTest}, which therefore carries no dep-overlap/changed/new signal
	 * and would land in tier 3 without cover-core retention.
	 */
	private static ChangedMembers structuralOnlyU() {
		Map<String, Set<String>> byClass = Map.of("p.U", Set.of("m"));
		return new ChangedMembers(Set.of("p.U"), Set.of("p.U#m"), byClass, Set.of(), Set.of(),
				Map.of("p.U#m", ChangeKind.BODY));
	}

	private static DependencyMap depMap() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("t.UTest", Set.of("p.U"));
		depMap.put("t.VTest", Set.of("p.V"));
		depMap.put("t.WTest", Set.of("p.W"));
		return depMap;
	}

	private TieredSelectOperation.TieredSelectConfig config(DependencyMap depMap, ChangedMembers changedMembers,
			boolean coverageEnforce, CapturingLog log) {
		return new TieredSelectOperation.TieredSelectConfig(depMap, new TestOrderState(), Set.of(), Set.of(),
				TestOrderState.ScoringWeights.DEFAULT, 0.5, false, Set.of(), tempDir.resolve("t1.txt"),
				tempDir.resolve("t2.txt"), tempDir.resolve("t3.txt"), log, changedMembers, coverageEnforce);
	}

	// ═══════════════════════════════════════════════════════════���
	// Cover-core retention in tier 1
	// ═════════════════════════════════════════════════���

	@Test
	void coverCoreTest_neverDemotedToTier2Or3() throws IOException {
		DependencyMap depMap = depMap();
		CapturingLog log = new CapturingLog();

		// Sanity: WITHOUT the closure the sole coverer of the structural-only change
		// has no tier-1 signal and falls to tier 3.
		TieredTestSelector.TieredSelection plain = new TieredTestSelector(depMap, new TestOrderState(), Set.of(),
				Set.of(), TestOrderState.ScoringWeights.DEFAULT, new TieredTestSelector.Config(0.5, false), Set.of())
				.select();
		assertFalse(plain.tier1().contains("t.UTest"),
				"scenario check: without closure t.UTest must not be tier 1, tier1=" + plain.tier1());

		// WITH the closure the cover core stays in tier 1.
		TieredSelectOperation.TieredSelectResult result = TieredSelectOperation
				.select(config(depMap, structuralOnlyU(), false, log));

		assertTrue(result.selection().tier1().contains("t.UTest"),
				"cover-core test must stay in tier 1, tier1=" + result.selection().tier1());
		assertFalse(result.selection().tier2().contains("t.UTest"));
		assertFalse(result.selection().tier3().contains("t.UTest"));
	}

	@Test
	void tier1ClosureSatisfied_statusReported() throws IOException {
		DependencyMap depMap = depMap();
		CapturingLog log = new CapturingLog();

		TieredSelectOperation.TieredSelectResult result = TieredSelectOperation
				.select(config(depMap, structuralOnlyU(), false, log));

		assertTrue(result.selection().coverageStatus() != null, "tier-1 coverage status must be exposed");
		assertTrue(result.selection().coverageStatus().allCovered(),
				"tier 1 must exercise the whole universe: " + result.selection().coverageStatus());
		assertEquals(1, result.selection().coverageStatus().totalElements());
		assertTrue(log.infos.stream().anyMatch(i -> i.contains("changed elements covered by tier 1: 1/1")),
				"coverage counts must appear in the tier summary, infos=" + log.infos);
		assertFalse(log.warns.stream().anyMatch(w -> w.contains("Tier-1 coverage closure")),
				"no closure warning when tier 1 covers everything, warns=" + log.warns);
	}

	// ═══════════════════════════════════════════════���
	// Uncovered reporting + enforce fail path
	// ═════════���

	/** Universe element {@code p.N} no test exercises (stale index scenario). */
	private static ChangedMembers orphanElementN() {
		Map<String, Set<String>> byClass = Map.of("p.N", Set.of("m"));
		return new ChangedMembers(Set.of("p.N"), Set.of("p.N#m"), byClass, Set.of(), Set.of(),
				Map.of("p.N#m", ChangeKind.BODY));
	}

	@Test
	void tier1Uncovered_warnedWithCounts() throws IOException {
		DependencyMap depMap = depMap();
		CapturingLog log = new CapturingLog();

		TieredSelectOperation.TieredSelectResult result = TieredSelectOperation
				.select(config(depMap, orphanElementN(), false, log));

		assertEquals(1, result.selection().coverageStatus().totalElements());
		assertEquals(0, result.selection().coverageStatus().coveredCount());
		assertEquals(List.of("p.N"), result.selection().coverageStatus().uncoveredElements());
		assertTrue(log.warns.stream().anyMatch(w -> w.contains("Tier-1 coverage closure") && w.contains("p.N")),
				"uncovered element must be WARNed with counts, warns=" + log.warns);
		assertTrue(log.infos.stream().anyMatch(i -> i.contains("changed elements covered by tier 1: 0/1")),
				"uncovered counts must appear in the tier summary, infos=" + log.infos);
	}

	@Test
	void tier1Uncovered_enforceFailsBeforeTierFilesAreWritten() {
		DependencyMap depMap = depMap();
		CapturingLog log = new CapturingLog();

		Path tier1File = tempDir.resolve("enforced-t1.txt");
		assertFalse(Files.exists(tier1File));

		TieredSelectOperation.TieredSelectConfig enforced = new TieredSelectOperation.TieredSelectConfig(depMap,
				new TestOrderState(), Set.of(), Set.of(), TestOrderState.ScoringWeights.DEFAULT, 0.5, false, Set.of(),
				tier1File, tempDir.resolve("enforced-t2.txt"), tempDir.resolve("enforced-t3.txt"), log,
				orphanElementN(), true);

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> TieredSelectOperation.select(enforced));
		assertTrue(ex.getMessage().contains("Tier-1 coverage closure failed"), ex.getMessage());
		assertTrue(ex.getMessage().contains("p.N"), "must list uncovered elements: " + ex.getMessage());
		assertTrue(ex.getMessage().contains("testorder.coverage.enforce"), "must be actionable: " + ex.getMessage());
		assertFalse(Files.exists(tier1File), "a failed-closure run must not leave tier files a CI step would consume");
	}

	@Test
	void backwardCompatConfig_withoutClosureWiring_inert() throws IOException {
		DependencyMap depMap = depMap();
		CapturingLog log = new CapturingLog();

		// Old 12-arg constructor: no changedMembers, no enforce — legacy behavior.
		TieredSelectOperation.TieredSelectResult result = TieredSelectOperation
				.select(new TieredSelectOperation.TieredSelectConfig(depMap, new TestOrderState(), Set.of(), Set.of(),
						TestOrderState.ScoringWeights.DEFAULT, 0.5, false, Set.of(), tempDir.resolve("c1.txt"),
						tempDir.resolve("c2.txt"), tempDir.resolve("c3.txt"), log));

		assertEquals(3, result.totalTests());
		assertTrue(
				result.selection().coverageStatus() == null || result.selection().coverageStatus().totalElements() == 0,
				"no closure wiring: zero-element (inert) status expected");
		assertFalse(log.warns.stream().anyMatch(w -> w.contains("Tier-1 coverage closure")));
	}
}
