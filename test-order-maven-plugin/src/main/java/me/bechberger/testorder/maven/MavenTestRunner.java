package me.bechberger.testorder.maven;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import me.bechberger.testorder.ops.detection.TestRunner;

/**
 * TestRunner implementation that executes tests via Maven Surefire in a
 * subprocess. Uses the test-order {@code FixedOrderClassOrderer} to control
 * execution order precisely. Parses Surefire XML reports to determine pass/fail
 * status.
 */
class MavenTestRunner implements TestRunner {

	private final MavenProject project;
	private final MavenSession session;
	private final Log log;
	private final Path reportDir;
	private final Path runtimeDir;
	private final List<String> extraClasspathEntries;

	/** Circular buffer of last N lines of subprocess output for diagnostics. */
	private static final int OUTPUT_BUFFER_SIZE = 50;
	private final Deque<String> lastOutputLines = new ArrayDeque<>(OUTPUT_BUFFER_SIZE);

	/**
	 * Common plugin skip flags to prevent unrelated failures blocking test
	 * execution.
	 */
	private static final List<String> PLUGIN_SKIP_FLAGS = List.of("-Drat.skip=true", "-Dcheckstyle.skip=true",
			"-Dspotbugs.skip=true", "-Denforcer.skip=true", "-Dpmd.skip=true", "-Djacoco.skip=true",
			"-Dlicense.skip=true", "-Danimal.sniffer.skip=true");

	MavenTestRunner(MavenProject project, MavenSession session, Log log) {
		this(project, session, log, List.of());
	}

	MavenTestRunner(MavenProject project, MavenSession session, Log log, List<String> extraClasspathEntries) {
		this.project = project;
		this.session = session;
		this.log = log;
		this.reportDir = Path.of(project.getBuild().getDirectory(), "surefire-reports");
		this.runtimeDir = Path.of(project.getBuild().getDirectory(), "test-order-detect-runtime");
		this.extraClasspathEntries = extraClasspathEntries;
	}

	@Override
	public boolean supportsMethodOrdering() {
		return true;
	}

	@Override
	public boolean supportsLearnPhase() {
		return true;
	}

