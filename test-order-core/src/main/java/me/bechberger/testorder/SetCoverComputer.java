package me.bechberger.testorder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Generic greedy set-cover computation with a priority queue and lazy count
 * updates.
 */
public final class SetCoverComputer<T, C> {

	public record Result<T>(List<T> order, Map<T, Integer> initialCoverCounts) {
	}

	private record QueueEntry<T>(T item, int uncoveredCount) {
	}

	private final Map<T, Set<C>> coverage;
	private final Set<C> universe;

	public SetCoverComputer(Map<T, Set<C>> coverage, Collection<C> universe) {
		this.coverage = new LinkedHashMap<>(coverage);
		this.universe = new HashSet<>(universe);
	}

	public Result<T> compute() {
		if (coverage.isEmpty() || universe.isEmpty()) {
			return new Result<>(List.of(), Map.of());
		}

		Map<C, List<T>> coveredBy = new HashMap<>();
		Map<T, Integer> remainingCounts = new HashMap<>(coverage.size());
		Map<T, Integer> initialCounts = new HashMap<>(coverage.size());
		for (var entry : coverage.entrySet()) {
			Set<C> covered = entry.getValue();
			int intersectionSize = 0;
			for (C element : covered) {
				if (universe.contains(element)) {
					intersectionSize++;
				}
				coveredBy.computeIfAbsent(element, ignored -> new ArrayList<>()).add(entry.getKey());
			}
			remainingCounts.put(entry.getKey(), intersectionSize);
			initialCounts.put(entry.getKey(), intersectionSize);
		}

		PriorityQueue<QueueEntry<T>> queue = new PriorityQueue<>(
				(a, b) -> Integer.compare(b.uncoveredCount(), a.uncoveredCount()));
		for (var entry : remainingCounts.entrySet()) {
			queue.add(new QueueEntry<>(entry.getKey(), entry.getValue()));
		}

		Set<C> uncovered = new HashSet<>(universe);
		Set<T> selected = new HashSet<>();
		List<T> order = new ArrayList<>();

		while (!queue.isEmpty() && !uncovered.isEmpty()) {
			QueueEntry<T> entry = queue.poll();
			int currentCount = remainingCounts.getOrDefault(entry.item(), 0);
			if (currentCount != entry.uncoveredCount()) {
				queue.add(new QueueEntry<>(entry.item(), currentCount));
				continue;
			}
			if (currentCount <= 0 || !selected.add(entry.item())) {
				continue;
			}
			order.add(entry.item());
			for (C newlyCovered : coverage.getOrDefault(entry.item(), Set.of())) {
				if (uncovered.remove(newlyCovered)) {
					for (T affected : coveredBy.getOrDefault(newlyCovered, List.of())) {
						if (!selected.contains(affected)) {
							remainingCounts.computeIfPresent(affected, (ignored, count) -> count - 1);
						}
					}
				}
			}
		}

		return new Result<>(order, initialCounts);
	}
}
