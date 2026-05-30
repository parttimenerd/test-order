package me.bechberger.testorder.ops.detection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

import me.bechberger.testorder.ops.PluginLog;

/**
 * Shared utilities for subprocess-based TestRunner implementations (Maven and
 * Gradle). Handles file-system side-effects that are identical in both runners.
 */
public final class TestRunnerSupport {

	private static final int OUTPUT_BUFFER_SIZE = 50;

	private final Path runtimeDir;
	private final Path reportDir;
	private final PluginLog log;

	private final Deque<String> lastOutputLines = new ArrayDeque<>(OUTPUT_BUFFER_SIZE);
	private boolean firstFailureOutputShown = false;

	public TestRunnerSupport(Path runtimeDir, Path reportDir, PluginLog log) {
		this.runtimeDir = runtimeDir;
		this.reportDir = reportDir;
		this.log = log;
	}

	/**
	 * Writes (or removes) the {@code junit-platform.properties} file that enables
	 * {@code FixedOrderClassOrderer} for class-level ordering.
	 *
	 * @param runAll
	 *            when {@code true}, delete the properties file so the JUnit default
	 *            order is used (discovery/baseline runs)
	 */
	public void setupRuntimeConfig(boolean runAll) throws IOException {
		Files.createDirectories(runtimeDir);
		Path junitProps = runtimeDir.resolve("junit-platform.properties");
		if (!runAll) {
			Files.writeString(junitProps, "junit.jupiter.testclass.order.default="
					+ "me.bechberger.testorder.junit.FixedOrderClassOrderer\n");
		} else {
			Files.deleteIfExists(junitProps);
		}
	}

	/**
	 * Writes the {@code junit-platform.properties} file that enables
	 * {@code FixedOrderMethodOrderer} for method-level ordering.
	 */
	public void setupRuntimeConfigForMethods() throws IOException {
		Files.createDirectories(runtimeDir);
		Path junitProps = runtimeDir.resolve("junit-platform.properties");
		Files.writeString(junitProps,
				"junit.jupiter.testmethod.order.default=" + "me.bechberger.testorder.junit.FixedOrderMethodOrderer\n");
	}

	/**
	 * Deletes all {@code .xml} files from the report directory so that stale
	 * results from a previous run are not picked up by the parser.
	 */
	public void cleanReports() throws IOException {
		if (Files.exists(reportDir)) {
			try (var files = Files.list(reportDir)) {
				files.filter(p -> p.toString().endsWith(".xml")).forEach(p -> {
					try {
						Files.deleteIfExists(p);
					} catch (IOException ignored) {
					}
				});
			}
		}
	}

	/**
	 * Appends {@code line} to the fixed-size circular diagnostic buffer.
	 * Thread-safe.
	 */
	public void captureOutputLine(String line) {
		synchronized (lastOutputLines) {
			if (lastOutputLines.size() >= OUTPUT_BUFFER_SIZE) {
				lastOutputLines.pollFirst();
			}
			lastOutputLines.addLast(line);
		}
	}

	/**
	 * Logs subprocess failure details once (on first non-zero exit), then
	 * downgrades subsequent failures to debug level to avoid log spam during
	 * detection loops.
	 */
	public void logSubprocessExitIfNeeded(int exitCode) {
		if (exitCode == 0)
			return;
		if (!firstFailureOutputShown) {
			firstFailureOutputShown = true;
			log.warn("[test-order] Subprocess exited with code " + exitCode);
			log.warn("[test-order] Last output lines:");
			synchronized (lastOutputLines) {
				for (String outputLine : lastOutputLines) {
					log.warn("  " + outputLine);
				}
			}
			log.warn("[test-order] (Subsequent subprocess failures during detection"
					+ " are expected and will not be repeated)");
		} else {
			log.debug("[test-order] Subprocess exited with code " + exitCode + " (expected during OD detection)");
		}
	}
}
