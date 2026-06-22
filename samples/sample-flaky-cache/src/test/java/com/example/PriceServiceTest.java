package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PriceServiceTest {
    @Test
    void total() {
        assertEquals(6, new PriceService().total(2, 3));
    }
}
