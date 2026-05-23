package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import me.bechberger.testorder.ops.DetectDependenciesOperation;
import me.bechberger.testorder.ops.DetectDependenciesOperation.Config;
import me.bechberger.testorder.ops.DetectDependenciesOperation.Result;
import me.bechberger.testorder.ops.detection.TestRunner;

/**
 * Maven goal to detect order-dependent tests in the project. Runs test suites
 * in various orders to discover tests that pass or fail depending on execution
 * sequence.
 *
 * <p>
 * Usage: {@code mvn test-order:detect-dependencies}
 *
 * <p>
 * Requires a TestRunner implementation to be configured (see documentation).
 */
@Mojo(name = "detect-dependencies", threadSafe = true, requiresProject = true)
public class DetectDependenciesMojo extends AbstractTestOrderMojo {

	/**
	 * Detection algorithm to use. Options: combined, reverse, random, history,
	 * pfast, iterative, bounded, tuscan.
	 */
	@Parameter(property = "testorder.detect.algorithm", defaultValue = "combined")
	private String algorithm;

	/** Time budget in seconds. 0 means unlimited. */
	@Parameter(property = "testorder.detect.timeBudget", defaultValue = "300")
	private int timeBudget;

	/** Stop after finding the first OD pair. */
	@Parameter(property = "testorder.detect.stopOnFirst", defaultValue = "false")
	private boolean stopOnFirst;

	/** Random seed for reproducibility. */
	@Parameter(property = "testorder.detect.seed", defaultValue = "42")
	private long randomSeed;

