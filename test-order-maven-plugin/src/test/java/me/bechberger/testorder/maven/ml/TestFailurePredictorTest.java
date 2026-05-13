package me.bechberger.testorder.maven.ml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.ml.MLHistoryPersistence;
import me.bechberger.testorder.ml.MLRunRecord;
import me.bechberger.testorder.ml.MLTestOutcome;

class TestFailurePredictorTest {

	@TempDir
	Path tempDir;

	@Test
	void predictorReturnsHigherProbabilityForHistoricallyRiskyAffectedTest() throws IOException {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.RiskyTest", Set.of("com.app.A", "com.app.shared.Util"));
		depMap.put("com.example.StableTest", Set.of("com.app.B"));

		Path historyFile = tempDir.resolve("history.lz4");
		MLHistoryPersistence.save(historyFile, List.of(
				run(1L, List.of("com.app.A"), outcome("com.example.RiskyTest", true, 100, "java.lang.RuntimeException"),
						outcome("com.example.StableTest", false, 50, null)),
				run(2L, List.of("com.app.A"), outcome("com.example.RiskyTest", true, 110, "java.lang.RuntimeException"),
						outcome("com.example.StableTest", false, 55, null)),
				run(3L, List.of("com.app.A"),
						outcome("com.example.RiskyTest", true, 120, "java.lang.IllegalStateException"),
						outcome("com.example.StableTest", false, 60, null)),
				run(4L, List.of("com.app.A"),
						outcome("com.example.RiskyTest", true, 130, "java.lang.IllegalStateException"),
						outcome("com.example.StableTest", false, 65, null)),
				run(5L, List.of("com.app.A"),
						outcome("com.example.RiskyTest", true, 140, "java.lang.IllegalStateException"),
						outcome("com.example.StableTest", false, 70, null)),
				run(6L, List.of("com.app.A"), outcome("com.example.RiskyTest", true, 150, "java.lang.AssertionError"),
						outcome("com.example.StableTest", false, 75, null)),
				run(7L, List.of("com.app.A"), outcome("com.example.RiskyTest", false, 160, null),
						outcome("com.example.StableTest", false, 80, null)),
				run(8L, List.of("com.app.A"), outcome("com.example.RiskyTest", true, 170, "java.lang.RuntimeException"),
						outcome("com.example.StableTest", false, 85, null))),
				0);

		Map<String, Double> predictions = TestFailurePredictor.trainAndPredict(historyFile, depMap, Set.of("com.app.A"),
				Set.of("com.example.ChangedHelperTest"), depMap.testClasses());

		assertEquals(Set.of("com.example.RiskyTest", "com.example.StableTest"), predictions.keySet());
		double risky = predictions.get("com.example.RiskyTest");
		double stable = predictions.get("com.example.StableTest");
		assertTrue(risky >= 0.0 && risky <= 1.0, "Risky prediction must be a probability");
		assertTrue(stable >= 0.0 && stable <= 1.0, "Stable prediction must be a probability");
		assertTrue(risky > stable, "Historically risky affected test should rank above stable test");
	}

	@Test
	void predictorSkipsWhenHistoryIsTooShort() throws IOException {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.RiskyTest", Set.of("com.app.A"));

		Path historyFile = tempDir.resolve("short-history.lz4");
		MLHistoryPersistence.save(historyFile,
				List.of(run(1L, List.of("com.app.A"),
						outcome("com.example.RiskyTest", true, 100, "java.lang.RuntimeException")),
						run(2L, List.of("com.app.A"), outcome("com.example.RiskyTest", false, 120, null))),
				0);

		Map<String, Double> predictions = TestFailurePredictor.trainAndPredict(historyFile, depMap, Set.of("com.app.A"),
				Set.of(), depMap.testClasses());

		assertTrue(predictions.isEmpty());
	}

	private static MLRunRecord run(long timestamp, List<String> changedClasses, MLTestOutcome... outcomes) {
		long failures = java.util.Arrays.stream(outcomes).filter(MLTestOutcome::failed).count();
		return new MLRunRecord(timestamp, changedClasses, List.of(), outcomes.length, (int) failures,
				List.of(outcomes));
	}

	private static MLTestOutcome outcome(String testClass, boolean failed, long durationMs, String failureType) {
		return new MLTestOutcome(testClass, failed, durationMs, failureType);
	}
}
