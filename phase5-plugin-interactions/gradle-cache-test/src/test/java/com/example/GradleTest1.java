package com.example;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class GradleTest1 {
    @Test
    public void testCacheOne() {
        assertEquals(2, 1 + 1);
    }
    
    @Test
    public void testCacheTwo() {
        assertEquals(4, 2 + 2);
    }
}
