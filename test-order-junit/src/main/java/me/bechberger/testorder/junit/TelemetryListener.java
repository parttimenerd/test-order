package me.bechberger.testorder.junit;

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

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import me.bechberger.testorder.PersistenceSupport;
import me.bechberger.testorder.TelemetryPersistence;
import me.bechberger.testorder.TestOrderConfig;
import me.bechberger.testorder.TestOrderConfigResolver;
import me.bechberger.testorder.TestOrderLogger;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.UsageStoreReflectionBridge;
import me.bechberger.testorder.ml.MLHistoryPersistence;
import me.bechberger.testorder.ml.MLRunRecord;
import me.bechberger.testorder.ml.MLTestOutcome;

/**
 * JUnit Platform TestExecutionListener that:
 * <ul>
 * <li>In <b>learn mode</b>: tracks test class boundaries and communicates them
 * to the agent's UsageStore.</li>
 * <li>In <b>order mode</b>: records test failures to
 * {@code .test-order-failures} for future prioritization.</li>
 * </ul>
 * <p>
 * In learn mode, calls {@code UsageStore.startTestClass/endTestClass} via
 * reflection to support per-test-class dependency tracking. Only activates
 * learn-mode tracking when system property {@code testorder.learn} is set to
 * {@code "true"}. Auto-discovered via
 * {@code META-INF/services/org.junit.platform.launcher.TestExecutionListener}.
 */
public class TelemetryListener implements TestExecutionListener {

	private boolean learnMode;
	private boolean fullMethodMode;
	private boolean dryRunMode;
	private boolean debugMode;
	private UsageStoreReflectionBridge bridge;

	// state tracking (active when state path is set)
	private TestOrderState state;
	private String statePath;
	private final Map<String, Long> classStartTimes = new ConcurrentHashMap<>();
	private final Map<String, Long> methodStartTimes = new ConcurrentHashMap<>();

	// run quality tracking — use thread-safe collections because JUnit Platform
	// delivers listener callbacks from the executing thread when
	// @Execution(CONCURRENT) is active (method-level or class-level parallelism).
	private final Set<String> executionOrderSet = ConcurrentHashMap.newKeySet();
	private final List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
	private final Set<String> failedClassNames = ConcurrentHashMap.newKeySet();
	private final Map<String, List<Long>> pendingDurations = new ConcurrentHashMap<>();
	private final Map<String, List<Long>> pendingMethodDurations = new ConcurrentHashMap<>();
	private final Set<String> failedMethodNames = ConcurrentHashMap.newKeySet();
	private final Set<String> warnedConcurrentClasses = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean warnedGlobalParallel = new AtomicBoolean(false);

	// ML history: maps test class → failure exception class name (for the most
	// recent failure in this run)
	private final Map<String, String> failureTypeMap = new ConcurrentHashMap<>();
	private boolean mlEnabled;

	// Tracks method keys (className#methodName) that are currently being tracked
	// via a container node (e.g., @ParameterizedTest template). Child invocations
	// of these containers should NOT start/end their own method tracking.
	private final Set<String> containerTrackedMethods = ConcurrentHashMap.newKeySet();

	/**
	 * Tracks whether testPlanExecutionFinished ran; used by the shutdown hook to
	 * avoid double-save.
	 */
	private volatile boolean finishedNormally;
	private Thread shutdownHook;

