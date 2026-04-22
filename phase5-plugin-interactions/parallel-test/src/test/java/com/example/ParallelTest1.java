package com.example;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class ParallelTest1 {
    @Test
    public void testParallel1() {
        System.out.println("Running ParallelTest1.testParallel1 on " + Thread.currentThread().getName());
        assertTrue(true);
    }
    
    @Test
    public void testParallel2() {
        System.out.println("Running ParallelTest1.testParallel2 on " + Thread.currentThread().getName());
        assertTrue(true);
    }
}
