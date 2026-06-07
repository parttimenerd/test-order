package me.bechberger.testorder.ops.detection;

import java.util.*;

/**
 * Manages ordering constraints derived from OD detection results. Supports
 * MUST_PRECEDE and MUST_NOT_PRECEDE constraints and produces a topologically
 * valid ordering.
 */
public class OrderConstraintManager {

	public enum ConstraintType {
		/** A must come before B (setter → brittle). */
		MUST_PRECEDE,
		/** A must NOT come immediately before B (polluter → victim). */
		MUST_NOT_PRECEDE
	}

	public record Constraint(String testA, String testB, ConstraintType type, String reason) {
	}

	private final List<Constraint> constraints = new ArrayList<>();

	public void addMustPrecede(String setter, String brittle, String reason) {
		constraints.add(new Constraint(setter, brittle, ConstraintType.MUST_PRECEDE, reason));
	}

	public void addMustNotPrecede(String polluter, String victim, String reason) {
		constraints.add(new Constraint(polluter, victim, ConstraintType.MUST_NOT_PRECEDE, reason));
	}

	public List<Constraint> constraints() {
		return Collections.unmodifiableList(constraints);
	}

	/**
	 * Apply constraints from OD detection results.
	 */
	public void applyResults(List<ODResult> results) {
		for (ODResult result : results) {
			if (result.type() == ODType.BRITTLE && result.dependencyChain().size() >= 2) {
				String setter = result.dependencyChain().get(0);
				addMustPrecede(setter, result.victim(), "Brittle: " + result.victim() + " needs " + setter);
			} else if (result.type() == ODType.VICTIM && result.dependencyChain().size() >= 2) {
				String polluter = result.dependencyChain().get(0);
				addMustNotPrecede(polluter, result.victim(), "Victim: " + polluter + " pollutes " + result.victim());
			}
		}
	}

	/**
	 * Produce an ordering that respects MUST_PRECEDE constraints via topological
	 * sort. Falls back to the reference order when no constraints are violated.
	 *
	 * @param referenceOrder
	 *            the original test order to base on
	 * @return constrained order, or the reference order if no constraints apply
	 */
	public List<String> buildConstrainedOrder(List<String> referenceOrder) {
		// Build adjacency list from MUST_PRECEDE constraints
		Map<String, Set<String>> successors = new HashMap<>();
		Map<String, Integer> inDegree = new HashMap<>();

		for (String test : referenceOrder) {
			successors.putIfAbsent(test, new HashSet<>());
			inDegree.putIfAbsent(test, 0);
		}

		for (Constraint c : constraints) {
			if (c.type != ConstraintType.MUST_PRECEDE)
				continue;
			if (!inDegree.containsKey(c.testA) || !inDegree.containsKey(c.testB))
				continue;
			if (successors.get(c.testA).add(c.testB)) {
				inDegree.merge(c.testB, 1, Integer::sum);
			}
		}

		// Kahn's algorithm with reference-order tie-breaking
		Map<String, Integer> refIndex = new HashMap<>();
		for (int i = 0; i < referenceOrder.size(); i++) {
			refIndex.put(referenceOrder.get(i), i);
		}

		PriorityQueue<String> ready = new PriorityQueue<>(
				Comparator.comparingInt(t -> refIndex.getOrDefault(t, Integer.MAX_VALUE)));

		for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
			if (entry.getValue() == 0) {
				ready.add(entry.getKey());
			}
		}

		List<String> result = new ArrayList<>();
		while (!ready.isEmpty()) {
			String test = ready.poll();
			result.add(test);

			for (String succ : successors.getOrDefault(test, Set.of())) {
				int newDeg = inDegree.merge(succ, -1, Integer::sum);
				if (newDeg == 0) {
					ready.add(succ);
				}
			}
		}

		if (result.size() < referenceOrder.size()) {
			// Cycle detected — fall back to reference order
			return new ArrayList<>(referenceOrder);
		}

		// Apply MUST_NOT_PRECEDE: swap adjacent violating pairs
		applyMustNotPrecede(result);

		return result;
	}

	private void applyMustNotPrecede(List<String> order) {
		Set<String> mustNotPairs = new HashSet<>();
		for (Constraint c : constraints) {
			if (c.type == ConstraintType.MUST_NOT_PRECEDE) {
				mustNotPairs.add(c.testA + " → " + c.testB);
			}
		}

		// Simple greedy: if polluter is immediately before victim, separate them.
		// After any swap we re-check the same position once (i--) so cascading
		// violations are not missed (e.g. P→V1 and P→V2: after V1 is moved away,
		// we re-check whether the new element at i+1 is also a victim of P).
		// A second re-check is skipped to avoid cycling on contradictory constraints.
		boolean recheck = false;
		for (int i = 0; i < order.size() - 1; i++) {
			String key = order.get(i) + " → " + order.get(i + 1);
			if (mustNotPairs.contains(key)) {
				if (i + 2 < order.size()) {
					// Swap victim with the element after it (move victim one step later)
					String temp = order.get(i + 1);
					order.set(i + 1, order.get(i + 2));
					order.set(i + 2, temp);
				} else {
					// Pair is at the end — move victim before the polluter instead
					String victim = order.remove(i + 1);
					order.add(i, victim);
				}
				// Re-check the same position once; skip double-recheck to avoid cycling.
				if (!recheck) {
					i--;
					recheck = true;
				} else {
					recheck = false;
				}
			} else {
				recheck = false;
			}
		}
	}
}
