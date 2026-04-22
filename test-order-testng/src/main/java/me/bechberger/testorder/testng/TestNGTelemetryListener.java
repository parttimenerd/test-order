package me.bechberger.testorder.testng;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import me.bechberger.testorder.PersistenceSupport;
import me.bechberger.testorder.TestOrderConfig;
import me.bechberger.testorder.TestOrderLogger;
import me.bechberger.testorder.TestOrderState;

/**
 * TestNG listener that tracks test execution telemetry for test-order.
 * <p>
 * In <b>learn mode</b>: communicates test class/method boundaries to the
 * agent's {@code UsageStore} via reflection so that per-test dependency data is
 * recorded.
 * <p>
 * In all modes: records test durations and failures into the shared
 * {@link TestOrderState} file for future prioritization.
 * <p>
 * Auto-discovered via {@code META-INF/services/org.testng.ITestNGListener}.
 */
public class TestNGTelemetryListener implements ITestListener {

	private boolean learnMode;
	private boolean fullMethodMode;
	private Object usageStoreInstance;
	private Method startTestClassMethod;
	private Method endTestClassMethod;
	private Method startTestMethodMethod;
	private Method endTestMethodMethod;

	private String statePath;

	private final Set<String> executionOrderSet = ConcurrentHashMap.newKeySet();
	private final List<String> executionOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
	private final Set<String> failedClassNames = ConcurrentHashMap.newKeySet();
	private final Map<String, ConcurrentLinkedQueue<Long>> pendingDurations = new ConcurrentHashMap<>();
	private final Map<String, ConcurrentLinkedQueue<Long>> pendingMethodDurations = new ConcurrentHashMap<>();
	private final Set<String> failedMethodNames = ConcurrentHashMap.newKeySet();

	/**
	 * Track the currently active test class per thread for class-level boundary
	 * calls.
	 */
	private final Map<Long, String> activeTestClass = new ConcurrentHashMap<>();
	/**
	 * Track class-level start times (nanos) keyed by class name for proper duration
	 * recording.
	 */
	private final Map<String, Long> classStartTimes = new ConcurrentHashMap<>();

	private volatile boolean finishedNormally;
	private final java.util.concurrent.atomic.AtomicBoolean initialized = new java.util.concurrent.atomic.AtomicBoolean();
	private Thread shutdownHook;

	@Override
	public void onStart(ITestContext context) {
		// Guard against multiple <test> tags calling onStart multiple times
		if (!initialized.compareAndSet(false, true))
			return;

		learnMode = "true".equals(System.getProperty(TestOrderConfig.LEARN));
		String instrumentationMode = System.getProperty(TestOrderConfig.INSTRUMENTATION_MODE);
		fullMethodMode = "FULL_METHOD".equals(instrumentationMode) || "FULL_MEMBER".equals(instrumentationMode);

		statePath = System.getProperty(TestOrderConfig.STATE_PATH);

		if (learnMode) {
			initReflection();
		}

		finishedNormally = false;
		shutdownHook = new Thread(this::emergencySave, "test-order-testng-emergency-save");
		Runtime.getRuntime().addShutdownHook(shutdownHook);
	}

	@Override
	public void onTestStart(ITestResult result) {
		String className = result.getTestClass().getRealClass().getName();
		long threadId = Thread.currentThread().threadId();

		// Track class boundary: start tracking when we enter a new class
		String previousClass = activeTestClass.get(threadId);
		if (!className.equals(previousClass)) {
			if (previousClass != null) {
				// Record class-level duration for the previous class
				Long startTime = classStartTimes.remove(previousClass);
				if (startTime != null) {
					long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
					pendingDurations.computeIfAbsent(previousClass, k -> new ConcurrentLinkedQueue<>()).add(durationMs);
				}
				if (learnMode && usageStoreInstance != null) {
					callEndTestClass(previousClass);
				}
			}
			activeTestClass.put(threadId, className);
			classStartTimes.putIfAbsent(className, System.nanoTime());

			if (executionOrderSet.add(className)) {
				executionOrder.add(className);
			}
			if (learnMode && usageStoreInstance != null) {
				callStartTestClass(className);
			}
		}

		// Method-level tracking
		if (fullMethodMode && learnMode && usageStoreInstance != null) {
			callStartTestMethod(className, result.getMethod().getMethodName());
		}
	}

