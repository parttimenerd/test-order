package me.bechberger.testorder.junit;

import static org.junit.platform.testkit.engine.EventConditions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;

import me.bechberger.testorder.TestOrderConfig;
import me.bechberger.testorder.ml.TestHealthReport;

/**
 * Verifies that {@link FlakyRetryExtension} only retries FLAKY-classified tests
 * and respects the {@code testorder.flaky.retries} and
 * {@code testorder.flaky.quarantine} properties.
 */
class FlakyRetryExtensionTest {

	@TempDir
	Path tempDir;

	/** Mutable counter shared with the test classes below. */
	static final AtomicInteger ATTEMPTS = new AtomicInteger();
	/** How many invocations should fail before passing. */
	static volatile int FAIL_UNTIL_ATTEMPT = 0;

	@BeforeEach
	void resetState() {
		ATTEMPTS.set(0);
		FlakyRetryExtension.resetForTesting();
		System.setProperty("testorder.junit.fixtures.enabled", "true");
	}

	@AfterEach
	void clearProps() {
		System.clearProperty(TestOrderConfig.FLAKY_RETRIES);
		System.clearProperty(TestOrderConfig.FLAKY_QUARANTINE);
		System.clearProperty(TestOrderConfig.FLAKY_REPORT_PATH);
		System.clearProperty("testorder.junit.fixtures.enabled");
		FlakyRetryExtension.resetForTesting();
	}

	/** Writes a tiny ML report classifying {@code flakyClass} as FLAKY. */
	private Path writeFlakyReport(String flakyClass) throws IOException {
		var health = new TestHealthReport.TestHealth(flakyClass, 0.8, 0.1, 0.4, 0.2, 10, 4,
				TestHealthReport.HealthStatus.FLAKY);
		var report = new TestHealthReport(Map.of(flakyClass, health), System.currentTimeMillis(), 10);
		Path file = tempDir.resolve("ml-report.txt");
		report.save(file);
		return file;
	}

	@Test
	void flakyTestPassesOnRetry() throws IOException {
		Path report = writeFlakyReport(FlakyTestCase.class.getName());
		System.setProperty(TestOrderConfig.FLAKY_REPORT_PATH, report.toString());
		System.setProperty(TestOrderConfig.FLAKY_RETRIES, "2");
		FAIL_UNTIL_ATTEMPT = 1; // fail attempt 0, pass attempt 1

		EngineTestKit.engine("junit-jupiter").selectors(DiscoverySelectors.selectClass(FlakyTestCase.class)).execute()
				.testEvents().assertStatistics(stats -> stats.started(1).succeeded(1).failed(0));
	}

	@Test
	void flakyTestFailsAfterExhaustingRetries() throws IOException {
		Path report = writeFlakyReport(FlakyTestCase.class.getName());
		System.setProperty(TestOrderConfig.FLAKY_REPORT_PATH, report.toString());
		System.setProperty(TestOrderConfig.FLAKY_RETRIES, "2");
		FAIL_UNTIL_ATTEMPT = 99; // always fails

		EngineTestKit.engine("junit-jupiter").selectors(DiscoverySelectors.selectClass(FlakyTestCase.class)).execute()
				.testEvents().assertStatistics(stats -> stats.started(1).succeeded(0).failed(1));
	}

	@Test
	void quarantineAbortsInsteadOfFailing() throws IOException {
		Path report = writeFlakyReport(FlakyTestCase.class.getName());
		System.setProperty(TestOrderConfig.FLAKY_REPORT_PATH, report.toString());
		System.setProperty(TestOrderConfig.FLAKY_RETRIES, "1");
		System.setProperty(TestOrderConfig.FLAKY_QUARANTINE, "true");
		FAIL_UNTIL_ATTEMPT = 99;

		EngineTestKit.engine("junit-jupiter").selectors(DiscoverySelectors.selectClass(FlakyTestCase.class)).execute()
				.testEvents().assertStatistics(stats -> stats.started(1).succeeded(0).failed(0).aborted(1));
	}

	@Test
	void healthyTestIsNotRetried() throws IOException {
		// Report classifies SOME OTHER class as flaky → HealthyTestCase is not FLAKY.
		Path report = writeFlakyReport("com.OtherFlaky");
		System.setProperty(TestOrderConfig.FLAKY_REPORT_PATH, report.toString());
		System.setProperty(TestOrderConfig.FLAKY_RETRIES, "5");
		FAIL_UNTIL_ATTEMPT = 99;

		EngineTestKit.engine("junit-jupiter").selectors(DiscoverySelectors.selectClass(HealthyTestCase.class)).execute()
				.testEvents().assertStatistics(stats -> stats.started(1).succeeded(0).failed(1));
		// Only one attempt — no retries for non-FLAKY tests
		org.junit.jupiter.api.Assertions.assertEquals(1, ATTEMPTS.get(), "non-FLAKY tests are never retried");
	}

	@Test
	void zeroRetriesNoQuarantineIsNoOp() throws IOException {
		Path report = writeFlakyReport(FlakyTestCase.class.getName());
		System.setProperty(TestOrderConfig.FLAKY_REPORT_PATH, report.toString());
		System.setProperty(TestOrderConfig.FLAKY_RETRIES, "0");
		FAIL_UNTIL_ATTEMPT = 99;

		EngineTestKit.engine("junit-jupiter").selectors(DiscoverySelectors.selectClass(FlakyTestCase.class)).execute()
				.testEvents().assertStatistics(stats -> stats.started(1).failed(1));
	}

	// ── Test fixtures ────────────────────────────────────────────────────────

	@ExtendWith(FlakyRetryExtension.class)
	@EnabledIfSystemProperty(named = "testorder.junit.fixtures.enabled", matches = "true")
	static class FlakyTestCase {
		@Test
		void flakyTest() {
			int attempt = ATTEMPTS.getAndIncrement();
			if (attempt < FAIL_UNTIL_ATTEMPT) {
				throw new AssertionError("simulated failure on attempt " + attempt);
			}
		}
	}

	@ExtendWith(FlakyRetryExtension.class)
	@EnabledIfSystemProperty(named = "testorder.junit.fixtures.enabled", matches = "true")
	static class HealthyTestCase {
		@Test
		void healthyTest() {
			int attempt = ATTEMPTS.getAndIncrement();
			if (attempt < FAIL_UNTIL_ATTEMPT) {
				throw new AssertionError("simulated failure on attempt " + attempt);
			}
		}
	}

	// Keep an explicit reference to satisfy spotbugs/unused import checks (event
	// matchers are loaded via static import above; not all are used directly).
	@SuppressWarnings("unused")
	private static final Object MATCHER_REF = event(test("placeholder"));
}
