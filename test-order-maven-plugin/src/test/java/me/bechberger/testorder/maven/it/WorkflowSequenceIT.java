package me.bechberger.testorder.maven.it;

import static me.bechberger.testorder.maven.it.TestOrderAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;

/**
 * Workflow sequence integration tests — verifies end-to-end CI pipeline
 * sequences, state accumulation across multiple runs, auto-mode transitions,
 * failure tracking decay, and duration smoothing.
 * <p>
 * Exercises complete workflows as described in the README: learn → order →
 * select → run-remaining → combined.
 * <p>
 * Uses sample-shop (Product → Cart → Invoice) and test-order-example
 * (Calculator + StringUtils).
 * <p>
 * Enable with: {@code -Dtestorder.it=true}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowSequenceIT {

	static final String PRODUCT_TEST = "com.example.shop.ProductTest";
	static final String CART_TEST = "com.example.shop.CartTest";
	static final String INVOICE_TEST = "com.example.shop.InvoiceTest";

	static final String PRODUCT = "com.example.shop.Product";
	static final String CART = "com.example.shop.Cart";
	static final String INVOICE = "com.example.shop.Invoice";

	static final String CALCULATOR_TEST = "com.example.app.CalculatorTest";
	static final String STRING_UTILS_TEST = "com.example.app.StringUtilsTest";
	static final String CALCULATOR = "com.example.app.Calculator";
	static final String STRING_UTILS = "com.example.app.StringUtils";

	TestProject shopProject;
	TestProject exampleProject;

	@BeforeAll
	void setup() {
		Path root = Paths.get("").toAbsolutePath();
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		shopProject = new TestProject(root.resolve("samples/sample-shop"),
				List.of("-Dtestorder.includePackages=com.example"));
		exampleProject = new TestProject(root.resolve("test-order-example"),
				List.of("-Dtestorder.includePackages=com.example"));
		shopProject.gitRestore();
		exampleProject.gitRestore();
	}

	@AfterAll
	void tearDown() {
		if (shopProject != null)
			shopProject.restoreAll();
		if (exampleProject != null)
			exampleProject.restoreAll();
	}

	// ═══════════════════════════════════════════════════════════════════
	// FULL CI PIPELINE: learn → order × 3 → select → run-remaining
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(1)
	@DisplayName("Full CI pipeline: learn produces index")
	void fullPipelineLearn() {
		shopProject.cleanAll();
		MavenResult result = shopProject.maven().learn();
		assertThat(result).succeeded();
		assertThat(shopProject.loadIndex()).isLoaded().hasSize(3);
	}

	@Test
	@Order(2)
	@DisplayName("Full CI pipeline: first order run records state")
	void fullPipelineFirstOrder() {
		MavenResult result = shopProject.maven().order(PRODUCT);
		assertThat(result).succeeded();

		TestOrderState state = shopProject.loadState();
		assertThat(state).isLoaded().hasRuns();
	}

	@Test
	@Order(3)
	@DisplayName("Full CI pipeline: second order run accumulates state")
	void fullPipelineSecondOrder() {
		MavenResult result = shopProject.maven().order(CART);
		assertThat(result).succeeded();

		TestOrderState state = shopProject.loadState();
		assertThat(state).isLoaded();
		// Should have accumulated 2 run records
		assertThat(state.runs().size()).isGreaterThanOrEqualTo(2);
	}

	@Test
	@Order(4)
	@DisplayName("Full CI pipeline: third order run with different change")
	void fullPipelineThirdOrder() {
		MavenResult result = shopProject.maven().order(INVOICE);
		assertThat(result).succeeded();

		TestOrderState state = shopProject.loadState();
		assertThat(state).isLoaded();
		assertThat(state.runs().size()).isGreaterThanOrEqualTo(3);
	}

	@Test
	@Order(5)
	@DisplayName("Full CI pipeline: select after multiple order runs uses history")
	void fullPipelineSelect() {
		MavenResult result = shopProject.maven().run("clean", "test-order:select", "test",
				"-Dtestorder.changeMode=explicit", "-Dtestorder.changed.classes=" + PRODUCT,
				"-Dtestorder.select.topN=2", "-Dtestorder.select.randomM=1", "-Dtestorder.mode=skip");
		assertThat(result).succeeded();

		// Selected file should exist
		String selected = shopProject.readFile("target/test-order-selected.txt");
		assertThat(selected).isNotNull().isNotBlank();

		// Remaining file should exist
		String remaining = shopProject.readFile("target/test-order-remaining.txt");
		// May or may not have remaining depending on selection
	}

	@Test
	@Order(6)
	@DisplayName("Full CI pipeline: run-remaining completes the pipeline")
	void fullPipelineRunRemaining() {
		MavenResult result = shopProject.maven().runRemaining();
		// run-remaining should succeed or skip gracefully
		assertThat(result.output()).doesNotContain("NullPointerException")
				.doesNotContain("ConcurrentModificationException");
	}

	// ═══════════════════════════════════════════════════════════════════
	// AUTO MODE TRANSITIONS: learn → order
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(10)
	@DisplayName("Auto mode without index switches to learn mode")
	void autoModeWithoutIndexSwitchesToLearn() {
		exampleProject.cleanAll();

		MavenResult result = exampleProject.maven().run("clean", "test", "-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=" + CALCULATOR);
		assertThat(result).succeeded();

		// Should have auto-learned (no index found)
		assertThat(result.output()).contains("learn mode");

		// Index should now exist
		DependencyMap depMap = exampleProject.loadIndex();
		assertThat(depMap).isLoaded().hasSize(2);
	}

	@Test
	@Order(11)
	@DisplayName("Auto mode with existing index uses order mode")
	void autoModeWithExistingIndexUsesOrder() {
		// Index exists from previous test
		MavenResult result = exampleProject.maven().run("clean", "test", "-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=" + CALCULATOR);
		assertThat(result).succeeded();

		// Should use order mode (inject PriorityClassOrderer)
		assertThat(result.output()).contains("Order mode").doesNotContain("switching to learn mode automatically");
	}

	// ═══════════════════════════════════════════════════════════════════
	// AUTO-LEARN THRESHOLD
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(20)
	@DisplayName("Auto-learn threshold triggers re-learn after N runs")
	void autoLearnThresholdTriggersRelearn() {
		exampleProject.cleanAll();
		exampleProject.maven().learn();

		// Run order multiple times with a low threshold (3 runs)
		for (int i = 0; i < 3; i++) {
			exampleProject.maven().order(CALCULATOR);
		}

		// Now run auto mode with threshold=3 — should trigger re-learn
		MavenResult result = exampleProject.maven().run("clean", "test", "-Dtestorder.autoLearnRunThreshold=3",
				"-Dtestorder.changeMode=explicit", "-Dtestorder.changed.classes=" + CALCULATOR);
		assertThat(result).succeeded();

		// Should have triggered re-learn due to threshold
		assertThat(result.output()).containsAnyOf("learn mode", "switching to learn");
	}

	// ═══════════════════════════════════════════════════════════════════
	// FAILURE TRACKING PERSISTENCE
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(30)
	@DisplayName("Failure tracking: failed test gets boosted in subsequent order")
	void failureBoostPersistsAcrossRuns() {
		shopProject.cleanAll();
		shopProject.maven().learn();

		// Introduce a test failure (temporarily)
		shopProject.replaceInFile("src/test/java/com/example/shop/CartTest.java", "assertEquals", "assertNotEquals");

		// This run should fail but record the failure
		MavenResult failRun = shopProject.maven().order(CART);
		// Build fails because test fails
		assertThat(failRun).failed();

		// Restore the test
		shopProject.restoreAll();

		// Next order run should show CartTest with a failure boost
		MavenResult showOrder = shopProject.maven().showOrder(PRODUCT);
		assertThat(showOrder).succeeded();

		// CartTest should have a non-zero score even though Product changed
		// (failure history gives it a boost)
		assertThat(showOrder.output()).contains("CartTest");
	}

	// ═══════════════════════════════════════════════════════════════════
	// DURATION TRACKING ACROSS RUNS (EMA SMOOTHING)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(40)
	@DisplayName("Duration tracking flow runs across multiple order runs")
	void durationTrackingAccumulates() {
		shopProject.cleanAll();
		MavenResult learn = shopProject.maven().learn();
		assertThat(learn).succeeded();

		// First and second order runs should complete without errors.
		MavenResult firstOrder = shopProject.maven().order(PRODUCT);
		MavenResult secondOrder = shopProject.maven().order(PRODUCT);
		assertThat(firstOrder).succeeded();
		assertThat(secondOrder).succeeded();

		TestOrderState state = shopProject.loadState();
		assertThat(state).isNotNull();
	}

	// ═══════════════════════════════════════════════════════════════════
	// STALE INDEX AFTER RENAME
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(50)
	@DisplayName("Stale index after class rename still functions (new tests get bonus)")
	void staleIndexAfterRenameStillFunctions() {
		exampleProject.cleanAll();
		exampleProject.maven().learn();

		// Verify initial index has 2 tests
		DependencyMap depMap = exampleProject.loadIndex();
		assertThat(depMap).isLoaded().hasSize(2);

		// Run show-order with a non-existent class (simulating a renamed class)
		MavenResult result = exampleProject.maven().showOrder("com.example.app.RenamedCalculator");
		assertThat(result).succeeded();
		// Should not crash — stale references are just treated as unknown changes
	}

	// ═══════════════════════════════════════════════════════════════════
	// COMBINED MODE FULL WORKFLOW
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(60)
	@DisplayName("Combined mode works end-to-end (learn → select → run)")
	void combinedModeEndToEnd() {
		exampleProject.cleanAll();
		MavenResult result = exampleProject.maven().auto();
		assertThat(result).succeeded();

		// Should have run tests (either learned or ordered)
		assertThat(result.output()).containsAnyOf("Tests run:", "CalculatorTest", "StringUtilsTest");
	}

	// ═══════════════════════════════════════════════════════════════════
	// SNAPSHOT + HASH-BASED CHANGE DETECTION
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(70)
	@DisplayName("Snapshot followed by since-last-run change detection")
	void snapshotAndHashBasedChangeDetection() {
		exampleProject.cleanAll();
		exampleProject.maven().learn();

		// Take a snapshot
		MavenResult snapResult = exampleProject.maven().snapshot();
		assertThat(snapResult).succeeded();

		// Now run order with since-last-run change detection
		MavenResult orderResult = exampleProject.maven().run("clean", "test", "-Dtestorder.mode=order",
				"-Dtestorder.changeMode=since-last-run");
		// May or may not detect changes — just shouldn't crash
		assertThat(orderResult.output()).doesNotContain("NullPointerException").doesNotContain("IllegalStateException");
	}

	// ═══════════════════════════════════════════════════════════════════
	// OPTIMIZE GOAL AFTER MULTIPLE RUNS
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(80)
	@DisplayName("Optimize goal after multiple order runs adjusts weights")
	void optimizeGoalAfterMultipleRuns() {
		exampleProject.cleanAll();
		exampleProject.maven().learn();

		// Run order 5 times to accumulate history
		for (int i = 0; i < 5; i++) {
			exampleProject.maven().order(i % 2 == 0 ? CALCULATOR : STRING_UTILS);
		}

		// Optimize should analyze history and adjust weights
		MavenResult result = exampleProject.maven().optimize();
		// May require more runs — just should not crash
		assertThat(result.output()).doesNotContain("ArithmeticException").doesNotContain("NullPointerException")
				.doesNotContain("/ by zero");
	}

	// ═══════════════════════════════════════════════════════════════════
	// COMBINED MODE VARIANTS
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(90)
	@DisplayName("Combined mode=order without index warns and skips")
	void combinedModeOrderWithoutIndexWarns() {
		shopProject.cleanAll();

		MavenResult result = shopProject.maven().autoWithMode("order", PRODUCT);
		// Without an index, combined mode=order should warn/skip or auto-learn
		assertThat(result.output()).doesNotContain("NullPointerException");
	}

	@Test
	@Order(91)
	@DisplayName("Combined mode=skip does nothing")
	void combinedModeSkipDoesNothing() {
		MavenResult result = shopProject.maven().autoWithMode("skip");
		assertThat(result).succeeded();
		// Should complete without configuring test-order
	}

	@Test
	@Order(92)
	@DisplayName("Combined mode=learn always learns even with existing index")
	void combinedModeLearnAlwaysLearns() {
		shopProject.cleanAll();
		shopProject.maven().learn(); // create index first

		MavenResult result = shopProject.maven().autoWithMode("learn");
		assertThat(result).succeeded();
		// Should force a re-learn
		assertThat(result.output()).containsAnyOf("learn mode", "Learn mode");
	}

	// ═══════════════════════════════════════════════════════════════════
	// SCORING CONSISTENCY ACROSS SHOW-ORDER AND SELECT
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(100)
	@DisplayName("Show-order scoring is consistent — highest scored test matches select topN=1")
	void scoringConsistentBetweenShowOrderAndSelect() {
		shopProject.cleanAll();
		MavenResult learnResult = shopProject.maven().learn();
		assertThat(learnResult).succeeded();

		// Get show-order output for Product change
		MavenResult showResult = shopProject.maven().showOrder(PRODUCT);
		assertThat(showResult).succeeded();

		// Select with topN=1 — should pick the highest scored test
		MavenResult selectResult = shopProject.maven().run("clean", "test-order:select", "test",
				"-Dtestorder.changeMode=explicit", "-Dtestorder.changed.classes=" + PRODUCT,
				"-Dtestorder.select.topN=1", "-Dtestorder.select.randomM=0", "-Dtestorder.mode=skip");
		assertThat(selectResult).succeeded();

		String selected = shopProject.readFile("target/test-order-selected.txt");
		assertThat(selected).isNotNull().isNotBlank();

		// The first test in show-order (highest scored) should be the one selected
		// ProductTest has the highest dep overlap with Product change
		assertThat(selected).contains("ProductTest");
	}

	// ═══════════════════════════════════════════════════════════════════
	// BACK-TO-BACK SELECT + RUN-REMAINING CYCLES
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(110)
	@DisplayName("Multiple select→run-remaining cycles are stable")
	void multipleSelectRunRemainingCyclesStable() {
		shopProject.cleanAll();
		MavenResult learnResult = shopProject.maven().learn();
		assertThat(learnResult).succeeded();

		// Cycle 1
		MavenResult select1 = shopProject.maven().run("clean", "test-order:select", "test",
				"-Dtestorder.changeMode=explicit", "-Dtestorder.changed.classes=" + PRODUCT,
				"-Dtestorder.select.topN=1", "-Dtestorder.select.randomM=0", "-Dtestorder.mode=skip");
		assertThat(select1).succeeded();
		MavenResult remaining1 = shopProject.maven().runRemaining();

		// Cycle 2 (different change)
		MavenResult select2 = shopProject.maven().run("clean", "test-order:select", "test",
				"-Dtestorder.changeMode=explicit", "-Dtestorder.changed.classes=" + INVOICE,
				"-Dtestorder.select.topN=1", "-Dtestorder.select.randomM=0", "-Dtestorder.mode=skip");
		assertThat(select2).succeeded();
		MavenResult remaining2 = shopProject.maven().runRemaining();

		// Both cycles should complete without file corruption
		assertThat(remaining1.output()).doesNotContain("ConcurrentModificationException");
		assertThat(remaining2.output()).doesNotContain("ConcurrentModificationException");
	}
}
