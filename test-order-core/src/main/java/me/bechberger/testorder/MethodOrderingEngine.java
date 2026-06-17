package me.bechberger.testorder;

import java.util.*;

/**
 * Shared method-ordering engine used by both the JUnit PriorityMethodOrderer
 * and TestNG's method reordering, as well as the show-method-order plugin
 * command.
 * <p>
 * Encapsulates:
 * <ul>
 * <li>Building method metadata from state</li>
 * <li>Scoring via {@link MethodScorer}</li>
 * <li>Sorting by score descending with stable tie-breaking</li>
 * </ul>
 */
public final class MethodOrderingEngine {

	/**
	 * Represents a scored and ordered method within a class.
	 */
	public record OrderedMethod(String className, String methodName, double score,
			MethodScorer.MethodScoreResult details) {
	}

	/**
	 * Result of ordering methods for a single class.
	 */
	public record ClassMethodOrder(String className, List<OrderedMethod> methods) {
	}

	private MethodOrderingEngine() {
	}

	/**
	 * Computes the method ordering for a single class.
	 *
	 * @param className
	 *            fully-qualified class name
	 * @param methodNames
	 *            test method names in the class
	 * @param state
	 *            test-order state (durations, failures)
	 * @param depMap
	 *            dependency map
	 * @param changedClasses
	 *            changed source classes
	 * @param changedMethods
	 *            changed methods (className#methodName)
	 * @param weights
	 *            method scoring weights
	 * @return ordered list of methods (highest score first)
	 */
	public static ClassMethodOrder orderMethods(String className, List<String> methodNames, TestOrderState state,
			DependencyMap depMap, Set<String> changedClasses, Set<String> changedMethods,
			TestOrderState.MethodScoringWeights weights) {
		if (methodNames.isEmpty()) {
			return new ClassMethodOrder(className, List.of());
		}

		List<MethodScorer.MethodMetadata> metadata = new ArrayList<>(methodNames.size());
		for (String methodName : methodNames) {
			double durationDouble = state.getDurationMethod(className, methodName, -1.0);
			// Round rather than truncate so sub-millisecond values map to 1ms rather than 0
			long duration = durationDouble < 0 ? (long) durationDouble : Math.max(1L, Math.round(durationDouble));
			metadata.add(new MethodScorer.MethodMetadata(className, methodName, duration, null));
		}

		return orderMethodsFromMetadata(metadata, className, state, depMap, changedClasses, changedMethods, weights);
	}

	/**
	 * Computes the method ordering from pre-built metadata.
	 *
	 * @param metadata
	 *            method metadata entries
	 * @param className
	 *            fully-qualified class name
	 * @param state
	 *            test-order state
	 * @param depMap
	 *            dependency map
	 * @param changedClasses
	 *            changed source classes
	 * @param changedMethods
	 *            changed methods (className#methodName)
	 * @param weights
	 *            method scoring weights
	 * @return ordered list of methods (highest score first)
	 */
	public static ClassMethodOrder orderMethodsFromMetadata(List<MethodScorer.MethodMetadata> metadata,
			String className, TestOrderState state, DependencyMap depMap, Set<String> changedClasses,
			Set<String> changedMethods, TestOrderState.MethodScoringWeights weights) {
		if (metadata.isEmpty()) {
			return new ClassMethodOrder(className, List.of());
		}

		MethodScorer scorer = new MethodScorer(weights, state, depMap, changedClasses, changedMethods);
		List<MethodScorer.MethodScoreResult> scored = scorer.score(metadata);

		// Sort by score descending, with method name as alphabetical tie-breaker for
		// deterministic ordering when scores are equal.
		List<MethodScorer.MethodScoreResult> sorted = new ArrayList<>(scored);
		sorted.sort(Comparator.<MethodScorer.MethodScoreResult>comparingDouble(r -> -r.score())
				.thenComparing(MethodScorer.MethodScoreResult::methodName));

		List<OrderedMethod> ordered = new ArrayList<>(sorted.size());
		for (MethodScorer.MethodScoreResult sr : sorted) {
			ordered.add(new OrderedMethod(sr.className(), sr.methodName(), sr.score(), sr));
		}
		return new ClassMethodOrder(className, ordered);
	}

	/**
	 * Checks whether any method telemetry (durations or failures) exists for the
	 * given class. If none, method ordering should fall back to source order.
	 */
	public static boolean hasTelemetry(String className, List<String> methodNames, TestOrderState state) {
		for (String methodName : methodNames) {
			if (state.getDurationMethod(className, methodName, -1.0) >= 0) {
				return true;
			}
			if (state.methodFailureScore(className, methodName) > 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Computes method ordering for all test classes that have method telemetry in
	 * the state. Discovers methods from the state's method durations map.
	 *
	 * @param state
	 *            test-order state
	 * @param depMap
	 *            dependency map
	 * @param changedClasses
	 *            changed source classes
	 * @param changedMethods
	 *            changed methods
	 * @param weights
	 *            method scoring weights
	 * @return list of class method orderings (only classes with telemetry)
	 */
	public static List<ClassMethodOrder> orderAllMethods(TestOrderState state, DependencyMap depMap,
			Set<String> changedClasses, Set<String> changedMethods, TestOrderState.MethodScoringWeights weights) {
		Map<String, Map<String, Double>> methodDurations = state.getMethodDurations();
		List<ClassMethodOrder> results = new ArrayList<>();
		for (Map.Entry<String, Map<String, Double>> entry : methodDurations.entrySet()) {
			String className = entry.getKey();
			List<String> methodNames = new ArrayList<>(entry.getValue().keySet());
			if (methodNames.isEmpty()) {
				continue;
			}
			ClassMethodOrder order = orderMethods(className, methodNames, state, depMap, changedClasses, changedMethods,
					weights);
			if (!order.methods().isEmpty()) {
				results.add(order);
			}
		}
		return results;
	}
}
