package me.bechberger.testorder.junit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.MethodOrdererContext;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.MethodOrderingEngine;
import me.bechberger.testorder.MethodScorer;
import me.bechberger.testorder.TestOrderConfig;
import me.bechberger.testorder.TestOrderConfigResolver;
import me.bechberger.testorder.TestOrderLogger;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.annotations.AlwaysRun;
import me.bechberger.testorder.annotations.TestOrder;

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
 * When the class declares {@code @TestMethodOrder} with a different orderer,
 * this orderer defers to it and does not reorder methods.
 */
public class PriorityMethodOrderer implements MethodOrderer {

	/**
	 * Immutable holder for all per-invocation state injected by
	 * PriorityClassOrderer. Using a single AtomicReference to a record avoids the
	 * partially-updated-state race that was present with 6 individual static
	 * volatile fields.
	 */
	private record PendingStateHolder(TestOrderState state, TestOrderState.MethodScoringWeights weights,
			boolean enabled, DependencyMap depMap, Set<String> changedClasses, Set<String> changedMethods) {
	}

	private static final AtomicReference<PendingStateHolder> pendingStateRef = new AtomicReference<>(null);

	/**
	 * Called by PriorityClassOrderer to inject the state, weights, and dep map for
	 * method ordering.
	 */
	public static void setPendingState(TestOrderState state, TestOrderState.MethodScoringWeights weights,
			boolean enabled, DependencyMap depMap, Set<String> changedClasses, Set<String> changedMethods) {
		pendingStateRef.set(new PendingStateHolder(state, weights, enabled, depMap, changedClasses, changedMethods));
	}

	public static void clearPendingState() {
		pendingStateRef.set(null);
	}

