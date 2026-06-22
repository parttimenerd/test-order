package me.bechberger.testorder.junit;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.opentest4j.TestAbortedException;

import me.bechberger.testorder.TestOrderConfig;
import me.bechberger.testorder.ml.FlakyReportLoader;

/**
 * Retries test method invocations classified as
 * {@link me.bechberger.testorder.ml.TestHealthReport.HealthStatus#FLAKY FLAKY}
 * by the most recent ML health report. Optionally quarantines persistent
 * failures (reports them as aborted instead of failed) when
 * {@code testorder.flaky.quarantine=true}.
 *
 * <p>
 * Configuration (all opt-in):
 * </p>
 * <ul>
 * <li>{@link TestOrderConfig#FLAKY_RETRIES testorder.flaky.retries} — max
 * retries per FLAKY test method ({@code 0} disables retries; default
 * {@code 0}).</li>
 * <li>{@link TestOrderConfig#FLAKY_REPORT_PATH testorder.flaky.report.path} —
 * path to the ML report (default {@code .test-order/ml-report.txt}).</li>
 * <li>{@link TestOrderConfig#FLAKY_QUARANTINE testorder.flaky.quarantine} —
 * when {@code true}, persistent failures of FLAKY tests are reported as aborted
 * (via {@code TestAbortedException}).</li>
 * </ul>
 *
 * <p>
 * Tests not classified as FLAKY are unaffected — this extension is a no-op for
 * HEALTHY / DEGRADING / FAILING tests. Retries and quarantines are recorded on
 * {@link TelemetryListener} so they surface in the CI summary and dashboard.
 * </p>
 *
 * <p>
 * <b>Lifecycle caveat:</b> retries are issued from inside
 * {@link InvocationInterceptor#interceptTestMethod}, which means JUnit Jupiter
 * fires {@code @BeforeEach} / {@code @AfterEach} only around the <em>outer</em>
 * invocation. Each retry runs against the same fixture instance as the original
 * attempt — parameter resolvers, fixture state mutations, and registered
 * callback extensions do not re-fire. This matches the
 * single-shot-{@code proceed()} contract of {@code InvocationInterceptor}. If
 * your flaky test relies on per-attempt fixture freshness, prefer a
 * {@code @TestTemplate} / {@code @RetryingTest}-style design instead. See
 * {@code docs/FLAKY_AND_CACHING.md} for the recommended pattern.
 * </p>
 *
 * <p>
 * Auto-discovered via
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension} when
 * JUnit auto-detection is enabled
 * ({@code junit.jupiter.extensions.autodetection.enabled=true}).
 * </p>
 */
public class FlakyRetryExtension implements InvocationInterceptor {

	private static final Logger LOG = Logger.getLogger(FlakyRetryExtension.class.getName());
	private static final String DEFAULT_REPORT_PATH = ".test-order/ml-report.txt";

	/** Cached FLAKY set — loaded lazily on first method interception. */
	private static volatile Set<String> flakySet;
	/** Cached config — recomputed on each test plan (cheap). */
	private static final AtomicInteger MAX_RETRIES = new AtomicInteger(-1);
	private static volatile Boolean quarantineEnabled;

	private static final ConcurrentHashMap<String, Integer> RETRY_COUNTS = new ConcurrentHashMap<>();
	private static final Set<String> QUARANTINED = ConcurrentHashMap.newKeySet();

	@Override
	public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
			ExtensionContext extensionContext) throws Throwable {
		int retries = maxRetries();
		boolean quarantine = quarantineEnabled();
		if (retries <= 0 && !quarantine) {
			invocation.proceed();
			return;
		}

		String testClass = invocationContext.getTargetClass().getName();
		Set<String> flaky = flakySet();
		boolean isFlaky = flaky.contains(testClass);
		if (!isFlaky) {
			invocation.proceed();
			return;
		}

		Throwable first;
		try {
			invocation.proceed();
			return; // first attempt passed
		} catch (Throwable t) {
			first = t;
		}

		// Retry up to `retries` times via direct reflection (invocation.proceed()
		// is single-shot; junit-pioneer uses the same pattern for repeated tries).
		Method method = invocationContext.getExecutable();
		Object target = invocationContext.getTarget().orElse(null);
		Throwable last = first;
		for (int attempt = 1; attempt <= retries; attempt++) {
			final int finalAttempt = attempt;
			try {
				method.invoke(target, invocationContext.getArguments().toArray());
				RETRY_COUNTS.merge(testClass, finalAttempt, Math::max);
				LOG.info(() -> "[test-order] retry succeeded for " + testClass + "#" + method.getName() + " on attempt "
						+ finalAttempt);
				return; // passed on retry
			} catch (java.lang.reflect.InvocationTargetException ite) {
				last = ite.getTargetException();
			} catch (Throwable t) {
				last = t;
			}
		}

		if (retries > 0) {
			RETRY_COUNTS.merge(testClass, retries, Math::max);
		}
		if (quarantine) {
			QUARANTINED.add(testClass);
			TestAbortedException aborted = new TestAbortedException(
					"quarantined — flaky test failed after " + retries + " retries: " + last.getMessage());
			aborted.addSuppressed(last);
			throw aborted;
		}
		throw last;
	}

	private static int maxRetries() {
		int cached = MAX_RETRIES.get();
		if (cached >= 0) {
			return cached;
		}
		String val = System.getProperty(TestOrderConfig.FLAKY_RETRIES, "0");
		int parsed;
		try {
			parsed = Math.max(0, Integer.parseInt(val.trim()));
		} catch (NumberFormatException e) {
			parsed = 0;
		}
		MAX_RETRIES.set(parsed);
		return parsed;
	}

	private static boolean quarantineEnabled() {
		Boolean cached = quarantineEnabled;
		if (cached != null) {
			return cached;
		}
		boolean enabled = "true".equalsIgnoreCase(System.getProperty(TestOrderConfig.FLAKY_QUARANTINE, "false"));
		quarantineEnabled = enabled;
		return enabled;
	}

	private static Set<String> flakySet() {
		Set<String> cached = flakySet;
		if (cached != null) {
			return cached;
		}
		String pathProp = System.getProperty(TestOrderConfig.FLAKY_REPORT_PATH, DEFAULT_REPORT_PATH);
		Path reportPath = Paths.get(pathProp);
		Set<String> loaded = FlakyReportLoader.loadFlakyClasses(reportPath);
		flakySet = loaded;
		return loaded;
	}

	/** Snapshot of {testClass -> max attempt number that succeeded or was used}. */
	public static java.util.Map<String, Integer> retryCounts() {
		return java.util.Map.copyOf(RETRY_COUNTS);
	}

	/** Snapshot of quarantined test classes for this JVM. */
	public static Set<String> quarantined() {
		return Set.copyOf(QUARANTINED);
	}

	/**
	 * Clears all cached static state: the FLAKY set, retry/quarantine maps, and the
	 * cached property values. Called per test plan from
	 * {@link TelemetryListener#testPlanExecutionStarted} so that a Gradle daemon
	 * reusing the JVM across builds does not carry retry/quarantine activity or a
	 * stale ML report from one test plan into the next. Also used by tests to reset
	 * between cases.
	 */
	public static void resetForTesting() {
		flakySet = null;
		MAX_RETRIES.set(-1);
		quarantineEnabled = null;
		RETRY_COUNTS.clear();
		QUARANTINED.clear();
	}
}
