package me.bechberger.testorder.maven;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;

import me.bechberger.testorder.ops.ReactorOrderOperation.ModuleScore;

/**
 * Reorders a Maven reactor so that modules with affected tests run first,
 * subject to the original dependency ordering. Optionally caps the active set
 * so that cumulative {@code affectedTestCount} does not exceed {@code topN}.
 *
 * <p>
 * Pure logic — no Maven I/O — to keep it unit-testable.
 */
public final class ReactorReorderer {

	private ReactorReorderer() {
	}

	public record ReorderResult(List<MavenProject> ordered, Set<MavenProject> deferred, int cumulativeAffected,
			int activeModules, int deferredModules, Set<MavenProject> active, Map<String, MavenProject> projectsById) {
	}

	/**
	 * @param original
	 *            reactor projects in their original (Maven-computed) order
	 * @param scoreById
	 *            moduleId → score (modules absent from this map are treated as
	 *            zero-score)
	 * @param topN
	 *            cap on cumulative affectedTestCount; null or non-positive means
	 *            uncapped
	 */
	public static ReorderResult reorder(List<MavenProject> original, Map<String, ModuleScore> scoreById, Integer topN) {
		if (original == null || original.isEmpty()) {
			return new ReorderResult(List.of(), Set.of(), 0, 0, 0, Set.of(), Map.of());
		}

		Map<String, MavenProject> projectsById = new HashMap<>();
		Map<MavenProject, Integer> originalIndex = new HashMap<>();
		for (int i = 0; i < original.size(); i++) {
			MavenProject p = original.get(i);
			projectsById.put(ModuleIds.of(p), p);
			originalIndex.put(p, i);
		}

		// Pick the active set: walk modules in score-sorted order, accumulate affected
		// tests
		// until topN reached. Modules with affectedTestCount == 0 never enter the
		// active set.
		List<MavenProject> sortedByScore = new ArrayList<>(original);
		sortedByScore.sort((a, b) -> {
			ModuleScore sa = scoreById.get(ModuleIds.of(a));
			ModuleScore sb = scoreById.get(ModuleIds.of(b));
			int cmp = Integer.compare(score(sb).affectedTestCount(), score(sa).affectedTestCount());
			if (cmp != 0)
				return cmp;
			cmp = Long.compare(score(sb).sumTestScores(), score(sa).sumTestScores());
			if (cmp != 0)
				return cmp;
			cmp = Integer.compare(score(sb).maxTestScore(), score(sa).maxTestScore());
			if (cmp != 0)
				return cmp;
			return Integer.compare(originalIndex.get(a), originalIndex.get(b));
		});

		Set<MavenProject> active = new LinkedHashSet<>();
		int cumulative = 0;
		int cap = (topN != null && topN > 0) ? topN : Integer.MAX_VALUE;
		for (MavenProject p : sortedByScore) {
			ModuleScore s = scoreById.get(ModuleIds.of(p));
			if (s == null || s.affectedTestCount() == 0) {
				continue;
			}
			if (cumulative >= cap) {
				break;
			}
			active.add(p);
			cumulative += s.affectedTestCount();
		}

		// Dependency-safe ordering: stable topological sort with priority key
		// (active-first, then score-rank, then original index).
		// We do this in two passes:
		// 1) compute priority per project
		// 2) Kahn-style emit: at each step pick the eligible project with the
		// best priority key
		Map<MavenProject, Integer> priorityRank = new HashMap<>();
		// Active projects in score order get ranks [0..k-1]; deferred get ranks
		// [k..n-1] in original order.
		int rank = 0;
		List<MavenProject> activeInScoreOrder = new ArrayList<>();
		for (MavenProject p : sortedByScore) {
			if (active.contains(p))
				activeInScoreOrder.add(p);
		}
		for (MavenProject p : activeInScoreOrder) {
			priorityRank.put(p, rank++);
		}
		for (MavenProject p : original) {
			if (!priorityRank.containsKey(p)) {
				priorityRank.put(p, rank++);
			}
		}

		// Build reactor-internal predecessor map: for each project, the set of other
		// reactor projects it depends on (via Maven <dependency>, parent, or
		// <module> declarations Maven already encoded in the original order).
		Map<MavenProject, Set<MavenProject>> predecessors = buildPredecessors(original, projectsById);

		// Kahn with priority-queue tie-break
		Map<MavenProject, Integer> remaining = new HashMap<>();
		for (Map.Entry<MavenProject, Set<MavenProject>> e : predecessors.entrySet()) {
			remaining.put(e.getKey(), e.getValue().size());
		}
		Map<MavenProject, List<MavenProject>> successors = new HashMap<>();
		for (Map.Entry<MavenProject, Set<MavenProject>> e : predecessors.entrySet()) {
			MavenProject child = e.getKey();
			for (MavenProject parent : e.getValue()) {
				successors.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
			}
		}

		java.util.PriorityQueue<MavenProject> ready = new java.util.PriorityQueue<>(
				(a, b) -> Integer.compare(priorityRank.get(a), priorityRank.get(b)));
		for (MavenProject p : original) {
			if (remaining.get(p) == 0) {
				ready.add(p);
			}
		}

		List<MavenProject> ordered = new ArrayList<>(original.size());
		while (!ready.isEmpty()) {
			MavenProject next = ready.poll();
			ordered.add(next);
			List<MavenProject> kids = successors.getOrDefault(next, List.of());
			for (MavenProject child : kids) {
				int r = remaining.get(child) - 1;
				remaining.put(child, r);
				if (r == 0) {
					ready.add(child);
				}
			}
		}

		// Defensive: if the topo sort was incomplete (shouldn't happen — Maven
		// already validated the reactor DAG), fall back to original order.
		if (ordered.size() != original.size()) {
			return new ReorderResult(new ArrayList<>(original), Set.of(), cumulative, active.size(),
					original.size() - active.size(), Collections.unmodifiableSet(active),
					Collections.unmodifiableMap(projectsById));
		}

		Set<MavenProject> deferred = new HashSet<>(original);
		deferred.removeAll(active);

		return new ReorderResult(Collections.unmodifiableList(ordered), Collections.unmodifiableSet(deferred),
				cumulative, active.size(), deferred.size(), Collections.unmodifiableSet(active),
				Collections.unmodifiableMap(projectsById));
	}

