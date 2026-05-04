package me.bechberger.testorder.testng;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import me.bechberger.testorder.PersistenceSupport;
import me.bechberger.testorder.TelemetryPersistence;
import me.bechberger.testorder.TestOrderConfig;
import me.bechberger.testorder.TestOrderConfigResolver;
import me.bechberger.testorder.TestOrderLogger;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.UsageStoreReflectionBridge;

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
	private UsageStoreReflectionBridge bridge;

	private String statePath;

	// Plain collections — tests never execute in parallel within the same JVM.
	private final Set<String> executionOrderSet = new HashSet<>();
	private final List<String> executionOrder = new ArrayList<>();
	private final Set<String> failedClassNames = new HashSet<>();
	private final Map<String, List<Long>> pendingDurations = new HashMap<>();
	private final Map<String, List<Long>> pendingMethodDurations = new HashMap<>();
	private final Set<String> failedMethodNames = new HashSet<>();

	/** The currently active test class (sequential execution). */
	private String activeTestClassName;
	/** Start time (nanos) of the current test class. */
	private long classStartTimeNanos;

	private volatile boolean finishedNormally;
	private boolean initialized;
	private Thread shutdownHook;

	@Override
	public void onStart(ITestContext context) {
		// Guard against multiple <test> tags calling onStart multiple times
		if (initialized)
			return;
		initialized = true;

		learnMode = "true".equals(System.getProperty(TestOrderConfig.LEARN));
		String instrumentationMode = System.getProperty(TestOrderConfig.INSTRUMENTATION_MODE);
		fullMethodMode = "FULL_METHOD".equals(instrumentationMode) || "FULL_MEMBER".equals(instrumentationMode);

		statePath = System.getProperty(TestOrderConfig.STATE_PATH);

		if (learnMode) {
			bridge = new UsageStoreReflectionBridge(fullMethodMode);
			bridge.init();
		}

		finishedNormally = false;
		shutdownHook = new Thread(this::emergencySave, "test-order-testng-emergency-save");
		Runtime.getRuntime().addShutdownHook(shutdownHook);
	}

	@Override
	public void onTestStart(ITestResult result) {
		String className = result.getTestClass().getRealClass().getName();
		// Track class boundary: start tracking when we enter a new class
		if (!className.equals(activeTestClassName)) {
			if (activeTestClassName != null) {
				// Record class-level duration for the previous class
				long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - classStartTimeNanos);
				pendingDurations.computeIfAbsent(activeTestClassName, k -> new ArrayList<>()).add(durationMs);
				if (learnMode && bridge.isAvailable()) {
					bridge.callEndTestClass(activeTestClassName);
				}
			}
			activeTestClassName = className;
			classStartTimeNanos = System.nanoTime();

			if (executionOrderSet.add(className)) {
				executionOrder.add(className);
			}
			if (learnMode && bridge.isAvailable()) {
				bridge.callStartTestClass(className);
			}
		}

		// Method-level tracking
		if (fullMethodMode && learnMode && bridge.isAvailable()) {
			bridge.callStartTestMethod(className, result.getMethod().getMethodName());
		}
	}

	@Override
	public void onTestSuccess(ITestResult result) {
		onTestEnd(result);
	}

	@Override
	public void onTestFailure(ITestResult result) {
		String className = result.getTestClass().getRealClass().getName();
		failedClassNames.add(TestOrderConfigResolver.toTopLevelClassName(className));
		String methodKey = className + "#" + result.getMethod().getMethodName();
		failedMethodNames.add(methodKey);
		onTestEnd(result);
	}

	@Override
	public void onTestSkipped(ITestResult result) {
		// End method tracking if we were in learn mode
		if (fullMethodMode && learnMode && bridge.isAvailable()) {
			bridge.callEndTestMethod();
		}
		// Record method duration even for skipped tests (will be ~0ms)
		String className = result.getTestClass().getRealClass().getName();
		String methodName = result.getMethod().getMethodName();
		long durationMs = result.getEndMillis() - result.getStartMillis();
		if (durationMs > 0) {
			pendingMethodDurations.computeIfAbsent(className + "#" + methodName, k -> new ArrayList<>())
					.add(durationMs);
		}
	}

	@Override
	public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
		onTestEnd(result);
	}

	@Override
	public void onFinish(ITestContext context) {
		// End any remaining active class boundary and record its duration
		if (activeTestClassName != null) {
			long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - classStartTimeNanos);
			pendingDurations.computeIfAbsent(activeTestClassName, k -> new ArrayList<>()).add(durationMs);
			if (learnMode && bridge.isAvailable()) {
				bridge.callEndTestClass(activeTestClassName);
			}
			activeTestClassName = null;
		}

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
		if (fullMethodMode && learnMode && bridge.isAvailable()) {
			bridge.callEndTestMethod();
		}

		// Record method-level durations only (class-level recorded at class boundary
		// transitions)
		long durationMs = result.getEndMillis() - result.getStartMillis();
		pendingMethodDurations.computeIfAbsent(className + "#" + methodName, k -> new ArrayList<>()).add(durationMs);
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
				TestOrderState state = TelemetryPersistence.loadStateOrEmpty(stateFile);
				TelemetryPersistence.applyHistoryMaxRuns(state);
				TelemetryPersistence.applyPendingTelemetry(state, pendingDurations, failedClassNames,
						pendingMethodDurations, failedMethodNames);

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
		TelemetryPersistence.emergencySave(statePath, pendingDurations, failedClassNames, pendingMethodDurations,
				failedMethodNames);
	}
}
