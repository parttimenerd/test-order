package me.bechberger.testorder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import io.jenetics.Genotype;
import io.jenetics.IntegerChromosome;
import io.jenetics.IntegerGene;
import io.jenetics.Optimize;
import io.jenetics.engine.Engine;
import io.jenetics.engine.EvolutionResult;
import io.jenetics.engine.Limits;
import io.jenetics.util.Factory;

final class ScoringOptimizer {

	static final int MAX_GENERATIONS = 2000;
	static final int STEADY_FITNESS_LIMIT = 200;
	static final int POPULATION_SIZE = 150;
	static final double L2_LAMBDA = 0.00002;
	static final double RECENCY_DECAY = 0.15;
	static final double MIN_TRAIN_FRACTION = 0.5;
	static final double OVERFIT_THRESHOLD = 0.85;
	static final int MIN_RUNS_FOR_OPTIMISATION = 3;

	private ScoringOptimizer() {
	}

	static TestOrderState.OptimizeResult optimize(List<TestOrderState.RunRecord> runs, Map<String, Long> durations,
			List<TestOrderState.WeightDef> defs, Logger logger) {
		long runsWithFailures = runs.stream().filter(run -> run.totalFailures() > 0).count();
		List<TestOrderState.RunRecord> withFailures = runs.stream()
				.filter(run -> run.totalFailures() > 0 && !run.outcomes().isEmpty()).toList();
		long skippedOldFormat = runsWithFailures - withFailures.size();
		if (skippedOldFormat > 0) {
			logger.warning("Optimizer skipped " + skippedOldFormat
					+ " run(s) with failures but no outcome data (pre-compact-format). "
					+ "These runs cannot contribute to weight optimisation.");
		}
		if (withFailures.size() < MIN_RUNS_FOR_OPTIMISATION) {
			logger.info("Skipping weight optimisation: only " + withFailures.size()
					+ " qualifying failure run(s), need at least " + MIN_RUNS_FOR_OPTIMISATION);
			return null;
		}

		Map<String, Long> durationSnapshot = Collections.unmodifiableMap(new LinkedHashMap<>(durations));
		int[] defaults = TestOrderState.ScoringWeights.DEFAULT.toArray();

		boolean useExpandingWindow = withFailures.size() >= 5;
		int minTrainSize = Math.max(2, (int) (withFailures.size() * MIN_TRAIN_FRACTION));
		int numFolds = useExpandingWindow ? withFailures.size() - minTrainSize : 0;

		List<IntegerChromosome> chromosomes = defs.stream().map(def -> IntegerChromosome.of(def.min(), def.max()))
				.toList();
		Factory<Genotype<IntegerGene>> genotypeFactory = Genotype.of(chromosomes);

		Engine<IntegerGene, Double> engine = Engine.builder(genotype -> {
			int[] weights = new int[defs.size()];
			for (int i = 0; i < weights.length; i++) {
				weights[i] = genotype.get(i).gene().allele();
			}
			double penalty = l2Penalty(weights, defaults);
			return useExpandingWindow
					? evaluateExpandingWindow(weights, withFailures, durationSnapshot, minTrainSize, numFolds) - penalty
					: evaluateWeights(weights, withFailures, durationSnapshot) - penalty;
		}, genotypeFactory).optimize(Optimize.MAXIMUM).populationSize(POPULATION_SIZE).build();

		Genotype<IntegerGene> best = engine.stream().limit(Limits.bySteadyFitness(STEADY_FITNESS_LIMIT))
				.limit(MAX_GENERATIONS).collect(EvolutionResult.toBestGenotype());

		int[] result = new int[defs.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = best.get(i).gene().allele();
		}
		TestOrderState.ScoringWeights optimized = TestOrderState.ScoringWeights.fromArray(result);

		double trainScore;
		double validationScore;
		boolean overfit = false;
		if (useExpandingWindow) {
			List<TestOrderState.RunRecord> trainRuns = withFailures.subList(0, minTrainSize);
			List<TestOrderState.RunRecord> validationRuns = withFailures.subList(minTrainSize, withFailures.size());
			trainScore = evaluateRecencyWeighted(result, trainRuns, durationSnapshot);
			validationScore = evaluateRecencyWeighted(result, validationRuns, durationSnapshot);
			if (validationScore < trainScore * OVERFIT_THRESHOLD) {
				overfit = true;
			}
		} else {
			trainScore = evaluateWeights(result, withFailures, durationSnapshot);
			validationScore = trainScore;
		}

		if (overfit) {
			logger.warning("Optimizer detected overfitting: train=" + String.format("%.3f", trainScore) + " validation="
					+ String.format("%.3f", validationScore) + ". Falling back to default weights.");
			return new TestOrderState.OptimizeResult(TestOrderState.ScoringWeights.DEFAULT, trainScore, validationScore,
					true, numFolds);
		}

		return new TestOrderState.OptimizeResult(optimized, trainScore, validationScore, false, numFolds);
	}

