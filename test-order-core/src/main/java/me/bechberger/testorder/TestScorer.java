package me.bechberger.testorder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Logger;

import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;

/**
 * Unified test scoring logic used by {@link PriorityClassOrderer},
 * {@link TestSelector}, and the Maven plugin's show-order goal.
 * <p>
 * Scores test classes based on weighted components: changed test bonus,
 * dependency overlap, change complexity, failure recency, new test bonus, and
 * speed bonus/penalty.
 * <p>
 * When structural change analysis ({@link ChangedMembers}) and member-level
 * dependency data are available, the dependency overlap computation uses
 * precise member-level matching: a class only counts as overlapping if the test
 * actually uses a member that changed (or a static initializer changed, which
 * affects all users implicitly).
 */
public class TestScorer {

	private static final Logger LOG = Logger.getLogger(TestScorer.class.getName());

	private static final String SPRING_BOOT_TEST = "org.springframework.boot.test.context.SpringBootTest";
	private static final String CONTEXT_CONFIGURATION = "org.springframework.test.context.ContextConfiguration";

	/**
	 * Half-range in log₂ space for speed buckets (covers 1/8× to 8× the median).
	 */
	private static final double SPEED_LOG_HALF_RANGE = 3.0;

	/** Geometric decline factor for set-cover bonuses. */
	private static final double SET_COVER_DECLINE = 0.8;

	/** Full result of scoring a single test class. */
	public record ScoreResult(int score, int depOverlap, int depTotal, double failScore, boolean isNew,
			boolean isChanged, boolean isFast, boolean isSlow, double complexityOverlap, double speedRatio,
			boolean hasStaticFieldOverlap) {
	}

	/**
	 * Computes the weight-independent speed ratio in [-1, 1].
	 * <p>
	 * Maps {@code duration/median} to log₂ space, clamped to [-3, 3], then
	 * normalises to [-1, 1]. Negative values mean faster than median, positive
	 * means slower. At the median the ratio is 0.
	 * <p>
	 * This value can be stored and later combined with any speed/speedPenalty
	 * weights for faithful re-scoring in the optimizer.
	 */
	public static double speedRatio(long duration, long median) {
		if (median <= 0 || duration < 0)
			return 0.0;
		double logRatio = Math.log(Math.max((double) duration / median, 1e-9)) / Math.log(2);
		return Math.max(-1.0, Math.min(1.0, logRatio / SPEED_LOG_HALF_RANGE));
	}

	/**
	 * Computes the depOverlap score contribution from raw overlap count, total
	 * deps, and weight.
	 * <p>
	 * Uses {@code overlap / sqrt(max(totalDeps, MIN_DEPS))} — a geometric mean that
	 * balances absolute overlap count against test breadth. The minimum denominator
	 * prevents tests with trivially small dep sets (1-3 deps) from getting the same
	 * maximum score as broad integration tests.
	 * <p>
	 * The weight acts as the maximum contribution (like {@code maxFailure}).
	 */
	private static final int MIN_DEPS_DENOMINATOR = 5;

	public static int depOverlapScore(int depOverlap, int depTotal, int weight) {
		if (depOverlap == 0 || depTotal == 0 || weight == 0)
			return 0;
		double normalized = depOverlap / Math.sqrt(Math.max(depTotal, MIN_DEPS_DENOMINATOR));
		return Math.min((int) Math.ceil(normalized * weight), weight);
	}

	/**
	 * Computes the change-complexity score contribution from the weighted
	 * complexity overlap, total deps, and the changeComplexity weight.
	 * <p>
	 * {@code complexityOverlap} is the sum of normalised complexity values (0.0–1.0
	 * each) for each overlapping changed dependency. Uses the same
	 * {@code / sqrt(totalDeps)} normalization as {@link #depOverlapScore} for
	 * consistency.
	 */
	public static int complexityScore(double complexityOverlap, int depTotal, int weight) {
		if (complexityOverlap <= 0 || depTotal <= 0 || weight == 0)
			return 0;
		double normalized = complexityOverlap / Math.sqrt(Math.max(depTotal, MIN_DEPS_DENOMINATOR));
		return Math.min((int) Math.ceil(normalized * weight), weight);
	}

