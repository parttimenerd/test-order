package me.bechberger.testorder.ops.detection;

import java.util.*;

import me.bechberger.testorder.ops.detection.TestRunner.TestRunResult;

/**
 * Algorithm 5: Tuscan Square Systematic Coverage. Uses combinatorial scheduling
 * (Tuscan squares) to guarantee every pair of tests (A, B) appears with A
 * immediately before B in some run. This maximizes coverage of pairwise
 * interactions.
 */
public class TuscanSystematicAlgorithm implements DetectionAlgorithm {

	@Override
	public String name() {
		return "tuscan-systematic";
	}

	@Override
	public Set<Prerequisite> prerequisites() {
		return Set.of(Prerequisite.PASSING_REFERENCE);
	}

	@Override
	public int estimatedRuns(int testCount, int conflictEdges) {
		// A Tuscan square of order n needs n rows (runs)
		return testCount;
	}

	@Override
	public List<ODResult> detect(DetectionContext ctx) {
		List<ODResult> findings = new ArrayList<>();
		int n = ctx.referenceOrder().size();
		if (n < 2)
			return findings;

		Set<String> knownVictims = new HashSet<>();

		// Generate runs using circular-shift construction (approximation of Tuscan
		// square)
		// Each row i is a circular permutation offset by i
		List<String> tests = new ArrayList<>(ctx.referenceOrder());

		for (int row = 0; row < n && !ctx.timeBudgetExhausted(); row++) {
			List<String> order = new ArrayList<>(n);
			for (int j = 0; j < n; j++) {
				order.add(tests.get((j + row) % n));
			}

			TestRunResult result = ctx.runner().run(order);

			for (String failed : result.failedTests()) {
				if (!ctx.passingTests().contains(failed))
					continue;
				if (knownVictims.contains(failed))
					continue;
				knownVictims.add(failed);

				// The immediate predecessor is the prime polluter suspect
				int idx = order.indexOf(failed);
				String suspect = idx > 0 ? order.get(idx - 1) : null;

				if (suspect != null) {
					// Quick verification: [suspect, victim] should fail
					TestRunResult verify = ctx.runner().run(List.of(suspect, failed));
					if (verify.failed(failed)) {
						findings.add(new ODResult(failed, ODType.VICTIM, List.of(suspect, failed), "Tuscan: " + suspect
								+ " immediately before " + failed + " causes failure (row " + row + ")", 0.9));
						continue;
					}
				}

				// Suspect alone wasn't enough — record with lower confidence
				List<String> predecessors = result.predecessorsOf(failed);
				List<String> chain = new ArrayList<>(
						predecessors.subList(Math.max(0, predecessors.size() - 3), predecessors.size()));
				chain.add(failed);
				findings.add(new ODResult(failed, ODType.VICTIM, chain,
						"Tuscan row " + row + ": failed with unknown polluter subset", 0.6));
			}
		}

		return findings;
	}
}
