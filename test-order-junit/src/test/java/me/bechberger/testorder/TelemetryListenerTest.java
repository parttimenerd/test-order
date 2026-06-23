package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.platform.engine.*;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import me.bechberger.testorder.junit.PriorityMethodOrderer;
import me.bechberger.testorder.junit.TelemetryListener;

class TelemetryListenerTest {

	@TempDir
	Path tempDir;

	private String originalLearn;
	private String originalTestClass;
	private String originalMode;
	private String originalStatePath;
	private String originalBuildId;
	private String originalPendingRunsDir;

	@BeforeEach
	void saveProperties() {
		originalLearn = System.getProperty("testorder.learn");
		originalTestClass = System.getProperty("testorder.current.testclass");
		originalMode = System.getProperty("testorder.instrumentation.mode");
		originalStatePath = System.getProperty("testorder.state.path");
		originalBuildId = System.getProperty("testorder.build.id");
		originalPendingRunsDir = System.getProperty("testorder.pending.runs.dir");
		// Override build-session aggregation props with blank so TelemetryListener
		// writes directly to the state file even when testorder-config.properties
		// has these set (e.g. when running under mvn test-order:auto test).
		System.setProperty("testorder.build.id", "");
		System.setProperty("testorder.pending.runs.dir", "");
	}

	@AfterEach
	void restoreProperties() {
		restoreProp("testorder.learn", originalLearn);
		restoreProp("testorder.current.testclass", originalTestClass);
		restoreProp("testorder.instrumentation.mode", originalMode);
		restoreProp("testorder.state.path", originalStatePath);
		restoreProp("testorder.build.id", originalBuildId);
		restoreProp("testorder.pending.runs.dir", originalPendingRunsDir);
		TestOrderState.resetPending();
	}

	private void restoreProp(String key, String value) {
		if (value == null) {
			System.clearProperty(key);
		} else {
			System.setProperty(key, value);
		}
	}

	@Test
	void doesNothingWhenLearnNotSet() {
		System.clearProperty("testorder.learn");
		System.clearProperty("testorder.current.testclass");
		System.clearProperty("testorder.state.path");

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		assertNull(System.getProperty("testorder.current.testclass"));
	}

	@Test
	void doesNothingWhenLearnIsFalse() {
		System.setProperty("testorder.learn", "false");
		System.clearProperty("testorder.current.testclass");
		System.clearProperty("testorder.state.path");

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		assertNull(System.getProperty("testorder.current.testclass"));
	}

	@Test
	void recordsDurationsInLearnMode() throws IOException {
		Path stateFile = tempDir.resolve(".test-order-state");

		System.setProperty("testorder.learn", "true");
		System.setProperty("testorder.instrumentation.mode", "FULL");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		TestIdentifier testId = createClassSourceTestIdentifier("com.example.FooTest");
		listener.executionStarted(testId);
		try {
			Thread.sleep(10);
		} catch (InterruptedException ignored) {
		}
		listener.executionFinished(testId, TestExecutionResult.successful());
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		assertTrue(Files.exists(stateFile), "State file should be saved in learn mode");
		TestOrderState state = TestOrderState.load(stateFile);
		long dur = state.getDuration("com.example.FooTest", -1);
		assertTrue(dur > 0, "Duration should be recorded in learn mode, got: " + dur);
	}

	@Test
	void recordsFailuresInLearnMode() throws IOException {
		Path stateFile = tempDir.resolve(".test-order-state");

		System.setProperty("testorder.learn", "true");
		System.setProperty("testorder.instrumentation.mode", "FULL");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		TestIdentifier testId = createClassSourceTestIdentifier("com.example.FooTest");
		listener.executionStarted(testId);
		listener.executionFinished(testId, TestExecutionResult.failed(new AssertionError("test failure")));
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		assertTrue(Files.exists(stateFile), "State file should be saved in learn mode");
		TestOrderState state = TestOrderState.load(stateFile);
		Map<String, Double> failures = state.getFailureScores();
		assertTrue(failures.containsKey("com.example.FooTest"), "Failed test should be recorded in learn mode");
	}

	@Test
	void recordsDurationsInOrderMode() throws IOException {
		Path stateFile = tempDir.resolve(".test-order-state");

		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		TestIdentifier testId = createClassSourceTestIdentifier("com.example.BarTest");
		listener.executionStarted(testId);
		try {
			Thread.sleep(10);
		} catch (InterruptedException ignored) {
		}
		listener.executionFinished(testId, TestExecutionResult.successful());
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		assertTrue(Files.exists(stateFile));
		TestOrderState state = TestOrderState.load(stateFile);
		assertTrue(state.getDuration("com.example.BarTest", -1) > 0);
	}

	@Test
	void recordsRepeatedClassExecutionsSeparately() throws IOException {
		Path stateFile = tempDir.resolve(".test-order-state");

		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		TestIdentifier firstRun = createClassSourceTestIdentifier("com.example.RepeatedTest", "run-1");
		TestIdentifier secondRun = createClassSourceTestIdentifier("com.example.RepeatedTest", "run-2");

		listener.executionStarted(firstRun);
		listener.executionFinished(firstRun, TestExecutionResult.successful());
		listener.executionStarted(secondRun);
		listener.executionFinished(secondRun, TestExecutionResult.successful());
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		assertTrue(state.getDuration("com.example.RepeatedTest", -1) >= 0);
	}

	@Test
	void repeatedClassExecutionDoesNotAccumulatePreviousRunDuration() throws IOException {
		Path stateFile = tempDir.resolve(".test-order-state-repeated-duration");

		// Force "latest sample wins" so the assertion observes the second execution
		// directly.
		TestOrderState seeded = new TestOrderState();
		seeded.setDurationAlpha(1.0);
		seeded.save(stateFile);

		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		TestIdentifier firstRun = createClassSourceTestIdentifier("com.example.RepeatedDurationTest", "run-1");
		TestIdentifier secondRun = createClassSourceTestIdentifier("com.example.RepeatedDurationTest", "run-2");

		listener.executionStarted(firstRun);
		try {
			Thread.sleep(220);
		} catch (InterruptedException ignored) {
		}
		listener.executionFinished(firstRun, TestExecutionResult.successful());

		listener.executionStarted(secondRun);
		try {
			Thread.sleep(220);
		} catch (InterruptedException ignored) {
		}
		listener.executionFinished(secondRun, TestExecutionResult.successful());
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		long duration = state.getDuration("com.example.RepeatedDurationTest", -1);
		// With alpha=1.0 and two 220ms method invocations in one run, the aggregated
		// class duration should be the SUM (~440ms) — not the average and not
		// a value from a prior run.
		assertTrue(duration >= 300 && duration <= 600,
				"Duration should be the sum of both method invocations (~440ms). Got: " + duration + " ms");
	}

