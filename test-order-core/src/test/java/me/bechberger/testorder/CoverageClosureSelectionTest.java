package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangeKind;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;
import me.bechberger.testorder.ops.AffectedOperation;
import me.bechberger.testorder.ops.PluginLog;

/**
 * Tests for the coverage-closure selection loop (cover core, sole-coverer cache
 * exemption, uncovered reporting, enforce flag).
 */
class CoverageClosureSelectionTest {

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
	 * Dep map where the changed method {@code com.A#m} is exercised ONLY by
	 * {@code DTest} (member-level dep), while {@code ATest} depends on
	 * {@code com.A} but uses a different member.
	 */
	private static DependencyMap memberPrecisionDepMap() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.ATest", Set.of("com.example.A"));
		depMap.put("com.example.BTest", Set.of("com.example.B"));
		depMap.put("com.example.DTest", Set.of("com.example.A"));
		depMap.putMemberDeps("com.example.ATest", Set.of("com.example.A#other"));
		depMap.putMemberDeps("com.example.DTest", Set.of("com.example.A#m"));
		return depMap;
	}

	private static ChangedMembers changedMethodM() {
		Map<String, Set<String>> byClass = Map.of("com.example.A", Set.of("m"));
		return new ChangedMembers(Set.of("com.example.A"), Set.of("com.example.A#m"), byClass, Set.of(), Set.of(),
				Map.of("com.example.A#m", ChangeKind.BODY));
	}

	// ═════════════════════════════════════════════════════════════���
	// Cover-core phase
	// ═════════════════════════════════════════════════════════════���

	@Test
	void coverCoreForcedOutsideTopNCap() throws IOException {
		DependencyMap depMap = memberPrecisionDepMap();
		TestOrderState state = new TestOrderState();
		CapturingLog log = new CapturingLog();

		// topN=1: without closure only one scored test would run; the sole coverer
		// of com.A (DTest) must be selected regardless
		AffectedOperation.SelectConfig config = new AffectedOperation.SelectConfig(depMap, state,
				Set.of("com.example.A"), Set.of(), state.weights(), 1, 0, null, Set.of(), tempDir.resolve("sel.txt"),
				tempDir.resolve("rem.txt"), log, null, TestSelector.CacheConfig.DISABLED, changedMethodM(), false);

		AffectedOperation.SelectResult result = AffectedOperation.select(config);

		assertTrue(result.selection().selected().contains("com.example.DTest"),
				"Sole coverer DTest must be force-included despite topN=1, selected=" + result.selection().selected());
		assertEquals(1, result.coverageStatus().totalElements());
		assertEquals(1, result.coverageStatus().coveredCount());
		assertTrue(result.coverageStatus().allCovered());
		// ATest does NOT exercise the changed member in MEMBER mode — it must not be
		// selected as a coverer (it may still appear as an uncapped scored test).
		assertFalse(result.coverageStatus().uncoveredElements().contains("com.example.A"));
	}

	@Test
	void coverCoreAdditive_doesNotConsumeTopN() throws IOException {
		DependencyMap depMap = memberPrecisionDepMap();
		TestOrderState state = new TestOrderState();
		CapturingLog log = new CapturingLog();

		// topN=1: cover core (DTest) is additive; the remaining budget must still
		// admit one uncapped affected/scored test (ATest)
		AffectedOperation.SelectConfig config = new AffectedOperation.SelectConfig(depMap, state,
				Set.of("com.example.A"), Set.of(), state.weights(), 1, 0, null, Set.of(), tempDir.resolve("sel2.txt"),
				tempDir.resolve("rem2.txt"), log, null, TestSelector.CacheConfig.DISABLED, changedMethodM(), false);

		AffectedOperation.SelectResult result = AffectedOperation.select(config);

		List<String> selected = result.selection().selected();
		assertTrue(selected.contains("com.example.DTest"), "cover core must run: " + selected);
		assertTrue(selected.contains("com.example.ATest"),
				"topN budget must still admit 1 uncapped affected test: " + selected);
	}

