package me.bechberger.testorder.ops.detection;

import java.util.*;

import me.bechberger.testorder.ops.detection.ConflictGraph.ConflictEdge;
import me.bechberger.testorder.ops.detection.TestRunner.TestRunResult;

/**
 * Algorithm 1: PRADET-style Iterative Refinement. Uses the conflict graph to
 * batch-test edge clusters, then narrows with ddmin.
 */
public class IterativeRefinementAlgorithm implements DetectionAlgorithm {

	@Override
	public String name() {
		return "iterative-refinement";
	}

	@Override
	public Set<Prerequisite> prerequisites() {
		return Set.of(Prerequisite.DEPENDENCY_MAP, Prerequisite.PASSING_REFERENCE);
	}

	@Override
	public int estimatedRuns(int testCount, int conflictEdges) {
		if (conflictEdges == 0)
			return 0;
		int clusterSize = Math.max(3, (int) Math.sqrt(conflictEdges));
		return 1 + conflictEdges / clusterSize + 3; // clusters + ddmin overhead
	}

	@Override
	public List<ODResult> detect(DetectionContext ctx) {
		List<ODResult> findings = new ArrayList<>();
		if (ctx.graph() == null || ctx.graph().isEmpty())
			return findings;

		List<ConflictEdge> edges = new ArrayList<>(ctx.graph().edges());
		edges.sort(Comparator.naturalOrder()); // highest weight first

		int clusterSize = Math.max(3, (int) Math.sqrt(edges.size()));
		List<List<ConflictEdge>> clusters = partition(edges, clusterSize);

		for (List<ConflictEdge> cluster : clusters) {
			if (ctx.timeBudgetExhausted())
				break;

			// Generate an order that violates all edges in the cluster
			List<String> order = generateViolation(cluster, ctx.referenceOrder());
			TestRunResult result = ctx.run(order, findings.size());

			if (result.allPassed())
				continue; // Cluster is benign

			// Something failed — isolate which edge(s) caused it
			for (String failed : result.failedTests()) {
				if (!ctx.passingTests().contains(failed))
					continue;

				// Use ddmin to find minimal polluter among predecessors
				List<String> predecessors = result.predecessorsOf(failed);
				if (predecessors.isEmpty())
					continue;

				List<String> minimalPolluters = DeltaDebugging.minimize(predecessors, failed, ctx.runner(), 10);

				if (!minimalPolluters.isEmpty()) {
					// Verify: does victim pass alone?
					TestRunResult isolation = ctx.run(List.of(failed), findings.size());
					if (isolation.passed(failed)) {
						findings.add(new ODResult(failed, ODType.VICTIM, buildChain(minimalPolluters, failed),
								"Iterative refinement: " + minimalPolluters + " → " + failed, 0.95));
					}
				}
			}
		}

		return findings;
	}

	private List<String> generateViolation(List<ConflictEdge> cluster, List<String> reference) {
		// Strategy: for each edge (A, B), ensure B comes before A (reverse their
		// relative order)
		Set<String> involved = new LinkedHashSet<>();
		Map<String, Integer> refIndex = new HashMap<>();
		for (int i = 0; i < reference.size(); i++) {
			refIndex.put(reference.get(i), i);
		}

		for (ConflictEdge edge : cluster) {
			involved.add(edge.testA());
			involved.add(edge.testB());
		}

		// Place involved tests in reverse reference order, then others in reference
		// order
		List<String> order = new ArrayList<>();
		List<String> involvedSorted = new ArrayList<>(involved);
		involvedSorted.sort((a, b) -> refIndex.getOrDefault(b, 0) - refIndex.getOrDefault(a, 0));
		order.addAll(involvedSorted);

		for (String test : reference) {
			if (!involved.contains(test)) {
				order.add(test);
			}
		}

		return order;
	}

	private List<String> buildChain(List<String> polluters, String victim) {
		List<String> chain = new ArrayList<>(polluters);
		chain.add(victim);
		return chain;
	}

	private static <T> List<List<T>> partition(List<T> list, int size) {
		List<List<T>> parts = new ArrayList<>();
		for (int i = 0; i < list.size(); i += size) {
			parts.add(new ArrayList<>(list.subList(i, Math.min(i + size, list.size()))));
		}
		return parts;
	}
}
