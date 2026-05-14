package com.example.od;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * BRITTLE test: its assertions depend on the exact value of the global counter.
 * If other tests increment the counter before this one, the assertions fail.
 * This test passes when run alone (counter starts at 0).
 */
class CounterTest {

    @Test
    void counterStartsAtZero() {
        // Only passes if no other test has called increment()
        assertEquals(0, GlobalRegistry.getInstance().getCounter(),
                "Counter should be 0 at start — fails if another test incremented it!");
    }

    @Test
    void firstIncrementReturnsOne() {
        // Only passes if counter was 0 before this call
        int val = GlobalRegistry.getInstance().increment();
        assertEquals(1, val,
                "First increment should return 1 — fails if counter was already > 0!");
    }
}
