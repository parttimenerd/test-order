package me.bechberger.testorder.ml;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import me.bechberger.testorder.DependencyMap;

class MLFeatureExtractorTest {

	@Test
	void extractIncludesPackageLevelAndTemporalSignals() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.FooTest", Set.of("com.app.A", "com.app.sub.B"));
		depMap.put("com.example.BarTest", Set.of("com.app.A"));
		depMap.put("com.other.BazTest", Set.of("com.other.C"));

		List<MLRunRecord> history = List.of(
				run(1L, List.of("com.app.A"), List.of("com.example.SupportTest"),
						outcome("com.example.FooTest", false, 100, null),
						outcome("com.example.BarTest", false, 80, null), outcome("com.other.BazTest", false, 60, null)),
				run(2L, List.of("com.app.A"), List.of("com.example.SupportTest"),
						outcome("com.example.FooTest", true, 120, "java.lang.IllegalStateException"),
						outcome("com.example.BarTest", true, 90, "java.lang.IllegalStateException"),
						outcome("com.other.BazTest", true, 65, "java.lang.IllegalStateException")),
				run(3L, List.of("com.app.sub.B"), List.of("com.example.SupportTest"),
						outcome("com.example.FooTest", false, 140, null),
						outcome("com.example.BarTest", false, 100, null),
						outcome("com.other.BazTest", false, 70, null)),
				run(4L, List.of("com.app.A"), List.of("com.example.SupportTest"),
						outcome("com.example.FooTest", true, 160, "java.lang.RuntimeException"),
						outcome("com.example.BarTest", true, 110, "java.lang.RuntimeException"),
						outcome("com.other.BazTest", false, 75, null)),
				run(5L, List.of("com.app.sub.B"), List.of("com.example.SupportTest"),
						outcome("com.example.FooTest", false, 180, null),
						outcome("com.example.BarTest", false, 120, null),
						outcome("com.other.BazTest", false, 80, null)),
				run(6L, List.of("com.app.sub.B"), List.of("com.example.SupportTest"),
						outcome("com.example.FooTest", false, 200, null),
						outcome("com.example.BarTest", true, 130, "java.lang.AssertionError"),
						outcome("com.other.BazTest", false, 85, null)));

		Map<String, MLFeatureExtractor.TestStats> stats = MLFeatureExtractor.buildStats(history);
		CoFailureTracker coFailure = MLFeatureExtractor.buildCoFailureTracker(history);

		double[] features = MLFeatureExtractor.extract("com.example.FooTest", depMap, coFailure,
				Set.of("com.app.A", "com.app.sub.B"), Set.of("com.example.BarTest"), stats);

		assertEquals(MLFeatureExtractor.FEATURE_COUNT, features.length);
		assertEquals(2.0, features[MLFeatureExtractor.IDX_DEP_COUNT]);
		assertEquals(2.0, features[MLFeatureExtractor.IDX_CHANGED_CLASS_COUNT]);
		assertEquals(1.0, features[MLFeatureExtractor.IDX_CHANGED_TEST_CLASS_COUNT]);
		assertEquals(2.0, features[MLFeatureExtractor.IDX_CHANGED_PKG_COUNT]);
		assertEquals(2.0, features[MLFeatureExtractor.IDX_DEP_PKG_COUNT]);
		assertEquals(1.0 / 3.0, features[MLFeatureExtractor.IDX_FAILURE_RATE], 1.0e-9);
		assertEquals(150.0, features[MLFeatureExtractor.IDX_MEAN_DURATION_MS], 1.0e-9);
		assertEquals(200.0, features[MLFeatureExtractor.IDX_MAX_DURATION_MS], 1.0e-9);
		assertEquals(0.5, features[MLFeatureExtractor.IDX_PKG_FAILURE_RATE], 1.0e-9);
		assertEquals(1.0, features[MLFeatureExtractor.IDX_PKG_TEST_COUNT], 1.0e-9);
		assertEquals(0.0, features[MLFeatureExtractor.IDX_LAST_OUTCOME], 1.0e-9);
		assertEquals(1.0, features[MLFeatureExtractor.IDX_TEST_IN_CHANGED_PKG], 1.0e-9);
		assertEquals(1.0, features[MLFeatureExtractor.IDX_IS_AFFECTED], 1.0e-9);
		assertEquals(2.0, features[MLFeatureExtractor.IDX_DEP_OVERLAP_COUNT], 1.0e-9);
		assertEquals(1.0, features[MLFeatureExtractor.IDX_DEP_OVERLAP_RATIO], 1.0e-9);
		assertTrue(features[MLFeatureExtractor.IDX_CHANGED_PKG_OVERLAP] > 0.0);
		assertTrue(features[MLFeatureExtractor.IDX_DURATION_TREND] > 0.0);
		assertTrue(features[MLFeatureExtractor.IDX_SHARED_FAILURE_PROXIMITY] > 0.0);
	}

	@Test
	void trainingExamplesUseOnlyPastData() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.FooTest", Set.of("com.app.A"));

		List<MLRunRecord> history = List.of(
				run(1L, List.of("com.app.A"), List.of(),
						outcome("com.example.FooTest", true, 100, "java.lang.RuntimeException")),
				run(2L, List.of("com.app.A"), List.of(), outcome("com.example.FooTest", false, 120, null)));

		List<MLFeatureExtractor.TrainingExample> examples = MLFeatureExtractor.extractTrainingExamples(history, depMap);

		assertEquals(2, examples.size());
		assertEquals(0.0, examples.get(0).features()[MLFeatureExtractor.IDX_FAILURE_RATE], 1.0e-9);
		assertEquals(0.0, examples.get(0).features()[MLFeatureExtractor.IDX_LAST_OUTCOME], 1.0e-9);
		assertEquals(1.0, examples.get(1).features()[MLFeatureExtractor.IDX_FAILURE_RATE], 1.0e-9);
		assertEquals(1.0, examples.get(1).features()[MLFeatureExtractor.IDX_LAST_OUTCOME], 1.0e-9);
	}

	private static MLRunRecord run(long timestamp, List<String> changedClasses, List<String> changedTestClasses,
			MLTestOutcome... outcomes) {
		long failures = java.util.Arrays.stream(outcomes).filter(MLTestOutcome::failed).count();
		return new MLRunRecord(timestamp, changedClasses, changedTestClasses, outcomes.length, (int) failures,
				List.of(outcomes));
	}

	private static MLTestOutcome outcome(String testClass, boolean failed, long durationMs, String failureType) {
		return new MLTestOutcome(testClass, failed, durationMs, failureType);
	}
}
