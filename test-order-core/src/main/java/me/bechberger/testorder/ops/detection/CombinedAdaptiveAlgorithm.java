package me.bechberger.testorder.ops.detection;

import java.util.*;
import java.util.stream.Collectors;

import me.bechberger.testorder.ops.detection.ConflictGraph.ConflictEdge;
import me.bechberger.testorder.ops.detection.TestRunner.TestRunResult;

/**
 * Algorithm 8: Combined Adaptive detection. The primary detection algorithm
 * that intelligently combines multiple strategies with an "isolate first"
 * protocol and adaptive budget allocation.
 *
 * <p>
 * Strategy:
 * <ol>
 * <li>Start with cheap detection (reverse order, history mining)</li>
 * <li>Use dependency info to prioritize high-weight conflict edges</li>
 * <li>For each discovered victim, immediately isolate the polluter via
 * ddmin</li>
 * <li>Adapt strategy based on findings (more brittles → switch to
 * PFAST-style)</li>
 * </ol>
 */
public class CombinedAdaptiveAlgorithm implements DetectionAlgorithm {

	/** Actions the algorithm can schedule, sorted by priority. */
	private enum ActionType {
		ISOLATE_PAIR, // Verify pair [polluter, victim] → OD or not
		CONFIRM_BRITTLE, // Verify [setter, brittle] → passes together
		MINIMIZE, // ddmin on known-failing set
		PROBE_EDGE, // Test a conflict edge
		RANDOM_PROBE, // Random shuffles for discovery
		EXCLUSION_PROBE // PFAST-style removal
	}

	private record Action(ActionType type, double priority, String victim,
			List<String> candidates) implements Comparable<Action> {
		@Override
		public int compareTo(Action o) {
			return Double.compare(o.priority, this.priority); // Higher priority first
		}
	}

	@Override
	public String name() {
		return "combined-adaptive";
	}

	@Override
	public Set<Prerequisite> prerequisites() {
		return Set.of(Prerequisite.PASSING_REFERENCE);
	}

	@Override
	public int estimatedRuns(int testCount, int conflictEdges) {
		// Budget: sqrt(tests) * 10 + edges (capped at 500)
		return Math.min(500, (int) (Math.sqrt(testCount) * 10) + conflictEdges);
	}

	@Override
	public List<ODResult> detect(DetectionContext ctx) {
		List<ODResult> findings = new ArrayList<>();
		Map<String, TestKnowledge> knowledge = new HashMap<>();
		PriorityQueue<Action> workQueue = new PriorityQueue<>();
		Random rng = new Random(ctx.randomSeed());

		// Phase 1: Cheap initial screening
		phase1_screen(ctx, workQueue, knowledge, findings);

		// Phase 2: Seed from conflict graph
		if (ctx.graph() != null && !ctx.graph().isEmpty()) {
			seedFromConflictGraph(ctx, workQueue, knowledge);
		}

		// Phase 2b: Always include exclusion probes for high-connectivity tests
		// (works even without execution order control, e.g. when Surefire picks its own
		// order)
		addInitialExclusionProbes(ctx, workQueue, knowledge);

		// Phase 3: Adaptive work loop
		int runsUsed = 0;
		int maxRuns = estimatedRuns(ctx.referenceOrder().size(), ctx.graph() != null ? ctx.graph().edgeCount() : 0);

		while (!workQueue.isEmpty() && !ctx.timeBudgetExhausted() && runsUsed < maxRuns) {
			Action action = workQueue.poll();
			runsUsed += executeAction(action, ctx, workQueue, knowledge, findings, rng);

			// Adaptation: if we're finding lots of brittles, add exclusion probes
			long brittleCount = findings.stream().filter(r -> r.type() == ODType.BRITTLE).count();
			if (brittleCount > 2 && workQueue.stream().noneMatch(a -> a.type == ActionType.EXCLUSION_PROBE)) {
				addExclusionProbes(ctx, workQueue, knowledge);
			}
		}

		// Phase 4: Final random probes if budget remains
		while (!ctx.timeBudgetExhausted() && runsUsed < maxRuns) {
			List<String> shuffled = new ArrayList<>(ctx.referenceOrder());
			Collections.shuffle(shuffled, rng);
			TestRunResult result = ctx.run(shuffled, findings.size());
			runsUsed++;

			for (String failed : result.failedTests()) {
				if (!ctx.passingTests().contains(failed))
					continue;
				TestKnowledge tk = knowledge.computeIfAbsent(failed, k -> new TestKnowledge());
				tk.incrementFailureCount();

				if (!tk.confirmedPolluters().isEmpty())
					continue; // Already resolved

				// Schedule minimization
				List<String> preds = result.predecessorsOf(failed);
				workQueue.add(new Action(ActionType.MINIMIZE, 8.0, failed, preds));
			}

			if (workQueue.isEmpty())
				break;
			// Process any newly scheduled actions
			while (!workQueue.isEmpty() && !ctx.timeBudgetExhausted() && runsUsed < maxRuns) {
				runsUsed += executeAction(workQueue.poll(), ctx, workQueue, knowledge, findings, rng);
			}
		}

		return findings;
	}

