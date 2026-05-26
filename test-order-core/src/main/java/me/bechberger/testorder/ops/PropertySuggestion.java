package me.bechberger.testorder.ops;

import java.util.*;

/**
 * Shared utility for detecting typos in testorder.* property keys and
 * suggesting corrections. Used by both the Maven and Gradle plugins to avoid
 * maintaining duplicate Levenshtein implementations and separate known-property
 * sets.
 */
public final class PropertySuggestion {

	private PropertySuggestion() {
	}

	/** All canonical testorder.* property keys accepted by the plugins. */
	public static final Set<String> KNOWN_KEYS = Set.of("testorder.mode", "testorder.skip", "testorder.debug",
			"testorder.learn", "testorder.instrumentation.mode", "testorder.changeMode", "testorder.includePackages",
			"testorder.filterByGroupId", "testorder.methodOrder.enabled", "testorder.weights.file",
			"testorder.changed.classes", "testorder.changed.test.classes", "testorder.changed.methods",
			"testorder.changed.classes.file", "testorder.index.path", "testorder.state.path", "testorder.source.root",
			"testorder.project.root", "testorder.autoLearnRunThreshold", "testorder.autoLearnDiffThreshold",
			"testorder.auto.optimizeEvery", "testorder.autoCompactEvery", "testorder.auto.runRemaining",
			"testorder.auto.active", "testorder.select.topN", "testorder.select.randomM", "testorder.select.seed",
			"testorder.select.remainingFile", "testorder.select.selectedFile", "testorder.tiered.tier2Fraction",
			"testorder.tiered.weightByDuration", "testorder.tiered.tier1File", "testorder.tiered.tier2File",
			"testorder.tiered.tier3File", "testorder.tiered.currentTier", "testorder.showOrder.explain",
			"testorder.showOrder.fullNames", "testorder.showMethodOrder.explain", "testorder.score.newTest",
			"testorder.score.changedTest", "testorder.score.maxFailure", "testorder.score.speed",
			"testorder.score.speedPenalty", "testorder.score.depOverlap", "testorder.score.changeComplexity",
			"testorder.score.staticFieldBonus", "testorder.score.coverageBonus",
			"testorder.score.springContextGrouping", "testorder.dump.output", "testorder.exportJson.output",
			"testorder.dashboard.output", "testorder.dashboard.open", "testorder.dashboard.port",
			"testorder.serve.port", "testorder.dashboard.serveSeconds", "testorder.dashboard.regenerate",
			"testorder.dashboard.separateAssets", "testorder.metrics.output", "testorder.history.maxRuns",
			"testorder.remaining.file", "testorder.failOnError", "testorder.coverage.threshold",
			"testorder.coverage.outputDir", "testorder.coverage.failOnViolation", "coverage.threshold",
			"coverage.outputDir", "testorder.git.timeout.seconds", "testorder.lock.stale.minutes",
			"testorder.change.complexity", "testorder.method.score.failureRecency", "testorder.method.score.fast",
			"testorder.method.score.slow", "testorder.method.score.depOverlap", "testorder.method.score.newMethod",
			"testorder.method.score.changedMethod", "testorder.method.score.coverageBonus", "testorder.tdd",
			"testorder.ml.enabled", "testorder.ml.predictions.file", "testorder.instrumentation",
			"testorder.offline.mapping", "testorder.offline.output", "testorder.offline.indexFile",
			"testorder.offline.backupDir", "testorder.collector.port",
			// File path aliases and additional keys
			"testorder.index", "testorder.stateFile", "testorder.sourceRoot", "testorder.hashFile",
			"testorder.testHashFile", "testorder.methodHashFile", "testorder.testSourceRoot", "testorder.depsDir",
			"testorder.methodOrderingEnabled", "testorder.structuralDiff.enabled", "testorder.compression",
			"testorder.verboseFile", "testorder.dashboard.coverageDir",
			// detect-dependencies keys
			"testorder.detect.algorithm", "testorder.detect.timeBudget", "testorder.detect.stopOnFirst",
			"testorder.detect.seed", "testorder.detect.failOnDetection",
			// show goal keys
			"testorder.show.classes", "testorder.show.methods", "testorder.show.ml", "testorder.show.all",
			"testorder.show.format", "testorder.show.filter",
			// reactor ordering keys
			"testorder.reactor.suggest", "testorder.reactor.topN");

