package com.myapp.service;

import com.myapp.api.Order;
import com.myapp.api.OrderRepository;

import java.util.List;

/**
 * Order processing service that validates and fulfills orders.
 */
public class OrderService {

    private final OrderRepository repository;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    /**
     * Place a new order. Validates that the order has at least one item.
     */
    public Order placeOrder(String id, String customer, List<String> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one item");
        }
        if (customer == null || customer.isBlank()) {
            throw new IllegalArgumentException("Customer name is required");
        }
        Order order = new Order(id, customer, items);
        repository.save(order);
        return order;
    }

    /**
     * Calculate a simple total — each item costs $10.
     */
    public int calculateTotal(String orderId) {
        return repository.findById(orderId)
                .map(o -> o.itemCount() * 10)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    /**
     * Returns all orders for a given customer.
     */
    public List<Order> getCustomerOrders(String customer) {
        return repository.findByCustomer(customer);
    }
}
