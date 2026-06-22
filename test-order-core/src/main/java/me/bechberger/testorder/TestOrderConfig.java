package me.bechberger.testorder;

/**
 * Shared configuration property names for test-order components.
 * <p>
 * <b>Configuration precedence</b> (highest to lowest):
 * <ol>
 * <li>JVM system properties ({@code -Dtestorder.xxx=...})</li>
 * <li>Maven plugin POM {@code <configuration>} parameters</li>
 * <li>Weights file ({@link #WEIGHTS_FILE})</li>
 * <li>Persisted state file defaults</li>
 * </ol>
 * System properties always override POM settings.
 */
public final class TestOrderConfig {

	// Shared runtime properties (canonical)
	public static final String INDEX_PATH = "testorder.index.path";
	public static final String STATE_PATH = "testorder.state.path";
	public static final String LEARN = "testorder.learn";
	public static final String INSTRUMENTATION_MODE = "testorder.instrumentation.mode";
	public static final String DEBUG = "testorder.debug";
	public static final String CHANGE_MODE = "testorder.changeMode";
	public static final String PROJECT_ROOT = "testorder.project.root";
	public static final String SOURCE_ROOT = "testorder.source.root";
	public static final String WEIGHTS_FILE = "testorder.weights.file";

	// Offline instrumentation properties
	public static final String OFFLINE_MAPPING = "testorder.offline.mapping";
	public static final String OFFLINE_OUTPUT = "testorder.offline.output";
	public static final String OFFLINE_INDEX_FILE = "testorder.offline.indexFile";
	public static final String OFFLINE_BACKUP_DIR = "testorder.offline.backupDir";

	/** Port of the IndexCollectorServer for socket-based dependency collection */
	public static final String COLLECTOR_PORT = "testorder.collector.port";

	/**
	 * Build session identifier shared by all forked JVMs in the same Maven test
	 * run. When set, TelemetryListener writes per-fork partial run records to
	 * {@code .test-order/pending-runs/} instead of the state file directly, and the
	 * Maven plugin merges them into a single per-build RunRecord after all forks
	 * complete.
	 */
	public static final String BUILD_ID = "testorder.build.id";

	/**
	 * Directory (relative to .test-order/) where per-fork partial run records are
	 * staged.
	 */
	public static final String PENDING_RUNS_DIR = "testorder.pending.runs.dir";
	public static final String CHANGED_CLASSES = "testorder.changed.classes";
	public static final String CHANGED_CLASSES_FILE = "testorder.changed.classes.file";
	public static final String CHANGED_TEST_CLASSES = "testorder.changed.test.classes";
	public static final String CHANGED_METHODS = "testorder.changed.methods";
	public static final String METHOD_ORDER_ENABLED = "testorder.methodOrder.enabled";
	public static final String STRUCTURAL_DIFF_ENABLED = "testorder.structuralDiff.enabled";
	public static final String CHANGE_COMPLEXITY = "testorder.change.complexity";
	public static final String STATIC_ANALYSIS_ENABLED = "testorder.staticAnalysis.enabled";
	public static final String STATIC_ANALYSIS_DEPTH = "testorder.staticAnalysis.depth";
	public static final String SPRING_CONTEXT_GROUPING = "testorder.score.springContextGrouping";
	public static final String EMA_VARIANCE_THRESHOLD = "testorder.score.ema.varianceThreshold";
	public static final String HISTORY_MAX_RUNS = "testorder.history.maxRuns";

	/**
	 * When set to {@code "true"}, new tests that pass without having failed first
	 * are artificially failed (TDD enforcement).
	 */
	public static final String TDD = "testorder.tdd";

	// Scoring override keys
	public static final String SCORE_NEW_TEST = "testorder.score.newTest";
	public static final String SCORE_CHANGED_TEST = "testorder.score.changedTest";
	public static final String SCORE_MAX_FAILURE = "testorder.score.maxFailure";
	public static final String SCORE_SPEED = "testorder.score.speed";
	public static final String SCORE_SPEED_PENALTY = "testorder.score.speedPenalty";
	public static final String SCORE_DEP_OVERLAP = "testorder.score.depOverlap";
	public static final String SCORE_CHANGE_COMPLEXITY = "testorder.score.changeComplexity";
	public static final String SCORE_STATIC_FIELD_BONUS = "testorder.score.staticFieldBonus";
	public static final String SCORE_COVERAGE_BONUS = "testorder.score.coverageBonus";
	public static final String SCORE_KILL_RATE_BONUS = "testorder.score.killRateBonus";
	public static final String SCORE_PACKAGE_PROXIMITY_BONUS = "testorder.score.packageProximityBonus";

