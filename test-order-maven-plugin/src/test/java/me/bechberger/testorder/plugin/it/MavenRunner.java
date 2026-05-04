package me.bechberger.testorder.plugin.it;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs Maven commands against a project directory, capturing output. Discovers
 * Maven from PATH or MAVEN_HOME.
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

	/** Run {@code mvn test-order:optimize} */
	public MavenResult optimize() {
		return run("test-order:optimize");
	}

	/** Run {@code mvn test-order:snapshot} */
	public MavenResult snapshot() {
		return run("test-order:snapshot");
	}

	/** Run Maven with arbitrary goals and arguments */
	public MavenResult run(String... args) {
		String mvn = findMavenExecutable();
		List<String> command = new ArrayList<>();
		command.add(mvn);
		command.add("-B"); // batch mode (non-interactive)
		command.add("-Dspotless.check.skip=true"); // ITs modify source files
		command.addAll(defaultArgs);
		command.addAll(List.of(args));

		try {
			ProcessBuilder pb = new ProcessBuilder(command).directory(projectDir.toFile()).redirectErrorStream(true);
			pb.environment().put("MAVEN_OPTS", "");

			Process process = pb.start();
			String output = new String(process.getInputStream().readAllBytes());

			boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				return new MavenResult(-1, output + "\n[TIMEOUT after " + DEFAULT_TIMEOUT_SECONDS + "s]",
						List.copyOf(command), projectDir);
			}

			return new MavenResult(process.exitValue(), output, List.copyOf(command), projectDir);
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException("Failed to run Maven: " + command, e);
		}
	}

	private static String findMavenExecutable() {
		// prefer MAVEN_HOME, then M2_HOME, then PATH
		String mavenHome = System.getenv("MAVEN_HOME");
		if (mavenHome == null)
			mavenHome = System.getenv("M2_HOME");
		if (mavenHome != null) {
			Path mvn = Path.of(mavenHome, "bin", "mvn");
			if (mvn.toFile().canExecute())
				return mvn.toString();
		}
		return "mvn"; // fall back to PATH
	}
}