	@Test
	void recordsMethodDurationsUsingUniqueExecutionIds() throws IOException {
		Path stateFile = tempDir.resolve(".test-order-state");

		System.setProperty("testorder.learn", "true");
		System.setProperty("testorder.instrumentation.mode", "METHOD");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		TestIdentifier firstMethod = createMethodSourceTestIdentifier("com.example.FooTest", "testA", "run-1");
		TestIdentifier secondMethod = createMethodSourceTestIdentifier("com.example.FooTest", "testA", "run-2");

		listener.executionStarted(firstMethod);
		listener.executionFinished(firstMethod, TestExecutionResult.successful());
		listener.executionStarted(secondMethod);
		listener.executionFinished(secondMethod, TestExecutionResult.successful());
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		assertTrue(state.getDurationMethod("com.example.FooTest", "testA", -1) >= 0);
	}

	@Test
	void clearsPendingMethodOrderingStateWhenPlanFinishes() {
		PriorityMethodOrderer.setPendingState(new TestOrderState(), TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of("com.example.Changed"), Set.of("com.example.FooTest#testA"));

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		assertFalse(isMethodOrderingStateConfigured());
	}

	private static boolean isMethodOrderingStateConfigured() {
		try {
			var field = PriorityMethodOrderer.class.getDeclaredField("pendingStateRef");
			field.setAccessible(true);
			return ((java.util.concurrent.atomic.AtomicReference<?>) field.get(null)).get() != null;
		} catch (ReflectiveOperationException e) {
			fail("Could not inspect PriorityMethodOrderer pending state: " + e.getMessage());
			return false;
		}
	}

	@Test
	void warnsWhenTestClassUsesConcurrentExecution() {
		System.setProperty("testorder.learn", "true");

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// Redirect System.err to capture the warning
		var buf = new java.io.ByteArrayOutputStream();
		var oldErr = System.err;
		System.setErr(new java.io.PrintStream(buf));
		try {
			// Use the inner class annotated @Execution(CONCURRENT) which is on the
			// classpath
			TestIdentifier testId = createClassSourceTestIdentifier(ConcurrentDummyTest.class.getName());
			listener.executionStarted(testId);
		} finally {
			System.setErr(oldErr);
		}

		String output = buf.toString();
		assertTrue(output.contains("CONCURRENT") || output.contains("concurrent"),
				"Should warn about @Execution(CONCURRENT), got: " + output);
	}

	/**
	 * Dummy test class annotated with @Execution(CONCURRENT) for use in the warn
	 * test.
	 */
	@Execution(ExecutionMode.CONCURRENT)
	static class ConcurrentDummyTest {
		@Test
		void noop() {
		}
	}

	@Test
	void durationRecordedIsNeverNegative() throws IOException {
		Path stateFile = tempDir.resolve(".test-order-state-nonneg");
		System.setProperty("testorder.learn", "true");
		System.setProperty("testorder.instrumentation.mode", "FULL");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		TestIdentifier testId = createClassSourceTestIdentifier("com.example.DurationTest");
		listener.executionStarted(testId);
		// Immediately finish (no sleep) — elapsed could theoretically round to 0, never
		// negative
		listener.executionFinished(testId, TestExecutionResult.successful());
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		long dur = state.getDuration("com.example.DurationTest", -99L);
		assertTrue(dur >= 0, "Duration must be non-negative, got: " + dur);
	}

	// ── Tier 3b: agent version mismatch & concurrent interleaving ──────────

	/**
	 * E3b-1: When the agent runtime (UsageStore) is not on the classpath — the same
	 * code path triggered by an agent version mismatch (ClassNotFoundException /
	 * NoSuchMethodException) — the listener must still record durations and
	 * failures normally in order mode. The reflection failure is swallowed and the
	 * listener degrades gracefully.
	 */
	@Test
	void agentUnavailableDoesNotPreventDurationRecordingInOrderMode() throws IOException {
		Path stateFile = tempDir.resolve(".state-agent-unavailable");
		// No testorder.learn → order mode; reflection will fail (agent not on
		// classpath)
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		TestIdentifier id = createClassSourceTestIdentifier("com.example.SomeTest");
		listener.executionStarted(id);
		try {
			Thread.sleep(5);
		} catch (InterruptedException ignored) {
		}
		listener.executionFinished(id, TestExecutionResult.successful());
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		// Duration must be recorded despite reflection failure
		assertTrue(Files.exists(stateFile), "State file must be written");
		TestOrderState state = TestOrderState.load(stateFile);
		assertTrue(state.getDuration("com.example.SomeTest", -1L) >= 0,
				"Duration must be recorded even when agent reflection setup fails");
	}

	/**
	 * E3b-2: When learn mode is active but the agent RuntimeClass is absent
	 * (simulates NoSuchMethodException from a version mismatch), the listener must
	 * still save the state file — failures and durations must not be lost.
	 */
	@Test
	void agentUnavailableInLearnModeStillPersistsFailures() throws IOException {
		Path stateFile = tempDir.resolve(".state-agent-fail");
		System.setProperty("testorder.learn", "true");
		System.setProperty("testorder.instrumentation.mode", "FULL");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		TestIdentifier id = createClassSourceTestIdentifier("com.example.FailingTest");
		listener.executionStarted(id);
		listener.executionFinished(id, TestExecutionResult.failed(new RuntimeException("boom")));
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		// Failure must be recorded despite reflection failure
		assertTrue(Files.exists(stateFile), "State file must be written in learn mode");
		TestOrderState state = TestOrderState.load(stateFile);
		assertTrue(state.getFailureScores().containsKey("com.example.FailingTest"),
				"Failure must be recorded even when agent reflection setup fails");
	}

	/**
	 * E3b-3: Concurrent interleaving — class A starts, class B starts, class A
	 * finishes, class B finishes. The listener must not crash and must record BOTH
	 * durations. (Tests that putIfAbsent correctly handles interleaved executions.)
	 */
	@Test
	void interleavedClassExecutionsBothGetDurationsRecorded() throws IOException {
		Path stateFile = tempDir.resolve(".state-interleaved");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		TestIdentifier classA = createClassSourceTestIdentifier("com.example.ClassATest", "run-classA");
		TestIdentifier classB = createClassSourceTestIdentifier("com.example.ClassBTest", "run-classB");

		// Interleaved: A starts, B starts, A finishes, B finishes
		listener.executionStarted(classA);
		listener.executionStarted(classB);
		try {
			Thread.sleep(5);
		} catch (InterruptedException ignored) {
		}
		listener.executionFinished(classA, TestExecutionResult.successful());
		listener.executionFinished(classB, TestExecutionResult.successful());
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		assertTrue(Files.exists(stateFile));
		TestOrderState state = TestOrderState.load(stateFile);
		assertTrue(state.getDuration("com.example.ClassATest", -1L) >= 0, "ClassA duration must be recorded");
		assertTrue(state.getDuration("com.example.ClassBTest", -1L) >= 0, "ClassB duration must be recorded");
	}

