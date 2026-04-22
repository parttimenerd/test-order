package me.bechberger.testorder.agent.runtime;

import java.io.FileWriter;
import java.io.PrintWriter;

/**
 * Simple file-based logger for the test-order agent, living on the bootstrap
 * classpath so it's visible to both the agent (system classloader) and the
 * runtime (bootstrap).
 * <p>
 * When a verbose file path is configured, all {@link #log} calls append to that
 * file. When not configured, logging is a no-op.
 */
public class AgentLogger {

	private static volatile PrintWriter writer;
	private static volatile boolean enabled;

	private AgentLogger() {
	}

	/**
	 * Enable verbose logging to the given file path. Called by Agent via
	 * reflection.
	 */
	public static synchronized void setVerboseFile(String path) {
		closeWriter();
		try {
			writer = new PrintWriter(new FileWriter(path, true), true);
			enabled = true;
			writer.println("[test-order] Verbose logging enabled → " + path);
		} catch (Exception e) {
			System.err.println("[test-order] Failed to open verbose log file: " + path + " — " + e.getMessage());
			closeWriter();
		}
	}

	/** Log a message if verbose mode is active. No-op otherwise. */
	public static void log(String message) {
		write(writer -> writer.println(message));
	}

	/** Check whether verbose mode is active. */
	public static boolean isVerbose() {
		return enabled;
	}

	public static void warn(String message) {
		write(writer -> writer.println("[WARN] " + message));
		System.err.println("[test-order] " + message);
	}

	public static void error(String message, Throwable throwable) {
		write(writer -> {
			writer.println("[ERROR] " + message);
			if (throwable != null) {
				throwable.printStackTrace(writer);
			}
		});
		System.err.println("[test-order] " + message);
	}

	private static synchronized void write(java.util.function.Consumer<PrintWriter> action) {
		PrintWriter current = writer;
		if (current == null) {
			return;
		}
		action.accept(current);
		if (current.checkError()) {
			closeWriter();
		}
	}

	private static synchronized void closeWriter() {
		if (writer != null) {
			writer.close();
			writer = null;
		}
		enabled = false;
	}
}
