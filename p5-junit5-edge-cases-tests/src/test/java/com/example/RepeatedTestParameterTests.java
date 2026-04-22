package com.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

/**
 * Tests for @RepeatedTest with parameter injection.
 * Tests all combinations of RepetitionInfo and parameter providers.
 */
@DisplayName("Repeated Tests with Parameter Injection")
public class RepeatedTestParameterTests {

    @RepeatedTest(3)
    @DisplayName("Repeated Test 1: Basic repetition")
    public void test01_basicRepetition(RepetitionInfo repetitionInfo) {
        assert repetitionInfo.getCurrentRepetition() > 0;
        assert repetitionInfo.getCurrentRepetition() <= 3;
    }

    @RepeatedTest(2)
    @DisplayName("Repeated Test 2: Access current repetition")
    public void test02_currentRepetition(RepetitionInfo info) {
        System.out.println("Repetition: " + info.getCurrentRepetition() + "/" + info.getTotalRepetitions());
        assert info.getCurrentRepetition() >= 1;
    }

    @RepeatedTest(4)
    @DisplayName("Repeated Test 3: Repetition with assertion")
    public void test03_repetitionAssertion(RepetitionInfo info) {
        int current = info.getCurrentRepetition();
        int total = info.getTotalRepetitions();
        assert current >= 1 && current <= total;
    }

    @ParameterizedTest
    @RepeatedTest(2)
    @ValueSource(ints = {1, 2, 3})
    @DisplayName("Repeated Parameterized 1: Values with repetition")
    public void test04_parameterizedRepeated(int value, RepetitionInfo info) {
        assert value > 0 && value <= 3;
        assert info.getCurrentRepetition() > 0;
    }

    @ParameterizedTest
    @RepeatedTest(2)
    @CsvSource({"1,a", "2,b", "3,c"})
    @DisplayName("Repeated Parameterized 2: CSV with repetition")
    public void test05_csvRepeated(int num, String letter, RepetitionInfo info) {
        assert num > 0;
        assert letter.length() == 1;
        assert info.getCurrentRepetition() > 0;
    }

    @RepeatedTest(5)
    @DisplayName("Repeated Test 4: Multiple assertions")
    public void test06_multipleAssertions(RepetitionInfo info) {
        int rep = info.getCurrentRepetition();
        assert rep > 0 : "Repetition must be positive";
        assert rep <= 5 : "Repetition must not exceed 5";
        assert info.getTotalRepetitions() == 5 : "Total must be 5";
    }

    @ParameterizedTest(name = "Repetition {0} of {1}")
    @RepeatedTest(2)
    @MethodSource("provideTestValues")
    @DisplayName("Repeated Parameterized 3: Method source with repetition")
    public void test07_methodSourceRepeated(String value, RepetitionInfo info) {
        assert value != null;
        assert info.getCurrentRepetition() > 0;
    }

    static Stream<String> provideTestValues() {
        return Stream.of("value1", "value2", "value3");
    }

    @RepeatedTest(1)
    @DisplayName("Repeated Test 5: Single repetition")
    public void test08_singleRepetition(RepetitionInfo info) {
        assert info.getCurrentRepetition() == 1;
        assert info.getTotalRepetitions() == 1;
    }

    @RepeatedTest(3)
    @DisplayName("Repeated Test 6: Using repetition in logic")
    public void test09_repetitionInLogic(RepetitionInfo info) {
        int rep = info.getCurrentRepetition();
        switch (rep) {
            case 1:
            case 2:
            case 3:
                assert true;
                break;
            default:
                assert false : "Invalid repetition";
        }
    }
}
