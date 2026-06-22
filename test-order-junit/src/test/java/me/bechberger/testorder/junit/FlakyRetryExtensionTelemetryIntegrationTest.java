package me.bechberger.testorder.junit;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import me.bechberger.testorder.TestOrderConfig;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.ml.TestHealthReport;

/**
 * End-to-end wiring test for the round-2 quarantine fix: when
 * {@link FlakyRetryExtension} aborts a FLAKY test via
 * {@code TestAbortedException}, {@link TelemetryListener} must pass
 * {@link FlakyRetryExtension#quarantined()} into
 * {@link TestOrderState#buildRunRecord(java.util.List, java.util.Set, java.util.Set)}
 * so the quarantined class is excluded from the recorded outcomes — keeping
 * {@code passStreak()} neutral across the quarantine window.
 *
 * <p>
 * Unit tests for each component exist in isolation; this test proves the full
 * chain: extension → listener → buildRunRecord → passStreak.
 */
@Timeout(60)
class FlakyRetryExtensionTelemetryIntegrationTest {

	@TempDir
	Path tempDir;

	static final AtomicInteger ATTEMPTS = new AtomicInteger();

	@BeforeEach
	void resetState() {
		ATTEMPTS.set(0);
		FlakyRetryExtension.resetForTesting();
		TestOrderState.resetPending();
		System.setProperty("testorder.junit.fixtures.enabled", "true");
	}

	@AfterEach
	void clearProps() {
		System.clearProperty(TestOrderConfig.FLAKY_RETRIES);
		System.clearProperty(TestOrderConfig.FLAKY_QUARANTINE);
		System.clearProperty(TestOrderConfig.FLAKY_REPORT_PATH);
		System.clearProperty(TestOrderConfig.STATE_PATH);
		System.clearProperty("testorder.junit.fixtures.enabled");
		System.clearProperty("testorder.build.id");
		System.clearProperty("testorder.pending.runs.dir");
		FlakyRetryExtension.resetForTesting();
		TestOrderState.resetPending();
	}

	private Path writeFlakyReport(String flakyClass) throws IOException {
		var health = new TestHealthReport.TestHealth(flakyClass, 0.8, 0.1, 0.4, 0.2, 10, 4,
				TestHealthReport.HealthStatus.FLAKY);
		var report = new TestHealthReport(Map.of(flakyClass, health), System.currentTimeMillis(), 10);
		Path file = tempDir.resolve("ml-report.txt");
		report.save(file);
		return file;
	}

	private TestExecutionSummary launchTests(Class<?>... classes) {
		LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
				.selectors(java.util.Arrays.stream(classes).map(DiscoverySelectors::selectClass).toList()).build();
		Launcher launcher = LauncherFactory.create();
		SummaryGeneratingListener summary = new SummaryGeneratingListener();
		TelemetryListener telemetry = new TelemetryListener();
		launcher.registerTestExecutionListeners(summary, telemetry);
		launcher.execute(request);
		return summary.getSummary();
	}

	@Test
	void quarantinedTest_excludedFromRunRecord_passStreakStaysZero() throws IOException {
		String fixtureClass = FlakyFixture.class.getName();
		Path mlReport = writeFlakyReport(fixtureClass);
		Path stateFile = tempDir.resolve(".state");

		System.setProperty(TestOrderConfig.FLAKY_REPORT_PATH, mlReport.toString());
		System.setProperty(TestOrderConfig.FLAKY_RETRIES, "1");
		System.setProperty(TestOrderConfig.FLAKY_QUARANTINE, "true");
		System.setProperty(TestOrderConfig.STATE_PATH, stateFile.toString());
		// Prevent build-session aggregation from a parent surefire run leaking in.
		System.setProperty("testorder.build.id", "");
		System.setProperty("testorder.pending.runs.dir", "");

		// Run 1: fixture always fails → after 1 retry, FlakyRetryExtension quarantines
		// it (TestAbortedException) instead of marking it failed.
		TestExecutionSummary run1 = launchTests(FlakyFixture.class);
		assertEquals(0, run1.getTestsFailedCount(), "quarantined test must not count as failed");
		assertTrue(run1.getTestsAbortedCount() >= 1, "quarantined test must be aborted");
		assertTrue(FlakyRetryExtension.quarantined().contains(fixtureClass),
				"FlakyRetryExtension must have recorded the quarantine");

		assertTrue(java.nio.file.Files.exists(stateFile), "state file written after run 1");
		TestOrderState state1 = TestOrderState.load(stateFile);
		assertEquals(0, state1.passStreak(fixtureClass),
				"quarantined class must not accrue pass streak from an aborted run");
		assertTrue(
				state1.runs().stream()
						.allMatch(r -> r.outcomes().stream().noneMatch(o -> fixtureClass.equals(o.testClass()))),
				"no RunRecord may contain an outcome for the quarantined class");

		// Run 2: reset attempt counter; same fixture aborts again.
		ATTEMPTS.set(0);
		TestExecutionSummary run2 = launchTests(FlakyFixture.class);
		assertEquals(0, run2.getTestsFailedCount());
		assertTrue(run2.getTestsAbortedCount() >= 1);

		TestOrderState state2 = TestOrderState.load(stateFile);
		assertEquals(0, state2.passStreak(fixtureClass),
				"passStreak must remain neutral (0) across multiple quarantined runs — "
						+ "this is the round-2 invariant that prevents cache-skip false-positives");
	}

	// ── Fixture (only enabled under the integration test's sysprop) ─────────

	@ExtendWith(FlakyRetryExtension.class)
	@EnabledIfSystemProperty(named = "testorder.junit.fixtures.enabled", matches = "true")
	static class FlakyFixture {
		@Test
		void alwaysFails() {
			ATTEMPTS.incrementAndGet();
			throw new AssertionError("simulated flake");
		}
	}
}
