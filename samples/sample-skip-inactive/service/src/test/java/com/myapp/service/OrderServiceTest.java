package com.myapp.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OrderServiceTest {
    @Test
    void processOrderFormatsCorrectly() {
        assertEquals("order-42=[widget]", new OrderService().processOrder("order-42", "widget"));
    }
}