	@Override
	public void testPlanExecutionStarted(TestPlan testPlan) {
		learnMode = "true".equals(System.getProperty(TestOrderConfig.LEARN));
		String instrumentationMode = System.getProperty(TestOrderConfig.INSTRUMENTATION_MODE);
		fullMethodMode = "FULL_METHOD".equals(instrumentationMode) || "FULL_MEMBER".equals(instrumentationMode);

		// M3: Detect dry-run mode — skip all recording
		dryRunMode = "true".equalsIgnoreCase(System.getProperty("junit.platform.execution.dryRun.enabled"));
		if (dryRunMode) {
			TestOrderLogger.info("[telemetry] Dry-run mode detected — skipping all telemetry recording.");
			return;
		}

		// L17: Detect debug mode — skip duration recording to avoid inflated EMA values
		debugMode = isDebugMode();
		if (debugMode) {
			TestOrderLogger
					.info("[telemetry] Debug mode detected — duration recording disabled to avoid EMA inflation.");
		}

		if (learnMode) {
			bridge = new UsageStoreReflectionBridge(fullMethodMode);
			bridge.init();
		}

		// load state file path for failure + duration tracking
		// Use TestOrderConfigResolver to check both system properties and classpath
		// config file — in order mode the path is written to
		// testorder-config.properties
		// on the classpath, not passed as a system property.
		TestOrderConfigResolver configResolver = new TestOrderConfigResolver(
				Thread.currentThread().getContextClassLoader());
		statePath = configResolver.getConfig(TestOrderConfig.STATE_PATH);

		// Register a JVM shutdown hook so accumulated durations/failures are not
		// lost when the JVM terminates abnormally (e.g. OOM, kill signal) before
		// testPlanExecutionFinished() runs.
		finishedNormally = false;
		shutdownHook = new Thread(this::emergencySave, "test-order-emergency-save");
		Runtime.getRuntime().addShutdownHook(shutdownHook);

		// ML history: check if ML is enabled (opt-in)
		mlEnabled = "true".equalsIgnoreCase(configResolver.getConfig(TestOrderConfig.ML_ENABLED));
	}

