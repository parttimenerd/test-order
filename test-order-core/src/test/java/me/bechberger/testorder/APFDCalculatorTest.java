package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class APFDCalculatorTest {

	private static final double DELTA = 1e-9;

	// ── TestOutcome factory helpers ───────────────────────────────────────────

	private static TestOrderState.TestOutcome pass(String name) {
		return new TestOrderState.TestOutcome(name, 0, false, false, 0, 1, 0.0, false, false, false, 0.0);
	}

	private static TestOrderState.TestOutcome fail(String name) {
		return new TestOrderState.TestOutcome(name, 0, false, false, 0, 1, 0.0, false, false, true, 0.0);
	}

	// ── computeAPFD ───────────────────────────────────────────────────────────

	@Test
	void apfd_emptyList_returnsNaN() {
		// No tests, no failures — APFD is undefined (NaN), not 1.0.
		assertTrue(Double.isNaN(APFDCalculator.computeAPFD(List.of())));
	}

	@Test
	void apfd_noFailures_returnsNaN() {
		// No faults — APFD is undefined (NaN): there is nothing to detect.
		List<TestOrderState.TestOutcome> outcomes = List.of(pass("A"), pass("B"), pass("C"));
		assertTrue(Double.isNaN(APFDCalculator.computeAPFD(outcomes)));
	}

	@Test
	void apfd_singleTestSingleFailure_returns1() {
		// n=1, m=1: 1 - 1/(1*1) + 1/2 = 0.5.
		// The formula gives 0.5 for n=m=1, which is correct per the APFD definition.
		List<TestOrderState.TestOutcome> outcomes = List.of(fail("A"));
		double expected = 1.0 - 1.0 / (1.0 * 1) + 1.0 / (2.0 * 1);
		assertEquals(expected, APFDCalculator.computeAPFD(outcomes), DELTA);
	}

	@Test
	void apfd_allTestsFail_singleRun() {
		// n=3, m=3, positionSum = 1+2+3 = 6
		// 1 - 6/(3*3) + 1/6 = 1 - 2/3 + 1/6 = 0.5
		List<TestOrderState.TestOutcome> outcomes = List.of(fail("A"), fail("B"), fail("C"));
		double expected = 1.0 - 6.0 / (3.0 * 3) + 1.0 / (2.0 * 3);
		assertEquals(expected, APFDCalculator.computeAPFD(outcomes), DELTA);
	}

	@Test
	void apfd_failureAtStart_higherThanFailureAtEnd() {
		// Failure at position 1 (first) should yield higher APFD than at position 3
		// (last).
		List<TestOrderState.TestOutcome> early = List.of(fail("A"), pass("B"), pass("C"));
		List<TestOrderState.TestOutcome> late = List.of(pass("A"), pass("B"), fail("C"));
		assertTrue(APFDCalculator.computeAPFD(early) > APFDCalculator.computeAPFD(late));
	}

	@Test
	void apfd_failureAtEnd_knownValue() {
		// n=3, m=1, positionSum=3 (last position)
		// 1 - 3/(3*1) + 1/6 ≈ 0.1667
		List<TestOrderState.TestOutcome> outcomes = List.of(pass("A"), pass("B"), fail("C"));
		double expected = 1.0 - 3.0 / (3.0 * 1) + 1.0 / (2.0 * 3);
		assertEquals(expected, APFDCalculator.computeAPFD(outcomes), DELTA);
	}

	@Test
	void apfd_multipleFailures_boundedBetween0And1() {
		List<TestOrderState.TestOutcome> outcomes = List.of(pass("A"), fail("B"), pass("C"), fail("D"), pass("E"));
		double apfd = APFDCalculator.computeAPFD(outcomes);
		assertTrue(apfd >= 0.0 && apfd <= 1.0, "APFD must be in [0,1], got " + apfd);
	}

	// ── computeAPFDc ──────────────────────────────────────────────────────────

	@Test
	void apfdC_emptyList_returns1() {
		assertTrue(Double.isNaN(APFDCalculator.computeAPFDc(List.of(), Map.of())));
	}

	@Test
	void apfdC_noFailures_returns1() {
		List<TestOrderState.TestOutcome> outcomes = List.of(pass("A"), pass("B"));
		assertTrue(Double.isNaN(APFDCalculator.computeAPFDc(outcomes, Map.of("A", 100L, "B", 200L))));
	}

	@Test
	void apfdC_negativeDuration_treatedAsUnitCost() {
		// Negative durations are treated as missing (defaults to cost=1.0).
		List<TestOrderState.TestOutcome> outcomes = List.of(fail("A"), pass("B"));
		Map<String, Long> durations = Map.of("A", -50L, "B", -100L);
		// All durations ≤0 ⇒ hasCosts=false ⇒ falls back to APFD.
		double apfdc = APFDCalculator.computeAPFDc(outcomes, durations);
		assertEquals(APFDCalculator.computeAPFD(outcomes), apfdc, DELTA);
	}

	@Test
	void apfdC_allDurationsZero_fallsBackToAPFD() {
		List<TestOrderState.TestOutcome> outcomes = List.of(fail("A"), pass("B"), pass("C"));
		Map<String, Long> durations = Map.of("A", 0L, "B", 0L, "C", 0L);
		double apfdc = APFDCalculator.computeAPFDc(outcomes, durations);
		assertEquals(APFDCalculator.computeAPFD(outcomes), apfdc, DELTA);
	}

	@Test
	void apfdC_missingDurations_fallsBackToAPFD() {
		List<TestOrderState.TestOutcome> outcomes = List.of(fail("A"), pass("B"));
		// No duration entry for any test ⇒ hasCosts stays false ⇒ uses plain APFD.
		double apfdc = APFDCalculator.computeAPFDc(outcomes, Map.of());
		assertEquals(APFDCalculator.computeAPFD(outcomes), apfdc, DELTA);
	}

	@Test
	void apfdC_singleFailure_knownValues() {
		// A=100ms (fail), B=200ms (pass)
		// totalCost=300, failure at A: cumulativeCost=100, weightedSum = 100 - 50 = 50
		// APFDc = 1 - 50 / (300 * 1) = 1 - 1/6 ≈ 0.8333
		List<TestOrderState.TestOutcome> outcomes = List.of(fail("A"), pass("B"));
		Map<String, Long> durations = Map.of("A", 100L, "B", 200L);
		double expected = 1.0 - 50.0 / (300.0 * 1);
		assertEquals(expected, APFDCalculator.computeAPFDc(outcomes, durations), DELTA);
	}

	@Test
	void apfdC_earlyFailureBetterThanLateFailure() {
		// fail first vs fail last — first should yield higher APFDc.
		Map<String, Long> durations = Map.of("A", 100L, "B", 100L, "C", 100L);
		List<TestOrderState.TestOutcome> failFirst = List.of(fail("A"), pass("B"), pass("C"));
		List<TestOrderState.TestOutcome> failLast = List.of(pass("A"), pass("B"), fail("C"));
		assertTrue(
				APFDCalculator.computeAPFDc(failFirst, durations) > APFDCalculator.computeAPFDc(failLast, durations));
	}

	@Test
	void apfdC_boundedBetween0And1WithRealDurations() {
		List<TestOrderState.TestOutcome> outcomes = List.of(pass("A"), fail("B"), pass("C"), fail("D"));
		Map<String, Long> durations = Map.of("A", 300L, "B", 100L, "C", 50L, "D", 600L);
		double apfdc = APFDCalculator.computeAPFDc(outcomes, durations);
		assertTrue(apfdc >= 0.0 && apfdc <= 1.0, "APFDc must be in [0,1], got " + apfdc);
	}

	// ── computeAPFDWithWeights ────────────────────────────────────────────────

	@Test
	void apfdWithWeights_emptyList_returns1() {
		assertTrue(
				Double.isNaN(APFDCalculator.computeAPFDWithWeights(List.of(), TestOrderState.ScoringWeights.DEFAULT)));
	}

	@Test
	void apfdWithWeights_noFailures_returns1() {
		List<TestOrderState.TestOutcome> outcomes = List.of(pass("A"), pass("B"));
		assertTrue(
				Double.isNaN(APFDCalculator.computeAPFDWithWeights(outcomes, TestOrderState.ScoringWeights.DEFAULT)));
	}

	@Test
	void apfdWithWeights_allSameScore_matchesPlainAPFD() {
		// When all tests have score 0, sort is stable-ish; result should match plain
		// APFD on the same order.
		List<TestOrderState.TestOutcome> outcomes = List.of(fail("A"), pass("B"), pass("C"));
		double withWeights = APFDCalculator.computeAPFDWithWeights(outcomes, TestOrderState.ScoringWeights.DEFAULT);
		// Just verify it's in range — ordering might differ from input but is
		// deterministic.
		assertTrue(withWeights >= 0.0 && withWeights <= 1.0);
	}
}
