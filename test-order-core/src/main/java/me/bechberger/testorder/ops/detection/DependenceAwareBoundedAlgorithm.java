package me.bechberger.testorder.ops.detection;

import java.util.*;
import java.util.stream.Collectors;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.ops.detection.TestRunner.TestRunResult;

/**
 * Algorithm 4: Dependence-Aware Bounded detection. Uses dependency information
 * to bound the search space for each test, only checking potential polluters
 * that share dependencies.
 */
public class DependenceAwareBoundedAlgorithm implements DetectionAlgorithm {

	@Override
	public String name() {
		return "dependence-aware-bounded";
	}

	@Override
	public Set<Prerequisite> prerequisites() {
		return Set.of(Prerequisite.DEPENDENCY_MAP, Prerequisite.PASSING_REFERENCE);
	}

	@Override
	public int estimatedRuns(int testCount, int conflictEdges) {
		// Bounded by the number of high-weight edges + verification runs
		return Math.min(conflictEdges, testCount);
	}

	@Override
	public List<ODResult> detect(DetectionContext ctx) {
		List<ODResult> findings = new ArrayList<>();
		if (ctx.depMap() == null)
			return findings;

		for (String victim : ctx.passingTests()) {
			if (ctx.timeBudgetExhausted())
				break;

			// Find tests that share dependencies with the victim
			Set<String> victimDeps = ctx.depMap().get(victim);
			if (victimDeps == null || victimDeps.isEmpty())
				continue;

			List<String> candidates = new ArrayList<>();
			for (String other : ctx.allTests()) {
				if (other.equals(victim))
					continue;
				Set<String> otherDeps = ctx.depMap().get(other);
				if (otherDeps == null)
					continue;

				// Check for overlap
				boolean overlaps = otherDeps.stream().anyMatch(victimDeps::contains);
				if (overlaps) {
					candidates.add(other);
				}
			}

			if (candidates.isEmpty())
				continue;

			// Verify victim passes alone
			TestRunResult isolation = ctx.runner().run(List.of(victim));
			if (!isolation.passed(victim))
				continue; // Not a victim, just flaky

			// Try candidates in an order that should pollute
			List<String> testOrder = new ArrayList<>(candidates);
			testOrder.add(victim);
			TestRunResult result = ctx.runner().run(testOrder);

			if (result.passed(victim))
				continue; // No pollution

			// ddmin to find minimal polluter set
			List<String> minimal = DeltaDebugging.minimize(candidates, victim, ctx.runner(), 15);

			if (!minimal.isEmpty()) {
				findings.add(new ODResult(victim, ODType.VICTIM, buildChain(minimal, victim),
						"Dep-aware bounded: found via shared deps "
								+ victimDeps.stream().limit(3).collect(Collectors.joining(", ")),
						0.95));
			}
		}

		return findings;
	}

	private List<String> buildChain(List<String> polluters, String victim) {
		List<String> chain = new ArrayList<>(polluters);
		chain.add(victim);
		return chain;
	}
}