	/**
	 * Detects whether the JVM is running in debug mode (-agentlib:jdwp or
	 * -Xrunjdwp).
	 */
	private static boolean isDebugMode() {
		for (String arg : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
			if (arg.startsWith("-agentlib:jdwp") || arg.startsWith("-Xrunjdwp")) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void executionStarted(TestIdentifier testIdentifier) {
		if (dryRunMode || isSuiteEngineNode(testIdentifier)) {
			return;
		}
		testIdentifier.getSource().ifPresent(source -> {
			if (source instanceof ClassSource classSource) {
				String name = classSource.getClassName();
				if (name == null)
					return; // guard against pathological custom engines
				maybeWarnConcurrentExecution(name);

				// track start time for duration (local map operation, very fast)
				if (!debugMode) {
					classStartTimes.put(testIdentifier.getUniqueId(), System.nanoTime());
				}

				// track execution order for run quality (O(1) dedup with HashSet)
				// Normalize to top-level class so inner/nested classes are attributed
				// to the same class used by failedClassNames (also normalized).
				String topLevel = TestOrderConfigResolver.toTopLevelClassName(name);
				if (executionOrderSet.add(topLevel)) {
					executionOrder.add(topLevel);
				}

				// In learn mode: call agent to record per-test-class boundary
				// Do this AFTER timing starts so agent overhead isn't counted
				if (learnMode && bridge.isAvailable()) {
					bridge.callStartTestClass(name);
				}
			} else if (source instanceof MethodSource methodSource) {
				if (methodSource.getClassName() == null || methodSource.getMethodName() == null)
					return;
				// track method-level start time
				String methodKey = methodSource.getClassName() + "#" + methodSource.getMethodName();
				if (!debugMode) {
					methodStartTimes.put(testIdentifier.getUniqueId(), System.nanoTime());
				}

				// In FULL_METHOD and FULL_MEMBER modes: start per-method dependency recording.
				// For @ParameterizedTest and @TestTemplate (type=CONTAINER), start tracking
				// on the container node so we capture @MethodSource provider calls and
				// argument resolution which happen BEFORE individual invocations fire.
				// For regular @Test (type=TEST), start as before.
				// Skip child invocations of a container (they share the parent's tracker).
				if (fullMethodMode && learnMode && bridge.isAvailable()) {
					if (!testIdentifier.getType().isTest()) {
						// Container node (e.g., @ParameterizedTest template) — start tracking early
						containerTrackedMethods.add(methodKey);
						bridge.callStartTestMethod(methodSource.getClassName(), methodSource.getMethodName());
					} else if (!containerTrackedMethods.contains(methodKey)) {
						// Regular @Test (not a child of a container) — start tracking normally
						bridge.callStartTestMethod(methodSource.getClassName(), methodSource.getMethodName());
					}
					// else: child invocation of a container — skip, parent already tracks
				}
			}
		});
	}

	@Override
	public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
		if (dryRunMode || isSuiteEngineNode(testIdentifier)) {
			return;
		}
		if (learnMode && bridge.isAvailable()) {
			testIdentifier.getSource().ifPresent(source -> {
				if (source instanceof ClassSource) {
					bridge.callEndTestClass(((ClassSource) source).getClassName());
				}
			});
		}
		// M9: Only record FAILED status as failures — ABORTED (assumptions) are not
		// test failures
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
					// so that @Nested class failures are attributed to the outer class.
					// TestScorer has a fallback that checks the top-level name when
					// no failure is found for the exact (nested) class name.
					String topLevel = TestOrderConfigResolver.toTopLevelClassName(className);
					failedClassNames.add(topLevel);
					// Record method-level failure (preserves nested class for method scoring)
					if (methodName != null) {
						String methodKey = className + "#" + methodName;
						failedMethodNames.add(methodKey);
					}
					// ML: capture failure exception type
					if (mlEnabled) {
						result.getThrowable().ifPresent(throwable -> {
							failureTypeMap.put(topLevel, throwable.getClass().getName());
						});
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
					pendingDurations.computeIfAbsent(classSource.getClassName(),
							ignored -> Collections.synchronizedList(new ArrayList<>())).add(duration);
				}
			} else if (source instanceof MethodSource methodSource) {
				// In FULL_METHOD and FULL_MEMBER modes: end per-method dependency recording.
				// Only end tracking for the same node that started it: either the container
				// node (@ParameterizedTest/@TestTemplate) or a leaf @Test method.
				// Skip end for child invocations since they didn't start their own tracker.
				if (fullMethodMode && learnMode && bridge.isAvailable()) {
					String methodKey = methodSource.getClassName() + "#" + methodSource.getMethodName();
					if (!testIdentifier.getType().isTest()) {
						// Container node finishing — end tracking and remove from tracked set
						containerTrackedMethods.remove(methodKey);
						bridge.callEndTestMethod();
					} else if (!containerTrackedMethods.contains(methodKey)) {
						// Regular @Test finishing — end tracking normally
						bridge.callEndTestMethod();
					}
					// else: child invocation finishing — skip, container will end tracking
				}

				String methodKey = methodSource.getClassName() + "#" + methodSource.getMethodName();
				Long start = methodStartTimes.remove(testIdentifier.getUniqueId());
				if (start != null) {
					long duration = elapsedMillis(start);
					if (testIdentifier.getType().isTest()) {
						// Record duration for actual test invocations (leaf @Test,
						// @ParameterizedTest invocations, @RepeatedTest invocations).
						pendingMethodDurations
								.computeIfAbsent(methodKey, ignored -> Collections.synchronizedList(new ArrayList<>()))
								.add(duration);
					} else if (!pendingMethodDurations.containsKey(methodKey)) {
						// Container with no child durations recorded (e.g. @TestFactory whose
						// DynamicTests lack MethodSource). Record the container's total duration
						// so this method gets speed scoring and stops receiving the perpetual
						// "new method" bonus.
						pendingMethodDurations
								.computeIfAbsent(methodKey, ignored -> Collections.synchronizedList(new ArrayList<>()))
								.add(duration);
					}
					// else: Container with child durations (@ParameterizedTest/@RepeatedTest)
					// — skip to avoid double-counting (children already recorded theirs).
				}
			}
		});
	}

	@Override
	public void executionSkipped(TestIdentifier testIdentifier, String reason) {
		// L18: Track skipped tests for execution order visibility (they were discovered
		// but not run)
		// We don't record durations or failures for skipped tests, but we note them
		// in the execution order so the run record reflects the full test set.
		if (dryRunMode) {
			return;
		}
		testIdentifier.getSource().ifPresent(source -> {
			String className = null;
			if (source instanceof ClassSource classSource) {
				className = classSource.getClassName();
			} else if (source instanceof MethodSource methodSource) {
				className = methodSource.getClassName();
			}
			if (className != null) {
				String topLevel = TestOrderConfigResolver.toTopLevelClassName(className);
				if (executionOrderSet.add(topLevel)) {
					executionOrder.add(topLevel);
				}
			}
		});
	}

	@Override
	public void testPlanExecutionFinished(TestPlan testPlan) {
		if (dryRunMode) {
			// Remove shutdown hook and return — nothing to save
			finishedNormally = true;
			if (shutdownHook != null) {
				try {
					Runtime.getRuntime().removeShutdownHook(shutdownHook);
				} catch (IllegalStateException ignored) {
				}
			}
			return;
		}

		// resolve state path: prefer system property, fall back to pending (set by
		// PriorityClassOrderer)
		String effectiveStatePath = statePath;
		if (effectiveStatePath == null || effectiveStatePath.isEmpty()) {
			effectiveStatePath = TestOrderState.getPendingStatePath();
		}

		// L26: Warn when the test plan had classes but none actually executed
		if (executionOrder.isEmpty() && pendingDurations.isEmpty()) {
			TestOrderLogger.warn("[telemetry] No tests were executed — state will not be updated. "
					+ "This may indicate all tests were filtered, disabled, or a DiscoveryIssue prevented execution.");
		}

		boolean resetPending = false;
		boolean stateSaved = false;
		if (effectiveStatePath != null && !effectiveStatePath.isEmpty()) {
			Path stateFile = Path.of(effectiveStatePath);
			try {
				state = PersistenceSupport.withFileLock(stateFile, () -> {
					TestOrderState lockedState = TelemetryPersistence.loadStateOrEmpty(stateFile);
					TelemetryPersistence.applyHistoryMaxRuns(lockedState);
					TelemetryPersistence.applyPendingTelemetry(lockedState, pendingDurations, failedClassNames,
							pendingMethodDurations, failedMethodNames);
					if (!executionOrder.isEmpty()) {
						TestOrderState.RunRecord record = TestOrderState.buildRunRecord(executionOrder,
								failedClassNames);
						lockedState.addRunRecord(record);
						boolean isLearnRun = Boolean.parseBoolean(System.getProperty(TestOrderConfig.LEARN, "false"));
						if (!isLearnRun) {
							lockedState.incrementRunsSinceLearn();
						}
						if (record.totalFailures() > 0) {
							TestOrderLogger.info("Run APFD: {}% (first failure at position {}/{})",
									String.format(java.util.Locale.US, "%.1f", record.apfd() * 100),
									record.firstFailurePosition() + 1, record.totalTests());
							// Estimate time saved: compare cumulative duration before first
							// failure in default (alphabetical) order vs. prioritized order
							if (!isLearnRun) {
								logTimeSaved(lockedState, record);
							}
						} else if (!isLearnRun && record.totalTests() > 1) {
							TestOrderLogger.info("{} tests ran in priority order — all passed", record.totalTests());
						}
					}
					finishedNormally = true;
					lockedState.save(stateFile);
					return lockedState;
				});
				stateSaved = true;
				resetPending = TestOrderState.hasPendingData();
			} catch (IOException e) {
				TestOrderLogger.error("Failed to save state: {}", e.getMessage());
			}

			// ML history: persist run data for failure prediction model (opt-in)
			if (mlEnabled && !executionOrder.isEmpty()) {
				writeMLHistory(effectiveStatePath);
			}
		}

		// Only clear data and remove the shutdown hook if state was saved.
		// If the save failed, keep data in memory so the shutdown hook can
		// attempt an emergency recovery save.
		if (stateSaved) {

			if (resetPending) {
				TestOrderState.resetPending();
			}

			// Clear accumulated data after persisting to prevent double-counting
			// when Surefire re-runs the test plan (rerunFailingTestsCount > 0).
			// Without this, a rerun plan execution would re-apply all durations and
			// failures from the original plan.
			pendingDurations.clear();
			pendingMethodDurations.clear();
			failedClassNames.clear();
			failedMethodNames.clear();
			failureTypeMap.clear();
			executionOrder.clear();
			executionOrderSet.clear();
		}

		// Always clear method ordering state — it's JVM-level state that should
		// not leak between test plan executions regardless of save outcome.
		PriorityMethodOrderer.clearPendingState();
		if (shutdownHook != null) {
			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException ignored) {
				/* JVM already shutting down */ }
		}
	}

	/**
	 * Emergency save invoked from the JVM shutdown hook when
	 * {@code testPlanExecutionFinished()} was never called (e.g. OOM, SIGTERM).
	 * Saves whatever durations and failures have been accumulated so far.
	 * <p>
	 * Snapshots all collections to avoid races with a concurrent
	 * {@code testPlanExecutionFinished()} call clearing the live maps.
	 */
	private void emergencySave() {
		if (finishedNormally)
			return;
		// Snapshot to avoid ConcurrentModificationException if the main thread
		// is clearing these maps in testPlanExecutionFinished().
		Map<String, List<Long>> durSnap = snapshotMapOfLists(pendingDurations);
		Set<String> failSnap = new java.util.HashSet<>(failedClassNames);
		Map<String, List<Long>> methDurSnap = snapshotMapOfLists(pendingMethodDurations);
		Set<String> methFailSnap = new java.util.HashSet<>(failedMethodNames);
		TelemetryPersistence.emergencySave(statePath, durSnap, failSnap, methDurSnap, methFailSnap);
	}

	/**
	 * Calculates and logs the estimated time saved by prioritized ordering.
	 * Compares the cumulative duration of tests that ran before the first failure
	 * in the prioritized order against what would have run in the default
	 * (alphabetical) order.
	 */
	private void logTimeSaved(TestOrderState lockedState, TestOrderState.RunRecord record) {
		if (record.firstFailurePosition() < 0 || executionOrder.size() <= 1) {
			return;
		}

		// Find the first failed test class
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

		// Default order: alphabetical by class name
		List<String> defaultOrder = new ArrayList<>(executionOrder);
		defaultOrder.sort(String.CASE_INSENSITIVE_ORDER);
		int defaultFailPos = defaultOrder.indexOf(firstFailedClass);
		if (defaultFailPos < 0) {
			return;
		}

		// No improvement — first failure already at same or worse position
		if (record.firstFailurePosition() >= defaultFailPos) {
			return;
		}

		// Compute duration for each test class: use actual measurement from this run,
		// fall back to EMA from state
		java.util.function.Function<String, Long> durationOf = tc -> {
			List<Long> measured = pendingDurations.get(tc);
			if (measured != null) {
				return sumDurations(measured);
			}
			// Check nested class durations (Outer$Inner stored under Outer)
			long nested = 0;
			for (var entry : pendingDurations.entrySet()) {
				if (entry.getKey().startsWith(tc + "$")) {
					nested += sumDurations(entry.getValue());
				}
			}
			if (nested > 0) {
				return nested;
			}
			return lockedState.getDuration(tc, 0);
		};

		// Cumulative duration before first failure in prioritized (actual) order
		long prioritizedDurationBeforeFailure = 0;
		for (int i = 0; i < record.firstFailurePosition(); i++) {
			prioritizedDurationBeforeFailure += durationOf.apply(executionOrder.get(i));
		}

		// Cumulative duration before first failure in default order
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
			TestOrderLogger.info("\u23f1\ufe0f  First failure surfaced {} positions earlier than default order",
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

	/**
	 * Writes ML run history for failure prediction. Resolves the ML history
	 * directory from config (defaults to {@code .test-order-ml/} next to the state
	 * file) and appends a run record.
	 */
	private void writeMLHistory(String effectiveStatePath) {
		try {
			Path stateDir = Path.of(effectiveStatePath).getParent();
			TestOrderConfigResolver mlConfig = new TestOrderConfigResolver(
					Thread.currentThread().getContextClassLoader());
			String historyDirStr = mlConfig.getConfig(TestOrderConfig.ML_HISTORY_DIR);
			Path historyDir = historyDirStr != null
					? Path.of(historyDirStr)
					: (stateDir != null ? stateDir.resolve("ml") : Path.of(".test-order-ml"));
			Path historyFile = historyDir.resolve("history.lz4");
			int maxRuns = 2000;
			String maxRunsStr = mlConfig.getConfig(TestOrderConfig.ML_HISTORY_MAX_RUNS);
			if (maxRunsStr != null) {
				try {
					maxRuns = Integer.parseInt(maxRunsStr.trim());
				} catch (NumberFormatException ignored) {
				}
			}

			// Resolve changed classes from config (set by PrepareMojo)
			List<String> changedClasses = parseCSV(mlConfig.getConfig(TestOrderConfig.CHANGED_CLASSES));
			List<String> changedTestClasses = parseCSV(mlConfig.getConfig(TestOrderConfig.CHANGED_TEST_CLASSES));

			// Build ML outcomes from execution data.
			// pendingDurations is keyed by raw class name (e.g. "Outer$Inner")
			// but executionOrder contains top-level class names (e.g. "Outer").
			// Aggregate durations from both the top-level name and any nested
			// class variants so nested class test durations are not lost.
			List<MLTestOutcome> outcomes = new java.util.ArrayList<>();
			for (String testClass : executionOrder) {
				boolean failed = failedClassNames.contains(testClass);
				long durationMs = sumDurations(pendingDurations.get(testClass));
				// Also collect durations stored under nested class names (Outer$...)
				for (var durEntry : pendingDurations.entrySet()) {
					String rawName = durEntry.getKey();
					if (!rawName.equals(testClass) && rawName.startsWith(testClass + "$")) {
						durationMs += sumDurations(durEntry.getValue());
					}
				}
				String failureType = failed ? failureTypeMap.get(testClass) : null;
				outcomes.add(new MLTestOutcome(testClass, failed, durationMs, failureType));
			}

			int totalFailures = (int) outcomes.stream().filter(MLTestOutcome::failed).count();
			MLRunRecord record = new MLRunRecord(System.currentTimeMillis(), changedClasses, changedTestClasses,
					outcomes.size(), totalFailures, outcomes);
			MLHistoryPersistence.append(historyFile, record, maxRuns);
			TestOrderLogger.debug("[ml] Saved ML history ({} outcomes, {} failures) to {}", outcomes.size(),
					totalFailures, historyFile);
		} catch (Exception e) {
			TestOrderLogger.warn("[ml] Failed to write ML history: {}", e.getMessage());
		}
	}

	private static long sumDurations(List<Long> durations) {
		if (durations == null) {
			return 0;
		}
		long total = 0;
		synchronized (durations) {
			for (Long d : durations) {
				total += d;
			}
		}
		return total;
	}

	private static List<String> parseCSV(String csv) {
		if (csv == null || csv.isBlank()) {
			return List.of();
		}
		return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
	}

	private static Map<String, List<Long>> snapshotMapOfLists(Map<String, List<Long>> source) {
		Map<String, List<Long>> snapshot = new java.util.HashMap<>();
		for (var entry : source.entrySet()) {
			List<Long> list = entry.getValue();
			synchronized (list) {
				snapshot.put(entry.getKey(), new java.util.ArrayList<>(list));
			}
		}
		return snapshot;
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

	/**
	 * Returns true if this test identifier originates from the JUnit Platform Suite
	 * engine. Suite-engine tests are duplicates of directly-discovered tests —
	 * recording them would double-count failures and inflate duration EMA (C6).
	 */
	private static boolean isSuiteEngineNode(TestIdentifier testIdentifier) {
		String uniqueId = testIdentifier.getUniqueId();
		return uniqueId.contains("[engine:junit-platform-suite]");
	}

	private static long elapsedMillis(long startNanos) {
		long elapsedNanos = System.nanoTime() - startNanos;
		return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, elapsedNanos));
	}

	private void maybeWarnConcurrentExecution(String className) {
		// One-time check: warn if global parallel execution is enabled via system
		// properties or junit-platform.properties (no annotation needed)
		if (warnedGlobalParallel.compareAndSet(false, true)) {
			String globalEnabled = System.getProperty("junit.jupiter.execution.parallel.enabled");
			String globalMode = System.getProperty("junit.jupiter.execution.parallel.mode.default");
			if ("true".equalsIgnoreCase(globalEnabled) && "concurrent".equalsIgnoreCase(globalMode)) {
				TestOrderLogger
						.warn("Global parallel execution is enabled (junit.jupiter.execution.parallel.enabled=true, "
								+ "mode.default=concurrent); learn-mode dependency tracking may be inaccurate");
			}
		}
		if (!warnedConcurrentClasses.add(className)) {
			return;
		}
		try {
			Class<?> testClass = Class.forName(className);
			// Use reflection to avoid hard dependency on jupiter-api parallel classes
			// (which may not be on the classpath for Vintage-only projects)
			Class<?> executionClass = Class.forName("org.junit.jupiter.api.parallel.Execution");
			Object execution = testClass
					.getAnnotation(executionClass.asSubclass(java.lang.annotation.Annotation.class));
			if (execution != null) {
				Object mode = executionClass.getMethod("value").invoke(execution);
				if ("CONCURRENT".equals(mode.toString())) {
					TestOrderLogger.warn(
							"Test class {} uses @Execution(CONCURRENT); learn-mode dependency tracking may be inaccurate",
							className);
				}
			}
		} catch (ClassNotFoundException ignored) {
			// Jupiter parallel API not on classpath — leave className in
			// warnedConcurrentClasses so we don't retry on every test method.
		} catch (ReflectiveOperationException ignored) {
			// Jupiter parallel API not available or annotation not present
		}
	}
}
