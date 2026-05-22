package me.bechberger.testorder.ml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.bechberger.testorder.DependencyMap;

/**
 * Extracts feature vectors for the ML failure prediction model.
 * <p>
 * Returns plain {@code double[]} arrays with no external ML dependency — the ML
 * framework adapter in the plugin module converts these to training examples.
 * <p>
 * Feature layout (total = {@value #FEATURE_COUNT}):
 * <ol start="0">
 * <li>{@code sharedFailureProximity} — co-failure partner set similarity
 * (0.0–1.0)</li>
 * <li>{@code failureTypeBucket} — hash of most recent failure exception type,
 * or -1</li>
 * <li>{@code durationVariance} — coefficient of variation of recent
 * durations</li>
 * <li>{@code runsSinceLastFail} — runs since last failure (capped at 100)</li>
 * <li>{@code failStreak} — consecutive failures from most recent runs (capped
 * at 20)</li>
 * <li>{@code changedPkgOverlap} — fraction of test dependency packages
 * overlapping with packages containing changed production classes</li>
 * <li>{@code testInChangedPkg} — 1.0 if test is in a package with changed test
 * classes</li>
 * <li>{@code isAffected} — 1.0 if any of the test's direct production
 * dependencies were changed</li>
 * <li>{@code depOverlapCount} — number of production dependencies that were
 * changed</li>
 * <li>{@code depOverlapRatio} — fraction of production dependencies that were
 * changed</li>
 * <li>{@code ewmaFailureRate} — EWMA of failure outcomes (α=0.3)</li>
 * <li>{@code failureTrendSlope} — linear trend of failure rate over sliding
 * windows</li>
 * <li>{@code failureAutocorrelation} — lag-1 autocorrelation of failure
 * outcomes</li>
 * <li>{@code failureRateVolatility} — std dev of failure rate across sliding
 * windows</li>
 * <li>{@code depCount} — total number of production dependencies for this
 * test</li>
 * <li>{@code failureRate} — overall historical failure rate (totalFailures /
 * totalRuns)</li>
 * <li>{@code changedClassCount} — number of production classes changed in this
 * diff</li>
 * <li>{@code meanDurationMs} — mean test duration in milliseconds</li>
 * <li>{@code pkgFailureRate} — average failure rate of other tests in the same
 * package</li>
 * <li>{@code pkgTestCount} — number of known tests in the same package</li>
 * <li>{@code changedTestClassCount} — number of test classes changed in this
 * diff</li>
 * <li>{@code changedPkgCount} — number of distinct packages among changed
 * production classes</li>
 * <li>{@code depPkgCount} — number of distinct packages in test's production
 * dependencies</li>
 * <li>{@code lastOutcome} — 1.0 if the test failed in its most recent run, 0.0
 * if passed</li>
 * <li>{@code durationTrend} — linear slope of recent durations (positive =
 * getting slower)</li>
 * <li>{@code maxDurationMs} — maximum observed duration in milliseconds</li>
 * </ol>
 */
public final class MLFeatureExtractor {

	/** Total number of features in the output vector. */
	public static final int FEATURE_COUNT = 26;

	/** Bucket count used for hashing the failure exception type into a scalar. */
	static final int FAILURE_TYPE_BUCKETS = 128;

