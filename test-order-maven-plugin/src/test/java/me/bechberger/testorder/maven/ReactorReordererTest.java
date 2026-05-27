package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
}
