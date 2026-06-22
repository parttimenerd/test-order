package com.myapp.web;

import com.myapp.service.OrderService;

public class OrderController {
    private final OrderService svc = new OrderService();

    public String handle(String id, String item) {
        return "OK: " + svc.processOrder(id, item);
    }
}
