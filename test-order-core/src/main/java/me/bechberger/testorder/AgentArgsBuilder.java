package me.bechberger.testorder;

import java.nio.file.Path;

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
	 *            instrumentation mode (FULL, METHOD_ENTRY, etc.)
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
	 *            instrumentation mode (FULL, METHOD_ENTRY, etc.)
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
		StringBuilder args = new StringBuilder();
		args.append("outputDir=").append(outputDir.toAbsolutePath());
		args.append(",mode=").append(instrumentationMode.toUpperCase());
		if (indexFile != null) {
			args.append(",indexFile=").append(indexFile.toAbsolutePath());
		}
		if (includePackages != null && !includePackages.isEmpty()) {
			args.append(",includePackages=").append(includePackages.replace(",", ";"));
		}
		if (verboseFile != null && !verboseFile.isEmpty()) {
			args.append(",verboseFile=").append(Path.of(verboseFile).toAbsolutePath());
		}
		if (instrumentTestClasses) {
			args.append(",skipTestClasses=false");
		}
		return args.toString();
	}
}
