package com.example.advanced;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Pattern 6: Test Templates & Repeated Tests
 * Testing @RepeatedTest with repeat counts
 */
class P6RepeatedTests {

    // Should run 5 times
    @RepeatedTest(5)
    void repeatedTest1() {
        assert true;
    }

    // Should run 10 times
    @RepeatedTest(10)
    void repeatedTest2() {
        assert 1 + 1 == 2;
    }

    // Should run 3 times
    @RepeatedTest(3)
    void repeatedTest3() {
        assert true;
    }

    // Total expected: 5 + 10 + 3 = 18 tests
}