	@Test
	void sinceLastRunNullMembers_classLevelFallback() throws IOException {
		DependencyMap depMap = memberPrecisionDepMap();
		// strip member deps: since-last-run indexes have none
		DependencyMap plain = new DependencyMap();
		plain.put("com.example.ATest", Set.of("com.example.A"));
		plain.put("com.example.BTest", Set.of("com.example.B"));
		plain.put("com.example.DTest", Set.of("com.example.C"));
		TestOrderState state = new TestOrderState();
		CapturingLog log = new CapturingLog();

		// changedMembers = null (since-last-run): class-level universe fallback.
		// DTest doesn't touch com.A; ATest covers it at class level.
		AffectedOperation.SelectConfig config = new AffectedOperation.SelectConfig(plain, state,
				Set.of("com.example.A"), Set.of(), state.weights(), 1, 0, null, Set.of(), tempDir.resolve("sel3.txt"),
				tempDir.resolve("rem3.txt"), log, null, TestSelector.CacheConfig.DISABLED, null, false);

		AffectedOperation.SelectResult result = AffectedOperation.select(config);

		assertTrue(result.selection().selected().contains("com.example.ATest"),
				"class-level fallback must cover com.A via ATest: " + result.selection().selected());
		assertTrue(result.coverageStatus().allCovered());
	}

	@Test
	void topNMinus1_behaviorUnchanged_whenAllCovered() throws IOException {
		DependencyMap depMap = memberPrecisionDepMap();
		TestOrderState state = new TestOrderState();
		CapturingLog log = new CapturingLog();

		AffectedOperation.SelectConfig config = new AffectedOperation.SelectConfig(depMap, state,
				Set.of("com.example.A"), Set.of(), state.weights(), -1, 0, null, Set.of(), tempDir.resolve("sel4.txt"),
				tempDir.resolve("rem4.txt"), log, null, TestSelector.CacheConfig.DISABLED, changedMethodM(), false);

		AffectedOperation.SelectResult result = AffectedOperation.select(config);

		// topN=-1: everything affected already runs — cover core adds nothing
		assertTrue(result.selection().selected().containsAll(List.of("com.example.ATest", "com.example.DTest")));
		assertFalse(result.selection().remaining().contains("com.example.ATest"));
		assertTrue(result.coverageStatus().allCovered());
	}

	// ═══════════════════════════════════════════════════════���
	/**
	 * Mirrors the fresh-learn repro: the static call-graph expansion scans TEST
	 * bytecode too, so {@code changedMembers.changedClasses()} contains the changed
	 * test classes (callers of the changed production class) alongside the changed
	 * production class.
	 */
	private static ChangedMembers expandedThroughTests() {
		Map<String, Set<String>> byClass = new java.util.LinkedHashMap<>();
		byClass.put("com.example.A", Set.of("m"));
		byClass.put("com.example.ATest", Set.of("<class>"));
		byClass.put("com.example.BTest", Set.of("<class>"));
		byClass.put("com.example.ATest$Inner", Set.of("<class>"));
		return new ChangedMembers(
				Set.of("com.example.A", "com.example.ATest", "com.example.BTest", "com.example.ATest$Inner"),
				Set.of("com.example.A#m", "com.example.ATest#<class>", "com.example.BTest#<class>"), byClass, Set.of(),
				Set.of(), Map.of("com.example.A#m", ChangeKind.BODY));
	}

	@Test
	void changedTestClasses_excludedFromUniverse() {
		DependencyMap depMap = memberPrecisionDepMap();
		// indexed changed test classes (changedTestClasses) must not enter the
		// universe either
		CoverageClosure closure = CoverageClosure.compute(depMap, Set.of("com.example.A"),
				Set.of("com.example.ATest", "com.example.BTest"), expandedThroughTests());

		assertEquals(Set.of("com.example.A"), closure.universe(),
				"universe must be production-only: changed test classes (incl. nested forms) are excluded");
	}

