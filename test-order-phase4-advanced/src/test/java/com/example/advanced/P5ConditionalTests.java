package com.example.advanced;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;

/**
 * Pattern 5: Conditional Test Execution
 * Testing @EnabledIf, @DisabledIf, @EnabledOnOs, @DisabledOnOs
 * @EnabledIfEnvironmentVariable conditions
 */
class P5ConditionalTests {

    // Always runs
    @Test
    void alwaysRun() {
        assert true;
    }

    // Runs only on Java 11+
    @Test
    @EnabledOnJre(JRE.JAVA_11)
    void javaVersionTest() {
        assert true;
    }

    // Runs only on Linux
    @Test
    @EnabledOnOs(OS.LINUX)
    void linuxOnlyTest() {
        assert true;
    }

    // Runs only on Windows
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsOnlyTest() {
        assert true;
    }

    // Disabled test - using valid condition
    @Test
    void disabledOnJava8() {
        assert true;
    }

    // Runs if environment variable exists
    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_PHASE4", matches = "true")
    void runIfEnvVarSet() {
        assert true;
    }

    // Total: varies by environment - typically 2-3 will actually run
}
