package me.bechberger.testorder.ops.detection;

import java.util.*;

import me.bechberger.testorder.ops.detection.TestRunner.TestRunResult;

/**
 * Searches for a "cleaner" test that neutralizes a polluter's effect. Given
 * (polluter → victim), finds a test C such that [polluter, C, victim] passes.
 */
public final class CleanerSearch {

	private CleanerSearch() {
	}

	/**
	 * Find a cleaner test that neutralizes the polluter for the given victim.
	 *
	 * @param polluter
	 *            the test that pollutes state
	 * @param victim
	 *            the test that fails after polluter
	 * @param candidates
	 *            potential cleaner tests
	 * @param runner
	 *            test execution engine
	 * @param runBudget
	 *            maximum number of runs to spend
	 * @return the cleaner test, or empty if none found
	 */
	public static Optional<String> find(String polluter, String victim, List<String> candidates, TestRunner runner,
			int runBudget) {
		// First verify the problem exists
		TestRunResult baseline = runner.run(List.of(polluter, victim));
		if (!baseline.failed(victim)) {
			return Optional.empty(); // No problem to fix
		}
		runBudget--;

		// Try candidates that might clean up the state
		for (String candidate : candidates) {
			if (runBudget <= 0)
				break;
			if (candidate.equals(polluter) || candidate.equals(victim))
				continue;

			TestRunResult result = runner.run(List.of(polluter, candidate, victim));
			runBudget--;

			if (result.passed(victim)) {
				return Optional.of(candidate);
			}
		}

		return Optional.empty();
	}

	/**
	 * Find all cleaners for a given polluter-victim pair (up to budget).
	 */
	public static List<String> findAll(String polluter, String victim, List<String> candidates, TestRunner runner,
			int runBudget) {
		List<String> cleaners = new ArrayList<>();

		TestRunResult baseline = runner.run(List.of(polluter, victim));
		if (!baseline.failed(victim))
			return cleaners;
		runBudget--;

		for (String candidate : candidates) {
			if (runBudget <= 0)
				break;
			if (candidate.equals(polluter) || candidate.equals(victim))
				continue;

			TestRunResult result = runner.run(List.of(polluter, candidate, victim));
			runBudget--;

			if (result.passed(victim)) {
				cleaners.add(candidate);
			}
		}

		return cleaners;
	}
}
