package com.example.test;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class FastTest {
    @Test
    public void testFast1() {
        assertThat(true).isTrue();
    }

    @Test
    public void testFast2() {
        assertThat(1).isEqualTo(1);
    }

    @Test
    public void testFast3() {
        assertThat("test").isNotEmpty();
    }
}
