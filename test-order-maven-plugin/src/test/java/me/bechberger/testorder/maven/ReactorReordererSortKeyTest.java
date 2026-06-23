package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

/**
 * Sort-key tests for {@link ReactorReorderer#reorder} that exercise distinct
 * values for {@code affectedTestCount}, {@code sumTestScores}, and
 * {@code maxTestScore} — the existing {@code ReactorReordererTest} fixtures
 * collapse all three into one number, so they don't pin down the priority order
 * between them.
 */
class ReactorReordererSortKeyTest {

	private static MavenProject project(String gid, String aid) {
		MavenProject p = mock(MavenProject.class);
		when(p.getGroupId()).thenReturn(gid);
		when(p.getArtifactId()).thenReturn(aid);
		when(p.getDependencies()).thenReturn(new ArrayList<>());
		when(p.getParent()).thenReturn(null);
		return p;
	}

	private static ModuleScore score(String mid, int affected, long sum, int max) {
		return new ModuleScore(mid, max, sum, affected, affected, List.of());
	}

	@Test
	void affectedCountIsPrimary_evenWhenSumWouldFlipOrder() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		List<MavenProject> orig = List.of(a, b);

		Map<String, ModuleScore> scores = new HashMap<>();
		// b has higher sum & max but fewer affected tests — affected wins under the new
		// key
		scores.put("g:a", score("g:a", 10, 100, 20));
		scores.put("g:b", score("g:b", 5, 999, 99));

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, null);
		assertSame(a, r.ordered().get(0), "module with higher affectedTestCount must come first");
	}

	@Test
	void sumIsTiebreaker_whenAffectedCountEqual() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		List<MavenProject> orig = List.of(a, b);

		Map<String, ModuleScore> scores = new HashMap<>();
		// Equal affected count; b has higher sum
		scores.put("g:a", score("g:a", 5, 100, 99));
		scores.put("g:b", score("g:b", 5, 200, 50));

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, null);
		assertSame(b, r.ordered().get(0), "module with higher sum must come first when affected counts tie");
	}

	@Test
	void maxIsFinalTiebreaker_whenAffectedAndSumEqual() {
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		List<MavenProject> orig = List.of(a, b);

		Map<String, ModuleScore> scores = new HashMap<>();
		// Equal affected and equal sum; b has higher max
		scores.put("g:a", score("g:a", 5, 200, 50));
		scores.put("g:b", score("g:b", 5, 200, 80));

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, null);
		assertSame(b, r.ordered().get(0), "module with higher max must come first when affected and sum tie");
	}

	@Test
	void dagConstraintBeatsScore() {
		// b depends on a → a must run first even when b has higher affectedTestCount
		MavenProject a = project("g", "a");
		MavenProject b = project("g", "b");
		Dependency d = new Dependency();
		d.setGroupId(a.getGroupId());
		d.setArtifactId(a.getArtifactId());
		b.getDependencies().add(d);
		List<MavenProject> orig = List.of(a, b);

		Map<String, ModuleScore> scores = new HashMap<>();
		scores.put("g:a", score("g:a", 1, 10, 5));
		scores.put("g:b", score("g:b", 100, 999, 99));

		ReactorReorderer.ReorderResult r = ReactorReorderer.reorder(orig, scores, null);
		assertEquals(List.of(a, b), r.ordered(), "dependency order must be preserved regardless of score");
	}
}