	private void phase1_screen(DetectionContext ctx, PriorityQueue<Action> workQueue,
			Map<String, TestKnowledge> knowledge, List<ODResult> findings) {
		// Run reverse order — cheapest possible OD discovery
		List<String> reversed = new ArrayList<>(ctx.referenceOrder());
		Collections.reverse(reversed);
		TestRunResult result = ctx.run(reversed, findings.size());

		for (String failed : result.failedTests()) {
			if (!ctx.passingTests().contains(failed))
				continue;
			TestKnowledge tk = knowledge.computeIfAbsent(failed, k -> new TestKnowledge());
			tk.incrementFailureCount();

			// Schedule isolation
			List<String> preds = result.predecessorsOf(failed);
			workQueue.add(new Action(ActionType.MINIMIZE, 9.0, failed, preds));
		}

		// Mine history if available
		if (ctx.state() != null && ctx.state().runs().size() >= 3) {
			HistoryMiningAlgorithm histMiner = new HistoryMiningAlgorithm();
			List<ODResult> histResults = histMiner.detect(ctx);
			for (ODResult hr : histResults) {
				// Schedule pair verification for history-mined suspects
				if (hr.dependencyChain().size() >= 2) {
					String polluter = hr.dependencyChain().get(0);
					workQueue.add(new Action(ActionType.ISOLATE_PAIR, 7.5, hr.victim(), List.of(polluter)));
				}
			}
		}
	}

	private void seedFromConflictGraph(DetectionContext ctx, PriorityQueue<Action> workQueue,
			Map<String, TestKnowledge> knowledge) {
		List<ConflictEdge> edges = ctx.graph().edges();
		for (ConflictEdge edge : edges) {
			if (workQueue.size() > 200)
				break; // Cap initial queue size

			// For each edge, schedule probes for both directions
			double priority = 5.0 + edge.weight() * 3.0; // Higher weight → higher priority
			workQueue.add(new Action(ActionType.PROBE_EDGE, priority, edge.testB(), List.of(edge.testA())));
			workQueue.add(new Action(ActionType.PROBE_EDGE, priority - 0.1, edge.testA(), List.of(edge.testB())));
		}
	}

