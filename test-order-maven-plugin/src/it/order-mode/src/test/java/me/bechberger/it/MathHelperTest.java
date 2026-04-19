package me.bechberger.it;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MathHelperTest {
    @Test
    void testAdd() {
        assertEquals(5, MathHelper.add(2, 3));
    }
}