	@Override
	public boolean runLearnPhase(String instrumentationMode) {
		log.info("[test-order] Running learn phase with instrumentation mode: " + instrumentationMode);

		List<String> command = new ArrayList<>(List.of("mvn", "me.bechberger:test-order-maven-plugin:learn", "test",
				"-Dtestorder.instrumentation.mode=" + instrumentationMode, "-Dspotless.check.skip=true",
				"--batch-mode"));
		command.addAll(PLUGIN_SKIP_FLAGS);

		File workDir;
		if (project.getBasedir().getParentFile() != null
				&& new File(project.getBasedir().getParentFile(), "pom.xml").exists() && !project.isExecutionRoot()) {
			command.add("-pl");
			command.add(project.getArtifactId());
			workDir = project.getBasedir().getParentFile();
		} else {
			workDir = project.getBasedir();
		}

		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(workDir);
		pb.redirectErrorStream(true);

		try {
			Process proc = pb.start();
			try {
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
					String line;
					while ((line = reader.readLine()) != null) {
						log.debug(line);
					}
				}
				int exitCode = proc.waitFor();
				if (exitCode == 0) {
					log.info("[test-order] Learn phase completed successfully.");
					return true;
				} else {
					log.warn("[test-order] Learn phase failed (exit code " + exitCode + ")");
					return false;
				}
			} catch (InterruptedException e) {
				proc.destroyForcibly();
				Thread.currentThread().interrupt();
				log.warn("[test-order] Learn phase interrupted");
				return false;
			}
		} catch (IOException e) {
			log.warn("[test-order] Learn phase failed: " + e.getMessage());
			return false;
		}
	}

	@Override
	public TestRunResult run(List<String> testOrder) {
		if (testOrder.isEmpty()) {
			return new TestRunResult(List.of(), Set.of(), Set.of());
		}

		// Handle wildcard: run all tests without filter
		boolean runAll = testOrder.size() == 1 && "*".equals(testOrder.get(0));

		try {
			// Write the order file for FixedOrderClassOrderer to read
			Path orderFile = writeOrderFile(testOrder);

			// Set up junit-platform.properties to register the FixedOrderClassOrderer
			setupRuntimeConfig(runAll);

			// Build test list for -Dtest (controls which tests are included)
			// Use FQCNs directly — simple names would collide when different packages
			// contain identically-named test classes (e.g., com.a.FooTest and
			// com.b.FooTest).
			String testList = runAll ? null : String.join(",", testOrder);

			// Clean previous reports
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

			// Build the maven command — use surefire:test to skip pre-test plugins
			List<String> command = new ArrayList<>(List.of("mvn", "surefire:test", "-DfailIfNoTests=false",
					"-Dsurefire.failIfNoSpecifiedTests=false", "-Dspotless.check.skip=true",
					// Pass the order file path to the forked JVM via argLine (quote for spaces)
					"-DargLine=-Dtestorder.fixed.order.file=\"" + orderFile.toAbsolutePath() + "\"",
					// Add runtime dir + test-order JARs to test classpath
					"-Dmaven.test.additionalClasspath=" + buildAdditionalClasspath(), "--batch-mode", "--quiet"));
			command.addAll(PLUGIN_SKIP_FLAGS);
			if (!runAll) {
				command.add(2, "-Dtest=" + testList);
			}

			// For multi-module projects, use -pl from parent; for standalone, run in
			// project dir
			File workDir;
			if (project.getBasedir().getParentFile() != null
					&& new File(project.getBasedir().getParentFile(), "pom.xml").exists()
					&& !project.isExecutionRoot()) {
				command.add(2, "-pl");
				command.add(3, project.getArtifactId());
				workDir = project.getBasedir().getParentFile();
			} else {
				workDir = project.getBasedir();
			}
			ProcessBuilder pb = new ProcessBuilder(command);
			pb.directory(workDir);
			pb.redirectErrorStream(true);

			Process proc = pb.start();
			// Capture output — keep last N lines for diagnostics
			int exitCode;
			try {
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
					String line;
					while ((line = reader.readLine()) != null) {
						captureOutputLine(line);
					}
				}
				exitCode = proc.waitFor();
			} catch (InterruptedException e) {
				proc.destroyForcibly();
				Thread.currentThread().interrupt();
				return new TestRunResult(testOrder, Set.of(), new HashSet<>(testOrder));
			}

			if (exitCode != 0) {
				log.warn("[test-order] Subprocess exited with code " + exitCode);
				log.warn("[test-order] Last output lines:");
				for (String outputLine : lastOutputLines) {
					log.warn("  " + outputLine);
				}
			}

			// Parse results from Surefire XML reports
			return parseResults(runAll ? List.of() : testOrder);
		} catch (IOException e) {
			log.warn("Test execution failed: " + e.getMessage());
			return new TestRunResult(testOrder, Set.of(), new HashSet<>(testOrder));
		}
	}

	@Override
	public MethodRunResult runMethods(String testClass, List<String> methodOrder) {
		if (methodOrder.isEmpty()) {
			return new MethodRunResult(testClass, List.of(), Set.of(), Set.of());
		}

		try {
			// Write the method order file for FixedOrderMethodOrderer to read
			Path methodOrderFile = writeMethodOrderFile(methodOrder);

			// Set up junit-platform.properties to register both class and method orderers
			setupRuntimeConfigForMethods(methodOrderFile);

			// Clean previous reports
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

			// Build the maven command
			// Use FQCN for -Dtest to avoid collisions with same-named classes in
			// different packages. Quote the method order file path for spaces.
			List<String> command = new ArrayList<>(List.of("mvn", "surefire:test", "-Dtest=" + testClass,
					"-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false", "-Dspotless.check.skip=true",
					"-DargLine=-Dtestorder.fixed.method.order.file=\"" + methodOrderFile.toAbsolutePath() + "\"",
					"-Dmaven.test.additionalClasspath=" + buildAdditionalClasspath(), "--batch-mode", "--quiet"));
			command.addAll(PLUGIN_SKIP_FLAGS);

			File workDir;
			if (project.getBasedir().getParentFile() != null
					&& new File(project.getBasedir().getParentFile(), "pom.xml").exists()
					&& !project.isExecutionRoot()) {
				command.add(2, "-pl");
				command.add(3, project.getArtifactId());
				workDir = project.getBasedir().getParentFile();
			} else {
				workDir = project.getBasedir();
			}

			ProcessBuilder pb = new ProcessBuilder(command);
			pb.directory(workDir);
			pb.redirectErrorStream(true);

			Process proc = pb.start();
			try {
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
					String line;
					while ((line = reader.readLine()) != null) {
						captureOutputLine(line);
					}
				}
				proc.waitFor();
			} catch (InterruptedException e) {
				proc.destroyForcibly();
				Thread.currentThread().interrupt();
				return new MethodRunResult(testClass, methodOrder, Set.of(), new HashSet<>(methodOrder));
			}

			// Parse method-level results from Surefire XML
			return parseMethodResults(testClass, methodOrder);
		} catch (IOException e) {
			log.warn("Method-level test execution failed: " + e.getMessage());
			return new MethodRunResult(testClass, methodOrder, Set.of(), new HashSet<>(methodOrder));
		}
	}

	/**
	 * Sets up the runtime directory with junit-platform.properties that registers
	 * the FixedOrderClassOrderer. Only written when order control is needed.
	 */
	private void setupRuntimeConfig(boolean runAll) throws IOException {
		Files.createDirectories(runtimeDir);
		Path junitProps = runtimeDir.resolve("junit-platform.properties");
		if (!runAll) {
			Files.writeString(junitProps, "junit.jupiter.testclass.order.default="
					+ "me.bechberger.testorder.junit.FixedOrderClassOrderer\n");
		} else {
			// Remove orderer config for discovery runs
			Files.deleteIfExists(junitProps);
		}
	}

	private Path writeOrderFile(List<String> testOrder) throws IOException {
		Path orderFile = Path.of(project.getBuild().getDirectory(), "test-order-detect.txt");
		Files.createDirectories(orderFile.getParent());
		Files.writeString(orderFile, String.join("\n", testOrder));
		return orderFile;
	}

	private String buildAdditionalClasspath() {
		List<String> entries = new ArrayList<>();
		entries.add(runtimeDir.toAbsolutePath().toString());
		entries.addAll(extraClasspathEntries);
		return String.join(",", entries);
	}

	private TestRunResult parseResults(List<String> executionOrder) {
		Set<String> passed = new HashSet<>();
		Set<String> failed = new HashSet<>();

		if (!Files.exists(reportDir)) {
			// No reports → assume all failed
			return new TestRunResult(executionOrder, Set.of(), new HashSet<>(executionOrder));
		}

		try (var files = Files.list(reportDir)) {
			files.filter(
					p -> p.getFileName().toString().startsWith("TEST-") && p.getFileName().toString().endsWith(".xml"))
					.forEach(report -> parseReport(report, passed, failed));
		} catch (IOException e) {
			log.warn("Could not read Surefire reports: " + e.getMessage());
		}

		// Tests not mentioned in reports → consider them passed (they may have been
		// skipped)
		for (String test : executionOrder) {
			if (!passed.contains(test) && !failed.contains(test)) {
				passed.add(test);
			}
		}

		return new TestRunResult(executionOrder, passed, failed);
	}

	private void parseReport(Path report, Set<String> passed, Set<String> failed) {
		try {
			String content = Files.readString(report);
			// Simple XML parsing — looking for failures/errors attributes
			String className = extractAttribute(content, "name");
			if (className == null)
				return;

			int totalTests = parseIntSafe(extractAttribute(content, "tests"));
			int failCount = parseIntSafe(extractAttribute(content, "failures"))
					+ parseIntSafe(extractAttribute(content, "errors"));
			int skipped = parseIntSafe(extractAttribute(content, "skipped"));

			// A class is only "failed" for detection purposes if ALL non-skipped
			// tests fail. When only some methods fail (e.g. due to missing
			// --add-opens), the class still participates in OD detection.
			int effective = totalTests - skipped;
			if (effective > 0 && failCount >= effective) {
				failed.add(className);
			} else {
				passed.add(className);
			}
		} catch (IOException e) {
			// Skip unreadable reports
		}
	}

	private static String extractAttribute(String xml, String attr) {
		String prefix = attr + "=\"";
		int start = xml.indexOf(prefix);
		if (start < 0)
			return null;
		start += prefix.length();
		int end = xml.indexOf('"', start);
		if (end < 0)
			return null;
		return xml.substring(start, end);
	}

	private static int parseIntSafe(String s) {
		if (s == null || s.isEmpty())
			return 0;
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	// ── Method-level helpers ──────────────────────────────────────────

	private Path writeMethodOrderFile(List<String> methodOrder) throws IOException {
		Path orderFile = Path.of(project.getBuild().getDirectory(), "test-order-method-detect.txt");
		Files.createDirectories(orderFile.getParent());
		Files.writeString(orderFile, String.join("\n", methodOrder));
		return orderFile;
	}

	private void setupRuntimeConfigForMethods(Path methodOrderFile) throws IOException {
		Files.createDirectories(runtimeDir);
		Path junitProps = runtimeDir.resolve("junit-platform.properties");
		Files.writeString(junitProps,
				"junit.jupiter.testmethod.order.default=" + "me.bechberger.testorder.junit.FixedOrderMethodOrderer\n");
	}

	private MethodRunResult parseMethodResults(String testClass, List<String> methodOrder) {
		Set<String> passed = new HashSet<>();
		Set<String> failed = new HashSet<>();

		if (!Files.exists(reportDir)) {
			return new MethodRunResult(testClass, methodOrder, Set.of(), new HashSet<>(methodOrder));
		}

		try (var files = Files.list(reportDir)) {
			files.filter(
					p -> p.getFileName().toString().startsWith("TEST-") && p.getFileName().toString().endsWith(".xml"))
					.forEach(report -> parseMethodReport(report, testClass, passed, failed));
		} catch (IOException e) {
			log.warn("Could not read Surefire reports for method parsing: " + e.getMessage());
		}

		// Methods not mentioned → assume passed
		for (String method : methodOrder) {
			if (!passed.contains(method) && !failed.contains(method)) {
				passed.add(method);
			}
		}

		return new MethodRunResult(testClass, methodOrder, passed, failed);
	}

	private void parseMethodReport(Path report, String testClass, Set<String> passed, Set<String> failed) {
		try {
			String content = Files.readString(report);
			// Parse individual <testcase> elements for method-level pass/fail
			int idx = 0;
			while (true) {
				int tcStart = content.indexOf("<testcase ", idx);
				if (tcStart < 0)
					break;
				int tcEnd = content.indexOf(">", tcStart);
				if (tcEnd < 0)
					break;

				// Check if it's a self-closing tag or has children (failure/error)
				String tag = content.substring(tcStart, tcEnd + 1);
				String methodName = extractAttributeFromTag(tag, "name");

				if (methodName != null) {
					// Strip parameter suffix like "(Path)" or "(int, String)"
					int parenIdx = methodName.indexOf('(');
					if (parenIdx > 0) {
						methodName = methodName.substring(0, parenIdx);
					}

					// Check if there's a <failure> or <error> child before the next </testcase> or
					// next <testcase
					boolean hasFail = false;
					if (!tag.endsWith("/>")) {
						int nextClose = content.indexOf("</testcase>", tcEnd);
						if (nextClose > 0) {
							String body = content.substring(tcEnd, nextClose);
							hasFail = body.contains("<failure") || body.contains("<error");
							idx = nextClose + "</testcase>".length();
						} else {
							idx = tcEnd + 1;
						}
					} else {
						idx = tcEnd + 1;
					}

					if (hasFail) {
						failed.add(methodName);
					} else {
						passed.add(methodName);
					}
				} else {
					idx = tcEnd + 1;
				}
			}
		} catch (IOException e) {
			// Skip unreadable reports
		}
	}

	private static String extractAttributeFromTag(String tag, String attr) {
		String prefix = attr + "=\"";
		int start = tag.indexOf(prefix);
		if (start < 0)
			return null;
		start += prefix.length();
		int end = tag.indexOf('"', start);
		if (end < 0)
			return null;
		return tag.substring(start, end);
	}

	private void captureOutputLine(String line) {
		synchronized (lastOutputLines) {
			if (lastOutputLines.size() >= OUTPUT_BUFFER_SIZE) {
				lastOutputLines.pollFirst();
			}
			lastOutputLines.addLast(line);
		}
	}
}