	// Feature indices
	public static final int IDX_SHARED_FAILURE_PROXIMITY = 0;
	public static final int IDX_FAILURE_TYPE_BUCKET = 1;
	public static final int IDX_DURATION_VARIANCE = 2;
	public static final int IDX_RUNS_SINCE_LAST_FAIL = 3;
	public static final int IDX_FAIL_STREAK = 4;
	public static final int IDX_CHANGED_PKG_OVERLAP = 5;
	public static final int IDX_TEST_IN_CHANGED_PKG = 6;
	public static final int IDX_IS_AFFECTED = 7;
	public static final int IDX_DEP_OVERLAP_COUNT = 8;
	public static final int IDX_DEP_OVERLAP_RATIO = 9;
	public static final int IDX_EWMA_FAILURE_RATE = 10;
	public static final int IDX_FAILURE_TREND_SLOPE = 11;
	public static final int IDX_FAILURE_AUTOCORRELATION = 12;
	public static final int IDX_FAILURE_RATE_VOLATILITY = 13;
	public static final int IDX_DEP_COUNT = 14;
	public static final int IDX_FAILURE_RATE = 15;
	public static final int IDX_CHANGED_CLASS_COUNT = 16;
	public static final int IDX_MEAN_DURATION_MS = 17;
	public static final int IDX_PKG_FAILURE_RATE = 18;
	public static final int IDX_PKG_TEST_COUNT = 19;
	public static final int IDX_CHANGED_TEST_CLASS_COUNT = 20;
	public static final int IDX_CHANGED_PKG_COUNT = 21;
	public static final int IDX_DEP_PKG_COUNT = 22;
	public static final int IDX_LAST_OUTCOME = 23;
	public static final int IDX_DURATION_TREND = 24;
	public static final int IDX_MAX_DURATION_MS = 25;

	/** Feature names for use by ML frameworks (e.g. Tribuo). */
	public static final String[] FEATURE_NAMES;

	static {
		FEATURE_NAMES = new String[FEATURE_COUNT];
		FEATURE_NAMES[IDX_SHARED_FAILURE_PROXIMITY] = "sharedFailureProximity";
		FEATURE_NAMES[IDX_FAILURE_TYPE_BUCKET] = "failureTypeBucket";
		FEATURE_NAMES[IDX_DURATION_VARIANCE] = "durationVariance";
		FEATURE_NAMES[IDX_RUNS_SINCE_LAST_FAIL] = "runsSinceLastFail";
		FEATURE_NAMES[IDX_FAIL_STREAK] = "failStreak";
		FEATURE_NAMES[IDX_CHANGED_PKG_OVERLAP] = "changedPkgOverlap";
		FEATURE_NAMES[IDX_TEST_IN_CHANGED_PKG] = "testInChangedPkg";
		FEATURE_NAMES[IDX_IS_AFFECTED] = "isAffected";
		FEATURE_NAMES[IDX_DEP_OVERLAP_COUNT] = "depOverlapCount";
		FEATURE_NAMES[IDX_DEP_OVERLAP_RATIO] = "depOverlapRatio";
		FEATURE_NAMES[IDX_EWMA_FAILURE_RATE] = "ewmaFailureRate";
		FEATURE_NAMES[IDX_FAILURE_TREND_SLOPE] = "failureTrendSlope";
		FEATURE_NAMES[IDX_FAILURE_AUTOCORRELATION] = "failureAutocorrelation";
		FEATURE_NAMES[IDX_FAILURE_RATE_VOLATILITY] = "failureRateVolatility";
		FEATURE_NAMES[IDX_DEP_COUNT] = "depCount";
		FEATURE_NAMES[IDX_FAILURE_RATE] = "failureRate";
		FEATURE_NAMES[IDX_CHANGED_CLASS_COUNT] = "changedClassCount";
		FEATURE_NAMES[IDX_MEAN_DURATION_MS] = "meanDurationMs";
		FEATURE_NAMES[IDX_PKG_FAILURE_RATE] = "pkgFailureRate";
		FEATURE_NAMES[IDX_PKG_TEST_COUNT] = "pkgTestCount";
		FEATURE_NAMES[IDX_CHANGED_TEST_CLASS_COUNT] = "changedTestClassCount";
		FEATURE_NAMES[IDX_CHANGED_PKG_COUNT] = "changedPkgCount";
		FEATURE_NAMES[IDX_DEP_PKG_COUNT] = "depPkgCount";
		FEATURE_NAMES[IDX_LAST_OUTCOME] = "lastOutcome";
		FEATURE_NAMES[IDX_DURATION_TREND] = "durationTrend";
		FEATURE_NAMES[IDX_MAX_DURATION_MS] = "maxDurationMs";
	}

	private MLFeatureExtractor() {
	}

