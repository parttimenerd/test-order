package me.bechberger.testorder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;

/**
 * Builds the {@code -javaagent} argument string for the test-order agent.
 * <p>
 * Shared between the Maven and Gradle plugins to avoid duplicated
 * agent-argument construction logic.
 */
public final class AgentArgsBuilder {

	private AgentArgsBuilder() {
	}

	/**
	 * Builds the agent arguments string (the part after {@code =} in
	 * {@code -javaagent:path=args}).
	 *
	 * @param outputDir
	 *            path to the deps output directory
	 * @param instrumentationMode
	 *            instrumentation mode (CLASS, METHOD, etc.)
	 * @param indexFile
	 *            path to the binary index file (may be null to omit)
	 * @param includePackages
	 *            comma-separated packages (may be null)
	 * @param verboseFile
	 *            path to verbose log file (may be null or empty)
	 * @return agent arguments string
	 */
	public static String buildArgs(Path outputDir, String instrumentationMode, Path indexFile, String includePackages,
			String verboseFile) {
		return buildArgs(outputDir, instrumentationMode, indexFile, includePackages, verboseFile, true);
	}

	/**
	 * Builds the agent arguments string (the part after {@code =} in
	 * {@code -javaagent:path=args}).
	 *
	 * @param outputDir
	 *            path to the deps output directory
	 * @param instrumentationMode
	 *            instrumentation mode (CLASS, METHOD, etc.)
	 * @param indexFile
	 *            path to the binary index file (may be null to omit)
	 * @param includePackages
	 *            comma-separated packages (may be null)
	 * @param verboseFile
	 *            path to verbose log file (may be null or empty)
	 * @param instrumentTestClasses
	 *            if true, test classes (Test*, *Test, *Tests, *TestCase) will also
	 *            be instrumented so that test utility dependencies are tracked
	 * @return agent arguments string
	 */
	public static String buildArgs(Path outputDir, String instrumentationMode, Path indexFile, String includePackages,
			String verboseFile, boolean instrumentTestClasses) {
		return buildArgs(outputDir, instrumentationMode, indexFile, includePackages, verboseFile, instrumentTestClasses,
				null);
	}

	/**
	 * Builds the agent arguments string (the part after {@code =} in
	 * {@code -javaagent:path=args}).
	 *
	 * @param outputDir
	 *            path to the deps output directory
	 * @param instrumentationMode
	 *            instrumentation mode (CLASS, METHOD, etc.)
	 * @param indexFile
	 *            path to the binary index file (may be null to omit)
	 * @param includePackages
	 *            comma-separated packages (may be null)
	 * @param verboseFile
	 *            path to verbose log file (may be null or empty)
	 * @param instrumentTestClasses
	 *            if true, test classes (Test*, *Test, *Tests, *TestCase) will also
	 *            be instrumented so that test utility dependencies are tracked
	 * @param runtimeJarPath
	 *            pre-extracted runtime jar path (may be null; avoids per-fork
	 *            extraction)
	 * @return agent arguments string
	 */
	public static String buildArgs(Path outputDir, String instrumentationMode, Path indexFile, String includePackages,
			String verboseFile, boolean instrumentTestClasses, Path runtimeJarPath) {
		StringBuilder args = new StringBuilder();
		args.append("outputDir=").append(escapePath(outputDir.toAbsolutePath()));
		args.append(",mode=").append(instrumentationMode.toUpperCase());
		if (indexFile != null) {
			args.append(",indexFile=").append(escapePath(indexFile.toAbsolutePath()));
		}
		if (includePackages != null && !includePackages.isEmpty()) {
			args.append(",includePackages=").append(includePackages.replace(",", ";"));
		}
		if (verboseFile != null && !verboseFile.isEmpty()) {
			args.append(",verboseFile=").append(escapePath(Path.of(verboseFile).toAbsolutePath()));
		}
		if (instrumentTestClasses) {
			args.append(",skipTestClasses=false");
		}
		if (runtimeJarPath != null) {
			args.append(",runtimeJarPath=").append(escapePath(runtimeJarPath.toAbsolutePath()));
		}
		return args.toString();
	}

	/** Escapes commas in a path string so the agent arg parser splits correctly. */
	private static String escapePath(Path path) {
		return path.toString().replace("\\,", "\\\\,").replace(",", "\\,");
	}

	/**
	 * Pre-extract the runtime jar from the agent jar so forked JVMs can skip the
	 * extraction step. The extracted jar is placed in
	 * {@code targetDir/.test-order/test-order-runtime.jar}.
	 *
	 * @param agentJarPath
	 *            path to the test-order-agent jar
	 * @param targetDir
	 *            project target directory (e.g. {@code target/})
	 * @return path to the extracted runtime jar, or null if extraction failed
	 */
	public static Path preExtractRuntimeJar(Path agentJarPath, Path targetDir) {
		Path outputDir = targetDir.resolve(".test-order");
		Path outputJar = outputDir.resolve("test-order-runtime.jar");
		try {
			// Skip if already extracted and agent jar hasn't changed
			if (Files.exists(outputJar) && Files.size(outputJar) > 0
					&& Files.getLastModifiedTime(outputJar).compareTo(Files.getLastModifiedTime(agentJarPath)) >= 0) {
				return outputJar;
			}
			Files.createDirectories(outputDir);
			try (JarFile jar = new JarFile(agentJarPath.toFile())) {
				var entry = jar.getEntry("test-order-runtime.jar");
				if (entry == null)
					return null;
				try (InputStream in = jar.getInputStream(entry)) {
					Path tmp = Files.createTempFile(outputDir, "runtime-", ".tmp");
					try {
						Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
						Files.move(tmp, outputJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
					} catch (IOException e) {
						Files.deleteIfExists(tmp);
						throw e;
					}
				}
			}
			return outputJar;
		} catch (IOException e) {
			return null; // graceful fallback: agent will extract on its own
		}
	}
}
