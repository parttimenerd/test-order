package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import me.bechberger.testorder.annotations.AlwaysRun;
import me.bechberger.testorder.annotations.TestOrder;
import me.bechberger.testorder.junit.PriorityClassOrderer;
import me.bechberger.testorder.junit.PriorityMethodOrderer;
import me.bechberger.testorder.junit.TelemetryListener;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * Workflow tests that exercise all major JUnit features through the real JUnit
 * Platform Launcher. Each test:
 * <ol>
 * <li>Configures a state path via system property</li>
 * <li>Launches real test classes using the Launcher API</li>
 * <li>TelemetryListener (auto-discovered via SPI) records telemetry</li>
 * <li>Verifies durations, failures, and execution order in the state file</li>
 * </ol>
 *
 * The inner "Subject" classes are the real test classes being launched. They
 * are NOT run by surefire directly (excluded via naming convention).
 */
@Timeout(30)
class JUnitFeaturesWorkflowTest {

	@TempDir
	Path tempDir;

	private String origLearn;
	private String origStatePath;
	private String origMode;

	@BeforeEach
	void saveProperties() {
		origLearn = System.getProperty("testorder.learn");
		origStatePath = System.getProperty("testorder.state.path");
		origMode = System.getProperty("testorder.instrumentation.mode");
	}

	@AfterEach
	void restoreProperties() {
		restoreProp("testorder.learn", origLearn);
		restoreProp("testorder.state.path", origStatePath);
		restoreProp("testorder.instrumentation.mode", origMode);
		// Clean up any pending state from previous launch
		TestOrderState.resetPending();
		PriorityMethodOrderer.clearPendingState();
	}

	private void restoreProp(String key, String value) {
		if (value == null)
			System.clearProperty(key);
		else
			System.setProperty(key, value);
	}

	// ── Launcher helper ──────────────────────────────────────────────────

	/**
	 * Launch the given test class through the real JUnit Platform, which triggers
	 * TelemetryListener (registered via META-INF/services).
	 */
	private TestExecutionSummary launchTests(Class<?>... testClasses) {
		LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
				.selectors(Stream.of(testClasses).map(DiscoverySelectors::selectClass).toList()).build();

		Launcher launcher = LauncherFactory.create();
		SummaryGeneratingListener summaryListener = new SummaryGeneratingListener();
		launcher.registerTestExecutionListeners(summaryListener);
		launcher.execute(request);
		return summaryListener.getSummary();
	}

	// ── @RepeatedTest workflow ───────────────────────────────────────────

	/** Subject class: test run by the Launcher, NOT by surefire. */
	static class RepeatedSubject {
		@RepeatedTest(3)
		void repeatedAdd() {
			assertEquals(4, 2 + 2);
		}

		@Test
		void normalTest() {
			assertTrue(true);
		}
	}

	@Test
	void repeatedTestWorkflow_durationsAggregated() throws IOException {
		Path stateFile = tempDir.resolve(".state-repeated-wf");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TestExecutionSummary summary = launchTests(RepeatedSubject.class);
		assertEquals(0, summary.getTestsFailedCount(), "All tests should pass");
		// 3 repetitions + 1 normal = 4 tests
		assertTrue(summary.getTestsSucceededCount() >= 4,
				"Expected >= 4 tests (3 repetitions + 1 normal), got " + summary.getTestsSucceededCount());

		TestOrderState state = TestOrderState.load(stateFile);
		// The class should have a recorded duration
		long classDur = state.getDuration(RepeatedSubject.class.getName(), -1);
		assertTrue(classDur >= 0, "RepeatedSubject class duration must be recorded");
	}

	// ── @TestFactory / DynamicTest workflow ──────────────────────────────

	/** Subject class with @TestFactory. */
	static class TestFactorySubject {
		@TestFactory
		Collection<DynamicTest> dynamicAddTests() {
			return List.of(DynamicTest.dynamicTest("1+1=2", () -> assertEquals(2, 1 + 1)),
					DynamicTest.dynamicTest("2+3=5", () -> assertEquals(5, 2 + 3)),
					DynamicTest.dynamicTest("0+0=0", () -> assertEquals(0, 0 + 0)));
		}

