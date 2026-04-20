package com.example.math;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

public class CalculatorParameterizedTest {

    @ParameterizedTest
    @CsvSource({
            "1, 1, 2",
            "2, 3, 5",
            "10, 20, 30",
            "-1, 1, 0",
            "0, 0, 0"
    })
    public void testAddWithCsvSource(int a, int b, int expected) {
        assertEquals(expected, Calculator.add(a, b));
    }

    @ParameterizedTest
    @CsvSource({
            "10, 3, 7",
            "5, 5, 0",
            "20, 8, 12"
    })
    public void testSubtractWithCsvSource(int a, int b, int expected) {
        assertEquals(expected, Calculator.subtract(a, b));
    }

    @ParameterizedTest
    @CsvSource({
            "2, 3, 6",
            "4, 5, 20",
            "0, 10, 0"
    })
    public void testMultiplyWithCsvSource(int a, int b, int expected) {
        assertEquals(expected, Calculator.multiply(a, b));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 5, 7, 11, 13, 17, 19})
    public void testIsPrimeWithTrueCases(int n) {
        assertTrue(Calculator.isPrime(n));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 6, 8, 9, 10, 12})
    public void testIsPrimeWithFalseCases(int n) {
        assertFalse(Calculator.isPrime(n));
    }
}
