package me.bechberger.testorder.ops.detection;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.ops.PluginLog;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared context passed to all detection algorithms.
 *
 * @param graph conflict graph (may be empty for algorithms that don't use it)
 * @param depMap raw dependency data (nullable if not available)
 * @param state run history + failure scores
 * @param referenceOrder last known passing order
 * @param passingTests tests that pass in reference order
 * @param runner executes test orders and reports results
 * @param deadlineMillis hard stop time (epoch millis)
 * @param randomSeed for reproducibility
 * @param log logger for progress messages
 */
public record DetectionContext(
        ConflictGraph graph,
        DependencyMap depMap,
        TestOrderState state,
        List<String> referenceOrder,
        Set<String> passingTests,
        TestRunner runner,
        long deadlineMillis,
        long randomSeed,
        PluginLog log,
        AtomicInteger runCounter) {

    /** Convenience constructor without log/counter (for tests). */
    public DetectionContext(ConflictGraph graph, DependencyMap depMap, TestOrderState state,
                            List<String> referenceOrder, Set<String> passingTests,
                            TestRunner runner, long deadlineMillis, long randomSeed) {
        this(graph, depMap, state, referenceOrder, passingTests, runner,
                deadlineMillis, randomSeed, null, new AtomicInteger(0));
    }

    /** Check if time budget is exhausted. */
    public boolean timeBudgetExhausted() {
        return System.currentTimeMillis() >= deadlineMillis;
    }

    /** Record that a test run was executed and log periodic progress. */
    public void recordRun(int findingsSoFar) {
        int count = runCounter.incrementAndGet();
        // Log progress every 5 runs
        if (log != null && count % 5 == 0) {
            long remaining = deadlineMillis == Long.MAX_VALUE ? -1
                    : (deadlineMillis - System.currentTimeMillis()) / 1000;
            String budget = remaining < 0 ? "unlimited"
                    : remaining + "s remaining";
            log.info("[progress] " + count + " runs completed, "
                    + findingsSoFar + " findings so far (" + budget + ")");
        }
    }

    /** Get the total number of runs executed. */
    public int totalRuns() {
        return runCounter.get();
    }

    /** Create a new context with a different deadline. */
    public DetectionContext withDeadline(long newDeadline) {
        return new DetectionContext(graph, depMap, state, referenceOrder, passingTests,
                runner, newDeadline, randomSeed, log, runCounter);
    }

    /** All test classes in the reference order. */
    public List<String> allTests() {
        return referenceOrder;
    }
}
