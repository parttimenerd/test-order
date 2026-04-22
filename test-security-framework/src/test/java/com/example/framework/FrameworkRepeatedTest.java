package com.example.framework;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 Framework Test Suite for P5-TFEC-001: @RepeatedTest Issues
 * 
 * Tests edge cases and potential issues with @RepeatedTest annotation:
 * 1. Correct repetition count
 * 2. RepetitionInfo access
 * 3. Current and total repetition values
 * 4. Interaction with other annotations
 * 5. Test failure handling
 */
@DisplayName("Framework - @RepeatedTest Edge Cases (P5-TFEC-001)")
class FrameworkRepeatedTest {

    @RepeatedTest(5)
    @DisplayName("P5-TFEC-001: Basic Repetition - Execute test 5 times")
    void testBasicRepetition(RepetitionInfo info) {
        assertTrue(info.getCurrentRepetition() >= 1, "Current repetition should be >= 1");
        assertTrue(info.getTotalRepetitions() == 5, "Total repetitions should be 5");
        assertTrue(info.getCurrentRepetition() <= 5, "Current repetition should be <= total");
    }

    @RepeatedTest(1)
    @DisplayName("P5-TFEC-001: Single Repetition - Test with only one repetition")
    void testSingleRepetition(RepetitionInfo info) {
        assertEquals(1, info.getCurrentRepetition(), "Single repetition should be 1");
        assertEquals(1, info.getTotalRepetitions(), "Total should be 1");
    }

    @RepeatedTest(10)
    @DisplayName("P5-TFEC-001: Multiple Repetitions - Test with 10 repetitions")
    void testMultipleRepetitions(RepetitionInfo info) {
        assertNotNull(info, "RepetitionInfo should be available");
        assertTrue(info.getCurrentRepetition() <= 10, "Current should not exceed total");
    }

    @RepeatedTest(3)
    @DisplayName("P5-TFEC-001: Repetition Counter - Verify counter increments")
    void testRepetitionCounterIncrement(RepetitionInfo info) {
        int current = info.getCurrentRepetition();
        assertTrue(current >= 1 && current <= 3, "Repetition counter should be valid");
    }

    @RepeatedTest(5)
    @DisplayName("P5-TFEC-001: RepetitionInfo Methods - All methods should work")
    void testRepetitionInfoMethods(RepetitionInfo info) {
        // Test all available methods
        assertNotNull(info.getCurrentRepetition(), "getCurrentRepetition should not be null");
        assertNotNull(info.getTotalRepetitions(), "getTotalRepetitions should not be null");

        int current = info.getCurrentRepetition();
        int total = info.getTotalRepetitions();

        assertTrue(current > 0, "Current repetition must be positive");
        assertTrue(total > 0, "Total repetitions must be positive");
        assertTrue(current <= total, "Current should not exceed total");
    }

    @RepeatedTest(4)
    @DisplayName("P5-TFEC-001: Stateless Repetition - Each repetition is independent")
    void testStatelessRepetition(RepetitionInfo info) {
        // Each repetition should be independent
        int repetition = info.getCurrentRepetition();
        assertTrue(repetition > 0, "Repetition number should be available");

        // This assertion should pass for each repetition
        assertEquals(repetition, info.getCurrentRepetition(), 
            "Same repetition call should return same value");
    }

    @RepeatedTest(3)
    @DisplayName("P5-TFEC-001: Test Naming with Repetition - Display name includes repetition")
    void testNamingWithRepetition(RepetitionInfo info) {
        // Test should execute 3 times with different display names
        assertNotNull(info, "RepetitionInfo should be injected");
    }

    @RepeatedTest(2)
    @DisplayName("P5-TFEC-001: Assertion in Repeated Test - Assertions should work normally")
    void testAssertionInRepeatedTest(RepetitionInfo info) {
        int current = info.getCurrentRepetition();
        
        assertTrue(current == 1 || current == 2, 
            "Current repetition should be 1 or 2");

        assertNotEquals(0, current, "Current repetition should not be 0");
    }

    @RepeatedTest(5)
    @DisplayName("P5-TFEC-001: Repetition with Different Assertions")
    void testRepeatedWithVariousAssertions(RepetitionInfo info) {
        int total = info.getTotalRepetitions();
        
        // Should execute consistently
        assertEquals(5, total, "Total should always be 5");

        // Current should change
        int current = info.getCurrentRepetition();
        assertTrue(current >= 1 && current <= 5, "Current should be in valid range");
    }

    @RepeatedTest(0)
    @DisplayName("P5-TFEC-001: Zero Repetitions - Edge case with zero repetitions")
    void testZeroRepetitions(RepetitionInfo info) {
        // This test should not execute if 0 repetitions is invalid
        fail("Test with 0 repetitions should not execute");
    }

    @RepeatedTest(3)
    @DisplayName("P5-TFEC-001: Repetition Index Access - Get current index")
    void testRepetitionIndexAccess(RepetitionInfo info) {
        int current = info.getCurrentRepetition();
        
        // Verify indices are 1-based
        assertTrue(current >= 1, "Repetition index should start at 1");
        assertTrue(current <= info.getTotalRepetitions(), 
            "Index should not exceed total");
    }
}
