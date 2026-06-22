package com.myapp.service;

import com.myapp.lib.MessageBuilder;

public class OrderService {
    public String processOrder(String id, String item) {
        return MessageBuilder.build(id, item);
    }
}
// test-change