	// Method-order scoring override keys
	public static final String METHOD_SCORE_FAILURE_RECENCY = "testorder.method.score.failureRecency";
	public static final String METHOD_SCORE_FAST = "testorder.method.score.fast";
	public static final String METHOD_SCORE_SLOW = "testorder.method.score.slow";
	public static final String METHOD_SCORE_DEP_OVERLAP = "testorder.method.score.depOverlap";
	public static final String METHOD_SCORE_NEW_METHOD = "testorder.method.score.newMethod";
	public static final String METHOD_SCORE_CHANGED_METHOD = "testorder.method.score.changedMethod";
	public static final String METHOD_SCORE_COVERAGE_BONUS = "testorder.method.score.coverageBonus";

	// Maven plugin keys
	public static final String MODE = "testorder.mode";
	public static final String INCLUDE_PACKAGES = "testorder.includePackages";
	public static final String FILTER_BY_GROUP_ID = "testorder.filterByGroupId";
	public static final String SELECT_TOP_N = "testorder.affected.topN";
	public static final String SELECT_RANDOM_M = "testorder.affected.randomM";
	public static final String SELECT_SEED = "testorder.affected.seed";
	public static final String SELECT_REMAINING_FILE = "testorder.affected.remainingFile";
	public static final String SELECTED_FILE = "testorder.affected.selectedFile";
	public static final String AUTO_LEARN_RUN_THRESHOLD = "testorder.autoLearnRunThreshold";
	public static final String AUTO_LEARN_DIFF_THRESHOLD = "testorder.autoLearnDiffThreshold";
	public static final String DUMP_OUTPUT = "testorder.dump.output";

	// Tiered CI selection keys
	public static final String TIERED_TIER2_FRACTION = "testorder.tiered.tier2Fraction";
	public static final String TIERED_WEIGHT_BY_DURATION = "testorder.tiered.weightByDuration";
	public static final String TIERED_TIER1_FILE = "testorder.tiered.tier1File";
	public static final String TIERED_TIER2_FILE = "testorder.tiered.tier2File";
	public static final String TIERED_TIER3_FILE = "testorder.tiered.tier3File";
	public static final String TIERED_CURRENT_TIER = "testorder.tiered.currentTier";
	/**
	 * Shard tier 3 (and only tier 3) across N parallel runners. Format: {@code k/N}
	 * where k is 1-based (e.g. {@code 1/3}, {@code 2/3}, {@code 3/3}). When set,
	 * {@code run-tier} and {@code run-tiered} only execute the k-th slice of tier 3
	 * tests.
	 */
	public static final String TIERED_SHARD = "testorder.tiered.shard";

	// ML predictive test prioritization (opt-in)
	public static final String ML_ENABLED = "testorder.ml.enabled";
	public static final String ML_HISTORY_DIR = "testorder.ml.historyDir";
	public static final String ML_HISTORY_MAX_RUNS = "testorder.ml.history.maxRuns";
	public static final String ML_PREDICTIONS_FILE = "testorder.ml.predictions.file";

	// Flaky-test handling (opt-in). Reads the ML health report to identify FLAKY
	// tests.
	/**
	 * Max retries per FLAKY-classified test method ({@code 0} disables retries).
	 */
	public static final String FLAKY_RETRIES = "testorder.flaky.retries";
	/**
	 * Path to the ML health report consumed by the runtime retry/quarantine
	 * extension.
	 */
	public static final String FLAKY_REPORT_PATH = "testorder.flaky.report.path";
	/**
	 * When {@code true}, final failures of FLAKY-classified tests are reported as
	 * aborted (via {@code TestAbortedException}) rather than failed, so they don't
	 * break the build.
	 */
	public static final String FLAKY_QUARANTINE = "testorder.flaky.quarantine";

	// Skip-if-unchanged caching (opt-in).
	/** When {@code true}, eligible tests are omitted from the run entirely. */
	public static final String CACHE_SKIP_UNCHANGED = "testorder.cache.skipUnchanged";
	/** Minimum consecutive passing runs before a test becomes cache-eligible. */
	public static final String CACHE_MIN_PASS_STREAK = "testorder.cache.minPassStreak";
	/**
	 * Safety cap: never skip more than this fraction of the suite, even if
	 * eligible.
	 */
	public static final String CACHE_MAX_SKIP_FRACTION = "testorder.cache.maxSkipFraction";

	// Legacy aliases kept for backward compatibility in Maven user properties
	public static final String LEGACY_INDEX = "testorder.index";
	public static final String LEGACY_STATE_FILE = "testorder.stateFile";
	public static final String LEGACY_DEPS_DIR = "testorder.depsDir";
	public static final String LEGACY_HASH_FILE = "testorder.hashFile";
	public static final String LEGACY_TEST_HASH_FILE = "testorder.testHashFile";
	public static final String LEGACY_METHOD_HASH_FILE = "testorder.methodHashFile";
	public static final String LEGACY_SOURCE_ROOT = "testorder.sourceRoot";
	public static final String LEGACY_TEST_SOURCE_ROOT = "testorder.testSourceRoot";
	public static final String LEGACY_VERBOSE_FILE = "testorder.verboseFile";
	public static final String LEGACY_METHOD_ORDERING_ENABLED = "testorder.methodOrderingEnabled";

	private TestOrderConfig() {
	}
}
