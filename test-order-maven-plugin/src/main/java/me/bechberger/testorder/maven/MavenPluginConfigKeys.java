package me.bechberger.testorder.maven;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Centralized Maven plugin configuration keys.
 */
final class MavenPluginConfigKeys {

	private MavenPluginConfigKeys() {
	}

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
	static final String AUTO_RUN_REMAINING = "testorder.auto.runRemaining";
	static final String AUTO_OPTIMIZE_EVERY = "testorder.auto.optimizeEvery";
	static final String INSTRUMENTATION = "testorder.instrumentation"; // online | offline
	static final String COMPRESSION = "testorder.compression"; // fast | hc
	static final String SHOW_ORDER_FULL_NAMES = "testorder.showOrder.fullNames";
	static final String AUTO_LEARN_RUN_THRESHOLD = "testorder.autoLearnRunThreshold";
	static final String AUTO_LEARN_DIFF_THRESHOLD = "testorder.autoLearnDiffThreshold";
	static final String AUTO_COMPACT_EVERY = "testorder.autoCompactEvery";
	static final String DUMP_OUTPUT = "testorder.dump.output";
	static final String EXPORT_JSON_OUTPUT = "testorder.exportJson.output";

	// Tiered CI selection keys
	static final String TIERED_TIER2_FRACTION = "testorder.tiered.tier2Fraction";
	static final String TIERED_WEIGHT_BY_DURATION = "testorder.tiered.weightByDuration";
	static final String TIERED_TIER1_FILE = "testorder.tiered.tier1File";
	static final String TIERED_TIER2_FILE = "testorder.tiered.tier2File";
	static final String TIERED_TIER3_FILE = "testorder.tiered.tier3File";
	static final String TIERED_CURRENT_TIER = "testorder.tiered.currentTier";
	static final String SHOW_ORDER_EXPLAIN = "testorder.showOrder.explain";
	static final String SHOW_METHOD_ORDER_EXPLAIN = "testorder.showMethodOrder.explain";

	// Dashboard goal keys
	static final String DASHBOARD_OUTPUT = "testorder.dashboard.output";
	static final String DASHBOARD_COVERAGE_DIR = "testorder.dashboard.coverageDir";
	static final String DASHBOARD_OPEN = "testorder.dashboard.open";
	static final String DASHBOARD_SEPARATE_ASSETS = "testorder.dashboard.separateAssets";
	static final String DASHBOARD_PORT = "testorder.dashboard.port";
	static final String DASHBOARD_REGENERATE = "testorder.dashboard.regenerate";
	static final String DASHBOARD_SERVE_SECONDS = "testorder.dashboard.serveSeconds";

	/**
	 * Alias for {@link #DASHBOARD_PORT} — users often try
	 * {@code testorder.serve.port}.
	 */
	static final String SERVE_PORT_ALIAS = "testorder.serve.port";

	// Show goal keys (inline in ShowMojo, listed here for unknown-property
	// detection)
	static final String SHOW_CLASSES = "testorder.show.classes";
	static final String SHOW_METHODS = "testorder.show.methods";
	static final String SHOW_ML = "testorder.show.ml";
	static final String SHOW_ALL = "testorder.show.all";
	static final String SHOW_FORMAT = "testorder.show.format";
	static final String SHOW_FILTER = "testorder.show.filter";

	// Detect-dependencies goal keys
	static final String DETECT_ALGORITHM = "testorder.detect.algorithm";
	static final String DETECT_TIME_BUDGET = "testorder.detect.timeBudget";
	static final String DETECT_STOP_ON_FIRST = "testorder.detect.stopOnFirst";
	static final String DETECT_SEED = "testorder.detect.seed";
	static final String DETECT_FAIL_ON_DETECTION = "testorder.detect.failOnDetection";

	// Mutation analysis goal keys
	static final String MUTATIONS_OUTPUT_FILE = "testorder.mutations.outputFile";
	static final String MUTATIONS_TIME_BUDGET = "testorder.mutations.timeBudget";
	static final String MUTATIONS_TARGET_CLASSES = "testorder.mutations.targetClasses";

