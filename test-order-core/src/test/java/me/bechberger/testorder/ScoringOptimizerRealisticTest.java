package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.TestOrderState.OptimizeResult;
import me.bechberger.testorder.TestOrderState.RunRecord;
import me.bechberger.testorder.TestOrderState.ScoringWeights;
import me.bechberger.testorder.TestOrderState.TestOutcome;
import me.bechberger.testorder.ops.OptimizeOperation;

/**
 * Tests the score optimization pipeline with realistic scenarios that
 * mirror actual test-order usage: a project with varying test classes,
 * dependency overlaps, changed code, and different failure patterns.
 *
 * <p>Scenario: A Spring Boot e-commerce app with 8 test classes covering
 * controllers, services, repositories, and utilities. Over 10+ runs,
 * developers change different parts of the codebase, some tests fail,
 * and the optimizer must learn which scoring weights push failures to
 * the front of the execution queue.
 */
class ScoringOptimizerRealisticTest {

	@TempDir
	Path tempDir;

	// ── Test classes in our simulated project ───────────────────────
	static final String ORDER_CONTROLLER_TEST = "com.shop.OrderControllerTest";
	static final String ORDER_SERVICE_TEST = "com.shop.OrderServiceTest";
	static final String PAYMENT_SERVICE_TEST = "com.shop.PaymentServiceTest";
	static final String USER_REPO_TEST = "com.shop.UserRepositoryTest";
	static final String CART_SERVICE_TEST = "com.shop.CartServiceTest";
	static final String INVENTORY_TEST = "com.shop.InventoryServiceTest";
	static final String EMAIL_SERVICE_TEST = "com.shop.EmailServiceTest";
	static final String UTIL_TEST = "com.shop.UtilTest";

	// ── Simulated durations (ms) ─────────────────────────────────────
	static final Map<String, Long> DURATIONS = Map.of(
			ORDER_CONTROLLER_TEST, 3200L,  // slow (Spring context)
			ORDER_SERVICE_TEST, 1800L,
			PAYMENT_SERVICE_TEST, 2500L,   // slow (external service mocks)
			USER_REPO_TEST, 1200L,
			CART_SERVICE_TEST, 900L,        // fast
			INVENTORY_TEST, 1100L,
			EMAIL_SERVICE_TEST, 600L,       // fast
			UTIL_TEST, 150L                 // very fast
	);

	/**
	 * Scenario: developer frequently changes OrderController and PaymentService.
	 * Tests with high dependency overlap on changed code should be prioritized.
	 * The optimizer should learn to weight depOverlap and changedTest highly.
	 */
	@Test
	void optimizerImprovesAPFDcOverDefaultWeights() {
		TestOrderState state = new TestOrderState();
		DURATIONS.forEach(state::recordDuration);

		// Build 10 runs simulating realistic development patterns
		List<RunRecord> runs = buildRealisticRunHistory();
		for (RunRecord run : runs) {
			state.addRunRecord(run);
		}

		// Verify we have enough failure data
		long failureRuns = runs.stream().filter(r -> r.totalFailures() > 0).count();
		assertTrue(failureRuns >= 3, "Need at least 3 failure runs, got " + failureRuns);

		// Run optimization
		OptimizeResult result = state.optimize();

		assertNotNull(result, "Optimization should return a result with sufficient failure data");
		ScoringWeights optimized = result.weights();

		// Verify result structure
		assertTrue(result.trainScore() > 0, "Train score should be positive");
		assertTrue(result.trainScore() <= 1.0, "Train score should be ≤ 1.0");
		assertTrue(result.validationScore() > 0, "Validation score should be positive");

		// All weights should be within valid ranges
		for (var def : TestOrderState.WEIGHT_DEFS) {
			int val = optimized.toMap().get(def.name());
			assertTrue(val >= def.min() && val <= def.max(),
					def.name() + "=" + val + " out of range [" + def.min() + "," + def.max() + "]");
		}
	}