	private final TestOrderState.ScoringWeights weights;
	private final DependencyMap depMap;
	private final TestOrderState state;
	private final Set<String> changedClasses;
	private final Set<String> changedTestClasses;
	/**
	 * Combined set of changed production classes and changed test classes, used for
	 * dependency overlap scoring. This ensures that tests depending on changed test
	 * utility classes (e.g., TestHelper, AbstractBaseTest) receive a depOverlap
	 * score boost.
	 */
	private final Set<String> effectiveChangedForOverlap;
	private final ChangedMembers changedMembers;
	private final Map<String, Double> failureScores;
	private final Map<String, Double> changeComplexity;
	private final long medianDuration;
	/** Set-cover bonus per test class (empty when coverageBonus weight is 0). */
	private final Map<String, Integer> setCoverBonuses;
	/**
	 * Cached overlap counts from set-cover computation (avoids recomputation in
	 * score()).
	 */
	private final Map<String, Integer> cachedOverlapCounts;

	/**
	 * Builder for {@link TestScorer} to avoid constructor overload ambiguity.
	 *
	 * <p>
	 * Required inputs are provided via the builder constructor; optional inputs are
	 * configured fluently before calling {@link #build()}.
	 * </p>
	 */
	public static final class Builder {
		private final TestOrderState.ScoringWeights weights;
		private final DependencyMap depMap;
		private final TestOrderState state;
		private final Set<String> changedClasses;
		private final Set<String> changedTestClasses;
		private Iterable<String> testClassNames = List.of();
		private ChangedMembers changedMembers;
		private Map<String, Double> changeComplexity = Map.of();

		/**
		 * Creates a builder with mandatory scorer dependencies.
		 *
		 * @param weights
		 *            scoring weight configuration
		 * @param depMap
		 *            dependency map used for overlap scoring
		 * @param state
		 *            historical state for failure and duration signals
		 * @param changedClasses
		 *            changed production classes
		 * @param changedTestClasses
		 *            changed test classes
		 */
		public Builder(TestOrderState.ScoringWeights weights, DependencyMap depMap, TestOrderState state,
				Set<String> changedClasses, Set<String> changedTestClasses) {
			this.weights = Objects.requireNonNull(weights, "weights");
			this.depMap = Objects.requireNonNull(depMap, "depMap");
			this.state = Objects.requireNonNull(state, "state");
			this.changedClasses = Objects.requireNonNullElse(changedClasses, Set.of());
			this.changedTestClasses = Objects.requireNonNullElse(changedTestClasses, Set.of());
		}

		/**
		 * Sets test classes that should participate in scoring and median duration
		 * computation.
		 */
		public Builder testClassNames(Iterable<String> testClassNames) {
			this.testClassNames = Objects.requireNonNull(testClassNames, "testClassNames");
			return this;
		}

		/**
		 * Supplies changed member information for member-level overlap scoring.
		 */
		public Builder changedMembers(ChangedMembers changedMembers) {
			this.changedMembers = changedMembers;
			return this;
		}

		/**
		 * Supplies optional normalized complexity values per changed class.
		 */
		public Builder changeComplexity(Map<String, Double> changeComplexity) {
			this.changeComplexity = Objects.requireNonNullElse(changeComplexity, Map.of());
			return this;
		}

		/**
		 * Builds an immutable scorer snapshot.
		 */
		public TestScorer build() {
			return new TestScorer(weights, depMap, state, changedClasses, changedTestClasses, testClassNames,
					changedMembers, changeComplexity);
		}
	}

	/**
	 * Creates a {@link Builder} for {@link TestScorer}.
	 */
	public static Builder builder(TestOrderState.ScoringWeights weights, DependencyMap depMap, TestOrderState state,
			Set<String> changedClasses, Set<String> changedTestClasses) {
		return new Builder(weights, depMap, state, changedClasses, changedTestClasses);
	}

