package me.bechberger.it.dy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UtilTest {
    @Test
    void exercisesShared() {
        assertEquals(-105, Util.negate(5));
    }

    @Test
    void exercisesSharedInner() {
        assertEquals(14, Util.tokenId(7));
    }
}
