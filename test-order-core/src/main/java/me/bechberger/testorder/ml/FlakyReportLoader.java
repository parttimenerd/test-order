package me.bechberger.testorder.ml;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads the persisted {@link TestHealthReport} (default
 * {@code .test-order/ml-report.txt}) and exposes the set of test classes
 * currently classified as {@link TestHealthReport.HealthStatus#FLAKY FLAKY}.
 * <p>
 * Used by the runtime retry/quarantine extension to decide which failures are
 * eligible for retry. Failures to load the report (missing file, parse errors,
 * unreadable path) are non-fatal and surface as an empty set: tests will run
 * normally and no retries will be attempted.
 */
public final class FlakyReportLoader {

	private FlakyReportLoader() {
	}

	/**
	 * Returns the set of FLAKY-classified test class names from the report at
	 * {@code reportFile}. Returns an empty set if the file does not exist or cannot
	 * be parsed.
	 */
	public static Set<String> loadFlakyClasses(Path reportFile) {
		if (reportFile == null) {
			return Set.of();
		}
		try {
			TestHealthReport report = TestHealthReport.load(reportFile);
			Set<String> flaky = new HashSet<>();
			for (TestHealthReport.TestHealth h : report.byStatus(TestHealthReport.HealthStatus.FLAKY)) {
				flaky.add(h.testClass());
			}
			return Set.copyOf(flaky);
		} catch (IOException e) {
			return Set.of();
		}
	}
}
