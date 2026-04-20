package me.bechberger.testorder;

import org.junit.jupiter.api.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PriorityMethodOrderer}: ordering, graceful degradation,
 * state lifecycle, and edge cases E25–E27.
 */
class PriorityMethodOrdererTest {

    // Dummy class whose real Method objects we use in contexts
    static class DummyTests {
        void alpha() {}
        void beta() {}
        void gamma() {}
    }

    private static Method alphaMethod;
    private static Method betaMethod;
    private static Method gammaMethod;

    @BeforeAll
    static void loadMethods() throws NoSuchMethodException {
        alphaMethod = DummyTests.class.getDeclaredMethod("alpha");
        betaMethod  = DummyTests.class.getDeclaredMethod("beta");
        gammaMethod = DummyTests.class.getDeclaredMethod("gamma");
    }

    @AfterEach
    void clearState() {
        PriorityMethodOrderer.clearPendingState();
    }

    // ── Stubs ──────────────────────────────────────────────────────────

    static StubMethodDescriptor desc(Method m) {
        return new StubMethodDescriptor(m);
    }

    static class StubMethodDescriptor implements MethodDescriptor {
        private final Method method;

        StubMethodDescriptor(Method method) {
            this.method = method;
        }

        @Override public Method getMethod() { return method; }
        @Override public String getDisplayName() { return method.getName(); }
        @Override public boolean isAnnotated(Class<? extends Annotation> t) { return false; }
        @Override public <A extends Annotation> Optional<A> findAnnotation(Class<A> t) { return Optional.empty(); }
        @Override public <A extends Annotation> List<A> findRepeatableAnnotations(Class<A> t) { return List.of(); }
    }

    static class StubMethodOrdererContext implements MethodOrdererContext {
        private final Class<?> testClass;
        private final List<MethodDescriptor> descriptors;

        StubMethodOrdererContext(Class<?> testClass, List<StubMethodDescriptor> descs) {
            this.testClass = testClass;
            this.descriptors = new ArrayList<>(descs);
        }

        @Override public Class<?> getTestClass() { return testClass; }
        @Override public List<MethodDescriptor> getMethodDescriptors() { return descriptors; }
        @Override public Optional<String> getConfigurationParameter(String key) { return Optional.empty(); }
    }

    // ── E27: No pending state → default (source) order preserved ──────