	/**
	 * Creates a scorer with defaults: no member-level change data and no complexity
	 * map.
	 */
	public TestScorer(TestOrderState.ScoringWeights weights, DependencyMap depMap, TestOrderState state,
			Set<String> changedClasses, Set<String> changedTestClasses, Iterable<String> testClassNames) {
		this(weights, depMap, state, changedClasses, changedTestClasses, testClassNames, null, Map.of());
	}

	/**
	 * Creates a scorer with member-level change data and default empty complexity
	 * map.
	 */
	public TestScorer(TestOrderState.ScoringWeights weights, DependencyMap depMap, TestOrderState state,
			Set<String> changedClasses, Set<String> changedTestClasses, Iterable<String> testClassNames,
			ChangedMembers changedMembers) {
		this(weights, depMap, state, changedClasses, changedTestClasses, testClassNames, changedMembers, Map.of());
	}

	/**
	 * Creates a scorer with full configuration.
	 */
	public TestScorer(TestOrderState.ScoringWeights weights, DependencyMap depMap, TestOrderState state,
			Set<String> changedClasses, Set<String> changedTestClasses, Iterable<String> testClassNames,
			ChangedMembers changedMembers, Map<String, Double> changeComplexity) {
		this.weights = weights;
		this.depMap = depMap;
		this.state = state;
		this.changedClasses = changedClasses;
		this.changedTestClasses = changedTestClasses;
		this.changedMembers = changedMembers;
		this.changeComplexity = changeComplexity != null ? changeComplexity : Map.of();
		this.failureScores = state.getFailureScores();
		this.medianDuration = computeMedianDuration(state, testClassNames);
		this.cachedOverlapCounts = new HashMap<>();

		// Build effective changed set: production classes + test utility classes.
		// This ensures that tests depending on changed test helpers get a depOverlap
		// boost.
		if (changedTestClasses.isEmpty()) {
			this.effectiveChangedForOverlap = changedClasses;
		} else {
			Set<String> merged = new LinkedHashSet<>(changedClasses);
			merged.addAll(changedTestClasses);
			this.effectiveChangedForOverlap = Collections.unmodifiableSet(merged);
		}

		this.setCoverBonuses = weights.coverageBonus() > 0
				? computeSetCoverBonuses(testClassNames, weights.coverageBonus())
				: Map.of();
	}

	/**
	 * Greedy set-cover: iteratively pick the test that covers the most
	 * not-yet-covered changed source classes. Each selected test earns a declining
	 * bonus: {@code weight} for the first pick, {@code weight-1} for the second,
	 * etc. (minimum 1).
	 *
	 * @return map from test class name to bonus (only tests that cover at least one
	 *         changed class appear)
	 */
	private Map<String, Integer> computeSetCoverBonuses(Iterable<String> testClassNames, int weight) {
		if (effectiveChangedForOverlap.isEmpty() || weight <= 0)
			return Map.of();

		// Build coverage map: test -> set of changed classes it covers
		Map<String, Set<String>> coverage = new LinkedHashMap<>();
		for (String test : testClassNames) {
			Set<String> deps = depMap.get(test);
			Set<String> memberDeps = depMap.hasMemberDeps() ? depMap.getMemberDeps(test) : null;
			Set<String> covered = StructuralChangeAnalyzer.computeOverlapClasses(deps, memberDeps, changedMembers,
					effectiveChangedForOverlap);
			// cache overlap count to avoid re-calling computeOverlapClasses in score()
			cachedOverlapCounts.put(test, covered.size());
			if (!covered.isEmpty()) {
				coverage.put(test, new HashSet<>(covered));
			}
		}
		Map<String, Integer> bonuses = new HashMap<>();
		int bonus = weight;

		SetCoverComputer.Result<String> result = new SetCoverComputer<>(coverage, effectiveChangedForOverlap).compute();
		for (String best : result.order()) {
			if (result.initialCoverCounts().getOrDefault(best, 0) == 0) {
				continue;
			}
			bonuses.put(best, bonus);
			bonus = Math.max((int) (bonus * SET_COVER_DECLINE), 1);
		}

		return bonuses;
	}

