package me.bechberger.testorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.*;
import org.junit.platform.engine.support.descriptor.ClassSource;
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

    /**
     * Creates a TestIdentifier backed by a ClassSource via a stub TestDescriptor.
     */
    private static TestIdentifier createClassSourceTestIdentifier(String className) {
        TestDescriptor stub = new StubTestDescriptor(
                UniqueId.parse("[engine:junit-jupiter]/[class:" + className + "]"),
                className,
                ClassSource.from(className));
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
