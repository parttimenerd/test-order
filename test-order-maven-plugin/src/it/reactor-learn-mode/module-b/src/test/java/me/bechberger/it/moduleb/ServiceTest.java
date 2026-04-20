package me.bechberger.it.moduleb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceTest {
    @Test
    void computeWorks() {
        assertEquals(11, Service.compute());
    }
}
