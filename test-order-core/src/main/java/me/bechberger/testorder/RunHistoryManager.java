package me.bechberger.testorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Encapsulates capped run-history storage and sampling logic.
 */
final class RunHistoryManager {

	private final List<TestOrderState.RunRecord> runs = new ArrayList<>();

	List<TestOrderState.RunRecord> runs() {
		return Collections.unmodifiableList(runs);
	}

	void add(TestOrderState.RunRecord record, int maxRuns) {
		runs.add(record);
		replace(thinRunHistory(runs, maxRuns));
	}

	void addRaw(TestOrderState.RunRecord record) {
		runs.add(record);
	}

	void replace(List<TestOrderState.RunRecord> updatedRuns) {
		runs.clear();
		runs.addAll(updatedRuns);
	}

	void trimToMax(int maxRuns) {
		replace(thinRunHistory(runs, maxRuns));
	}

	static List<TestOrderState.RunRecord> thinRunHistory(List<TestOrderState.RunRecord> sourceRuns, int maxRuns) {
		if (maxRuns <= 0) {
			throw new IllegalArgumentException("maxRuns must be > 0: " + maxRuns);
		}
		if (sourceRuns.size() <= maxRuns) {
			return new ArrayList<>(sourceRuns);
		}
		int recentKeep = Math.min(Math.max(1, maxRuns / 2), maxRuns);
		int historicalSlots = maxRuns - recentKeep;
		int recentStart = Math.max(0, sourceRuns.size() - recentKeep);
		List<TestOrderState.RunRecord> result = new ArrayList<>(maxRuns);
		sampleHistoricalRuns(result, sourceRuns.subList(0, recentStart), historicalSlots);
		result.addAll(sourceRuns.subList(recentStart, sourceRuns.size()));
		return result;
	}

	private static void sampleHistoricalRuns(List<TestOrderState.RunRecord> target,
			List<TestOrderState.RunRecord> historicalRuns, int slots) {
		if (slots <= 0 || historicalRuns.isEmpty()) {
			return;
		}
		if (historicalRuns.size() <= slots) {
			target.addAll(historicalRuns);
			return;
		}
		Set<Integer> indices = new LinkedHashSet<>();
		if (slots == 1) {
			indices.add(historicalRuns.size() - 1);
		} else {
			for (int i = 0; i < slots; i++) {
				int index = (int) Math.round(i * (historicalRuns.size() - 1.0) / (slots - 1.0));
				indices.add(index);
			}
			for (int index = 0; indices.size() < slots && index < historicalRuns.size(); index++) {
				indices.add(index);
			}
		}
		indices.stream().sorted().forEach(index -> target.add(historicalRuns.get(index)));
	}
}