	/**
	 * End-to-end repro: fresh-learn state, explicit changed production class,
	 * changed TEST classes present (via the expanded members), topN=1. The topN cap
	 * must be respected (select 1, defer 1, remaining file written) and no test
	 * class may be reported as an uncovered changed element.
	 */
	@Test
	void changedTestClasses_topNCapRespected_noTestClassUncovered() throws IOException {
		DependencyMap depMap = memberPrecisionDepMap();
		TestOrderState state = new TestOrderState();
		CapturingLog log = new CapturingLog();

		// changedClasses = {com.example.A} (explicit mode); the expanded members also
		// carry the changed test classes; changedTests mirrors the changedAndNew set
		// the workflow passes (test classes "changed" per the widened analysis)
		AffectedOperation.SelectConfig config = new AffectedOperation.SelectConfig(depMap, state,
				Set.of("com.example.A"), Set.of("com.example.ATest", "com.example.BTest"), state.weights(), 1, 0, null,
				Set.of(), tempDir.resolve("selT.txt"), tempDir.resolve("remT.txt"), log, null,
				TestSelector.CacheConfig.DISABLED, expandedThroughTests(), false);

		AffectedOperation.SelectResult result = AffectedOperation.select(config);

		List<String> selected = result.selection().selected();
		List<String> remaining = result.selection().remaining();
		// ATest is the sole coverer of com.example.A (force-included, additive) —
		// but BTest must NOT be force-included by a self-cover and must NOT consume
		// the cap as a phantom cover-core pick: topN=1 admits only the cover core
		// plus one scored test, and BTest is not change-affected.
		assertTrue(selected.contains("com.example.ATest"), "sole coverer of A must run: " + selected);
		assertEquals(List.of("com.example.BTest"), remaining,
				"topN=1 must defer the unaffected test instead of select-all: " + selected);
		assertEquals(1, result.coverageStatus().totalElements(), "universe must be production-only");
		assertTrue(result.coverageStatus().allCovered(),
				"no test class may be reported uncovered, uncovered=" + result.coverageStatus().uncoveredElements());
		for (String element : result.coverageStatus().uncoveredElements()) {
			assertFalse(element.endsWith("Test"), "changed TEST class must never be an uncovered element: " + element);
		}
		// remaining.txt must be written so run-remaining can pick up the deferral
		assertTrue(java.nio.file.Files.exists(tempDir.resolve("remT.txt")),
				"remaining file must be written when tests are deferred");
		assertEquals("com.example.BTest", TestSelector.readTestList(tempDir.resolve("remT.txt")).get(0));
	}

	// Sole-coverer cache exemption
	// ═══════════════════════════════════════���

	@Test
	void soleCovererNeverCacheSkipped() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.XTest", Set.of("com.example.S"));
		depMap.put("com.example.YTest", Set.of("com.example.T"));
		TestOrderState state = new TestOrderState();
		recordPassingRuns(state, 5, "com.example.XTest", "com.example.YTest");

		// Structural-only change: com.S is in the universe via ChangedMembers but
		// NOT in the git-level changedClasses, so the cache eligibility check
		// (getAffectedTests(changedClasses)) would pass XTest — the sole-coverer
		// exemption must still block the skip.
		ChangedMembers structuralOnly = new ChangedMembers(Set.of("com.example.S"), Set.of("com.example.S#m"),
				Map.of("com.example.S", Set.of("m")), Set.of(), Set.of(), Map.of("com.example.S#m", ChangeKind.BODY));
		CoverageClosure closure = CoverageClosure.compute(depMap, Set.of(), Set.of(), structuralOnly);

