package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

import me.bechberger.testorder.ops.ReactorOrderOperation.ModuleScore;

class ReactorReordererTest {

	private static MavenProject project(String gid, String aid) {
		MavenProject p = mock(MavenProject.class);
		when(p.getGroupId()).thenReturn(gid);
		when(p.getArtifactId()).thenReturn(aid);
		when(p.getDependencies()).thenReturn(new ArrayList<>());
		when(p.getParent()).thenReturn(null);
		return p;
	}

	private static void addDep(MavenProject p, MavenProject upstream) {
		Dependency d = new Dependency();
		d.setGroupId(upstream.getGroupId());
		d.setArtifactId(upstream.getArtifactId());
		List<Dependency> deps = p.getDependencies();
		deps.add(d);
	}

	private static ModuleScore score(String mid, int affected) {
		return new ModuleScore(mid, affected, affected, affected, affected, List.of());
	}

	@Test
	void emptyReactor_returnsEmptyResult() {
		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(List.of(), Map.of(), null);
		assertTrue(r.ordered().isEmpty());
		assertEquals(0, r.activeModules());
	}

	@Test
	void noScores_keepsOriginalOrder() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		List<MavenProject> orig = List.of(a, b, c);
		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, Map.of(), null);
		assertEquals(orig, r.ordered());
		assertEquals(0, r.activeModules());
		assertEquals(3, r.deferredModules());
	}

	@Test
	void scoredModulesBubbleForward_whenNoDepsBlock() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		List<MavenProject> orig = List.of(a, b, c);

		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-c", score("g-c", 10));

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, null);
		assertSame(c, r.ordered().get(0), "highest-score module should run first");
		assertEquals(1, r.activeModules());
		assertEquals(2, r.deferredModules());
		assertEquals(10, r.cumulativeAffected());
	}

	@Test
	void dependencyConstraintsPreserved_evenWhenScoreSaysOtherwise() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		// b depends on a, c depends on b → must run a, b, c
		addDep(b, a);
		addDep(c, b);
		List<MavenProject> orig = List.of(a, b, c);

		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-c", score("g-c", 10)); // would want c first, but a,b must precede

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, null);
		assertEquals(List.of(a, b, c), r.ordered(), "deps must be honored");
	}

	@Test
	void scoreOrderRespected_amongIndependentModules() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		List<MavenProject> orig = List.of(a, b, c);

		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-a", score("g-a", 1));
		scores.put("g-b", score("g-b", 5));
		scores.put("g-c", score("g-c", 3));

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, null);
		assertEquals(List.of(b, c, a), r.ordered());
		assertEquals(3, r.activeModules());
		assertEquals(0, r.deferredModules());
		assertEquals(9, r.cumulativeAffected());
	}

	@Test
	void topNCap_limitsActiveSet() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		List<MavenProject> orig = List.of(a, b, c);

		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-a", score("g-a", 4));
		scores.put("g-b", score("g-b", 4));
		scores.put("g-c", score("g-c", 4));

		// topN=5 → after first module (4) we still have budget; second pushes to 8 ≥ 5
		// → only first picked
		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, 5);
		// After picking 1 module (4 tests), cumulative = 4 < 5, so we pick another
		// (cumulative=8); then 8 ≥ 5 stops
		assertEquals(2, r.activeModules(), "should pick 2 modules: 4 tests, then 8 tests crossing threshold");
		assertEquals(1, r.deferredModules());
		assertEquals(8, r.cumulativeAffected());
	}

	@Test
	void zeroAffected_neverActive() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		List<MavenProject> orig = List.of(a, b);

		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-a", score("g-a", 0));
		scores.put("g-b", score("g-b", 0));

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, null);
		assertEquals(0, r.activeModules());
		assertEquals(2, r.deferredModules());
	}

	@Test
	void nullOriginal_returnsEmpty() {
		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(null, Map.of(), 5);
		assertTrue(r.ordered().isEmpty());
		assertEquals(0, r.activeModules());
		assertEquals(0, r.deferredModules());
		assertEquals(0, r.cumulativeAffected());
	}

	@Test
	void parentRelationship_treatedAsDependency() {
		MavenProject parent = project("g", "parent");
		MavenProject child = project("g", "child");
		when(child.getParent()).thenReturn(parent);
		List<MavenProject> orig = List.of(parent, child);

		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-child", score("g-child", 100));

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, null);
		assertEquals(List.of(parent, child), r.ordered(),
				"parent must run before child even when child has higher score");
	}

	@Test
	void dependencyOnNonReactorModule_ignored() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		// b "depends" on something that is NOT in the reactor — must not be treated as
		// a constraint
		Dependency external = new Dependency();
		external.setGroupId("com.external");
		external.setArtifactId("not-in-reactor");
		b.getDependencies().add(external);
		List<MavenProject> orig = List.of(a, b);

		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-b", score("g-b", 5));

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, null);
		assertSame(b, r.ordered().get(0), "b should run first; non-reactor dep is irrelevant");
	}

	@Test
	void tiedScores_brokenByOriginalIndex() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		List<MavenProject> orig = List.of(a, b, c);

		Map<String, ModuleScore> scores = new HashMap<>();
		// All identical scores → original order should be preserved among them
		scores.put("g-a", score("g-a", 3));
		scores.put("g-b", score("g-b", 3));
		scores.put("g-c", score("g-c", 3));

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, null);
		assertEquals(List.of(a, b, c), r.ordered(), "tied scores must keep original order");
	}

	@Test
	void topNZero_treatedAsUncapped() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		List<MavenProject> orig = List.of(a, b);

		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-a", score("g-a", 5));
		scores.put("g-b", score("g-b", 5));

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, 0);
		assertEquals(2, r.activeModules(), "topN<=0 should be treated as uncapped");
	}

	@Test
	void missingScores_areTreatedAsZero() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		List<MavenProject> orig = List.of(a, b);

		// No score map entries at all
		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, Map.of(), null);
		assertEquals(orig, r.ordered(), "with no scores, order is unchanged");
		assertEquals(0, r.activeModules());
	}

	@Test
	void result_orderedListIsImmutable() {
		MavenProject a = project("g", "a");
		List<MavenProject> orig = List.of(a);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-a", score("g-a", 1));

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, null);
		assertThrows(UnsupportedOperationException.class, () -> r.ordered().add(a));
		assertThrows(UnsupportedOperationException.class, () -> r.deferred().add(a));
	}

	@Test
	void diamondDependency_orderingHonoured() {
		// a → b, a → c, b → d, c → d (diamond)
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		MavenProject d = project("g", "d");
		addDep(b, a);
		addDep(c, a);
		addDep(d, b);
		addDep(d, c);
		List<MavenProject> orig = List.of(a, b, c, d);

		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-d", score("g-d", 100)); // d wants to be first, but everything must precede it

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, null);
		List<MavenProject> ord = r.ordered();
		// d must be last
		assertSame(d, ord.get(3));
		// a must be first (needed by both b and c)
		assertSame(a, ord.get(0));
		// b and c can be in either order, but both must come before d
		assertTrue(ord.indexOf(b) < ord.indexOf(d));
		assertTrue(ord.indexOf(c) < ord.indexOf(d));
	}

	// ---- transitiveRequired tests ----

	private static Map<String, MavenProject> byId(List<MavenProject> projects) {
		Map<String, MavenProject> m = new HashMap<>();
		for (MavenProject p : projects) {
			m.put(ModuleIds.of(p), p);
		}
		return m;
	}

	@Test
	void transitiveRequired_emptyActive_returnsEmpty() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		List<MavenProject> reactor = List.of(a, b);
		java.util.Set<MavenProject> required = ReactorReorderer.transitiveRequired(Set.of(), reactor, byId(reactor));
		assertTrue(required.isEmpty());
	}

	@Test
	void transitiveRequired_activeWithNoDeps_returnsOnlySelf() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		List<MavenProject> reactor = List.of(a, b);
		// a and b are independent; only a is active
		java.util.Set<MavenProject> required = ReactorReorderer.transitiveRequired(Set.of(a), reactor, byId(reactor));
		assertEquals(Set.of(a), required);
	}

	@Test
	void transitiveRequired_chain_includesFullAncestry() {
		// a → b → c, only c active → a and b must be included
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		addDep(b, a);
		addDep(c, b);
		List<MavenProject> reactor = List.of(a, b, c);
		java.util.Set<MavenProject> required = ReactorReorderer.transitiveRequired(Set.of(c), reactor, byId(reactor));
		assertEquals(Set.of(a, b, c), required);
	}

	@Test
	void transitiveRequired_twoIndependentSubgraphs_onlyActiveSubgraphRequired() {
		// left: a → b, right: c → d — only b is active
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		MavenProject d = project("g", "d");
		addDep(b, a);
		addDep(d, c);
		List<MavenProject> reactor = List.of(a, b, c, d);
		java.util.Set<MavenProject> required = ReactorReorderer.transitiveRequired(Set.of(b), reactor, byId(reactor));
		assertEquals(Set.of(a, b), required, "right subgraph (c, d) must not be required");
	}

	@Test
	void transitiveRequired_diamond_allFourRequired() {
		// a ← b ← d, a ← c ← d; d is active
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		MavenProject d = project("g", "d");
		addDep(b, a);
		addDep(c, a);
		addDep(d, b);
		addDep(d, c);
		List<MavenProject> reactor = List.of(a, b, c, d);
		java.util.Set<MavenProject> required = ReactorReorderer.transitiveRequired(Set.of(d), reactor, byId(reactor));
		assertEquals(Set.of(a, b, c, d), required);
	}

	@Test
	void transitiveRequired_allModulesActive_returnsAll() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		addDep(b, a);
		addDep(c, b);
		List<MavenProject> reactor = List.of(a, b, c);
		java.util.Set<MavenProject> active = Set.of(a, b, c);
		java.util.Set<MavenProject> required = ReactorReorderer.transitiveRequired(active, reactor, byId(reactor));
		assertEquals(Set.of(a, b, c), required);
	}

	@Test
	void transitiveRequired_activeModuleIsOwnDep_noCycleOrDuplicate() {
		// Multiple active modules where one is already a transitive dep of another.
		// b → a, both active: result must still just be {a, b}, not duplicates.
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		addDep(b, a);
		List<MavenProject> reactor = List.of(a, b);
		java.util.Set<MavenProject> required = ReactorReorderer.transitiveRequired(Set.of(a, b), reactor,
				byId(reactor));
		assertEquals(Set.of(a, b), required);
	}

	@Test
	void transitiveRequired_multipleActiveModules_unionOfAncestors() {
		// topology: a, b, c, d, e — c→a, d→b, e is independent
		// active = {c, d} → required = {a, b, c, d}; e is not required
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		MavenProject d = project("g", "d");
		MavenProject e = project("g", "e");
		addDep(c, a);
		addDep(d, b);
		List<MavenProject> reactor = List.of(a, b, c, d, e);
		java.util.Set<MavenProject> required = ReactorReorderer.transitiveRequired(Set.of(c, d), reactor,
				byId(reactor));
		assertEquals(Set.of(a, b, c, d), required, "e must not be required");
	}

	@Test
	void reorderResult_activeSetAndProjectsByIdExposed() {
		// Smoke test that the new record fields are populated correctly.
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		List<MavenProject> orig = List.of(a, b);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-a", score("g-a", 5));

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, null);

		assertTrue(r.active().contains(a), "a must be in the active set");
		assertFalse(r.active().contains(b), "b has no score and must not be active");
		assertEquals("g", r.projectsById().get("g-a").getGroupId());
		assertEquals("g", r.projectsById().get("g-b").getGroupId());
	}

	@Test
	void reorderResult_activeSetIsImmutable() {
		MavenProject a = project("g", "a");
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-a", score("g-a", 1));
		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(List.of(a), scores, null);
		assertThrows(UnsupportedOperationException.class, () -> r.active().add(a));
		assertThrows(UnsupportedOperationException.class, () -> r.projectsById().put("x", a));
	}

	@Test
	void deferred_isComplementOfActive() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		MavenProject c = project("g", "c");
		List<MavenProject> orig = List.of(a, b, c);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-b", score("g-b", 5));

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, null);

		// Every module is either active or deferred, never both, never neither.
		for (MavenProject p : orig) {
			boolean inActive = r.active().contains(p);
			boolean inDeferred = r.deferred().contains(p);
			assertTrue(inActive ^ inDeferred, p.getArtifactId() + " must be in exactly one of active/deferred");
		}
		assertEquals(r.activeModules() + r.deferredModules(), orig.size());
	}

	@Test
	void topN_exactlyMeetsCapOnFirstModule_onlyOneActive() {
		// cap=5, first module has affectedTestCount=5 → cumulative goes 0→5, which
		// equals cap, so the loop stops after adding that one module.
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		List<MavenProject> orig = List.of(a, b);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-a", score("g-a", 5));
		scores.put("g-b", score("g-b", 3));

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, 5);
		assertEquals(1, r.activeModules(), "first module exactly fills the cap → only it is active");
		assertTrue(r.active().contains(a));
		assertFalse(r.active().contains(b));
	}

	@Test
	void topN_capExceededOnFirstModule_stillAddsIt() {
		// cap=1, first module has affectedTestCount=10 → still added (we only stop
		// AFTER adding while cumulative < cap check is false).
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		List<MavenProject> orig = List.of(a, b);
		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g-a", score("g-a", 10));
		scores.put("g-b", score("g-b", 10));

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, 1);
		assertEquals(1, r.activeModules(), "cap=1 should allow at most one module in");
		assertTrue(r.active().contains(a), "a was first in score order (tie broken by index) → selected");
	}

	@Test
	void buildPredecessors_nullDependenciesReturnedByProject_treatedAsNoDeps() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		// Override getDependencies() to return null (some mocked Maven environments do
		// this).
		when(b.getDependencies()).thenReturn(null);
		List<MavenProject> orig = List.of(a, b);
		Map<String, MavenProject> byId = byId(orig);

		// Should not throw; b simply has no predecessors.
		Map<MavenProject, Set<MavenProject>> preds = ReactorReorderer.buildPredecessors(orig, byId);
		assertTrue(preds.get(b).isEmpty(), "null getDependencies() must be treated as empty");
	}

	@Test
	void buildPredecessors_selfDependency_ignored() {
		// A project that declares itself as a dependency (malformed POM, defensive).
		MavenProject a = project("g", "a");
		org.apache.maven.model.Dependency selfDep = new org.apache.maven.model.Dependency();
		selfDep.setGroupId("g");
		selfDep.setArtifactId("a");
		a.getDependencies().add(selfDep);
		List<MavenProject> orig = List.of(a);
		Map<String, MavenProject> byId = byId(orig);

		Map<MavenProject, Set<MavenProject>> preds = ReactorReorderer.buildPredecessors(orig, byId);
		assertTrue(preds.get(a).isEmpty(), "self-dependency must not produce a self-loop");
	}

	@Test
	void buildPredecessors_sameDepViaBothDependencyAndParent_notDuplicated() {
		// b declares a as a <dependency> AND a is b's parent → a appears in b's
		// predecessor set exactly once.
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		addDep(b, a);
		when(b.getParent()).thenReturn(a); // same project as parent
		List<MavenProject> orig = List.of(a, b);
		Map<String, MavenProject> byId = byId(orig);

		Map<MavenProject, Set<MavenProject>> preds = ReactorReorderer.buildPredecessors(orig, byId);
		assertEquals(1, preds.get(b).size(), "a should appear exactly once as b's predecessor");
		assertTrue(preds.get(b).contains(a));
	}

	@Test
	void transitiveRequired_activeNotInReactor_doesNotCrash() {
		// Safety: if an active module isn't in the reactor list (shouldn't happen,
		// but defensive), transitiveRequired must not throw.
		MavenProject a = project("g", "a");
		MavenProject ghost = project("g", "ghost"); // not in reactor
		List<MavenProject> reactor = List.of(a);
		Map<String, MavenProject> byId = byId(reactor);

		// ghost is active but not in reactor; its preds won't be found → just returns
		// {ghost}
		Set<MavenProject> required = ReactorReorderer.transitiveRequired(Set.of(ghost), reactor, byId);
		assertTrue(required.contains(ghost));
		assertFalse(required.contains(a), "a is not a dep of ghost → must not appear");
	}

	@Test
	void longChain_transitiveRequired_traversesAll() {
		// Chain of 10: each depends on the previous. Only the last is active.
		// All 10 must be required.
		List<MavenProject> chain = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			chain.add(project("g", "m" + i));
		}
		for (int i = 1; i < 10; i++) {
			addDep(chain.get(i), chain.get(i - 1));
		}
		Map<String, MavenProject> byId = byId(chain);
		Set<MavenProject> required = ReactorReorderer.transitiveRequired(Set.of(chain.get(9)), chain, byId);
		assertEquals(10, required.size(), "all 10 chain members must be required");
	}
}