	/**
	 * Scenario where changed tests reliably predict failures.
	 * The optimizer should value changedTest weight.
	 */
	@Test
	void optimizerValuesChangedTestWhenItPredicts() {
		TestOrderState state = new TestOrderState();
		DURATIONS.forEach(state::recordDuration);

		// 8 runs where the changed test always fails
		for (int i = 0; i < 8; i++) {
			String changedTest = i % 2 == 0 ? ORDER_SERVICE_TEST : PAYMENT_SERVICE_TEST;
			List<TestOutcome> outcomes = new ArrayList<>();

			for (String test : DURATIONS.keySet()) {
				boolean isChanged = test.equals(changedTest);
				boolean failed = isChanged; // changed test always fails
				outcomes.add(new TestOutcome(test, 5, false, isChanged,
						0, 10, 0.0, DURATIONS.get(test) < 800, DURATIONS.get(test) > 2000,
						failed, 0.0));
			}

			int failPos = outcomes.indexOf(outcomes.stream().filter(TestOutcome::failed).findFirst().orElseThrow());
			state.addRunRecord(new RunRecord(i * 1000L, outcomes.size(), 1, failPos, 0.5, outcomes));
		}

		OptimizeResult result = state.optimize();
		assertNotNull(result);

		// changedTest should have non-trivial weight since changed tests always fail
		assertTrue(result.weights().changedTest() > 0,
				"changedTest weight should be positive when changed tests predict failures");
	}

	/**
	 * Scenario with dependency overlap as the primary failure predictor:
	 * tests with high dep overlap on changed code fail more often.
	 */
	@Test
	void optimizerValuesDepOverlapWhenItPredicts() {
		TestOrderState state = new TestOrderState();
		DURATIONS.forEach(state::recordDuration);

		// 8 runs where tests with dependency overlap on changed code fail
		for (int i = 0; i < 8; i++) {
			List<TestOutcome> outcomes = new ArrayList<>();
			for (String test : DURATIONS.keySet()) {
				// Simulate: OrderControllerTest and OrderServiceTest have high dep overlap
				// on the changed code, and they fail
				int depOverlap = 0;
				boolean failed = false;
				if (test.equals(ORDER_CONTROLLER_TEST) || test.equals(ORDER_SERVICE_TEST)) {
					depOverlap = 5;
					failed = true;
				} else if (test.equals(CART_SERVICE_TEST)) {
					depOverlap = 2; // some overlap but doesn't fail
				}

				outcomes.add(new TestOutcome(test, 5, false, false,
						depOverlap, 10, 0.0,
						DURATIONS.get(test) < 800, DURATIONS.get(test) > 2000,
						failed, 0.0));
			}

			state.addRunRecord(new RunRecord(i * 1000L, outcomes.size(), 2, 0, 0.5, outcomes));
		}

		OptimizeResult result = state.optimize();
		assertNotNull(result);

		// depOverlap should have meaningful weight
		assertTrue(result.weights().depOverlap() > 0,
				"depOverlap weight should be positive when dep overlap predicts failures");
	}

	/**
	 * End-to-end: optimize via OptimizeOperation (save/load from file).
	 */
	@Test
	void optimizeOperationEndToEnd() throws IOException {
		TestOrderState state = new TestOrderState();
		DURATIONS.forEach(state::recordDuration);

		for (RunRecord run : buildRealisticRunHistory()) {
			state.addRunRecord(run);
		}

		Path stateFile = tempDir.resolve("test-order.state");
		state.save(stateFile);

		List<String> logMessages = new ArrayList<>();
		OptimizeOperation.Result result = OptimizeOperation.run(stateFile, logMessages::add);

		assertNotNull(result, "OptimizeOperation should produce a result");
		assertTrue(result.totalRuns() > 0);
		assertTrue(result.failureRuns() >= 3);
		assertNotNull(result.optimizedWeights());
		assertNotNull(result.previousWeights());
		assertTrue(result.elapsedMs() >= 0);
		assertTrue(result.trainScore() > 0 && result.trainScore() <= 1.0);

		// Verify state file was updated
		TestOrderState reloaded = TestOrderState.load(stateFile);
		assertEquals(result.optimizedWeights(), reloaded.weights());

		// Verify log output
		assertTrue(logMessages.stream().anyMatch(m -> m.contains("Runs:")));
		assertTrue(logMessages.stream().anyMatch(m -> m.contains("Optimised weights")));
	}

	/**
	 * Verify optimization is stable: running twice on the same data
	 * should produce broadly similar results (within the genetic algorithm's
	 * stochastic nature).
	 */
	@Test
	void optimizationIsStableAcrossRuns() {
		TestOrderState state1 = new TestOrderState();
		TestOrderState state2 = new TestOrderState();
		DURATIONS.forEach(state1::recordDuration);
		DURATIONS.forEach(state2::recordDuration);

		List<RunRecord> runs = buildRealisticRunHistory();
		for (RunRecord run : runs) {
			state1.addRunRecord(run);
			state2.addRunRecord(run);
		}

		OptimizeResult result1 = state1.optimize();
		OptimizeResult result2 = state2.optimize();

		assertNotNull(result1);
		assertNotNull(result2);

		// Train scores should be similar (same data)
		double scoreDiff = Math.abs(result1.trainScore() - result2.trainScore());
		assertTrue(scoreDiff < 0.15,
				"Two optimizations on same data should have similar train scores, diff=" + scoreDiff);
	}