		@Test
		void staticTest() {
			assertTrue(true);
		}
	}

	@Test
	void testFactoryWorkflow_factoryAndStaticBothTracked() throws IOException {
		Path stateFile = tempDir.resolve(".state-factory-wf");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TestExecutionSummary summary = launchTests(TestFactorySubject.class);
		assertEquals(0, summary.getTestsFailedCount(), "All tests should pass");
		// 3 dynamic + 1 static = 4 tests
		assertTrue(summary.getTestsSucceededCount() >= 4,
				"Expected >= 4 tests (3 dynamic + 1 static), got " + summary.getTestsSucceededCount());

		TestOrderState state = TestOrderState.load(stateFile);
		long classDur = state.getDuration(TestFactorySubject.class.getName(), -1);
		assertTrue(classDur >= 0, "TestFactorySubject class duration must be recorded");
	}

	// ── @Disabled workflow ──────────────────────────────────────────────

	/** Subject with one enabled and one disabled test. */
	static class DisabledSubject {
		@Test
		void enabledTest() {
			assertTrue(true);
		}

		@Disabled("Intentionally disabled for workflow test")
		@Test
		void disabledTest() {
			fail("Should never run");
		}
	}

	@Disabled("Entire class is disabled")
	static class FullyDisabledSubject {
		@Test
		void shouldNeverRun() {
			fail("Should never run");
		}
	}

	@Test
	void disabledWorkflow_disabledTestsExcluded() throws IOException {
		Path stateFile = tempDir.resolve(".state-disabled-wf");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TestExecutionSummary summary = launchTests(DisabledSubject.class, FullyDisabledSubject.class);
		assertEquals(0, summary.getTestsFailedCount(), "No tests should fail");
		// Only 1 enabled test should run
		assertEquals(1, summary.getTestsSucceededCount(), "Only the enabled test should run");
		// The disabled test should be skipped
		assertTrue(summary.getTestsSkippedCount() >= 1, "At least 1 test should be skipped (disabled)");

		TestOrderState state = TestOrderState.load(stateFile);
		// Only the enabled class should have duration data
		long enabledDur = state.getDuration(DisabledSubject.class.getName(), -1);
		assertTrue(enabledDur >= 0, "Enabled class must have a recorded duration");

		// Fully disabled class should NOT have duration data
		long disabledDur = state.getDuration(FullyDisabledSubject.class.getName(), -1);
		assertEquals(-1L, disabledDur, "Fully disabled class must not appear in state");
	}

	// ── @Nested workflow ────────────────────────────────────────────────

	/** Subject with @Nested inner class. */
	static class NestedSubject {
		@Test
		void outerTest() {
			assertTrue(true);
		}

		@Nested
		class InnerTests {
			@Test
			void innerTestA() {
				assertEquals(3, 1 + 2);
			}

			@Test
			void innerTestB() {
				assertEquals(7, 3 + 4);
			}
		}
	}

	@Test
	void nestedWorkflow_innerTestsAttributedToTopLevel() throws IOException {
		Path stateFile = tempDir.resolve(".state-nested-wf");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TestExecutionSummary summary = launchTests(NestedSubject.class);
		assertEquals(0, summary.getTestsFailedCount());
		// 1 outer + 2 inner = 3 tests
		assertEquals(3, summary.getTestsSucceededCount());

		TestOrderState state = TestOrderState.load(stateFile);
		// Duration should be under the top-level class (not the $InnerTests)
		long dur = state.getDuration(NestedSubject.class.getName(), -1);
		assertTrue(dur >= 0, "Nested class tests must be attributed to top-level class");
	}

	// ── Combined workflow: all features in one run ───────────────────────

