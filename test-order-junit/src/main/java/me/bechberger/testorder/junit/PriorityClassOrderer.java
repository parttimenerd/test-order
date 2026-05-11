package me.bechberger.testorder.junit;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;

import me.bechberger.testorder.*;
import me.bechberger.testorder.annotations.AlwaysRun;
import me.bechberger.testorder.annotations.TestOrder;

/**
 * JUnit ClassOrderer that prioritizes test classes based on a weighted score.
 * <p>
 * Scores are computed from five weighted components (new test bonus, changed
 * test bonus, failure recency, speed bonus, dependency overlap). Weights are
 * loaded from the {@code .test-order/state.lz4} file written by the Maven
 * plugin; system properties / classpath properties override individual weights.
 * <p>
 * Among tests with equal scores, a greedy Jaccard-diversity selection maximises
 * the breadth of covered dependencies, so that tests exercising different parts
 * of the codebase run before redundant ones.
 * <p>
 * Configuration via system properties (or {@code testorder-config.properties}
 * on the classpath):
 * <ul>
 * <li>{@code testorder.index.path} — path to the
 * {@code test-dependencies.lz4}</li>
 * <li>{@code testorder.state.path} — path to the {@code .test-order/state.lz4}
 * file</li>
 * <li>{@code testorder.changed.classes} — comma-separated changed class
 * FQCNs</li>
 * <li>{@code testorder.changed.classes.file} — file with one changed class per
 * line</li>
 * <li>{@code testorder.changed.test.classes} — comma-separated changed test
 * class FQCNs</li>
 * <li>{@code testorder.score.*} — individual weight overrides</li>
 * </ul>
 */
public class PriorityClassOrderer implements ClassOrderer {

	/**
	 * Guards against repeated change-detection info messages across multiple
	 * orderClasses() calls.
	 */
	private static final AtomicBoolean changeDetectionLogged = new AtomicBoolean(false);

	/** Shared config resolver (system props + classpath properties). */
	private TestOrderConfigResolver config;

	/**
	 * Cached setup result — avoids repeated load attempts (and error logs) on every
	 * orderClasses() call. Volatile to ensure visibility across threads.
	 */
	private volatile ClassOrderingEngine.SetupResult cachedSetup;
	private volatile boolean setupAttempted;

	/** Lock for one-time setup initialization. */
	private final Object setupLock = new Object();

