package me.bechberger.testorder.maven.it;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs Maven commands against a project directory, capturing output. Discovers
 * Maven from PATH or MAVEN_HOME.
 * <p>
 * Handles macOS-specific file handle contention by waiting for all descendant
 * processes to fully exit and retrying on transient fork/clean errors.
 */
public class MavenRunner {

	private static final long DEFAULT_TIMEOUT_SECONDS = 120;

	private final Path projectDir;
	private final List<String> defaultArgs;

	public MavenRunner(Path projectDir) {
		this(projectDir, List.of());
	}

	public MavenRunner(Path projectDir, List<String> defaultArgs) {
		this.projectDir = projectDir;
		this.defaultArgs = defaultArgs;
	}

	/** Run {@code mvn clean test -Dtestorder.mode=learn} */
	public MavenResult learn() {
		return run("clean", "test", "-Dtestorder.mode=learn");
	}

	/**
	 * Run {@code mvn clean test -Dtestorder.mode=learn} with verbose agent logging
	 */
	public MavenResult learnVerbose(Path verboseFile) {
		return run("clean", "test", "-Dtestorder.mode=learn",
				"-Dtestorder.verboseFile=" + verboseFile.toAbsolutePath());
	}

	/**
	 * Run {@code mvn clean test -Dtestorder.mode=order} with explicit changed
	 * classes
	 */
	public MavenResult order(String... changedClasses) {
		List<String> args = new ArrayList<>(
				List.of("clean", "test", "-Dtestorder.mode=order", "-Dtestorder.changeMode=explicit"));
		if (changedClasses.length > 0) {
			args.add("-Dtestorder.changed.classes=" + String.join(",", changedClasses));
		}
		return run(args.toArray(String[]::new));
	}

	/**
	 * Run {@code mvn clean test} (auto mode — the default) with explicit changed
	 * classes
	 */
	public MavenResult auto(String... changedClasses) {
		List<String> args = new ArrayList<>(List.of("clean", "test", "-Dtestorder.changeMode=explicit"));
		if (changedClasses.length > 0) {
			args.add("-Dtestorder.changed.classes=" + String.join(",", changedClasses));
		}
		return run(args.toArray(String[]::new));
	}

	/** Run {@code mvn test-order:show-order} with explicit changed classes */
	public MavenResult showOrder(String... changedClasses) {
		List<String> args = new ArrayList<>(List.of("test-order:show-order", "-Dtestorder.changeMode=explicit"));
		if (changedClasses.length > 0) {
			args.add("-Dtestorder.changed.classes=" + String.join(",", changedClasses));
		}
		return run(args.toArray(String[]::new));
	}

	/** Run {@code mvn test-order:dump} */
	public MavenResult dump() {
		return run("test-order:dump");
	}

	/** Run {@code mvn test-order:export-json} */
	public MavenResult exportJson() {
		return run("test-order:export-json");
	}

	/** Run {@code mvn test-order:export-json -Dtestorder.exportJson.output=<path>} */
	public MavenResult exportJsonTo(String outputPath) {
		return run("test-order:export-json", "-Dtestorder.exportJson.output=" + outputPath);
	}

	/** Run {@code mvn test-order:aggregate} */
	public MavenResult aggregate() {
		return run("test-order:aggregate");
	}

	/** Run learn mode with METHOD_ENTRY instrumentation */
	public MavenResult learnMethodEntry() {
		return run("clean", "test", "-Dtestorder.mode=learn", "-Dtestorder.instrumentation.mode=METHOD_ENTRY");
	}

	/** Run {@code mvn test-order:select test} with explicit changed classes */
	public MavenResult select(String... changedClasses) {
		List<String> args = new ArrayList<>(
				List.of("clean", "test-order:select", "test", "-Dtestorder.changeMode=explicit"));
		if (changedClasses.length > 0) {
			args.add("-Dtestorder.changed.classes=" + String.join(",", changedClasses));
		}
		return run(args.toArray(String[]::new));
	}

	/** Run {@code mvn test-order:run-remaining test} */
	public MavenResult runRemaining() {
		return run("test-order:run-remaining", "test");
	}

	/** Run {@code mvn test-order:auto test} */
	public MavenResult auto() {
		return run("clean", "test-order:auto", "test");
	}

	/**
	 * Run {@code mvn test-order:auto test} with an explicit {@code testorder.mode}
	 * value and optional explicit changed classes.
	 */
	public MavenResult autoWithMode(String mode, String... changedClasses) {
		List<String> args = new ArrayList<>(List.of("clean", "test-order:auto", "test", "-Dtestorder.mode=" + mode));
		if (changedClasses.length > 0) {
			args.add("-Dtestorder.changeMode=explicit");
			args.add("-Dtestorder.changed.classes=" + String.join(",", changedClasses));
		}
		return run(args.toArray(String[]::new));
	}

	/** Run {@code mvn test-order:tiered-select test} with explicit changed classes */
	public MavenResult tieredSelect(String... changedClasses) {
		List<String> args = new ArrayList<>(
				List.of("test-order:tiered-select", "test", "-Dtestorder.changeMode=explicit",
						"-Dsurefire.failIfNoSpecifiedTests=false"));
		if (changedClasses.length > 0) {
			args.add("-Dtestorder.changed.classes=" + String.join(",", changedClasses));
		}
		return run(args.toArray(String[]::new));
	}

