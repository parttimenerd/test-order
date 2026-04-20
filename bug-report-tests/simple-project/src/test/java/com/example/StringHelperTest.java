package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class StringHelperTest {
    @Test
    public void testReverse() {
        assertEquals("cba", new StringHelper().reverse("abc"));
    }
}