	@Override
	public void onTestSuccess(ITestResult result) {
		onTestEnd(result);
	}

	@Override
	public void onTestFailure(ITestResult result) {
		String className = result.getTestClass().getRealClass().getName();
		failedClassNames.add(toTopLevelClassName(className));
		String methodKey = className + "#" + result.getMethod().getMethodName();
		failedMethodNames.add(methodKey);
		onTestEnd(result);
	}

	@Override
	public void onTestSkipped(ITestResult result) {
		// End method tracking if we were in learn mode
		if (fullMethodMode && learnMode && usageStoreInstance != null) {
			callEndTestMethod();
		}
		// Record method duration even for skipped tests (will be ~0ms)
		String className = result.getTestClass().getRealClass().getName();
		String methodName = result.getMethod().getMethodName();
		long durationMs = result.getEndMillis() - result.getStartMillis();
		if (durationMs > 0) {
			pendingMethodDurations.computeIfAbsent(className + "#" + methodName, k -> new ConcurrentLinkedQueue<>())
					.add(durationMs);
		}
	}

	@Override
	public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
		onTestEnd(result);
	}

	@Override
	public void onFinish(ITestContext context) {
		// End any remaining active class boundaries and record their durations
		for (var entry : activeTestClass.entrySet()) {
			String className = entry.getValue();
			Long startTime = classStartTimes.remove(className);
			if (startTime != null) {
				long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
				pendingDurations.computeIfAbsent(className, k -> new ConcurrentLinkedQueue<>()).add(durationMs);
			}
			if (learnMode && usageStoreInstance != null) {
				callEndTestClass(className);
			}
		}
		activeTestClass.clear();

		persistState();

		finishedNormally = true;
		if (shutdownHook != null) {
			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException ignored) {
				// JVM already shutting down
			}
		}
	}

	private void onTestEnd(ITestResult result) {
		String className = result.getTestClass().getRealClass().getName();
		String methodName = result.getMethod().getMethodName();

		// End method-level tracking
		if (fullMethodMode && learnMode && usageStoreInstance != null) {
			callEndTestMethod();
		}

		// Record method-level durations only (class-level recorded at class boundary
		// transitions)
		long durationMs = result.getEndMillis() - result.getStartMillis();
		pendingMethodDurations.computeIfAbsent(className + "#" + methodName, k -> new ConcurrentLinkedQueue<>())
				.add(durationMs);
	}

	private void persistState() {
		// Resolve state path: prefer system property, fall back to pending (set by
		// interceptor)
		String effectiveStatePath = statePath;
		if (effectiveStatePath == null || effectiveStatePath.isEmpty()) {
			effectiveStatePath = TestOrderState.getPendingStatePath();
		}
		if (effectiveStatePath == null || effectiveStatePath.isEmpty())
			return;

		boolean resetPending = false;
		Path stateFile = Path.of(effectiveStatePath);
		try {
			PersistenceSupport.withFileLock(stateFile, () -> {
				TestOrderState state = loadStateOrEmpty(stateFile);
				applyHistoryMaxRuns(state);
				applyPendingTelemetry(state);

				if (TestOrderState.hasPendingData() && !executionOrder.isEmpty()) {
					TestOrderState.RunRecord record = TestOrderState.buildRunRecord(executionOrder, failedClassNames);
					state.addRunRecord(record);
					boolean isLearnRun = Boolean.parseBoolean(System.getProperty(TestOrderConfig.LEARN, "false"));
					if (!isLearnRun) {
						state.incrementRunsSinceLearn();
					}
					if (record.totalFailures() > 0) {
						TestOrderLogger.info("Run APFD: {}% (first failure at position {}/{})",
								String.format("%.1f", record.apfd() * 100), record.firstFailurePosition() + 1,
								record.totalTests());
					}
				}
				state.save(stateFile);
				return state;
			});
			resetPending = TestOrderState.hasPendingData() && !executionOrder.isEmpty();
		} catch (IOException e) {
			TestOrderLogger.error("Failed to save TestNG state: {}", e.getMessage());
		}
		if (resetPending) {
			TestOrderState.resetPending();
		}
	}

	private void emergencySave() {
		if (finishedNormally)
			return;

		String effectiveStatePath = statePath;
		if (effectiveStatePath == null || effectiveStatePath.isEmpty()) {
			effectiveStatePath = TestOrderState.getPendingStatePath();
		}
		if (effectiveStatePath == null || effectiveStatePath.isEmpty())
			return;

		try {
			Path stateFile = Path.of(effectiveStatePath);
			PersistenceSupport.withFileLock(stateFile, () -> {
				TestOrderState state = loadStateOrEmpty(stateFile);
				applyHistoryMaxRuns(state);
				applyPendingTelemetry(state);
				state.save(stateFile);
				return state;
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

	private void applyPendingTelemetry(TestOrderState state) {
		for (var entry : pendingDurations.entrySet()) {
			entry.getValue().forEach(duration -> state.recordDuration(entry.getKey(), duration));
		}
		for (String failed : failedClassNames) {
			state.recordFailure(failed);
		}
		for (var entry : pendingMethodDurations.entrySet()) {
			String[] parts = entry.getKey().split("#", 2);
			if (parts.length == 2) {
				entry.getValue().forEach(duration -> state.recordMethodDuration(parts[0], parts[1], duration));
			}
		}
		for (String methodKey : failedMethodNames) {
			String[] parts = methodKey.split("#", 2);
			if (parts.length == 2) {
				state.recordMethodFailure(parts[0], parts[1]);
			}
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

	/**
	 * Strips inner/nested class suffixes to get the top-level enclosing class name.
	 */
	static String toTopLevelClassName(String className) {
		int dollar = className.indexOf('$');
		return dollar > 0 ? className.substring(0, dollar) : className;
	}

	// ── Reflection access to UsageStore ────────────────────────────────

	private void initReflection() {
		try {
			Class<?> usageStoreClass = Class.forName("me.bechberger.testorder.agent.runtime.UsageStore", true, null);
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
		if (usageStoreInstance == null)
			return;
		try {
			startTestClassMethod.invoke(usageStoreInstance, testClassName);
		} catch (Exception e) {
			TestOrderLogger.debug("Failed to call startTestClass: {}", e.getMessage());
		}
	}

	private void callEndTestClass(String testClassName) {
		if (usageStoreInstance == null)
			return;
		try {
			endTestClassMethod.invoke(usageStoreInstance, testClassName);
		} catch (Exception e) {
			TestOrderLogger.debug("Failed to call endTestClass: {}", e.getMessage());
		}
	}

	private void callStartTestMethod(String className, String methodName) {
		if (usageStoreInstance == null || startTestMethodMethod == null)
			return;
		try {
			startTestMethodMethod.invoke(usageStoreInstance, className, methodName);
		} catch (Exception e) {
			TestOrderLogger.debug("Failed to call startTestMethod: {}", e.getMessage());
		}
	}

	private void callEndTestMethod() {
		if (usageStoreInstance == null || endTestMethodMethod == null)
			return;
		try {
			endTestMethodMethod.invoke(usageStoreInstance);
		} catch (Exception e) {
			TestOrderLogger.debug("Failed to call endTestMethod: {}", e.getMessage());
		}
	}
}