	/** Run {@code mvn test-order:run-tier test -Dtestorder.tiered.currentTier=N} */
	public MavenResult runTier(int tier) {
		return run("test-order:run-tier", "test",
				"-Dtestorder.tiered.currentTier=" + tier,
				"-Dsurefire.failIfNoSpecifiedTests=false");
	}

	/** Run {@code mvn test-order:optimize} */
	public MavenResult optimize() {
		return run("test-order:optimize");
	}

	/** Run {@code mvn test-order:snapshot} */
	public MavenResult snapshot() {
		return run("test-order:snapshot");
	}

	/**
	 * Run Maven with arbitrary goals and arguments. Retries up to 2 times on
	 * transient fork/clean errors caused by macOS resource contention.
	 */
	public MavenResult run(String... args) {
		MavenResult result = runOnce(args);
		for (int retry = 0; retry < 2 && result.exitCode() != 0 && isTransientError(result.output()); retry++) {
			try {
				Thread.sleep(1000L * (retry + 1));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			result = runOnce(args);
		}
		return result;
	}

	private boolean isTransientError(String output) {
		return output.contains("forked VM terminated without properly saying goodbye")
				|| output.contains("There was an error in the forked process")
				|| output.contains("unexpected error occurred while trying to open file")
				|| output.contains("Failed to clean project")
				|| output.contains("Error occurred in starting fork")
				|| output.contains("unable to access file: java.nio.file.NoSuchFileException")
				// Compilation failures due to stale file locks preventing clean from working
				|| (output.contains("cannot find symbol") && output.contains("COMPILATION ERROR"))
				// Class loading failures in forked surefire JVM after clean (APFS race)
				|| output.contains("NoClassDefFoundError")
				|| output.contains("No tests matching pattern");
	}

	private MavenResult runOnce(String... args) {
		String mvn = findMavenExecutable();
		List<String> command = new ArrayList<>();
		command.add(mvn);
		command.add("-B");
		command.add("-Dspotless.check.skip=true");
		command.add("-Djacoco.skip=true");
		command.add("-Dmaven.compiler.useIncrementalCompilation=false");
		// Force single fork — ensures predictable fork lifecycle. Avoids
		// reuseForks=false which causes intermittent ClassNotFoundException on macOS
		// when multiple IT classes share the same sample project under load.
		command.add("-DforkCount=1");
		command.addAll(defaultArgs);
		command.addAll(List.of(args));

		try {
			ProcessBuilder pb = new ProcessBuilder(command).directory(projectDir.toFile()).redirectErrorStream(true);
			pb.environment().put("MAVEN_OPTS", "");

			Process process = pb.start();
			String output = new String(process.getInputStream().readAllBytes());

			boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			if (!finished) {
				killProcessTree(process);
				return new MavenResult(-1, output + "\n[TIMEOUT after " + DEFAULT_TIMEOUT_SECONDS + "s]",
						List.copyOf(command), projectDir);
			}

			int exitCode = process.exitValue();
			waitForDescendantsToExit(process);
			return new MavenResult(exitCode, output, List.copyOf(command), projectDir);
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException("Failed to run Maven: " + command, e);
		}
	}

	/**
	 * Wait for all descendant processes (forked surefire JVMs) to fully terminate.
	 * On macOS, even after the parent Maven process exits, forked JVMs may still be
	 * running shutdown hooks (writing .lz4 files, state data) or holding open file
	 * descriptors on target/. We first give them time to exit gracefully (shutdown
	 * hooks need to complete), only killing them if they hang.
	 */
	private static void waitForDescendantsToExit(Process process) throws InterruptedException {
		List<ProcessHandle> descendants = process.descendants().toList();
		if (descendants.isEmpty()) {
			// Even with no visible descendants, macOS may still be releasing handles
			// from JVMs that exited and were reparented to PID 1 before we checked.
			// The agent's shutdown hook writes state/deps files; allow time for that
			// I/O to flush and become visible. 4 seconds covers the observed worst-case
			// on macOS APFS with concurrent IT test classes.
			Thread.sleep(4000);
			return;
		}
		// Phase 1: Wait for descendants to exit naturally (shutdown hooks writing data)
		long gracePeriod = System.currentTimeMillis() + 8_000;
		boolean allExited = false;
		while (!allExited && System.currentTimeMillis() < gracePeriod) {
			allExited = true;
			for (ProcessHandle ph : descendants) {
				if (ph.isAlive()) {
					allExited = false;
					break;
				}
			}
			if (!allExited) {
				Thread.sleep(200);
			}
		}
		// Phase 2: Kill any that are still alive after grace period
		for (ProcessHandle ph : descendants) {
			if (!ph.isAlive())
				continue;
			ph.destroy();
			try {
				ph.onExit().orTimeout(2_000, TimeUnit.MILLISECONDS).join();
			} catch (Exception ignored) {
			}
			if (ph.isAlive()) {
				ph.destroyForcibly();
			}
		}
		// Allow OS kernel to fully release file handles after all descendants exit
		Thread.sleep(300);
	}

	private static void killProcessTree(Process process) {
		process.descendants().forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
	}

	private static String findMavenExecutable() {
		String mavenHome = System.getenv("MAVEN_HOME");
		if (mavenHome == null)
			mavenHome = System.getenv("M2_HOME");
		if (mavenHome != null) {
			Path mvn = Path.of(mavenHome, "bin", "mvn");
			if (mvn.toFile().canExecute())
				return mvn.toString();
		}
		return "mvn";
	}
}