	/**
	 * Creates a TestIdentifier backed by a ClassSource via a stub TestDescriptor.
	 */
	@Test
	void toTopLevelClassNameStripsNestedSuffix() {
		assertEquals("com.example.OuterTest",
				TestOrderConfigResolver.toTopLevelClassName("com.example.OuterTest$InnerTest"));
		assertEquals("com.example.OuterTest", TestOrderConfigResolver.toTopLevelClassName("com.example.OuterTest$A$B"));
		assertEquals("com.example.FooTest", TestOrderConfigResolver.toTopLevelClassName("com.example.FooTest"));
	}

	@Test
	void nestedClassFailureRecordedUnderTopLevelClass() throws IOException {
		Path stateFile = tempDir.resolve(".test-order-state");

		System.setProperty("testorder.learn", "true");
		System.setProperty("testorder.instrumentation.mode", "FULL");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// Simulate a failure in a @Nested class — MethodSource reports the nested class
		// name
		TestIdentifier methodId = createMethodSourceTestIdentifier("com.example.OuterTest$InnerTest", "failingTest",
				"nested-fail");
		listener.executionStarted(methodId);
		listener.executionFinished(methodId, TestExecutionResult.failed(new AssertionError("nested failure")));
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		assertTrue(Files.exists(stateFile));
		TestOrderState state = TestOrderState.load(stateFile);
		Map<String, Double> failures = state.getFailureScores();
		// Failure must be recorded under the top-level class, not the nested class
		assertTrue(failures.containsKey("com.example.OuterTest"),
				"Failure should be recorded under top-level class; got: " + failures.keySet());
		assertFalse(failures.containsKey("com.example.OuterTest$InnerTest"),
				"Failure should NOT be recorded under nested class name");
	}

	// ── Parameterized method tests ──────────────────────────────────────

	/**
	 * Multiple parameterized invocations of the same method (e.g. @CsvSource with 3
	 * rows) must aggregate their durations under a single method key.
	 */
	@Test
	void parameterizedMethodInvocationsAggregateDurations() throws IOException {
		Path stateFile = tempDir.resolve(".state-param-durations");

		System.setProperty("testorder.learn", "true");
		System.setProperty("testorder.instrumentation.mode", "METHOD");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// Simulate 3 parameterized invocations of the same method
		for (int i = 1; i <= 3; i++) {
			TestIdentifier paramId = createMethodSourceTestIdentifier("com.example.CalcTest", "testAdd", "param-" + i);
			listener.executionStarted(paramId);
			listener.executionFinished(paramId, TestExecutionResult.successful());
		}
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		double dur = state.getDurationMethod("com.example.CalcTest", "testAdd", -1.0);
		// Duration should be recorded (aggregated across 3 runs)
		assertTrue(dur >= 0, "Parameterized method duration should be aggregated, got: " + dur);
	}

	/**
	 * A failure in one parameterized invocation must record the method-level
	 * failure, not per-parameter.
	 */
	@Test
	void parameterizedMethodFailureRecordedAtMethodLevel() throws IOException {
		Path stateFile = tempDir.resolve(".state-param-failure");

		System.setProperty("testorder.learn", "true");
		System.setProperty("testorder.instrumentation.mode", "METHOD");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// param-1 passes, param-2 fails, param-3 passes
		TestIdentifier pass1 = createMethodSourceTestIdentifier("com.example.DivTest", "testDivide", "param-1");
		listener.executionStarted(pass1);
		listener.executionFinished(pass1, TestExecutionResult.successful());

		TestIdentifier fail = createMethodSourceTestIdentifier("com.example.DivTest", "testDivide", "param-2");
		listener.executionStarted(fail);
		listener.executionFinished(fail, TestExecutionResult.failed(new ArithmeticException("/ by zero")));

		TestIdentifier pass2 = createMethodSourceTestIdentifier("com.example.DivTest", "testDivide", "param-3");
		listener.executionStarted(pass2);
		listener.executionFinished(pass2, TestExecutionResult.successful());

		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		// Method-level failure should be recorded
		double methodScore = state.methodFailureScore("com.example.DivTest", "testDivide");
		assertTrue(methodScore > 0, "Method failure should be recorded when any parameter set fails");
		// Class-level failure should also be recorded (top-level)
		Map<String, Double> classFailures = state.getFailureScores();
		assertTrue(classFailures.containsKey("com.example.DivTest"),
				"Class-level failure should be recorded for parameterized method failure");
	}

	/**
	 * Multiple parameterized methods in the same class must track durations
	 * separately per method.
	 */
	@Test
	void multipleParameterizedMethodsTrackedSeparately() throws IOException {
		Path stateFile = tempDir.resolve(".state-multi-param");

		System.setProperty("testorder.learn", "true");
		System.setProperty("testorder.instrumentation.mode", "METHOD");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// Method "testAdd" with 3 params
		for (int i = 1; i <= 3; i++) {
			TestIdentifier id = createMethodSourceTestIdentifier("com.example.MathTest", "testAdd", "add-" + i);
			listener.executionStarted(id);
			listener.executionFinished(id, TestExecutionResult.successful());
		}
		// Method "testSubtract" with 2 params
		for (int i = 1; i <= 2; i++) {
			TestIdentifier id = createMethodSourceTestIdentifier("com.example.MathTest", "testSubtract", "sub-" + i);
			listener.executionStarted(id);
			listener.executionFinished(id, TestExecutionResult.successful());
		}
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		// Both methods should have independently tracked durations
		double addDur = state.getDurationMethod("com.example.MathTest", "testAdd", -1.0);
		double subDur = state.getDurationMethod("com.example.MathTest", "testSubtract", -1.0);
		assertTrue(addDur >= 0, "testAdd duration should be recorded");
		assertTrue(subDur >= 0, "testSubtract duration should be recorded");
	}

	/**
	 * Parameterized class (JUnit 6 @ParameterizedClass): multiple ClassSource
	 * TestIdentifiers for the same class should aggregate durations.
	 */
	@Test
	void parameterizedClassInvocationsAggregateDurations() throws IOException {
		Path stateFile = tempDir.resolve(".state-param-class");

		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// Simulate a parameterized class with 3 argument sets
		for (int i = 1; i <= 3; i++) {
			TestIdentifier classId = createClassSourceTestIdentifier("com.example.ParamClassTest", "arg-set-" + i);
			listener.executionStarted(classId);
			listener.executionFinished(classId, TestExecutionResult.successful());
		}
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		long dur = state.getDuration("com.example.ParamClassTest", -1);
		assertTrue(dur >= 0, "Parameterized class duration should be recorded");
	}