	private int executeAction(Action action, DetectionContext ctx, PriorityQueue<Action> workQueue,
			Map<String, TestKnowledge> knowledge, List<ODResult> findings, Random rng) {
		TestKnowledge tk = knowledge.computeIfAbsent(action.victim, k -> new TestKnowledge());

		// Skip if already fully resolved
		if (!tk.confirmedPolluters().isEmpty() && action.type != ActionType.CONFIRM_BRITTLE) {
			return 0;
		}

		return switch (action.type) {
			case ISOLATE_PAIR -> executeIsolatePair(action, ctx, tk, findings);
			case CONFIRM_BRITTLE -> executeConfirmBrittle(action, ctx, tk, findings);
			case MINIMIZE -> executeMinimize(action, ctx, tk, workQueue, findings);
			case PROBE_EDGE -> executeProbeEdge(action, ctx, tk, workQueue, knowledge);
			case RANDOM_PROBE -> executeRandomProbe(ctx, workQueue, knowledge, rng);
			case EXCLUSION_PROBE -> executeExclusionProbe(action, ctx, workQueue, knowledge);
		};
	}

	private int executeIsolatePair(Action action, DetectionContext ctx, TestKnowledge tk, List<ODResult> findings) {
		if (action.candidates.isEmpty())
			return 0;
		String suspect = action.candidates.get(0);

		// Run [suspect, victim] — does victim fail?
		TestRunResult result = ctx.run(List.of(suspect, action.victim), 0);

		if (result.failed(action.victim)) {
			tk.confirmedPolluters().add(suspect);
			findings.add(new ODResult(action.victim, ODType.VICTIM, List.of(suspect, action.victim),
					"Confirmed: " + suspect + " pollutes " + action.victim, 0.99));
		} else {
			tk.eliminatedPolluters().add(suspect);
		}
		return 1;
	}

	private int executeConfirmBrittle(Action action, DetectionContext ctx, TestKnowledge tk, List<ODResult> findings) {
		if (action.candidates.isEmpty())
			return 0;
		String setter = action.candidates.get(0);

		// Run [setter, brittle] — does brittle pass? (confirms dependency)
		TestRunResult result = ctx.run(List.of(setter, action.victim), 0);

		if (result.passed(action.victim)) {
			tk.confirmedSetters().add(setter);
			findings.add(new ODResult(action.victim, ODType.BRITTLE, List.of(setter, action.victim),
					"Confirmed brittle: " + action.victim + " needs " + setter, 0.99));
		}
		return 1;
	}

	private int executeMinimize(Action action, DetectionContext ctx, TestKnowledge tk, PriorityQueue<Action> workQueue,
			List<ODResult> findings) {
		List<String> candidates = new ArrayList<>(action.candidates);
		// Remove already-eliminated suspects
		candidates.removeAll(tk.eliminatedPolluters());
		if (candidates.isEmpty())
			return 0;

		List<String> minimal = DeltaDebugging.minimize(candidates, action.victim, ctx.runner(), 15);
		int runs = Math.min(15, candidates.size()); // Approximate runs used

		if (!minimal.isEmpty()) {
			// Verify: does victim pass alone?
			TestRunResult isolation = ctx.run(List.of(action.victim), 0);
			runs++;

			if (isolation.passed(action.victim)) {
				// It's a victim — each test in minimal set is a polluter
				for (String polluter : minimal) {
					tk.confirmedPolluters().add(polluter);
				}
				findings.add(new ODResult(action.victim, ODType.VICTIM, buildChain(minimal, action.victim),
						"Minimized: " + minimal + " → " + action.victim, 0.95));
			} else {
				// Victim fails alone too — it's BRITTLE, needs a setter
				tk.setPassesAlone(false);
				// The reference order predecessors might be setters
				int refIdx = ctx.referenceOrder().indexOf(action.victim);
				if (refIdx > 0) {
					// Try immediate predecessor in reference order as setter
					String possibleSetter = ctx.referenceOrder().get(refIdx - 1);
					workQueue.add(new Action(ActionType.CONFIRM_BRITTLE, 8.5, action.victim, List.of(possibleSetter)));
				}
			}
		}

		return runs;
	}

