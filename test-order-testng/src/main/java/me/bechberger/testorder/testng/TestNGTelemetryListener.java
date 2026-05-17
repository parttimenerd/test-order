package me.bechberger.testorder.testng;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

	// Thread-safe collections — TestNG supports parallel="methods"/"classes".
	private final Set<String> executionOrderSet = ConcurrentHashMap.newKeySet();
	private final List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
	private final Set<String> failedClassNames = ConcurrentHashMap.newKeySet();
	private final Map<String, List<Long>> pendingDurations = new ConcurrentHashMap<>();
	private final Map<String, List<Long>> pendingMethodDurations = new ConcurrentHashMap<>();
	private final Set<String> failedMethodNames = ConcurrentHashMap.newKeySet();

	/** Per-class start times for parallel-safe class boundary tracking. */
	private final Map<String, Long> classStartTimes = new ConcurrentHashMap<>();
	/** Debug mode — skip duration recording to avoid inflated EMA values. */
	private volatile boolean debugMode;

	private volatile boolean finishedNormally;
	private final AtomicBoolean initialized = new AtomicBoolean();
	private final AtomicBoolean finished = new AtomicBoolean();
	private Thread shutdownHook;

	@Override
	public void onStart(ITestContext context) {
		// Guard against multiple <test> tags calling onStart multiple times
		if (!initialized.compareAndSet(false, true))
			return;

		learnMode = "true".equals(System.getProperty(TestOrderConfig.LEARN));
		String instrumentationMode = System.getProperty(TestOrderConfig.INSTRUMENTATION_MODE);
		fullMethodMode = "FULL_METHOD".equals(instrumentationMode) || "FULL_MEMBER".equals(instrumentationMode);

		// L17: Detect debug mode — skip duration recording to avoid inflated EMA values
		debugMode = isDebugMode();
		if (debugMode) {
			TestOrderLogger
					.info("[telemetry] Debug mode detected — duration recording disabled to avoid EMA inflation.");
		}

		// Resolve state path from system property or classpath config (matching JUnit
		// TelemetryListener behavior)
		TestOrderConfigResolver configResolver = new TestOrderConfigResolver(
				Thread.currentThread().getContextClassLoader());
		statePath = configResolver.getConfig(TestOrderConfig.STATE_PATH);

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
		// Track execution order and start time per class (thread-safe for parallel
		// modes)
		if (executionOrderSet.add(className)) {
			executionOrder.add(className);
			if (!debugMode) {
				classStartTimes.putIfAbsent(className, System.nanoTime());
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
			pendingMethodDurations
					.computeIfAbsent(className + "#" + methodName, k -> Collections.synchronizedList(new ArrayList<>()))
					.add(durationMs);
		}
	}

	@Override
	public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
		onTestEnd(result);
	}

	@Override
	public void onFinish(ITestContext context) {
		// Guard against multiple <test> tags calling onFinish multiple times
		if (!finished.compareAndSet(false, true))
			return;

		// Record class-level durations for all tracked classes.
		// In parallel execution, wall-clock (now - startTime) includes concurrent
		// work from other classes, inflating duration and skewing speed scores.
		// Use sum of per-method durations when available (accurate under parallelism),
		// falling back to wall-clock for classes without method-level data.
		if (!debugMode) {
			long now = System.nanoTime();
			for (var entry : classStartTimes.entrySet()) {
				String className = entry.getKey();
				// Sum method durations for this class
				long methodSum = 0;
				boolean hasMethodData = false;
				for (var mEntry : pendingMethodDurations.entrySet()) {
					if (mEntry.getKey().startsWith(className + "#")) {
						for (Long d : mEntry.getValue()) {
							methodSum += d;
						}
						hasMethodData = true;
					}
				}
				long durationMs;
				if (hasMethodData) {
					durationMs = methodSum;
				} else {
					durationMs = TimeUnit.NANOSECONDS.toMillis(now - entry.getValue());
				}
				pendingDurations.computeIfAbsent(className, k -> Collections.synchronizedList(new ArrayList<>()))
						.add(durationMs);
			}
		}
		// End learn-mode tracking for all discovered classes
		if (learnMode && bridge.isAvailable()) {
			for (String className : executionOrderSet) {
				bridge.callEndTestClass(className);
			}
		}

		persistState();

		// Print summary (R18-2: parity with JUnit5 TelemetryListener summary)
		if (!executionOrder.isEmpty()) {
			if (failedClassNames.isEmpty()) {
				TestOrderLogger.info("{} tests ran in priority order — all passed", executionOrder.size());
			} else {
				TestOrderLogger.info("{} tests ran in priority order — {} failed", executionOrder.size(),
						failedClassNames.size());
			}
		}

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
		if (!debugMode) {
			long durationMs = result.getEndMillis() - result.getStartMillis();
			pendingMethodDurations
					.computeIfAbsent(className + "#" + methodName, k -> Collections.synchronizedList(new ArrayList<>()))
					.add(durationMs);
		}
	}

	/**
	 * Detects whether the JVM is running in debug mode (-agentlib:jdwp or
	 * -Xrunjdwp). Duration recording is skipped in debug mode to avoid inflating
	 * EMA values with breakpoint-inflated timings.
	 */
	private static boolean isDebugMode() {
		for (String arg : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
			if (arg.startsWith("-agentlib:jdwp") || arg.startsWith("-Xrunjdwp")) {
				return true;
			}
		}
		return false;
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

				if (!executionOrder.isEmpty()) {
					TestOrderState.RunRecord record = TestOrderState.buildRunRecord(executionOrder, failedClassNames);
					state.addRunRecord(record);
					boolean isLearnRun = Boolean.parseBoolean(System.getProperty(TestOrderConfig.LEARN, "false"));
					if (!isLearnRun) {
						state.incrementRunsSinceLearn();
					}
					if (record.totalFailures() > 0) {
						TestOrderLogger.info("Run APFD: {}% (first failure at position {}/{})",
								String.format(java.util.Locale.US, "%.1f", record.apfd() * 100),
								record.firstFailurePosition() + 1, record.totalTests());
						if (!isLearnRun) {
							logTimeSaved(state, record);
						}
					}
				}
				state.save(stateFile);
				return state;
			});
			resetPending = TestOrderState.hasPendingData();
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

	/**
	 * Calculates and logs the estimated time saved by prioritized ordering.
	 */
	private void logTimeSaved(TestOrderState lockedState, TestOrderState.RunRecord record) {
		if (record.firstFailurePosition() < 0 || executionOrder.size() <= 1) {
			return;
		}

		String firstFailedClass = null;
		for (int i = 0; i < executionOrder.size(); i++) {
			if (failedClassNames.contains(executionOrder.get(i))) {
				firstFailedClass = executionOrder.get(i);
				break;
			}
		}
		if (firstFailedClass == null) {
			return;
		}

		List<String> defaultOrder = new ArrayList<>(executionOrder);
		defaultOrder.sort(String::compareTo);
		int defaultFailPos = defaultOrder.indexOf(firstFailedClass);
		if (defaultFailPos < 0) {
			return;
		}

		// No improvement — first failure already at same or worse position
		if (record.firstFailurePosition() >= defaultFailPos) {
			return;
		}

		java.util.function.Function<String, Long> durationOf = tc -> {
			List<Long> measured = pendingDurations.get(tc);
			if (measured != null) {
				long total = 0;
				synchronized (measured) {
					for (Long d : measured)
						total += d;
				}
				return total;
			}
			return lockedState.getDuration(tc, 0);
		};

		long prioritizedDurationBeforeFailure = 0;
		for (int i = 0; i < record.firstFailurePosition(); i++) {
			prioritizedDurationBeforeFailure += durationOf.apply(executionOrder.get(i));
		}

		long defaultDurationBeforeFailure = 0;
		for (int i = 0; i < defaultFailPos; i++) {
			defaultDurationBeforeFailure += durationOf.apply(defaultOrder.get(i));
		}

		long savedMs = defaultDurationBeforeFailure - prioritizedDurationBeforeFailure;
		int positionsEarlier = defaultFailPos - record.firstFailurePosition();
		if (savedMs > 0) {
			TestOrderLogger.info("\u23f1\ufe0f  Estimated time saved: {} (based on default execution order)",
					formatDuration(savedMs));
		} else {
			// Position improved but durations are zero/unknown
			TestOrderLogger.info(
					"\u23f1\ufe0f  First failure surfaced {} positions earlier than default order",
					positionsEarlier);
		}
	}

	private static String formatDuration(long ms) {
		if (ms < 1000) {
			return ms + "ms";
		}
		long seconds = ms / 1000;
		if (seconds < 60) {
			return seconds + "s";
		}
		long minutes = seconds / 60;
		long remainingSeconds = seconds % 60;
		if (minutes < 60) {
			return String.format("%dm %02ds", minutes, remainingSeconds);
		}
		long hours = minutes / 60;
		long remainingMinutes = minutes % 60;
		return String.format("%dh %02dm %02ds", hours, remainingMinutes, remainingSeconds);
	}
}