	/**
	 * Parameterized class with a failure in one argument set must record the
	 * class-level failure.
	 */
	@Test
	void parameterizedClassFailureRecordedAtClassLevel() throws IOException {
		Path stateFile = tempDir.resolve(".state-param-class-fail");

		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// arg-set-1 passes
		TestIdentifier pass = createClassSourceTestIdentifier("com.example.FailParamTest", "arg-set-1");
		listener.executionStarted(pass);
		listener.executionFinished(pass, TestExecutionResult.successful());

		// arg-set-2 fails
		TestIdentifier fail = createClassSourceTestIdentifier("com.example.FailParamTest", "arg-set-2");
		listener.executionStarted(fail);
		listener.executionFinished(fail, TestExecutionResult.failed(new AssertionError("wrong result")));

		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		Map<String, Double> failures = state.getFailureScores();
		assertTrue(failures.containsKey("com.example.FailParamTest"),
				"Class failure should be recorded when parameterized class arg set fails");
	}

	/**
	 * Mixed scenario: parameterized class with parameterized methods inside. Both
	 * class-level and method-level telemetry should be recorded.
	 */
	@Test
	void parameterizedClassWithParameterizedMethodsRecordsBothLevels() throws IOException {
		Path stateFile = tempDir.resolve(".state-param-both");

		System.setProperty("testorder.learn", "true");
		System.setProperty("testorder.instrumentation.mode", "METHOD");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// Class-level event (parameterized class arg-set-1)
		TestIdentifier classId = createClassSourceTestIdentifier("com.example.FullParamTest", "arg-set-1");
		listener.executionStarted(classId);

		// Method-level events inside the class (parameterized method with 2 param sets)
		TestIdentifier method1 = createMethodSourceTestIdentifier("com.example.FullParamTest", "testCalc",
				"arg1-param1");
		listener.executionStarted(method1);
		listener.executionFinished(method1, TestExecutionResult.successful());

		TestIdentifier method2 = createMethodSourceTestIdentifier("com.example.FullParamTest", "testCalc",
				"arg1-param2");
		listener.executionStarted(method2);
		listener.executionFinished(method2, TestExecutionResult.successful());

		// Class-level finish
		listener.executionFinished(classId, TestExecutionResult.successful());
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		// Class-level duration should be recorded
		long classDur = state.getDuration("com.example.FullParamTest", -1);
		assertTrue(classDur >= 0, "Class-level duration should be recorded");
		// Method-level duration should also be recorded
		double methodDur = state.getDurationMethod("com.example.FullParamTest", "testCalc", -1.0);
		assertTrue(methodDur >= 0, "Method-level duration should be recorded for parameterized method");
	}

	/**
	 * Parameterized method where all invocations pass — no failure should be
	 * recorded.
	 */
	@Test
	void parameterizedMethodAllPassNoFailureRecorded() throws IOException {
		Path stateFile = tempDir.resolve(".state-param-all-pass");

		System.setProperty("testorder.learn", "true");
		System.setProperty("testorder.instrumentation.mode", "METHOD");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// 5 parameterized invocations, all pass
		for (int i = 1; i <= 5; i++) {
			TestIdentifier id = createMethodSourceTestIdentifier("com.example.PrimeTest", "testIsPrime", "value-" + i);
			listener.executionStarted(id);
			listener.executionFinished(id, TestExecutionResult.successful());
		}
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		double failScore = state.methodFailureScore("com.example.PrimeTest", "testIsPrime");
		assertEquals(0.0, failScore, 0.001, "No failure should be recorded when all param sets pass");
	}

	/**
	 * Parameterized method inside a @Nested class: failure must be attributed to
	 * the top-level class, and method duration must be tracked under the nested
	 * class name (since MethodSource reports the nested name).
	 */
	@Test
	void parameterizedMethodInNestedClassAttributedToTopLevel() throws IOException {
		Path stateFile = tempDir.resolve(".state-nested-param");

		System.setProperty("testorder.learn", "true");
		System.setProperty("testorder.instrumentation.mode", "METHOD");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// 3 parameterized invocations inside Outer$Inner.testMultiply
		for (int i = 1; i <= 3; i++) {
			TestIdentifier id = createMethodSourceTestIdentifier("com.example.OuterTest$InnerTest", "testMultiply",
					"csv-" + i);
			listener.executionStarted(id);
			if (i == 2) {
				listener.executionFinished(id, TestExecutionResult.failed(new AssertionError("bad product")));
			} else {
				listener.executionFinished(id, TestExecutionResult.successful());
			}
		}
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		// Class-level failure must be under top-level class
		Map<String, Double> classFailures = state.getFailureScores();
		assertTrue(classFailures.containsKey("com.example.OuterTest"),
				"Parameterized failure in @Nested class must be attributed to top-level class");
		assertFalse(classFailures.containsKey("com.example.OuterTest$InnerTest"),
				"Failure must NOT be under nested class name");
		// Method-level failure uses the nested class name (for method scoring)
		double methodScore = state.methodFailureScore("com.example.OuterTest$InnerTest", "testMultiply");
		assertTrue(methodScore > 0, "Method failure should be recorded under nested class");
		// Method duration should also be recorded
		double dur = state.getDurationMethod("com.example.OuterTest$InnerTest", "testMultiply", -1.0);
		assertTrue(dur >= 0, "Method duration should be aggregated for nested parameterized method");
	}

	/**
	 * ABORTED parameterized invocations (e.g. Assumptions.assumeTrue() skips some
	 * param sets): duration must still be tracked, but no failure recorded.
	 */
	@Test
	void abortedParameterizedInvocationNotRecordedAsFailure() throws IOException {
		Path stateFile = tempDir.resolve(".state-param-aborted");

		System.setProperty("testorder.learn", "true");
		System.setProperty("testorder.instrumentation.mode", "METHOD");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// param-1 passes, param-2 is aborted (assumption failure), param-3 passes
		TestIdentifier p1 = createMethodSourceTestIdentifier("com.example.AssumptionTest", "testConditional", "p-1");
		listener.executionStarted(p1);
		listener.executionFinished(p1, TestExecutionResult.successful());

		TestIdentifier p2 = createMethodSourceTestIdentifier("com.example.AssumptionTest", "testConditional", "p-2");
		listener.executionStarted(p2);
		listener.executionFinished(p2,
				TestExecutionResult.aborted(new org.opentest4j.TestAbortedException("assumption")));

		TestIdentifier p3 = createMethodSourceTestIdentifier("com.example.AssumptionTest", "testConditional", "p-3");
		listener.executionStarted(p3);
		listener.executionFinished(p3, TestExecutionResult.successful());

		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		// No failure should be recorded (ABORTED ≠ FAILED)
		double methodScore = state.methodFailureScore("com.example.AssumptionTest", "testConditional");
		assertEquals(0.0, methodScore, 0.001, "ABORTED invocations must not count as failures");
		Map<String, Double> classFailures = state.getFailureScores();
		assertFalse(classFailures.containsKey("com.example.AssumptionTest"),
				"ABORTED invocations must not produce class-level failures");
		// Duration should still be tracked for all 3 invocations
		double dur = state.getDurationMethod("com.example.AssumptionTest", "testConditional", -1.0);
		assertTrue(dur >= 0, "Duration should be tracked even for aborted parameterized invocations");
	}

