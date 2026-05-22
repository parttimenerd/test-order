package com.myapp.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

public class ParamTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 8})
    void testValidFibonacci(int num) {
        assertTrue(num > 0);
    }

    @ParameterizedTest
    @CsvSource({
        "1, 1",
        "2, 2",
        "3, 6",
        "4, 24"
    })
    void testFactorial(int n, int expected) {
        int result = factorial(n);
        assertEquals(expected, result);
    }

    private int factorial(int n) {
        if (n <= 1) return 1;
        return n * factorial(n - 1);
    }
}