	@Override
	public void orderMethods(MethodOrdererContext context) {
		// Atomically read the entire state holder — a single AtomicReference.get()
		// guarantees all fields are seen consistently (no partial-update window).
		PendingStateHolder holder = pendingStateRef.get();
		final TestOrderState localState;
		final TestOrderState.MethodScoringWeights localWeights;
		final boolean localEnabled;
		final DependencyMap localDepMap;
		final Set<String> localChangedClasses;
		final Set<String> localChangedMethods;
		if (holder == null) {
			localState = null;
			localWeights = null;
			localEnabled = false;
			localDepMap = null;
			localChangedClasses = null;
			localChangedMethods = null;
		} else {
			localState = holder.state();
			localWeights = holder.weights();
			localEnabled = holder.enabled();
			localDepMap = holder.depMap();
			localChangedClasses = holder.changedClasses();
			localChangedMethods = holder.changedMethods();
		}
		if (!localEnabled || localState == null || localWeights == null) {
			// No reordering if disabled or state not available
			return;
		}

		String className = context.getTestClass().getName();
		List<Method> methods = context.getMethodDescriptors().stream().map(md -> md.getMethod()).toList();

		if (methods.isEmpty()) {
			return;
		}

		// C7: Skip reordering entirely when @Execution(CONCURRENT) is detected — method
		// ordering is ineffective
		if (hasConcurrentExecution(context.getTestClass())) {
			TestOrderLogger.debug(
					"[method-order] {}: @Execution(CONCURRENT) detected — skipping method reordering (ordering is ineffective in parallel).",
					className);
			return;
		}

		// C4/C9: Warn and skip reordering when PER_CLASS lifecycle is active —
		// reordering may break stateful tests
		if (isPerClassLifecycle(context.getTestClass())) {
			TestOrderLogger.warn("[method-order] {}: @TestInstance(PER_CLASS) detected — skipping method reordering "
					+ "to avoid breaking stateful tests that depend on execution order.", className);
			return;
		}

		// Defer to a different @TestMethodOrder orderer if one is explicitly declared
		org.junit.jupiter.api.TestMethodOrder tmo = context.getTestClass()
				.getAnnotation(org.junit.jupiter.api.TestMethodOrder.class);
		if (tmo != null && !PriorityMethodOrderer.class.equals(tmo.value())) {
			TestOrderLogger.debug("[method-order] {}: @TestMethodOrder specifies another orderer — not reordering",
					className);
			return;
		}

		// Build metadata for each method (use -1 for unknown duration)
		List<MethodScorer.MethodMetadata> methodMetadata = new ArrayList<>();
		for (Method m : methods) {
			String methodName = m.getName();
			double duration = localState.getDurationMethod(className, methodName, -1.0);
			methodMetadata.add(new MethodScorer.MethodMetadata(className, methodName, (long) duration, null));
		}

		// Graceful fallback: if no telemetry at all (no durations, no failures), keep
		// source order
		boolean hasDurations = false;
		boolean hasFailures = false;
		for (MethodScorer.MethodMetadata m : methodMetadata) {
			if (m.durationMs() >= 0)
				hasDurations = true;
			if (localState.methodFailureScore(m.className(), m.methodName()) > 0)
				hasFailures = true;
			if (hasDurations && hasFailures)
				break;
		}
		if (!hasDurations && !hasFailures) {
			TestOrderLogger.debug("[method-order] {}: no telemetry available; using source order", className);
			return;
		}

		// Score methods
		MethodScorer scorer = new MethodScorer(localWeights, localState, localDepMap, localChangedClasses,
				localChangedMethods);
		List<MethodScorer.MethodScoreResult> scores = scorer.score(methodMetadata);

		// Log class-level stats
		long classMedian = scores.stream().map(s -> s.classMedianMs()).findFirst().orElse(0L);
		TestOrderLogger.debug("[method-order] {}: median_duration={}ms, {} methods", className, classMedian,
				methods.size());

		// Log each method's score
		if (Boolean.parseBoolean(System.getProperty(TestOrderConfig.DEBUG))) {
			for (MethodScorer.MethodScoreResult score : scores) {
				TestOrderLogger.debug(
						"[method-order] → {}: score={} (recency={}, speed={}, depOverlap={}, coverage={}, new={}, changed={}, classMedian={}ms)",
						score.methodName(), String.format(java.util.Locale.US, "%.1f", score.score()),
						String.format(java.util.Locale.US, "%.1f", score.failureRecencyBonus()),
						String.format(java.util.Locale.US, "%.1f", score.speedBonus()),
						String.format(java.util.Locale.US, "%.1f", score.depOverlapBonus()),
						String.format(java.util.Locale.US, "%.1f", score.coverageBonus()),
						String.format(java.util.Locale.US, "%.1f", score.newMethodBonus()),
						String.format(java.util.Locale.US, "%.1f", score.changedMethodBonus()), score.classMedianMs());
			}
		}

		// Pre-build lookup map for O(1) score access (instead of O(N) linear search per
		// comparator call).
		// Key includes parameter types so overloaded methods (same name, different
		// params) get distinct entries and do not collide.
		Map<String, Integer> scoreIndexMap = new HashMap<>(scores.size() * 2);
		for (int i = 0; i < scores.size(); i++) {
			scoreIndexMap.put(methodUniqueKey(methods.get(i)), i);
		}

		// Apply @TestOrder and @AlwaysRun annotation overrides
		Map<String, Double> effectiveScores = new HashMap<>(scores.size() * 2);
		List<org.junit.jupiter.api.MethodDescriptor> pinFirstMethods = new ArrayList<>();
		List<org.junit.jupiter.api.MethodDescriptor> pinLastMethods = new ArrayList<>();
		Set<org.junit.jupiter.api.MethodDescriptor> pinFirstSet = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<org.junit.jupiter.api.MethodDescriptor> pinLastSet = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int i = 0; i < scores.size(); i++) {
			effectiveScores.put(methodUniqueKey(methods.get(i)), scores.get(i).score());
		}
		for (org.junit.jupiter.api.MethodDescriptor md : context.getMethodDescriptors()) {
			boolean alwaysRun = md.getMethod().isAnnotationPresent(AlwaysRun.class);
			TestOrder ann = md.getMethod().getAnnotation(TestOrder.class);
			if (!alwaysRun && ann == null)
				continue;
			String methodKey = className + "#" + md.getMethod().getName();
			boolean isChanged = localChangedMethods != null && localChangedMethods.contains(methodKey);
			double delta = ann != null ? ann.scoreBonus() + (isChanged ? ann.changeBonus() : 0) : 0;
			TestOrder.Priority prio = ann != null ? ann.priority() : TestOrder.Priority.NORMAL;
			String uniqueKey = methodUniqueKey(md.getMethod());
			if (alwaysRun || prio == TestOrder.Priority.FIRST) {
				if (pinFirstSet.add(md))
					pinFirstMethods.add(md);
				if (delta != 0)
					effectiveScores.merge(uniqueKey, delta, Double::sum);
			} else if (prio == TestOrder.Priority.LAST) {
				pinLastMethods.add(md);
				pinLastSet.add(md);
				if (delta != 0)
					effectiveScores.merge(uniqueKey, delta, Double::sum);
			} else {
				if (prio == TestOrder.Priority.HIGH)
					delta += TestOrder.Priority.BOOST;
				else if (prio == TestOrder.Priority.LOW)
					delta -= TestOrder.Priority.BOOST;
				if (delta != 0)
					effectiveScores.merge(uniqueKey, delta, Double::sum);
			}
		}