	/**
	 * Scores a single test class.
	 */
	public ScoreResult score(String testClassName) {
		int score = 0;

		// For inner/nested classes (e.g. OuterTest$Inner), fall back to the
		// top-level class name for state lookups where data may be stored under
		// the parent (failure history, changed test detection).
		String topLevel = toTopLevel(testClassName);

		boolean isChanged = changedTestClasses.contains(testClassName);
		if (!isChanged && !topLevel.equals(testClassName)) {
			isChanged = changedTestClasses.contains(topLevel);
		}
		if (isChanged)
			score += weights.changedTest();

		Set<String> deps = depMap.get(testClassName);
		int depTotal = deps.size();
		int depOverlap = 0;
		double complexityOvlp = 0.0;
		int staticFieldOverlap = 0;
		if (!effectiveChangedForOverlap.isEmpty()) {
			Set<String> memberDeps = depMap.hasMemberDeps() ? depMap.getMemberDeps(testClassName) : null;

			if (!setCoverBonuses.isEmpty()) {
				// Greedy set-cover mode: reuse cached overlap count from set-cover computation
				depOverlap = cachedOverlapCounts.getOrDefault(testClassName, 0);
				score += setCoverBonuses.getOrDefault(testClassName, 0);
			} else {
				Set<String> overlapClasses = StructuralChangeAnalyzer.computeOverlapClasses(deps, memberDeps,
						changedMembers, effectiveChangedForOverlap);
				depOverlap = overlapClasses.size();
				score += depOverlapScore(depOverlap, depTotal, weights.depOverlap());

				// Complexity-weighted overlap: sum normalised complexity of overlapping deps
				if (!changeComplexity.isEmpty() && depOverlap > 0) {
					for (String dep : overlapClasses) {
						complexityOvlp += changeComplexity.getOrDefault(dep, 0.0);
					}
					score += complexityScore(complexityOvlp, depTotal, weights.changeComplexity());
				}
			}

			// Optional bonus for changed static fields that this test actually overlaps
			// with.
			// Guarded by member-level deps to avoid class-level noise.
			if (weights.staticFieldBonus() > 0 && memberDeps != null && !memberDeps.isEmpty()) {
				staticFieldOverlap = StructuralChangeAnalyzer.computeStaticFieldOverlap(memberDeps, changedMembers);
				if (staticFieldOverlap > 0) {
					score += weights.staticFieldBonus();
				}
			}
		}

		double failScore = failureScores.getOrDefault(testClassName, 0.0);
		if (failScore == 0.0 && !topLevel.equals(testClassName)) {
			failScore = failureScores.getOrDefault(topLevel, 0.0);
		}
		if (failScore > 0) {
			score += Math.min((int) Math.ceil(failScore), weights.maxFailure());
		}

		boolean isNew = !depMap.testClasses().contains(testClassName);
		if (isNew) {
			score += weights.newTest();
			LOG.fine(() -> "New test class (not in dependency map): " + testClassName + " — awarded newTest bonus of "
					+ weights.newTest());
		}

		long dur = state.getDuration(testClassName, -1);
		boolean isFast = false;
		boolean isSlow = false;
		double sRatio = 0.0;
		if (medianDuration > 0 && dur >= 0) {
			sRatio = speedRatio(dur, medianDuration);
			int speedScore = speedBucketScore(dur, medianDuration, weights.speed(), weights.speedPenalty());
			score += speedScore;
			isFast = speedScore > 0;
			isSlow = speedScore < 0;
		}

		return new ScoreResult(score, depOverlap, depTotal, failScore, isNew, isChanged, isFast, isSlow, complexityOvlp,
				sRatio, staticFieldOverlap > 0);
	}

	public long medianDuration() {
		return medianDuration;
	}

	/**
	 * Returns the scoring weights used by this scorer.
	 */
	public TestOrderState.ScoringWeights weights() {
		return weights;
	}

