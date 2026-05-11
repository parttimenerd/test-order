package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TelemetryPersistence}.
 */
class TelemetryPersistenceTest {

	private String originalMaxRuns;

	@BeforeEach
	void saveProperties() {
		originalMaxRuns = System.getProperty(TestOrderConfig.HISTORY_MAX_RUNS);
	}

	@AfterEach
	void restoreProperties() {
		if (originalMaxRuns == null) {
			System.clearProperty(TestOrderConfig.HISTORY_MAX_RUNS);
		} else {
			System.setProperty(TestOrderConfig.HISTORY_MAX_RUNS, originalMaxRuns);
		}
	}

	@Test
	void applyHistoryMaxRunsWithValidValue() {
		System.setProperty(TestOrderConfig.HISTORY_MAX_RUNS, "5");
		TestOrderState state = new TestOrderState();
		TelemetryPersistence.applyHistoryMaxRuns(state);
		assertEquals(5, state.historyMaxRuns());
	}

	@Test
	void applyHistoryMaxRunsWithZeroDoesNotThrow() {
		// Zero is invalid (must be > 0) — should log warning, not crash
		System.setProperty(TestOrderConfig.HISTORY_MAX_RUNS, "0");
		TestOrderState state = new TestOrderState();
		int before = state.historyMaxRuns();
		assertDoesNotThrow(() -> TelemetryPersistence.applyHistoryMaxRuns(state));
		assertEquals(before, state.historyMaxRuns(),
				"Invalid maxRuns=0 should leave the state unchanged");
	}

	@Test
	void applyHistoryMaxRunsWithNonNumericDoesNotThrow() {
		System.setProperty(TestOrderConfig.HISTORY_MAX_RUNS, "abc");
		TestOrderState state = new TestOrderState();
		int before = state.historyMaxRuns();
		assertDoesNotThrow(() -> TelemetryPersistence.applyHistoryMaxRuns(state));
		assertEquals(before, state.historyMaxRuns(),
				"Non-numeric maxRuns should leave the state unchanged");
	}

	@Test
	void applyHistoryMaxRunsWithNegativeDoesNotThrow() {
		System.setProperty(TestOrderConfig.HISTORY_MAX_RUNS, "-5");
		TestOrderState state = new TestOrderState();
		int before = state.historyMaxRuns();
		assertDoesNotThrow(() -> TelemetryPersistence.applyHistoryMaxRuns(state));
		assertEquals(before, state.historyMaxRuns(),
				"Negative maxRuns should leave the state unchanged");
	}

	@Test
	void applyHistoryMaxRunsNotSetLeavesDefault() {
		System.clearProperty(TestOrderConfig.HISTORY_MAX_RUNS);
		TestOrderState state = new TestOrderState();
		int before = state.historyMaxRuns();
		TelemetryPersistence.applyHistoryMaxRuns(state);
		assertEquals(before, state.historyMaxRuns(),
				"Unset property should leave the default unchanged");
	}
}
