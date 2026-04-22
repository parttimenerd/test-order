package com.example;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Tests for @Disabled and @DisabledIf conditional test execution.
 * Tests edge cases in test discovery and ordering.
 */
@DisplayName("Disabled and Conditional Tests")
public class DisabledConditionalTests {

    @Test
    @DisplayName("Test 1: Normal test that should run")
    public void test01_normalTest() {
        assert true;
    }

    @Test
    @Disabled("This test is disabled with direct annotation")
    @DisplayName("Test 2: Disabled with annotation")
    public void test02_disabledDirect() {
        assert false : "Should not run";
    }

    @Test
    @Disabled("Temporarily disabled for debugging")
    @DisplayName("Test 3: Another disabled test")
    public void test03_disabledAnother() {
        assert false : "Should not run";
    }

    @Test
    @DisplayName("Test 4: Normal test in disabled section")
    public void test04_normalInDisabledSection() {
        assert true;
    }

    @Test
    @DisabledIf("isDisabledBecauseOfCondition")
    @DisplayName("Test 5: Conditionally disabled - check method")
    public void test05_conditionalDisabled() {
        assert true;
    }

    static boolean isDisabledBecauseOfCondition() {
        return true;
    }

    @Test
    @DisabledIf("isNotDisabled")
    @DisplayName("Test 6: Conditionally disabled - false condition")
    public void test06_conditionalNotDisabled() {
        assert true;
    }

    static boolean isNotDisabled() {
        return false;
    }

    @Test
    @DisplayName("Test 7: Test after mixed disabled tests")
    public void test07_afterMixedDisabled() {
        assert true;
    }

    @Test
    @Disabled
    @DisplayName("Test 8: Disabled without reason")
    public void test08_disabledNoReason() {
        assert false : "Should not run";
    }

    @Test
    @DisplayName("Test 9: Final normal test")
    public void test09_finalNormal() {
        assert true;
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("Test 10: Disabled on Windows only")
    public void test10_disabledOnWindows() {
        assert true;
    }
}
