package me.bechberger.testorder;

import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;

import java.util.*;

/**
 * Unified test scoring logic used by {@link PriorityClassOrderer},
 * {@link TestSelector}, and the Maven plugin's show-order goal.
 * <p>
 * Scores test classes based on weighted components:
 * changed test bonus, dependency overlap, change complexity, failure recency,
 * new test bonus, and speed bonus/penalty.
 * <p>
 * When structural change analysis ({@link ChangedMembers}) and member-level dependency
 * data are available, the dependency overlap computation uses precise member-level matching:
 * a class only counts as overlapping if the test actually uses a member that changed
 * (or a static initializer changed, which affects all users implicitly).
 */
public class TestScorer {

    /** Half-range in log₂ space for speed buckets (covers 1/8× to 8× the median). */
    private static final double SPEED_LOG_HALF_RANGE = 3.0;

    /** Geometric decline factor for set-cover bonuses. */
    private static final double SET_COVER_DECLINE = 0.8;

    /** Full result of scoring a single test class. */
    public record ScoreResult(int score, int depOverlap, int depTotal, double failScore,
                              boolean isNew, boolean isChanged, boolean isFast, boolean isSlow,
                              double complexityOverlap, double speedRatio,
                              boolean hasStaticFieldOverlap) {}

    /**
     * Computes the weight-independent speed ratio in [-1, 1].
     * <p>
     * Maps {@code duration/median} to log₂ space, clamped to [-3, 3], then
     * normalises to [-1, 1].  Negative values mean faster than median,
     * positive means slower.  At the median the ratio is 0.
     * <p>
     * This value can be stored and later combined with any speed/speedPenalty
     * weights for faithful re-scoring in the optimizer.
     */
    public static double speedRatio(long duration, long median) {
        if (median <= 0 || duration < 0) return 0.0;
        double logRatio = Math.log(Math.max((double) duration / median, 1e-9)) / Math.log(2);
        return Math.max(-1.0, Math.min(1.0, logRatio / SPEED_LOG_HALF_RANGE));
    }

    /**
     * Computes the depOverlap score contribution from raw overlap count, total deps, and weight.
     * <p>
     * Uses {@code overlap / sqrt(totalDeps)} — a geometric mean that balances
     * absolute overlap count (how many changed deps does this test touch?) against
     * test breadth (how many total deps does it have?).  This avoids the problem
     * of pure-ratio scoring where 10 changed deps out of 100 total scores the
     * same as 1 out of 100.
     * <p>
     * The weight acts as the maximum contribution (like {@code maxFailure}).
     */
    public static int depOverlapScore(int depOverlap, int depTotal, int weight) {
        if (depOverlap == 0 || depTotal == 0 || weight == 0) return 0;
        double normalized = depOverlap / Math.sqrt(depTotal);
        return Math.min((int) Math.ceil(normalized * weight), weight);
    }

    /**
     * Computes the change-complexity score contribution from the weighted complexity
     * overlap, total deps, and the changeComplexity weight.
     * <p>
     * {@code complexityOverlap} is the sum of normalised complexity values (0.0–1.0
     * each) for each overlapping changed dependency.  Uses the same
     * {@code / sqrt(totalDeps)} normalization as {@link #depOverlapScore} for consistency.
     */
    public static int complexityScore(double complexityOverlap, int depTotal, int weight) {
        if (complexityOverlap <= 0 || depTotal <= 0 || weight == 0) return 0;
        double normalized = complexityOverlap / Math.sqrt(depTotal);
        return Math.min((int) Math.ceil(normalized * weight), weight);
    }