	static double l2Penalty(int[] weights, int[] defaults) {
		if (weights.length != defaults.length) {
			throw new IllegalArgumentException(
					"Weight/default array length mismatch: " + weights.length + " != " + defaults.length);
		}
		double penalty = 0;
		for (int i = 0; i < weights.length; i++) {
			double diff = weights[i] - defaults[i];
			penalty += diff * diff;
		}
		return L2_LAMBDA * penalty;
	}

	static double evaluateWeights(int[] weights, List<TestOrderState.RunRecord> runs, Map<String, Long> durations) {
		TestOrderState.ScoringWeights scoringWeights = TestOrderState.ScoringWeights.fromArray(weights);
		double sum = 0;
		for (TestOrderState.RunRecord run : runs) {
			sum += APFDCalculator.computeAPFDcWithWeights(run.outcomes(), scoringWeights, durations);
		}
		return sum / runs.size();
	}

	static double evaluateExpandingWindow(int[] weights, List<TestOrderState.RunRecord> runs,
			Map<String, Long> durations, int minTrainSize, int numFolds) {
		TestOrderState.ScoringWeights scoringWeights = TestOrderState.ScoringWeights.fromArray(weights);
		double weightedSum = 0;
		double weightTotal = 0;
		double retain = 1.0 - RECENCY_DECAY;

		for (int fold = 0; fold < numFolds; fold++) {
			int validationIndex = minTrainSize + fold;
			if (validationIndex >= runs.size()) {
				break;
			}
			TestOrderState.RunRecord validationRun = runs.get(validationIndex);
			double foldScore = APFDCalculator.computeAPFDcWithWeights(validationRun.outcomes(), scoringWeights,
					durations);
			double recencyWeight = Math.pow(retain, numFolds - 1 - fold);
			weightedSum += foldScore * recencyWeight;
			weightTotal += recencyWeight;
		}

		return weightTotal > 0 ? weightedSum / weightTotal : 0.0;
	}

	static double evaluateRecencyWeighted(int[] weights, List<TestOrderState.RunRecord> runs,
			Map<String, Long> durations) {
		if (runs.isEmpty()) {
			return 0.0;
		}
		TestOrderState.ScoringWeights scoringWeights = TestOrderState.ScoringWeights.fromArray(weights);
		double weightedSum = 0;
		double weightTotal = 0;
		double retain = 1.0 - RECENCY_DECAY;

		for (int i = 0; i < runs.size(); i++) {
			double score = APFDCalculator.computeAPFDcWithWeights(runs.get(i).outcomes(), scoringWeights, durations);
			double recencyWeight = Math.pow(retain, runs.size() - 1 - i);
			weightedSum += score * recencyWeight;
			weightTotal += recencyWeight;
		}

		return weightTotal > 0 ? weightedSum / weightTotal : 0.0;
	}
}
