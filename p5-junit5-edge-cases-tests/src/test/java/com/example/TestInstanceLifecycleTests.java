package com.example;

import org.junit.jupiter.api.*;

/**
 * Tests for TestInstance lifecycle - PER_CLASS vs PER_METHOD.
 * Tests edge cases with shared state and static initialization.
 */
@DisplayName("Test Instance Lifecycle Tests")
class TestInstanceLifecyclePerMethod {

    private int instanceValue = 0;

    @BeforeEach
    public void setUp() {
        instanceValue = 0;
    }

    @Test
    @DisplayName("Test 1: Basic instance test")
    public void test01_basicInstance() {
        instanceValue++;
        assert instanceValue == 1;
    }

    @Test
    @DisplayName("Test 2: Another instance test")
    public void test02_anotherInstance() {
        // Should start with fresh instanceValue = 0
        instanceValue++;
        assert instanceValue == 1;
    }

    @Test
    @DisplayName("Test 3: Multiple increments")
    public void test03_multipleIncrements() {
        instanceValue++;
        instanceValue++;
        assert instanceValue == 2;
    }
}

@DisplayName("Test Instance Lifecycle - PER_CLASS")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestInstanceLifecyclePerClass {

    private int instanceValue = 0;

    @BeforeAll
    public void setUpAll() {
        instanceValue = 100;
    }

    @BeforeEach
    public void setUp() {
        // This will run before each test but instance is shared
        instanceValue += 10;
    }

    @Test
    @DisplayName("Test 1: First test with shared instance")
    public void test01_first() {
        // instanceValue should be 110 after BeforeEach
        assert instanceValue >= 110;
        instanceValue++;
    }

    @Test
    @DisplayName("Test 2: Second test with shared instance")
    public void test02_second() {
        // instanceValue carries over from previous test
        assert instanceValue > 110;
        instanceValue++;
    }

    @Test
    @DisplayName("Test 3: Third test with shared instance")
    public void test03_third() {
        // instanceValue continues to increase
        assert instanceValue > 120;
        instanceValue++;
    }

    @AfterAll
    public void tearDownAll() {
        // All tests have run with same instance
        assert instanceValue > 130;
    }
}
