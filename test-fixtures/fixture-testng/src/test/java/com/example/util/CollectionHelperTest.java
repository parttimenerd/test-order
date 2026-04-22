package com.example.util;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class CollectionHelperTest {

    @Test
    public void testSum() {
        assertEquals(CollectionHelper.sum(new int[]{1, 2, 3}), 6);
    }

    @Test
    public void testAverage() {
        assertEquals(CollectionHelper.average(new int[]{2, 4, 6}), 4.0, 0.001);
    }

    @Test
    public void testAverageEmpty() {
        assertEquals(CollectionHelper.average(new int[]{}), 0.0, 0.001);
    }

    @Test
    public void testMax() {
        assertEquals(CollectionHelper.max(new int[]{3, 7, 2}), 7);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testMaxEmpty() {
        CollectionHelper.max(new int[]{});
    }
}
