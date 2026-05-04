package me.bechberger.testorder.plugin;

import java.util.ArrayList;
import java.util.List;
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
	static final String SHOW_ORDER_FULL_NAMES = "testorder.showOrder.fullNames";
	static final String AUTO_LEARN_RUN_THRESHOLD = "testorder.autoLearnRunThreshold";
	static final String AUTO_LEARN_DIFF_THRESHOLD = "testorder.autoLearnDiffThreshold";
	static final String DUMP_OUTPUT = "testorder.dump.output";
	static final String EXPORT_JSON_OUTPUT = "testorder.exportJson.output";
	static final String SHOW_ORDER_EXPLAIN = "testorder.showOrder.explain";

	// Dashboard goal keys
	static final String DASHBOARD_OUTPUT = "testorder.dashboard.output";
	static final String DASHBOARD_COVERAGE_DIR = "testorder.dashboard.coverageDir";
	static final String DASHBOARD_OPEN = "testorder.dashboard.open";
	static final String DASHBOARD_SEPARATE_ASSETS = "testorder.dashboard.separateAssets";
	static final String DASHBOARD_PORT = "testorder.dashboard.port";
	static final String DASHBOARD_REGENERATE = "testorder.dashboard.regenerate";

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

	/** All known testorder.* property keys (canonical + legacy). */
	static final Set<String> ALL_KNOWN_KEYS = Set.of(INDEX_PATH, STATE_PATH, LEARN, INSTRUMENTATION_MODE, CHANGE_MODE,
			PROJECT_ROOT, SOURCE_ROOT, WEIGHTS_FILE, CHANGED_CLASSES, CHANGED_TEST_CLASSES, CHANGED_METHODS,
			METHOD_ORDER_ENABLED, STRUCTURAL_DIFF_ENABLED, SCORE_NEW_TEST, SCORE_CHANGED_TEST, SCORE_MAX_FAILURE,
			SCORE_SPEED, SCORE_SPEED_PENALTY, SCORE_DEP_OVERLAP, SCORE_CHANGE_COMPLEXITY, SCORE_STATIC_FIELD_BONUS,
			SCORE_COVERAGE_BONUS, MODE, INCLUDE_PACKAGES, FILTER_BY_GROUP_ID, SELECT_TOP_N, SELECT_RANDOM_M,
			SELECT_SEED, SELECT_REMAINING_FILE, SELECTED_FILE, AUTO_RUN_REMAINING, AUTO_OPTIMIZE_EVERY,
			AUTO_LEARN_RUN_THRESHOLD, AUTO_LEARN_DIFF_THRESHOLD,
			DUMP_OUTPUT, EXPORT_JSON_OUTPUT, SHOW_ORDER_EXPLAIN, SHOW_ORDER_FULL_NAMES, DASHBOARD_OUTPUT, DASHBOARD_COVERAGE_DIR,
			DASHBOARD_OPEN, DASHBOARD_SEPARATE_ASSETS, DASHBOARD_PORT, DASHBOARD_REGENERATE, LEGACY_INDEX,
			LEGACY_STATE_FILE, LEGACY_DEPS_DIR, LEGACY_HASH_FILE, LEGACY_TEST_HASH_FILE, LEGACY_METHOD_HASH_FILE,
			LEGACY_SOURCE_ROOT, LEGACY_TEST_SOURCE_ROOT, LEGACY_VERBOSE_FILE, LEGACY_METHOD_ORDERING_ENABLED,
			"testorder.skip", "testorder.debug", "testorder.history.maxRuns", "testorder.changed.classes.file",
			"testorder.methodOrder", "testorder.score.springContextGrouping", "testorder.score.ema.varianceThreshold",
			"testorder.auto.active", "testorder.remaining.file");

	/**
	 * Find the closest known key to the given unknown key using Levenshtein
	 * distance. Returns null if no key is within the threshold.
	 */
	static String findClosestKey(String unknown) {
		int bestDist = Integer.MAX_VALUE;
		String bestKey = null;
		for (String known : ALL_KNOWN_KEYS) {
			int dist = levenshtein(unknown.toLowerCase(), known.toLowerCase());
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
			String suggestion = findClosestKey(key);
			if (suggestion != null) {
				warnings.add("Unknown property '" + key + "' — did you mean '" + suggestion + "'?");
			} else {
				warnings.add("Unknown property '" + key + "' — no matching testorder.* property found.");
			}
		}
		return warnings;
	}

	/** Standard Levenshtein distance. */
	static int levenshtein(String a, String b) {
		int n = a.length(), m = b.length();
		int[] prev = new int[m + 1];
		int[] curr = new int[m + 1];
		for (int j = 0; j <= m; j++)
			prev[j] = j;
		for (int i = 1; i <= n; i++) {
			curr[0] = i;
			for (int j = 1; j <= m; j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
			}
			int[] tmp = prev;
			prev = curr;
			curr = tmp;
		}
		return prev[m];
	}
}
