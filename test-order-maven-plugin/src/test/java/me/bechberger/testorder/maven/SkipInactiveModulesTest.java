package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

import me.bechberger.testorder.ops.ReactorOrderOperation.ModuleScore;

/**
 * Tests for the "skip inactive modules" logic in
 * {@link CollectorLifecycleParticipant} and supporting helpers.
 *
 * <p>
 * Covers: which modules get skip properties set, which are left alone, the
 * activation flag, and edge cases (all active, none active, POM packaging,
 * transitive deps not skipped).
 */
class SkipInactiveModulesTest {

	private final CollectorLifecycleParticipant participant = new CollectorLifecycleParticipant();

	// ---- project / session factory helpers ----

	private static MavenProject project(String gid, String aid) {
		MavenProject p = mock(MavenProject.class);
		when(p.getGroupId()).thenReturn(gid);
		when(p.getArtifactId()).thenReturn(aid);
		when(p.getDependencies()).thenReturn(new ArrayList<>());
		when(p.getParent()).thenReturn(null);
		when(p.getPackaging()).thenReturn("jar");
		Properties props = new Properties();
		when(p.getProperties()).thenReturn(props);
		return p;
	}

	private static MavenProject pomProject(String gid, String aid) {
		MavenProject p = project(gid, aid);
		when(p.getPackaging()).thenReturn("pom");
		return p;
	}

	private static void addDep(MavenProject p, MavenProject upstream) {
		Dependency d = new Dependency();
		d.setGroupId(upstream.getGroupId());
		d.setArtifactId(upstream.getArtifactId());
		p.getDependencies().add(d);
	}

	private static ModuleScore score(String mid, int affected) {
		return new ModuleScore(mid, affected, affected, affected, affected, List.of());
	}

	/**
	 * A module that has observed tests in the index but none affected by the
	 * current change set. Production emits this for every non-pom reactor module
	 * whose tests are indexed but unrelated to the change. Distinguishes "observed
	 * unaffected" from "unindexed" (no score / totalTestCount=0), which must not be
	 * skipped.
	 */
	private static ModuleScore observedUnaffected(String mid, int total) {
		return new ModuleScore(mid, 0, 0L, 0, total, List.of());
	}

	// Compute skip flags the same way CollectorLifecycleParticipant does.
	private static void applySkipInactive(List<MavenProject> reactor, Map<String, ModuleScore> scoreById) {
		Map<String, MavenProject> byId = new HashMap<>();
		for (MavenProject p : reactor) {
			byId.put(ModuleIds.of(p), p);
		}
		ReactorReorderer.ReorderResult reorder = ReactorReorderer.reorder(reactor, scoreById, null);
		if (reorder.activeModules() == 0) {
			return;
		}
		java.util.Set<MavenProject> required = ReactorReorderer.transitiveRequired(reorder.active(), reactor,
				reorder.projectsById());
		for (MavenProject p : reactor) {
			if ("pom".equals(p.getPackaging())) {
				continue;
			}
			if (!required.contains(p)) {
				ModuleScore ms = scoreById.get(ModuleIds.of(p));
				if (ms == null || ms.totalTestCount() == 0) {
					continue;
				}
				p.getProperties().setProperty("maven.main.skip", "true");
				p.getProperties().setProperty("maven.test.skip", "true");
				p.getProperties().setProperty("skipTests", "true");
				p.getProperties().setProperty("enforcer.skip", "true");
				p.getProperties().setProperty("skipFormatting", "true");
			}
		}
	}

	private static boolean isSkipped(MavenProject p) {
		return "true".equals(p.getProperties().getProperty("maven.main.skip"));
	}

	// ---- activation flag tests ----

	private org.apache.maven.execution.MavenSession sessionWithGoals(Properties userProps, List<String> goals) {
		org.apache.maven.execution.MavenSession s = mock(org.apache.maven.execution.MavenSession.class);
		lenient().when(s.getUserProperties()).thenReturn(userProps);
		lenient().when(s.getSystemProperties()).thenReturn(new Properties());
		lenient().when(s.getTopLevelProject()).thenReturn(null);
		lenient().when(s.getGoals()).thenReturn(goals);
		return s;
	}