		// Sort by effective score descending, maintain source order for ties
		context.getMethodDescriptors().sort((a, b) -> {
			boolean aPin1 = pinFirstSet.contains(a), bPin1 = pinFirstSet.contains(b);
			boolean aPin0 = pinLastSet.contains(a), bPin0 = pinLastSet.contains(b);
			if (aPin1 && !bPin1)
				return -1;
			if (!aPin1 && bPin1)
				return 1;
			if (aPin0 && !bPin0)
				return 1;
			if (!aPin0 && bPin0)
				return -1;
			double sa = effectiveScores.getOrDefault(methodUniqueKey(a.getMethod()), 0.0);
			double sb = effectiveScores.getOrDefault(methodUniqueKey(b.getMethod()), 0.0);
			int cmp = Double.compare(sb, sa); // higher score first
			if (cmp == 0) {
				int ia = scoreIndexMap.getOrDefault(methodUniqueKey(a.getMethod()), -1);
				int ib = scoreIndexMap.getOrDefault(methodUniqueKey(b.getMethod()), -1);
				return Integer.compare(ia, ib);
			}
			return cmp;
		});
	}

	/**
	 * /** Detects @Execution(CONCURRENT) on the test class or any enclosing class
	 * (for @Nested classes that inherit the annotation) via reflection to avoid a
	 * hard compile-time dependency on jupiter-api parallel classes.
	 */
	private boolean hasConcurrentExecution(Class<?> testClass) {
		for (Class<?> c = testClass; c != null; c = c.getEnclosingClass()) {
			try {
				Class<?> executionClass = Class.forName("org.junit.jupiter.api.parallel.Execution");
				Object execution = c.getAnnotation(executionClass.asSubclass(java.lang.annotation.Annotation.class));
				if (execution != null) {
					Object mode = executionClass.getMethod("value").invoke(execution);
					if ("CONCURRENT".equals(mode.toString())) {
						return true;
					}
				}
			} catch (ReflectiveOperationException ignored) {
				// Jupiter parallel API not available
				break;
			}
		}
		return false;
	}

	/**
	 * Detects @TestInstance(Lifecycle.PER_CLASS) on the test class or any enclosing
	 * class (for @Nested classes that inherit the lifecycle), either via annotation
	 * or the global default config parameter.
	 */
	private boolean isPerClassLifecycle(Class<?> testClass) {
		for (Class<?> c = testClass; c != null; c = c.getEnclosingClass()) {
			try {
				Class<?> testInstanceClass = Class.forName("org.junit.jupiter.api.TestInstance");
				Object annotation = c
						.getAnnotation(testInstanceClass.asSubclass(java.lang.annotation.Annotation.class));
				if (annotation != null) {
					Object lifecycle = testInstanceClass.getMethod("value").invoke(annotation);
					if ("PER_CLASS".equals(lifecycle.toString())) {
						return true;
					}
				}
			} catch (ReflectiveOperationException ignored) {
				// TestInstance API not available
				break;
			}
		}
		// Also check global default via system property (C9)
		String globalDefault = System.getProperty("junit.jupiter.testinstance.lifecycle.default");
		if ("per_class".equalsIgnoreCase(globalDefault)) {
			return true;
		}
		return false;
	}

	/**
	 * Returns a unique key for a method that includes the parameter types, so that
	 * overloaded methods (same name, different parameter lists) produce distinct
	 * entries in score maps.
	 * <p>
	 * Format: {@code methodName(param1Type,param2Type,...)}
	 */
	private static String methodUniqueKey(Method m) {
		StringBuilder sb = new StringBuilder(m.getName()).append('(');
		Class<?>[] params = m.getParameterTypes();
		for (int i = 0; i < params.length; i++) {
			if (i > 0)
				sb.append(',');
			sb.append(params[i].getName());
		}
		return sb.append(')').toString();
	}
}