	/**
	 * Find the closest known key to the given unknown key using case-insensitive
	 * matching, suffix matching, and Levenshtein distance (threshold &le; 3).
	 *
	 * @return the closest key, or {@code null} if no good match
	 */
	public static String findClosest(String unknown) {
		if (unknown == null || unknown.isBlank())
			return null;
		String unknownLower = unknown.toLowerCase();

		// Common alias typo: testorder.changed.tests
		if ("testorder.changed.tests".equals(unknownLower)) {
			return "testorder.changed.test.classes";
		}

		// Common alias: testorder.select.maxTests / testorder.select.limit →
		// testorder.select.topN
		if (unknownLower.endsWith(".maxtests") || unknownLower.endsWith(".limit") || unknownLower.endsWith(".max")
				|| unknownLower.endsWith(".count")) {
			return "testorder.select.topN";
		}

		// Common alias: testorder.showOrder.format → testorder.show.format
		if ("testorder.showorder.format".equals(unknownLower)) {
			return "testorder.show.format";
		}

		// Common alias: testorder.serve.* → testorder.dashboard.*
		if ("testorder.serve.port".equals(unknownLower)) {
			return "testorder.dashboard.port";
		}
		if ("testorder.serve.serveseconds".equals(unknownLower)) {
			return "testorder.dashboard.serveSeconds";
		}

		// Dot-normalized match: handle camelCase vs. dotted variants (e.g.
		// testorder.changeMode vs testorder.change.mode)
		for (String known : KNOWN_KEYS) {
			String knownNorm = known.toLowerCase().replace(".", "");
			String unknownNorm = unknownLower.replace(".", "");
			if (knownNorm.equals(unknownNorm) || levenshtein(knownNorm, unknownNorm) <= 1) {
				return known;
			}
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

			// Prefix match: find keys whose suffix (after testorder.) has
			// a low Levenshtein distance to the unknown's suffix. This catches
			// typos like testorder.indx → testorder.index.path (R8-5)
			String bestPrefixMatch = null;
			int bestPrefixDist = Integer.MAX_VALUE;
			for (String known : KNOWN_KEYS) {
				if (!known.startsWith("testorder."))
					continue;
				String knownSuffix = known.substring("testorder.".length());
				// Check if the unknown suffix is a prefix of the known suffix (truncation)
				if (knownSuffix.startsWith(suffix)
						|| knownSuffix.replace(".", "").startsWith(suffix.replace(".", ""))) {
					return known;
				}
				// Check per-segment: first segment match
				String unknownFirstSeg = suffix.contains(".") ? suffix.substring(0, suffix.indexOf('.')) : suffix;
				String knownFirstSeg = knownSuffix.contains(".")
						? knownSuffix.substring(0, knownSuffix.indexOf('.'))
						: knownSuffix;
				int segDist = levenshtein(unknownFirstSeg, knownFirstSeg);
				if (segDist <= 2 && segDist < bestPrefixDist) {
					bestPrefixDist = segDist;
					bestPrefixMatch = known;
				}
			}
			if (bestPrefixMatch != null) {
				return bestPrefixMatch;
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
	 * @param keys
	 *            the property key names to check
	 * @return list of warning messages (empty if all known)
	 */
	public static List<String> findUnknownKeys(Collection<String> keys) {
		List<String> warnings = new ArrayList<>();
		if (keys == null)
			return warnings;
		for (String key : keys) {
			if (key == null)
				continue;
			// Detect common prefix mistakes: test-order.* or test_order.* instead of
			// testorder.*
			if (key.startsWith("test-order.") || key.startsWith("test_order.")) {
				String corrected = "testorder." + key.substring(key.indexOf('.') + 1);
				String suggestion = KNOWN_KEYS.contains(corrected) ? corrected : findClosest(corrected);
				if (suggestion != null) {
					warnings.add("Unknown property '" + key + "' — did you mean '" + suggestion
							+ "'? (Note: the prefix is 'testorder.' with no hyphen or underscore)");
				} else {
					warnings.add("Unknown property '" + key
							+ "' — the correct prefix is 'testorder.' (no hyphen or underscore).");
				}
				continue;
			}
			if (!key.startsWith("testorder."))
				continue;
			if (KNOWN_KEYS.contains(key))
				continue;
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
		if (a == null)
			a = "";
		if (b == null)
			b = "";
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
