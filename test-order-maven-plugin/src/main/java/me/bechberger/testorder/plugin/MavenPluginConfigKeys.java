package me.bechberger.testorder.plugin;

/**
 * Centralized Maven plugin configuration keys.
 */
final class MavenPluginConfigKeys {

    private MavenPluginConfigKeys() {}

    // Canonical shared runtime keys
    static final String INDEX_PATH = "testorder.index.path";
    static final String STATE_PATH = "testorder.state.path";
    static final String LEARN = "testorder.learn";
    static final String INSTRUMENTATION_MODE = "testorder.instrumentation.mode";
    static final String CHANGE_MODE = "testorder.changeMode";
    static final String PROJECT_ROOT = "testorder.project.root";
    static final String SOURCE_ROOT = "testorder.source.root";
    static final String WEIGHTS_FILE = "testorder.weights.file";
    static final String CHANGED_CLASSES = "testorder.changed.classes";
    static final String CHANGED_TEST_CLASSES = "testorder.changed.test.classes";
    static final String CHANGED_METHODS = "testorder.changed.methods";
    static final String METHOD_ORDER_ENABLED = "testorder.methodOrder.enabled";
    static final String STRUCTURAL_DIFF_ENABLED = "testorder.structuralDiff.enabled";

    // Score overrides
    static final String SCORE_NEW_TEST = "testorder.score.newTest";
    static final String SCORE_CHANGED_TEST = "testorder.score.changedTest";
    static final String SCORE_MAX_FAILURE = "testorder.score.maxFailure";
    static final String SCORE_SPEED = "testorder.score.speed";
    static final String SCORE_SPEED_PENALTY = "testorder.score.speedPenalty";
    static final String SCORE_DEP_OVERLAP = "testorder.score.depOverlap";
    static final String SCORE_CHANGE_COMPLEXITY = "testorder.score.changeComplexity";
    static final String SCORE_STATIC_FIELD_BONUS = "testorder.score.staticFieldBonus";
    static final String SCORE_COVERAGE_BONUS = "testorder.score.coverageBonus";

    // Maven-plugin specific keys
    static final String MODE = "testorder.mode";
    static final String INCLUDE_PACKAGES = "testorder.includePackages";
    static final String FILTER_BY_GROUP_ID = "testorder.filterByGroupId";
    static final String SELECT_TOP_N = "testorder.select.topN";
    static final String SELECT_RANDOM_M = "testorder.select.randomM";
    static final String SELECT_SEED = "testorder.select.seed";
    static final String SELECT_REMAINING_FILE = "testorder.select.remainingFile";
    static final String SELECTED_FILE = "testorder.select.selectedFile";
    static final String COMBINED_RUN_REMAINING = "testorder.combined.runRemaining";
    static final String COMBINED_OPTIMIZE_EVERY = "testorder.combined.optimizeEvery";
    static final String AUTO_LEARN_RUN_THRESHOLD = "testorder.autoLearnRunThreshold";
    static final String AUTO_LEARN_DIFF_THRESHOLD = "testorder.autoLearnDiffThreshold";
    static final String DUMP_OUTPUT = "testorder.dump.output";

    // Dashboard goal keys
    static final String DASHBOARD_OUTPUT = "testorder.dashboard.output";
    static final String DASHBOARD_COVERAGE_DIR = "testorder.dashboard.coverageDir";
    static final String DASHBOARD_OPEN = "testorder.dashboard.open";
    static final String DASHBOARD_SEPARATE_ASSETS = "testorder.dashboard.separateAssets";

    // Legacy aliases kept for backward compatibility in Maven user properties
    static final String LEGACY_INDEX = "testorder.index";
    static final String LEGACY_STATE_FILE = "testorder.stateFile";
    static final String LEGACY_DEPS_DIR = "testorder.depsDir";
    static final String LEGACY_HASH_FILE = "testorder.hashFile";
    static final String LEGACY_TEST_HASH_FILE = "testorder.testHashFile";
    static final String LEGACY_METHOD_HASH_FILE = "testorder.methodHashFile";
    static final String LEGACY_SOURCE_ROOT = "testorder.sourceRoot";
    static final String LEGACY_TEST_SOURCE_ROOT = "testorder.testSourceRoot";
    static final String LEGACY_VERBOSE_FILE = "testorder.verboseFile";
    static final String LEGACY_METHOD_ORDERING_ENABLED = "testorder.methodOrderingEnabled";
    static final String LEGACY_INSTRUMENTATION_MODE = "testorder.instrumentationMode";
}
