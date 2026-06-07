package me.bechberger.it.dx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UtilTest {
    @Test
    void exercisesShared() {
        assertEquals(105, Util.wrap(5));
    }

    @Test
    void exercisesSharedInner() {
        assertEquals(42, Util.tokenId(42));
    }
}