    @Test
    @DisplayName("E27: noPendingState — orderMethods does not reorder")
    void orderMethods_noPendingState_defaultOrder() {
        // clearPendingState is already the initial state; no state set here
        PriorityMethodOrderer orderer = new PriorityMethodOrderer();
        List<StubMethodDescriptor> descs = new ArrayList<>(List.of(
                desc(gammaMethod), desc(alphaMethod), desc(betaMethod)));
        var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
        orderer.orderMethods(ctx);

        // No state → returns early; order must be unchanged
        assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName());
        assertEquals("alpha", ctx.getMethodDescriptors().get(1).getMethod().getName());
        assertEquals("beta",  ctx.getMethodDescriptors().get(2).getMethod().getName());
    }

    // ── E25: Empty method list → no exception ─────────────────────────

    @Test
    @DisplayName("E25: emptyMethodList — orderMethods() must not throw")
    void orderMethods_emptyMethodList_noOp() {
        TestOrderState state = new TestOrderState();
        PriorityMethodOrderer.setPendingState(
                state, TestOrderState.MethodScoringWeights.DEFAULT,
                true, new DependencyMap(), Set.of(), Set.of());

        PriorityMethodOrderer orderer = new PriorityMethodOrderer();
        var ctx = new StubMethodOrdererContext(DummyTests.class, List.of());
        assertDoesNotThrow(() -> orderer.orderMethods(ctx));
        assertTrue(ctx.getMethodDescriptors().isEmpty());
    }

    // ── Disabled flag → source order preserved ────────────────────────

    @Test
    @DisplayName("disabled=false → orderMethods does not reorder")
    void orderMethods_disabled_preservesSourceOrder() {
        TestOrderState state = new TestOrderState();
        // setPendingState with enabled=false
        PriorityMethodOrderer.setPendingState(
                state, TestOrderState.MethodScoringWeights.DEFAULT,
                false, new DependencyMap(), Set.of(), Set.of());

        PriorityMethodOrderer orderer = new PriorityMethodOrderer();
        List<StubMethodDescriptor> descs = new ArrayList<>(List.of(
                desc(gammaMethod), desc(alphaMethod), desc(betaMethod)));
        var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
        orderer.orderMethods(ctx);

        assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName());
        assertEquals("alpha", ctx.getMethodDescriptors().get(1).getMethod().getName());
        assertEquals("beta",  ctx.getMethodDescriptors().get(2).getMethod().getName());
    }

    // ── No telemetry → source order preserved ─────────────────────────

    @Test
    @DisplayName("No telemetry (no durations, no failures) → source order")
    void orderMethods_noTelemetry_preservesSourceOrder() {
        // State with no recorded method durations or failures
        TestOrderState state = new TestOrderState();
        PriorityMethodOrderer.setPendingState(
                state, TestOrderState.MethodScoringWeights.DEFAULT,
                true, new DependencyMap(), Set.of(), Set.of());

        PriorityMethodOrderer orderer = new PriorityMethodOrderer();
        List<StubMethodDescriptor> descs = new ArrayList<>(List.of(
                desc(gammaMethod), desc(alphaMethod), desc(betaMethod)));
        var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
        orderer.orderMethods(ctx);

        // All durations unknown (-1) and no failures → early return → source order
        assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName());
        assertEquals("alpha", ctx.getMethodDescriptors().get(1).getMethod().getName());
        assertEquals("beta",  ctx.getMethodDescriptors().get(2).getMethod().getName());
    }

    // ── Failure score → failing method runs first ──────────────────────

    @Test
    @DisplayName("Method with failure score runs before methods with no failure")
    void orderMethods_withFailure_failingMethodRunsFirst() {
        String className = DummyTests.class.getName();
        TestOrderState state = new TestOrderState();
        // Record a failure for "beta" — it should be sorted to the top
        state.recordMethodFailure(className, "beta");

        PriorityMethodOrderer.setPendingState(
                state, TestOrderState.MethodScoringWeights.DEFAULT,
                true, new DependencyMap(), Set.of(), Set.of());

        PriorityMethodOrderer orderer = new PriorityMethodOrderer();
        // Original order: alpha, beta, gamma
        List<StubMethodDescriptor> descs = new ArrayList<>(List.of(
                desc(alphaMethod), desc(betaMethod), desc(gammaMethod)));
        var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
        orderer.orderMethods(ctx);

        assertEquals("beta", ctx.getMethodDescriptors().get(0).getMethod().getName(),
                "Method with failure score must run first");
    }

    // ── Duration-based ordering → fast method runs before slow ────────

    @Test
    @DisplayName("Shorter-duration method gets speed bonus and runs before slow method")
    void orderMethods_withDurations_fastMethodRunsFirst() {
        String className = DummyTests.class.getName();
        TestOrderState state = new TestOrderState();
        // alpha: 10ms (fast), beta: 1000ms (slow), gamma: 10ms (same as alpha)
        state.recordMethodDuration(className, "alpha", 10);
        state.recordMethodDuration(className, "beta", 1000);
        state.recordMethodDuration(className, "gamma", 10);

        PriorityMethodOrderer.setPendingState(
                state, TestOrderState.MethodScoringWeights.DEFAULT,
                true, new DependencyMap(), Set.of(), Set.of());

        PriorityMethodOrderer orderer = new PriorityMethodOrderer();
        // Original order: beta(slow), alpha(fast), gamma(fast)
        List<StubMethodDescriptor> descs = new ArrayList<>(List.of(
                desc(betaMethod), desc(alphaMethod), desc(gammaMethod)));
        var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
        orderer.orderMethods(ctx);

        // beta (slow) must not be first; alpha or gamma (both fast) should be first
        assertNotEquals("beta", ctx.getMethodDescriptors().get(0).getMethod().getName(),
                "Slow method must not run first when fast methods exist");
        // beta (slow, penalized) must be last
        assertEquals("beta", ctx.getMethodDescriptors().get(2).getMethod().getName(),
                "Slow method must run last");
    }

    // ── E26: All tied → source order preserved ────────────────────────

    @Test
    @DisplayName("E26: All methods tied in score → source order preserved")
    void orderMethods_allTied_preservesSourceOrder() {
        String className = DummyTests.class.getName();
        TestOrderState state = new TestOrderState();
        // All methods identical duration → same median → same speed bonus (0)
        // No failures → tie on all scores → source order by index
        state.recordMethodDuration(className, "alpha", 100);
        state.recordMethodDuration(className, "beta",  100);
        state.recordMethodDuration(className, "gamma", 100);

        PriorityMethodOrderer.setPendingState(
                state, TestOrderState.MethodScoringWeights.DEFAULT,
                true, new DependencyMap(), Set.of(), Set.of());

        PriorityMethodOrderer orderer = new PriorityMethodOrderer();
        // Original order: gamma, alpha, beta
        List<StubMethodDescriptor> descs = new ArrayList<>(List.of(
                desc(gammaMethod), desc(alphaMethod), desc(betaMethod)));
        var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
        orderer.orderMethods(ctx);

        assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName(),
                "Tied scores must preserve original source order");
        assertEquals("alpha", ctx.getMethodDescriptors().get(1).getMethod().getName());
        assertEquals("beta",  ctx.getMethodDescriptors().get(2).getMethod().getName());
    }

    // ── clearPendingState resets all fields ───────────────────────────

    @Test
    @DisplayName("clearPendingState() causes subsequent orderMethods to be a no-op")
    void clearPendingState_resetsFields() {
        String className = DummyTests.class.getName();
        TestOrderState state = new TestOrderState();
        state.recordMethodFailure(className, "gamma");

        PriorityMethodOrderer.setPendingState(
                state, TestOrderState.MethodScoringWeights.DEFAULT,
                true, new DependencyMap(), Set.of(), Set.of());

        // Verify the state is active (gamma should move to first)
        PriorityMethodOrderer orderer = new PriorityMethodOrderer();
        var ctx1 = new StubMethodOrdererContext(DummyTests.class, List.of(
                desc(alphaMethod), desc(betaMethod), desc(gammaMethod)));
        orderer.orderMethods(ctx1);
        assertEquals("gamma", ctx1.getMethodDescriptors().get(0).getMethod().getName());

        // Now clear and verify subsequent call is a no-op
        PriorityMethodOrderer.clearPendingState();
        var ctx2 = new StubMethodOrdererContext(DummyTests.class, List.of(
                desc(alphaMethod), desc(betaMethod), desc(gammaMethod)));
        orderer.orderMethods(ctx2);
        assertEquals("alpha", ctx2.getMethodDescriptors().get(0).getMethod().getName(),
                "After clearPendingState, orderMethods must preserve original order");
    }

    // ── Changed method bonus ───────────────────────────────────────────

    @Test
    @DisplayName("Changed method runs before unchanged methods with durations")
    void orderMethods_changedMethod_runsFirst() {
        String className = DummyTests.class.getName();
        TestOrderState state = new TestOrderState();
        // Give all methods the same duration so the only differentiator is changed flag
        state.recordMethodDuration(className, "alpha", 100);
        state.recordMethodDuration(className, "beta",  100);
        state.recordMethodDuration(className, "gamma", 100);

        // Mark "beta" as changed
        String betaKey = className + "#beta";
        Set<String> changedMethods = Set.of(betaKey);

        PriorityMethodOrderer.setPendingState(
                state, TestOrderState.MethodScoringWeights.DEFAULT,
                true, new DependencyMap(), Set.of(), changedMethods);

        PriorityMethodOrderer orderer = new PriorityMethodOrderer();
        // Original order: alpha, beta, gamma
        List<StubMethodDescriptor> descs = new ArrayList<>(List.of(
                desc(alphaMethod), desc(betaMethod), desc(gammaMethod)));
        var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
        orderer.orderMethods(ctx);

        assertEquals("beta", ctx.getMethodDescriptors().get(0).getMethod().getName(),
                "Changed method must run first when scores are otherwise equal");
    }

    // ── Single method → no exception ──────────────────────────────────

    @Test
    @DisplayName("Single method list with failure score does not throw")
    void orderMethods_singleMethod_noException() {
        String className = DummyTests.class.getName();
        TestOrderState state = new TestOrderState();
        state.recordMethodFailure(className, "alpha");

        PriorityMethodOrderer.setPendingState(
                state, TestOrderState.MethodScoringWeights.DEFAULT,
                true, new DependencyMap(), Set.of(), Set.of());

        PriorityMethodOrderer orderer = new PriorityMethodOrderer();
        List<StubMethodDescriptor> descs = new ArrayList<>(List.of(desc(alphaMethod)));
        var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
        assertDoesNotThrow(() -> orderer.orderMethods(ctx));
        assertEquals("alpha", ctx.getMethodDescriptors().get(0).getMethod().getName());
    }

    @Test
    @DisplayName("clearPendingState sets pendingState to null")
    void clearPendingState_resetsState() throws Exception {
        PriorityMethodOrderer.setPendingState(
                new TestOrderState(), TestOrderState.MethodScoringWeights.DEFAULT,
                true, new DependencyMap(), Set.of(), Set.of());

        PriorityMethodOrderer.clearPendingState();

        // pendingState is private; verify indirectly: orderMethods with no state should no-op
        PriorityMethodOrderer orderer = new PriorityMethodOrderer();
        List<StubMethodDescriptor> descs = new ArrayList<>(List.of(desc(alphaMethod), desc(betaMethod)));
        var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
        // Should not throw — just leave order unchanged
        assertDoesNotThrow(() -> orderer.orderMethods(ctx));
        // After clearing, pendingState field should be null (check via reflection)
        var field = PriorityMethodOrderer.class.getDeclaredField("pendingState");
        field.setAccessible(true);
        assertNull(field.get(null), "pendingState should be null after clearPendingState()");
    }
}