	/**
	 * A labeled training example extracted from history.
	 */
	public record TrainingExample(double[] features, boolean failed) {
	}

	/**
	 * Extracts training examples from ML history with correct temporal ordering.
	 * Features for each outcome are computed using state accumulated BEFORE that
	 * run, ensuring no data leakage.
	 *
	 * @param history
	 *            ordered list of ML run records (oldest first)
	 * @param depMap
	 *            dependency map (test → production classes)
	 * @return list of (features, label) training examples
	 */
	public static List<TrainingExample> extractTrainingExamples(List<MLRunRecord> history, DependencyMap depMap) {
		List<TrainingExample> examples = new ArrayList<>();
		Map<String, TestStats> incrementalStats = new HashMap<>();
		Map<String, String> statsPackageCache = new HashMap<>();
		CoFailureTracker incrementalCoFailure = new CoFailureTracker();

		for (MLRunRecord run : history) {
			Set<String> changedClasses = new HashSet<>(run.changedClasses());
			Set<String> changedTestClasses = new HashSet<>(run.changedTestClasses());
			Set<String> failedInRun = new HashSet<>();

			for (MLTestOutcome outcome : run.outcomes()) {
				double[] features = extract(outcome.testClass(), depMap, incrementalCoFailure, changedClasses,
						changedTestClasses, incrementalStats, statsPackageCache);
				examples.add(new TrainingExample(features, outcome.failed()));

				// Update stats AFTER extraction (temporal correctness)
				updateStats(incrementalStats, outcome);
				statsPackageCache.putIfAbsent(outcome.testClass(), extractPackage(outcome.testClass()));
				if (outcome.failed()) {
					failedInRun.add(outcome.testClass());
				}
			}
			incrementalCoFailure.recordRun(failedInRun);

			// Increment runsSinceLastFail for tests not seen in this run
			Set<String> runTests = new HashSet<>();
			for (MLTestOutcome outcome : run.outcomes()) {
				runTests.add(outcome.testClass());
			}
			for (var entry : incrementalStats.entrySet()) {
				if (!runTests.contains(entry.getKey())) {
					entry.getValue().runsSinceLastFail++;
				}
			}
		}
		return examples;
	}

	/**
	 * Extracts a feature vector for a single test class.
	 *
	 * @param testClass
	 *            FQN of the test class
	 * @param depMap
	 *            dependency map (test → production classes)
	 * @param coFailure
	 *            co-failure tracker built from history
	 * @param changedClasses
	 *            production classes in the current diff context
	 * @param changedTestClasses
	 *            test classes in the current diff context
	 * @param stats
	 *            per-test running statistics from history
	 * @return double array of length {@value #FEATURE_COUNT}
	 */
	public static double[] extract(String testClass, DependencyMap depMap, CoFailureTracker coFailure,
			Set<String> changedClasses, Set<String> changedTestClasses, Map<String, TestStats> stats) {
		return extract(testClass, depMap, coFailure, changedClasses, changedTestClasses, stats, null);
	}

