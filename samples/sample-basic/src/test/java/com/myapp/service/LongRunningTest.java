package com.myapp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class LongRunningTest {

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void longRunning() throws InterruptedException {
        Thread.sleep(5000);
    }

    @Test
    void normalTest() {
        assertEquals(1, 1);
    }
}