    private final TestOrderState.ScoringWeights weights;
    private final DependencyMap depMap;
    private final TestOrderState state;
    private final Set<String> changedClasses;
    private final Set<String> changedTestClasses;
    private final ChangedMembers changedMembers;
    private final Map<String, Double> failureScores;
    private final Map<String, Double> changeComplexity;
    private final long medianDuration;
    /** Set-cover bonus per test class (empty when coverageBonus weight is 0). */
    private final Map<String, Integer> setCoverBonuses;
    /** Cached overlap counts from set-cover computation (avoids recomputation in score()). */
    private final Map<String, Integer> cachedOverlapCounts;

    public TestScorer(TestOrderState.ScoringWeights weights, DependencyMap depMap,
                      TestOrderState state, Set<String> changedClasses,
                      Set<String> changedTestClasses,
                      Iterable<String> testClassNames) {
        this(weights, depMap, state, changedClasses, changedTestClasses,
                testClassNames, null, Map.of());
    }

    public TestScorer(TestOrderState.ScoringWeights weights, DependencyMap depMap,
                      TestOrderState state, Set<String> changedClasses,
                      Set<String> changedTestClasses,
                      Iterable<String> testClassNames, ChangedMembers changedMembers) {
        this(weights, depMap, state, changedClasses, changedTestClasses,
                testClassNames, changedMembers, Map.of());
    }

    public TestScorer(TestOrderState.ScoringWeights weights, DependencyMap depMap,
                      TestOrderState state, Set<String> changedClasses,
                      Set<String> changedTestClasses,
                      Iterable<String> testClassNames, ChangedMembers changedMembers,
                      Map<String, Double> changeComplexity) {
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
        this.setCoverBonuses = weights.coverageBonus() > 0
                ? computeSetCoverBonuses(testClassNames, weights.coverageBonus())
                : Map.of();
    }

    /**
     * Greedy set-cover: iteratively pick the test that covers the most
     * not-yet-covered changed source classes.  Each selected test earns a
     * declining bonus: {@code weight} for the first pick, {@code weight-1}
     * for the second, etc. (minimum 1).
     *
     * @return map from test class name to bonus (only tests that cover at
     *         least one changed class appear)
     */
    private Map<String, Integer> computeSetCoverBonuses(Iterable<String> testClassNames, int weight) {
        if (changedClasses.isEmpty() || weight <= 0) return Map.of();

        // Build coverage map: test -> set of changed classes it covers
        Map<String, Set<String>> coverage = new LinkedHashMap<>();
        // Reverse index: changed class -> tests that cover it
        Map<String, List<String>> classToTests = new HashMap<>();
        for (String test : testClassNames) {
            Set<String> deps = depMap.get(test);
            Set<String> memberDeps = depMap.hasMemberDeps()
                    ? depMap.getMemberDeps(test) : null;
            Set<String> covered = StructuralChangeAnalyzer.computeOverlapClasses(
                    deps, memberDeps, changedMembers, changedClasses);
            // cache overlap count to avoid re-calling computeOverlapClasses in score()
            cachedOverlapCounts.put(test, covered.size());
            if (!covered.isEmpty()) {
                coverage.put(test, new HashSet<>(covered));
                for (String c : covered) {
                    classToTests.computeIfAbsent(c, k -> new ArrayList<>()).add(test);
                }
            }
        }

        // Maintain incremental uncovered count per test
        Map<String, Integer> remainingCount = new HashMap<>(coverage.size());
        for (var entry : coverage.entrySet()) {
            remainingCount.put(entry.getKey(), entry.getValue().size());
        }

        Set<String> uncovered = new HashSet<>(changedClasses);
        Map<String, Integer> bonuses = new HashMap<>();
        int bonus = weight;

        while (!uncovered.isEmpty() && !remainingCount.isEmpty()) {
            // Pick the test with the highest remaining uncovered count
            String best = null;
            int bestCount = 0;
            for (var entry : remainingCount.entrySet()) {
                if (entry.getValue() > bestCount) {
                    bestCount = entry.getValue();
                    best = entry.getKey();
                }
            }
            if (best == null || bestCount == 0) break;
            bonuses.put(best, bonus);
            // Decrement counts for tests sharing newly covered classes
            Set<String> bestCoverage = coverage.get(best);
            for (String c : bestCoverage) {
                if (uncovered.remove(c)) {
                    for (String test : classToTests.getOrDefault(c, List.of())) {
                        Integer cnt = remainingCount.get(test);
                        if (cnt != null) {
                            remainingCount.put(test, cnt - 1);
                        }
                    }
                }
            }
            remainingCount.remove(best);
            bonus = Math.max((int) (bonus * SET_COVER_DECLINE), 1);
        }

        return bonuses;
    }