	/**
	 * Produces a detailed {@link ExplainEntry} for a single test class, including
	 * per-component point breakdowns and the full dependency list.
	 *
	 * @param testClassName
	 *            fully-qualified test class name
	 * @param rank
	 *            1-based position in the sorted order (caller provides this after
	 *            sorting)
	 */
	public ExplainEntry explain(String testClassName, int rank) {
		int totalScore = 0;
		String topLevel = toTopLevel(testClassName);

		// Changed test
		boolean isChanged = changedTestClasses.contains(testClassName);
		if (!isChanged && !topLevel.equals(testClassName)) {
			isChanged = changedTestClasses.contains(topLevel);
		}
		int changedTestPts = isChanged ? weights.changedTest() : 0;
		totalScore += changedTestPts;

		// Dependencies
		Set<String> deps = depMap.get(testClassName);
		int depTotal = deps.size();
		Set<String> memberDeps = depMap.hasMemberDeps() ? depMap.getMemberDeps(testClassName) : null;

		Set<String> overlapClasses = Set.of();
		int depOverlapPts = 0;
		double complexityOvlp = 0.0;
		int complexityPts = 0;
		int staticFieldPts = 0;
		boolean hasStaticFieldOvlp = false;
		int setCoverPts = 0;

		if (!effectiveChangedForOverlap.isEmpty()) {
			if (!setCoverBonuses.isEmpty()) {
				int overlap = cachedOverlapCounts.getOrDefault(testClassName, 0);
				setCoverPts = setCoverBonuses.getOrDefault(testClassName, 0);
				totalScore += setCoverPts;
				// rebuild overlap classes for display
				overlapClasses = StructuralChangeAnalyzer.computeOverlapClasses(deps, memberDeps, changedMembers,
						effectiveChangedForOverlap);
			} else {
				overlapClasses = StructuralChangeAnalyzer.computeOverlapClasses(deps, memberDeps, changedMembers,
						effectiveChangedForOverlap);
				depOverlapPts = depOverlapScore(overlapClasses.size(), depTotal, weights.depOverlap());
				totalScore += depOverlapPts;

				if (!changeComplexity.isEmpty() && !overlapClasses.isEmpty()) {
					for (String dep : overlapClasses) {
						complexityOvlp += changeComplexity.getOrDefault(dep, 0.0);
					}
					complexityPts = complexityScore(complexityOvlp, depTotal, weights.changeComplexity());
					totalScore += complexityPts;
				}
			}

			if (weights.staticFieldBonus() > 0 && memberDeps != null && !memberDeps.isEmpty()) {
				int sfOverlap = StructuralChangeAnalyzer.computeStaticFieldOverlap(memberDeps, changedMembers);
				if (sfOverlap > 0) {
					staticFieldPts = weights.staticFieldBonus();
					hasStaticFieldOvlp = true;
					totalScore += staticFieldPts;
				}
			}
		}

		// Failure history
		double failScore = failureScores.getOrDefault(testClassName, 0.0);
		if (failScore == 0.0 && !topLevel.equals(testClassName)) {
			failScore = failureScores.getOrDefault(topLevel, 0.0);
		}
		int failurePts = failScore > 0 ? Math.min((int) Math.ceil(failScore), weights.maxFailure()) : 0;
		totalScore += failurePts;

		// New test
		boolean isNew = !depMap.testClasses().contains(testClassName);
		int newTestPts = isNew ? weights.newTest() : 0;
		totalScore += newTestPts;

		// Speed
		long dur = state.getDuration(testClassName, -1);
		double sRatio = 0.0;
		int speedPts = 0;
		if (medianDuration > 0 && dur >= 0) {
			sRatio = speedRatio(dur, medianDuration);
			speedPts = speedBucketScore(dur, medianDuration, weights.speed(), weights.speedPenalty());
			totalScore += speedPts;
		}

		return new ExplainEntry(testClassName, rank, totalScore, isChanged, changedTestPts, deps, overlapClasses,
				depOverlapPts, complexityOvlp, complexityPts, hasStaticFieldOvlp, staticFieldPts, failScore, failurePts,
				isNew, newTestPts, dur, medianDuration, sRatio, speedPts, setCoverPts, weights);
	}

