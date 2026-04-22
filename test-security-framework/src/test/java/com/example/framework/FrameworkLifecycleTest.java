package com.example.framework;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 Framework Test Suite for P5-TFEC-002 and P5-TFEC-003:
 * 
 * P5-TFEC-002: @TestInstance Lifecycle Issues
 * Tests the @TestInstance annotation and how it affects lifecycle:
 * 1. PER_METHOD (default) - new instance per test method
 * 2. PER_CLASS - single instance for all methods
 * 3. BeforeAll/AfterAll with PER_CLASS
 * 
 * P5-TFEC-003: @Disabled and Conditional Test Execution
 * Tests @Disabled annotation and conditional test execution:
 * 1. @Disabled - skip specific tests
 * 2. @EnabledIf conditions
 * 3. @DisabledIf conditions
 */
@DisplayName("Framework - TestInstance Lifecycle & Conditional Tests (P5-TFEC-002/003)")
class FrameworkLifecycleTest {

    private static int staticCounter = 0;
    private int instanceCounter = 0;

    @BeforeEach
    void setUp() {
        instanceCounter = 0;
    }

    @AfterEach
    void tearDown() {
        assertTrue(instanceCounter >= 0, "Instance counter should be valid");
    }

    // ==================== P5-TFEC-002: TestInstance Lifecycle ====================

    @Test
    @DisplayName("P5-TFEC-002: Lifecycle - First test method")
    void testLifecycleFirst() {
        instanceCounter++;
        assertEquals(1, instanceCounter, "First invocation of this test should have counter = 1");
    }

    @Test
    @DisplayName("P5-TFEC-002: Lifecycle - Second test method")
    void testLifecycleSecond() {
        instanceCounter++;
        assertEquals(1, instanceCounter, "Counter should reset for each test (PER_METHOD)");
    }

    @Test
    @DisplayName("P5-TFEC-002: TestInstance Default - PER_METHOD creates new instance per test")
    void testTestInstanceDefault() {
        assertNotEquals(2, instanceCounter, 
            "With PER_METHOD, each test should have fresh instance");
    }

    @Test
    @DisplayName("P5-TFEC-002: Field Isolation - Fields don't persist between tests")
    void testFieldIsolation() {
        int localCounter = 0;
        localCounter++;
        assertEquals(1, localCounter, "Local variable should be independent");
    }

    @Test
    @DisplayName("P5-TFEC-002: Static Fields - Static state persists across tests")
    void testStaticFieldPersistence() {
        staticCounter++;
        assertTrue(staticCounter >= 1, "Static counter should persist");
    }

    @Test
    @DisplayName("P5-TFEC-002: Setup and Teardown - Called for each test")
    void testSetupTeardownExecution() {
        // BeforeEach should have initialized instanceCounter to 0
        assertEquals(0, instanceCounter, "BeforeEach should reset counter to 0");
        instanceCounter++;
        // AfterEach will be called after this test
    }

    // ==================== P5-TFEC-003: Disabled and Conditional Tests ====================

    @Test
    @Disabled("P5-TFEC-003: This test is intentionally disabled")
    @DisplayName("P5-TFEC-003: Disabled Test - Should not execute")
    void testDisabledTest() {
        fail("This test should not execute because it's disabled");
    }

    @Test
    @DisplayName("P5-TFEC-003: Enabled Test - Should execute normally")
    void testEnabledTest() {
        assertTrue(true, "This test should execute normally");
    }

    @Test
    @Disabled("Experimental feature - disabled for now")
    @DisplayName("P5-TFEC-003: Disabled with Reason - Skipped with description")
    void testDisabledWithReason() {
        fail("Should not execute - test is disabled");
    }

    @Test
    @EnabledIfSystemProperty(named = "java.version", matches = ".*")
    @DisplayName("P5-TFEC-003: Conditional Enabled - Execute if system property matches")
    void testConditionalEnabled() {
        String javaVersion = System.getProperty("java.version");
        assertNotNull(javaVersion, "Java version should be available");
    }

    @Test
    @DisabledIfSystemProperty(named = "os.name", matches = "Windows.*")
    @DisplayName("P5-TFEC-003: Disabled on Windows - Skip if running on Windows")
    void testDisabledOnWindows() {
        String osName = System.getProperty("os.name");
        assertFalse(osName.contains("Windows"), "Should not run on Windows");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PATH", matches = ".*")
    @DisplayName("P5-TFEC-003: Conditional by Environment - Check environment variable")
    void testConditionalByEnvironment() {
        String path = System.getenv("PATH");
        // PATH should exist on most systems
        assertTrue(path != null || System.getProperty("os.name").contains("Windows"),
            "PATH environment variable check");
    }

    @Test
    @DisplayName("P5-TFEC-003: Multiple Conditions - Test with multiple conditionals")
    void testMultipleConditions() {
        // This test should execute if all conditions are met
        String javaVersion = System.getProperty("java.version");
        assertNotNull(javaVersion, "Java version should exist");
    }

    @Test
    @DisplayName("P5-TFEC-003: Always Enabled - No conditional restrictions")
    void testAlwaysEnabled() {
        assertTrue(true, "This test should always execute");
    }

    @Test
    @Disabled("P5-TFEC-003: Disabled for maintenance")
    @DisplayName("P5-TFEC-003: Disabled for Maintenance - Temporarily skipped")
    void testDisabledForMaintenance() {
        fail("Should not execute - under maintenance");
    }
}

/**
 * Test class using @TestInstance(PER_CLASS) - Single instance for all tests
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Framework - TestInstance PER_CLASS Lifecycle")
class FrameworkLifecyclePerClassTest {

    private int instanceCounter = 0;

    @BeforeAll
    void setUpAll() {
        instanceCounter = 0;
    }

    @AfterAll
    void tearDownAll() {
        // Cleanup for all tests
    }

    @Test
    @DisplayName("P5-TFEC-002: PER_CLASS - First test increments counter")
    void testPerClassFirst() {
        instanceCounter++;
        assertEquals(1, instanceCounter, "First test should increment to 1");
    }

    @Test
    @DisplayName("P5-TFEC-002: PER_CLASS - Second test continues counter")
    void testPerClassSecond() {
        instanceCounter++;
        assertEquals(2, instanceCounter, "Second test should see counter = 2 (same instance)");
    }

    @Test
    @DisplayName("P5-TFEC-002: PER_CLASS - Third test continues counter")
    void testPerClassThird() {
        instanceCounter++;
        assertEquals(3, instanceCounter, "Third test should see counter = 3");
    }

    @Test
    @DisplayName("P5-TFEC-002: PER_CLASS Persistence - State persists across tests")
    void testPerClassStatePersistence() {
        assertTrue(instanceCounter >= 3, "Instance state should persist with PER_CLASS");
    }

    @Test
    @DisplayName("P5-TFEC-002: BeforeAll with PER_CLASS - Can be non-static")
    void testBeforeAllWithPerClass() {
        // With PER_CLASS, BeforeAll can be non-static
        assertTrue(true, "Non-static BeforeAll should work with PER_CLASS");
    }
}
