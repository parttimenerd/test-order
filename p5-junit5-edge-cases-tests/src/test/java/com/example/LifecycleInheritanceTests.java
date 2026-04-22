package com.example;

import org.junit.jupiter.api.*;

/**
 * Tests for @BeforeAll and @AfterAll with inheritance.
 * Tests edge cases in lifecycle management across inheritance hierarchies.
 */
@DisplayName("Lifecycle Inheritance Tests")
class LifecycleInheritanceBaseTests {

    protected static int staticCounter = 0;
    protected int instanceCounter = 0;

    @BeforeAll
    static void beforeAllBase() {
        staticCounter = 100;
    }

    @BeforeEach
    void beforeEachBase() {
        instanceCounter = 10;
    }

    @Test
    @DisplayName("Base Test 1")
    public void baseTest01() {
        assert staticCounter == 100;
        assert instanceCounter == 10;
    }

    @Test
    @DisplayName("Base Test 2")
    public void baseTest02() {
        assert staticCounter == 100;
        assert instanceCounter == 10;
    }

    @AfterEach
    void afterEachBase() {
        assert instanceCounter >= 10;
    }

    @AfterAll
    static void afterAllBase() {
        staticCounter = 0;
    }
}

@DisplayName("Inherited Lifecycle Tests")
class LifecycleInheritanceChildTests extends LifecycleInheritanceBaseTests {

    @BeforeAll
    static void beforeAllChild() {
        // This runs after parent BeforeAll
        staticCounter = 200;
    }

    @BeforeEach
    void beforeEachChild() {
        // This runs after parent BeforeEach
        super.beforeEachBase();
        instanceCounter = 20;
    }

    @Test
    @DisplayName("Child Test 1")
    public void childTest01() {
        assert staticCounter == 200;
        assert instanceCounter == 20;
    }

    @Test
    @DisplayName("Child Test 2")
    public void childTest02() {
        assert staticCounter == 200;
        assert instanceCounter == 20;
    }

    @AfterEach
    void afterEachChild() {
        assert instanceCounter >= 20;
    }

    @AfterAll
    static void afterAllChild() {
        staticCounter = 0;
    }
}

@DisplayName("Deep Inheritance Lifecycle Tests")
class LifecycleInheritanceGrandchildTests extends LifecycleInheritanceChildTests {

    @BeforeAll
    static void beforeAllGrandchild() {
        staticCounter = 300;
    }

    @BeforeEach
    void beforeEachGrandchild() {
        super.beforeEachChild();
        instanceCounter = 30;
    }

    @Test
    @DisplayName("Grandchild Test 1")
    public void grandchildTest01() {
        assert staticCounter == 300;
        assert instanceCounter == 30;
    }

    @Test
    @DisplayName("Grandchild Test 2")
    public void grandchildTest02() {
        assert staticCounter == 300;
        assert instanceCounter == 30;
    }

    @AfterEach
    void afterEachGrandchild() {
        assert instanceCounter >= 30;
    }

    @AfterAll
    static void afterAllGrandchild() {
        staticCounter = 0;
    }
}
