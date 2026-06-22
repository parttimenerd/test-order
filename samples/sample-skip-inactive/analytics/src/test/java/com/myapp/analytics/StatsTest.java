package com.myapp.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class StatsTest {
    @Test
    void meanOfThreeValues() {
        assertEquals(2.0, Stats.mean(new double[]{1, 2, 3}), 0.001);
    }
}
