package me.bechberger.testorder;

import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.engine.TestSource;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * JUnit Platform TestExecutionListener that:
 * <ul>
 *     <li>In <b>learn mode</b>: tracks test class boundaries and communicates them to the agent's UsageStore.</li>
 *     <li>In <b>order mode</b>: records test failures to {@code .test-order-failures} for future prioritization.</li>
 * </ul>
 * <p>
 * In learn mode, calls {@code UsageStore.startTestClass/endTestClass} via reflection
 * to support per-test-class dependency tracking.
 * Only activates learn-mode tracking when system property {@code testorder.learn} is set to {@code "true"}.
 * Auto-discovered via {@code META-INF/services/org.junit.platform.launcher.TestExecutionListener}.
 */
public class TelemetryListener implements TestExecutionListener {

    private boolean learnMode;
    private boolean fullMethodMode;
    private Object usageStoreInstance;
    private Method startTestClassMethod;
    private Method endTestClassMethod;
    private Method startTestMethodMethod;
    private Method endTestMethodMethod;

    // state tracking (active when state path is set)
    private TestOrderState state;
    private String statePath;
    private final Map<String, Long> classStartTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> methodStartTimes = new ConcurrentHashMap<>();

    // run quality tracking
    private final Set<String> executionOrderSet = ConcurrentHashMap.newKeySet();
    private final List<String> executionOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Set<String> failedClassNames = ConcurrentHashMap.newKeySet();
    private final Map<String, ConcurrentLinkedQueue<Long>> pendingDurations = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<Long>> pendingMethodDurations = new ConcurrentHashMap<>();
    private final Set<String> failedMethodNames = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedConcurrentClasses = ConcurrentHashMap.newKeySet();

    /** Tracks whether testPlanExecutionFinished ran; used by the shutdown hook to avoid double-save. */
    private volatile boolean finishedNormally;
    private Thread shutdownHook;

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        learnMode = "true".equals(System.getProperty(TestOrderConfig.LEARN));
        String instrumentationMode = System.getProperty(TestOrderConfig.INSTRUMENTATION_MODE);
        fullMethodMode = "FULL_METHOD".equals(instrumentationMode)
            || "FULL_MEMBER".equals(instrumentationMode);

        if (learnMode) {
            initReflection();
        }

        // load state file path for failure + duration tracking
        statePath = System.getProperty(TestOrderConfig.STATE_PATH);

        // Register a JVM shutdown hook so accumulated durations/failures are not
        // lost when the JVM terminates abnormally (e.g. OOM, kill signal) before
        // testPlanExecutionFinished() runs.
        finishedNormally = false;
        shutdownHook = new Thread(this::emergencySave, "test-order-emergency-save");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        testIdentifier.getSource().ifPresent(source -> {
            if (source instanceof ClassSource classSource) {
                String name = classSource.getClassName();
                maybeWarnConcurrentExecution(name);

                // track start time for duration (local map operation, very fast)
                classStartTimes.put(testIdentifier.getUniqueId(), System.nanoTime());

                // track execution order for run quality (O(1) dedup with ConcurrentHashSet)
                if (executionOrderSet.add(name)) {
                    executionOrder.add(name);
                }

                // In learn mode: call agent to record per-test-class boundary
                // Do this AFTER timing starts so agent overhead isn't counted
                if (learnMode && usageStoreInstance != null) {
                    callStartTestClass(name);
                }
            } else if (source instanceof MethodSource methodSource) {
                // track method-level start time
                String methodKey = methodSource.getClassName() + "#" + methodSource.getMethodName();
                methodStartTimes.put(testIdentifier.getUniqueId(), System.nanoTime());

                // In FULL_METHOD and FULL_MEMBER modes: start per-method dependency recording
                if (fullMethodMode && learnMode && usageStoreInstance != null) {
                    callStartTestMethod(methodSource.getClassName(), methodSource.getMethodName());
                }
            }
        });
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
        if (learnMode && usageStoreInstance != null) {
            testIdentifier.getSource().ifPresent(source -> {
                if (source instanceof ClassSource) {
                    callEndTestClass(((ClassSource) source).getClassName());
                }
            });
        }
        // record failures (both class-level and method-level)
        if (result.getStatus() == TestExecutionResult.Status.FAILED) {
            testIdentifier.getSource().ifPresent(source -> {
                String className = null;
                String methodName = null;
                if (source instanceof ClassSource classSource) {
                    className = classSource.getClassName();
                } else if (source instanceof MethodSource methodSource) {
                    className = methodSource.getClassName();
                    methodName = methodSource.getMethodName();
                }
                if (className != null) {
                    // Record class-level failure under the top-level enclosing class
                    // so that @Nested class failures are attributed to the outer class
                    // (PriorityClassOrderer looks up scores by top-level class name)
                    failedClassNames.add(toTopLevelClassName(className));
                    // Record method-level failure (preserves nested class for method scoring)
                    if (methodName != null) {
                        String methodKey = className + "#" + methodName;
                        failedMethodNames.add(methodKey);
                    }
                }
            });
        }
        // record duration (both class-level and method-level)
        testIdentifier.getSource().ifPresent(source -> {
            if (source instanceof ClassSource classSource) {
                Long start = classStartTimes.remove(testIdentifier.getUniqueId());
                if (start != null) {
                    long duration = elapsedMillis(start);
                    pendingDurations
                            .computeIfAbsent(classSource.getClassName(), ignored -> new ConcurrentLinkedQueue<>())
                            .add(duration);
                }
            } else if (source instanceof MethodSource methodSource) {
                // In FULL_METHOD and FULL_MEMBER modes: end per-method dependency recording
                if (fullMethodMode && learnMode && usageStoreInstance != null) {
                    callEndTestMethod();
                }

                String methodKey = methodSource.getClassName() + "#" + methodSource.getMethodName();
                Long start = methodStartTimes.remove(testIdentifier.getUniqueId());
                if (start != null) {
                    long duration = elapsedMillis(start);
                    pendingMethodDurations
                            .computeIfAbsent(methodKey, ignored -> new ConcurrentLinkedQueue<>())
                            .add(duration);
                }
            }
        });
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        // resolve state path: prefer system property, fall back to pending (set by PriorityClassOrderer)
        String effectiveStatePath = statePath;
        if (effectiveStatePath == null || effectiveStatePath.isEmpty()) {
            effectiveStatePath = TestOrderState.getPendingStatePath();
        }