	static double[] extract(String testClass, DependencyMap depMap, CoFailureTracker coFailure,
			Set<String> changedClasses, Set<String> changedTestClasses, Map<String, TestStats> stats,
			Map<String, String> statsPackageCache) {
		double[] features = new double[FEATURE_COUNT];

		// For inner classes (e.g. ValidateTest$NotNull), history data is stored
		// under the top-level class name. Compute the top-level name once and
		// use it as a fallback key for stats and co-failure lookups.
		String topLevel = toTopLevel(testClass);
		boolean isInnerClass = !topLevel.equals(testClass);

		// Feature 0: shared failure proximity
		double proximity = coFailure.sharedFailureProximity(testClass, changedTestClasses);
		if (proximity == 0.0 && isInnerClass) {
			proximity = coFailure.sharedFailureProximity(topLevel, changedTestClasses);
		}
		features[IDX_SHARED_FAILURE_PROXIMITY] = proximity;

		// Feature 1: failure type bucket
		// ML history records outcomes at top-level class granularity, so inner
		// classes won't have their own stats entry. Fall back to the enclosing
		// top-level class stats so inner classes inherit their parent's history.
		TestStats ts = stats.get(testClass);
		if (ts == null && isInnerClass) {
			ts = stats.get(topLevel);
		}
		if (ts != null && ts.lastFailureType != null) {
			features[IDX_FAILURE_TYPE_BUCKET] = hashToBucket(ts.lastFailureType, FAILURE_TYPE_BUCKETS);
		} else {
			features[IDX_FAILURE_TYPE_BUCKET] = -1.0;
		}

		// Feature 2: duration variance (coefficient of variation)
		if (ts != null && ts.durationCount >= 2) {
			double mean = ts.durationSum / ts.durationCount;
			double variance = (ts.durationSumSq / ts.durationCount) - (mean * mean);
			features[IDX_DURATION_VARIANCE] = mean > 0 ? Math.sqrt(Math.max(0, variance)) / mean : 0.0;
		}

		// Feature 3: runs since last failure (capped)
		features[IDX_RUNS_SINCE_LAST_FAIL] = ts != null ? Math.min(ts.runsSinceLastFail, 100) : 100;

		// Feature 4: fail streak
		features[IDX_FAIL_STREAK] = ts != null ? Math.min(ts.failStreak, 20) : 0;

		// Feature 5: changed package overlap — fraction of test's dependency packages
		// that also contain changed production classes
		Set<String> deps = depMap.get(testClass);
		Set<String> depPackages = new HashSet<>();
		for (String dep : deps) {
			depPackages.add(extractPackage(dep));
		}
		Set<String> changedPackages = new HashSet<>();
		for (String cls : changedClasses) {
			changedPackages.add(extractPackage(cls));
		}
		if (!depPackages.isEmpty() && !changedPackages.isEmpty()) {
			int overlap = 0;
			for (String pkg : depPackages) {
				if (changedPackages.contains(pkg)) {
					overlap++;
				}
			}
			features[IDX_CHANGED_PKG_OVERLAP] = (double) overlap / depPackages.size();
		}

		// Feature 6: test in changed package — 1.0 if the test class is in a package
		// where at least one test class was changed
		String testPkg = extractPackage(testClass);
		for (String changedTest : changedTestClasses) {
			if (extractPackage(changedTest).equals(testPkg)) {
				features[IDX_TEST_IN_CHANGED_PKG] = 1.0;
				break;
			}
		}

		// Feature 7: isAffected — 1.0 if any of the test's production dependencies
		// are in the changed classes set (direct dependency overlap)
		int depOverlapCount = 0;
		if (!deps.isEmpty() && !changedClasses.isEmpty()) {
			for (String dep : deps) {
				if (changedClasses.contains(dep)) {
					depOverlapCount++;
				}
			}
			if (depOverlapCount > 0) {
				features[IDX_IS_AFFECTED] = 1.0;
			}
		}

		// Feature 8: depOverlapCount — raw number of production deps that were changed
		features[IDX_DEP_OVERLAP_COUNT] = depOverlapCount;

		// Feature 9: depOverlapRatio — fraction of production deps that were changed
		if (!deps.isEmpty()) {
			features[IDX_DEP_OVERLAP_RATIO] = (double) depOverlapCount / deps.size();
		}

		// Feature 10: EWMA failure rate
		features[IDX_EWMA_FAILURE_RATE] = ts != null ? ts.ewmaFailureRate : 0.0;

		// Feature 11: failure trend slope (linear regression of windowed failure rates)
		if (ts != null && ts.recentCount >= 10) {
			features[IDX_FAILURE_TREND_SLOPE] = computeTrendSlope(ts);
		}

		// Feature 12: lag-1 autocorrelation of failure outcomes
		if (ts != null && ts.recentCount >= 5) {
			features[IDX_FAILURE_AUTOCORRELATION] = computeAutocorrelation(ts);
		}

		// Feature 13: failure rate volatility (std dev of windowed failure rates)
		if (ts != null && ts.recentCount >= 10) {
			features[IDX_FAILURE_RATE_VOLATILITY] = computeVolatility(ts);
		}

		// Feature 14: total production dependency count (test complexity proxy)
		features[IDX_DEP_COUNT] = deps.size();

		// Feature 15: overall historical failure rate
		if (ts != null && ts.totalRuns > 0) {
			features[IDX_FAILURE_RATE] = (double) ts.totalFailures / ts.totalRuns;
		}

		// Feature 16: number of changed production classes in this diff
		features[IDX_CHANGED_CLASS_COUNT] = changedClasses.size();

		// Feature 17: mean duration in milliseconds
		if (ts != null && ts.durationCount > 0) {
			features[IDX_MEAN_DURATION_MS] = ts.durationSum / ts.durationCount;
		}

		// Feature 18: package failure rate — average failure rate of other tests in
		// same package
		// Feature 19: package test count — how many known tests share this package
		int pkgTestCount = 0;
		double pkgFailRateSum = 0;
		for (var entry : stats.entrySet()) {
			String key = entry.getKey();
			if (!key.equals(testClass)) {
				String entryPkg = statsPackageCache != null
						? statsPackageCache.getOrDefault(key, extractPackage(key))
						: extractPackage(key);
				if (entryPkg.equals(testPkg)) {
					TestStats other = entry.getValue();
					if (other.totalRuns > 0) {
						pkgFailRateSum += (double) other.totalFailures / other.totalRuns;
						pkgTestCount++;
					}
				}
			}
		}
		features[IDX_PKG_TEST_COUNT] = pkgTestCount;
		if (pkgTestCount > 0) {
			features[IDX_PKG_FAILURE_RATE] = pkgFailRateSum / pkgTestCount;
		}

		// Feature 20: number of changed test classes
		features[IDX_CHANGED_TEST_CLASS_COUNT] = changedTestClasses.size();

		// Feature 21: number of distinct packages among changed production classes
		features[IDX_CHANGED_PKG_COUNT] = changedPackages.size();

		// Feature 22: number of distinct packages in test's production dependencies
		features[IDX_DEP_PKG_COUNT] = depPackages.size();

		// Feature 23: last outcome — 1.0 if last run failed
		if (ts != null && ts.totalRuns > 0) {
			features[IDX_LAST_OUTCOME] = ts.lastFailed ? 1.0 : 0.0;
		}

		// Feature 24: duration trend — linear slope of recent durations
		if (ts != null && ts.durationHistCount >= 5) {
			features[IDX_DURATION_TREND] = computeDurationTrend(ts);
		}

		// Feature 25: max observed duration
		if (ts != null && ts.durationCount > 0) {
			features[IDX_MAX_DURATION_MS] = ts.maxDuration;
		}

		return features;
	}

