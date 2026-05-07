package me.bechberger.testorder.ops;

import java.util.*;

/**
 * Shared utility for detecting typos in testorder.* property keys and suggesting corrections.
 * Used by both the Maven and Gradle plugins to avoid maintaining duplicate Levenshtein
 * implementations and separate known-property sets.
 */
public final class PropertySuggestion {

	private PropertySuggestion() {
	}

	/** All canonical testorder.* property keys accepted by the plugins. */
	public static final Set<String> KNOWN_KEYS = Set.of(
			"testorder.mode", "testorder.skip", "testorder.debug", "testorder.learn", "testorder.verbose",
			"testorder.instrumentation.mode", "testorder.changeMode",
			"testorder.includePackages", "testorder.filterByGroupId",
			"testorder.methodOrder.enabled", "testorder.methodOrder",
			"testorder.weightsFile", "testorder.changed.classes", "testorder.changed.test.classes",
			"testorder.changed.methods", "testorder.changed.classes.file",
			"testorder.index.path", "testorder.state.path",
			"testorder.source.root", "testorder.project.root",
			"testorder.autoLearnRunThreshold", "testorder.autoLearnDiffThreshold",
			"testorder.auto.optimizeEvery", "testorder.autoCompactEvery",
			"testorder.auto.runRemaining", "testorder.auto.active",
			"testorder.select.topN", "testorder.select.randomM", "testorder.select.seed",
			"testorder.select.remainingFile", "testorder.select.selectedFile",
			"testorder.tiered.tier2Fraction", "testorder.tiered.weightByDuration",
			"testorder.tiered.tier1File", "testorder.tiered.tier2File", "testorder.tiered.tier3File",
			"testorder.tiered.currentTier",
			"testorder.showOrder.explain", "testorder.showOrder.fullNames",
			"testorder.showMethodOrder.explain",
			"testorder.score.newTest", "testorder.score.changedTest",
			"testorder.score.maxFailure", "testorder.score.speed",
			"testorder.score.speedPenalty", "testorder.score.depOverlap",
			"testorder.score.changeComplexity", "testorder.score.staticFieldBonus",
			"testorder.score.coverageBonus", "testorder.score.springContextGrouping",
			"testorder.score.ema.varianceThreshold",
			"testorder.dump.output", "testorder.exportJson.output",
			"testorder.dashboard.output", "testorder.dashboard.open",
			"testorder.dashboard.port", "testorder.dashboard.serveSeconds",
			"testorder.dashboard.regenerate", "testorder.dashboard.separateAssets",
			"testorder.metrics.output", "testorder.history.maxRuns",
			"testorder.remaining.file", "testorder.failOnError",
			"testorder.coverage.threshold", "coverage.threshold");

	/**
	 * Find the closest known key to the given unknown key using case-insensitive matching,
	 * suffix matching, and Levenshtein distance (threshold &le; 3).
	 *
	 * @return the closest key, or {@code null} if no good match
	 */
	public static String findClosest(String unknown) {
		String unknownLower = unknown.toLowerCase();

		// Common alias typo: testorder.changed.tests
		if ("testorder.changed.tests".equals(unknownLower)) {
			return "testorder.changed.test.classes";
		}

		// Zero-distance pass: case-only mismatch
		for (String known : KNOWN_KEYS) {
			if (known.toLowerCase().equals(unknownLower)) {
				return known;
			}
		}

		// Suffix match (e.g. testorder.topN → testorder.select.topN)
		if (unknownLower.startsWith("testorder.")) {
			String suffix = unknownLower.substring("testorder.".length());
			String suffixMatch = null;
			for (String known : KNOWN_KEYS) {
				if (known.toLowerCase().endsWith(suffix) && known.length() > unknown.length()) {
					if (suffixMatch == null || known.length() < suffixMatch.length()) {
						suffixMatch = known;
					}
				}
			}
			if (suffixMatch != null) {
				return suffixMatch;
			}

			// Prefix match (e.g. testorder.indx → testorder.index.path)
			String prefixMatch = null;
			for (String known : KNOWN_KEYS) {
				if (known.toLowerCase().startsWith(unknownLower) || unknownLower.startsWith(known.toLowerCase().substring(0, Math.min(known.length(), unknownLower.length())))) {
					// Check if the unknown looks like a truncation/typo of this key
					if (known.toLowerCase().startsWith(unknownLower.substring(0, Math.min(unknownLower.length() - 1, known.length())))) {
						if (prefixMatch == null || known.length() < prefixMatch.length()) {
							prefixMatch = known;
						}
					}
				}
			}
			if (prefixMatch != null) {
				return prefixMatch;
			}
		}

		// Levenshtein distance
		int bestDist = Integer.MAX_VALUE;
		String bestKey = null;
		for (String known : KNOWN_KEYS) {
			int dist = levenshtein(unknownLower, known.toLowerCase());
			if (dist < bestDist) {
				bestDist = dist;
				bestKey = known;
			}
		}
		return (bestDist > 0 && bestDist <= 3) ? bestKey : null;
	}

	/**
	 * Checks a set of property keys for unknown testorder.* entries and returns
	 * user-friendly warning messages with suggestions.
	 *
	 * @param keys the property key names to check
	 * @return list of warning messages (empty if all known)
	 */
	public static List<String> findUnknownKeys(Collection<String> keys) {
		List<String> warnings = new ArrayList<>();
		if (keys == null) return warnings;
		for (String key : keys) {
			// Detect common prefix mistakes: test-order.* or test_order.* instead of testorder.*
			if (key.startsWith("test-order.") || key.startsWith("test_order.")) {
				String corrected = "testorder." + key.substring(key.indexOf('.') + 1);
				String suggestion = KNOWN_KEYS.contains(corrected) ? corrected : findClosest(corrected);
				if (suggestion != null) {
					warnings.add("Unknown property '" + key + "' — did you mean '" + suggestion
							+ "'? (Note: the prefix is 'testorder.' with no hyphen or underscore)");
				} else {
					warnings.add("Unknown property '" + key + "' — the correct prefix is 'testorder.' (no hyphen or underscore).");
				}
				continue;
			}
			if (!key.startsWith("testorder.")) continue;
			if (KNOWN_KEYS.contains(key)) continue;
			// Skip internal reactor-style keys
			if (key.startsWith("testorder.changed.classes.") || key.startsWith("testorder.changed.test.classes."))
				continue;
			String suggestion = findClosest(key);
			if (suggestion != null) {
				warnings.add("Unknown property '" + key + "' — did you mean '" + suggestion + "'?");
			} else {
				warnings.add("Unknown property '" + key + "' — no matching testorder.* property found.");
			}
		}
		return warnings;
	}

	/** Standard Levenshtein edit distance. */
	public static int levenshtein(String a, String b) {
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
