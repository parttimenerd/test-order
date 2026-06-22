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

import me.bechberger.testorder.PartialRunAggregator;
import me.bechberger.testorder.PersistenceSupport;
import me.bechberger.testorder.TelemetryPersistence;
import me.bechberger.testorder.TestOrderConfig;
import me.bechberger.testorder.TestOrderConfigResolver;
import me.bechberger.testorder.TestOrderLogger;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.UsageStoreReflectionBridge;

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
	// build-session aggregation: when set, per-fork records go to pending-runs dir
	private String buildId;
	private String pendingRunsDir;
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
		// Clear any static state left in FlakyRetryExtension by a previous test
		// plan in the same JVM (Gradle daemon reuses the JVM across builds). The
		// extension caches the FLAKY set, retry/quarantine counts, and parsed
		// system properties — all must reset per plan so module B doesn't see
		// module A's data.
		try {
			FlakyRetryExtension.resetForTesting();
		} catch (NoClassDefFoundError ignored) {
			// FlakyRetryExtension is in the same artifact, so this should never
			// happen — defensive against repackaging.
		}

		learnMode = "true".equals(System.getProperty(TestOrderConfig.LEARN));
		String instrumentationMode = System.getProperty(TestOrderConfig.INSTRUMENTATION_MODE);
		fullMethodMode = "METHOD".equalsIgnoreCase(instrumentationMode)
				|| "MEMBER".equalsIgnoreCase(instrumentationMode);

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
			// Check for offline mode: if mapping file is set, bootstrap from it
			String offlineMappingPath = System.getProperty(TestOrderConfig.OFFLINE_MAPPING);
			if (offlineMappingPath != null && !offlineMappingPath.isBlank()) {
				bootstrapOfflineRuntime(offlineMappingPath);
			}
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

		// Build-session aggregation: when buildId is set, write per-fork partial
		// records to pending-runs/ instead of writing directly to the state file.
		buildId = configResolver.getConfig(TestOrderConfig.BUILD_ID);
		pendingRunsDir = configResolver.getConfig(TestOrderConfig.PENDING_RUNS_DIR);

		// Register a JVM shutdown hook so accumulated durations/failures are not
		// lost when the JVM terminates abnormally (e.g. OOM, kill signal) before
		// testPlanExecutionFinished() runs. emergencySave is a no-op when statePath
		// is absent, so registering unconditionally is safe.
		finishedNormally = false;
		shutdownHook = new Thread(this::emergencySave, "test-order-emergency-save");
		Runtime.getRuntime().addShutdownHook(shutdownHook);
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

	/**
	 * Bootstraps the offline instrumentation runtime by loading the class-id
	 * mapping file and configuring UsageStore. Called when
	 * {@code testorder.offline.mapping} system property is set.
	 *
	 * Throws RuntimeException on failure to prevent silent data loss. If this
	 * method fails, test execution will not be instrumented and no .deps files will
	 * be written, making data loss visible to the user.
	 */
	private void bootstrapOfflineRuntime(String mappingPath) {
		try {
			String outputDir = System.getProperty(TestOrderConfig.OFFLINE_OUTPUT, "");
			String indexFile = System.getProperty(TestOrderConfig.OFFLINE_INDEX_FILE, "");
			Class<?> bootstrapClass = resolveClass("me.bechberger.testorder.agent.runtime.OfflineRuntimeBootstrap");
			java.lang.reflect.Method initMethod = bootstrapClass.getMethod("init", java.nio.file.Path.class,
					String.class, String.class, boolean.class);
			initMethod.invoke(null, java.nio.file.Path.of(mappingPath), outputDir, indexFile, fullMethodMode);
			TestOrderLogger.info("[telemetry] Offline runtime bootstrapped from: " + mappingPath);
		} catch (java.lang.reflect.InvocationTargetException e) {
			// Unwrap the actual exception thrown by OfflineRuntimeBootstrap.init()
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			String msg = "[test-order] FATAL: Failed to bootstrap offline runtime from " + mappingPath
					+ " — test execution will not be instrumented and no dependency data will be collected. "
					+ "Cause: " + cause.getMessage();
			TestOrderLogger.error(msg);
			throw new RuntimeException(msg, cause);
		} catch (ClassNotFoundException e) {
			String msg = "[test-order] FATAL: OfflineRuntimeBootstrap class not found — agent JAR may not be on the classpath. "
					+ "Cause: " + e.getMessage();
			TestOrderLogger.error(msg);
			throw new RuntimeException(msg, e);
		} catch (Exception e) {
			String msg = "[test-order] FATAL: Failed to bootstrap offline runtime from " + mappingPath
					+ " — test execution will not be instrumented and no dependency data will be collected. "
					+ "Cause: " + e.getMessage();
			TestOrderLogger.error(msg);
			throw new RuntimeException(msg, e);
		}
	}

	/**
	 * Resolves a class by name, trying bootstrap classloader first (online mode)
	 * then context/system classloader (offline mode).
	 */
	private static Class<?> resolveClass(String className) throws ClassNotFoundException {
		try {
			return Class.forName(className, true, null);
		} catch (ClassNotFoundException e) {
			ClassLoader cl = Thread.currentThread().getContextClassLoader();
			if (cl != null) {
				try {
					return Class.forName(className, true, cl);
				} catch (ClassNotFoundException ignored) {
				}
			}
			return Class.forName(className);
		}
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

				// In METHOD and MEMBER modes: start per-method dependency recording.
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
					// so that @Nested class failures are attributed to the outer class
					// (PriorityClassOrderer looks up scores by top-level class name)
					failedClassNames.add(TestOrderConfigResolver.toTopLevelClassName(className));
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
					pendingDurations.computeIfAbsent(classSource.getClassName(),
							ignored -> Collections.synchronizedList(new ArrayList<>(1))).add(duration);
				}
			} else if (source instanceof MethodSource methodSource) {
				// In METHOD and MEMBER modes: end per-method dependency recording.
				// Only end tracking for the same node that started it: either the container
				// node (@ParameterizedTest/@TestTemplate) or a leaf @Test method.
				// Skip end for child invocations since they didn't start their own tracker.
				String methodKey = methodSource.getClassName() + "#" + methodSource.getMethodName();
				if (fullMethodMode && learnMode && bridge.isAvailable()) {
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

				Long start = methodStartTimes.remove(testIdentifier.getUniqueId());
				if (start != null) {
					long duration = elapsedMillis(start);
					if (testIdentifier.getType().isTest()) {
						// Record duration for actual test invocations (leaf @Test,
						// @ParameterizedTest invocations, @RepeatedTest invocations).
						pendingMethodDurations
								.computeIfAbsent(methodKey, ignored -> Collections.synchronizedList(new ArrayList<>(1)))
								.add(duration);
					} else if (!pendingMethodDurations.containsKey(methodKey)) {
						// Container with no child durations recorded (e.g. @TestFactory whose
						// DynamicTests lack MethodSource). Record the container's total duration
						// so this method gets speed scoring and stops receiving the perpetual
						// "new method" bonus.
						pendingMethodDurations
								.computeIfAbsent(methodKey, ignored -> Collections.synchronizedList(new ArrayList<>(1)))
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
		if (effectiveStatePath != null && !effectiveStatePath.isEmpty()) {
			Path stateFile = Path.of(effectiveStatePath);
			boolean isLearnRun = Boolean.parseBoolean(System.getProperty(TestOrderConfig.LEARN, "false"));
			try {
				if (isAggregatedMode() && !executionOrder.isEmpty()) {
					// Build-session aggregation mode: write partial record to staging dir.
					// The Maven plugin will merge all per-fork records after all forks complete.
					// Synchronize on executionOrder to prevent ConcurrentModificationException
					// from concurrent testFinished writes during iteration in buildRunRecord.
					TestOrderState.RunRecord record;
					synchronized (executionOrder) {
						record = TestOrderState.buildRunRecord(executionOrder, failedClassNames,
								FlakyRetryExtension.quarantined());
					}
					try {
						PartialRunAggregator.writePartial(Path.of(pendingRunsDir), buildId, record, isLearnRun);
					} catch (IOException e) {
						TestOrderLogger.warn("[telemetry] Could not write partial run record: {}", e.getMessage());
					}
					// Still apply duration/failure telemetry to state under lock.
					// Use saveAggregatedFork to avoid applying decay to historical scores —
					// PartialRunAggregator.mergeAndApply applies the single decay round at
					// session end via addRunRecord.
					state = PersistenceSupport.withFileLock(stateFile, () -> {
						TestOrderState lockedState = TelemetryPersistence.loadStateOrEmpty(stateFile);
						TelemetryPersistence.applyHistoryMaxRuns(lockedState);
						TelemetryPersistence.applyPendingTelemetry(lockedState, pendingDurations, failedClassNames,
								pendingMethodDurations, failedMethodNames);
						lockedState.saveAggregatedFork(stateFile);
						return lockedState;
					});
				} else {
					// Normal (non-aggregated) mode: write RunRecord directly to state.
					state = PersistenceSupport.withFileLock(stateFile, () -> {
						TestOrderState lockedState = TelemetryPersistence.loadStateOrEmpty(stateFile);
						TelemetryPersistence.applyHistoryMaxRuns(lockedState);
						TelemetryPersistence.applyPendingTelemetry(lockedState, pendingDurations, failedClassNames,
								pendingMethodDurations, failedMethodNames);
						// Synchronize on executionOrder to prevent ConcurrentModificationException
						// from concurrent testFinished writes during iteration in buildRunRecord.
						synchronized (executionOrder) {
							if (!executionOrder.isEmpty()) {
								TestOrderState.RunRecord record = TestOrderState.buildRunRecord(executionOrder,
										failedClassNames, FlakyRetryExtension.quarantined());
								lockedState.addRunRecord(record);
								if (!isLearnRun) {
									lockedState.incrementRunsSinceLearn();
								}
								if (record.totalFailures() > 0) {
									String timeSavedMsg = formatTimeSaved(executionOrder, failedClassNames,
											pendingDurations, lockedState);
									if (timeSavedMsg != null) {
										TestOrderLogger.info("Run APFD: {}% (first failure at position {}/{}) — {}",
												String.format(java.util.Locale.US, "%.1f", record.apfd() * 100),
												record.firstFailurePosition() + 1, record.totalTests(), timeSavedMsg);
									} else {
										TestOrderLogger.info("Run APFD: {}% (first failure at position {}/{})",
												String.format(java.util.Locale.US, "%.1f", record.apfd() * 100),
												record.firstFailurePosition() + 1, record.totalTests());
									}
								} else if (!isLearnRun && record.totalTests() > 1) {
									TestOrderLogger.info("{} tests ran in priority order — all passed",
											record.totalTests());
								}
							}
						}
						lockedState.save(stateFile);
						return lockedState;
					});
				}
				resetPending = TestOrderState.hasPendingData();
			} catch (IOException e) {
				TestOrderLogger.error("Failed to save state: {}", e.getMessage());
			}
		}
		if (resetPending) {
			TestOrderState.resetPending();
		}
		// Guard against NoClassDefFoundError on JUnit 4/Vintage where
		// org.junit.jupiter.api.MethodOrderer (imported by PriorityMethodOrderer) is
		// absent.
		try {
			PriorityMethodOrderer.clearPendingState();
		} catch (NoClassDefFoundError ignored) {
			// JUnit Jupiter method orderer API not available (e.g. JUnit 4 / Vintage
			// engine)
		}

		// Clear accumulated data after persisting to prevent double-counting
		// when Surefire re-runs the test plan (rerunFailingTestsCount > 0).
		// Without this, a rerun plan execution would re-apply all durations and
		// failures from the original plan.
		pendingDurations.clear();
		pendingMethodDurations.clear();
		failedClassNames.clear();
		failedMethodNames.clear();
		synchronized (executionOrder) {
			executionOrder.clear();
		}
		executionOrderSet.clear();
		if (shutdownHook != null) {
			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException ignored) {
				/* JVM already shutting down */ }
		}
		// Persist any retry/quarantine activity recorded by FlakyRetryExtension
		// so the Maven/Gradle plugin can surface it in the CI summary and dashboard.
		persistFlakyRuntimeReport(effectiveStatePath);
		// Set finishedNormally=true AFTER all IO and map clearing is done.
		// The shutdown hook reads this flag and returns early, so setting it last
		// ensures it cannot observe an empty/partially-cleared snapshot.
		finishedNormally = true;

		// Offline mode: restore original (uninstrumented) class files from backup
		// so that subsequent builds/tools don't encounter instrumented bytecode.
		restoreOfflineBackupIfPresent();
	}

	/**
	 * Snapshots {@link FlakyRetryExtension}'s runtime retry/quarantine maps and
	 * persists them next to the state file as {@code flaky-runtime.txt}, so the
	 * Maven/Gradle plugin can surface the data in the CI summary and dashboard.
	 * No-op when no retries/quarantines happened.
	 */
	private void persistFlakyRuntimeReport(String effectiveStatePath) {
		try {
			Map<String, Integer> retries;
			Set<String> quarantined;
			try {
				retries = FlakyRetryExtension.retryCounts();
				quarantined = FlakyRetryExtension.quarantined();
			} catch (NoClassDefFoundError ignored) {
				return; // extension JAR absent (shouldn't normally happen)
			}
			Path target = resolveFlakyRuntimePath(effectiveStatePath);
			if (target == null) {
				return;
			}
			Set<String> currentFlaky = currentFlakyClasses();
			if (retries.isEmpty() && quarantined.isEmpty() && currentFlaky == null) {
				return;
			}
			me.bechberger.testorder.ml.FlakyRuntimeReport.write(target, retries, quarantined, currentFlaky);
		} catch (IOException e) {
			TestOrderLogger.warn("[telemetry] Failed to write flaky-runtime report: {}", e.getMessage());
		}
	}

	/**
	 * Loads the current FLAKY classification from the ML report so the rewritten
	 * flaky-runtime.txt can drop entries that no longer apply. Returns null when
	 * the ML report is missing (no filtering should happen — leave the file as the
	 * union of historical entries).
	 */
	private static Set<String> currentFlakyClasses() {
		String pathProp = System.getProperty(TestOrderConfig.FLAKY_REPORT_PATH, ".test-order/ml-report.txt");
		Path reportPath = Path.of(pathProp);
		if (!java.nio.file.Files.exists(reportPath)) {
			return null;
		}
		return me.bechberger.testorder.ml.FlakyReportLoader.loadFlakyClasses(reportPath);
	}

	/**
	 * Picks {@code <stateDir>/flaky-runtime.txt} (or
	 * {@code .test-order/flaky-runtime.txt} as fallback).
	 */
	private static Path resolveFlakyRuntimePath(String effectiveStatePath) {
		if (effectiveStatePath != null && !effectiveStatePath.isEmpty()) {
			Path stateFile = Path.of(effectiveStatePath);
			Path parent = stateFile.getParent();
			if (parent != null) {
				return parent.resolve(me.bechberger.testorder.ml.FlakyRuntimeReport.DEFAULT_FILENAME);
			}
		}
		return Path.of(".test-order", me.bechberger.testorder.ml.FlakyRuntimeReport.DEFAULT_FILENAME);
	}

	/**
	 * If offline instrumentation was used, restore original class files from the
	 * backup directory. This prevents stale instrumented bytecode from causing
	 * {@code NoClassDefFoundError} in subsequent non-learn runs.
	 */
	private void restoreOfflineBackupIfPresent() {
		String backupDirPath = System.getProperty(TestOrderConfig.OFFLINE_BACKUP_DIR);
		if (backupDirPath == null || backupDirPath.isBlank()) {
			return;
		}
		try {
			Path backupDir = Path.of(backupDirPath);
			// Use reflection to call OfflineInstrumentor.restore(Path) since the agent
			// module may not be on the test classpath in all configurations.
			Class<?> instrumentorClass = resolveClass("me.bechberger.testorder.agent.OfflineInstrumentor");
			java.lang.reflect.Method restoreMethod = instrumentorClass.getMethod("restore", Path.class);
			Object restored = restoreMethod.invoke(null, backupDir);
			if (Boolean.TRUE.equals(restored)) {
				TestOrderLogger.info("[telemetry] Restored original classes from offline backup.");
			}
		} catch (ClassNotFoundException e) {
			// OfflineInstrumentor not on classpath — skip silently (online mode agent)
		} catch (java.lang.reflect.InvocationTargetException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			TestOrderLogger.warn("[telemetry] Failed to restore offline backup: {}", cause.toString());
		} catch (Exception e) {
			TestOrderLogger.warn("[telemetry] Failed to restore offline backup: {}", e.toString());
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
		TelemetryPersistence.emergencySave(statePath, durSnap, failSnap, methDurSnap, methFailSnap, isAggregatedMode());
	}

	private boolean isAggregatedMode() {
		return buildId != null && !buildId.isBlank() && pendingRunsDir != null && !pendingRunsDir.isBlank();
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

	/**
	 * Returns true if this test identifier originates from the JUnit Platform Suite
	 * engine. Suite-engine tests are duplicates of directly-discovered tests —
	 * recording them would double-count failures and inflate duration EMA (C6).
	 */
	private static boolean isSuiteEngineNode(TestIdentifier testIdentifier) {
		String uniqueId = testIdentifier.getUniqueId();
		return uniqueId.startsWith("[engine:junit-platform-suite]");
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
			// Class not yet loaded — leave the dedup entry so we don't retry on every call
		} catch (ReflectiveOperationException ignored) {
			// Jupiter parallel API not available or annotation not present
		}
	}

	/**
	 * Calculates estimated time saved by running the first failing test earlier.
	 * Compares actual execution time to reach the first failure vs. the time that
	 * would have been needed in default (alphabetical) order. Returns null if
	 * insufficient data or no time is saved.
	 */
	private static String formatTimeSaved(List<String> executionOrder, Set<String> failedClassNames,
			Map<String, List<Long>> pendingDurations, TestOrderState state) {
		if (executionOrder.isEmpty() || failedClassNames.isEmpty()) {
			return null;
		}

		// Find the first failure in actual execution order
		String firstFailed = null;
		for (String tc : executionOrder) {
			if (failedClassNames.contains(tc)) {
				firstFailed = tc;
				break;
			}
		}
		if (firstFailed == null) {
			return null;
		}

		// Build duration lookup: prefer measured durations, fall back to EMA.
		// pendingDurations is keyed with raw class names (possibly inner classes like
		// "Outer$Inner") but executionOrder uses normalized top-level names ("Outer").
		// Build a normalized-key sum map so lookups work for inner classes too.
		Map<String, Long> normalizedDurationMs = new java.util.HashMap<>();
		for (var entry : pendingDurations.entrySet()) {
			String topKey = TestOrderConfigResolver.toTopLevelClassName(entry.getKey());
			long sum = 0;
			for (long d : entry.getValue())
				sum += d;
			normalizedDurationMs.merge(topKey, sum, Long::sum);
		}
		java.util.function.Function<String, long[]> getDuration = tc -> {
			Long measured = normalizedDurationMs.get(tc);
			if (measured != null && measured > 0) {
				return new long[]{measured};
			}
			long ema = state != null ? state.getDuration(tc, -1) : -1;
			return new long[]{ema};
		};

		// Time in actual order (up to and including the first failed test)
		long actualTimeMs = 0;
		for (String tc : executionOrder) {
			long[] d = getDuration.apply(tc);
			if (d[0] > 0)
				actualTimeMs += d[0];
			if (tc.equals(firstFailed))
				break;
		}

		// Time in default (alphabetical) order to reach the first failure
		List<String> alphabetical = new java.util.ArrayList<>(executionOrder);
		java.util.Collections.sort(alphabetical);
		long alphabeticalTimeMs = 0;
		for (String tc : alphabetical) {
			long[] d = getDuration.apply(tc);
			if (d[0] > 0)
				alphabeticalTimeMs += d[0];
			if (tc.equals(firstFailed))
				break;
		}

		long savedMs = alphabeticalTimeMs - actualTimeMs;
		if (savedMs <= 0) {
			return null;
		}

		// Format the time saved in human-readable form
		String timeStr;
		if (savedMs < 1000) {
			timeStr = savedMs + "ms";
		} else if (savedMs < 60_000) {
			timeStr = String.format(java.util.Locale.US, "%.1fs", savedMs / 1000.0);
		} else {
			timeStr = String.format(java.util.Locale.US, "%dm %ds", savedMs / 60_000, (savedMs % 60_000) / 1000);
		}
		return "~" + timeStr + " faster than default order";
	}
}
