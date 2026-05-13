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
				"testorder.ml.enabled", "testorder.ml.historyDir", "testorder.ml.history.maxRuns",
				"testorder.ml.predictions.file");

		List<String> warnings = PropertySuggestion.findUnknownKeys(keys);

		assertTrue(warnings.isEmpty(), () -> "Expected no warnings, but got: " + warnings);
	}

	@Test
	void suggestsCanonicalMlHistoryDirForLegacyDottedTypo() {
		List<String> warnings = PropertySuggestion.findUnknownKeys(List.of("testorder.ml.history.dirr"));

		assertEquals(1, warnings.size());
		assertTrue(warnings.get(0).contains("testorder.ml.historyDir"));
	}
}
