package me.bechberger.testorder.ops.detection;

import java.util.*;
import java.util.stream.Collectors;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.ops.detection.ConflictGraph.ConflictEdge;

/**
 * Builds a conflict graph from DependencyMap data. Two tests are connected by
 * an edge if they share access to the same mutable fields.
 */
public final class ConflictGraphBuilder {

	private ConflictGraphBuilder() {
	}

	/**
	 * Build the conflict graph from available dependency data.
	 *
	 * @param depMap
	 *            dependency map with member-level or class-level data
	 * @param state
	 *            run history for intermittency boosting
	 * @param testClasses
	 *            tests to consider
	 * @return conflict graph with weighted edges
	 */
	public static ConflictGraph build(DependencyMap depMap, TestOrderState state, List<String> testClasses) {
		if (depMap == null) {
			return ConflictGraph.empty();
		}

		List<ConflictEdge> edges = new ArrayList<>();
		Set<String> intermittent = findIntermittentTests(state);

		if (depMap.hasMemberDeps()) {
			edges = buildFromMemberDeps(depMap, testClasses, intermittent);
		} else {
			edges = buildFromClassDeps(depMap, testClasses, intermittent);
		}

		edges.sort(Comparator.naturalOrder());
		return new ConflictGraph(edges);
	}

	private static List<ConflictEdge> buildFromMemberDeps(DependencyMap depMap, List<String> testClasses,
			Set<String> intermittent) {
		// Build inverted index: member → set of tests accessing it
		Map<String, Set<String>> memberToTests = new HashMap<>();
		for (String test : testClasses) {
			Set<String> members = depMap.getMemberDeps(test);
			if (members == null)
				continue;
			for (String member : members) {
				// Only consider fields likely to be mutable (heuristic: not final-like)
				if (isLikelyMutableField(member)) {
					memberToTests.computeIfAbsent(member, k -> new HashSet<>()).add(test);
				}
			}
		}

		// Build edges from shared members
		Map<String, ConflictEdge> edgeMap = new HashMap<>();
		for (Map.Entry<String, Set<String>> entry : memberToTests.entrySet()) {
			Set<String> tests = entry.getValue();
			if (tests.size() < 2)
				continue;

			List<String> testList = new ArrayList<>(tests);
			for (int i = 0; i < testList.size(); i++) {
				for (int j = i + 1; j < testList.size(); j++) {
					String a = testList.get(i);
					String b = testList.get(j);
					String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
					String edgeA = a.compareTo(b) < 0 ? a : b;
					String edgeB = a.compareTo(b) < 0 ? b : a;

					edgeMap.compute(key, (k, existing) -> {
						Set<String> shared = existing != null
								? new HashSet<>(existing.sharedMembers())
								: new HashSet<>();
						shared.add(entry.getKey());
						double weight = computeWeight(shared.size(), intermittent, edgeA, edgeB);
						return new ConflictEdge(edgeA, edgeB, shared, weight);
					});
				}
			}
		}

		return new ArrayList<>(edgeMap.values());
	}

	private static List<ConflictEdge> buildFromClassDeps(DependencyMap depMap, List<String> testClasses,
			Set<String> intermittent) {
		// Build inverted index: app class → set of tests depending on it
		Map<String, Set<String>> classToTests = new HashMap<>();
		for (String test : testClasses) {
			Set<String> deps = depMap.get(test);
			if (deps == null)
				continue;
			for (String dep : deps) {
				classToTests.computeIfAbsent(dep, k -> new HashSet<>()).add(test);
			}
		}

		// Build edges — require ≥3 shared classes for class-level (weaker signal)
		Map<String, Integer> sharedCount = new HashMap<>();
		Map<String, ConflictEdge> edgeMap = new HashMap<>();

		for (Map.Entry<String, Set<String>> entry : classToTests.entrySet()) {
			Set<String> tests = entry.getValue();
			if (tests.size() < 2 || tests.size() > testClasses.size() / 2)
				continue;
			// Skip overly common classes (utility classes)

			List<String> testList = new ArrayList<>(tests);
			for (int i = 0; i < testList.size(); i++) {
				for (int j = i + 1; j < testList.size(); j++) {
					String a = testList.get(i);
					String b = testList.get(j);
					String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
					sharedCount.merge(key, 1, Integer::sum);
				}
			}
		}

		// Only create edges with ≥3 shared classes
		for (Map.Entry<String, Integer> entry : sharedCount.entrySet()) {
			if (entry.getValue() < 3)
				continue;
			String[] parts = entry.getKey().split("\\|");
			double weight = computeWeight(entry.getValue(), intermittent, parts[0], parts[1]);
			edgeMap.put(entry.getKey(), new ConflictEdge(parts[0], parts[1], Set.of(), weight));
		}

		return new ArrayList<>(edgeMap.values());
	}

	private static double computeWeight(int sharedCount, Set<String> intermittent, String testA, String testB) {
		// Base weight from shared count (diminishing returns)
		double weight = 1.0 - 1.0 / (1.0 + sharedCount * 0.5);

		// Boost if either test is intermittent (historical OD signal)
		if (intermittent.contains(testA) || intermittent.contains(testB)) {
			weight = Math.min(1.0, weight + 0.3);
		}

		return weight;
	}

	private static Set<String> findIntermittentTests(TestOrderState state) {
		if (state == null || state.runs().size() < 3)
			return Set.of();

		// Tests that have both passed and failed across different runs
		Map<String, boolean[]> outcomes = new HashMap<>();
		for (TestOrderState.RunRecord run : state.runs()) {
			for (TestOrderState.TestOutcome outcome : run.outcomes()) {
				outcomes.computeIfAbsent(outcome.testClass(), k -> new boolean[] { false, false });
				boolean[] flags = outcomes.get(outcome.testClass());
				if (outcome.failed())
					flags[0] = true;
				else
					flags[1] = true;
			}
		}

		return outcomes.entrySet().stream().filter(e -> e.getValue()[0] && e.getValue()[1]).map(Map.Entry::getKey)
				.collect(Collectors.toSet());
	}

	private static boolean isLikelyMutableField(String member) {
		// member format: "com.example.Class#fieldName"
		// Heuristic: skip fields named with ALL_CAPS (likely constants, e.g. MAX_SIZE)
		int hash = member.lastIndexOf('#');
		if (hash < 0)
			return true;
		String field = member.substring(hash + 1);
		return !field.equals(field.toUpperCase(Locale.ROOT));
	}
}
