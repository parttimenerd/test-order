package me.bechberger.testorder;

import java.lang.reflect.Method;
import java.util.*;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.MethodOrdererContext;

/**
 * JUnit MethodOrderer that reorders test methods within a class based on
 * fail-fast scores.
 * <p>
 * Methods are scored using {@link MethodScorer}, which considers:
 * <ul>
 * <li><b>Failure recency:</b> Methods that failed recently run first.</li>
 * <li><b>Speed (class-local):</b> Fast methods run before slow ones, compared
 * to the class median.</li>
 * </ul>
 * <p>
 * <b>Graceful degradation:</b> If no method telemetry is available (first run),
 * methods remain in source order. This is logged, not an error.
 * <p>
 * Methods with explicit {@code @Order} annotations take precedence over
 * score-based ordering.
 */
public class PriorityMethodOrderer implements MethodOrderer {

	private static volatile TestOrderState pendingState;
	private static volatile TestOrderState.MethodScoringWeights methodWeights;
	private static volatile boolean enabled = false;
	private static volatile DependencyMap depMap;
	private static volatile Set<String> changedClasses;
	private static volatile Set<String> changedMethods;

	/**
	 * Called by PriorityClassOrderer to inject the state, weights, and dep map for
	 * method ordering.
	 */
	public static synchronized void setPendingState(TestOrderState state, TestOrderState.MethodScoringWeights weights,
			boolean enabled, DependencyMap depMap, Set<String> changedClasses, Set<String> changedMethods) {
		PriorityMethodOrderer.pendingState = state;
		PriorityMethodOrderer.methodWeights = weights;
		PriorityMethodOrderer.enabled = enabled;
		PriorityMethodOrderer.depMap = depMap;
		PriorityMethodOrderer.changedClasses = changedClasses;
		PriorityMethodOrderer.changedMethods = changedMethods;
	}

	static synchronized void clearPendingState() {
		pendingState = null;
		methodWeights = null;
		enabled = false;
		depMap = null;
		changedClasses = null;
		changedMethods = null;
	}

	@Override
	public void orderMethods(MethodOrdererContext context) {
		if (!enabled || pendingState == null || methodWeights == null) {
			// No reordering if disabled or state not available
			return;
		}

		String className = context.getTestClass().getName();
		List<Method> methods = context.getMethodDescriptors().stream().map(md -> md.getMethod()).toList();

		if (methods.isEmpty()) {
			return;
		}

		// Build metadata for each method (use -1 for unknown duration)
		List<MethodScorer.MethodMetadata> methodMetadata = new ArrayList<>();
		for (Method m : methods) {
			String methodName = m.getName();
			double duration = pendingState.getDurationMethod(className, methodName, -1.0);
			methodMetadata.add(new MethodScorer.MethodMetadata(className, methodName, (long) duration, null));
		}

		// Graceful fallback: if no telemetry at all (no durations, no failures), keep
		// source order
		boolean hasDurations = false;
		boolean hasFailures = false;
		for (MethodScorer.MethodMetadata m : methodMetadata) {
			if (m.durationMs() >= 0)
				hasDurations = true;
			if (pendingState.methodFailureScore(m.className(), m.methodName()) > 0)
				hasFailures = true;
			if (hasDurations && hasFailures)
				break;
		}
		if (!hasDurations && !hasFailures) {
			TestOrderLogger.debug("[method-order] {}: no telemetry available; using source order", className);
			return;
		}

		// Score methods
		MethodScorer scorer = new MethodScorer(methodWeights, pendingState, depMap, changedClasses, changedMethods);
		List<MethodScorer.MethodScoreResult> scores = scorer.score(methodMetadata);

		// Log class-level stats
		long classMedian = scores.stream().map(s -> s.classMedianMs()).findFirst().orElse(0L);
		TestOrderLogger.debug("[method-order] {}: median_duration={}ms, {} methods", className, classMedian,
				methods.size());

		// Log each method's score
		for (MethodScorer.MethodScoreResult score : scores) {
			TestOrderLogger.debug(
					"[method-order] → {}: score={} (recency={}, speed={}, depOverlap={}, coverage={}, new={}, changed={}, classMedian={}ms)",
					score.methodName(), String.format("%.1f", score.score()),
					String.format("%.1f", score.failureRecencyBonus()), String.format("%.1f", score.speedBonus()),
					String.format("%.1f", score.depOverlapBonus()), String.format("%.1f", score.coverageBonus()),
					String.format("%.1f", score.newMethodBonus()), String.format("%.1f", score.changedMethodBonus()),
					score.classMedianMs());
		}

		// Pre-build lookup map for O(1) score access (instead of O(N) linear search per
		// comparator call)
		Map<String, Integer> scoreIndexMap = new HashMap<>(scores.size() * 2);
		for (int i = 0; i < scores.size(); i++) {
			scoreIndexMap.put(scores.get(i).methodName(), i);
		}

		// Sort by score descending (highest score runs first), maintain source order
		// for ties
		context.getMethodDescriptors().sort((a, b) -> {
			int scoreA = scoreIndexMap.getOrDefault(a.getMethod().getName(), -1);
			int scoreB = scoreIndexMap.getOrDefault(b.getMethod().getName(), -1);

			// Get scores in descending order
			double sa = scoreA >= 0 ? scores.get(scoreA).score() : 0.0;
			double sb = scoreB >= 0 ? scores.get(scoreB).score() : 0.0;

			int cmp = Double.compare(sb, sa); // reverse: higher score first
			if (cmp == 0) {
				// Tie: preserve source order (by index in original list)
				return Integer.compare(scoreA, scoreB);
			}
			return cmp;
		});
	}
}
