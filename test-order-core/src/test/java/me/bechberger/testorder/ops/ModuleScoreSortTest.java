package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import me.bechberger.testorder.ops.ReactorOrderOperation.ModuleScore;

/**
 * Tests for {@link ModuleScore#compareTo} sort-key correctness, including the
 * stable final tie-break by {@code moduleId} (BUG-184).
 *
 * <p>
 * Before BUG-184, {@code ModuleScore.compareTo} had no tie-breaking rule when
 * all three numeric keys (affectedTestCount, sumTestScores, maxTestScore) were
 * equal. The sort was then non-deterministic — the resulting order depended on
 * the JVM sort algorithm's behaviour on equal elements, which varies across JVM
 * versions and input orderings. The fix adds an alphabetically ascending
 * {@code moduleId} final tie-break, matching the identical rule used by
 * {@code ShowWorkflow.ModuleAggregate.compareByPriority} so the reactor
 * ordering and the show preview remain consistent.
 */
class ModuleScoreSortTest {

	private static ModuleScore score(String mid, int affected, long sum, int max) {
		return new ModuleScore(mid, max, sum, affected, affected, List.of());
	}

	@Test
	void affectedCountIsPrimary() {
		ModuleScore high = score("g:high", 10, 50, 5);
		ModuleScore low = score("g:low", 3, 999, 999);
		List<ModuleScore> list = new ArrayList<>(List.of(low, high));
		Collections.sort(list);
		assertEquals("g:high", list.get(0).moduleId(), "higher affectedCount must sort first");
	}

	@Test
	void sumIsTiebreaker_whenAffectedEqual() {
		ModuleScore a = score("g:a", 5, 100, 99);
		ModuleScore b = score("g:b", 5, 200, 50);
		List<ModuleScore> list = new ArrayList<>(List.of(a, b));
		Collections.sort(list);
		assertEquals("g:b", list.get(0).moduleId(), "higher sumTestScores must sort first when affected counts tie");
	}

	@Test
	void maxIsTiebreaker_whenAffectedAndSumEqual() {
		ModuleScore a = score("g:a", 5, 200, 50);
		ModuleScore b = score("g:b", 5, 200, 80);
		List<ModuleScore> list = new ArrayList<>(List.of(a, b));
		Collections.sort(list);
		assertEquals("g:b", list.get(0).moduleId(), "higher maxTestScore must sort first when affected and sum tie");
	}

	/**
	 * BUG-184: when all three numeric keys are equal, the order must be
	 * deterministic and alphabetical by moduleId (ascending). Before the fix, there
	 * was no tie-break and the sort produced an unpredictable ordering.
	 */
	@Test
	void moduleIdIsStableFinalTiebreaker_whenAllNumericKeysEqual() {
		ModuleScore alpha = score("g:alpha", 5, 200, 80);
		ModuleScore beta = score("g:beta", 5, 200, 80);
		ModuleScore gamma = score("g:gamma", 5, 200, 80);

		// Shuffle to ensure the test doesn't pass by accident
		List<ModuleScore> list = new ArrayList<>(List.of(gamma, alpha, beta));
		Collections.sort(list);

		assertEquals(List.of("g:alpha", "g:beta", "g:gamma"), list.stream().map(ModuleScore::moduleId).toList(),
				"equal-priority modules must sort alphabetically by moduleId (BUG-184)");
	}

	@Test
	void priorityOrder_isDeterministicForEqualModules() {
		// Verify via ReactorOrderResult.priorityOrder() which calls
		// ModuleScore.compareTo
		List<ModuleScore> scores = List.of(score("g:z-module", 5, 100, 10), score("g:a-module", 5, 100, 10),
				score("g:m-module", 5, 100, 10));
		ReactorOrderOperation.ReactorOrderResult result = new ReactorOrderOperation.ReactorOrderResult(scores,
				java.util.Set.of(), java.util.Set.of());

		List<String> order = result.priorityOrder();
		assertEquals(List.of("g:a-module", "g:m-module", "g:z-module"), order,
				"priorityOrder() must produce deterministic alphabetical order for equal-priority modules (BUG-184)");
	}
}
