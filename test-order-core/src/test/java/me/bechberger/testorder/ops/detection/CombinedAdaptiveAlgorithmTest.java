package me.bechberger.testorder.ops.detection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

class CombinedAdaptiveAlgorithmTest {

	/**
	 * Simulates a test suite where "Polluter" pollutes state causing "Victim" to
	 * fail.
	 */
	private TestRunner polluterRunner() {
		return order -> {
			Set<String> passed = new HashSet<>();
			Set<String> failed = new HashSet<>();
			boolean polluted = false;

			for (String test : order) {
				if (test.equals("Polluter")) {
					polluted = true;
					passed.add(test);
				} else if (test.equals("Victim")) {
					if (polluted)
						failed.add(test);
					else
						passed.add(test);
				} else {
					passed.add(test);
				}
			}
			return new TestRunner.TestRunResult(order, passed, failed);
		};
	}

	@Test
	void detectsPolluterVictimPair() {
		CombinedAdaptiveAlgorithm algo = new CombinedAdaptiveAlgorithm();

		// Reference order has Victim before Polluter → Victim passes
		DetectionContext ctx = new DetectionContext(ConflictGraph.empty(), null, null,
				List.of("Victim", "Polluter", "Other"), Set.of("Victim", "Polluter", "Other"), polluterRunner(),
				Long.MAX_VALUE, 42L);

		List<ODResult> results = algo.detect(ctx);

		// Should find the Victim as an OD test
		assertTrue(results.stream().anyMatch(r -> r.victim().equals("Victim")),
				"Should detect Victim as order-dependent");
	}

	@Test
	void noFindingsForStableTests() {
		TestRunner stableRunner = order -> new TestRunner.TestRunResult(order, new HashSet<>(order), Set.of());

		CombinedAdaptiveAlgorithm algo = new CombinedAdaptiveAlgorithm();
		DetectionContext ctx = new DetectionContext(ConflictGraph.empty(), null, null, List.of("A", "B", "C"),
				Set.of("A", "B", "C"), stableRunner, Long.MAX_VALUE, 42L);

		List<ODResult> results = algo.detect(ctx);

		assertTrue(results.isEmpty());
	}

	@Test
	void respectsTimeBudget() {
		int[] runCount = { 0 };
		TestRunner runner = order -> {
			runCount[0]++;
			return new TestRunner.TestRunResult(order, new HashSet<>(order), Set.of());
		};

		CombinedAdaptiveAlgorithm algo = new CombinedAdaptiveAlgorithm();
		// Set deadline to 0 (already expired)
		DetectionContext ctx = new DetectionContext(ConflictGraph.empty(), null, null, List.of("A", "B", "C"),
				Set.of("A", "B", "C"), runner, 0L, 42L); // deadline = 0 → immediately expired

		algo.detect(ctx);

		// Should do at most the initial reverse-order run
		assertTrue(runCount[0] <= 1, "Should stop quickly when time exhausted, got " + runCount[0]);
	}

	@Test
	void detectsBrittleTest() {
		// Brittle test needs "Setter" to have run first
		TestRunner runner = order -> {
			Set<String> passed = new HashSet<>();
			Set<String> failed = new HashSet<>();
			boolean setup = false;

			for (String test : order) {
				if (test.equals("Setter")) {
					setup = true;
					passed.add(test);
				} else if (test.equals("Brittle")) {
					if (setup)
						passed.add(test);
					else
						failed.add(test);
				} else {
					passed.add(test);
				}
			}
			return new TestRunner.TestRunResult(order, passed, failed);
		};

		CombinedAdaptiveAlgorithm algo = new CombinedAdaptiveAlgorithm();
		// Reference order: Setter runs first → Brittle passes
		DetectionContext ctx = new DetectionContext(ConflictGraph.empty(), null, null,
				List.of("Setter", "Brittle", "Other"), Set.of("Setter", "Brittle", "Other"), runner, Long.MAX_VALUE,
				42L);

		List<ODResult> results = algo.detect(ctx);

		// Reverse order puts Brittle before Setter → Brittle fails
		// Algorithm should detect this
		assertTrue(results.stream().anyMatch(r -> r.victim().equals("Brittle")),
				"Should detect Brittle as order-dependent");
	}

	@Test
	void nameIsCombinedAdaptive() {
		assertEquals("combined-adaptive", new CombinedAdaptiveAlgorithm().name());
	}
}
