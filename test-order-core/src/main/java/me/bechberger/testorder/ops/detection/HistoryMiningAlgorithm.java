package me.bechberger.testorder.ops.detection;

import java.util.*;
import java.util.stream.Collectors;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.ops.detection.TestRunner.TestRunResult;

/**
 * Algorithm 6: History Mining. Zero-cost algorithm that mines existing run
 * history to find OD suspects using RankFO-style differential scoring.
 */
public class HistoryMiningAlgorithm implements DetectionAlgorithm {

	@Override
	public String name() {
		return "history-mining";
	}

	@Override
	public Set<Prerequisite> prerequisites() {
		return Set.of(Prerequisite.MULTIPLE_RUNS);
	}

	@Override
	public int estimatedRuns(int testCount, int conflictEdges) {
		return 0; // Pure analysis of existing data
	}

	@Override
	public List<ODResult> detect(DetectionContext ctx) {
		List<ODResult> findings = new ArrayList<>();
		if (ctx.state() == null || ctx.state().runs().size() < 3) {
			return findings;
		}

		// Find intermittent tests (both passed and failed across runs)
		Map<String, List<Boolean>> outcomes = new HashMap<>();
		Map<String, List<List<String>>> predecessorMap = new HashMap<>();

		for (TestOrderState.RunRecord run : ctx.state().runs()) {
			List<String> order = run.outcomes().stream().map(TestOrderState.TestOutcome::testClass).toList();

			for (TestOrderState.TestOutcome outcome : run.outcomes()) {
				outcomes.computeIfAbsent(outcome.testClass(), k -> new ArrayList<>()).add(outcome.failed());

				if (outcome.failed()) {
					int idx = order.indexOf(outcome.testClass());
					if (idx > 0) {
						predecessorMap.computeIfAbsent(outcome.testClass(), k -> new ArrayList<>())
								.add(order.subList(0, idx));
					}
				}
			}
		}

		// RankFO: for each intermittent test, rank which predecessors correlate with
		// failure
		for (Map.Entry<String, List<Boolean>> entry : outcomes.entrySet()) {
			String test = entry.getKey();
			List<Boolean> results = entry.getValue();

			long failCount = results.stream().filter(Boolean::booleanValue).count();
			long passCount = results.size() - failCount;
			if (failCount == 0 || passCount == 0)
				continue; // Not intermittent

			List<List<String>> failPredecessors = predecessorMap.getOrDefault(test, List.of());
			if (failPredecessors.isEmpty())
				continue;

			// Score each predecessor by (appears-in-fail-runs / fail-count) -
			// (appears-in-pass-runs / pass-count)
			Map<String, Double> scores = rankFO(test, ctx.state().runs());

			// Top candidate with score > 0.3
			scores.entrySet().stream().filter(e -> e.getValue() > 0.3).max(Map.Entry.comparingByValue())
					.ifPresent(
							best -> findings
									.add(new ODResult(test, ODType.VICTIM, List.of(best.getKey(), test),
											String.format("History-mined: %s likely pollutes %s (RankFO=%.2f)",
													best.getKey(), test, best.getValue()),
											Math.min(1.0, best.getValue()))));
		}

		return findings;
	}

	private Map<String, Double> rankFO(String victim, List<TestOrderState.RunRecord> runs) {
		Map<String, Integer> appearsInFail = new HashMap<>();
		Map<String, Integer> appearsInPass = new HashMap<>();
		int failRuns = 0;
		int passRuns = 0;

		for (TestOrderState.RunRecord run : runs) {
			List<String> order = run.outcomes().stream().map(TestOrderState.TestOutcome::testClass).toList();
			int victimIdx = order.indexOf(victim);
			if (victimIdx < 0)
				continue;

			boolean failed = run.outcomes().stream().anyMatch(o -> o.testClass().equals(victim) && o.failed());

			List<String> predecessors = order.subList(0, victimIdx);
			if (failed) {
				failRuns++;
				for (String p : predecessors) {
					appearsInFail.merge(p, 1, Integer::sum);
				}
			} else {
				passRuns++;
				for (String p : predecessors) {
					appearsInPass.merge(p, 1, Integer::sum);
				}
			}
		}

		Map<String, Double> scores = new HashMap<>();
		if (failRuns == 0)
			return scores;

		Set<String> allPredecessors = new HashSet<>(appearsInFail.keySet());
		allPredecessors.addAll(appearsInPass.keySet());

		for (String p : allPredecessors) {
			double failRate = appearsInFail.getOrDefault(p, 0) / (double) failRuns;
			double passRate = passRuns > 0 ? appearsInPass.getOrDefault(p, 0) / (double) passRuns : 0.0;
			scores.put(p, failRate - passRate);
		}

		return scores;
	}
}
