package me.bechberger.testorder.maven.it;

import static me.bechberger.testorder.maven.it.TestOrderAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import me.bechberger.testorder.ml.MLHistoryPersistence;
import me.bechberger.testorder.ml.MLPredictions;
import me.bechberger.testorder.ml.MLRunRecord;
import me.bechberger.testorder.ml.TestHealthReport;

/**
 * End-to-end ML workflow integration tests.
 * <p>
 * Uses the sample-basic project to verify that repeated Maven executions: 1.
 * persist ML history, 2. generate predictions once enough history exists, 3.
 * propagate runtime config into the forked test JVM, and 4. produce an offline
 * health report via the analyze goal.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MLPredictorWorkflowIT {

	private static final int EXPECTED_TEST_CLASSES = 7;

	TestProject project;

	@BeforeAll
	void setup() {
		Path root = Paths.get("").toAbsolutePath();
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		project = new TestProject(root.resolve("samples/sample-basic"),
				List.of("-Dtestorder.includePackages=com.myapp"));
		project.gitRestore();
		project.cleanAll();
	}

	@AfterAll
	void tearDown() {
		if (project != null) {
			project.restoreAll();
		}
	}

	@Test
	@Order(1)
	@DisplayName("ML workflow: repeated runs persist history and generate predictions")
	void repeatedRunsGenerateHistoryAndPredictions() throws Exception {
		MavenResult finalResult = null;
		for (int run = 1; run <= 6; run++) {
			finalResult = project.maven().run("clean", "test", "-Dtestorder.ml.enabled=true",
					"-Dmaven.test.failure.ignore=true");
			assertThat(finalResult).succeeded().outputContains("[test-order]").outputContains("Tests run:");
		}

		Path historyFile = project.path(".test-order/ml/history.lz4");
		assertThat(historyFile).exists();

		List<MLRunRecord> history = MLHistoryPersistence.load(historyFile);
		assertThat(history).hasSize(6);
		assertThat(history).allSatisfy(run -> {
			assertThat(run.totalTests()).isEqualTo(EXPECTED_TEST_CLASSES);
			assertThat(run.outcomes()).hasSize(EXPECTED_TEST_CLASSES);
		});

		Path predictionsFile = project.path("target/test-order-runtime/ml-predictions.properties");
		assertThat(predictionsFile).exists();
		Map<String, Double> predictions = MLPredictions.read(predictionsFile);
		assertThat(predictions).hasSize(EXPECTED_TEST_CLASSES);
		assertThat(predictions.values()).allSatisfy(score -> assertThat(score).isBetween(0.0, 1.0));

		String runtimeConfig = project.readFile("target/test-order-runtime/testorder-config.properties");
		assertThat(runtimeConfig).contains("testorder.ml.enabled=true");
		assertThat(runtimeConfig).contains("testorder.ml.predictions.file=");

		assertThat(finalResult.output())
				.contains("[ml] Generated predictions for " + EXPECTED_TEST_CLASSES + " test classes");
	}

	@Test
	@Order(2)
	@DisplayName("ML workflow: forked test JVM consumes generated predictions")
	void forkedJvmConsumesPredictions() {
		MavenResult result = project.maven().run("clean", "test", "-Dtestorder.ml.enabled=true",
				"-Dtestorder.debug=true", "-Dmaven.test.failure.ignore=true");
		assertThat(result).succeeded()
				.outputContains("[ml] Generated predictions for " + EXPECTED_TEST_CLASSES + " test classes");
		assertThat(result.output())
				.contains("[ml] Applied ML predictions for " + EXPECTED_TEST_CLASSES + " test classes");
	}

	@Test
	@Order(3)
	@DisplayName("ML workflow: analyze goal writes a health report from ML history")
	void analyzeGoalProducesHealthReport() throws Exception {
		MavenResult result = project.maven().run("test-order:analyze");
		assertThat(result).succeeded().outputContains("Test Health Report");

		Path reportFile = project.path(".test-order/test-health-report.txt");
		assertThat(reportFile).exists();

		TestHealthReport report = TestHealthReport.load(reportFile);
		assertThat(report.runsAnalyzed()).isGreaterThanOrEqualTo(6);
		assertThat(report.tests()).hasSize(EXPECTED_TEST_CLASSES);
		assertThat(report.tests().values()).allSatisfy(test -> assertThat(test.totalRuns()).isGreaterThanOrEqualTo(6));
	}
}
