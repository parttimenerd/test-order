package me.bechberger.testorder.agent.runtime;

import java.io.FileWriter;
import java.io.PrintWriter;

/**
 * Simple file-based logger for the test-order agent, living on the bootstrap classpath
 * so it's visible to both the agent (system classloader) and the runtime (bootstrap).
 * <p>
 * When a verbose file path is configured, all {@link #log} calls append to that file.
 * When not configured, logging is a no-op.
 */
public class AgentLogger {

    private static volatile PrintWriter writer;
    private static boolean enabled;  // plain boolean; avoids volatile read on every isVerbose() call

    private AgentLogger() {}

    /** Enable verbose logging to the given file path. Called by Agent via reflection. */
    public static synchronized void setVerboseFile(String path) {
        if (writer != null) {
            writer.close();
        }
        try {
            writer = new PrintWriter(new FileWriter(path, true), true);
            enabled = true;
            writer.println("[test-order] Verbose logging enabled → " + path);
        } catch (Exception e) {
            System.err.println("[test-order] Failed to open verbose log file: " + path + " — " + e.getMessage());
            writer = null;
            enabled = false;
        }
    }

    /** Log a message if verbose mode is active. No-op otherwise. */
    public static void log(String message) {
        PrintWriter w = writer;
        if (w != null) {
            w.println(message);
        }
    }

    /** Check whether verbose mode is active. Plain boolean avoids volatile read on hot path. */
    public static boolean isVerbose() {
        return enabled;
    }
}
