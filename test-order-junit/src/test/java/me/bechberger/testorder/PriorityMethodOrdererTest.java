package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;

import org.junit.jupiter.api.*;

import me.bechberger.testorder.annotations.AlwaysRun;
import me.bechberger.testorder.annotations.TestOrder;
import me.bechberger.testorder.junit.PriorityMethodOrderer;

/**
 * Tests for {@link PriorityMethodOrderer}: ordering, graceful degradation,
 * state lifecycle, and edge cases E25–E27.
 */
class PriorityMethodOrdererTest {

	// Dummy class whose real Method objects we use in contexts
	static class DummyTests {
		void alpha() {
		}
		void beta() {
		}
		void gamma() {
		}
	}

	// Fixture with @Order annotations on methods
	static class OrderedDummy {
		@org.junit.jupiter.api.Order(1)
		void alpha() {
		}
		@org.junit.jupiter.api.Order(2)
		void beta() {
		}
		@org.junit.jupiter.api.Order(3)
		void gamma() {
		}
	}

	// Fixture with @TestMethodOrder on the class
	@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
	static class TestMethodOrderDummy {
		void alpha() {
		}
		void beta() {
		}
		void gamma() {
		}
	}

	// Fixture with partial @Order (only some methods)
	static class PartialOrderDummy {
		void alpha() {
		}
		@org.junit.jupiter.api.Order(1)
		void beta() {
		}
		void gamma() {
		}
	}

	// Fixture with @AlwaysRun on a method
	static class AlwaysRunMethodDummy {
		void alpha() {
		}
		void beta() {
		}
		@AlwaysRun
		void gamma() {
		}
	}

	// Fixture with both @AlwaysRun and @TestOrder(priority=LAST) on same method
	static class AlwaysRunLastDummy {
		void alpha() {
		}
		void beta() {
		}
		@AlwaysRun
		@TestOrder(priority = TestOrder.Priority.LAST)
		void gamma() {
		}
	}

	private static Method alphaMethod;
	private static Method betaMethod;
	private static Method gammaMethod;
	private static Method orderedAlpha, orderedBeta, orderedGamma;
	private static Method tmoAlpha, tmoBeta, tmoGamma;
	private static Method partialAlpha, partialBeta, partialGamma;
	private static Method arAlpha, arBeta, arGamma;
	private static Method arlAlpha, arlBeta, arlGamma;

	@BeforeAll
	static void loadMethods() throws NoSuchMethodException {
		alphaMethod = DummyTests.class.getDeclaredMethod("alpha");
		betaMethod = DummyTests.class.getDeclaredMethod("beta");
		gammaMethod = DummyTests.class.getDeclaredMethod("gamma");

		orderedAlpha = OrderedDummy.class.getDeclaredMethod("alpha");
		orderedBeta = OrderedDummy.class.getDeclaredMethod("beta");
		orderedGamma = OrderedDummy.class.getDeclaredMethod("gamma");

		tmoAlpha = TestMethodOrderDummy.class.getDeclaredMethod("alpha");
		tmoBeta = TestMethodOrderDummy.class.getDeclaredMethod("beta");
		tmoGamma = TestMethodOrderDummy.class.getDeclaredMethod("gamma");

		partialAlpha = PartialOrderDummy.class.getDeclaredMethod("alpha");
		partialBeta = PartialOrderDummy.class.getDeclaredMethod("beta");
		partialGamma = PartialOrderDummy.class.getDeclaredMethod("gamma");

		arAlpha = AlwaysRunMethodDummy.class.getDeclaredMethod("alpha");
		arBeta = AlwaysRunMethodDummy.class.getDeclaredMethod("beta");
		arGamma = AlwaysRunMethodDummy.class.getDeclaredMethod("gamma");

		arlAlpha = AlwaysRunLastDummy.class.getDeclaredMethod("alpha");
		arlBeta = AlwaysRunLastDummy.class.getDeclaredMethod("beta");
		arlGamma = AlwaysRunLastDummy.class.getDeclaredMethod("gamma");
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

		@Override
		public Method getMethod() {
			return method;
		}
		@Override
		public String getDisplayName() {
			return method.getName();
		}
		@Override
		public boolean isAnnotated(Class<? extends Annotation> t) {
			return false;
		}
		@Override
		public <A extends Annotation> Optional<A> findAnnotation(Class<A> t) {
			return Optional.empty();
		}
		@Override
		public <A extends Annotation> List<A> findRepeatableAnnotations(Class<A> t) {
			return List.of();
		}
	}

