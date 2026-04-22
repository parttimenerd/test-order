package com.example;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class APTTest {
    @Test
    public void testLombokGeneration() {
        User user = new User("John", 25);
        assertEquals("John", user.getName());
        assertEquals(25, user.getAge());
    }
}
