package com.myapp;

import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Exercises JUnit 6 features that interact with test-order telemetry:
 * <ul>
 *   <li>M6: {@code @RepeatedTest} with {@code failureThreshold}</li>
 *   <li>M13: {@code @TestFactory} with nested {@code DynamicContainer}</li>
 *   <li>M18: {@code @TestFactory} duration attribution</li>
 * </ul>
 */
class MathServiceAdvancedTest {

    /**
     * M6: Each repetition fires a separate MethodSource event.
     * failureThreshold=2 means after 2 failures the remaining repetitions are skipped.
     */
    @RepeatedTest(value = 5, failureThreshold = 2)
    void additionIsRepeatable() {
        assertEquals(4, new MathService().add(2, 2));
    }

    /**
     * M13/M18: DynamicContainer creates a nested hierarchy of tests.
     * TelemetryListener should handle the non-standard TestSource gracefully.
     */
    @TestFactory
    Stream<DynamicContainer> dynamicMathTests() {
        MathService math = new MathService();
        return Stream.of(
                dynamicContainer("addition", Stream.of(
                        dynamicTest("1+1=2", () -> assertEquals(2, math.add(1, 1))),
                        dynamicTest("0+0=0", () -> assertEquals(0, math.add(0, 0)))
                )),
                dynamicContainer("multiplication", Stream.of(
                        dynamicTest("3*2=6", () -> assertEquals(6, math.multiply(3, 2))),
                        dynamicTest("0*5=0", () -> assertEquals(0, math.multiply(0, 5)))
                ))
        );
    }

    @Test
    void basicMultiplication() {
        assertEquals(6, new MathService().multiply(2, 3));
    }
}
