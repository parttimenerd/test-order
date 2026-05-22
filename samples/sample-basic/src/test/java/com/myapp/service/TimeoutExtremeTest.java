package com.myapp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class TimeoutExtremeTest {

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void testWithOneSecondTimeout() throws InterruptedException {
        Thread.sleep(500);
        assertEquals(1, 1);
    }

    @Test
    @Timeout(value = 100, unit = TimeUnit.MILLISECONDS)
    void testWithVeryShortTimeout() {
        assertEquals(1, 1);
    }

    @Test
    void testWithoutTimeout() {
        assertEquals(1, 1);
    }
}
