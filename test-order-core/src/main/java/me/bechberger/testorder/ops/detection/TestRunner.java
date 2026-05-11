package me.bechberger.testorder.ops.detection;

import java.util.List;
import java.util.Set;

/**
 * Abstraction for running tests in a specific order and comparing results to baseline.
 * Implementations delegate to Surefire/Failsafe/Gradle test task.
 */
public interface TestRunner {

    /**
     * Run the given tests in the specified order and return the outcome.
     *
     * @param testOrder ordered list of test class FQCNs to execute
     * @return the run result with pass/fail per test
     */
    TestRunResult run(List<String> testOrder);

    /**
     * Run a single test class with methods in the specified order.
     * Returns method-level pass/fail results.
     *
     * @param testClass FQCN of the test class
     * @param methodOrder ordered list of method names to execute
     * @return the method-level run result
     */
    default MethodRunResult runMethods(String testClass, List<String> methodOrder) {
        throw new UnsupportedOperationException("Method-level execution not supported by this runner");
    }

    /**
     * Whether this runner supports method-level execution order control.
     */
    default boolean supportsMethodOrdering() {
        return false;
    }

    /**
     * Run a learn phase to collect dependency data at the specified instrumentation level.
     * After this completes, the dependency index file should be updated with fresh data.
     *
     * @param instrumentationMode the instrumentation mode to use (e.g. "FULL_MEMBER")
     * @return true if the learn phase succeeded
     */
    default boolean runLearnPhase(String instrumentationMode) {
        throw new UnsupportedOperationException("Learn phase not supported by this runner");
    }

    /**
     * Whether this runner supports triggering a learn phase.
     */
    default boolean supportsLearnPhase() {
        return false;
    }

    /**
     * Set a deadline (epoch millis) after which the runner should kill any in-progress
     * subprocess and return a failure result. Use {@link Long#MAX_VALUE} for no deadline.
     *
     * @param deadlineMillis hard stop time
     */
    default void setDeadline(long deadlineMillis) {
        // Default: ignore — runners that don't support subprocess control can no-op
    }

    /**
     * Returns true if the configured deadline has already passed.
     */
    default boolean deadlineExceeded() {
        return false;
    }

    /**
     * Result of a test run (class-level).
     */
    record TestRunResult(
            List<String> executionOrder,
            Set<String> passedTests,
            Set<String> failedTests) {

        public boolean passed(String test) {
            return passedTests.contains(test);
        }

        public boolean failed(String test) {
            return failedTests.contains(test);
        }

        public boolean allPassed() {
            return failedTests.isEmpty();
        }

        /** Get all tests that ran before the given test in this execution. */
        public List<String> predecessorsOf(String test) {
            int idx = executionOrder.indexOf(test);
            if (idx <= 0) return List.of();
            return executionOrder.subList(0, idx);
        }
    }

    /**
     * Result of a method-level test run (within a single class).
     */
    record MethodRunResult(
            String testClass,
            List<String> executionOrder,
            Set<String> passedMethods,
            Set<String> failedMethods) {

        public boolean passed(String method) {
            return passedMethods.contains(method);
        }

        public boolean failed(String method) {
            return failedMethods.contains(method);
        }

        public boolean allPassed() {
            return failedMethods.isEmpty();
        }

        /** Get all methods that ran before the given method in this execution. */
        public List<String> predecessorsOf(String method) {
            int idx = executionOrder.indexOf(method);
            if (idx <= 0) return List.of();
            return executionOrder.subList(0, idx);
        }
    }
}
