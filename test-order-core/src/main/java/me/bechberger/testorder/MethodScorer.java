package me.bechberger.testorder;

import java.util.*;

/**
 * Method-level scoring for fail-fast reordering within test classes.
 * <p>
 * Scores individual test methods based on:
 * <ul>
 * <li><b>Failure recency:</b> Recent failures boost score so flaky methods run
 * first.</li>
 * <li><b>Speed relative to class median:</b> Fast/slow compared to other
 * methods in the same class.</li>
 * <li><b>Dependency overlap:</b> Methods whose per-method deps overlap more
 * with changed classes get a higher score (available with METHOD and MEMBER
 * instrumentation modes).</li>
 * <li><b>New method:</b> Methods with no telemetry history get a bonus
 * (untested = risky).</li>
 * <li><b>Changed method:</b> Methods whose source code changed get a
 * bonus.</li>
 * </ul>
 * <p>
 * Key difference from class-level scoring: speed thresholds are
 * <b>class-local</b>. A method's duration is compared against the median of all
 * methods in its class, not a global median. This ensures fair comparison
 * across classes of different speeds.
 */
public class MethodScorer {

	/**
	 * Half-range in log₂ space for speed buckets (covers 1/8× to 8× the median).
	 */
	private static final double SPEED_LOG_HALF_RANGE = 3.0;

	/** Geometric decline factor for set-cover bonuses. */
	private static final double SET_COVER_DECLINE = ScoringConstants.SET_COVER_DECLINE;

	/** Result of scoring a single test method. */
	public record MethodScoreResult(String className, String methodName, double score, double failureRecencyBonus,
			double speedBonus, double depOverlapBonus, double coverageBonus, double newMethodBonus,
			double changedMethodBonus, boolean isRecent, boolean isNew, boolean isChanged, boolean isFast,
			boolean isSlow, long classMedianMs) {
	}

	/** Metadata for a method to be scored. */
	public record MethodMetadata(String className, String methodName, long durationMs, Long lastFailureEpochMs) {
	}

	private final TestOrderState.MethodScoringWeights weights;
	private final TestOrderState state;
	private final DependencyMap depMap;
	private final Set<String> changedClasses;
	private final Set<String> changedMethods;

	/**
	 * Creates a method scorer with method-level weights, state, and optional
	 * dependency/change data.
	 *
	 * @param weights
	 *            method-level scoring weights
	 * @param state
	 *            test order state containing failure history
	 * @param depMap
	 *            dependency map with per-method deps (may be null)
	 * @param changedClasses
	 *            set of changed class FQCNs (may be null)
	 * @param changedMethods
	 *            set of changed method keys className#methodName (may be null)
	 */
	public MethodScorer(TestOrderState.MethodScoringWeights weights, TestOrderState state, DependencyMap depMap,
			Set<String> changedClasses, Set<String> changedMethods) {
		this.weights = weights;
		this.state = state;
		this.depMap = depMap;
		this.changedClasses = changedClasses != null ? changedClasses : Set.of();
		this.changedMethods = changedMethods != null ? changedMethods : Set.of();
	}

	/**
	 * Scores a list of methods, typically all methods in a single test class.
	 * <p>
	 * Speed bonuses/penalties are calculated relative to the median duration of
	 * methods in the same class (passed as classMedianMs). This ensures methods are
	 * fairly compared to their peers.
	 *
	 * @param methods
	 *            methods to score, all from the same class
	 * @return list of scored results (same order as input unless specified
	 *         otherwise)
	 */
	public List<MethodScoreResult> score(List<MethodMetadata> methods) {
		if (methods.isEmpty())
			return List.of();

		// Compute class-local median
		long classMedian = computeMedianDuration(methods);

		// Set-cover bonuses (only when coverageBonus weight > 0)
		Map<String, Double> setCoverBonuses = weights.coverageBonus() > 0
				? computeSetCoverBonuses(methods, weights.coverageBonus())
				: Map.of();

		List<MethodScoreResult> results = new ArrayList<>();
		for (MethodMetadata m : methods) {
			double failRecencyBonus = computeFailureRecencyBonus(m);
			double speedBonus = computeSpeedBonus(m.durationMs(), classMedian);
			double newMethodBonus = computeNewMethodBonus(m);
			double changedMethodBonus = computeChangedMethodBonus(m);

			double depOverlapBonus;
			double coverageBonusVal;
			if (!setCoverBonuses.isEmpty()) {
				// Set-cover mode: use pre-computed bonus instead of Jaccard-based depOverlap
				String methodKey = m.className() + "#" + m.methodName();
				coverageBonusVal = setCoverBonuses.getOrDefault(methodKey, 0.0);
				depOverlapBonus = 0.0;
			} else {
				depOverlapBonus = computeDepOverlapBonus(m);
				coverageBonusVal = 0.0;
			}

			double score = failRecencyBonus + speedBonus + depOverlapBonus + coverageBonusVal + newMethodBonus
					+ changedMethodBonus;

			boolean isRecent = failRecencyBonus > 0;
			boolean isNew = newMethodBonus > 0;
			boolean isChanged = changedMethodBonus > 0;
			boolean isFast = speedBonus > 0;
			boolean isSlow = speedBonus < 0;

			results.add(new MethodScoreResult(m.className(), m.methodName(), score, failRecencyBonus, speedBonus,
					depOverlapBonus, coverageBonusVal, newMethodBonus, changedMethodBonus, isRecent, isNew, isChanged,
					isFast, isSlow, classMedian));
		}
		return results;
	}

