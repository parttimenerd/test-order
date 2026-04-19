package me.bechberger.it;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StringHelperTest {
    @Test
    void testReverse() {
        assertEquals("cba", StringHelper.reverse("abc"));
    }
}
