package com.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import java.util.stream.Stream;

/**
 * Tests for @ParameterizedTest with all provider types.
 * Tests edge cases in handling various parameter sources.
 */
@DisplayName("Parameterized Tests with All Provider Types")
public class ParameterizedAllProvidersTests {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("Parameterized 1: Value source - integers")
    public void test01_valueSourceInts(int value) {
        assert value > 0 && value <= 5;
    }

    @ParameterizedTest
    @ValueSource(strings = {"alpha", "beta", "gamma"})
    @DisplayName("Parameterized 2: Value source - strings")
    public void test02_valueSourceStrings(String value) {
        assert value != null && !value.isEmpty();
    }

    @ParameterizedTest
    @ValueSource(doubles = {1.1, 2.2, 3.3})
    @DisplayName("Parameterized 3: Value source - doubles")
    public void test03_valueSourceDoubles(double value) {
        assert value > 0;
    }

    @ParameterizedTest
    @CsvSource({
            "1, one, 1.0",
            "2, two, 2.0",
            "3, three, 3.0"
    })
    @DisplayName("Parameterized 4: CSV source - mixed types")
    public void test04_csvSourceMixed(int num, String word, double value) {
        assert num > 0;
        assert word != null;
        assert value > 0;
    }

    @ParameterizedTest
    @CsvSource({
            "'hello world', 5",
            "'test case', 4",
            "'junit5 params', 6"
    })
    @DisplayName("Parameterized 5: CSV source with quoted values")
    public void test05_csvSourceQuoted(String text, int length) {
        assert text != null;
        assert text.length() > 0;
    }

    @ParameterizedTest
    @CsvSource(value = {
            "A|1|true",
            "B|2|false",
            "C|3|true"
    }, delimiter = '|')
    @DisplayName("Parameterized 6: CSV source with custom delimiter")
    public void test06_csvCustomDelimiter(String letter, int num, String bool) {
        assert letter != null;
        assert num > 0;
    }

    @ParameterizedTest
    @MethodSource("provideArguments")
    @DisplayName("Parameterized 7: Method source provider")
    public void test07_methodSource(String arg) {
        assert arg != null;
    }

    static Stream<String> provideArguments() {
        return Stream.of("arg1", "arg2", "arg3");
    }

    @ParameterizedTest
    @MethodSource("provideComplexArguments")
    @DisplayName("Parameterized 8: Method source with complex objects")
    public void test08_methodSourceComplex(TestData data) {
        assert data != null;
        assert data.getName() != null;
    }

    static Stream<TestData> provideComplexArguments() {
        return Stream.of(
                new TestData("data1", 10),
                new TestData("data2", 20),
                new TestData("data3", 30)
        );
    }

    @ParameterizedTest
    @ArgumentsSource(CustomArgumentProvider.class)
    @DisplayName("Parameterized 9: Custom argument provider")
    public void test09_customArgumentProvider(String argument) {
        assert argument != null;
    }

    static class CustomArgumentProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(
                    Arguments.of("custom1"),
                    Arguments.of("custom2"),
                    Arguments.of("custom3")
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "ab", "abc"})
    @DisplayName("Parameterized 10: Empty and null edge cases")
    public void test10_edgeCases(String value) {
        assert value != null;
    }

    @ParameterizedTest
    @CsvSource({
            "null",
            "test",
            "another"
    })
    @DisplayName("Parameterized 11: CSV with null handling")
    public void test11_csvNullHandling(String value) {
        // Value can be "null" as string
        assert value != null;
    }

    @ParameterizedTest
    @MethodSource("provideNumbers")
    @DisplayName("Parameterized 12: Method source numbers")
    public void test12_methodSourceNumbers(int num) {
        assert num >= 0;
    }

    static Stream<Integer> provideNumbers() {
        return Stream.of(0, 1, 10, 100, 1000);
    }

    // Test data class for complex test cases
    static class TestData {
        private final String name;
        private final int value;

        TestData(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public int getValue() {
            return value;
        }
    }
}