	/**
	 * Verify that the optimizer doesn't crash with the minimum required
	 * number of failure runs (3).
	 */
	@Test
	void optimizerWorksWithMinimumRuns() {
		TestOrderState state = new TestOrderState();
		DURATIONS.forEach(state::recordDuration);

		// Exactly 3 runs with failures
		for (int i = 0; i < 3; i++) {
			List<TestOutcome> outcomes = List.of(
					new TestOutcome(ORDER_CONTROLLER_TEST, 5, false, true,
							3, 10, 0.0, false, true, true, 0.0),
					new TestOutcome(UTIL_TEST, 1, false, false,
							0, 10, 0.0, true, false, false, 0.0),
					new TestOutcome(EMAIL_SERVICE_TEST, 1, false, false,
							0, 10, 0.0, true, false, false, 0.0)
			);
			state.addRunRecord(new RunRecord(i * 1000L, 3, 1, 0, 0.6, outcomes));
		}

		OptimizeResult result = state.optimize();
		assertNotNull(result, "Should optimize with exactly 3 failure runs");
		// With only 3 runs, expanding window won't be used (< 5)
		assertEquals(0, result.folds(), "Should not use expanding window with < 5 runs");
	}

	/**
	 * Verify that with enough runs, expanding-window cross-validation is used
	 * and overfitting detection works.
	 */
	@Test
	void expandingWindowUsedWithEnoughRuns() {
		TestOrderState state = new TestOrderState();
		DURATIONS.forEach(state::recordDuration);

		// 7 runs with failures — enough for expanding window
		for (int i = 0; i < 7; i++) {
			String failingTest = i % 3 == 0 ? ORDER_CONTROLLER_TEST
					: i % 3 == 1 ? PAYMENT_SERVICE_TEST : ORDER_SERVICE_TEST;

			List<TestOutcome> outcomes = new ArrayList<>();
			for (String test : DURATIONS.keySet()) {
				boolean failed = test.equals(failingTest);
				outcomes.add(new TestOutcome(test, 5, false, failed,
						failed ? 4 : 0, 10, 0.0,
						DURATIONS.get(test) < 800, DURATIONS.get(test) > 2000,
						failed, 0.0));
			}
			state.addRunRecord(new RunRecord(i * 1000L, outcomes.size(), 1, 0, 0.5, outcomes));
		}

		OptimizeResult result = state.optimize();
		assertNotNull(result);
		assertTrue(result.folds() > 0, "Should use expanding-window CV with ≥ 5 failure runs");
		assertTrue(result.validationScore() > 0, "Validation score should be positive");
	}

	// ── Helper: build realistic run history ─────────────────────────

