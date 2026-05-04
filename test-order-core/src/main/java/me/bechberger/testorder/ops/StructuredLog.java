package me.bechberger.testorder.ops;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import me.bechberger.testorder.ErrorCode;
import me.bechberger.util.json.PrettyPrinter;

/**
 * Structured logging utility for test-order operations.
 * Produces JSON-compatible log lines that can be parsed by CI/CD systems.
 *
 * <p>Format: {@code [test-order] <LEVEL> <CODE> <MESSAGE> | <JSON_CONTEXT>}
 *
 * <p>Example:
 * {@code [test-order] INFO MODE_DECISION order | {"reason":"Index exists","mode":"order","timestamp":"2026-04-29T10:30:00Z"}}
 */
public final class StructuredLog {

    public enum Level {
        DEBUG("DEBUG"),
        INFO("INFO"),
        WARN("WARN"),
        ERROR("ERROR");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private StructuredLog() {
    }

    /**
     * Create a structured log entry.
     *
     * @param level log level
     * @param code error/info code
     * @param message human-readable message
     * @param context optional JSON context data
     * @return formatted log line
     */
    public static String format(Level level, String code, String message, Map<String, ?> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("[test-order] ").append(level.label).append(" ").append(code).append(" ").append(message);

        if (context != null && !context.isEmpty()) {
            Map<String, Object> enriched = new LinkedHashMap<>(context);
            if (!enriched.containsKey("timestamp")) {
                enriched.put("timestamp", Instant.now().toString());
            }
            String json = PrettyPrinter.compactPrint(enriched);
            sb.append(" | ").append(json);
        }

        return sb.toString();
    }

    /**
     * Create a structured log entry without context.
     */
    public static String format(Level level, String code, String message) {
        return format(level, code, message, null);
    }

    /**
     * Create a builder for fluent log construction.
     */
    public static LogBuilder builder(Level level, String code) {
        return new LogBuilder(level, code);
    }

    /**
     * Fluent builder for structured logs.
     */
    public static class LogBuilder {
        private final Level level;
        private final String code;
        private String message;
        private final Map<String, Object> context = new LinkedHashMap<>();

        private LogBuilder(Level level, String code) {
            this.level = level;
            this.code = code;
        }

        public LogBuilder message(String msg) {
            this.message = msg;
            return this;
        }

        public LogBuilder context(String key, Object value) {
            this.context.put(key, value);
            return this;
        }

        public LogBuilder context(Map<String, ?> data) {
            this.context.putAll(data);
            return this;
        }

        public String build() {
            if (message == null) {
                throw new IllegalStateException("Message is required");
            }
            return format(level, code, message, context);
        }

        public String toString() {
            return build();
        }
    }

    // -----------------------------------------------
    // Convenience methods for common log scenarios
    // -----------------------------------------------

    public static String modeDecision(String mode, String reason, Map<String, ?> context) {
        Map<String, Object> ctx = new LinkedHashMap<>(context != null ? context : Map.of());
        ctx.put("mode", mode);
        ctx.put("reason", reason);
        return format(Level.INFO, "MODE_DECISION", mode, ctx);
    }

    public static String autoSwitch(String from, String to, String reason, Map<String, ?> context) {
        Map<String, Object> ctx = new LinkedHashMap<>(context != null ? context : Map.of());
        ctx.put("from_mode", from);
        ctx.put("to_mode", to);
        ctx.put("reason", reason);
        return format(Level.WARN, "AUTO_SWITCH", "Switched from " + from + " to " + to, ctx);
    }

    public static String autoAggregation(int filesFound, int testsAggregated, Map<String, ?> context) {
        Map<String, Object> ctx = new LinkedHashMap<>(context != null ? context : Map.of());
        ctx.put("deps_files_found", filesFound);
        ctx.put("tests_aggregated", testsAggregated);
        return format(Level.INFO, "AUTO_AGGREGATION", "Aggregated " + testsAggregated + " tests", ctx);
    }

    public static String newTestsDetected(java.util.List<String> tests, Map<String, ?> context) {
        Map<String, Object> ctx = new LinkedHashMap<>(context != null ? context : Map.of());
        ctx.put("new_tests", tests);
        ctx.put("count", tests.size());
        return format(Level.WARN, "NEW_TESTS_DETECTED", tests.size() + " new test classes found", ctx);
    }

    public static String indexHealth(long totalEntries, long staleEntries, Map<String, ?> context) {
        Map<String, Object> ctx = new LinkedHashMap<>(context != null ? context : Map.of());
        ctx.put("total_entries", totalEntries);
        ctx.put("stale_entries", staleEntries);
        ctx.put("stale_percentage", staleEntries > 0 ? (staleEntries * 100 / totalEntries) : 0);
        String msg = staleEntries > 0 ? "Index contains " + staleEntries + " stale entries" : "Index is clean";
        return format(Level.INFO, "INDEX_HEALTH", msg, ctx);
    }

    public static String errorWithSuggestion(ErrorCode code, String message, String suggestion) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("code", code.getCode());
        ctx.put("suggestion", suggestion);
        return format(Level.ERROR, code.name(), message, ctx);
    }
}