    /**
     * Scores a single test class.
     */
    public ScoreResult score(String testClassName) {
        int score = 0;

        boolean isChanged = changedTestClasses.contains(testClassName);
        if (isChanged) score += weights.changedTest();

        Set<String> deps = depMap.get(testClassName);
        int depTotal = deps.size();
        int depOverlap = 0;
        double complexityOvlp = 0.0;
        int staticFieldOverlap = 0;
        if (!changedClasses.isEmpty()) {
            Set<String> memberDeps = depMap.hasMemberDeps()
                    ? depMap.getMemberDeps(testClassName)
                    : null;

            if (!setCoverBonuses.isEmpty()) {
                // Greedy set-cover mode: reuse cached overlap count from set-cover computation
                depOverlap = cachedOverlapCounts.getOrDefault(testClassName, 0);
                score += setCoverBonuses.getOrDefault(testClassName, 0);
            } else {
                Set<String> overlapClasses = StructuralChangeAnalyzer.computeOverlapClasses(
                        deps, memberDeps, changedMembers, changedClasses);
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

            // Optional bonus for changed static fields that this test actually overlaps with.
            // Guarded by member-level deps to avoid class-level noise.
            if (weights.staticFieldBonus() > 0 && memberDeps != null && !memberDeps.isEmpty()) {
                staticFieldOverlap = StructuralChangeAnalyzer.computeStaticFieldOverlap(memberDeps, changedMembers);
                if (staticFieldOverlap > 0) {
                    score += weights.staticFieldBonus();
                }
            }
        }

        double failScore = failureScores.getOrDefault(testClassName, 0.0);
        if (failScore > 0) {
            score += Math.min((int) Math.ceil(failScore), weights.maxFailure());
        }

        boolean isNew = !depMap.testClasses().contains(testClassName);
        if (isNew) score += weights.newTest();

        long dur = state.getDuration(testClassName, -1);
        boolean isFast = false;
        boolean isSlow = false;
        double sRatio = 0.0;
        if (medianDuration > 0 && dur >= 0) {
            sRatio = speedRatio(dur, medianDuration);
            int speedScore = speedBucketScore(dur, medianDuration,
                    weights.speed(), weights.speedPenalty());
            score += speedScore;
            isFast = speedScore > 0;
            isSlow = speedScore < 0;
        }

        return new ScoreResult(score, depOverlap, depTotal, failScore, isNew, isChanged,
                isFast, isSlow, complexityOvlp, sRatio, staticFieldOverlap > 0);
    }

    public long medianDuration() { return medianDuration; }

    /**
     * Computes speed score on a logarithmic scale.
     * <p>
     * Maps {@code duration/median} to log₂ space, clamped to [2⁻³, 2³] (1/8× to 8× median).
     * Scores range from {@code +speedBonus} (8× faster) through 0 (at median) to
     * {@code -speedPenalty} (8× slower), with smooth interpolation in between.
     */
    public static int speedBucketScore(long duration, long median, int speedBonus, int speedPenalty) {
        if (median <= 0 || duration < 0 || (speedBonus == 0 && speedPenalty == 0)) return 0;
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
                if (count == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                buf[count++] = d;
            }
        }
        if (count == 0) return 0;
        java.util.Arrays.sort(buf, 0, count);
        return buf[count / 2];
    }
}