	/**
	 * Interleaved parameterized methods: two @ParameterizedTest methods alternate
	 * invocations (A[1], B[1], A[2], B[2]). Each must track durations separately.
	 */
	@Test
	void interleavedParameterizedMethodsTrackedSeparately() throws IOException {
		Path stateFile = tempDir.resolve(".state-interleaved-param");

		System.setProperty("testorder.learn", "true");
		System.setProperty("testorder.instrumentation.mode", "METHOD");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// Interleaved: A[1], B[1], A[2], B[2]
		TestIdentifier a1 = createMethodSourceTestIdentifier("com.example.InterleavedTest", "testAdd", "a1");
		TestIdentifier b1 = createMethodSourceTestIdentifier("com.example.InterleavedTest", "testSub", "b1");
		TestIdentifier a2 = createMethodSourceTestIdentifier("com.example.InterleavedTest", "testAdd", "a2");
		TestIdentifier b2 = createMethodSourceTestIdentifier("com.example.InterleavedTest", "testSub", "b2");

		listener.executionStarted(a1);
		listener.executionStarted(b1);
		listener.executionFinished(a1, TestExecutionResult.successful());
		listener.executionFinished(b1, TestExecutionResult.failed(new AssertionError("wrong")));
		listener.executionStarted(a2);
		listener.executionStarted(b2);
		listener.executionFinished(a2, TestExecutionResult.successful());
		listener.executionFinished(b2, TestExecutionResult.successful());

		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		// testAdd should have no failure
		double addFail = state.methodFailureScore("com.example.InterleavedTest", "testAdd");
		assertEquals(0.0, addFail, 0.001, "testAdd had no failures");
		// testSub should have a failure (from b1)
		double subFail = state.methodFailureScore("com.example.InterleavedTest", "testSub");
		assertTrue(subFail > 0, "testSub should record failure from interleaved invocation");
		// Both should have durations
		double addDur = state.getDurationMethod("com.example.InterleavedTest", "testAdd", -1.0);
		double subDur = state.getDurationMethod("com.example.InterleavedTest", "testSub", -1.0);
		assertTrue(addDur >= 0, "testAdd duration should be tracked despite interleaving");
		assertTrue(subDur >= 0, "testSub duration should be tracked despite interleaving");
	}

	/**
	 * Single parameterized invocation (degenerate case: @ValueSource with 1 value).
	 * Must still record duration and not crash.
	 */
	@Test
	void singleParameterizedInvocationTracked() throws IOException {
		Path stateFile = tempDir.resolve(".state-single-param");

		System.setProperty("testorder.learn", "true");
		System.setProperty("testorder.instrumentation.mode", "METHOD");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// Single parameterized invocation
		TestIdentifier id = createMethodSourceTestIdentifier("com.example.SingleTest", "testSingle", "only-param");
		listener.executionStarted(id);
		listener.executionFinished(id, TestExecutionResult.successful());
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		double dur = state.getDurationMethod("com.example.SingleTest", "testSingle", -1.0);
		assertTrue(dur >= 0, "Single parameterized invocation must track duration");
		double failScore = state.methodFailureScore("com.example.SingleTest", "testSingle");
		assertEquals(0.0, failScore, 0.001, "Single passing invocation must have no failure");
	}

	/**
	 * Execution order tracking for a parameterized class: all arg sets of the same
	 * class must be de-duplicated into a single entry in the execution order list.
	 */
	@Test
	void parameterizedClassExecutionOrderDeduped() throws IOException {
		Path stateFile = tempDir.resolve(".state-param-exec-order");

		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		// Seed pending data so RunRecord is built
		TestOrderState.setStatePath(stateFile.toString());
		TestOrderState.recordBreakdown("com.example.ParamDedup",
				new TestOrderState.ScoreBreakdown(5, false, false, 0, 0, 0.0, false, false, 0.0));

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// 3 ClassSource events for the same class (3 arg sets)
		for (int i = 1; i <= 3; i++) {
			TestIdentifier id = createClassSourceTestIdentifier("com.example.ParamDedup", "arg-" + i);
			listener.executionStarted(id);
			listener.executionFinished(id, TestExecutionResult.successful());
		}
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		// Load the state to check the run record
		TestOrderState state = TestOrderState.load(stateFile);
		List<TestOrderState.RunRecord> history = state.runs();
		assertFalse(history.isEmpty(), "Run record should be saved");
		TestOrderState.RunRecord lastRun = history.get(history.size() - 1);
		// The class should appear at most once in the execution order
		assertEquals(1, lastRun.totalTests(),
				"Parameterized class arg sets should be de-duplicated in execution order");
	}

	// ── @RepeatedTest ────────────────────────────────────────────────────

	/**
	 * @RepeatedTest generates MethodSource identifiers per repetition (like
	 * @ParameterizedTest). Verify durations from multiple repetitions are
	 *                      aggregated to the same method key.
	 */
	@Test
	void repeatedTestInvocationsAggregateDurations() throws IOException {
		Path stateFile = tempDir.resolve(".state-repeated-dur");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// Simulate 3 repetitions of the same @RepeatedTest method
		for (int i = 1; i <= 3; i++) {
			TestIdentifier id = createMethodSourceTestIdentifier("com.example.RepTest", "repeatMe", "repetition-" + i);
			listener.executionStarted(id);
			listener.executionFinished(id, TestExecutionResult.successful());
		}
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		double dur = state.getDurationMethod("com.example.RepTest", "repeatMe", -1);
		assertTrue(dur >= 0, "Repeated test durations must be aggregated");
	}

	/**
	 * @RepeatedTest — if one repetition fails, it must be recorded as a method
	 *               failure.
	 */
	@Test
	void repeatedTestFailureRecordedAtMethodLevel() throws IOException {
		Path stateFile = tempDir.resolve(".state-repeated-fail");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// rep 1 passes
		TestIdentifier rep1 = createMethodSourceTestIdentifier("com.example.RepFailTest", "repeatFail", "rep-1");
		listener.executionStarted(rep1);
		listener.executionFinished(rep1, TestExecutionResult.successful());

		// rep 2 fails
		TestIdentifier rep2 = createMethodSourceTestIdentifier("com.example.RepFailTest", "repeatFail", "rep-2");
		listener.executionStarted(rep2);
		listener.executionFinished(rep2, TestExecutionResult.failed(new AssertionError("oops")));

		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		double failScore = state.methodFailureScore("com.example.RepFailTest", "repeatFail");
		assertTrue(failScore > 0, "Repeated test failure must be recorded at method level");
	}