        boolean resetPending = false;
        if (effectiveStatePath != null && !effectiveStatePath.isEmpty()) {
            Path stateFile = Path.of(effectiveStatePath);
            try {
                state = PersistenceSupport.withFileLock(stateFile, () -> {
                    TestOrderState lockedState = loadStateOrEmpty(stateFile);
                    applyHistoryMaxRuns(lockedState);
                    applyPendingTelemetry(lockedState);
                    if (TestOrderState.hasPendingData() && !executionOrder.isEmpty()) {
                        TestOrderState.RunRecord record =
                                TestOrderState.buildRunRecord(executionOrder, failedClassNames);
                        lockedState.addRunRecord(record);
                        boolean isLearnRun = Boolean.parseBoolean(System.getProperty(TestOrderConfig.LEARN, "false"));
                        if (!isLearnRun) {
                            lockedState.incrementRunsSinceLearn();
                        }
                        if (record.totalFailures() > 0) {
                            TestOrderLogger.info("Run APFD: {}% (first failure at position {}/{})",
                                    String.format("%.1f", record.apfd() * 100),
                                    record.firstFailurePosition() + 1, record.totalTests());
                        }
                    }
                    lockedState.save(stateFile);
                    return lockedState;
                });
                resetPending = TestOrderState.hasPendingData() && !executionOrder.isEmpty();
            } catch (IOException e) {
                TestOrderLogger.error("Failed to save state: {}", e.getMessage());
            }
        }
        if (resetPending) {
            TestOrderState.resetPending();
        }
        PriorityMethodOrderer.clearPendingState();