	@Test
	void combinedWorkflow_allFeaturesInOneRun() throws IOException {
		Path stateFile = tempDir.resolve(".state-combined-wf");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		TestExecutionSummary summary = launchTests(RepeatedSubject.class, TestFactorySubject.class,
				DisabledSubject.class, NestedSubject.class);

		assertEquals(0, summary.getTestsFailedCount(), "No tests should fail");

		// Expected: 3 repeated + 1 normal + 3 dynamic + 1 static + 1 enabled + 3 nested
		// = 12
		long succeeded = summary.getTestsSucceededCount();
		assertTrue(succeeded >= 12, "Expected >= 12 successful tests across all features, got " + succeeded);

		TestOrderState state = TestOrderState.load(stateFile);

		// All non-disabled classes should have durations
		for (Class<?> cls : List.of(RepeatedSubject.class, TestFactorySubject.class, DisabledSubject.class,
				NestedSubject.class)) {
			long dur = state.getDuration(cls.getName(), -1);
			assertTrue(dur >= 0, cls.getSimpleName() + " must have recorded duration");
		}

		// Fully disabled class must NOT have duration
		long disabledDur = state.getDuration(FullyDisabledSubject.class.getName(), -1);
		assertEquals(-1L, disabledDur, "FullyDisabledSubject must not have duration");

		// Run record should exist with execution order
		List<TestOrderState.RunRecord> runs = state.runs();
		assertFalse(runs.isEmpty(), "At least one run record should exist");
	}

	// ── Failure tracking across features ────────────────────────────────

	/** Subject where one dynamic test fails. */
	static class FailingDynamicSubject {
		@TestFactory
		Collection<DynamicTest> dynamicWithFailure() {
			return List.of(DynamicTest.dynamicTest("passes", () -> assertTrue(true)),
					DynamicTest.dynamicTest("fails", () -> fail("intentional")));
		}
	}

	/** Subject with a failing repetition. */
	static class FailingRepeatSubject {
		static int counter = 0;

		@RepeatedTest(3)
		void sometimesFails() {
			counter++;
			if (counter == 2) {
				fail("Repetition 2 fails intentionally");
			}
		}
	}

	@Test
	void failureTracking_failedDynamicAndRepeatedRecorded() throws IOException {
		Path stateFile = tempDir.resolve(".state-failures-wf");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		// Reset counter for repeatable test
		FailingRepeatSubject.counter = 0;

		// Seed pending data so RunRecord is built
		TestOrderState.setStatePath(stateFile.toString());
		TestOrderState.recordBreakdown(FailingDynamicSubject.class.getName(),
				new TestOrderState.ScoreBreakdown(5, false, false, 0, 0, 0.0, false, false, 0.0));
		TestOrderState.recordBreakdown(FailingRepeatSubject.class.getName(),
				new TestOrderState.ScoreBreakdown(5, false, false, 0, 0, 0.0, false, false, 0.0));

		TestExecutionSummary summary = launchTests(FailingDynamicSubject.class, FailingRepeatSubject.class);

		// Some tests fail — that's expected
		assertTrue(summary.getTestsFailedCount() >= 2, "At least 2 tests should fail (1 dynamic + 1 repeated)");

		TestOrderState state = TestOrderState.load(stateFile);

		// Run record should record the failures
		List<TestOrderState.RunRecord> runs = state.runs();
		assertFalse(runs.isEmpty(), "Run record must exist");
		TestOrderState.RunRecord lastRun = runs.get(runs.size() - 1);
		assertTrue(lastRun.totalFailures() > 0, "Failures must be recorded in run record");
	}

	// ── Two-pass workflow: learn then verify state ──────────────────────

	@Test
	void twoPassWorkflow_secondRunSeesFirstRunData() throws IOException {
		Path stateFile = tempDir.resolve(".state-twopass-wf");
		System.clearProperty("testorder.learn");
		System.setProperty("testorder.state.path", stateFile.toString());

		// First pass: generate telemetry
		launchTests(RepeatedSubject.class, TestFactorySubject.class);

		TestOrderState stateAfterFirst = TestOrderState.load(stateFile);
		long firstDur = stateAfterFirst.getDuration(RepeatedSubject.class.getName(), -1);
		assertTrue(firstDur >= 0, "First pass must record duration");

		// Second pass: TelemetryListener appends to existing state
		launchTests(RepeatedSubject.class, TestFactorySubject.class);

		TestOrderState stateAfterSecond = TestOrderState.load(stateFile);
		long secondDur = stateAfterSecond.getDuration(RepeatedSubject.class.getName(), -1);
		assertTrue(secondDur >= 0, "Second pass must still have duration data");
	}
}
