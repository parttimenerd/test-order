package me.bechberger.testorder.agent.runtime;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Consumer;

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
			writer = new PrintWriter(new OutputStreamWriter(Files.newOutputStream(java.nio.file.Path.of(path),
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND),
					StandardCharsets.UTF_8), true);
			enabled = true;
			writer.println("[test-order] Verbose logging enabled → " + path);
		} catch (Exception e) {
			System.err.println("[test-order] Failed to open verbose log file: " + path + " — " + e.getMessage());
			closeWriter();
		}
	}

	/** Log a message if verbose mode is active. No-op otherwise. */
	public static void log(String message) {
		if (!enabled) {
			return;
		}
		write(writer -> writer.println(message));
	}

	/** Check whether verbose mode is active. */
	public static boolean isVerbose() {
		return enabled;
	}

	/**
	 * Always prints to stderr regardless of verbose mode; also writes to the
	 * verbose log file when active. Use for key lifecycle events that a user should
	 * always see (e.g. index written confirmation).
	 */
	public static void info(String message) {
		System.err.println("[test-order] " + message);
		if (!enabled) {
			return;
		}
		write(writer -> writer.println("[INFO] " + message));
	}

	public static void warn(String message) {
		if (!enabled) {
			return;
		}
		write(writer -> writer.println("[WARN] " + message));
		System.err.println("[test-order] " + message);
	}

	public static void error(String message, Throwable throwable) {
		// Always print errors to stderr so they are visible even without verbose mode.
		System.err.println("[test-order] ERROR: " + message);
		if (throwable != null) {
			throwable.printStackTrace(System.err);
		}
		if (!enabled) {
			return;
		}
		write(writer -> {
			writer.println("[ERROR] " + message);
			if (throwable != null) {
				throwable.printStackTrace(writer);
			}
		});
	}

	private static synchronized void write(Consumer<PrintWriter> action) {
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
