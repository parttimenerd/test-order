package com.myapp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class SlowTests {
    @Test
    @Timeout(value = 500, unit = TimeUnit.MILLISECONDS)
    void slowTest1() throws InterruptedException {
        Thread.sleep(100);
        assertTrue(true);
    }

    @Test
    @Timeout(value = 500, unit = TimeUnit.MILLISECONDS)
    void slowTest2() throws InterruptedException {
        Thread.sleep(200);
        assertTrue(true);
    }

    @Test
    void fastTest() {
        assertTrue(true);
    }
}