	/**
	 * Builds per-test running statistics from the full ML history. Used at
	 * prediction time when we don't need temporal training extraction.
	 */
	public static Map<String, TestStats> buildStats(List<MLRunRecord> history) {
		Map<String, TestStats> stats = new HashMap<>();
		for (MLRunRecord run : history) {
			Set<String> runTests = new HashSet<>();
			for (MLTestOutcome outcome : run.outcomes()) {
				runTests.add(outcome.testClass());
				updateStats(stats, outcome);
			}
			for (var entry : stats.entrySet()) {
				if (!runTests.contains(entry.getKey())) {
					entry.getValue().runsSinceLastFail++;
				}
			}
		}
		return stats;
	}

	/**
	 * Builds a {@link CoFailureTracker} from the full ML history.
	 */
	public static CoFailureTracker buildCoFailureTracker(List<MLRunRecord> history) {
		CoFailureTracker tracker = new CoFailureTracker();
		for (MLRunRecord run : history) {
			Set<String> failedInRun = new HashSet<>();
			for (MLTestOutcome outcome : run.outcomes()) {
				if (outcome.failed()) {
					failedInRun.add(outcome.testClass());
				}
			}
			tracker.recordRun(failedInRun);
		}
		return tracker;
	}

	private static void updateStats(Map<String, TestStats> stats, MLTestOutcome outcome) {
		TestStats ts = stats.computeIfAbsent(outcome.testClass(), k -> new TestStats());
		ts.totalRuns++;
		double dur = outcome.durationMs();
		ts.durationSum += dur;
		ts.durationSumSq += dur * dur;
		ts.durationCount++;
		if (dur > ts.maxDuration) {
			ts.maxDuration = dur;
		}
		ts.lastFailed = outcome.failed();
		if (outcome.failed()) {
			ts.runsSinceLastFail = 0;
			ts.failStreak++;
			ts.totalFailures++;
			if (outcome.failureType() != null) {
				ts.lastFailureType = outcome.failureType();
			}
		} else {
			ts.runsSinceLastFail++;
			ts.failStreak = 0;
		}
		// Temporal: update EWMA and ring buffer
		ts.ewmaFailureRate = TestStats.EWMA_ALPHA * (outcome.failed() ? 1.0 : 0.0)
				+ (1 - TestStats.EWMA_ALPHA) * ts.ewmaFailureRate;
		ts.recentOutcomes[ts.recentCount % TestStats.WINDOW_SIZE] = outcome.failed() ? 1 : 0;
		ts.recentCount++;
		// Duration ring buffer
		ts.recentDurations[ts.durationHistCount % TestStats.WINDOW_SIZE] = dur;
		ts.durationHistCount++;
	}