	@Override
	public void orderClasses(ClassOrdererContext context) {
		if (config == null) {
			synchronized (setupLock) {
				if (config == null) {
					config = new TestOrderConfigResolver(getClass().getClassLoader());
				}
			}
		}

		if (!setupAttempted) {
			synchronized (setupLock) {
				if (!setupAttempted) {
					cachedSetup = ClassOrderingEngine.setup(config);
					setupAttempted = true;
				}
			}
		}
		ClassOrderingEngine.SetupResult s = cachedSetup;
		if (s == null) {
			return;
		}

		// warn about negative weights (once)
		TestOrderState.ScoringWeights effectiveWeights = s.effectiveWeights();
		if (effectiveWeights.newTest() < 0 || effectiveWeights.changedTest() < 0 || effectiveWeights.maxFailure() < 0
				|| effectiveWeights.speed() < 0 || effectiveWeights.depOverlap() < 0
				|| effectiveWeights.changeComplexity() < 0) {
			TestOrderLogger.warn(
					"One or more scoring weights are negative — " + "this inverts the scoring for those components.");
		}

		// set up run-quality tracking
		if (s.statePath() != null && !s.statePath().isEmpty()) {
			TestOrderState.setStatePath(s.statePath());
		}

		List<? extends ClassDescriptor> descriptors = context.getClassDescriptors();

		// Create a mutable copy — JUnit may return an unmodifiable list
		List<ClassDescriptor> mutableDescriptors = new ArrayList<>(descriptors);
		// Sort by class name for deterministic ordering regardless of JUnit
		// discovery order
		mutableDescriptors.sort(Comparator.comparing((ClassDescriptor d) -> getTopLevelClassName(d)));

		List<String> testClassNames = descriptors.stream().map(this::getTopLevelClassName).toList();

		if (changeDetectionLogged.compareAndSet(false, true)) {
			// R17-1: With forkCount>1, each fork JVM logs independently. Suppress logging
			// if the mojo already printed change-detection info (indicated by config
			// property).
			boolean suppressedByMojo = "true".equals(config.getConfig("testorder.changeDetection.logged"));
			if (!suppressedByMojo) {
				TestOrderLogger.info("change detection mode={} changedClasses={} changedTests={}",
						config.getConfig(TestOrderConfig.CHANGE_MODE), s.changedClasses().size(),
						s.changedTestClasses().size());
			}
			if (s.debug()) {
				TestOrderLogger.debug("changed classes: {}", s.changedClasses());
				TestOrderLogger.debug("changed test classes: {}", s.changedTestClasses());
			}
		}

		TestScorer scorer = ClassOrderingEngine.buildScorer(s, testClassNames);

		// score each test class
		Map<ClassDescriptor, Integer> scores = new HashMap<>();
		for (ClassDescriptor desc : descriptors) {
			String testClassName = getTopLevelClassName(desc);
			TestScorer.ScoreResult result = scorer.score(testClassName);
			scores.put(desc, result.score());

			// record breakdown for run history
			ClassOrderingEngine.recordBreakdown(s.statePath(), testClassName, result);

			if (s.debug()) {
				long dur = s.state().getDuration(testClassName, -1);
				TestOrderLogger.debug("{} score={} (deps={}, fail={}, new={}, changed={}, fast={}, slow={}, dur={})",
						testClassName, result.score(), result.depOverlap(), result.failScore(), result.isNew(),
						result.isChanged(), result.isFast(), result.isSlow(), dur >= 0 ? dur + "ms" : "?");
			}
		}

		// order: group by score descending, within each group use Jaccard diversity
		// Apply @TestOrder, @AlwaysRun, and @Order annotation adjustments before
		// sorting
		List<ClassDescriptor> pinFirst = new ArrayList<>();
		List<ClassDescriptor> pinLast = new ArrayList<>();
		List<ClassDescriptor> junitOrdered = new ArrayList<>();
		Set<ClassDescriptor> pinFirstSet = Collections.newSetFromMap(new IdentityHashMap<>());
		for (ClassDescriptor desc : mutableDescriptors) {
			// JUnit's @Order: extract into a separate block sorted by @Order value,
			// placed after FIRST-pinned but before score-ordered classes.
			// @Order takes precedence over @TestOrder.
			org.junit.jupiter.api.Order orderAnn = desc.getTestClass().getAnnotation(org.junit.jupiter.api.Order.class);
			if (orderAnn != null) {
				junitOrdered.add(desc);
				if (s.debug()) {
					TestOrderLogger.debug("@Order({}) on {} — excluding from score-based sort", orderAnn.value(),
							getTopLevelClassName(desc));
				}
				continue;
			}
			boolean alwaysRun = desc.getTestClass().isAnnotationPresent(AlwaysRun.class);
			TestOrder ann = desc.getTestClass().getAnnotation(TestOrder.class);
			if (!alwaysRun && ann == null)
				continue;
			String testClassName = getTopLevelClassName(desc);
			int bonus = ann != null ? ann.scoreBonus() : 0;
			int changeBonus = ann != null && s.changedTestClasses().contains(testClassName) ? ann.changeBonus() : 0;
			TestOrder.Priority prio = ann != null ? ann.priority() : TestOrder.Priority.NORMAL;

			if (alwaysRun || prio == TestOrder.Priority.FIRST) {
				if (pinFirstSet.add(desc))
					pinFirst.add(desc);
			} else if (prio == TestOrder.Priority.LAST) {
				pinLast.add(desc);
			} else {
				int delta = bonus + changeBonus;
				if (prio == TestOrder.Priority.HIGH)
					delta += TestOrder.Priority.BOOST;
				else if (prio == TestOrder.Priority.LOW)
					delta -= TestOrder.Priority.BOOST;
				if (delta != 0)
					scores.merge(desc, delta, Integer::sum);
			}
			if (s.debug() && (bonus != 0 || changeBonus != 0 || prio != TestOrder.Priority.NORMAL)) {
				TestOrderLogger.debug("@TestOrder on {}: priority={}, scoreBonus={}, changeBonus={} (applied={})",
						testClassName, prio, bonus, ann.changeBonus(), changeBonus);
			}
		}
		// Remove pinned and @Order descriptors from main list before score-based sort
		Set<ClassDescriptor> toRemove = Collections.newSetFromMap(new IdentityHashMap<>());
		toRemove.addAll(pinFirst);
		toRemove.addAll(pinLast);
		toRemove.addAll(junitOrdered);
		mutableDescriptors.removeAll(toRemove);
		// Stable sort pinFirst by scoreBonus desc then name, pinLast by scoreBonus asc
		// then name
		pinFirst.sort(Comparator
				.<ClassDescriptor, Integer>comparing(
						d -> Optional.ofNullable(d.getTestClass().getAnnotation(TestOrder.class))
								.map(TestOrder::scoreBonus).orElse(0))
				.reversed().thenComparing(d -> getTopLevelClassName(d)));
		pinLast.sort(
				Comparator
						.<ClassDescriptor, Integer>comparing(
								d -> Optional.ofNullable(d.getTestClass().getAnnotation(TestOrder.class))
										.map(TestOrder::scoreBonus).orElse(0))
						.thenComparing(d -> getTopLevelClassName(d)));
		// Sort @Order classes by ascending @Order value, then alphabetically
		junitOrdered.sort(Comparator.<ClassDescriptor, Integer>comparing(d -> {
			org.junit.jupiter.api.Order o = d.getTestClass().getAnnotation(org.junit.jupiter.api.Order.class);
			return o != null ? o.value() : Integer.MAX_VALUE;
		}).thenComparing(d -> getTopLevelClassName(d)));

		orderByScoreAndDiversity(mutableDescriptors, scores, s.depMap(), s.state(),
				config.getConfigBool(TestOrderConfig.SPRING_CONTEXT_GROUPING, false));

		// Prepend FIRST-pinned, then @Order block, then score-ordered, then LAST-pinned
		mutableDescriptors.addAll(0, junitOrdered);
		mutableDescriptors.addAll(0, pinFirst);
		mutableDescriptors.addAll(pinLast);

		// Set up method-level ordering
		boolean methodOrderingEnabled = config.getConfigBool(TestOrderConfig.METHOD_ORDER_ENABLED, false);
		if (methodOrderingEnabled) {
			TestOrderState.MethodScoringWeights methodWeights = config.resolveMethodWeights(s.state());

			// Resolve changed methods
			Set<String> changedMethods = config.resolveChangedMethods();

			// Inject state, weights, and dependency map for method ordering
			PriorityMethodOrderer.setPendingState(s.state(), methodWeights, true, s.depMap(), s.changedClasses(),
					changedMethods);
			TestOrderLogger.debug(
					"[method-order] enabled with weights: failureRecency={}, fast={}, slow={}, "
							+ "depOverlap={}, newMethod={}, changedMethod={}, coverageBonus={}",
					methodWeights.failureRecency(), methodWeights.fast(), methodWeights.slow(),
					methodWeights.depOverlap(), methodWeights.newMethod(), methodWeights.changedMethod(),
					methodWeights.coverageBonus());
		}

		// Write the computed order back into the original list so JUnit sees it.
		// JUnit's ClassOrderer contract requires in-place modification.
		@SuppressWarnings("unchecked")
		List<ClassDescriptor> originalList = (List<ClassDescriptor>) descriptors;
		originalList.clear();
		originalList.addAll(mutableDescriptors);

		if (s.debug() && originalList.size() > 1) {
			TestOrderLogger.debug("Final order:");
			for (int i = 0; i < originalList.size(); i++) {
				TestOrderLogger.debug("  {}. {} (score={})", i + 1, originalList.get(i).getTestClass().getName(),
						scores.getOrDefault(originalList.get(i), 0));
			}
		}
	}