	/**
	 * Computes speed score on a logarithmic scale.
	 * <p>
	 * Maps {@code duration/median} to log₂ space, clamped to [2⁻³, 2³] (1/8× to 8×
	 * median). Scores range from {@code +speedBonus} (8× faster) through 0 (at
	 * median) to {@code -speedPenalty} (8× slower), with smooth interpolation in
	 * between.
	 */
	public static int speedBucketScore(long duration, long median, int speedBonus, int speedPenalty) {
		if (median <= 0 || duration < 0 || (speedBonus == 0 && speedPenalty == 0))
			return 0;
		double logRatio = Math.log(Math.max((double) duration / median, 1e-9)) / Math.log(2);
		logRatio = Math.max(-SPEED_LOG_HALF_RANGE, Math.min(SPEED_LOG_HALF_RANGE, logRatio));
		double score;
		if (logRatio <= 0) {
			score = (-logRatio / SPEED_LOG_HALF_RANGE) * speedBonus;
		} else {
			score = -(logRatio / SPEED_LOG_HALF_RANGE) * speedPenalty;
		}
		return (int) Math.round(score);
	}

	/**
	 * Computes the median known duration across the given test class names.
	 */
	public static long computeMedianDuration(TestOrderState state, Iterable<String> testClassNames) {
		// Collect into primitive array to avoid boxing overhead
		long[] buf = new long[64];
		int count = 0;
		for (String tc : testClassNames) {
			long d = state.getDuration(tc, -1);
			if (d >= 0) {
				if (count == buf.length)
					buf = java.util.Arrays.copyOf(buf, buf.length * 2);
				buf[count++] = d;
			}
		}
		if (count == 0)
			return 0;
		java.util.Arrays.sort(buf, 0, count);
		if (count % 2 == 1) {
			return buf[count / 2];
		}
		return (buf[count / 2 - 1] + buf[count / 2]) / 2;
	}

	public static String springContextKey(Class<?> testClass) {
		for (Annotation annotation : testClass.getAnnotations()) {
			String annotationName = annotation.annotationType().getName();
			if (SPRING_BOOT_TEST.equals(annotationName) || CONTEXT_CONFIGURATION.equals(annotationName)) {
				String key = springContextKey(annotation);
				if (key != null) {
					return annotationName + ":" + key;
				}
				return annotationName;
			}
		}
		return null;
	}

	private static String springContextKey(Annotation annotation) {
		List<String> parts = new ArrayList<>();
		addAnnotationValues(annotation, "classes", parts);
		addAnnotationValues(annotation, "value", parts);
		addAnnotationValues(annotation, "locations", parts);
		return parts.isEmpty() ? null : String.join("|", parts);
	}

	private static void addAnnotationValues(Annotation annotation, String attributeName, List<String> parts) {
		try {
			Method method = annotation.annotationType().getMethod(attributeName);
			Object value = method.invoke(annotation);
			if (value == null) {
				return;
			}
			Class<?> valueType = value.getClass();
			if (valueType.isArray()) {
				int length = Array.getLength(value);
				for (int i = 0; i < length; i++) {
					Object element = Array.get(value, i);
					if (element instanceof Class<?> klass) {
						parts.add(klass.getName());
					} else if (element != null) {
						parts.add(element.toString());
					}
				}
			} else if (value instanceof Class<?> klass) {
				parts.add(klass.getName());
			} else {
				parts.add(value.toString());
			}
		} catch (ReflectiveOperationException ignored) {
		}
	}

	/**
	 * Strips inner/nested class suffixes (everything from the first {@code $}) to
	 * get the top-level enclosing class name. Returns the original name if it does
	 * not contain {@code $}.
	 */
	static String toTopLevel(String className) {
		int dollar = className.indexOf('$');
		return dollar > 0 ? className.substring(0, dollar) : className;
	}
}
