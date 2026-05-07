package com.myapp.service;

import com.myapp.api.Order;
import com.myapp.api.OrderRepository;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.*;

public class OrderServiceTest {

    private OrderRepository repository;
    private OrderService service;

    @BeforeMethod
    public void setUp() {
        repository = new OrderRepository();
        service = new OrderService(repository);
    }

    @Test
    public void testPlaceOrder() {
        Order order = service.placeOrder("p1", "Alice", List.of("Widget", "Gadget"));
        assertEquals(order.getId(), "p1");
        assertEquals(order.getCustomer(), "Alice");
        assertEquals(order.itemCount(), 2);
    }

    @Test
    public void testPlaceOrderSavesToRepository() {
        service.placeOrder("p2", "Bob", List.of("Phone"));
        assertTrue(repository.findById("p2").isPresent());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testPlaceOrderEmptyItems() {
        service.placeOrder("p3", "Carol", List.of());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testPlaceOrderNullItems() {
        service.placeOrder("p4", "Dave", null);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testPlaceOrderBlankCustomer() {
        service.placeOrder("p5", "  ", List.of("Item"));
    }

    @Test
    public void testCalculateTotal() {
        service.placeOrder("t1", "Alice", List.of("A", "B", "C"));
        assertEquals(service.calculateTotal("t1"), 30);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testCalculateTotalNotFound() {
        service.calculateTotal("nonexistent");
    }

    @Test
    public void testGetCustomerOrders() {
        service.placeOrder("c1", "Alice", List.of("X"));
        service.placeOrder("c2", "Bob", List.of("Y"));
        service.placeOrder("c3", "Alice", List.of("Z"));

        List<Order> aliceOrders = service.getCustomerOrders("Alice");
        assertEquals(aliceOrders.size(), 2);
    }

    @Test
    public void testGetCustomerOrdersEmpty() {
        List<Order> orders = service.getCustomerOrders("Nobody");
        assertTrue(orders.isEmpty());
    }
}
