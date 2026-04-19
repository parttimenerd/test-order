package me.bechberger.testorder;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.tinylog.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<String, Long> pendingDurations = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingMethodDurations = new ConcurrentHashMap<>();
    private final Set<String> failedMethodNames = ConcurrentHashMap.newKeySet();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        learnMode = "true".equals(System.getProperty("testorder.learn"));
        String instrumentationMode = System.getProperty("testorder.instrumentation.mode");
        fullMethodMode = "FULL_METHOD".equals(instrumentationMode)
            || "FULL_MEMBER".equals(instrumentationMode);

        if (learnMode) {
            initReflection();
        }

        // load state file path for failure + duration tracking
        statePath = System.getProperty("testorder.state.path");
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        testIdentifier.getSource().ifPresent(source -> {
            if (source instanceof ClassSource classSource) {
                String name = classSource.getClassName();

                // track start time for duration (local map operation, very fast)
                classStartTimes.putIfAbsent(name, System.currentTimeMillis());

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
                methodStartTimes.putIfAbsent(methodKey, System.currentTimeMillis());

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
                    callEndTestClass();
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
                    // Record class-level failure once per class per run
                    failedClassNames.add(className);
                    // Record method-level failure
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
                Long start = classStartTimes.remove(classSource.getClassName());
                if (start != null) {
                    long duration = System.currentTimeMillis() - start;
                    pendingDurations.put(classSource.getClassName(), duration);
                }
            } else if (source instanceof MethodSource methodSource) {
                // In FULL_METHOD and FULL_MEMBER modes: end per-method dependency recording
                if (fullMethodMode && learnMode && usageStoreInstance != null) {
                    callEndTestMethod();
                }

                String methodKey = methodSource.getClassName() + "#" + methodSource.getMethodName();
                Long start = methodStartTimes.remove(methodKey);
                if (start != null) {
                    long duration = System.currentTimeMillis() - start;
                    pendingMethodDurations.put(methodKey, duration);
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

        // load state (deferred to here so the file is only read once, single-threaded)
        if (effectiveStatePath != null && !effectiveStatePath.isEmpty()) {
            try { state = TestOrderState.load(Path.of(effectiveStatePath)); }
            catch (IOException e) { state = new TestOrderState(); }
        }

        // apply pending durations and failures collected during the run
        if (state != null) {
            for (var entry : pendingDurations.entrySet()) {
                state.recordDuration(entry.getKey(), entry.getValue());
            }
            for (String failed : failedClassNames) {
                state.recordFailure(failed);
            }
            // Apply method-level durations and failures from pending
            for (var entry : pendingMethodDurations.entrySet()) {
                String[] parts = entry.getKey().split("#", 2);
                if (parts.length == 2) {
                    state.recordMethodDuration(parts[0], parts[1], entry.getValue());
                }
            }
            for (String methodKey : failedMethodNames) {
                String[] parts = methodKey.split("#", 2);
                if (parts.length == 2) {
                    state.recordMethodFailure(parts[0], parts[1]);
                }
            }
        }

        // save run quality record into state
        if (TestOrderState.hasPendingData() && !executionOrder.isEmpty()) {
            if (state != null) {
                TestOrderState.RunRecord record =
                        TestOrderState.buildRunRecord(executionOrder, failedClassNames);
                state.addRunRecord(record);
                // Increment counter for auto-learn threshold checks only in non-learn runs.
                boolean isLearnRun = Boolean.parseBoolean(System.getProperty("testorder.learn", "false"));
                if (!isLearnRun) {
                    state.incrementRunsSinceLearn();
                }
                if (record.totalFailures() > 0) {
                    Logger.info("Run APFD: {}% (first failure at position {}/{})",
                            String.format("%.1f", record.apfd() * 100),
                            record.firstFailurePosition() + 1, record.totalTests());
                }
            }
            TestOrderState.resetPending();
        }

        // save state file (durations, failures, run history all in one)
        if (state != null && effectiveStatePath != null && !effectiveStatePath.isEmpty()) {
            try {
                state.save(Path.of(effectiveStatePath));
            } catch (IOException e) {
                Logger.error("Failed to save state: {}", e.getMessage());
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
            endTestClassMethod = usageStoreClass.getMethod("endTestClass");
            if (fullMethodMode) {
                startTestMethodMethod = usageStoreClass.getMethod("startTestMethod", String.class, String.class);
                endTestMethodMethod = usageStoreClass.getMethod("endTestMethod");
            }
        } catch (Exception e) {
            Logger.error("Failed to initialize UsageStore reflection: {}", e.getMessage());
        }
    }

    private void callStartTestClass(String testClassName) {
        if (usageStoreInstance == null) return;
        try {
            startTestClassMethod.invoke(usageStoreInstance, testClassName);
        } catch (Exception e) {
            Logger.debug("Failed to call startTestClass: {}", e.getMessage());
        }
    }

    private void callEndTestClass() {
        if (usageStoreInstance == null) return;
        try {
            endTestClassMethod.invoke(usageStoreInstance);
        } catch (Exception e) {
            Logger.debug("Failed to call endTestClass: {}", e.getMessage());
        }
    }

    private void callStartTestMethod(String className, String methodName) {
        if (usageStoreInstance == null || startTestMethodMethod == null) return;
        try {
            startTestMethodMethod.invoke(usageStoreInstance, className, methodName);
        } catch (Exception e) {
            Logger.debug("Failed to call startTestMethod: {}", e.getMessage());
        }
    }

    private void callEndTestMethod() {
        if (usageStoreInstance == null || endTestMethodMethod == null) return;
        try {
            endTestMethodMethod.invoke(usageStoreInstance);
        } catch (Exception e) {
            Logger.debug("Failed to call endTestMethod: {}", e.getMessage());
        }
    }
}
