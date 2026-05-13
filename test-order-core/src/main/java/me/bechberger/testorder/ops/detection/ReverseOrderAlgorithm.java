package me.bechberger.testorder.ops.detection;

import java.util.*;

import me.bechberger.testorder.ops.detection.TestRunner.TestRunResult;

/**
 * Algorithm 3: Reverse Order detection. Runs the test suite in reverse order —
 * cheapest possible detection (1 run). DTDetector reports ~66% of OD bugs are
 * detected by a single reverse run.
 */
public class ReverseOrderAlgorithm implements DetectionAlgorithm {

	@Override
	public String name() {
		return "reverse-order";
	}

	@Override
	public Set<Prerequisite> prerequisites() {
		return Set.of(Prerequisite.PASSING_REFERENCE);
	}

	@Override
	public int estimatedRuns(int testCount, int conflictEdges) {
		return 1;
	}

	@Override
	public List<ODResult> detect(DetectionContext ctx) {
		List<String> reversed = new ArrayList<>(ctx.referenceOrder());
		Collections.reverse(reversed);

		TestRunResult result = ctx.runner().run(reversed);
		List<ODResult> findings = new ArrayList<>();

		for (String failed : result.failedTests()) {
			if (!ctx.passingTests().contains(failed))
				continue;
			// This test passed in reference order but failed in reverse → OD candidate

			// Attempt to isolate the polluter via delta debugging
			List<String> predecessors = result.predecessorsOf(failed);
			if (!predecessors.isEmpty() && !ctx.timeBudgetExhausted()) {
				List<String> minimal = DeltaDebugging.minimize(predecessors, failed, ctx.runner(), 20);
				if (!minimal.isEmpty()) {
					String polluter = minimal.get(0);
					findings.add(new ODResult(failed, ODType.VICTIM, buildChain(minimal, failed),
							"Reverse order: " + polluter + " pollutes " + failed, 0.95));
					continue;
				}
			}

			// Fallback: polluter unknown but still report as candidate
			findings.add(new ODResult(failed, ODType.VICTIM, List.of(failed),
					"Failed in reverse order — OD candidate (polluter could not be isolated)", 0.6));
		}

		return findings;
	}

	private static List<String> buildChain(List<String> polluters, String victim) {
		List<String> chain = new ArrayList<>(polluters);
		chain.add(victim);
		return chain;
	}
}
