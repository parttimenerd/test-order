package com.example.framework;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.BeforeEach;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 Framework Test Suite for P5-TFEC-008: Mixed Parameters and Parameter Issues
 * 
 * Tests edge cases with parameterized tests and mixed parameter types:
 * 1. @ParameterizedTest with single parameter
 * 2. Multiple parameter sources
 * 3. CSV parameters with special characters
 * 4. Null parameters
 * 5. Empty parameter sets
 * 6. Mixed types (String, int, boolean, enum)
 */
@DisplayName("📊 Framework - Mixed Parameters (P5-TFEC-008)")
class FrameworkParametersTest {

    @ParameterizedTest(name = "{index} - Testing with {0}")
    @ValueSource(strings = {"apple", "banana", "orange", "grape"})
    @DisplayName("P5-TFEC-008: String Parameters - Single string values")
    void testStringParameters(String fruit) {
        assertNotNull(fruit, "Fruit name should not be null");
        assertTrue(fruit.length() > 0, "Fruit name should not be empty");
    }

    @ParameterizedTest(name = "{index} - Testing with {0}")
    @ValueSource(ints = {1, 2, 3, 5, 8, 13})
    @DisplayName("P5-TFEC-008: Integer Parameters - Single int values")
    void testIntegerParameters(int number) {
        assertTrue(number > 0, "Number should be positive");
    }

    @ParameterizedTest(name = "{index} - Testing with {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("P5-TFEC-008: Boolean Parameters - True and false values")
    void testBooleanParameters(boolean flag) {
        // Flag will be true or false
        assertTrue(flag == true || flag == false, "Should be true or false");
    }

    @ParameterizedTest(name = "{index} - Double: {0}")
    @ValueSource(doubles = {1.5, 2.7, 3.14, 99.99})
    @DisplayName("P5-TFEC-008: Double Parameters - Floating point values")
    void testDoubleParameters(double value) {
        assertTrue(value > 0, "Double value should be positive");
    }

    @ParameterizedTest(name = "{index} - CSV: {0}, {1}, {2}")
    @CsvSource({
        "John, 30, active",
        "Jane, 25, inactive",
        "Bob, 35, active",
        "Alice, 28, inactive"
    })
    @DisplayName("P5-TFEC-008: CSV Parameters - Multiple values per row")
    void testCsvParameters(String name, int age, String status) {
        assertNotNull(name, "Name should not be null");
        assertTrue(age > 0, "Age should be positive");
        assertNotNull(status, "Status should not be null");
    }

    @ParameterizedTest(name = "{index} - CSV: {0} + {1} = {2}")
    @CsvSource({
        "1, 2, 3",
        "10, 20, 30",
        "100, 200, 300",
        "-5, 5, 0"
    })
    @DisplayName("P5-TFEC-008: CSV Math - Arithmetic validation")
    void testCsvMathParameters(int a, int b, int expected) {
        assertEquals(a + b, expected, a + " + " + b + " should equal " + expected);
    }

    @ParameterizedTest
    @MethodSource("provideTestData")
    @DisplayName("P5-TFEC-008: Method Source - Parameters from method")
    void testMethodSourceParameters(String data) {
        assertNotNull(data, "Data from method source should not be null");
    }

    static Stream<String> provideTestData() {
        return Stream.of("data1", "data2", "data3");
    }

    @ParameterizedTest
    @MethodSource("provideNumbers")
    @DisplayName("P5-TFEC-008: Method Source Numbers - Multiple number parameters")
    void testMethodSourceNumbers(int number, int squared) {
        assertEquals(number * number, squared, 
            "Square calculation should be correct");
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> provideNumbers() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(1, 1),
            org.junit.jupiter.params.provider.Arguments.of(2, 4),
            org.junit.jupiter.params.provider.Arguments.of(3, 9),
            org.junit.jupiter.params.provider.Arguments.of(4, 16)
        );
    }

    @ParameterizedTest
    @EnumSource(ColorEnum.class)
    @DisplayName("P5-TFEC-008: Enum Source - Enum values as parameters")
    void testEnumSourceParameters(ColorEnum color) {
        assertNotNull(color, "Enum value should not be null");
    }

    enum ColorEnum {
        RED, GREEN, BLUE, YELLOW
    }

