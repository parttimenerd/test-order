package me.bechberger.testorder.ops.detection;

import java.util.ArrayList;
import java.util.List;

import me.bechberger.testorder.TestOrderLogger;
import me.bechberger.testorder.ops.detection.TestRunner.TestRunResult;

/**
 * Delta debugging (ddmin) implementation. Finds the 1-minimal subset of tests
 * that causes a victim to fail.
 */
public final class DeltaDebugging {

	private DeltaDebugging() {
	}

	/**
	 * Find the minimal set of tests from {@code candidates} that, when placed
	 * before {@code victim}, cause it to fail.
	 *
	 * @param candidates
	 *            potential polluter tests
	 * @param victim
	 *            the test that fails
	 * @param runner
	 *            test execution engine
	 * @param runBudget
	 *            maximum number of runs to spend
	 * @return minimal polluter set, or empty if none found within budget
	 */
	public static List<String> minimize(List<String> candidates, String victim, TestRunner runner, int runBudget) {
		if (candidates.isEmpty())
			return List.of();

		// First verify: does [all candidates, victim] actually fail?
		List<String> fullOrder = new ArrayList<>(candidates);
		fullOrder.add(victim);
		TestRunResult result = runner.run(fullOrder);
		runBudget--;
		if (!result.failed(victim)) {
			return List.of(); // Can't reproduce the failure
		}

		if (candidates.size() == 1) {
			return candidates;
		}

		return ddmin(candidates, victim, runner, 2, runBudget);
	}

	private static List<String> ddmin(List<String> candidates, String victim, TestRunner runner, int partitions,
			int runBudget) {
		if (candidates.size() == 1) {
			return candidates;
		}
		if (runBudget <= 0) {
			// Budget exhausted — returning un-minimized list; caller cannot distinguish
			// from success
			TestOrderLogger
					.warn("[ddmin] budget exhausted, returning un-minimized list of " + candidates.size() + " tests");
			return candidates;
		}

		int chunkSize = Math.max(1, candidates.size() / partitions);
		List<List<String>> chunks = partition(candidates, chunkSize);

		// Try each chunk: does [chunk, victim] fail?
		for (List<String> chunk : chunks) {
			if (runBudget <= 0)
				break;

			List<String> order = new ArrayList<>(chunk);
			order.add(victim);
			TestRunResult result = runner.run(order);
			runBudget--;

			if (result.failed(victim)) {
				// This chunk alone is sufficient — recurse into it
				if (chunk.size() == 1)
					return chunk;
				return ddmin(chunk, victim, runner, 2, runBudget);
			}
		}

		// No single chunk is sufficient — try complements
		for (int i = 0; i < chunks.size(); i++) {
			if (runBudget <= 0)
				break;

			List<String> complement = new ArrayList<>();
			for (int j = 0; j < chunks.size(); j++) {
				if (j != i)
					complement.addAll(chunks.get(j));
			}

			List<String> order = new ArrayList<>(complement);
			order.add(victim);
			TestRunResult result = runner.run(order);
			runBudget--;

			if (result.failed(victim)) {
				// The complement fails — recurse with more partitions
				return ddmin(complement, victim, runner, Math.max(2, partitions - 1), runBudget);
			}
		}

		// Neither chunks nor complements work alone — increase granularity
		if (partitions < candidates.size()) {
			return ddmin(candidates, victim, runner, Math.min(candidates.size(), partitions * 2), runBudget);
		}

		// Can't minimize further
		return candidates;
	}

	private static List<List<String>> partition(List<String> list, int chunkSize) {
		List<List<String>> chunks = new ArrayList<>();
		for (int i = 0; i < list.size(); i += chunkSize) {
			chunks.add(new ArrayList<>(list.subList(i, Math.min(i + chunkSize, list.size()))));
		}
		return chunks;
	}
}