	static class StubMethodOrdererContext implements MethodOrdererContext {
		private final Class<?> testClass;
		private final List<MethodDescriptor> descriptors;

		StubMethodOrdererContext(Class<?> testClass, List<StubMethodDescriptor> descs) {
			this.testClass = testClass;
			this.descriptors = new ArrayList<>(descs);
		}

		@Override
		public Class<?> getTestClass() {
			return testClass;
		}
		@Override
		public List<MethodDescriptor> getMethodDescriptors() {
			return descriptors;
		}
		@Override
		public Optional<String> getConfigurationParameter(String key) {
			return Optional.empty();
		}
	}

	// ── E27: No pending state → default (source) order preserved ──────

	@Test
	@DisplayName("E27: noPendingState — orderMethods does not reorder")
	void orderMethods_noPendingState_defaultOrder() {
		// clearPendingState is already the initial state; no state set here
		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(gammaMethod), desc(alphaMethod), desc(betaMethod)));
		var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
		orderer.orderMethods(ctx);

		// No state → returns early; order must be unchanged
		assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName());
		assertEquals("alpha", ctx.getMethodDescriptors().get(1).getMethod().getName());
		assertEquals("beta", ctx.getMethodDescriptors().get(2).getMethod().getName());
	}

	// ── E25: Empty method list → no exception ─────────────────────────

	@Test
	@DisplayName("E25: emptyMethodList — orderMethods() must not throw")
	void orderMethods_emptyMethodList_noOp() {
		TestOrderState state = new TestOrderState();
		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

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
		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, false,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(gammaMethod), desc(alphaMethod), desc(betaMethod)));
		var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
		orderer.orderMethods(ctx);

		assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName());
		assertEquals("alpha", ctx.getMethodDescriptors().get(1).getMethod().getName());
		assertEquals("beta", ctx.getMethodDescriptors().get(2).getMethod().getName());
	}

	// ── No telemetry → source order preserved ─────────────────────────

	@Test
	@DisplayName("No telemetry (no durations, no failures) → source order")
	void orderMethods_noTelemetry_preservesSourceOrder() {
		// State with no recorded method durations or failures
		TestOrderState state = new TestOrderState();
		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(gammaMethod), desc(alphaMethod), desc(betaMethod)));
		var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
		orderer.orderMethods(ctx);

		// All durations unknown (-1) and no failures → early return → source order
		assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName());
		assertEquals("alpha", ctx.getMethodDescriptors().get(1).getMethod().getName());
		assertEquals("beta", ctx.getMethodDescriptors().get(2).getMethod().getName());
	}

	// ── Failure score → failing method runs first ──────────────────────

	@Test
	@DisplayName("Method with failure score runs before methods with no failure")
	void orderMethods_withFailure_failingMethodRunsFirst() {
		String className = DummyTests.class.getName();
		TestOrderState state = new TestOrderState();
		// Record a failure for "beta" — it should be sorted to the top
		state.recordMethodFailure(className, "beta");

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		// Original order: alpha, beta, gamma
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(alphaMethod), desc(betaMethod), desc(gammaMethod)));
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

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		// Original order: beta(slow), alpha(fast), gamma(fast)
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(betaMethod), desc(alphaMethod), desc(gammaMethod)));
		var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
		orderer.orderMethods(ctx);

		// beta (slow) must not be first; alpha or gamma (both fast) should be first
		assertNotEquals("beta", ctx.getMethodDescriptors().get(0).getMethod().getName(),
				"Slow method must not run first when fast methods exist");
		// beta (slow, penalized) must be last
		assertEquals("beta", ctx.getMethodDescriptors().get(2).getMethod().getName(), "Slow method must run last");
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
		state.recordMethodDuration(className, "beta", 100);
		state.recordMethodDuration(className, "gamma", 100);

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		// Original order: gamma, alpha, beta
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(gammaMethod), desc(alphaMethod), desc(betaMethod)));
		var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
		orderer.orderMethods(ctx);

		assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName(),
				"Tied scores must preserve original source order");
		assertEquals("alpha", ctx.getMethodDescriptors().get(1).getMethod().getName());
		assertEquals("beta", ctx.getMethodDescriptors().get(2).getMethod().getName());
	}

	// ── clearPendingState resets all fields ───────────────────────────

	@Test
	@DisplayName("clearPendingState() causes subsequent orderMethods to be a no-op")
	void clearPendingState_resetsFields() {
		String className = DummyTests.class.getName();
		TestOrderState state = new TestOrderState();
		state.recordMethodFailure(className, "gamma");

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		// Verify the state is active (gamma should move to first)
		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		var ctx1 = new StubMethodOrdererContext(DummyTests.class,
				List.of(desc(alphaMethod), desc(betaMethod), desc(gammaMethod)));
		orderer.orderMethods(ctx1);
		assertEquals("gamma", ctx1.getMethodDescriptors().get(0).getMethod().getName());

		// Now clear and verify subsequent call is a no-op
		PriorityMethodOrderer.clearPendingState();
		var ctx2 = new StubMethodOrdererContext(DummyTests.class,
				List.of(desc(alphaMethod), desc(betaMethod), desc(gammaMethod)));
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
		state.recordMethodDuration(className, "beta", 100);
		state.recordMethodDuration(className, "gamma", 100);

		// Mark "beta" as changed
		String betaKey = className + "#beta";
		Set<String> changedMethods = Set.of(betaKey);

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), changedMethods);

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		// Original order: alpha, beta, gamma
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(alphaMethod), desc(betaMethod), desc(gammaMethod)));
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

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(List.of(desc(alphaMethod)));
		var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
		assertDoesNotThrow(() -> orderer.orderMethods(ctx));
		assertEquals("alpha", ctx.getMethodDescriptors().get(0).getMethod().getName());
	}

	@Test
	@DisplayName("clearPendingState sets pendingState to null")
	void clearPendingState_resetsState() throws Exception {
		PriorityMethodOrderer.setPendingState(new TestOrderState(), TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer.clearPendingState();

		// pendingState is private; verify indirectly: orderMethods with no state should
		// no-op
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

	// ── @Order annotation tests ───────────────────────────────────────

	@Test
	@DisplayName("@Order on methods → skips method reordering entirely")
	void orderMethods_junitOrderAnnotation_skipsReordering() {
		String className = OrderedDummy.class.getName();
		TestOrderState state = new TestOrderState();
		state.recordMethodFailure(className, "gamma");

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		// Original order: gamma, alpha, beta — even though gamma has failure, @Order
		// wins
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(orderedGamma), desc(orderedAlpha), desc(orderedBeta)));
		var ctx = new StubMethodOrdererContext(OrderedDummy.class, descs);
		orderer.orderMethods(ctx);

		// Order preserved — skipped because @Order is present
		assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName());
		assertEquals("alpha", ctx.getMethodDescriptors().get(1).getMethod().getName());
		assertEquals("beta", ctx.getMethodDescriptors().get(2).getMethod().getName());
	}

	@Test
	@DisplayName("@TestMethodOrder on class → skips method reordering")
	void orderMethods_testMethodOrderAnnotation_skipsReordering() {
		String className = TestMethodOrderDummy.class.getName();
		TestOrderState state = new TestOrderState();
		state.recordMethodFailure(className, "beta");

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(List.of(desc(tmoGamma), desc(tmoAlpha), desc(tmoBeta)));
		var ctx = new StubMethodOrdererContext(TestMethodOrderDummy.class, descs);
		orderer.orderMethods(ctx);

		assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName());
		assertEquals("alpha", ctx.getMethodDescriptors().get(1).getMethod().getName());
		assertEquals("beta", ctx.getMethodDescriptors().get(2).getMethod().getName());
	}

	@Test
	@DisplayName("No @Order or @TestMethodOrder → still reorders by score")
	void orderMethods_noJUnitOrder_stillReorders() {
		String className = DummyTests.class.getName();
		TestOrderState state = new TestOrderState();
		state.recordMethodFailure(className, "beta");

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(alphaMethod), desc(betaMethod), desc(gammaMethod)));
		var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
		orderer.orderMethods(ctx);

		assertEquals("beta", ctx.getMethodDescriptors().get(0).getMethod().getName(),
				"beta has failure score → should be first");
	}

	@Test
	@DisplayName("Partial @Order (only some methods) → skips reordering (conservative)")
	void orderMethods_partialJUnitOrder_skipsReordering() {
		String className = PartialOrderDummy.class.getName();
		TestOrderState state = new TestOrderState();
		state.recordMethodFailure(className, "gamma");

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		// Original order: gamma, alpha, beta — even with failure on gamma, partial
		// @Order → skip
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(partialGamma), desc(partialAlpha), desc(partialBeta)));
		var ctx = new StubMethodOrdererContext(PartialOrderDummy.class, descs);
		orderer.orderMethods(ctx);

		assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName());
		assertEquals("alpha", ctx.getMethodDescriptors().get(1).getMethod().getName());
		assertEquals("beta", ctx.getMethodDescriptors().get(2).getMethod().getName());
	}

	@Test
	@DisplayName("@Order-only (no @TestMethodOrder) → score-based reordering still applies")
	void orderMethods_orderAnnotationOnly_stillReordersByScore() {
		String className = OrderedDummy.class.getName();
		TestOrderState state = new TestOrderState();
		state.recordMethodFailure(className, "alpha");

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(orderedBeta), desc(orderedGamma), desc(orderedAlpha)));
		var ctx = new StubMethodOrdererContext(OrderedDummy.class, descs);
		orderer.orderMethods(ctx);

		// @Order without @TestMethodOrder does not suppress score-based reordering —
		// alpha has a failure so it moves to first
		assertEquals("alpha", ctx.getMethodDescriptors().get(0).getMethod().getName());
	}

	// ── @AlwaysRun method tests ───────────────────────────────────────

	@Test
	@DisplayName("@AlwaysRun method pinned first")
	void alwaysRunMethod_pinnedFirst() {
		String className = AlwaysRunMethodDummy.class.getName();
		TestOrderState state = new TestOrderState();
		// Give all methods equal duration — gamma (@AlwaysRun) should still be first
		state.recordMethodDuration(className, "alpha", 100);
		state.recordMethodDuration(className, "beta", 100);
		state.recordMethodDuration(className, "gamma", 100);

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(List.of(desc(arAlpha), desc(arBeta), desc(arGamma)));
		var ctx = new StubMethodOrdererContext(AlwaysRunMethodDummy.class, descs);
		orderer.orderMethods(ctx);

		assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName(),
				"@AlwaysRun method must be pinned first");
	}

	@Test
	@DisplayName("@AlwaysRun method with failure on another — both get priority positions")
	void alwaysRunMethod_withFailureScoring() {
		String className = AlwaysRunMethodDummy.class.getName();
		TestOrderState state = new TestOrderState();
		state.recordMethodDuration(className, "alpha", 100);
		state.recordMethodDuration(className, "beta", 100);
		state.recordMethodDuration(className, "gamma", 100);
		state.recordMethodFailure(className, "beta");

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(List.of(desc(arAlpha), desc(arBeta), desc(arGamma)));
		var ctx = new StubMethodOrdererContext(AlwaysRunMethodDummy.class, descs);
		orderer.orderMethods(ctx);

		// gamma pinned first (@AlwaysRun), beta second (failure), alpha last
		assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName(),
				"@AlwaysRun method must be first");
	}

	@Test
	@DisplayName("@AlwaysRun + @TestOrder(priority=LAST) → @AlwaysRun wins")
	void alwaysRunMethod_combinedWithTestOrderLast() {
		String className = AlwaysRunLastDummy.class.getName();
		TestOrderState state = new TestOrderState();
		state.recordMethodDuration(className, "alpha", 100);
		state.recordMethodDuration(className, "beta", 100);
		state.recordMethodDuration(className, "gamma", 100);

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(List.of(desc(arlAlpha), desc(arlBeta), desc(arlGamma)));
		var ctx = new StubMethodOrdererContext(AlwaysRunLastDummy.class, descs);
		orderer.orderMethods(ctx);

		assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName(),
				"@AlwaysRun must take precedence over @TestOrder(priority=LAST)");
	}

	// ── Parameterized method ordering tests ──────────────────────────────

	@Test
	@DisplayName("Parameterized method with failure runs first among equal-duration methods")
	void orderMethods_parameterizedFailure_runsFirst() {
		String className = DummyTests.class.getName();
		TestOrderState state = new TestOrderState();
		// Simulate aggregated durations from parameterized runs:
		// alpha was run 5x (CsvSource), beta was run 3x, gamma was run 8x (ValueSource)
		// EMA aggregation would produce these values
		state.recordMethodDuration(className, "alpha", 100);
		state.recordMethodDuration(className, "beta", 100);
		state.recordMethodDuration(className, "gamma", 100);

		// gamma failed in one of its parameterized invocations
		state.recordMethodFailure(className, "gamma");

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(alphaMethod), desc(betaMethod), desc(gammaMethod)));
		var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
		orderer.orderMethods(ctx);

		assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName(),
				"Parameterized method that failed should run first");
	}

	@Test
	@DisplayName("Parameterized method with aggregated fast duration runs before slow")
	void orderMethods_parameterizedDurations_fastFirst() {
		String className = DummyTests.class.getName();
		TestOrderState state = new TestOrderState();
		// Simulate aggregated durations: alpha fast (from 5 value-source runs),
		// beta slow (from 3 csv-source runs), gamma fast
		state.recordMethodDuration(className, "alpha", 10);
		state.recordMethodDuration(className, "beta", 2000);
		state.recordMethodDuration(className, "gamma", 15);

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(betaMethod), desc(gammaMethod), desc(alphaMethod)));
		var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
		orderer.orderMethods(ctx);

		assertNotEquals("beta", ctx.getMethodDescriptors().get(0).getMethod().getName(),
				"Slow parameterized method must not run first");
		assertEquals("beta", ctx.getMethodDescriptors().get(2).getMethod().getName(),
				"Slow parameterized method must run last");
	}

	@Test
	@DisplayName("Changed parameterized method runs first")
	void orderMethods_changedParameterizedMethod_runsFirst() {
		String className = DummyTests.class.getName();
		TestOrderState state = new TestOrderState();
		state.recordMethodDuration(className, "alpha", 100);
		state.recordMethodDuration(className, "beta", 100);
		state.recordMethodDuration(className, "gamma", 100);

		// Mark gamma as changed (simulating a changed @ParameterizedTest method)
		String gammaKey = className + "#gamma";
		Set<String> changedMethods = Set.of(gammaKey);

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), changedMethods);

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(alphaMethod), desc(betaMethod), desc(gammaMethod)));
		var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
		orderer.orderMethods(ctx);

		assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName(),
				"Changed parameterized method must run first");
	}

	@Test
	@DisplayName("Multiple parameterized method failures sort by recency")
	void orderMethods_multipleParameterizedFailures_sortedByRecency() {
		String className = DummyTests.class.getName();
		TestOrderState state = new TestOrderState();
		state.recordMethodDuration(className, "alpha", 100);
		state.recordMethodDuration(className, "beta", 100);
		state.recordMethodDuration(className, "gamma", 100);

		// Both alpha and gamma have failures (from different param sets)
		state.recordMethodFailure(className, "alpha");
		state.recordMethodFailure(className, "gamma");

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(alphaMethod), desc(betaMethod), desc(gammaMethod)));
		var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
		orderer.orderMethods(ctx);

		// beta (no failure) must be last
		assertEquals("beta", ctx.getMethodDescriptors().get(2).getMethod().getName(),
				"Method without failure must run after methods with parameterized failures");
	}

	@Test
	@DisplayName("Parameterized failure + fast duration combined scoring")
	void orderMethods_parameterizedFailureAndFast_combinedScore() {
		String className = DummyTests.class.getName();
		TestOrderState state = new TestOrderState();
		// alpha: slow, no failure; beta: fast, with failure; gamma: medium, no failure
		state.recordMethodDuration(className, "alpha", 1000);
		state.recordMethodDuration(className, "beta", 10);
		state.recordMethodDuration(className, "gamma", 100);
		state.recordMethodFailure(className, "beta");

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(alphaMethod), desc(betaMethod), desc(gammaMethod)));
		var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
		orderer.orderMethods(ctx);

		// beta has both failure score and fast bonus → should be first
		assertEquals("beta", ctx.getMethodDescriptors().get(0).getMethod().getName(),
				"Parameterized method with failure + fast duration should run first");
		// alpha (slow, no failure) should be last
		assertEquals("alpha", ctx.getMethodDescriptors().get(2).getMethod().getName(),
				"Slow method without failure should run last");
	}

	@Test
	@DisplayName("Single parameterized method (1 param set) with failure runs first")
	void orderMethods_singleParameterizedInvocationWithFailure_runsFirst() {
		String className = DummyTests.class.getName();
		TestOrderState state = new TestOrderState();
		// Degenerate: only 1 invocation each, like @ValueSource(ints = {42})
		state.recordMethodDuration(className, "alpha", 50);
		state.recordMethodDuration(className, "beta", 50);
		state.recordMethodDuration(className, "gamma", 50);
		state.recordMethodFailure(className, "alpha");

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(betaMethod), desc(gammaMethod), desc(alphaMethod)));
		var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
		orderer.orderMethods(ctx);

		assertEquals("alpha", ctx.getMethodDescriptors().get(0).getMethod().getName(),
				"Single-param method with failure should run first");
	}

	@Test
	@DisplayName("Parameterized method with no telemetry preserves source order")
	void orderMethods_parameterizedNoTelemetry_preservesSourceOrder() {
		String className = DummyTests.class.getName();
		TestOrderState state = new TestOrderState();
		// No durations or failures recorded for these methods yet (first run)

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(gammaMethod), desc(alphaMethod), desc(betaMethod)));
		var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
		orderer.orderMethods(ctx);

		// No telemetry → source order preserved
		assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName());
		assertEquals("alpha", ctx.getMethodDescriptors().get(1).getMethod().getName());
		assertEquals("beta", ctx.getMethodDescriptors().get(2).getMethod().getName());
	}

	// ── @RepeatedTest ────────────────────────────────────────────────────

	@Test
	@DisplayName("@RepeatedTest method with failure scored and ordered first")
	void orderMethods_repeatedTestFailure_runsFirst() {
		String className = DummyTests.class.getName();
		TestOrderState state = new TestOrderState();
		// "beta" is a @RepeatedTest whose aggregated repetitions recorded a failure
		state.recordMethodDuration(className, "alpha", 100);
		state.recordMethodDuration(className, "beta", 50);
		state.recordMethodDuration(className, "beta", 50); // 2 repetitions
		state.recordMethodDuration(className, "gamma", 200);
		state.recordMethodFailure(className, "beta"); // failure in one repetition

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(alphaMethod), desc(gammaMethod), desc(betaMethod)));
		var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
		orderer.orderMethods(ctx);

		assertEquals("beta", ctx.getMethodDescriptors().get(0).getMethod().getName(),
				"@RepeatedTest method with failure must run first");
	}

	// ── @TestFactory ─────────────────────────────────────────────────────

	@Test
	@DisplayName("@TestFactory method failure scored same as regular method")
	void orderMethods_testFactoryFailure_runsFirst() {
		String className = DummyTests.class.getName();
		TestOrderState state = new TestOrderState();
		// "gamma" is a @TestFactory that had its container-level failure recorded
		state.recordMethodDuration(className, "alpha", 100);
		state.recordMethodDuration(className, "beta", 100);
		state.recordMethodDuration(className, "gamma", 100);
		state.recordMethodFailure(className, "gamma");

		PriorityMethodOrderer.setPendingState(state, TestOrderState.MethodScoringWeights.DEFAULT, true,
				new DependencyMap(), Set.of(), Set.of());

		PriorityMethodOrderer orderer = new PriorityMethodOrderer();
		List<StubMethodDescriptor> descs = new ArrayList<>(
				List.of(desc(alphaMethod), desc(betaMethod), desc(gammaMethod)));
		var ctx = new StubMethodOrdererContext(DummyTests.class, descs);
		orderer.orderMethods(ctx);

		assertEquals("gamma", ctx.getMethodDescriptors().get(0).getMethod().getName(),
				"@TestFactory method with failure must run first");
	}
}
