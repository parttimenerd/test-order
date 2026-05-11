package me.bechberger.testorder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class APFDCalculator {

	private APFDCalculator() {
	}

	static double computeAPFD(List<TestOrderState.TestOutcome> orderedOutcomes) {
		int n = orderedOutcomes.size();
		int m = 0;
		double positionSum = 0;
		for (int i = 0; i < n; i++) {
			if (orderedOutcomes.get(i).failed()) {
				m++;
				positionSum += (i + 1);
			}
		}
		if (m == 0 || n == 0) {
			return 1.0;
		}
		return 1.0 - positionSum / ((double) n * m) + 1.0 / (2.0 * n);
	}

	static double computeAPFDc(List<TestOrderState.TestOutcome> orderedOutcomes, Map<String, Long> durations) {
		int n = orderedOutcomes.size();
		if (n == 0) {
			return 1.0;
		}

		double[] costs = new double[n];
		boolean hasCosts = false;
		for (int i = 0; i < n; i++) {
			Long duration = durations.get(orderedOutcomes.get(i).testClass());
			if (duration != null && duration > 0) {
				costs[i] = duration;
				hasCosts = true;
			} else {
				costs[i] = 1.0;
			}
		}
		if (!hasCosts) {
			return computeAPFD(orderedOutcomes);
		}

		double totalCost = 0;
		for (double cost : costs) {
			totalCost += cost;
		}

		int failures = 0;
		double weightedSum = 0;
		double cumulativeCost = 0;
		for (int i = 0; i < n; i++) {
			cumulativeCost += costs[i];
			if (orderedOutcomes.get(i).failed()) {
				failures++;
				weightedSum += cumulativeCost - 0.5 * costs[i];
			}
		}
		if (failures == 0 || totalCost <= 0) {
			return 1.0;
		}
		return 1.0 - weightedSum / (totalCost * failures);
	}

	static double computeAPFDWithWeights(List<TestOrderState.TestOutcome> outcomes,
			TestOrderState.ScoringWeights weights) {
		List<TestOrderState.TestOutcome> reordered = new ArrayList<>(outcomes);
		reordered.sort(reorderComparator(weights));
		return computeAPFD(reordered);
	}

	static double computeAPFDcWithWeights(List<TestOrderState.TestOutcome> outcomes,
			TestOrderState.ScoringWeights weights, Map<String, Long> durations) {
		List<TestOrderState.TestOutcome> reordered = new ArrayList<>(outcomes);
		reordered.sort(reorderComparator(weights));
		return computeAPFDc(reordered, durations);
	}

	static Comparator<TestOrderState.TestOutcome> reorderComparator(TestOrderState.ScoringWeights weights) {
		return Comparator.comparingDouble((TestOrderState.TestOutcome outcome) -> {
			double score = 0;
			if (outcome.isNew()) {
				score += weights.newTest();
			}
			if (outcome.isChanged()) {
				score += weights.changedTest();
			}
			if (outcome.failScore() > 0) {
				score += Math.min(Math.ceil(outcome.failScore()), weights.maxFailure());
			}

			if (outcome.speedRatio() != 0.0) {
				double speedRatio = outcome.speedRatio();
				if (speedRatio <= 0) {
					score += (-speedRatio) * weights.speed();
				} else {
					score -= speedRatio * weights.speedPenalty();
				}
			} else {
				if (outcome.isFast()) {
					score += weights.speed();
				} else if (outcome.isSlow()) {
					score -= weights.speedPenalty();
				}
			}

			if (weights.coverageBonus() > 0) {
				score += TestScorer.depOverlapScore(outcome.depOverlap(), outcome.depTotal(), weights.coverageBonus());
			} else {
				score += TestScorer.depOverlapScore(outcome.depOverlap(), outcome.depTotal(), weights.depOverlap());
				score += TestScorer.complexityScore(outcome.complexityOverlap(), outcome.depTotal(),
						weights.changeComplexity());
			}

			if (outcome.hasStaticFieldOverlap()) {
				score += weights.staticFieldBonus();
			}
			return score;
		}).reversed();
	}
}