	/**
	 * Greedy set-cover for methods: iteratively pick the method whose deps cover
	 * the most not-yet-covered changed classes, awarding a declining bonus.
	 *
	 * @return map from method key (className#methodName) to bonus value
	 */
	private Map<String, Double> computeSetCoverBonuses(List<MethodMetadata> methods, double weight) {
		if (depMap == null || changedClasses.isEmpty() || weight <= 0)
			return Map.of();

		// Build coverage map: methodKey -> set of changed classes it covers
		Map<String, Set<String>> coverage = new LinkedHashMap<>();
		for (MethodMetadata m : methods) {
			String methodKey = m.className() + "#" + m.methodName();
			Set<String> methodDeps = depMap.getMethodDeps(methodKey);
			if (methodDeps == null || methodDeps.isEmpty())
				continue;
			if (methodDeps.stream().anyMatch(changedClasses::contains)) {
				Set<String> covered = methodDeps.stream().filter(changedClasses::contains)
						.collect(java.util.stream.Collectors.toUnmodifiableSet());
				coverage.put(methodKey, covered);
			}
		}
		Map<String, Double> bonuses = new HashMap<>();
		double bonus = weight;

		SetCoverComputer.Result<String> result = new SetCoverComputer<>(coverage, changedClasses).compute();
		for (String best : result.order()) {
			bonuses.put(best, bonus);
			bonus = Math.max(bonus * SET_COVER_DECLINE, ScoringConstants.SET_COVER_FLOOR);
		}

		return bonuses;
	}

	/**
	 * Computes dependency overlap bonus for a method.
	 * <p>
	 * Uses {@code overlap / sqrt(totalMethodDeps)} normalisation (matching
	 * class-level formula) scaled by the depOverlap weight. Returns 0 if no
	 * method-level dependency data is available.
	 */
	private double computeDepOverlapBonus(MethodMetadata m) {
		if (depMap == null || changedClasses.isEmpty() || weights.depOverlap() == 0) {
			return 0.0;
		}
		String methodKey = m.className() + "#" + m.methodName();
		Set<String> methodDeps = depMap.getMethodDeps(methodKey);
		if (methodDeps == null || methodDeps.isEmpty()) {
			return 0.0;
		}
		long intersectionSize = 0;
		for (String dep : methodDeps) {
			if (DependencyMap.changedClassesContains(changedClasses, dep))
				intersectionSize++;
		}
		if (intersectionSize == 0)
			return 0.0;
		double normalized = intersectionSize / Math.sqrt(Math.max(methodDeps.size(), TestScorer.MIN_DEPS_DENOMINATOR));
		return Math.min(normalized * weights.depOverlap(), weights.depOverlap());
	}

	/**
	 * Computes new method bonus. A method is "new" if it has no duration history in
	 * the state (never seen before).
	 */
	private double computeNewMethodBonus(MethodMetadata m) {
		if (weights.newMethod() == 0)
			return 0.0;
		double existingDuration = state.getDurationMethod(m.className(), m.methodName(), -1.0);
		if (existingDuration < 0) {
			return weights.newMethod();
		}
		return 0.0;
	}

	/**
	 * Computes changed method bonus. A method is "changed" if its source hash
	 * differs from the previous snapshot.
	 */
	private double computeChangedMethodBonus(MethodMetadata m) {
		if (weights.changedMethod() == 0 || changedMethods.isEmpty())
			return 0.0;
		String methodKey = m.className() + "#" + m.methodName();
		if (changedMethods.contains(methodKey)) {
			return weights.changedMethod();
		}
		return 0.0;
	}

	/**
	 * Computes failure recency bonus.
	 * <p>
	 * Uses ceil(failScore) capped at failureRecency weight — consistent with
	 * class-level formula where each accumulated failure contributes 1 point up to
	 * the weight cap. Uses methodFailureScore() which includes both historical and
	 * current-run failures.
	 */
	private double computeFailureRecencyBonus(MethodMetadata m) {
		double failScore = state.methodFailureScore(m.className(), m.methodName());
		if (!(failScore > 0))
			return 0.0;
		return Math.min(Math.ceil(failScore), weights.failureRecency());
	}

	/**
	 * Computes speed bonus/penalty on a logarithmic scale relative to class median.
	 * <p>
	 * Maps {@code duration/classMedian} to log₂ space, clamped to [−3, +3]. Scores
	 * range from {@code +fast} (8× faster) through 0 (at median) to {@code -slow}
	 * (8× slower).
	 */
	private double computeSpeedBonus(long duration, long classMedian) {
		if (classMedian <= 0 || duration < 0 || (weights.fast() == 0 && weights.slow() == 0))
			return 0.0;
		double logRatio = Math.log(Math.max((double) duration / classMedian, 1e-9)) / Math.log(2);
		logRatio = Math.max(-SPEED_LOG_HALF_RANGE, Math.min(SPEED_LOG_HALF_RANGE, logRatio));
		if (logRatio <= 0) {
			return (-logRatio / SPEED_LOG_HALF_RANGE) * weights.fast();
		} else {
			return -(logRatio / SPEED_LOG_HALF_RANGE) * weights.slow();
		}
	}

	/**
	 * Computes the median duration of methods in a class. Methods with unknown
	 * duration (negative values) are excluded.
	 */
	private long computeMedianDuration(List<MethodMetadata> methods) {
		if (methods.isEmpty())
			return 0;
		long[] durations = new long[methods.size()];
		int count = 0;
		for (MethodMetadata m : methods) {
			if (m.durationMs() >= 0)
				durations[count++] = m.durationMs();
		}
		if (count == 0)
			return 0;
		java.util.Arrays.sort(durations, 0, count);
		int mid = count / 2;
		if (count % 2 == 0) {
			long a = durations[mid - 1];
			long b = durations[mid];
			return a / 2 + b / 2 + (a % 2 + b % 2) / 2;
		} else {
			return durations[mid];
		}
	}
}
