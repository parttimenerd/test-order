package me.bechberger.testorder.maven.ml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.tribuo.MutableDataset;
import org.tribuo.Prediction;
import org.tribuo.classification.Label;
import org.tribuo.classification.LabelFactory;
import org.tribuo.classification.sgd.linear.LogisticRegressionTrainer;
import org.tribuo.impl.ArrayExample;
import org.tribuo.provenance.SimpleDataSourceProvenance;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderLogger;
import me.bechberger.testorder.ml.CoFailureTracker;
import me.bechberger.testorder.ml.MLFeatureExtractor;
import me.bechberger.testorder.ml.MLHistoryPersistence;
import me.bechberger.testorder.ml.MLRunRecord;

/**
 * Failure prediction using Tribuo (Apache 2.0) logistic regression.
 * <p>
 * This is the only class with a Tribuo dependency — all feature extraction and
 * data management lives in test-order-core's {@code ml} package.
 * <p>
 * Training: loads ML history, extracts temporally-correct feature vectors via
 * {@link MLFeatureExtractor#extractTrainingExamples}, and batch-trains a
 * logistic regression model.
 * <p>
 * Prediction: for each test class, extracts features and returns P(fail).
 */
public final class TestFailurePredictor {

	/** Minimum number of historical runs before the model produces predictions. */
	private static final int MIN_HISTORY_RUNS = 5;

	/** Class imbalance weight multiplier for the minority class (failures). */
	private static final float FAILURE_WEIGHT = 5.0f;

	private TestFailurePredictor() {
	}

	/**
	 * Trains a model on the full ML history and returns failure probability
	 * predictions for the given test classes.
	 *
	 * @param historyFile
	 *            path to the ML history LZ4 file
	 * @param depMap
	 *            dependency map for feature extraction
	 * @param currentChanged
	 *            production classes changed in the current diff
	 * @param currentChangedTests
	 *            test classes changed in the current diff
	 * @param testClasses
	 *            test classes to predict for
	 * @return map from test class name to P(fail), or empty if insufficient history
	 */
	public static Map<String, Double> trainAndPredict(Path historyFile, DependencyMap depMap,
			Set<String> currentChanged, Set<String> currentChangedTests, Set<String> testClasses) throws IOException {
		List<MLRunRecord> history = MLHistoryPersistence.load(historyFile);
		if (history.size() < MIN_HISTORY_RUNS) {
			TestOrderLogger.debug("[ml] Insufficient history ({} runs, need {}), skipping ML predictions",
					history.size(), MIN_HISTORY_RUNS);
			return Map.of();
		}

		// Phase 1: Extract temporally-correct training examples (in core)
		List<MLFeatureExtractor.TrainingExample> trainingData = MLFeatureExtractor.extractTrainingExamples(history,
				depMap);
		if (trainingData.isEmpty()) {
			return Map.of();
		}

		// Phase 2: Train logistic regression via Tribuo (Apache 2.0)
		LabelFactory labelFactory = new LabelFactory();
		var provenance = new SimpleDataSourceProvenance("test-order-ml", labelFactory);
		MutableDataset<Label> dataset = new MutableDataset<>(provenance, labelFactory);

		Label passLabel = labelFactory.generateOutput("pass");
		Label failLabel = labelFactory.generateOutput("fail");

		for (MLFeatureExtractor.TrainingExample ex : trainingData) {
			Label label = ex.failed() ? failLabel : passLabel;
			var example = new ArrayExample<>(label, MLFeatureExtractor.FEATURE_NAMES, ex.features());
			if (ex.failed()) {
				example.setWeight(FAILURE_WEIGHT);
			}
			dataset.add(example);
		}

		var trainer = new LogisticRegressionTrainer();
		var model = trainer.train(dataset);

		TestOrderLogger.debug("[ml] Trained model on {} examples ({} features)", trainingData.size(),
				MLFeatureExtractor.FEATURE_COUNT);

		// Phase 3: Predict P(fail) for current test classes
		Map<String, MLFeatureExtractor.TestStats> stats = MLFeatureExtractor.buildStats(history);
		CoFailureTracker coFailure = MLFeatureExtractor.buildCoFailureTracker(history);

		Map<String, Double> predictions = new HashMap<>();
		for (String testClass : testClasses) {
			double[] features = MLFeatureExtractor.extract(testClass, depMap, coFailure, currentChanged,
					currentChangedTests, stats);
			var query = new ArrayExample<>(passLabel, MLFeatureExtractor.FEATURE_NAMES, features);
			Prediction<Label> prediction = model.predict(query);
			Map<String, Label> scores = prediction.getOutputScores();
			Label fail = scores.get("fail");
			double pFail = fail != null ? fail.getScore() : 0.0;
			predictions.put(testClass, pFail);
		}

		TestOrderLogger.debug("[ml] Generated predictions for {} test classes", predictions.size());
		return predictions;
	}

	/**
	 * Writes predictions to a simple key=value file for consumption by
	 * PriorityClassOrderer in the forked test JVM.
	 */
	public static void writePredictions(Path predictionsFile, Map<String, Double> predictions) throws IOException {
		Files.createDirectories(predictionsFile.getParent());
		var sb = new StringBuilder();
		sb.append("# ML failure predictions (test-order)\n");
		for (var entry : predictions.entrySet()) {
			sb.append(entry.getKey()).append('=').append(String.format(java.util.Locale.US, "%.6f", entry.getValue()))
					.append('\n');
		}
		Files.writeString(predictionsFile, sb.toString());
	}
}