	private int executeProbeEdge(Action action, DetectionContext ctx, TestKnowledge tk, PriorityQueue<Action> workQueue,
			Map<String, TestKnowledge> knowledge) {
		if (action.candidates.isEmpty())
			return 0;
		String suspect = action.candidates.get(0);

		// Run [suspect, victim] in that order
		TestRunResult result = ctx.run(List.of(suspect, action.victim), 0);

		if (result.failed(action.victim)) {
			// Verify it's not just flaky: run victim alone
			TestRunResult isolation = ctx.run(List.of(action.victim), 0);
			if (isolation.passed(action.victim)) {
				tk.confirmedPolluters().add(suspect);
				// Don't add to findings yet — schedule a proper ISOLATE_PAIR to confirm
				workQueue.add(new Action(ActionType.ISOLATE_PAIR, 9.5, action.victim, List.of(suspect)));
				return 2;
			} else {
				tk.setPassesAlone(false);
				return 2;
			}
		}
		return 1;
	}

	private int executeRandomProbe(DetectionContext ctx, PriorityQueue<Action> workQueue,
			Map<String, TestKnowledge> knowledge, Random rng) {
		List<String> shuffled = new ArrayList<>(ctx.referenceOrder());
		Collections.shuffle(shuffled, rng);
		TestRunResult result = ctx.run(shuffled, 0);

		for (String failed : result.failedTests()) {
			if (!ctx.passingTests().contains(failed))
				continue;
			TestKnowledge tk = knowledge.computeIfAbsent(failed, k -> new TestKnowledge());
			tk.incrementFailureCount();
			if (tk.confirmedPolluters().isEmpty()) {
				List<String> preds = result.predecessorsOf(failed);
				workQueue.add(new Action(ActionType.MINIMIZE, 8.0, failed, preds));
			}
		}
		return 1;
	}

	private int executeExclusionProbe(Action action, DetectionContext ctx, PriorityQueue<Action> workQueue,
			Map<String, TestKnowledge> knowledge) {
		if (action.candidates.isEmpty())
			return 0;
		String excluded = action.candidates.get(0);

		List<String> order = new ArrayList<>(ctx.referenceOrder());
		order.remove(excluded);
		TestRunResult result = ctx.run(order, 0);

		for (String failed : result.failedTests()) {
			if (!ctx.passingTests().contains(failed))
				continue;
			// Test failed without `excluded` — schedule CONFIRM_BRITTLE
			workQueue.add(new Action(ActionType.CONFIRM_BRITTLE, 9.0, failed, List.of(excluded)));
		}
		return 1;
	}

	private void addExclusionProbes(DetectionContext ctx, PriorityQueue<Action> workQueue,
			Map<String, TestKnowledge> knowledge) {
		// Add exclusion probes for tests that appear early in reference order
		// (more likely to be setters)
		int limit = Math.min(20, ctx.referenceOrder().size() / 2);
		for (int i = 0; i < limit; i++) {
			String test = ctx.referenceOrder().get(i);
			workQueue.add(new Action(ActionType.EXCLUSION_PROBE, 4.0, test, List.of(test)));
		}
	}

	private void addInitialExclusionProbes(DetectionContext ctx, PriorityQueue<Action> workQueue,
			Map<String, TestKnowledge> knowledge) {
		// Include exclusion probes for a sample of tests.
		// This ensures detection works even without execution order control
		// (e.g. standard Maven Surefire that ignores requested class order).
		// Priority 15.0 — above edge probes — since exclusion is the most reliable
		// strategy without a custom test ordering extension.
		int limit = Math.min(ctx.referenceOrder().size(), 10);
		for (int i = 0; i < limit; i++) {
			String test = ctx.referenceOrder().get(i);
			workQueue.add(new Action(ActionType.EXCLUSION_PROBE, 15.0, test, List.of(test)));
		}
	}

	private List<String> buildChain(List<String> polluters, String victim) {
		List<String> chain = new ArrayList<>(polluters);
		chain.add(victim);
		return chain;
	}
}
