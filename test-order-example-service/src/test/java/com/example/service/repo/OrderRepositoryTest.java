package com.example.service.repo;

import com.example.service.model.Order;
import com.example.service.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderRepositoryTest {

    private OrderRepository repo;
    private User alice;

    @BeforeEach
    void setUp() {
        repo = new OrderRepository();
        alice = new User("Alice", "alice@example.com");
    }

    @Test
    void saveAndFindById() {
        Order order = new Order(1L, alice, "Widget", 2);
        repo.save(order);
        assertTrue(repo.findById(1L).isPresent());
        assertEquals("Widget", repo.findById(1L).get().getProduct());
    }

    @Test
    void findByIdNotFound() {
        assertTrue(repo.findById(999L).isEmpty());
    }

    @Test
    void findByUser() {
        repo.save(new Order(1L, alice, "Widget", 1));
        repo.save(new Order(2L, alice, "Gadget", 3));
        User bob = new User("Bob", "bob@example.com");
        repo.save(new Order(3L, bob, "Thingamajig", 1));

        List<Order> aliceOrders = repo.findByUser(alice);
        assertEquals(2, aliceOrders.size());
    }

    @Test
    void findAll() {
        repo.save(new Order(1L, alice, "A", 1));
        repo.save(new Order(2L, alice, "B", 2));
        assertEquals(2, repo.findAll().size());
    }

    @Test
    void count() {
        assertEquals(0, repo.count());
        repo.save(new Order(1L, alice, "Widget", 1));
        assertEquals(1, repo.count());
    }
}
