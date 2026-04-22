package com.example.advanced;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.DisplayName;

/**
 * Testing parameterized test variant handling
 * Same test method with different providers
 */
class ParameterVariantTests {

    // First variant: 5 values
    @ParameterizedTest(name = "variant-1-{0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void testVariant1(int value) {
        assert value > 0;
    }

    // Similar test name but different provider
    @ParameterizedTest(name = "variant-2-{0}")
    @ValueSource(strings = {"a", "b", "c"})
    @DisplayName("Variant with strings")
    void testVariant2(String value) {
        assert !value.isEmpty();
    }

    // Repeated parameterization - should create distinct tests
    @ParameterizedTest
    @ValueSource(doubles = {1.1, 2.2, 3.3, 4.4, 5.5})
    void testVariant3(double value) {
        assert value > 0.0;
    }

    // Total: 5 + 3 + 5 = 13
}
