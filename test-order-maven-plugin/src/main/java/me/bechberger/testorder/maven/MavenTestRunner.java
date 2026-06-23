package me.bechberger.testorder.maven;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import me.bechberger.testorder.ops.detection.JUnitXmlParser;
import me.bechberger.testorder.ops.detection.TestRunner;
import me.bechberger.testorder.ops.detection.TestRunnerSupport;

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
	/** Additional JARs/directories to add to the test classpath for all runs. */
	private final List<String> extraClasspath;
	private final TestRunnerSupport support;

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

	MavenTestRunner(MavenProject project, MavenSession session, Log log, List<String> ordererClasspath) {
		this.project = project;
		this.session = session;
		this.log = log;
		this.reportDir = Path.of(project.getBuild().getDirectory(), "surefire-reports");
		this.runtimeDir = Path.of(project.getBuild().getDirectory(), "test-order-detect-runtime");
		this.extraClasspath = ordererClasspath;
		this.support = new TestRunnerSupport(runtimeDir, reportDir, MavenPluginLog.wrap(log));
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
	public boolean runLearnPhase(String instrumentationMode, Path targetIndexFile) {
		log.info("[test-order] Running learn phase with instrumentation mode: " + instrumentationMode);

		List<String> command = new ArrayList<>(
				List.of(findMavenExecutableForProject(), "me.bechberger:test-order-maven-plugin:learn", "test",
						"-Dtestorder.instrumentation.mode=" + instrumentationMode, "-Dspotless.check.skip=true",
						"--batch-mode"));
		if (targetIndexFile != null) {
			command.add("-Dtestorder.index.path=" + targetIndexFile.toAbsolutePath());
		}
		command.addAll(PLUGIN_SKIP_FLAGS);

		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(resolveWorkDir());
		pb.redirectErrorStream(true);

		try {
			Process proc = pb.start();
			try {
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
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
			} catch (IOException e) {
				proc.destroyForcibly();
				throw e;
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
			support.setupRuntimeConfig(runAll);

			// Build test list for -Dtest (controls which tests are included)
			// Use FQCNs directly — simple names would collide when different packages
			// contain identically-named test classes (e.g., com.a.FooTest and
			// com.b.FooTest).
			String testList = runAll ? null : String.join(",", testOrder);

			// Clean previous reports
			support.cleanReports();

			// Build the maven command — use initialize + surefire:test so lifecycle
			// plugins (JaCoCo etc.) can set @{argLine} before Surefire runs.
			// Pass the order file path as a Surefire system property (not via argLine)
			// so we don't overwrite the @{argLine} value set by JaCoCo etc.
			List<String> command = new ArrayList<>(List.of(findMavenExecutableForProject(), "initialize",
					"surefire:test", "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false",
					"-Dspotless.check.skip=true",
					// Pass the order file as a forked-JVM system property (not via argLine,
					// which would lose JaCoCo's --add-opens flags)
					"-Dtestorder.fixed.order.file=" + orderFile.toAbsolutePath(),
					// Add runtime dir and orderer JARs to test classpath
					"-Dmaven.test.additionalClasspath=" + buildAdditionalClasspath(), "--batch-mode", "--quiet"));
			command.addAll(PLUGIN_SKIP_FLAGS);
			if (!runAll) {
				command.add(2, "-Dtest=" + testList);
			}

			File workDir = resolveWorkDir();
			if (isSubmodule()) {
				command.add(2, "-pl");
				command.add(3, ":" + project.getArtifactId());
			}
			ProcessBuilder pb = new ProcessBuilder(command);
			pb.directory(workDir);
			pb.redirectErrorStream(true);

			Process proc = pb.start();
			// Capture output — keep last N lines for diagnostics
			int exitCode;
			try {
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						support.captureOutputLine(line);
					}
				}
				exitCode = proc.waitFor();
			} catch (InterruptedException e) {
				proc.destroyForcibly();
				Thread.currentThread().interrupt();
				return new TestRunResult(testOrder, Set.of(), new HashSet<>(testOrder));
			} catch (IOException e) {
				proc.destroyForcibly();
				throw e;
			}

			support.logSubprocessExitIfNeeded(exitCode);

			// Parse results from Surefire XML reports
			return JUnitXmlParser.parseClassResults(reportDir, runAll ? List.of() : testOrder, false,
					JUnitXmlParser.MissingReportPolicy.PASS);
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
			support.setupRuntimeConfigForMethods();

			// Clean previous reports
			support.cleanReports();

			// Build the maven command — use initialize + surefire:test so lifecycle
			// plugins (JaCoCo etc.) can set @{argLine} before Surefire runs.
			// Pass the method order file as a user property (forwarded to forked JVM)
			// instead of via -DargLine to preserve @{argLine} (JaCoCo --add-opens etc.)
			List<String> command = new ArrayList<>(List.of(findMavenExecutableForProject(), "initialize",
					"surefire:test", "-Dtest=" + testClass, "-DfailIfNoTests=false",
					"-Dsurefire.failIfNoSpecifiedTests=false", "-Dspotless.check.skip=true",
					"-Dtestorder.fixed.method.order.file=" + methodOrderFile.toAbsolutePath(),
					"-Dmaven.test.additionalClasspath=" + buildAdditionalClasspath(), "--batch-mode", "--quiet"));
			command.addAll(PLUGIN_SKIP_FLAGS);

			File workDir = resolveWorkDir();
			if (isSubmodule()) {
				command.add(2, "-pl");
				command.add(3, ":" + project.getArtifactId());
			}

			ProcessBuilder pb = new ProcessBuilder(command);
			pb.directory(workDir);
			pb.redirectErrorStream(true);

			Process proc = pb.start();
			try {
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						support.captureOutputLine(line);
					}
				}
				int exitCode = proc.waitFor();
				support.logSubprocessExitIfNeeded(exitCode);
			} catch (InterruptedException e) {
				proc.destroyForcibly();
				Thread.currentThread().interrupt();
				return new MethodRunResult(testClass, methodOrder, Set.of(), new HashSet<>(methodOrder));
			} catch (IOException e) {
				proc.destroyForcibly();
				throw e;
			}

			// Parse method-level results from Surefire XML
			return JUnitXmlParser.parseMethodResults(reportDir, testClass, methodOrder,
					JUnitXmlParser.MissingReportPolicy.PASS);
		} catch (IOException e) {
			log.warn("Method-level test execution failed: " + e.getMessage());
			return new MethodRunResult(testClass, methodOrder, Set.of(), new HashSet<>(methodOrder));
		}
	}

	/**
	 * Returns true when this project is a submodule (has a parent pom.xml and is
	 * not the execution root).
	 */
	private boolean isSubmodule() {
		return project.getBasedir().getParentFile() != null
				&& new File(project.getBasedir().getParentFile(), "pom.xml").exists() && !project.isExecutionRoot();
	}

	/**
	 * Resolves the working directory for Maven subprocess invocations. Submodule
	 * builds run from the parent directory; standalone builds run from the project
	 * directory.
	 */
	private File resolveWorkDir() {
		return isSubmodule() ? project.getBasedir().getParentFile() : project.getBasedir();
	}

	private Path writeOrderFile(List<String> testOrder) throws IOException {
		Path orderFile = Path.of(project.getBuild().getDirectory(), "test-order-detect.txt");
		Files.createDirectories(orderFile.getParent());
		Files.writeString(orderFile, String.join("\n", testOrder));
		return orderFile;
	}

	private Path writeMethodOrderFile(List<String> methodOrder) throws IOException {
		Path orderFile = Path.of(project.getBuild().getDirectory(), "test-order-method-detect.txt");
		Files.createDirectories(orderFile.getParent());
		Files.writeString(orderFile, String.join("\n", methodOrder));
		return orderFile;
	}

	/**
	 * Builds the comma-separated additional classpath string for Surefire. Always
	 * includes the runtime dir (contains junit-platform.properties). Appends any
	 * extra JARs passed in via the constructor (orderer classpath).
	 */
	private String buildAdditionalClasspath() {
		StringBuilder sb = new StringBuilder(runtimeDir.toAbsolutePath().toString());
		for (String extra : extraClasspath) {
			sb.append(',').append(extra);
		}
		return sb.toString();
	}

	/**
	 * Resolves the Maven executable. Lookup order:
	 * <ol>
	 * <li>{@code MAVEN_HOME/bin/mvn[.cmd]} — explicit installation</li>
	 * <li>{@code ./mvnw[.cmd]} in the working directory — Maven Wrapper (common in
	 * CI environments that ship only the wrapper)</li>
	 * <li>{@code mvn[.cmd]} on PATH — bare fallback</li>
	 * </ol>
	 */
	private String findMavenExecutableForProject() {
		String exe = findMavenExecutable();
		if (!exe.startsWith("mvn"))
			return exe; // MAVEN_HOME found
		// Check for mvnw in the project working directory
		boolean isWindows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("win");
		String wrapperName = isWindows ? "mvnw.cmd" : "mvnw";
		Path wrapper = resolveWorkDir().toPath().resolve(wrapperName);
		if (wrapper.toFile().canExecute())
			return wrapper.toAbsolutePath().toString();
		return exe;
	}

	/**
	 * Resolves the Maven executable, preferring MAVEN_HOME/M2_HOME over the bare
	 * {@code mvn} on PATH. Falls back to {@code mvn} (or {@code mvn.cmd} on
	 * Windows) if neither env var is set.
	 */
	static String findMavenExecutable() {
		boolean isWindows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("win");
		String ext = isWindows ? ".cmd" : "";
		String mavenHome = System.getenv("MAVEN_HOME");
		if (mavenHome == null)
			mavenHome = System.getenv("M2_HOME");
		if (mavenHome != null) {
			Path mvn = Path.of(mavenHome, "bin", "mvn" + ext);
			if (mvn.toFile().canExecute())
				return mvn.toString();
		}
		return "mvn" + ext;
	}
}