		TestSelector selector = new TestSelector(depMap, state, Set.of(), Set.of(), state.weights(),
				new TestSelector.Config(-1, 0, null), Set.of(), Map.of(),
				new TestSelector.CacheConfig(true, 3, 1.0, Set.of()), closure);
		TestSelector.Selection selection = selector.select();

		assertTrue(selection.selected().contains("com.example.XTest"),
				"sole coverer must not be cache-skipped, selected=" + selection.selected());
		assertTrue(selection.cached().contains("com.example.YTest"),
				"non-coverer with pass streak is still cacheable, cached=" + selection.cached());
		assertTrue(selection.coverageStatus().allCovered());
	}

	@Test
	void coverCoreNeverCacheSkipped_evenWhenNotSoleCoverer() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.XTest", Set.of("com.example.S"));
		depMap.put("com.example.ZTest", Set.of("com.example.S"));
		depMap.put("com.example.YTest", Set.of("com.example.T"));
		TestOrderState state = new TestOrderState();
		recordPassingRuns(state, 5, "com.example.XTest", "com.example.ZTest", "com.example.YTest");

		// Two tests cover structural-only element com.S; the greedy core picks one of
		// them. Neither is affected per the git-level eligibility check — the core
		// pick must still survive cache-skip so closure holds.
		ChangedMembers structuralOnly = new ChangedMembers(Set.of("com.example.S"), Set.of("com.example.S#m"),
				Map.of("com.example.S", Set.of("m")), Set.of(), Set.of(), Map.of("com.example.S#m", ChangeKind.BODY));
		CoverageClosure closure = CoverageClosure.compute(depMap, Set.of(), Set.of(), structuralOnly);

		TestSelector selector = new TestSelector(depMap, state, Set.of(), Set.of(), state.weights(),
				new TestSelector.Config(-1, 0, null), Set.of(), Map.of(),
				new TestSelector.CacheConfig(true, 3, 1.0, Set.of()), closure);
		TestSelector.Selection selection = selector.select();

		assertEquals(1, closure.coverCore().size());
		assertTrue(selection.selected().contains(closure.coverCore().get(0)),
				"cover-core pick must not be cache-skipped, selected=" + selection.selected());
		assertTrue(selection.coverageStatus().allCovered());
	}

	private static void recordPassingRuns(TestOrderState state, int n, String... tests) {
		List<String> order = Arrays.asList(tests);
		for (int i = 0; i < n; i++) {
			state.addRunRecord(TestOrderState.buildRunRecord(order, Set.of()));
		}
	}

	// ═════════���
	// Uncovered reporting + enforce flag
	// ═══════���

	@Test
	void uncoveredElements_warnAndSummaryCounts() throws IOException {
		DependencyMap depMap = memberPrecisionDepMap();
		// remove DTest's member dep: nothing exercises com.A#m anymore
		depMap.putMemberDeps("com.example.DTest", Set.of("com.example.C#x"));
		// DTest still depends on com.A at class level but uses no changed member —
		// MEMBER mode says it does NOT cover com.A → uncovered
		TestOrderState state = new TestOrderState();
		CapturingLog log = new CapturingLog();

		AffectedOperation.SelectConfig config = new AffectedOperation.SelectConfig(depMap, state,
				Set.of("com.example.A"), Set.of(), state.weights(), -1, 0, null, Set.of(), tempDir.resolve("sel5.txt"),
				tempDir.resolve("rem5.txt"), log, null, TestSelector.CacheConfig.DISABLED, changedMethodM(), false);

		AffectedOperation.SelectResult result = AffectedOperation.select(config);

		assertEquals(1, result.coverageStatus().totalElements());
		assertEquals(0, result.coverageStatus().coveredCount());
		assertEquals(List.of("com.example.A"), result.coverageStatus().uncoveredElements());
		assertTrue(log.warns.stream().anyMatch(w -> w.contains("Coverage closure") && w.contains("com.example.A")),
				"uncovered element must be WARNed, warns=" + log.warns);
		assertEquals(1, result.summary().elementsTotal());
		assertEquals(0, result.summary().elementsCovered());
		assertEquals(1, result.summary().elementsUncovered());
	}

	@Test
	void enforceFlag_failsBuildOnUncovered() throws IOException {
		DependencyMap depMap = memberPrecisionDepMap();
		depMap.putMemberDeps("com.example.DTest", Set.of("com.example.C#x"));
		TestOrderState state = new TestOrderState();
		CapturingLog log = new CapturingLog();

		AffectedOperation.SelectConfig config = new AffectedOperation.SelectConfig(depMap, state,
				Set.of("com.example.A"), Set.of(), state.weights(), -1, 0, null, Set.of(), tempDir.resolve("sel6.txt"),
				tempDir.resolve("rem6.txt"), log, null, TestSelector.CacheConfig.DISABLED, changedMethodM(), true);

		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AffectedOperation.select(config));
		assertTrue(ex.getMessage().contains("Coverage closure failed"), ex.getMessage());
		assertTrue(ex.getMessage().contains("com.example.A"), "must list uncovered elements: " + ex.getMessage());
		assertTrue(ex.getMessage().contains("testorder.coverage.enforce"), "must be actionable: " + ex.getMessage());
		// enforce must fire BEFORE the fresh lists of this run are written
		assertFalse(java.nio.file.Files.exists(tempDir.resolve("sel6.txt")),
				"a failed-closure run must not write the selected list");
		assertFalse(java.nio.file.Files.exists(tempDir.resolve("rem6.txt")),
				"a failed-closure run must not write the remaining list");
	}

	@Test
	void enforceFail_deletesStaleSelectionArtifacts() throws IOException {
		// stale lists from a PREVIOUS run must be deleted before the enforce throw,
		// so a file-existence-keyed CI step cannot consume outdated selections
		DependencyMap depMap = memberPrecisionDepMap();
		depMap.putMemberDeps("com.example.DTest", Set.of("com.example.C#x"));
		TestOrderState state = new TestOrderState();
		CapturingLog log = new CapturingLog();
		java.nio.file.Files.write(tempDir.resolve("sel6.txt"), "com.example.Stale\n".getBytes());
		java.nio.file.Files.write(tempDir.resolve("rem6.txt"), "com.example.Stale\n".getBytes());

		AffectedOperation.SelectConfig config = new AffectedOperation.SelectConfig(depMap, state,
				Set.of("com.example.A"), Set.of(), state.weights(), -1, 0, null, Set.of(), tempDir.resolve("sel6.txt"),
				tempDir.resolve("rem6.txt"), log, null, TestSelector.CacheConfig.DISABLED, changedMethodM(), true);

		assertThrows(IllegalStateException.class, () -> AffectedOperation.select(config));
		assertFalse(java.nio.file.Files.exists(tempDir.resolve("sel6.txt")),
				"stale selected list must be deleted on enforce failure");
		assertFalse(java.nio.file.Files.exists(tempDir.resolve("rem6.txt")),
				"stale remaining list must be deleted on enforce failure");
	}

	@Test
	void precisionNotice_loggedWhenMemberDataMissing() throws IOException {
		// universe derived from member-level change data, but the index has no
		// MEMBER instrumentation → class-level coverage + precision notice
		DependencyMap plain = new DependencyMap();
		plain.put("com.example.ATest", Set.of("com.example.A"));
		plain.put("com.example.BTest", Set.of("com.example.B"));
		TestOrderState state = new TestOrderState();
		CapturingLog log = new CapturingLog();

		AffectedOperation.SelectConfig config = new AffectedOperation.SelectConfig(plain, state,
				Set.of("com.example.A"), Set.of(), state.weights(), -1, 0, null, Set.of(), tempDir.resolve("sel7.txt"),
				tempDir.resolve("rem7.txt"), log, null, TestSelector.CacheConfig.DISABLED, changedMethodM(), false);

		AffectedOperation.SelectResult result = AffectedOperation.select(config);

		assertTrue(log.infos.stream().anyMatch(i -> i.contains("class-level precision")),
				"precision notice expected, infos=" + log.infos);
		// class-level fallback: ATest covers com.A
		assertTrue(result.coverageStatus().allCovered());
	}

	@Test
	void noChangeSignal_closureInert() throws IOException {
		DependencyMap depMap = memberPrecisionDepMap();
		TestOrderState state = new TestOrderState();
		CapturingLog log = new CapturingLog();

		AffectedOperation.SelectConfig config = new AffectedOperation.SelectConfig(depMap, state, Set.of(), Set.of(),
				state.weights(), 1, 0, null, Set.of(), tempDir.resolve("sel8.txt"), tempDir.resolve("rem8.txt"), log,
				null, TestSelector.CacheConfig.DISABLED, null, false);

		AffectedOperation.SelectResult result = AffectedOperation.select(config);

		// no universe: legacy behavior preserved, zero coverage counts
		assertEquals(0, result.summary().elementsTotal());
		assertTrue(result.coverageStatus() == null || result.coverageStatus().totalElements() == 0);
	}

	@Test
	void precisionNotice_loggedWhenOnlyMethodLevelMemberData() throws IOException {
		// getMemberDeps reads ONLY the per-test-CLASS member map; an index with only
		// per-test-METHOD member data (hasMemberDeps()=true, memberDepsMap empty)
		// degrades to class-level coverage sets — the precision notice must still
		// fire (it was suppressed before, hiding the precision loss).
		DependencyMap methodOnly = new DependencyMap();
		methodOnly.put("com.example.ATest", Set.of("com.example.A"));
		methodOnly.put("com.example.BTest", Set.of("com.example.B"));
		methodOnly.putMethodMemberDeps("com.example.ATest#t", Set.of("com.example.A#other"));
		methodOnly.putMethodMemberDeps("com.example.BTest#t", Set.of("com.example.B#x"));
		TestOrderState state = new TestOrderState();
		CapturingLog log = new CapturingLog();

		AffectedOperation.SelectConfig config = new AffectedOperation.SelectConfig(methodOnly, state,
				Set.of("com.example.A"), Set.of(), state.weights(), -1, 0, null, Set.of(), tempDir.resolve("sel9.txt"),
				tempDir.resolve("rem9.txt"), log, null, TestSelector.CacheConfig.DISABLED, changedMethodM(), false);

		AffectedOperation.SelectResult result = AffectedOperation.select(config);

		assertTrue(log.infos.stream().anyMatch(i -> i.contains("class-level precision")),
				"precision notice expected for method-only member data, infos=" + log.infos);
		// class-level fallback: ATest covers com.A
		assertTrue(result.coverageStatus().allCovered());
	}

	// ════════════════════════════════════════════════════════════
	// Universe hygiene: nested elements + expansion-reached callers
	// ════════════════════════════════════════════════════════════

	@Test
	void nestedClassUniverseElement_topLevelDepIsCandidateAndCoverer() throws IOException {
		// changed NESTED class (com.example.Outer$Helper) while the test's recorded
		// dep is only the top-level class (com.example.Outer). The inverted index
		// stores the dep under com.Outer, so candidate discovery and class-level
		// overlap must go through the top-level alias — otherwise the sole coverer
		// is invisible and the element is falsely reported uncovered (spurious WARN
		// or coverage.enforce build failure).
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.OuterTest", Set.of("com.example.Outer"));
		depMap.put("com.example.OtherTest", Set.of("com.example.Unrelated"));
		TestOrderState state = new TestOrderState();
		CapturingLog log = new CapturingLog();

		AffectedOperation.SelectConfig config = new AffectedOperation.SelectConfig(depMap, state,
				Set.of("com.example.Outer$Helper"), Set.of(), state.weights(), 1, 0, null, Set.of(),
				tempDir.resolve("selN.txt"), tempDir.resolve("remN.txt"), log, null, TestSelector.CacheConfig.DISABLED,
				null, false);

		AffectedOperation.SelectResult result = AffectedOperation.select(config);

		assertEquals(1, result.coverageStatus().totalElements());
		assertTrue(result.coverageStatus().allCovered(), "top-level dep must cover the nested element, uncovered="
				+ result.coverageStatus().uncoveredElements());
		assertTrue(result.selection().selected().contains("com.example.OuterTest"),
				"sole coverer via top-level dep must be force-included, selected=" + result.selection().selected());
	}

	@Test
	void nestedClassUniverseElement_structuralOnly_coverCoreFound() {
		// tiered/structural-only path: changedClasses empty, the nested class enters
		// the universe via changedMembers — the top-level dep must still cover it
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.OuterTest", Set.of("com.example.Outer"));
		ChangedMembers members = nestedHelperChanged();

		CoverageClosure closure = CoverageClosure.compute(depMap, Set.of(), Set.of(), members);

		assertEquals(Set.of("com.example.Outer$Helper"), closure.universe());
		assertEquals(List.of("com.example.OuterTest"), closure.coverCore(),
				"the top-level-dep test must be discovered as the sole coverer");
		assertTrue(closure.status(List.of("com.example.OuterTest")).allCovered());
	}

	private static ChangedMembers nestedHelperChanged() {
		Map<String, Set<String>> byClass = Map.of("com.example.Outer$Helper", Set.of("m"));
		return new ChangedMembers(Set.of("com.example.Outer$Helper"), Set.of("com.example.Outer$Helper#m"), byClass,
				Set.of(), Set.of(), Map.of("com.example.Outer$Helper#m", ChangeKind.BODY));
	}

	@Test
	void expansionReachedUnchangedCaller_excludedFromUniverse() {
		// the static call-graph expansion reached com.example.UnchangedCaller (a
		// caller of com.example.A) but the class did NOT change; the class-level
		// changed set is authoritative and must win — an unchanged caller must not
		// become a universe element (cover-core inflation, false sole coverers)
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.ATest", Set.of("com.example.A"));
		depMap.put("com.example.CallersTest", Set.of("com.example.UnchangedCaller"));
		Map<String, Set<String>> byClass = Map.of("com.example.A", Set.of("m"), "com.example.UnchangedCaller",
				Set.of("<class>"));
		ChangedMembers expanded = new ChangedMembers(Set.of("com.example.A", "com.example.UnchangedCaller"),
				Set.of("com.example.A#m", "com.example.UnchangedCaller#<class>"), byClass, Set.of(), Set.of(),
				Map.of("com.example.A#m", ChangeKind.BODY));

		CoverageClosure closure = CoverageClosure.compute(depMap, Set.of("com.example.A"), Set.of(), expanded);

		assertEquals(Set.of("com.example.A"), closure.universe(),
				"expansion-reached caller classes that did not change must not be universe elements");
	}

	@Test
	void nestedStructuralClass_keptWhenTopLevelChanged() {
		// git-level detection derives class names from file paths (top-level),
		// while structural FQCNs can be nested — the universe intersection must be
		// nested-aware or genuinely changed nested classes would be dropped
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.OuterTest", Set.of("com.example.Outer"));
		ChangedMembers members = nestedHelperChanged();

		CoverageClosure closure = CoverageClosure.compute(depMap, Set.of("com.example.Outer"), Set.of(), members);

		assertEquals(Set.of("com.example.Outer", "com.example.Outer$Helper"), closure.universe(),
				"nested structural class must survive intersection with the top-level changed set");
		// the top-level dep covers both elements (exact + nested under top-level)
		assertTrue(closure.status(List.of("com.example.OuterTest")).allCovered());
	}
}