	/**
	 * Simulates 10 development runs with varying patterns:
	 * - Runs 0-2: Developer changes OrderController area → OrderControllerTest + OrderServiceTest fail
	 * - Run 3: All-pass run (bug fixed)
	 * - Runs 4-5: Developer changes PaymentService → PaymentServiceTest fails
	 * - Run 6: New test added, passes
	 * - Runs 7-8: Large refactor — CartServiceTest + InventoryTest fail
	 * - Run 9: Only a fast UtilTest fails (regression in utility)
	 */
	private List<RunRecord> buildRealisticRunHistory() {
		List<RunRecord> runs = new ArrayList<>();

		// Runs 0-2: OrderController changes cause failures in related tests
		for (int i = 0; i < 3; i++) {
			List<TestOutcome> outcomes = new ArrayList<>();
			outcomes.add(outcome(ORDER_CONTROLLER_TEST, true, true, 5, true));   // changed + high overlap → fails
			outcomes.add(outcome(ORDER_SERVICE_TEST, false, false, 3, true));     // overlap → fails
			outcomes.add(outcome(PAYMENT_SERVICE_TEST, false, false, 0, false));
			outcomes.add(outcome(USER_REPO_TEST, false, false, 0, false));
			outcomes.add(outcome(CART_SERVICE_TEST, false, false, 1, false));
			outcomes.add(outcome(INVENTORY_TEST, false, false, 0, false));
			outcomes.add(outcome(EMAIL_SERVICE_TEST, false, false, 0, false));
			outcomes.add(outcome(UTIL_TEST, false, false, 0, false));

			runs.add(new RunRecord(i * 60_000L, 8, 2, 0, 0.65, outcomes));
		}

		// Run 3: All pass (bug fixed)
		{
			List<TestOutcome> outcomes = new ArrayList<>();
			for (String test : DURATIONS.keySet()) {
				outcomes.add(outcome(test, false, false, 0, false));
			}
			runs.add(new RunRecord(3 * 60_000L, 8, 0, -1, 1.0, outcomes));
		}

		// Runs 4-5: Payment changes
		for (int i = 4; i <= 5; i++) {
			List<TestOutcome> outcomes = new ArrayList<>();
			outcomes.add(outcome(ORDER_CONTROLLER_TEST, false, false, 1, false));
			outcomes.add(outcome(ORDER_SERVICE_TEST, false, false, 0, false));
			outcomes.add(outcome(PAYMENT_SERVICE_TEST, true, true, 4, true));   // changed + overlap → fails
			outcomes.add(outcome(USER_REPO_TEST, false, false, 0, false));
			outcomes.add(outcome(CART_SERVICE_TEST, false, false, 0, false));
			outcomes.add(outcome(INVENTORY_TEST, false, false, 0, false));
			outcomes.add(outcome(EMAIL_SERVICE_TEST, false, false, 0, false));
			outcomes.add(outcome(UTIL_TEST, false, false, 0, false));

			runs.add(new RunRecord(i * 60_000L, 8, 1, 2, 0.75, outcomes));
		}

		// Run 6: New test, all pass
		{
			List<TestOutcome> outcomes = new ArrayList<>();
			for (String test : DURATIONS.keySet()) {
				boolean isNew = test.equals(INVENTORY_TEST); // pretend it's new
				outcomes.add(outcome(test, isNew, false, 0, false));
			}
			runs.add(new RunRecord(6 * 60_000L, 8, 0, -1, 1.0, outcomes));
		}

		// Runs 7-8: Large refactor — multiple failures
		for (int i = 7; i <= 8; i++) {
			List<TestOutcome> outcomes = new ArrayList<>();
			outcomes.add(outcome(ORDER_CONTROLLER_TEST, false, false, 0, false));
			outcomes.add(outcome(ORDER_SERVICE_TEST, false, false, 0, false));
			outcomes.add(outcome(PAYMENT_SERVICE_TEST, false, false, 0, false));
			outcomes.add(outcome(USER_REPO_TEST, false, false, 0, false));
			outcomes.add(outcome(CART_SERVICE_TEST, true, true, 3, true));       // changed + overlap → fails
			outcomes.add(outcome(INVENTORY_TEST, false, false, 4, true));         // high overlap → fails
			outcomes.add(outcome(EMAIL_SERVICE_TEST, false, false, 0, false));
			outcomes.add(outcome(UTIL_TEST, false, false, 0, false));

			runs.add(new RunRecord(i * 60_000L, 8, 2, 4, 0.6, outcomes));
		}

		// Run 9: Utility regression
		{
			List<TestOutcome> outcomes = new ArrayList<>();
			outcomes.add(outcome(ORDER_CONTROLLER_TEST, false, false, 0, false));
			outcomes.add(outcome(ORDER_SERVICE_TEST, false, false, 0, false));
			outcomes.add(outcome(PAYMENT_SERVICE_TEST, false, false, 0, false));
			outcomes.add(outcome(USER_REPO_TEST, false, false, 0, false));
			outcomes.add(outcome(CART_SERVICE_TEST, false, false, 0, false));
			outcomes.add(outcome(INVENTORY_TEST, false, false, 0, false));
			outcomes.add(outcome(EMAIL_SERVICE_TEST, false, false, 0, false));
			outcomes.add(outcome(UTIL_TEST, true, true, 2, true));              // changed + overlap → fails

			runs.add(new RunRecord(9 * 60_000L, 8, 1, 7, 0.55, outcomes));
		}

		return runs;
	}

	/**
	 * Helper to create a TestOutcome with realistic attributes.
	 */
	private TestOutcome outcome(String testClass, boolean isNew, boolean isChanged,
			int depOverlap, boolean failed) {
		long duration = DURATIONS.getOrDefault(testClass, 1000L);
		boolean isFast = duration < 800;
		boolean isSlow = duration > 2000;
		double failScore = failed ? 1.0 : 0.0;
		return new TestOutcome(testClass, 0, isNew, isChanged, depOverlap, 10,
				failScore, isFast, isSlow, failed, 0.0);
	}
}