	@Test
	void skipInactiveEnabled_defaultOnForAffectedGoal() {
		org.apache.maven.execution.MavenSession s = sessionWithGoals(new Properties(),
				List.of("test-order:affected", "test"));
		assertTrue(participant.isReorderEnabled(s),
				"skipInactiveModules must be default-on when 'affected' is in goals");
	}

	@Test
	void skipInactiveDisabled_defaultOffForPlainTest() {
		org.apache.maven.execution.MavenSession s = sessionWithGoals(new Properties(), List.of("test"));
		assertFalse(participant.isReorderEnabled(s), "skipInactiveModules must be default-off for plain 'test'");
	}

	@Test
	void skipInactiveExplicitFalse_disablesEvenWithAffectedGoal() {
		Properties p = new Properties();
		p.setProperty("testorder.reactorReorder", "false");
		org.apache.maven.execution.MavenSession s = sessionWithGoals(p, List.of("test-order:affected"));
		assertFalse(participant.isReorderEnabled(s));
	}

	// ---- skip-properties logic tests ----

	@Test
	void noActiveModules_noSkipsApplied() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		List<MavenProject> reactor = List.of(a, b);
		applySkipInactive(reactor, Map.of()); // no scores → no active modules
		assertFalse(isSkipped(a));
		assertFalse(isSkipped(b));
	}

	@Test
	void allModulesActive_noSkipsApplied() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		List<MavenProject> reactor = List.of(a, b);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-a", score("g-a", 3));
		scores.put("g-b", score("g-b", 3));
		applySkipInactive(reactor, scores);
		assertFalse(isSkipped(a));
		assertFalse(isSkipped(b));
	}

	@Test
	void independentModules_onlyActiveModuleNotSkipped() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		List<MavenProject> reactor = List.of(a, b, c);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-a", observedUnaffected("g-a", 4));
		scores.put("g-b", score("g-b", 5)); // only b is active
		scores.put("g-c", observedUnaffected("g-c", 2));
		applySkipInactive(reactor, scores);
		assertTrue(isSkipped(a), "a has indexed tests but none affected and doesn't feed b → must be skipped");
		assertFalse(isSkipped(b), "b is active → must not be skipped");
		assertTrue(isSkipped(c), "c has indexed tests but none affected and doesn't feed b → must be skipped");
	}

	@Test
	void transitiveDepOfActiveModule_notSkipped() {
		// chain: a → b → c (c has affected tests)
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		addDep(b, a);
		addDep(c, b);
		List<MavenProject> reactor = List.of(a, b, c);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-c", score("g-c", 5));
		applySkipInactive(reactor, scores);
		assertFalse(isSkipped(a), "a is a transitive dep of active c → must compile");
		assertFalse(isSkipped(b), "b is a transitive dep of active c → must compile");
		assertFalse(isSkipped(c), "c is active → must not be skipped");
	}

	@Test
	void pomPackagingModule_neverSkipped() {
		MavenProject pom = pomProject("g", "parent");
		MavenProject leaf = project("g", "leaf");
		List<MavenProject> reactor = List.of(pom, leaf);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-leaf", score("g-leaf", 5));
		applySkipInactive(reactor, scores);
		assertFalse(isSkipped(pom), "POM-packaging modules must never receive skip flags");
	}

	@Test
	void skippedModule_hasFiveSkipPropertiesSet() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		List<MavenProject> reactor = List.of(a, b);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-a", observedUnaffected("g-a", 3));
		scores.put("g-b", score("g-b", 1));
		applySkipInactive(reactor, scores);
		Properties props = a.getProperties();
		assertEquals("true", props.getProperty("maven.main.skip"));
		assertEquals("true", props.getProperty("maven.test.skip"));
		assertEquals("true", props.getProperty("skipTests"));
		assertEquals("true", props.getProperty("enforcer.skip"));
		assertEquals("true", props.getProperty("skipFormatting"));
	}

	@Test
	void activeModule_hasNoSkipPropertiesSet() {
		MavenProject a = project("g", "a");
		List<MavenProject> reactor = List.of(a);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-a", score("g-a", 3));
		applySkipInactive(reactor, scores);
		Properties props = a.getProperties();
		assertNull(props.getProperty("maven.main.skip"));
		assertNull(props.getProperty("maven.test.skip"));
		assertNull(props.getProperty("skipTests"));
	}

	@Test
	void diamondTopologyActiveLeaf_allAncestorsSpared() {
		// a ← b ← d, a ← c ← d; only d active
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		MavenProject d = project("g", "d");
		MavenProject unrelated = project("g", "unrelated");
		addDep(b, a);
		addDep(c, a);
		addDep(d, b);
		addDep(d, c);
		List<MavenProject> reactor = List.of(a, b, c, d, unrelated);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-d", score("g-d", 10));
		scores.put("g-unrelated", observedUnaffected("g-unrelated", 5));
		applySkipInactive(reactor, scores);
		assertFalse(isSkipped(a));
		assertFalse(isSkipped(b));
		assertFalse(isSkipped(c));
		assertFalse(isSkipped(d));
		assertTrue(isSkipped(unrelated), "module unrelated to the active subtree must be skipped");
	}

	@Test
	void twoActiveModulesWithSeparateDeps_thirdIndependentSkipped() {
		// left: a → b (b active); right: c → d (d active); e is unrelated
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		MavenProject d = project("g", "d");
		MavenProject e = project("g", "e");
		addDep(b, a);
		addDep(d, c);
		List<MavenProject> reactor = List.of(a, b, c, d, e);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-b", score("g-b", 3));
		scores.put("g-d", score("g-d", 4));
		scores.put("g-e", observedUnaffected("g-e", 2));
		applySkipInactive(reactor, scores);
		assertFalse(isSkipped(a), "a feeds active b → required");
		assertFalse(isSkipped(b), "b is active");
		assertFalse(isSkipped(c), "c feeds active d → required");
		assertFalse(isSkipped(d), "d is active");
		assertTrue(isSkipped(e), "e is unrelated → must be skipped");
	}

	@Test
	void singleModuleReactor_activeModule_nothingSkipped() {
		MavenProject a = project("g", "a");
		List<MavenProject> reactor = List.of(a);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-a", score("g-a", 1));
		applySkipInactive(reactor, scores);
		assertFalse(isSkipped(a));
	}

	@Test
	void topNCapDoesNotPreventTransitiveDepsFromBeingRequired() {
		// topN=1 caps at 1 active module; the active module still has transitive deps
		// that must compile.
		// chain: a → b → c; c has the highest score so it's the one module topN=1
		// picks.
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		addDep(b, a);
		addDep(c, b);
		List<MavenProject> reactor = List.of(a, b, c);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-c", score("g-c", 20)); // highest score → picked by topN=1
		scores.put("g-b", score("g-b", 5));
		ReactorReorderer.ReorderResult reorder = ReactorReorderer.reorder(reactor, scores, 1);
		assertEquals(1, reorder.activeModules(), "topN=1 should select exactly one module");
		assertTrue(reorder.active().contains(c), "c has the highest score and must be the active module");
		java.util.Set<MavenProject> required = ReactorReorderer.transitiveRequired(reorder.active(), reactor,
				reorder.projectsById());
		assertTrue(required.contains(c), "active module c must be required");
		assertTrue(required.contains(b), "b is a direct dep of active c → required");
		assertTrue(required.contains(a), "a is a transitive dep of active c → required");
	}

	@Test
	void preExistingSkipTrue_notOverwritten() {
		// A module already has maven.main.skip=true set (e.g. by the user or another
		// plugin). Applying skip-inactive must not clear it — it should stay true.
		MavenProject a = project("g", "a");
		a.getProperties().setProperty("maven.main.skip", "true");
		MavenProject b = project("g", "b");
		List<MavenProject> reactor = List.of(a, b);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-b", score("g-b", 3));
		applySkipInactive(reactor, scores);
		assertEquals("true", a.getProperties().getProperty("maven.main.skip"),
				"pre-existing true skip must not be disturbed");
	}

	@Test
	void skipInactiveEnabled_autoGoalAlsoTriggers() {
		org.apache.maven.execution.MavenSession s = sessionWithGoals(new Properties(),
				List.of("test-order:auto", "test"));
		assertTrue(participant.isReorderEnabled(s), "auto goal must also trigger skip-inactive");
	}

	@Test
	void skipInactiveEnabled_runTierGoalAlsoTriggers() {
		org.apache.maven.execution.MavenSession s = sessionWithGoals(new Properties(), List.of("test-order:run-tier1"));
		assertTrue(participant.isReorderEnabled(s), "run-tier goal must also trigger skip-inactive");
	}

	@Test
	void requiredModulesHaveNoExtraPropertiesSetAtAll() {
		// Transitive deps of active modules must be completely untouched: none of
		// the five skip properties should be set on them.
		MavenProject a = project("g", "a"); // dep of b
		MavenProject b = project("g", "b"); // active
		addDep(b, a);
		List<MavenProject> reactor = List.of(a, b);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-b", score("g-b", 5));
		applySkipInactive(reactor, scores);
		Properties props = a.getProperties();
		assertNull(props.getProperty("maven.main.skip"), "required module must not have maven.main.skip set");
		assertNull(props.getProperty("maven.test.skip"), "required module must not have maven.test.skip set");
		assertNull(props.getProperty("skipTests"), "required module must not have skipTests set");
		assertNull(props.getProperty("enforcer.skip"), "required module must not have enforcer.skip set");
		assertNull(props.getProperty("skipFormatting"), "required module must not have skipFormatting set");
	}

	@Test
	void largeReactor_onlyOneMostPopularModuleActive_correctCountSkipped() {
		// 20 independent modules; only module-10 has affected tests, the other 19
		// have indexed tests but none affected. All 19 others (independent) must
		// be skipped.
		List<MavenProject> reactor = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			reactor.add(project("g", "m" + i));
		}
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-m10", score("g-m10", 7));
		for (int i = 0; i < 20; i++) {
			if (i == 10)
				continue;
			scores.put("g-m" + i, observedUnaffected("g-m" + i, 2));
		}
		applySkipInactive(reactor, scores);

		int skippedCount = 0;
		for (MavenProject p : reactor) {
			if (isSkipped(p))
				skippedCount++;
		}
		assertEquals(19, skippedCount, "19 of 20 independent modules must be skipped");
		assertFalse(isSkipped(reactor.get(10)), "active module m10 must not be skipped");
	}

	@Test
	void unindexedModule_notSkipped_evenWhenNotRequired() {
		// Bug #39: a sibling module with NO score data (e.g. newly added module,
		// or test classes not compiled this run) must NOT be silently skipped just
		// because no active module depends on it. We have no evidence its tests
		// are unaffected by the change set, so we must run it.
		MavenProject active = project("g", "active");
		MavenProject unindexed = project("g", "unindexed");
		// no addDep — modules are independent
		List<MavenProject> reactor = List.of(active, unindexed);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-active", score("g-active", 5));
		// note: NO entry for g-unindexed → ms == null in production
		applySkipInactive(reactor, scores);
		assertFalse(isSkipped(unindexed),
				"unindexed module must not be skipped — its tests may be affected by the change set");
		assertFalse(isSkipped(active));
	}

	@Test
	void moduleWithZeroTotalTestCount_notSkipped() {
		// Bug #39 sibling case: ReactorOrderOperation emits
		// ModuleScore(...,0,0,0,0,...)
		// when on-disk tests are empty or none are indexed. totalTestCount==0 means
		// we have no real signal — must not skip.
		MavenProject active = project("g", "active");
		MavenProject empty = project("g", "empty");
		List<MavenProject> reactor = List.of(active, empty);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-active", score("g-active", 5));
		scores.put("g-empty", new ModuleScore("g-empty", 0, 0L, 0, 0, List.of()));
		applySkipInactive(reactor, scores);
		assertFalse(isSkipped(empty), "module with totalTestCount=0 means we have no index signal — must not skip");
	}

	@Test
	void mixedPomAndJarModules_pomOnesNeverSkipped_jarOnesSkippedIfUnneeded() {
		MavenProject pomParent = pomProject("g", "parent");
		MavenProject libA = project("g", "lib-a");
		MavenProject libB = project("g", "lib-b"); // active
		MavenProject pomIntermediate = pomProject("g", "intermediate");
		List<MavenProject> reactor = List.of(pomParent, libA, libB, pomIntermediate);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-lib-a", observedUnaffected("g-lib-a", 3));
		scores.put("g-lib-b", score("g-lib-b", 4));
		applySkipInactive(reactor, scores);

		assertFalse(isSkipped(pomParent), "POM parent must never be skipped");
		assertFalse(isSkipped(pomIntermediate), "POM intermediate must never be skipped");
		assertTrue(isSkipped(libA), "lib-a has no affected tests and lib-b doesn't depend on it → skip");
		assertFalse(isSkipped(libB), "lib-b is active → not skipped");
	}
}
