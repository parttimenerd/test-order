package com.myapp.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderRepositoryTest {

    private OrderRepository repo;

    @BeforeEach
    void setUp() {
        repo = new OrderRepository();
    }

    @Test
    void saveAndFind() {
        Order order = new Order("r1", "Alice", List.of("Item1"));
        repo.save(order);
        assertTrue(repo.findById("r1").isPresent());
        assertEquals("Alice", repo.findById("r1").get().getCustomer());
    }

    @Test
    void findByCustomer() {
        repo.save(new Order("a1", "Alice", List.of("X")));
        repo.save(new Order("b1", "Bob", List.of("Y")));
        repo.save(new Order("a2", "Alice", List.of("Z")));

        List<Order> aliceOrders = repo.findByCustomer("Alice");
        assertEquals(2, aliceOrders.size());
    }

    @Test
    void countAndClear() {
        repo.save(new Order("c1", "Carol", List.of("A")));
        repo.save(new Order("c2", "Carol", List.of("B")));
        assertEquals(2, repo.count());
        repo.clear();
        assertEquals(0, repo.count());
    }

    @Test
    void findByIdNotFound() {
        assertTrue(repo.findById("nonexistent").isEmpty());
    }
}
