package me.bechberger.testorder.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;

/**
 * Shared utility methods for Gradle plugin tasks.
 * Eliminates repeated boilerplate across task registrations.
 */
final class GradlePluginSupport {

    private GradlePluginSupport() {
    }

    /**
     * Checks that the index file exists; throws a descriptive GradleException if not.
     * Used by tasks that require a pre-existing dependency index.
     */
    static Path requireIndex(TestOrderExtension ext) {
        Path indexFile = ext.getIndexFile().get().getAsFile().toPath();
        if (!Files.exists(indexFile)) {
            throw new GradleException("[test-order] No dependency index found at " + indexFile
                    + " — run tests in learn mode first.");
        }
        return indexFile;
    }

    /**
     * Wraps an IOException-throwing operation, converting to GradleException.
     *
     * @param action  the operation that may throw IOException
     * @param message error message prefix if the operation fails
     */
    static void wrapIO(IORunnable action, String message) {
        try {
            action.run();
        } catch (IOException e) {
            throw new GradleException(message + ": " + e.getMessage(), e);
        }
    }

    /**
     * Wraps an IOException-throwing supplier, converting to GradleException.
     *
     * @param supplier the operation that may throw IOException
     * @param message  error message prefix if the operation fails
     * @return the result
     */
    static <T> T wrapIOGet(IOSupplier<T> supplier, String message) {
        try {
            return supplier.get();
        } catch (IOException e) {
            throw new GradleException(message + ": " + e.getMessage(), e);
        }
    }

    /**
     * Injects PriorityClassOrderer and debug flag into a test task.
     * This pattern is needed in order mode, select mode, and optimize mode.
     */
    static void configureOrderer(Test task, Project project) {
        task.systemProperty("junit.jupiter.testclass.order.default",
                "me.bechberger.testorder.junit.PriorityClassOrderer");
        String debugFlag = TestOrderPlugin.gradleOrSystemProperty(project, "testorder.debug");
        if ("true".equalsIgnoreCase(debugFlag)) {
            task.systemProperty("testorder.debug", "true");
        }
    }

    /**
     * Resolves a property with CLI override support.
     * Checks Gradle project property / system property first, then falls back to extension default.
     *
     * @param project  the Gradle project
     * @param key      the property key (e.g., "testorder.select.topN")
     * @param fallback the extension default value supplier
     * @return the resolved string value, or null if neither is set
     */
    static String resolveProperty(Project project, String key, Supplier<String> fallback) {
        String override = TestOrderPlugin.gradleOrSystemProperty(project, key);
        if (override != null && !override.isBlank()) {
            return override;
        }
        return fallback.get();
    }

    /**
     * Resolves an integer property with CLI override support.
     *
     * @param project      the Gradle project
     * @param key          the property key
     * @param fallback     the extension default
     * @param propertyName human-readable name for error messages
     * @return the resolved integer value
     */
    static int resolveIntProperty(Project project, String key, int fallback, String propertyName) {
        String override = TestOrderPlugin.gradleOrSystemProperty(project, key);
        if (override != null && !override.isBlank()) {
            try {
                return Integer.parseInt(override);
            } catch (NumberFormatException e) {
                throw new GradleException("[test-order] Invalid value for " + propertyName + ": '"
                        + override + "' (expected integer)");
            }
        }
        return fallback;
    }

    /**
     * Resolves a boolean property with CLI override support.
     */
    static boolean resolveBooleanProperty(Project project, String key, boolean fallback) {
        String override = TestOrderPlugin.gradleOrSystemProperty(project, key);
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override);
        }
        return fallback;
    }

    @FunctionalInterface
    interface IORunnable {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface IOSupplier<T> {
        T get() throws IOException;
    }
}