	// Legacy aliases kept for backward compatibility in Maven user properties
	static final String LEGACY_INDEX = "testorder.index";
	static final String LEGACY_STATE_FILE = "testorder.stateFile";
	static final String LEGACY_DEPS_DIR = "testorder.depsDir";
	static final String LEGACY_HASH_FILE = "testorder.hashFile";
	static final String LEGACY_TEST_HASH_FILE = "testorder.testHashFile";
	static final String LEGACY_METHOD_HASH_FILE = "testorder.methodHashFile";
	static final String BYTECODE_HASH_FILE = "testorder.bytecodeHashFile";
	static final String BYTECODE_CHANGE_DETECTION_ENABLED = "testorder.bytecodeChangeDetection.enabled";
	static final String BYTECODE_AUGMENT_DEPENDENCY_MAP_ENABLED = "testorder.bytecodeAugmentDependencyMap.enabled";
	static final String LEGACY_SOURCE_ROOT = "testorder.sourceRoot";
	static final String LEGACY_TEST_SOURCE_ROOT = "testorder.testSourceRoot";
	static final String LEGACY_VERBOSE_FILE = "testorder.verboseFile";
	static final String LEGACY_METHOD_ORDERING_ENABLED = "testorder.methodOrderingEnabled";

	/**
	 * CamelCase aliases that users commonly guess. Each alias maps to its canonical
	 * key. When a user passes an alias, {@link #findUnknownProperties} accepts it
	 * silently (info-level message) instead of emitting an "unknown property"
	 * warning.
	 */
	static final Map<String, String> ALIASES = Map.of(
			// testorder.changedClasses → testorder.changed.classes
			"testorder.changedClasses", CHANGED_CLASSES,
			// testorder.showOrder.format → testorder.show.format
			"testorder.showOrder.format", SHOW_FORMAT,
			// testorder.showOrder.topN → testorder.select.topN
			"testorder.showOrder.topN", SELECT_TOP_N,
			// testorder.tier → testorder.tiered.currentTier (shorthand for run-tier)
			"testorder.tier", TIERED_CURRENT_TIER);

	/** All known testorder.* property keys (canonical + legacy + aliases). */
	static final Set<String> ALL_KNOWN_KEYS = Set.of(INDEX_PATH, STATE_PATH, LEARN, INSTRUMENTATION_MODE, CHANGE_MODE,
			PROJECT_ROOT, SOURCE_ROOT, WEIGHTS_FILE, CHANGED_CLASSES, CHANGED_TEST_CLASSES, CHANGED_METHODS,
			METHOD_ORDER_ENABLED, STRUCTURAL_DIFF_ENABLED, SCORE_NEW_TEST, SCORE_CHANGED_TEST, SCORE_MAX_FAILURE,
			SCORE_SPEED, SCORE_SPEED_PENALTY, SCORE_DEP_OVERLAP, SCORE_CHANGE_COMPLEXITY, SCORE_STATIC_FIELD_BONUS,
			SCORE_COVERAGE_BONUS, MODE, INCLUDE_PACKAGES, FILTER_BY_GROUP_ID, SELECT_TOP_N, SELECT_RANDOM_M,
			SELECT_SEED, SELECT_REMAINING_FILE, SELECTED_FILE, AUTO_RUN_REMAINING, AUTO_OPTIMIZE_EVERY,
			AUTO_LEARN_RUN_THRESHOLD, AUTO_LEARN_DIFF_THRESHOLD, DUMP_OUTPUT, EXPORT_JSON_OUTPUT, SHOW_ORDER_EXPLAIN,
			SHOW_METHOD_ORDER_EXPLAIN, SHOW_ORDER_FULL_NAMES, COMPRESSION, DASHBOARD_OUTPUT, DASHBOARD_COVERAGE_DIR,
			DASHBOARD_OPEN, DASHBOARD_SEPARATE_ASSETS, DASHBOARD_PORT, DASHBOARD_REGENERATE, DASHBOARD_SERVE_SECONDS,
			DETECT_ALGORITHM, DETECT_TIME_BUDGET, DETECT_STOP_ON_FIRST, DETECT_SEED, DETECT_FAIL_ON_DETECTION,
			TIERED_TIER2_FRACTION, TIERED_WEIGHT_BY_DURATION, TIERED_TIER1_FILE, TIERED_TIER2_FILE, TIERED_TIER3_FILE,
			TIERED_CURRENT_TIER, LEGACY_INDEX, LEGACY_STATE_FILE, LEGACY_DEPS_DIR, LEGACY_HASH_FILE,
			LEGACY_TEST_HASH_FILE, LEGACY_METHOD_HASH_FILE, BYTECODE_HASH_FILE, BYTECODE_CHANGE_DETECTION_ENABLED,
			BYTECODE_AUGMENT_DEPENDENCY_MAP_ENABLED, LEGACY_SOURCE_ROOT, LEGACY_TEST_SOURCE_ROOT, LEGACY_VERBOSE_FILE,
			LEGACY_METHOD_ORDERING_ENABLED, AUTO_COMPACT_EVERY, "testorder.skip", "testorder.debug",
			"testorder.history.maxRuns", "testorder.changed.classes.file", "testorder.score.springContextGrouping",
			"testorder.auto.active", "testorder.remaining.file", "testorder.metrics.output", SERVE_PORT_ALIAS,
			MUTATIONS_OUTPUT_FILE, MUTATIONS_TIME_BUDGET, MUTATIONS_TARGET_CLASSES,
			// Show goal keys (inline in ShowMojo)
			SHOW_CLASSES, SHOW_METHODS, SHOW_ML, SHOW_ALL, SHOW_FORMAT, SHOW_FILTER,
			// CamelCase aliases (silently accepted, see ALIASES map)
			"testorder.changedClasses", "testorder.showOrder.format", "testorder.showOrder.topN",
			// Shorthand alias for run-tier (see ALIASES map)
			"testorder.tier",
			// Download goal keys
			"testorder.download.fallbackToLearn",
			// Static analysis goal keys
			"testorder.staticAnalysis.enabled", "testorder.staticAnalysis.depth",
			"testorder.showStaticAnalysis.verbose",
			// Reactor lifecycle participant keys
			"testorder.reactorReorder", "testorder.reactorTopN", "testorder.reactorReorder.dryRun",
			// Offline instrumentation runtime keys (passed to surefire JVM)
			"testorder.offline.includePackages", "testorder.offline.instrMode", "testorder.offline.pending",
			// Agent runtime / multi-module coordination keys
			"testorder.moduleId", "testorder.internal.buildId",
			// Auto-mode runtime coordination keys (written to testorder-config.properties)
			"testorder.build.id", "testorder.pending.runs.dir", "testorder.changeDetection.logged");

