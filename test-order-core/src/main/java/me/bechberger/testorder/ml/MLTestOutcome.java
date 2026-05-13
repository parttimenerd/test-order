package me.bechberger.testorder.ml;

/**
 * Raw per-test outcome data for ML history. Captures only novel data not
 * already in the rule-based
 * {@link me.bechberger.testorder.TestOrderState.TestOutcome}.
 *
 * @param testClass
 *            fully qualified test class name
 * @param failed
 *            whether the test failed in this run
 * @param durationMs
 *            execution duration in milliseconds
 * @param failureType
 *            exception class name (null if passed)
 */
public record MLTestOutcome(String testClass, boolean failed, long durationMs, String failureType) {
}
