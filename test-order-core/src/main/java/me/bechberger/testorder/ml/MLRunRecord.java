package me.bechberger.testorder.ml;

import java.util.List;

/**
 * A single test run's ML-relevant data. Stored in the ML history file for
 * training the failure prediction model.
 *
 * @param timestamp
 *            epoch millis when the run completed
 * @param changedClasses
 *            production classes changed in this run's diff context
 * @param changedTestClasses
 *            test classes changed in this run's diff context
 * @param totalTests
 *            total test classes executed
 * @param totalFailures
 *            total test classes that failed
 * @param outcomes
 *            per-test outcomes
 */
public record MLRunRecord(long timestamp, List<String> changedClasses, List<String> changedTestClasses, int totalTests,
		int totalFailures, List<MLTestOutcome> outcomes) {

	public MLRunRecord {
		changedClasses = List.copyOf(changedClasses);
		changedTestClasses = List.copyOf(changedTestClasses);
		outcomes = List.copyOf(outcomes);
	}
}