	// ── @TestFactory / DynamicTest ──────────────────────────────────────

	/**
	 * A @TestFactory method container has a MethodSource — it must be tracked for
	 * duration like a normal test method.
	 */
	@Test
	void testFactoryContainerTrackedAsMethod() throws IOException {
		Path stateFile = tempDir.resolve(".state-factory-container");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// The @TestFactory method itself is reported as a MethodSource container
		TestIdentifier factory = createMethodSourceTestIdentifier("com.example.DynTest", "dynamicTests", "factory");
		listener.executionStarted(factory);
		listener.executionFinished(factory, TestExecutionResult.successful());

		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		double dur = state.getDurationMethod("com.example.DynTest", "dynamicTests", -1);
		assertTrue(dur >= 0, "TestFactory container must be tracked as MethodSource");
	}

	/**
	 * DynamicTest children that have NO source (Optional.empty) must be handled
	 * gracefully — no crash, no duration, no failure attributed.
	 */
	@Test
	void dynamicTestChildWithNoSourceHandledGracefully() throws IOException {
		Path stateFile = tempDir.resolve(".state-dynamic-nosource");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// Source-less DynamicTest child
		TestIdentifier noSource = createSourcelessTestIdentifier("dynamic-child-1");
		listener.executionStarted(noSource);
		listener.executionFinished(noSource, TestExecutionResult.failed(new RuntimeException("boom")));

		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		// No crash — state file can be loaded (may be empty)
		if (Files.exists(stateFile)) {
			TestOrderState state = TestOrderState.load(stateFile);
			assertNotNull(state);
		}
		// If we got here without exception, the test passes
	}

	/**
	 * @TestFactory failure on the container method itself must be recorded as a
	 *              method-level and class-level failure.
	 */
	@Test
	void testFactoryContainerFailureRecorded() throws IOException {
		Path stateFile = tempDir.resolve(".state-factory-fail");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		TestIdentifier factory = createMethodSourceTestIdentifier("com.example.FailFactory", "brokenFactory",
				"factory");
		listener.executionStarted(factory);
		listener.executionFinished(factory, TestExecutionResult.failed(new RuntimeException("factory broke")));

		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		double failScore = state.methodFailureScore("com.example.FailFactory", "brokenFactory");
		assertTrue(failScore > 0, "Factory method failure must be recorded");
	}

	// ── @Disabled ──────────────────────────────────────────────────────

	/**
	 * @Disabled tests are excluded during discovery — executionStarted is never
	 *           called. If somehow a class only has disabled methods, no events
	 *           fire and the state should not contain it.
	 */
	@Test
	void disabledClassAbsentFromTelemetry() throws IOException {
		Path stateFile = tempDir.resolve(".state-disabled");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		// Seed pending data with one known class so a RunRecord is built
		TestOrderState.setStatePath(stateFile.toString());
		TestOrderState.recordBreakdown("com.example.EnabledTest",
				new TestOrderState.ScoreBreakdown(5, false, false, 0, 0, 0.0, false, false, 0.0));

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// Only the enabled class fires events — disabled class never appears
		TestIdentifier enabledId = createClassSourceTestIdentifier("com.example.EnabledTest");
		listener.executionStarted(enabledId);
		listener.executionFinished(enabledId, TestExecutionResult.successful());

		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		double disabledDur = state.getDuration("com.example.DisabledTest", -1);
		assertEquals(-1, disabledDur, 0.001, "Disabled class must not appear in state");
	}

	// ── @ClassTemplate ─────────────────────────────────────────────────

	/**
	 * @ClassTemplate is like @ParameterizedClass — multiple ClassSource events
	 *                aggregate durations under the same class name.
	 */
	@Test
	void classTemplateInvocationsAggregateDurations() throws IOException {
		Path stateFile = tempDir.resolve(".state-classtemplate");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// Simulate 2 invocations of a @ClassTemplate class
		for (int i = 1; i <= 2; i++) {
			TestIdentifier id = createClassSourceTestIdentifier("com.example.TemplateTest", "template-ctx-" + i);
			listener.executionStarted(id);
			listener.executionFinished(id, TestExecutionResult.successful());
		}
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		double dur = state.getDuration("com.example.TemplateTest", -1);
		assertTrue(dur >= 0, "ClassTemplate durations must be aggregated");
	}

	/**
	 * @ClassTemplate failure in one invocation must be recorded at class level.
	 */
	@Test
	void classTemplateFailureRecordedAtClassLevel() throws IOException {
		Path stateFile = tempDir.resolve(".state-classtemplate-fail");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// ctx-1 passes
		TestIdentifier ctx1 = createClassSourceTestIdentifier("com.example.FailTemplateTest", "tmpl-1");
		listener.executionStarted(ctx1);
		listener.executionFinished(ctx1, TestExecutionResult.successful());

		// ctx-2 fails
		TestIdentifier ctx2 = createClassSourceTestIdentifier("com.example.FailTemplateTest", "tmpl-2");
		listener.executionStarted(ctx2);
		listener.executionFinished(ctx2, TestExecutionResult.failed(new AssertionError("template fail")));

		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		List<TestOrderState.RunRecord> history = state.runs();
		assertFalse(history.isEmpty());
		assertTrue(history.get(history.size() - 1).totalFailures() > 0, "ClassTemplate failure must be recorded");
	}

	// ═══════════════════════════════════════════════════════════════════
	// M3: Dry-run mode — TelemetryListener should skip all recording
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void dryRunModeSkipsRecording() throws IOException {
		Path stateFile = tempDir.resolve(".state-dryrun");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());
		System.setProperty("junit.platform.execution.dryRun.enabled", "true");

		try {
			TelemetryListener listener = new TelemetryListener();
			listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

			TestIdentifier id = createClassSourceTestIdentifier("com.example.DryRunTest");
			listener.executionStarted(id);
			listener.executionFinished(id, TestExecutionResult.successful());

			listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

			// In dry-run mode, no state should be persisted
			assertFalse(Files.exists(stateFile), "Dry-run mode should not create state file");
		} finally {
			System.clearProperty("junit.platform.execution.dryRun.enabled");
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// C6: Suite engine deduplication
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void suiteEngineEventsAreIgnored() throws IOException {
		Path stateFile = tempDir.resolve(".state-suite");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		// Direct Jupiter execution — should be recorded
		TestIdentifier directId = createClassSourceTestIdentifier("com.example.SuiteTest");
		listener.executionStarted(directId);
		listener.executionFinished(directId, TestExecutionResult.successful());

		// Suite-engine execution — should be ignored
		TestIdentifier suiteId = createSuiteEngineTestIdentifier("com.example.SuiteTest", "suite-1");
		listener.executionStarted(suiteId);
		listener.executionFinished(suiteId, TestExecutionResult.failed(new AssertionError("suite fail")));

		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		// Only the direct execution should be recorded — the suite failure should be
		// ignored
		List<TestOrderState.RunRecord> runs = state.runs();
		assertFalse(runs.isEmpty());
		assertEquals(0, runs.get(runs.size() - 1).totalFailures(), "Suite engine failure should not be recorded");
	}

	// ═══════════════════════════════════════════════════════════════════
	// L18: executionSkipped — dormant test handling
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void executionSkippedDoesNotThrow() {
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", tempDir.resolve(".state-skip").toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		TestIdentifier id = createClassSourceTestIdentifier("com.example.SkippedTest");
		// executionSkipped should not throw or cause NPE
		assertDoesNotThrow(() -> listener.executionSkipped(id, "disabled via @Disabled"));

		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);
	}

