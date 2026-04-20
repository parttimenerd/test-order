package com.example.test;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class SlowTest {
    @Test
    public void testSlow1() throws InterruptedException {
        Thread.sleep(1000);
        assertThat(true).isTrue();
    }

    @Test
    public void testSlow2() throws InterruptedException {
        Thread.sleep(800);
        assertThat(1).isEqualTo(1);
    }

    @Test
    public void testSlow3() throws InterruptedException {
        Thread.sleep(900);
        assertThat("test").isNotEmpty();
    }
}