	/**
	 * Running statistics for a single test class, built incrementally from history.
	 */
	public static final class TestStats {
		int totalRuns;
		int totalFailures;
		double durationSum;
		double durationSumSq;
		int durationCount;
		int runsSinceLastFail = 100;
		int failStreak;
		String lastFailureType;
		boolean lastFailed;
		double maxDuration;
		// Temporal tracking for time series features
		static final int WINDOW_SIZE = 50;
		static final double EWMA_ALPHA = 0.3;
		final int[] recentOutcomes = new int[WINDOW_SIZE]; // ring buffer: 0=pass, 1=fail
		int recentCount; // total outcomes added (index = recentCount % WINDOW_SIZE)
		double ewmaFailureRate;
		// Duration ring buffer for duration trend
		final double[] recentDurations = new double[WINDOW_SIZE];
		int durationHistCount;
	}

	// ── Temporal feature computation ──────────────────────────────────────

	/**
	 * Computes linear trend slope of failure rate over sliding sub-windows.
	 * Positive slope means the test is failing more frequently over time.
	 */
	private static double computeTrendSlope(TestStats ts) {
		int count = Math.min(ts.recentCount, TestStats.WINDOW_SIZE);
		int subWindow = 5;
		if (count < subWindow * 2) {
			return 0.0;
		}
		int numWindows = count / subWindow;
		double[] rates = new double[numWindows];
		int oldest = ts.recentCount - count;
		for (int w = 0; w < numWindows; w++) {
			int fails = 0;
			for (int i = 0; i < subWindow; i++) {
				fails += ts.recentOutcomes[(oldest + w * subWindow + i) % TestStats.WINDOW_SIZE];
			}
			rates[w] = (double) fails / subWindow;
		}
		double meanX = (numWindows - 1) / 2.0;
		double meanY = 0;
		for (double r : rates) {
			meanY += r;
		}
		meanY /= numWindows;
		double num = 0, den = 0;
		for (int i = 0; i < numWindows; i++) {
			num += (i - meanX) * (rates[i] - meanY);
			den += (i - meanX) * (i - meanX);
		}
		return den > 0 ? num / den : 0.0;
	}

	/**
	 * Computes lag-1 autocorrelation of failure outcomes. High positive value
	 * indicates clustered failures (flaky). High negative value indicates
	 * alternating pass/fail.
	 */
	private static double computeAutocorrelation(TestStats ts) {
		int count = Math.min(ts.recentCount, TestStats.WINDOW_SIZE);
		if (count < 3) {
			return 0.0;
		}
		int oldest = ts.recentCount - count;
		double mean = 0;
		for (int i = 0; i < count; i++) {
			mean += ts.recentOutcomes[(oldest + i) % TestStats.WINDOW_SIZE];
		}
		mean /= count;
		double num = 0, den = 0;
		for (int i = 0; i < count; i++) {
			double xi = ts.recentOutcomes[(oldest + i) % TestStats.WINDOW_SIZE] - mean;
			den += xi * xi;
			if (i < count - 1) {
				double xi1 = ts.recentOutcomes[(oldest + i + 1) % TestStats.WINDOW_SIZE] - mean;
				num += xi * xi1;
			}
		}
		return den > 0 ? num / den : 0.0;
	}