	private static ModuleScore score(ModuleScore s) {
		return s != null ? s : new ModuleScore("", 0, 0L, 0, 0, List.of());
	}

	/**
	 * Returns the set of reactor projects that are transitively required by any
	 * project in {@code active}: i.e., active projects themselves plus all their
	 * transitive compile-time predecessors. Any project NOT in the returned set can
	 * have its entire build (compile, test-compile, enforcer, etc.) skipped without
	 * breaking the active modules.
	 */
	static Set<MavenProject> transitiveRequired(Set<MavenProject> active, List<MavenProject> reactor,
			Map<String, MavenProject> byId) {
		Map<MavenProject, Set<MavenProject>> preds = buildPredecessors(reactor, byId);
		Set<MavenProject> required = new HashSet<>(active);
		java.util.ArrayDeque<MavenProject> queue = new java.util.ArrayDeque<>(active);
		while (!queue.isEmpty()) {
			MavenProject p = queue.poll();
			for (MavenProject dep : preds.getOrDefault(p, Set.of())) {
				if (required.add(dep)) {
					queue.add(dep);
				}
			}
		}
		return required;
	}

	// Package-private for unit testing.
	static Map<MavenProject, Set<MavenProject>> buildPredecessors(List<MavenProject> reactor,
			Map<String, MavenProject> byId) {
		Map<MavenProject, Set<MavenProject>> map = new HashMap<>();
		for (MavenProject p : reactor) {
			map.put(p, new HashSet<>());
		}
		for (MavenProject p : reactor) {
			Set<MavenProject> preds = map.getOrDefault(p, java.util.Set.of());
			// declared <dependency> entries that resolve to another reactor module
			List<Dependency> deps = p.getDependencies();
			if (deps != null) {
				for (Dependency d : deps) {
					String id = ModuleIds.of(d.getGroupId(), d.getArtifactId());
					MavenProject other = byId.get(id);
					if (other != null && other != p) {
						preds.add(other);
					}
				}
			}
			// parent in the same reactor
			MavenProject parent = p.getParent();
			if (parent != null) {
				MavenProject reactorParent = byId.get(ModuleIds.of(parent.getGroupId(), parent.getArtifactId()));
				if (reactorParent != null && reactorParent != p) {
					preds.add(reactorParent);
				}
			}
		}
		return map;
	}
}
