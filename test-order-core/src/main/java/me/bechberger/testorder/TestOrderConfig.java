package me.bechberger.testorder;

/**
 * Shared configuration property names for test-order components.
 * <p>
 * <b>Configuration precedence</b> (highest to lowest):
 * <ol>
 *   <li>JVM system properties ({@code -Dtestorder.xxx=...})</li>
 *   <li>Maven plugin POM {@code <configuration>} parameters</li>
 *   <li>Weights file ({@link #WEIGHTS_FILE})</li>
 *   <li>Persisted state file defaults</li>
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
    public static final String CHANGED_CLASSES = "testorder.changed.classes";
    public static final String CHANGED_CLASSES_FILE = "testorder.changed.classes.file";
    public static final String CHANGED_TEST_CLASSES = "testorder.changed.test.classes";
    public static final String CHANGED_METHODS = "testorder.changed.methods";
    public static final String METHOD_ORDER_ENABLED = "testorder.methodOrder.enabled";
    public static final String STRUCTURAL_DIFF_ENABLED = "testorder.structuralDiff.enabled";
    public static final String CHANGE_COMPLEXITY = "testorder.change.complexity";
    public static final String SPRING_CONTEXT_GROUPING = "testorder.score.springContextGrouping";
    public static final String EMA_VARIANCE_THRESHOLD = "testorder.score.ema.varianceThreshold";
    public static final String HISTORY_MAX_RUNS = "testorder.history.maxRuns";

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
    public static final String SELECT_TOP_N = "testorder.select.topN";
    public static final String SELECT_RANDOM_M = "testorder.select.randomM";
    public static final String SELECT_SEED = "testorder.select.seed";
    public static final String SELECT_REMAINING_FILE = "testorder.select.remainingFile";
    public static final String SELECTED_FILE = "testorder.select.selectedFile";
    public static final String COMBINED_RUN_REMAINING = "testorder.combined.runRemaining";
    public static final String COMBINED_OPTIMIZE_EVERY = "testorder.combined.optimizeEvery";
    public static final String AUTO_LEARN_RUN_THRESHOLD = "testorder.autoLearnRunThreshold";
    public static final String AUTO_LEARN_DIFF_THRESHOLD = "testorder.autoLearnDiffThreshold";
    public static final String DUMP_OUTPUT = "testorder.dump.output";

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
    public static final String LEGACY_INSTRUMENTATION_MODE = "testorder.instrumentationMode";

    private TestOrderConfig() {}
}