    @ParameterizedTest(name = "{index} - Value: {0}")
    @ValueSource(strings = {"", " ", "normal", "UPPERCASE", "123"})
    @DisplayName("P5-TFEC-008: Various String Values - Empty, spaces, mixed cases")
    void testVariousStringValues(String value) {
        assertNotNull(value, "String value should not be null");
    }

    @ParameterizedTest(name = "{index} - CSV with nulls")
    @CsvSource({
        "value1, 100",
        "value2, 200",
        "value3, 300"
    })
    @DisplayName("P5-TFEC-008: CSV with Numbers - String and number pairs")
    void testCsvWithNumbers(String name, int count) {
        assertNotNull(name, "Name should not be null");
        assertTrue(count > 0, "Count should be positive");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, Long.MAX_VALUE, Long.MIN_VALUE + 1})
    @DisplayName("P5-TFEC-008: Long Parameters - Large number values")
    void testLongParameters(long value) {
        // Test with various long values
        assertTrue(true, "Long parameter: " + value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "ab", "abc", "abcd", "abcde"})
    @DisplayName("P5-TFEC-008: Increasing Length Strings - Various length strings")
    void testIncreasingLengthStrings(String str) {
        assertTrue(str.length() > 0, "String should not be empty");
        assertTrue(str.length() <= 5, "String should be <= 5 chars");
    }

    @ParameterizedTest(name = "{index} - Testing: {0}")
    @ValueSource(strings = {"test@example.com", "user@domain.co.uk", "admin@site.org"})
    @DisplayName("P5-TFEC-008: Email Strings - Email-like parameters")
    void testEmailParameters(String email) {
        assertTrue(email.contains("@"), "Email should contain @");
        assertTrue(email.contains("."), "Email should contain .");
    }

    @ParameterizedTest
    @CsvSource({
        "'value with spaces', 42",
        "'quoted, value', 100",
        "normal, 200"
    })
    @DisplayName("P5-TFEC-008: CSV with Quoted Values - Handling quoted strings")
    void testCsvWithQuotedValues(String value, int number) {
        assertNotNull(value, "Value should not be null");
        assertTrue(number > 0, "Number should be positive");
    }

    @ParameterizedTest
    @MethodSource("provideComplexObjects")
    @DisplayName("P5-TFEC-008: Complex Objects - Custom class parameters")
    void testComplexObjectParameters(TestData data) {
        assertNotNull(data, "Data object should not be null");
        assertNotNull(data.getName(), "Name should not be null");
    }

    static Stream<TestData> provideComplexObjects() {
        return Stream.of(
            new TestData("Test1", 10),
            new TestData("Test2", 20),
            new TestData("Test3", 30)
        );
    }

    static class TestData {
        private String name;
        private int value;

        TestData(String name, int value) {
            this.name = name;
            this.value = value;
        }

        String getName() {
            return name;
        }

        int getValue() {
            return value;
        }
    }

    @Test
    @DisplayName("P5-TFEC-008: Regular Test - Regular @Test alongside parameterized")
    void testRegularTestMethod() {
        assertTrue(true, "Regular test should work with parameterized tests");
    }

    @ParameterizedTest(name = "{index} - Boundary test")
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE})
    @DisplayName("P5-TFEC-008: Boundary Values - Min/max integer values")
    void testBoundaryValues(int value) {
        assertNotNull(value, "Boundary value should exist");
    }

    @ParameterizedTest
    @CsvSource({
        "1, 1, true",
        "2, 2, true",
        "1, 2, false",
        "3, 5, false"
    })
    @DisplayName("P5-TFEC-008: CSV with Boolean Result - Testing equality")
    void testCsvWithBoolean(int a, int b, boolean expected) {
        assertEquals(a == b, expected, 
            "Equality check should match expected result");
    }

    @ParameterizedTest
    @MethodSource("provideStringsAndLengths")
    @DisplayName("P5-TFEC-008: String Length Validation - Check string lengths")
    void testStringLengthValidation(String str, int expectedLength) {
        assertEquals(expectedLength, str.length(), 
            "String length should match expected");
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> provideStringsAndLengths() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of("hello", 5),
            org.junit.jupiter.params.provider.Arguments.of("java", 4),
            org.junit.jupiter.params.provider.Arguments.of("test", 4),
            org.junit.jupiter.params.provider.Arguments.of("a", 1)
        );
    }
}