	/**
	 * Computes linear trend slope of test duration over recent runs. Positive slope
	 * means the test is getting slower over time.
	 */
	private static double computeDurationTrend(TestStats ts) {
		int count = Math.min(ts.durationHistCount, TestStats.WINDOW_SIZE);
		if (count < 3) {
			return 0.0;
		}
		int oldest = ts.durationHistCount - count;
		double meanX = (count - 1) / 2.0;
		double meanY = 0;
		for (int i = 0; i < count; i++) {
			meanY += ts.recentDurations[(oldest + i) % TestStats.WINDOW_SIZE];
		}
		meanY /= count;
		double num = 0, den = 0;
		for (int i = 0; i < count; i++) {
			double y = ts.recentDurations[(oldest + i) % TestStats.WINDOW_SIZE];
			num += (i - meanX) * (y - meanY);
			den += (i - meanX) * (i - meanX);
		}
		return den > 0 ? num / den : 0.0;
	}

	/**
	 * Computes failure rate volatility — the standard deviation of per-sub-window
	 * failure rates. High volatility indicates inconsistent (flaky) test behaviour.
	 */
	private static double computeVolatility(TestStats ts) {
		int count = Math.min(ts.recentCount, TestStats.WINDOW_SIZE);
		int subWindow = 5;
		if (count < subWindow * 2) {
			return 0.0;
		}
		int numWindows = count / subWindow;
		int oldest = ts.recentCount - count;
		double meanRate = 0;
		double[] rates = new double[numWindows];
		for (int w = 0; w < numWindows; w++) {
			int fails = 0;
			for (int i = 0; i < subWindow; i++) {
				fails += ts.recentOutcomes[(oldest + w * subWindow + i) % TestStats.WINDOW_SIZE];
			}
			rates[w] = (double) fails / subWindow;
			meanRate += rates[w];
		}
		meanRate /= numWindows;
		double variance = 0;
		for (double r : rates) {
			variance += (r - meanRate) * (r - meanRate);
		}
		return Math.sqrt(variance / numWindows);
	}

	/**
	 * Extracts the package name from a fully qualified class name.
	 */
	static String extractPackage(String fqcn) {
		int lastDot = fqcn.lastIndexOf('.');
		return lastDot > 0 ? fqcn.substring(0, lastDot) : "";
	}

	/**
	 * Hashes a string to a bucket index in [0, numBuckets).
	 */
	static int hashToBucket(String value, int numBuckets) {
		int h = murmur3Hash32(value);
		return (h & 0x7FFF_FFFF) % numBuckets;
	}

	private static int murmur3Hash32(String key) {
		int h = 0x811c9dc5;
		for (int i = 0; i < key.length(); i++) {
			int k = key.charAt(i);
			k *= 0xcc9e2d51;
			k = Integer.rotateLeft(k, 15);
			k *= 0x1b873593;
			h ^= k;
			h = Integer.rotateLeft(h, 13);
			h = h * 5 + 0xe6546b64;
		}
		h ^= key.length();
		h ^= h >>> 16;
		h *= 0x85ebca6b;
		h ^= h >>> 13;
		h *= 0xc2b2ae35;
		h ^= h >>> 16;
		return h;
	}

	/**
	 * Strips inner/nested class suffixes (everything from the first {@code $}) to
	 * get the top-level enclosing class name.
	 */
	static String toTopLevel(String className) {
		int dollar = className.indexOf('$');
		return dollar > 0 ? className.substring(0, dollar) : className;
	}
}