	/** Fail the build if OD bugs are detected. */
	@Parameter(property = "testorder.detect.failOnDetection", defaultValue = "false")
	private boolean failOnDetection;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip) {
			return;
		}

		// In a multi-module reactor, only run detection from the top-level project.
		// Without this guard, Maven invokes execute() on every module (non-aggregator),
		// causing O(N²) detection runs and N redundant reactor installs.
		if (session != null && session.getProjects() != null && session.getProjects().size() > 1
				&& !project.equals(session.getTopLevelProject())) {
			getLog().debug("[test-order] Skipping detect-dependencies — handled by reactor root.");
			return;
		}

		// Early check: JUnit 4 without JUnit 5 is a hard blocker for detection
		if (isJUnit4OnTestClasspath() && !isJUnit5OnTestClasspath()) {
			String msg = "[test-order] Cannot detect order-dependent tests: "
					+ "this project uses JUnit 4 but test-order requires JUnit 5 (Jupiter).\n"
					+ "  To fix, add the JUnit Vintage engine so JUnit 4 tests run under the JUnit 5 platform:\n" + "\n"
					+ "    <dependency>\n" + "      <groupId>org.junit.vintage</groupId>\n"
					+ "      <artifactId>junit-vintage-engine</artifactId>\n"
					+ "      <version>${junit.version}</version>\n" + "      <scope>test</scope>\n"
					+ "    </dependency>\n" + "    <dependency>\n" + "      <groupId>org.junit.jupiter</groupId>\n"
					+ "      <artifactId>junit-jupiter-engine</artifactId>\n"
					+ "      <version>${junit.version}</version>\n" + "      <scope>test</scope>\n"
					+ "    </dependency>\n" + "\n" + "  Replace ${junit.version} with your project's JUnit 5 version.\n"
					+ "  Alternatively, migrate your tests to JUnit 5.";
			throw new MojoExecutionException(msg);
		}

		// Multi-module support: determine which projects to analyze
		List<MavenProject> projectsToAnalyze = getProjectsToAnalyze();

		// For multi-module projects, ensure modules are installed so cross-module
		// dependencies resolve in subprocess test runs (#28)
		if (projectsToAnalyze.size() > 1) {
			runReactorInstall();
		}

		int totalFindings = 0;

		for (MavenProject moduleProject : projectsToAnalyze) {
			int findings = runDetectionForModule(moduleProject);
			totalFindings += findings;
		}

		if (totalFindings > 0 && failOnDetection) {
			throw new MojoExecutionException("[test-order] Order-dependent tests detected (" + totalFindings
					+ " findings across " + projectsToAnalyze.size() + " module(s)). "
					+ "Build failed because -Dtestorder.detect.failOnDetection=true is set.");
		} else if (totalFindings > 0) {
			getLog().warn("[test-order] Order-dependent tests detected (" + totalFindings + " findings). "
					+ "Use -Dtestorder.detect.failOnDetection=true to fail the build.");
		}
	}

	/**
	 * Run {@code mvn install -DskipTests} from the reactor root to ensure
	 * cross-module dependencies are installed in the local repo before running
	 * detection subprocesses.
	 */
	private void runReactorInstall() throws MojoExecutionException {
		getLog().info(
				"[test-order] Multi-module project: running reactor install to resolve cross-module dependencies...");
		List<String> command = new ArrayList<>(List.of(MavenTestRunner.findMavenExecutable(), "install", "-DskipTests",
				"-Dspotless.check.skip=true", "--batch-mode", "--quiet"));
		command.addAll(
				List.of("-Drat.skip=true", "-Dcheckstyle.skip=true", "-Dspotbugs.skip=true", "-Denforcer.skip=true",
						"-Dpmd.skip=true", "-Djacoco.skip=true", "-Dlicense.skip=true", "-Danimal.sniffer.skip=true"));

		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(session.getTopLevelProject().getBasedir());
		pb.redirectErrorStream(true);

		try {
			Process proc = pb.start();
			try {
				try (java.io.BufferedReader reader = new java.io.BufferedReader(
						new java.io.InputStreamReader(proc.getInputStream()))) {
					String line;
					while ((line = reader.readLine()) != null) {
						getLog().debug(line);
					}
				}
				int exitCode = proc.waitFor();
				if (exitCode != 0) {
					throw new MojoExecutionException("[test-order] Reactor install failed (exit code " + exitCode
							+ "). " + "Try running 'mvn install -DskipTests' manually first.");
				}
				getLog().info("[test-order] Reactor install complete.");
			} catch (InterruptedException e) {
				proc.destroyForcibly();
				Thread.currentThread().interrupt();
				throw new MojoExecutionException("[test-order] Reactor install interrupted", e);
			}
		} catch (java.io.IOException e) {
			throw new MojoExecutionException("[test-order] Reactor install failed: " + e.getMessage(), e);
		}
	}

	private List<MavenProject> getProjectsToAnalyze() {
		List<MavenProject> reactorProjects = session.getProjects();
		if (reactorProjects == null || reactorProjects.size() <= 1) {
			return List.of(project);
		}

		// Filter to modules that have test sources (skip parent POMs)
		List<MavenProject> testModules = reactorProjects.stream().filter(p -> !"pom".equals(p.getPackaging()))
				.filter(p -> {
					Path testDir = p.getBasedir().toPath().resolve("src/test");
					return Files.exists(testDir);
				}).collect(Collectors.toList());

		if (testModules.isEmpty()) {
			return List.of(project);
		}

		getLog().info("[test-order] Multi-module project: analyzing " + testModules.size() + " modules with tests");
		return testModules;
	}

	private int runDetectionForModule(MavenProject moduleProject) throws MojoExecutionException {
		// Use ReactorContext-resolved paths so detection reads/writes the same
		// shared index and state used by prepare/auto/learn goals.
		Path indexPath = ctx.resolveIndexFile(indexFile);
		Path statePath = ctx.resolveStateFile(stateFile);
		Path outputDir = ctx.resolveBaseDir().resolve("detection");

		getLog().info("[test-order] [" + moduleProject.getArtifactId() + "] Starting OD detection (algorithm="
				+ algorithm + ", budget=" + (timeBudget > 0 ? timeBudget + "s" : "unlimited")
				+ "). This may take a while...");

		Config config = new Config(indexPath, statePath, outputDir, algorithm, timeBudget, stopOnFirst, randomSeed,
				moduleProject.getArtifactId(), MavenPluginLog.wrap(getLog()));

		// Resolve test-order-junit and dependencies so the FixedOrderClassOrderer
		// is available on the forked test classpath
		List<String> ordererClasspath;
		try {
			Path[] resolved = resolveOrdererClasspath();
			ordererClasspath = java.util.Arrays.stream(resolved).map(p -> p.toAbsolutePath().toString()).toList();
		} catch (MojoExecutionException e) {
			getLog().warn("[test-order] Could not resolve orderer classpath: " + e.getMessage());
			ordererClasspath = List.of();
		}

		TestRunner runner = new MavenTestRunner(moduleProject, session, getLog(), ordererClasspath);

		try {
			Result result = DetectDependenciesOperation.run(config, runner);

			if (result.hasFindings()) {
				int classLevel = result.results().size();
				int methodLevel = result.methodResults().size();
				int total = classLevel + methodLevel;
				getLog().warn("[test-order] [" + moduleProject.getArtifactId() + "] Detected " + total
						+ " order-dependent finding(s): " + result.victimCount() + " class-level victims, "
						+ result.brittleCount() + " class-level brittles"
						+ (methodLevel > 0 ? ", " + methodLevel + " method-level" : ""));
				if (result.reportPath() != null) {
					getLog().warn("[test-order] JSON report: " + result.reportPath());
				}
				if (result.markdownReportPath() != null) {
					getLog().warn("[test-order] Markdown report: " + result.markdownReportPath());
				}
				return total;
			} else {
				getLog().info(
						"[test-order] [" + moduleProject.getArtifactId() + "] No order-dependent tests detected.");
				return 0;
			}
		} catch (IOException e) {
			throw new MojoExecutionException(
					"Detection failed for " + moduleProject.getArtifactId() + ": " + e.getMessage(), e);
		}
	}
}