	// ═══════════════════════════════════════════════════════════════════
	// M9: Abort vs failure — ABORTED should not count as failure
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void abortedTestNotCountedAsFailure() throws IOException {
		Path stateFile = tempDir.resolve(".state-abort");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

		TestIdentifier id = createClassSourceTestIdentifier("com.example.AbortedTest");
		listener.executionStarted(id);
		listener.executionFinished(id,
				TestExecutionResult.aborted(new org.opentest4j.TestAbortedException("assumption failed")));

		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		List<TestOrderState.RunRecord> runs = state.runs();
		assertFalse(runs.isEmpty());
		assertEquals(0, runs.get(runs.size() - 1).totalFailures(), "ABORTED tests should not count as failures");
	}

	// ── Rerun recovery tests ─────────────────────────────────────────

	/**
	 * Simulates Surefire's rerunFailingTestsCount: a test fails in the first plan
	 * execution, then succeeds in the rerun plan. The final state should NOT record
	 * the class as failed, and durations should not be double-counted.
	 */
	@Test
	void rerunRecovery_failThenPass_clearsFailure() throws IOException {
		Path stateFile = tempDir.resolve(".state-rerun-recovery");

		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();

		// Plan 1: test fails
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);
		TestIdentifier classId1 = createClassSourceTestIdentifier("com.example.FlakyTest", "attempt1");
		listener.executionStarted(classId1);
		listener.executionFinished(classId1, TestExecutionResult.failed(new AssertionError("flaky")));
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		// After plan 1, state has a failure recorded
		TestOrderState state1 = TestOrderState.load(stateFile);
		assertTrue(state1.getFailureScores().containsKey("com.example.FlakyTest"),
				"After first plan, failure should be recorded");

