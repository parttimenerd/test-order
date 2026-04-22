package com.example.listeners;

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Custom test execution listener that tracks test execution timing.
 * This listener is registered automatically via service loader.
 * Tests if test-order plugin handles custom listeners correctly.
 */
public class TimingListener implements TestExecutionListener {
    private static final java.util.Map<String, Long> timings = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> startTimes = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        System.out.println("[TimingListener] Test plan started");
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        if (testIdentifier.isTest()) {
            startTimes.put(testIdentifier.getUniqueId(), System.nanoTime());
        }
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
        if (testIdentifier.isTest()) {
            Long startTime = startTimes.remove(testIdentifier.getUniqueId());
            if (startTime != null) {
                long duration = (System.nanoTime() - startTime) / 1_000_000; // convert to ms
                timings.put(testIdentifier.getDisplayName(), duration);
                System.out.println("[TimingListener] " + testIdentifier.getDisplayName() + " took " + duration + "ms");
            }
        }
    }

    public static java.util.Map<String, Long> getTimings() {
        return new java.util.HashMap<>(timings);
    }

    public static void reset() {
        timings.clear();
        startTimes.clear();
    }
}