	/**
	 * Groups tests by score (descending), then within each group uses greedy
	 * Jaccard-distance selection to maximise dependency diversity. Within a Jaccard
	 * tie, shorter duration wins.
	 */
	@SuppressWarnings("unchecked")
	private void orderByScoreAndDiversity(List<? extends ClassDescriptor> descriptors,
			Map<ClassDescriptor, Integer> scores, DependencyMap depMap, TestOrderState state,
			boolean springContextGrouping) {
		List<ClassDescriptor> mutable = (List<ClassDescriptor>) descriptors;
		ClassOrderingEngine.orderByScoreAndDiversity(mutable, d -> scores.getOrDefault(d, 0),
				this::getTopLevelClassName, depMap, state,
				springContextGrouping ? d -> TestScorer.springContextKey(d.getTestClass()) : null);
	}

	private final Map<ClassDescriptor, String> topLevelClassNameCache = new ConcurrentHashMap<>();

	private String getTopLevelClassName(ClassDescriptor descriptor) {
		return topLevelClassNameCache.computeIfAbsent(descriptor, d -> {
			Class<?> clazz = d.getTestClass();
			while (clazz.getEnclosingClass() != null) {
				clazz = clazz.getEnclosingClass();
			}
			return clazz.getName();
		});
	}
}
