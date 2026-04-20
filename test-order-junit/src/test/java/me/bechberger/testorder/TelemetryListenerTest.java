package me.bechberger.testorder;

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryListenerTest {

    @TempDir
    Path tempDir;

    private String originalLearn;
    private String originalTestClass;
    private String originalMode;
    private String originalStatePath;

    @BeforeEach
    void saveProperties() {
        originalLearn = System.getProperty("testorder.learn");
        originalTestClass = System.getProperty("testorder.current.testclass");
        originalMode = System.getProperty("testorder.instrumentation.mode");
        originalStatePath = System.getProperty("testorder.state.path");
    }

    @AfterEach
    void restoreProperties() {
        restoreProp("testorder.learn", originalLearn);
        restoreProp("testorder.current.testclass", originalTestClass);
        restoreProp("testorder.instrumentation.mode", originalMode);
        restoreProp("testorder.state.path", originalStatePath);
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
        System.setProperty("testorder.instrumentation.mode", "METHOD_ENTRY");
        System.setProperty("testorder.state.path", stateFile.toString());

        TelemetryListener listener = new TelemetryListener();
        listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

        TestIdentifier testId = createClassSourceTestIdentifier("com.example.FooTest");
        listener.executionStarted(testId);
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
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
        System.setProperty("testorder.instrumentation.mode", "METHOD_ENTRY");
        System.setProperty("testorder.state.path", stateFile.toString());

        TelemetryListener listener = new TelemetryListener();
        listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

        TestIdentifier testId = createClassSourceTestIdentifier("com.example.FooTest");
        listener.executionStarted(testId);
        listener.executionFinished(testId,
                TestExecutionResult.failed(new AssertionError("test failure")));
        listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

        assertTrue(Files.exists(stateFile), "State file should be saved in learn mode");
        TestOrderState state = TestOrderState.load(stateFile);
        Map<String, Double> failures = state.getFailureScores();
        assertTrue(failures.containsKey("com.example.FooTest"),
                "Failed test should be recorded in learn mode");
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
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
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

        // Force "latest sample wins" so the assertion observes the second execution directly.
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
        assertTrue(duration >= 150 && duration <= 320,
                "Duration should reflect a single run, not cumulative timing across repeated executions. Got: "
                        + duration + " ms");
    }

    @Test
    void recordsMethodDurationsUsingUniqueExecutionIds() throws IOException {
        Path stateFile = tempDir.resolve(".test-order-state");

        System.setProperty("testorder.learn", "true");
        System.setProperty("testorder.instrumentation.mode", "FULL_METHOD");
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
        PriorityMethodOrderer.setPendingState(
                new TestOrderState(),
                TestOrderState.MethodScoringWeights.DEFAULT,
                true,
                new DependencyMap(),
                Set.of("com.example.Changed"),
                Set.of("com.example.FooTest#testA"));

        TelemetryListener listener = new TelemetryListener();
        listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

        assertFalse(isMethodOrderingStateConfigured());
    }

    private static boolean isMethodOrderingStateConfigured() {
        try {
            var field = PriorityMethodOrderer.class.getDeclaredField("pendingState");
            field.setAccessible(true);
            return field.get(null) != null;
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
            // Use the inner class annotated @Execution(CONCURRENT) which is on the classpath
            TestIdentifier testId = createClassSourceTestIdentifier(
                    ConcurrentDummyTest.class.getName());
            listener.executionStarted(testId);
        } finally {
            System.setErr(oldErr);
        }

        String output = buf.toString();
        assertTrue(output.contains("CONCURRENT") || output.contains("concurrent"),
                "Should warn about @Execution(CONCURRENT), got: " + output);
    }

    /** Dummy test class annotated with @Execution(CONCURRENT) for use in the warn test. */
    @Execution(ExecutionMode.CONCURRENT)
    static class ConcurrentDummyTest {
        @Test void noop() {}
    }

    @Test
    void durationRecordedIsNeverNegative() throws IOException {
        Path stateFile = tempDir.resolve(".test-order-state-nonneg");
        System.setProperty("testorder.learn", "true");
        System.setProperty("testorder.instrumentation.mode", "METHOD_ENTRY");
        System.setProperty("testorder.state.path", stateFile.toString());

        TelemetryListener listener = new TelemetryListener();
        listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

        TestIdentifier testId = createClassSourceTestIdentifier("com.example.DurationTest");
        listener.executionStarted(testId);
        // Immediately finish (no sleep) — elapsed could theoretically round to 0, never negative
        listener.executionFinished(testId, TestExecutionResult.successful());
        listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

        TestOrderState state = TestOrderState.load(stateFile);
        long dur = state.getDuration("com.example.DurationTest", -99L);
        assertTrue(dur >= 0, "Duration must be non-negative, got: " + dur);
    }

    // ── Tier 3b: agent version mismatch & concurrent interleaving ──────────

    /**
     * E3b-1: When the agent runtime (UsageStore) is not on the classpath — the same code
     * path triggered by an agent version mismatch (ClassNotFoundException / NoSuchMethodException)
     * — the listener must still record durations and failures normally in order mode.
     * The reflection failure is swallowed and the listener degrades gracefully.
     */
    @Test
    void agentUnavailableDoesNotPreventDurationRecordingInOrderMode() throws IOException {
        Path stateFile = tempDir.resolve(".state-agent-unavailable");
        // No testorder.learn → order mode; reflection will fail (agent not on classpath)
        System.clearProperty("testorder.learn");
        System.setProperty("testorder.state.path", stateFile.toString());

        TelemetryListener listener = new TelemetryListener();
        listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

        TestIdentifier id = createClassSourceTestIdentifier("com.example.SomeTest");
        listener.executionStarted(id);
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
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
        System.setProperty("testorder.instrumentation.mode", "METHOD_ENTRY");
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
     * E3b-3: Concurrent interleaving — class A starts, class B starts, class A finishes,
     * class B finishes. The listener must not crash and must record BOTH durations.
     * (Tests that putIfAbsent correctly handles interleaved executions.)
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
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        listener.executionFinished(classA, TestExecutionResult.successful());
        listener.executionFinished(classB, TestExecutionResult.successful());
        listener.testPlanExecutionFinished(StubTestPlan.EMPTY);

        assertTrue(Files.exists(stateFile));
        TestOrderState state = TestOrderState.load(stateFile);
        assertTrue(state.getDuration("com.example.ClassATest", -1L) >= 0,
                "ClassA duration must be recorded");
        assertTrue(state.getDuration("com.example.ClassBTest", -1L) >= 0,
                "ClassB duration must be recorded");
    }

    /**
     * Creates a TestIdentifier backed by a ClassSource via a stub TestDescriptor.
     */
    @Test
    void toTopLevelClassNameStripsNestedSuffix() {
        assertEquals("com.example.OuterTest",
                TelemetryListener.toTopLevelClassName("com.example.OuterTest$InnerTest"));
        assertEquals("com.example.OuterTest",
                TelemetryListener.toTopLevelClassName("com.example.OuterTest$A$B"));
        assertEquals("com.example.FooTest",
                TelemetryListener.toTopLevelClassName("com.example.FooTest"));
    }

    @Test
    void nestedClassFailureRecordedUnderTopLevelClass() throws IOException {
        Path stateFile = tempDir.resolve(".test-order-state");

        System.setProperty("testorder.learn", "true");
        System.setProperty("testorder.instrumentation.mode", "METHOD_ENTRY");
        System.setProperty("testorder.state.path", stateFile.toString());

        TelemetryListener listener = new TelemetryListener();
        listener.testPlanExecutionStarted(StubTestPlan.EMPTY);

        // Simulate a failure in a @Nested class — MethodSource reports the nested class name
        TestIdentifier methodId = createMethodSourceTestIdentifier(
                "com.example.OuterTest$InnerTest", "failingTest", "nested-fail");
        listener.executionStarted(methodId);
        listener.executionFinished(methodId,
                TestExecutionResult.failed(new AssertionError("nested failure")));
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

    private static TestIdentifier createClassSourceTestIdentifier(String className) {
        return createClassSourceTestIdentifier(className, className);
    }

    private static TestIdentifier createClassSourceTestIdentifier(String className, String uniqueSuffix) {
        TestDescriptor stub = new StubTestDescriptor(
                UniqueId.parse("[engine:junit-jupiter]/[class:" + className + "]/[dynamic:" + uniqueSuffix + "]"),
                className,
                ClassSource.from(className));
        return TestIdentifier.from(stub);
    }

    private static TestIdentifier createMethodSourceTestIdentifier(String className, String methodName, String uniqueSuffix) {
        TestDescriptor stub = new StubTestDescriptor(
                UniqueId.parse("[engine:junit-jupiter]/[class:" + className + "]/[method:" + methodName + "]/[dynamic:" + uniqueSuffix + "]"),
                className + "#" + methodName,
                MethodSource.from(className, methodName));
        return TestIdentifier.from(stub);
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

        @Override public UniqueId getUniqueId() { return uniqueId; }
        @Override public String getDisplayName() { return displayName; }
        @Override public Set<TestTag> getTags() { return Set.of(); }
        @Override public Optional<TestSource> getSource() { return Optional.of(source); }
        @Override public Optional<TestDescriptor> getParent() { return Optional.ofNullable(parent); }
        @Override public void setParent(TestDescriptor parent) { this.parent = parent; }
        @Override public Set<? extends TestDescriptor> getChildren() { return Set.of(); }
        @Override public void addChild(TestDescriptor descriptor) { }
        @Override public void removeChild(TestDescriptor descriptor) { }
        @Override public void removeFromHierarchy() { }
        @Override public Type getType() { return Type.TEST; }
        @Override public Optional<? extends TestDescriptor> findByUniqueId(UniqueId uniqueId) {
            return Optional.empty();
        }
    }

    /** A stub TestPlan that has no test identifiers. */
    private static class StubTestPlan extends TestPlan {
        static final StubTestPlan EMPTY = new StubTestPlan();

        private StubTestPlan() {
            super(false, null, null);
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
}
