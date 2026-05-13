package me.bechberger.testorder.ops.detection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

class ReverseOrderAlgorithmTest {

	@Test
	void detectsVictimInReversedOrder() {
		// Victim passes when A is before it, fails when A is after
		TestRunner runner = order -> {
			Set<String> passed = new HashSet<>();
			Set<String> failed = new HashSet<>();
			for (String t : order) {
				if (t.equals("Victim") && order.indexOf("A") > order.indexOf("Victim")) {
					// A is after Victim → reversed scenario where some polluter earlier fails
					failed.add(t);
				} else {
					passed.add(t);
				}
			}
			return new TestRunner.TestRunResult(order, passed, failed);
		};

		ReverseOrderAlgorithm algo = new ReverseOrderAlgorithm();
		DetectionContext ctx = new DetectionContext(ConflictGraph.empty(), null, null, List.of("A", "Victim", "C"),
				Set.of("A", "Victim", "C"), runner, Long.MAX_VALUE, 42L);

		List<ODResult> results = algo.detect(ctx);

		assertEquals(1, results.size());
		assertEquals("Victim", results.get(0).victim());
		assertEquals(ODType.VICTIM, results.get(0).type());
	}

	@Test
	void noFindingsWhenAllPassInReverse() {
		TestRunner runner = order -> new TestRunner.TestRunResult(order, new HashSet<>(order), Set.of());

		ReverseOrderAlgorithm algo = new ReverseOrderAlgorithm();
		DetectionContext ctx = new DetectionContext(ConflictGraph.empty(), null, null, List.of("A", "B", "C"),
				Set.of("A", "B", "C"), runner, Long.MAX_VALUE, 42L);

		List<ODResult> results = algo.detect(ctx);

		assertTrue(results.isEmpty());
	}

	@Test
	void ignoresAlreadyFailingTests() {
		// A test that isn't in passingTests should be ignored even if it fails
		TestRunner runner = order -> {
			Set<String> passed = new HashSet<>(order);
			Set<String> failed = new HashSet<>();
			passed.remove("Flaky");
			failed.add("Flaky");
			return new TestRunner.TestRunResult(order, passed, failed);
		};

		ReverseOrderAlgorithm algo = new ReverseOrderAlgorithm();
		DetectionContext ctx = new DetectionContext(ConflictGraph.empty(), null, null, List.of("A", "Flaky", "C"),
				Set.of("A", "C"), // Flaky is NOT in passing tests
				runner, Long.MAX_VALUE, 42L);

		List<ODResult> results = algo.detect(ctx);

		assertTrue(results.isEmpty());
	}

	@Test
	void estimatedRunsIsOne() {
		ReverseOrderAlgorithm algo = new ReverseOrderAlgorithm();
		assertEquals(1, algo.estimatedRuns(100, 50));
	}
}