		// Plan 2: rerun — same test succeeds
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);
		TestIdentifier classId2 = createClassSourceTestIdentifier("com.example.FlakyTest", "attempt2");
		listener.executionStarted(classId2);
		listener.executionFinished(classId2, TestExecutionResult.successful());
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		// After plan 2, the rerun success should NOT add another failure
		TestOrderState state2 = TestOrderState.load(stateFile);
		List<TestOrderState.RunRecord> runs = state2.runs();
		// The second run record (rerun) should have 0 failures
		assertTrue(runs.size() >= 2, "Should have at least 2 run records");
		assertEquals(0, runs.get(runs.size() - 1).totalFailures(),
				"Rerun plan with passing test should record 0 failures");
	}

	/**
	 * Verifies that pending data is cleared between plan executions so durations
	 * from plan 1 are not re-applied in plan 2.
	 */
	@Test
	void rerunDoesNotDoubleDurations() throws IOException {
		Path stateFile = tempDir.resolve(".state-rerun-durations");

		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();

		// Plan 1: test runs
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);
		TestIdentifier id1 = createClassSourceTestIdentifier("com.example.SlowTest", "run1");
		listener.executionStarted(id1);
		listener.executionFinished(id1, TestExecutionResult.successful());
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		// Plan 2: rerun (same test)
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);
		TestIdentifier id2 = createClassSourceTestIdentifier("com.example.SlowTest", "run2");
		listener.executionStarted(id2);
		listener.executionFinished(id2, TestExecutionResult.successful());
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		// Load state — should have duration data (not zero, not doubled)
		TestOrderState state = TestOrderState.load(stateFile);
		long dur = state.getDuration("com.example.SlowTest", -1L);
		assertTrue(dur >= 0, "Duration should be recorded after rerun");
	}

	/**
	 * Method-level rerun recovery: a method fails, then passes on retry.
	 */
	@Test
	void rerunRecovery_methodLevel_clearsMethodFailure() throws IOException {
		Path stateFile = tempDir.resolve(".state-rerun-method");

		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TelemetryListener listener = new TelemetryListener();

		// Plan 1: method fails
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);
		TestIdentifier classId1 = createClassSourceTestIdentifier("com.example.MethodFlakyTest", "a1");
		listener.executionStarted(classId1);
		TestIdentifier methodId1 = createMethodSourceTestIdentifier("com.example.MethodFlakyTest", "flakyMethod", "m1");
		listener.executionStarted(methodId1);
		listener.executionFinished(methodId1, TestExecutionResult.failed(new AssertionError("flaky")));
		listener.executionFinished(classId1, TestExecutionResult.failed(new AssertionError("child failed")));
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		// Plan 2: method passes on retry
		listener.testPlanExecutionStarted(StubTestPlan.EMPTY);
		TestIdentifier classId2 = createClassSourceTestIdentifier("com.example.MethodFlakyTest", "a2");
		listener.executionStarted(classId2);
		TestIdentifier methodId2 = createMethodSourceTestIdentifier("com.example.MethodFlakyTest", "flakyMethod", "m2");
		listener.executionStarted(methodId2);
		listener.executionFinished(methodId2, TestExecutionResult.successful());
		listener.executionFinished(classId2, TestExecutionResult.successful());
		listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

		TestOrderState state = TestOrderState.load(stateFile);
		List<TestOrderState.RunRecord> runs = state.runs();
		assertTrue(runs.size() >= 2);
		assertEquals(0, runs.get(runs.size() - 1).totalFailures(),
				"Rerun plan with passing method should record 0 failures");
	}

	private static TestIdentifier createClassSourceTestIdentifier(String className) {
		return createClassSourceTestIdentifier(className, className);
	}

	private static TestIdentifier createClassSourceTestIdentifier(String className, String uniqueSuffix) {
		TestDescriptor stub = new StubTestDescriptor(
				UniqueId.parse("[engine:junit-jupiter]/[class:" + className + "]/[dynamic:" + uniqueSuffix + "]"),
				className, ClassSource.from(className));
		return TestIdentifier.from(stub);
	}

	private static TestIdentifier createMethodSourceTestIdentifier(String className, String methodName,
			String uniqueSuffix) {
		TestDescriptor stub = new StubTestDescriptor(UniqueId.parse("[engine:junit-jupiter]/[class:" + className
				+ "]/[method:" + methodName + "]/[dynamic:" + uniqueSuffix + "]"), className + "#" + methodName,
				MethodSource.from(className, methodName));
		return TestIdentifier.from(stub);
	}

	/**
	 * Creates a TestIdentifier with a suite engine UniqueId, simulating a test
	 * executed via @Suite. The isSuiteEngineNode() check looks for
	 * "[engine:junit-platform-suite]" in the unique ID string.
	 */
	private static TestIdentifier createSuiteEngineTestIdentifier(String className, String uniqueSuffix) {
		TestDescriptor stub = new StubTestDescriptor(UniqueId.parse("[engine:junit-platform-suite]/[suite:" + className
				+ "]/[engine:junit-jupiter]/[class:" + className + "]/[dynamic:" + uniqueSuffix + "]"), className,
				ClassSource.from(className));
		return TestIdentifier.from(stub);
	}

	/**
	 * Creates a TestIdentifier with MethodSource and type=CONTAINER, simulating a
	 *
	 * @ParameterizedTest or @TestTemplate container node.
	 */
	private static TestIdentifier createContainerMethodSourceTestIdentifier(String className, String methodName,
			String uniqueSuffix) {
		TestDescriptor stub = new ContainerStubTestDescriptor(
				UniqueId.parse("[engine:junit-jupiter]/[class:" + className + "]/[test-template:" + methodName
						+ "]/[dynamic:" + uniqueSuffix + "]"),
				className + "#" + methodName, MethodSource.from(className, methodName));
		return TestIdentifier.from(stub);
	}

	/**
	 * Creates a TestIdentifier with no source (simulating a DynamicTest child or
	 * container node that has no ClassSource/MethodSource).
	 */
	private static TestIdentifier createSourcelessTestIdentifier(String uniqueSuffix) {
		TestDescriptor stub = new SourcelessTestDescriptor(
				UniqueId.parse("[engine:junit-jupiter]/[dynamic:" + uniqueSuffix + "]"), "dynamic-" + uniqueSuffix);
		return TestIdentifier.from(stub);
	}

	/** TestDescriptor with no source — simulates DynamicTest children. */
	private static class SourcelessTestDescriptor implements TestDescriptor {
		private final UniqueId uniqueId;
		private final String displayName;
		private TestDescriptor parent;

		SourcelessTestDescriptor(UniqueId uniqueId, String displayName) {
			this.uniqueId = uniqueId;
			this.displayName = displayName;
		}

		@Override
		public UniqueId getUniqueId() {
			return uniqueId;
		}
		@Override
		public String getDisplayName() {
			return displayName;
		}
		@Override
		public Set<TestTag> getTags() {
			return Set.of();
		}
		@Override
		public Optional<TestSource> getSource() {
			return Optional.empty();
		}
		@Override
		public Optional<TestDescriptor> getParent() {
			return Optional.ofNullable(parent);
		}
		@Override
		public void setParent(TestDescriptor parent) {
			this.parent = parent;
		}
		@Override
		public Set<? extends TestDescriptor> getChildren() {
			return Set.of();
		}
		@Override
		public void addChild(TestDescriptor descriptor) {
		}
		@Override
		public void removeChild(TestDescriptor descriptor) {
		}
		@Override
		public void removeFromHierarchy() {
		}
		@Override
		public Type getType() {
			return Type.TEST;
		}
		@Override
		public Optional<? extends TestDescriptor> findByUniqueId(UniqueId uniqueId) {
			return Optional.empty();
		}
	}

	/** Minimal TestDescriptor stub that provides a ClassSource. */
	private static class StubTestDescriptor implements TestDescriptor {
		private final UniqueId uniqueId;
		private final String displayName;
		private final TestSource source;
		private TestDescriptor parent;

		StubTestDescriptor(UniqueId uniqueId, String displayName, TestSource source) {
			this.uniqueId = uniqueId;
			this.displayName = displayName;
			this.source = source;
		}

		@Override
		public UniqueId getUniqueId() {
			return uniqueId;
		}
		@Override
		public String getDisplayName() {
			return displayName;
		}
		@Override
		public Set<TestTag> getTags() {
			return Set.of();
		}
		@Override
		public Optional<TestSource> getSource() {
			return Optional.of(source);
		}
		@Override
		public Optional<TestDescriptor> getParent() {
			return Optional.ofNullable(parent);
		}
		@Override
		public void setParent(TestDescriptor parent) {
			this.parent = parent;
		}
		@Override
		public Set<? extends TestDescriptor> getChildren() {
			return Set.of();
		}
		@Override
		public void addChild(TestDescriptor descriptor) {
		}
		@Override
		public void removeChild(TestDescriptor descriptor) {
		}
		@Override
		public void removeFromHierarchy() {
		}
		@Override
		public Type getType() {
			return Type.TEST;
		}
		@Override
		public Optional<? extends TestDescriptor> findByUniqueId(UniqueId uniqueId) {
			return Optional.empty();
		}
	}

	/** A stub TestPlan that has no test identifiers. */
	private static class StubTestPlan extends TestPlan {
		static final StubTestPlan EMPTY = new StubTestPlan();

		private StubTestPlan() {
			super(false, null);
		}

		@Override
		public Set<TestIdentifier> getRoots() {
			return Set.of();
		}

		@Override
		public Set<TestIdentifier> getChildren(TestIdentifier parent) {
			return Set.of();
		}
	}

	/**
	 * TestDescriptor stub with type=CONTAINER — simulates @ParameterizedTest and
	 *
	 * @TestTemplate container nodes.
	 */
	private static class ContainerStubTestDescriptor implements TestDescriptor {
		private final UniqueId uniqueId;
		private final String displayName;
		private final TestSource source;
		private TestDescriptor parent;

		ContainerStubTestDescriptor(UniqueId uniqueId, String displayName, TestSource source) {
			this.uniqueId = uniqueId;
			this.displayName = displayName;
			this.source = source;
		}

		@Override
		public UniqueId getUniqueId() {
			return uniqueId;
		}
		@Override
		public String getDisplayName() {
			return displayName;
		}
		@Override
		public Set<TestTag> getTags() {
			return Set.of();
		}
		@Override
		public Optional<TestSource> getSource() {
			return Optional.of(source);
		}
		@Override
		public Optional<TestDescriptor> getParent() {
			return Optional.ofNullable(parent);
		}
		@Override
		public void setParent(TestDescriptor parent) {
			this.parent = parent;
		}
		@Override
		public Set<? extends TestDescriptor> getChildren() {
			return Set.of();
		}
		@Override
		public void addChild(TestDescriptor descriptor) {
		}
		@Override
		public void removeChild(TestDescriptor descriptor) {
		}
		@Override
		public void removeFromHierarchy() {
		}
		@Override
		public Type getType() {
			return Type.CONTAINER;
		}
		@Override
		public Optional<? extends TestDescriptor> findByUniqueId(UniqueId uniqueId) {
			return Optional.empty();
		}
	}
}
