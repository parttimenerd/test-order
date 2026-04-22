package com.example.listeners;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Assertions;

/**
 * Test class with CSV-based parameterized tests.
 */
@DisplayName("Parameterized Tests - CSV Source")
public class ParameterizedCsvTest {

    @ParameterizedTest
    @CsvSource({
        "1, 2, 3",
        "10, 20, 30",
        "100, 200, 300"
    })
    @DisplayName("Test with CSV source")
    void testWithCsvValues(int a, int b, int expected) {
        Assertions.assertEquals(a + b, expected);
    }

    @Test
    @DisplayName("Regular test in CSV parameterized class")
    void regularTest() {
        Assertions.assertTrue(true);
    }
}
