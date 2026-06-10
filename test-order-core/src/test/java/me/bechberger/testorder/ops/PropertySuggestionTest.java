package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class PropertySuggestionTest {

	@Test
	void newlyAddedCanonicalKeysAreNotReportedAsUnknown() {
		List<String> keys = List.of("testorder.change.complexity", "testorder.weights.file",
				"testorder.method.score.failureRecency", "testorder.method.score.fast", "testorder.method.score.slow",
				"testorder.method.score.depOverlap", "testorder.method.score.newMethod",
				"testorder.method.score.changedMethod", "testorder.method.score.coverageBonus", "testorder.tdd",
				"testorder.ml.enabled", "testorder.ml.predictions.file");

		List<String> warnings = PropertySuggestion.findUnknownKeys(keys);

		assertTrue(warnings.isEmpty(), () -> "Expected no warnings, but got: " + warnings);
	}

	@Test
	void mlHistoryDirTypoProducesWarning() {
		// testorder.ml.history.dirr is a typo — and testorder.ml.historyDir is itself
		// not a valid/functional key, so the warning should not suggest it
		List<String> warnings = PropertySuggestion.findUnknownKeys(List.of("testorder.ml.history.dirr"));

		assertEquals(1, warnings.size());
		// Should produce a warning (either "unknown" or a suggestion for a real key)
		assertFalse(warnings.get(0).isEmpty(), "Expected a non-empty warning for ml.history.dirr typo");
	}

	@Test
	void showAndDetectAndReactorKeysAreNotReportedAsUnknown() {
		List<String> keys = List.of("testorder.show.classes", "testorder.show.methods", "testorder.show.ml",
				"testorder.show.all", "testorder.show.format", "testorder.show.filter", "testorder.detect.algorithm",
				"testorder.detect.timeBudget", "testorder.detect.stopOnFirst", "testorder.detect.seed",
				"testorder.detect.failOnDetection", "testorder.reactor.suggest", "testorder.reactor.topN");

		List<String> warnings = PropertySuggestion.findUnknownKeys(keys);

		assertTrue(warnings.isEmpty(), () -> "Expected no warnings for show/detect/reactor keys, but got: " + warnings);
	}

	@Test
	void showExplainAndFullNamesSuggestShowOrderVariants() {
		// testorder.show.explain is a registered alias for testorder.showOrder.explain
		// — it must NOT produce an "unknown key" warning
		List<String> explainWarnings = PropertySuggestion.findUnknownKeys(List.of("testorder.show.explain"));
		assertTrue(explainWarnings.isEmpty(),
				"testorder.show.explain is a valid alias — expected no warning, got: " + explainWarnings);
	}

	@Test
	void nonExistentShowSubkeyProducesWarning() {
		// testorder.show.xplain is a typo — it should produce a warning
		List<String> typoWarnings = PropertySuggestion.findUnknownKeys(List.of("testorder.show.xplain"));
		assertFalse(typoWarnings.isEmpty(), "testorder.show.xplain should produce a warning (not a real key)");
	}

	@Test
	void aliasKeysAreNotReportedAsUnknown() {
		List<String> keys = List.of("testorder.index", "testorder.stateFile", "testorder.sourceRoot",
				"testorder.hashFile", "testorder.testHashFile", "testorder.methodHashFile", "testorder.testSourceRoot",
				"testorder.depsDir", "testorder.methodOrderingEnabled", "testorder.structuralDiff.enabled",
				"testorder.compression", "testorder.verboseFile", "testorder.dashboard.coverageDir");

		List<String> warnings = PropertySuggestion.findUnknownKeys(keys);

		assertTrue(warnings.isEmpty(), () -> "Expected no warnings for alias keys, but got: " + warnings);
	}

	@Test
	void hyphenPrefixTypoSuggestsCorrectKey() {
		List<String> warnings = PropertySuggestion.findUnknownKeys(List.of("test-order.mode"));

		assertEquals(1, warnings.size());
		assertTrue(warnings.get(0).contains("testorder.mode"),
				"Should suggest testorder.mode for test-order.mode: " + warnings.get(0));
		assertTrue(warnings.get(0).contains("hyphen") || warnings.get(0).contains("no hyphen"),
				"Should mention the hyphen issue: " + warnings.get(0));
	}

	@Test
	void underscorePrefixTypoSuggestsCorrectKey() {
		List<String> warnings = PropertySuggestion.findUnknownKeys(List.of("test_order.skip"));

		assertEquals(1, warnings.size());
		assertTrue(warnings.get(0).contains("testorder.skip"),
				"Should suggest testorder.skip for test_order.skip: " + warnings.get(0));
	}

	@Test
	void nullInputReturnsEmpty() {
		assertTrue(PropertySuggestion.findUnknownKeys(null).isEmpty());
	}

	@Test
	void emptyInputReturnsEmpty() {
		assertTrue(PropertySuggestion.findUnknownKeys(List.of()).isEmpty());
	}

	@Test
	void nonTestorderKeyIsIgnored() {
		List<String> warnings = PropertySuggestion.findUnknownKeys(List.of("maven.compiler.source", "surefire.skip"));

		assertTrue(warnings.isEmpty(), "Non-testorder keys should be ignored");
	}

	@Test
	void closestSuggestsChangedTestClasses() {
		// testorder.changed.tests is a common typo for testorder.changed.test.classes
		String closest = PropertySuggestion.findClosest("testorder.changed.tests");
		assertEquals("testorder.changed.test.classes", closest);
	}

	@Test
	void closestReturnsNullForCompletelyUnrelatedKey() {
		String closest = PropertySuggestion.findClosest("testorder.zzzzzzzzzzz");
		// Should not return a wildly wrong suggestion — null is acceptable
		// (if it returns something, verify it's genuinely close)
		if (closest != null) {
			int dist = PropertySuggestion.levenshtein("testorder.zzzzzzzzzzz".toLowerCase(), closest.toLowerCase());
			assertTrue(dist <= 3, "Returned suggestion is too far from input: " + closest);
		}
	}

	@Test
	void closestNullReturnsNull() {
		assertNull(PropertySuggestion.findClosest(null));
	}

	@Test
	void closestBlankReturnsNull() {
		assertNull(PropertySuggestion.findClosest(""));
		assertNull(PropertySuggestion.findClosest("   "));
	}

	@Test
	void legacyWeightsFileSuggestsCanonicalKey() {
		// testorder.weightsFile is not a functional property — the correct key is
		// testorder.weights.file
		List<String> warnings = PropertySuggestion.findUnknownKeys(List.of("testorder.weightsFile"));
		assertFalse(warnings.isEmpty(), "testorder.weightsFile should produce a warning (not a real key)");
		assertTrue(warnings.get(0).contains("testorder.weights.file"),
				"Should suggest testorder.weights.file: " + warnings.get(0));
	}

	@Test
	void mlHistoryDirIsNotAKnownKey() {
		// testorder.ml.historyDir is not read by any plugin — the history directory is
		// hardcoded to .test-order/ml-history
		List<String> warnings = PropertySuggestion.findUnknownKeys(List.of("testorder.ml.historyDir"));
		assertFalse(warnings.isEmpty(), "testorder.ml.historyDir should produce a warning (not a real key)");
	}

	@Test
	void mlHistoryMaxRunsIsNotAKnownKey() {
		// testorder.ml.history.maxRuns is not implemented — the append() method that
		// would use it is never called from production code
		List<String> warnings = PropertySuggestion.findUnknownKeys(List.of("testorder.ml.history.maxRuns"));
		assertFalse(warnings.isEmpty(), "testorder.ml.history.maxRuns should produce a warning (not a real key)");
	}

	@Test
	void emaVarianceThresholdIsNotAKnownKey() {
		// testorder.score.ema.varianceThreshold is only stored in the state file — no
		// plugin reads it from -D system properties
		List<String> warnings = PropertySuggestion.findUnknownKeys(List.of("testorder.score.ema.varianceThreshold"));
		assertFalse(warnings.isEmpty(),
				"testorder.score.ema.varianceThreshold should produce a warning (not a real key)");
	}

	@Test
	void verboseIsNotAKnownKey() {
		// testorder.verbose is not processed by any plugin — the correct key for
		// verbose agent output is testorder.verboseFile
		List<String> warnings = PropertySuggestion.findUnknownKeys(List.of("testorder.verbose"));
		assertFalse(warnings.isEmpty(), "testorder.verbose should produce a warning (not a real key)");
		assertTrue(warnings.get(0).contains("testorder.verboseFile") || warnings.get(0).contains("verbose"),
				"Should suggest verboseFile or mention verbose: " + warnings.get(0));
	}

	@Test
	void bareMethodOrderIsNotAKnownKey() {
		// testorder.methodOrder (bare) is not a real key — the correct key is
		// testorder.methodOrder.enabled
		List<String> warnings = PropertySuggestion.findUnknownKeys(List.of("testorder.methodOrder"));
		assertFalse(warnings.isEmpty(), "testorder.methodOrder should produce a warning (not a real key)");
		assertTrue(warnings.get(0).contains("testorder.methodOrder.enabled") || warnings.get(0).contains("methodOrder"),
				"Should suggest testorder.methodOrder.enabled: " + warnings.get(0));
	}

	@Test
	void levenshteinNullTreatedAsEmpty() {
		assertEquals(0, PropertySuggestion.levenshtein(null, null));
		assertEquals(3, PropertySuggestion.levenshtein(null, "abc"));
		assertEquals(3, PropertySuggestion.levenshtein("abc", null));
	}

	@Test
	void findUnknownKeysSkipsNullEntries() {
		List<String> keys = new java.util.ArrayList<>();
		keys.add(null);
		keys.add("testorder.mode");
		keys.add(null);
		// should not throw, and valid key should not produce a warning
		List<String> warnings = PropertySuggestion.findUnknownKeys(keys);
		assertTrue(warnings.isEmpty(), "Null entries should be skipped: " + warnings);
	}

	@Test
	void newReactorDownloadAndStaticAnalysisKeysAreNotReportedAsUnknown() {
		List<String> keys = List.of("testorder.reactorReorder", "testorder.reactorTopN",
				"testorder.reactorReorder.dryRun", "testorder.download.fallbackToLearn",
				"testorder.staticAnalysis.enabled", "testorder.staticAnalysis.depth",
				"testorder.showStaticAnalysis.verbose");

		List<String> warnings = PropertySuggestion.findUnknownKeys(keys);

		assertTrue(warnings.isEmpty(), () -> "Expected no warnings for new keys, but got: " + warnings);
	}

	@Test
	void internalRuntimeKeysAreNotReportedAsUnknown() {
		// Keys written by the plugin to testorder-config.properties or passed as
		// surefire JVM properties — must not trigger user-facing warnings
		List<String> keys = List.of("testorder.pendingRestores", "testorder.build.id", "testorder.pending.runs.dir",
				"testorder.changeDetection.logged", "testorder.moduleId", "testorder.internal.buildId",
				"testorder.offline.includePackages", "testorder.offline.instrMode", "testorder.offline.pending");

		List<String> warnings = PropertySuggestion.findUnknownKeys(keys);

		assertTrue(warnings.isEmpty(), "Internal runtime keys must not warn: " + warnings);
	}

	@Test
	void mutationsAndBytecodeKeysAreNotReportedAsUnknown() {
		List<String> keys = List.of("testorder.mutations.outputFile", "testorder.mutations.timeBudget",
				"testorder.mutations.targetClasses", "testorder.bytecodeChangeDetection.enabled",
				"testorder.bytecodeAugmentDependencyMap.enabled", "testorder.bytecodeHashFile");

		List<String> warnings = PropertySuggestion.findUnknownKeys(keys);

		assertTrue(warnings.isEmpty(), () -> "Expected no warnings for mutation/bytecode keys, but got: " + warnings);
	}
}