        // Mark normal completion and remove the shutdown hook — the state was already saved.
        finishedNormally = true;
        if (shutdownHook != null) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException ignored) { /* JVM already shutting down */ }
        }
    }

    /**
     * Emergency save invoked from the JVM shutdown hook when
     * {@code testPlanExecutionFinished()} was never called (e.g. OOM, SIGTERM).
     * Saves whatever durations and failures have been accumulated so far.
     */
    private void emergencySave() {
        if (finishedNormally) return;

        String effectiveStatePath = statePath;
        if (effectiveStatePath == null || effectiveStatePath.isEmpty()) {
            effectiveStatePath = TestOrderState.getPendingStatePath();
        }
        if (effectiveStatePath == null || effectiveStatePath.isEmpty()) return;

        try {
            Path stateFile = Path.of(effectiveStatePath);
            PersistenceSupport.withFileLock(stateFile, () -> {
                TestOrderState emergencyState = loadStateOrEmpty(stateFile);
                applyHistoryMaxRuns(emergencyState);
                applyPendingTelemetry(emergencyState);
                emergencyState.save(stateFile);
                return emergencyState;
            });
        } catch (Exception ignored) {
            // Best-effort: shutdown hooks must not throw
        }
    }

    private TestOrderState loadStateOrEmpty(Path stateFile) {
        try {
            return TestOrderState.load(stateFile);
        } catch (IOException e) {
            return new TestOrderState();
        }
    }

    private void applyHistoryMaxRuns(TestOrderState targetState) {
        String maxRunsProp = System.getProperty(TestOrderConfig.HISTORY_MAX_RUNS);
        if (maxRunsProp != null) {
            try {
                targetState.setHistoryMaxRuns(Integer.parseInt(maxRunsProp));
            } catch (IllegalArgumentException ignored) {
                // Fall back to default if the property is not a valid positive integer
            }
        }
    }

    private void applyPendingTelemetry(TestOrderState targetState) {
        for (var entry : pendingDurations.entrySet()) {
            entry.getValue().forEach(duration -> targetState.recordDuration(entry.getKey(), duration));
        }
        for (String failed : failedClassNames) {
            targetState.recordFailure(failed);
        }
        for (var entry : pendingMethodDurations.entrySet()) {
            String[] parts = entry.getKey().split("#", 2);
            if (parts.length == 2) {
                entry.getValue().forEach(duration -> targetState.recordMethodDuration(parts[0], parts[1], duration));
            }
        }
        for (String methodKey : failedMethodNames) {
            String[] parts = methodKey.split("#", 2);
            if (parts.length == 2) {
                targetState.recordMethodFailure(parts[0], parts[1]);
            }
        }
    }

    private Set<String> extractTestClassNames(TestPlan testPlan) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (TestIdentifier root : testPlan.getRoots()) {
            for (TestIdentifier child : testPlan.getChildren(root)) {
                child.getSource().ifPresent(source -> {
                    if (source instanceof ClassSource classSource) {
                        names.add(classSource.getClassName());
                    }
                });
            }
        }
        return names;
    }

    private void initReflection() {
        try {
            Class<?> usageStoreClass = Class.forName(
                    "me.bechberger.testorder.agent.runtime.UsageStore", true, null);
            usageStoreInstance = usageStoreClass.getMethod("getInstance").invoke(null);
            startTestClassMethod = usageStoreClass.getMethod("startTestClass", String.class);
            endTestClassMethod = usageStoreClass.getMethod("endTestClass", String.class);
            if (fullMethodMode) {
                startTestMethodMethod = usageStoreClass.getMethod("startTestMethod", String.class, String.class);
                endTestMethodMethod = usageStoreClass.getMethod("endTestMethod");
            }
        } catch (Exception e) {
            TestOrderLogger.error("Failed to initialize UsageStore reflection: {}", e.getMessage());
        }
    }

    private void callStartTestClass(String testClassName) {
        if (usageStoreInstance == null) return;
        try {
            startTestClassMethod.invoke(usageStoreInstance, testClassName);
        } catch (Exception e) {
            TestOrderLogger.debug("Failed to call startTestClass: {}", e.getMessage());
        }
    }

    private void callEndTestClass(String testClassName) {
        if (usageStoreInstance == null) return;
        try {
            endTestClassMethod.invoke(usageStoreInstance, testClassName);
        } catch (Exception e) {
            TestOrderLogger.debug("Failed to call endTestClass: {}", e.getMessage());
        }
    }

    private void callStartTestMethod(String className, String methodName) {
        if (usageStoreInstance == null || startTestMethodMethod == null) return;
        try {
            startTestMethodMethod.invoke(usageStoreInstance, className, methodName);
        } catch (Exception e) {
            TestOrderLogger.debug("Failed to call startTestMethod: {}", e.getMessage());
        }
    }

    private void callEndTestMethod() {
        if (usageStoreInstance == null || endTestMethodMethod == null) return;
        try {
            endTestMethodMethod.invoke(usageStoreInstance);
        } catch (Exception e) {
            TestOrderLogger.debug("Failed to call endTestMethod: {}", e.getMessage());
        }
    }

    private static long elapsedMillis(long startNanos) {
        long elapsedNanos = System.nanoTime() - startNanos;
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, elapsedNanos));
    }

    private void maybeWarnConcurrentExecution(String className) {
        if (!warnedConcurrentClasses.add(className)) {
            return;
        }
        try {
            Class<?> testClass = Class.forName(className);
            Execution execution = testClass.getAnnotation(Execution.class);
            if (execution != null && execution.value() == ExecutionMode.CONCURRENT) {
                TestOrderLogger.warn("Test class {} uses @Execution(CONCURRENT); learn-mode dependency tracking may be inaccurate", className);
            }
        } catch (ClassNotFoundException ignored) {
            warnedConcurrentClasses.remove(className);
        }
    }

    /** Strips inner/nested class suffixes to get the top-level enclosing class name. */
    static String toTopLevelClassName(String className) {
        int dollar = className.indexOf('$');
        return dollar > 0 ? className.substring(0, dollar) : className;
    }
}
