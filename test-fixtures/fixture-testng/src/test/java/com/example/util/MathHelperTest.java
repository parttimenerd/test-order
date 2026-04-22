package com.example.util;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class MathHelperTest {

    @Test
    public void testFactorial() {
        assertEquals(MathHelper.factorial(5), 120);
    }

    @Test
    public void testFactorialZero() {
        assertEquals(MathHelper.factorial(0), 1);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testFactorialNegative() {
        MathHelper.factorial(-1);
    }

    @Test
    public void testIsPrime() {
        assertTrue(MathHelper.isPrime(7));
        assertFalse(MathHelper.isPrime(4));
        assertFalse(MathHelper.isPrime(1));
    }

    @Test
    public void testGcd() {
        assertEquals(MathHelper.gcd(12, 8), 4);
        assertEquals(MathHelper.gcd(17, 13), 1);
    }
}
