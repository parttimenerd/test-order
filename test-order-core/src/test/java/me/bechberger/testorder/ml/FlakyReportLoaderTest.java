package me.bechberger.testorder.ml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlakyReportLoaderTest {

	@TempDir
	Path tempDir;

	private TestHealthReport.TestHealth health(String cls, TestHealthReport.HealthStatus status) {
		return new TestHealthReport.TestHealth(cls, 0.5, 0.0, 0.2, 0.1, 10, 2, status);
	}

	@Test
	void emptySetWhenFileMissing() {
		Path missing = tempDir.resolve("does-not-exist.txt");
		assertEquals(Set.of(), FlakyReportLoader.loadFlakyClasses(missing));
	}

	@Test
	void emptySetWhenPathNull() {
		assertEquals(Set.of(), FlakyReportLoader.loadFlakyClasses(null));
	}

	@Test
	void returnsOnlyFlakyClasses() throws IOException {
		TestHealthReport report = new TestHealthReport(
				Map.of("com.A", health("com.A", TestHealthReport.HealthStatus.FLAKY), "com.B",
						health("com.B", TestHealthReport.HealthStatus.HEALTHY), "com.C",
						health("com.C", TestHealthReport.HealthStatus.FLAKY), "com.D",
						health("com.D", TestHealthReport.HealthStatus.DEGRADING), "com.E",
						health("com.E", TestHealthReport.HealthStatus.FAILING)),
				System.currentTimeMillis(), 10);
		Path file = tempDir.resolve("ml-report.txt");
		report.save(file);

		Set<String> flaky = FlakyReportLoader.loadFlakyClasses(file);
		assertEquals(Set.of("com.A", "com.C"), flaky);
	}

	@Test
	void emptySetOnUnparseableFile() throws IOException {
		Path file = tempDir.resolve("bad.txt");
		Files.writeString(file, "this is not a valid report file\n");
		// Should not throw — returns empty set.
		assertNotNull(FlakyReportLoader.loadFlakyClasses(file));
	}

	@Test
	void duplicateClassEntries_lastWriteWins() throws IOException {
		// Pin current parser semantics: when the same class appears twice in the
		// report file, the second entry overrides the first (HashMap.put). A future
		// refactor to "first wins" or "throw" would silently change retry behaviour
		// for repos that hand-edit ml-report.txt.
		Path file = tempDir.resolve("ml-report.txt");
		Files.writeString(file, """
				# Test Order Health Report
				# Analyzed: 2026-06-22T00:00:00Z
				# Runs analyzed: 10
				# Format: class|status|flakiness|trend|failRate|volatility|runs|failures
				com.A|HEALTHY|0.100|+0.0000|0.000|0.000|10|0
				com.A|FLAKY|0.800|+0.0000|0.400|0.200|10|4
				""");

		Set<String> flaky = FlakyReportLoader.loadFlakyClasses(file);
		assertEquals(Set.of("com.A"), flaky, "second entry (FLAKY) overrides the first (HEALTHY)");
	}
}
