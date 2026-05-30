package me.bechberger.testorder.ops.detection;

import java.util.*;

import me.bechberger.testorder.ops.detection.TestRunner.TestRunResult;

/**
 * Algorithm 2: Random Reordering detection. Runs the suite in random orders to
 * discover OD bugs probabilistically.
 */
public class RandomReorderingAlgorithm implements DetectionAlgorithm {

	private final int rounds;

	public RandomReorderingAlgorithm(int rounds) {
		this.rounds = rounds;
	}

	public RandomReorderingAlgorithm() {
		this(100);
	}

	@Override
	public String name() {
		return "random-reordering";
	}

	@Override
	public Set<Prerequisite> prerequisites() {
		return Set.of(Prerequisite.PASSING_REFERENCE);
	}

	@Override
	public int estimatedRuns(int testCount, int conflictEdges) {
		return rounds;
	}

	@Override
	public List<ODResult> detect(DetectionContext ctx) {
		List<ODResult> findings = new ArrayList<>();
		Set<String> knownVictims = new HashSet<>();
		Random rng = new Random(ctx.randomSeed());

		for (int i = 0; i < rounds && !ctx.timeBudgetExhausted(); i++) {
			List<String> shuffled = new ArrayList<>(ctx.referenceOrder());
			Collections.shuffle(shuffled, rng);

			TestRunResult result = ctx.run(shuffled, findings.size());

			for (String failed : result.failedTests()) {
				if (!ctx.passingTests().contains(failed))
					continue;
				if (knownVictims.contains(failed))
					continue;
				knownVictims.add(failed);

				findings.add(new ODResult(failed, ODType.VICTIM, List.of("(polluter unknown)", failed),
						"Failed in random order (round " + (i + 1) + ")", 0.7));
			}
		}

		return findings;
	}
}
