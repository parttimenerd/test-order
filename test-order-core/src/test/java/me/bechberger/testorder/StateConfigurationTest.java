package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StateConfiguration}.
 */
class StateConfigurationTest {

	@Test
	void testDefaultValues() {
		StateConfiguration config = new StateConfiguration();

		// Verify defaults are set from TestOrderState resource defaults
		assertEquals(TestOrderState.DEFAULT_FAILURE_DECAY, config.failureDecay());
		assertEquals(TestOrderState.DEFAULT_METHOD_FAILURE_DECAY, config.methodFailureDecay());
		assertEquals(TestOrderState.DEFAULT_DURATION_ALPHA, config.durationAlpha());
		assertEquals(TestOrderState.DEFAULT_METHOD_DURATION_ALPHA, config.methodDurationAlpha());
		assertEquals(TestOrderState.DEFAULT_FAILURE_PRUNE_THRESHOLD, config.failurePruneThreshold());
		assertEquals(TestOrderState.DEFAULT_EMA_VARIANCE_THRESHOLD, config.emaVarianceThreshold());
		assertEquals(TestOrderState.DEFAULT_HISTORY_MAX_RUNS, config.historyMaxRuns());
		assertEquals(0, config.runsSinceLearn());
	}

	@Test
	void testCopyConstructor() {
		StateConfiguration original = new StateConfiguration();
		original.setFailureDecay(0.2);
		original.setDurationAlpha(0.9);

		StateConfiguration copy = new StateConfiguration(original);

		assertEquals(0.2, copy.failureDecay());
		assertEquals(0.9, copy.durationAlpha());
	}

	@Test
	void testFailureDecayValidation() {
		StateConfiguration config = new StateConfiguration();

		// Valid values
		config.setFailureDecay(0.0);
		config.setFailureDecay(1.0);
		config.setFailureDecay(0.5);

		// Invalid values
		assertThrows(IllegalArgumentException.class, () -> config.setFailureDecay(-0.1));
		assertThrows(IllegalArgumentException.class, () -> config.setFailureDecay(1.1));
	}

	@Test
	void testMethodFailureDecayValidation() {
		StateConfiguration config = new StateConfiguration();

		// Valid values
		config.setMethodFailureDecay(0.0);
		config.setMethodFailureDecay(1.0);
		config.setMethodFailureDecay(0.3);

		// Invalid values
		assertThrows(IllegalArgumentException.class, () -> config.setMethodFailureDecay(-0.01));
		assertThrows(IllegalArgumentException.class, () -> config.setMethodFailureDecay(1.01));
	}

	@Test
	void testDurationAlphaValidation() {
		StateConfiguration config = new StateConfiguration();

		// Valid values
		config.setDurationAlpha(0.0);
		config.setDurationAlpha(1.0);
		config.setDurationAlpha(0.85);

		// Invalid values
		assertThrows(IllegalArgumentException.class, () -> config.setDurationAlpha(-0.001));
		assertThrows(IllegalArgumentException.class, () -> config.setDurationAlpha(1.001));
	}

	@Test
	void testMethodDurationAlphaValidation() {
		StateConfiguration config = new StateConfiguration();

		// Valid values
		config.setMethodDurationAlpha(0.0);
		config.setMethodDurationAlpha(1.0);
		config.setMethodDurationAlpha(0.75);

		// Invalid values
		assertThrows(IllegalArgumentException.class, () -> config.setMethodDurationAlpha(-1.0));
		assertThrows(IllegalArgumentException.class, () -> config.setMethodDurationAlpha(2.0));
	}

	@Test
	void testFailurePruneThresholdValidation() {
		StateConfiguration config = new StateConfiguration();

		// Valid values
		config.setFailurePruneThreshold(0.0);
		config.setFailurePruneThreshold(0.01);
		config.setFailurePruneThreshold(1000.0);

		// Invalid values
		assertThrows(IllegalArgumentException.class, () -> config.setFailurePruneThreshold(-0.1));
		assertThrows(IllegalArgumentException.class, () -> config.setFailurePruneThreshold(Double.NaN));
		assertThrows(IllegalArgumentException.class, () -> config.setFailurePruneThreshold(Double.POSITIVE_INFINITY));
		assertThrows(IllegalArgumentException.class, () -> config.setFailurePruneThreshold(Double.NEGATIVE_INFINITY));
	}

	@Test
	void testEmaVarianceThresholdValidation() {
		StateConfiguration config = new StateConfiguration();

		// Valid values
		config.setEmaVarianceThreshold(0.0);
		config.setEmaVarianceThreshold(0.35);
		config.setEmaVarianceThreshold(100.0);

		// Invalid values
		assertThrows(IllegalArgumentException.class, () -> config.setEmaVarianceThreshold(-0.01));
	}

	@Test
	void testHistoryMaxRunsValidation() {
		StateConfiguration config = new StateConfiguration();

		// Valid values
		config.setHistoryMaxRuns(1);
		config.setHistoryMaxRuns(50);
		config.setHistoryMaxRuns(1000);

		// Invalid values
		assertThrows(IllegalArgumentException.class, () -> config.setHistoryMaxRuns(0));
		assertThrows(IllegalArgumentException.class, () -> config.setHistoryMaxRuns(-1));
	}

	@Test
	void testRunsSinceLearneCounter() {
		StateConfiguration config = new StateConfiguration();

		assertEquals(0, config.runsSinceLearn());

		config.incrementRunsSinceLearn();
		assertEquals(1, config.runsSinceLearn());

		config.incrementRunsSinceLearn();
		config.incrementRunsSinceLearn();
		assertEquals(3, config.runsSinceLearn());

		config.resetRunsSinceLearn();
		assertEquals(0, config.runsSinceLearn());
	}

	@Test
	void testReset() {
		StateConfiguration config = new StateConfiguration();

		// Modify all settings
		config.setFailureDecay(0.2);
		config.setMethodFailureDecay(0.25);
		config.setDurationAlpha(0.9);
		config.setMethodDurationAlpha(0.8);
		config.setFailurePruneThreshold(0.05);
		config.setEmaVarianceThreshold(0.5);
		config.setHistoryMaxRuns(100);
		config.incrementRunsSinceLearn();
		config.incrementRunsSinceLearn();

		// Reset
		config.reset();

		// Verify all back to defaults
		assertEquals(TestOrderState.DEFAULT_FAILURE_DECAY, config.failureDecay());
		assertEquals(TestOrderState.DEFAULT_METHOD_FAILURE_DECAY, config.methodFailureDecay());
		assertEquals(TestOrderState.DEFAULT_DURATION_ALPHA, config.durationAlpha());
		assertEquals(TestOrderState.DEFAULT_METHOD_DURATION_ALPHA, config.methodDurationAlpha());
		assertEquals(TestOrderState.DEFAULT_FAILURE_PRUNE_THRESHOLD, config.failurePruneThreshold());
		assertEquals(TestOrderState.DEFAULT_EMA_VARIANCE_THRESHOLD, config.emaVarianceThreshold());
		assertEquals(TestOrderState.DEFAULT_HISTORY_MAX_RUNS, config.historyMaxRuns());
		assertEquals(0, config.runsSinceLearn());
	}
}
