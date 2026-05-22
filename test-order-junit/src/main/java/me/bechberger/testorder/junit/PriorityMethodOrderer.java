package me.bechberger.testorder.junit;

import java.lang.reflect.Method;
import java.util.*;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.MethodOrdererContext;

import me.bechberger.testorder.*;
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

	public static synchronized void clearPendingState() {
		pendingState = null;
		methodWeights = null;
		enabled = false;
		depMap = null;
		changedClasses = null;
		changedMethods = null;
	}

	@Override
	public void orderMethods(MethodOrdererContext context) {
		// Snapshot all volatile fields under the same lock used by setPendingState()
		// to avoid seeing a partially-updated state (e.g., new 'enabled' but old
		// 'pendingState' from a concurrent setPendingState() call).
		final TestOrderState localState;
		final TestOrderState.MethodScoringWeights localWeights;
		final boolean localEnabled;
		final DependencyMap localDepMap;
		final Set<String> localChangedClasses;
		final Set<String> localChangedMethods;
		synchronized (PriorityMethodOrderer.class) {
			localState = pendingState;
			localWeights = methodWeights;
			localEnabled = enabled;
			localDepMap = depMap;
			localChangedClasses = changedClasses;
			localChangedMethods = changedMethods;
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

		// Respect JUnit's @Order annotation: if any method in the class uses @Order,
		// the user has declared an explicit ordering — do not override it.
		if (hasJUnitOrderAnnotation(context)) {
			TestOrderLogger.debug("[method-order] {}: @Order or @TestMethodOrder detected; skipping reordering",
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
		// comparator call)
		Map<String, Integer> scoreIndexMap = new HashMap<>(scores.size() * 2);
		for (int i = 0; i < scores.size(); i++) {
			scoreIndexMap.put(scores.get(i).methodName(), i);
		}

		// Apply @TestOrder and @AlwaysRun annotation overrides
		Map<String, Double> effectiveScores = new HashMap<>(scores.size() * 2);
		List<org.junit.jupiter.api.MethodDescriptor> pinFirstMethods = new ArrayList<>();
		List<org.junit.jupiter.api.MethodDescriptor> pinLastMethods = new ArrayList<>();
		Set<org.junit.jupiter.api.MethodDescriptor> pinFirstSet = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<org.junit.jupiter.api.MethodDescriptor> pinLastSet = Collections.newSetFromMap(new IdentityHashMap<>());
		for (MethodScorer.MethodScoreResult sr : scores) {
			effectiveScores.put(sr.methodName(), sr.score());
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
			if (alwaysRun || prio == TestOrder.Priority.FIRST) {
				if (pinFirstSet.add(md))
					pinFirstMethods.add(md);
				if (delta != 0)
					effectiveScores.merge(md.getMethod().getName(), delta, Double::sum);
			} else if (prio == TestOrder.Priority.LAST) {
				pinLastMethods.add(md);
				pinLastSet.add(md);
				if (delta != 0)
					effectiveScores.merge(md.getMethod().getName(), delta, Double::sum);
			} else {
				if (prio == TestOrder.Priority.HIGH)
					delta += TestOrder.Priority.BOOST;
				else if (prio == TestOrder.Priority.LOW)
					delta -= TestOrder.Priority.BOOST;
				if (delta != 0)
					effectiveScores.merge(md.getMethod().getName(), delta, Double::sum);
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
			double sa = effectiveScores.getOrDefault(a.getMethod().getName(), 0.0);
			double sb = effectiveScores.getOrDefault(b.getMethod().getName(), 0.0);
			int cmp = Double.compare(sb, sa); // higher score first
			if (cmp == 0) {
				int ia = scoreIndexMap.getOrDefault(a.getMethod().getName(), -1);
				int ib = scoreIndexMap.getOrDefault(b.getMethod().getName(), -1);
				return Integer.compare(ia, ib);
			}
			return cmp;
		});
	}

	/**
	 * Returns {@code true} if any method in the class uses JUnit's {@code @Order}
	 * annotation or if the class declares {@code @TestMethodOrder} — in which case
	 * we should not override the user's explicit ordering.
	 */
	private boolean hasJUnitOrderAnnotation(MethodOrdererContext context) {
		// Check for @TestMethodOrder on the class itself — but only if it specifies
		// a different orderer (not this one), otherwise we'd skip our own ordering
		org.junit.jupiter.api.TestMethodOrder tmo = context.getTestClass()
				.getAnnotation(org.junit.jupiter.api.TestMethodOrder.class);
		if (tmo != null && !PriorityMethodOrderer.class.equals(tmo.value())) {
			return true;
		}
		// Check for @Order on any test method
		boolean hasOrderAnnotation = false;
		for (org.junit.jupiter.api.MethodDescriptor md : context.getMethodDescriptors()) {
			if (md.getMethod().isAnnotationPresent(org.junit.jupiter.api.Order.class)) {
				hasOrderAnnotation = true;
				break;
			}
		}
		if (hasOrderAnnotation) {
			// Warn if @Order is used without
			// @TestMethodOrder(MethodOrderer.OrderAnnotation)
			// because JUnit will not respect @Order without it — the ordering is undefined.
			boolean hasOrderAnnotationClass = tmo != null
					&& org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class.equals(tmo.value());
			if (!hasOrderAnnotationClass) {
				TestOrderLogger.warn("[method-order] {}: @Order annotation on test methods will be ignored by JUnit — "
						+ "add @TestMethodOrder(MethodOrderer.OrderAnnotation.class) to the class for @Order to take effect.",
						context.getTestClass().getName());
			}
			return true;
		}
		return false;
	}

	/**
	 * Detects @Execution(CONCURRENT) on the test class or any enclosing class
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
}