	/**
	 * Find the closest known key to the given unknown key. Delegates to
	 * {@link me.bechberger.testorder.ops.PropertySuggestion} with a fallback to
	 * Maven-specific legacy keys.
	 */
	static String findClosestKey(String unknown) {
		// Check shared core keys first
		String coreMatch = me.bechberger.testorder.ops.PropertySuggestion.findClosest(unknown);
		if (coreMatch != null)
			return coreMatch;
		// Fall back to Maven-specific legacy keys via Levenshtein
		String unknownLower = unknown.toLowerCase();
		int bestDist = Integer.MAX_VALUE;
		String bestKey = null;
		for (String known : ALL_KNOWN_KEYS) {
			if (me.bechberger.testorder.ops.PropertySuggestion.KNOWN_KEYS.contains(known))
				continue;
			int dist = me.bechberger.testorder.ops.PropertySuggestion.levenshtein(unknownLower, known.toLowerCase());
			if (dist < bestDist) {
				bestDist = dist;
				bestKey = known;
			}
		}
		return (bestDist > 0 && bestDist <= 3) ? bestKey : null;
	}

	/**
	 * Find all unknown testorder.* user properties and return suggestions.
	 */
	static List<String> findUnknownProperties(java.util.Properties userProperties) {
		List<String> warnings = new ArrayList<>();
		if (userProperties == null)
			return warnings;
		for (String key : userProperties.stringPropertyNames()) {
			if (!key.startsWith("testorder."))
				continue;
			if (ALL_KNOWN_KEYS.contains(key))
				continue;
			if (me.bechberger.testorder.ops.PropertySuggestion.KNOWN_KEYS.contains(key))
				continue;
			// Skip internal reactor propagation keys and session-coordination keys
			if (key.startsWith(CHANGED_CLASSES + ".") || key.startsWith(CHANGED_TEST_CLASSES + ".")
					|| key.equals("testorder.pendingRestores") || key.equals("testorder.activeCollectors"))
				continue;
			String suggestion = findClosestKey(key);
			if (suggestion != null) {
				warnings.add("Unknown property '" + key + "' — did you mean '" + suggestion + "'?");
			} else {
				warnings.add("Unknown property '" + key + "' — no matching testorder.* property found.");
			}
		}
		return warnings;
	}

	/**
	 * Find all testorder.* user properties that are camelCase aliases and return
	 * info-level messages pointing to the canonical name. Aliases are accepted
	 * silently (no warning); this method exists so callers can optionally log at
	 * INFO level.
	 */
	static List<String> findAliasedProperties(java.util.Properties userProperties) {
		List<String> infos = new ArrayList<>();
		if (userProperties == null)
			return infos;
		for (String key : userProperties.stringPropertyNames()) {
			String canonical = ALIASES.get(key);
			if (canonical != null) {
				infos.add("Property '" + key + "' is a camelCase alias — using canonical name '" + canonical + "'.");
			}
		}
		return infos;
	}
}
