package com.example.service.repo;

import com.example.service.model.Order;
import com.example.service.model.User;

import java.util.*;
import java.util.stream.Collectors;

/**
 * In-memory order repository.
 */
public class OrderRepository {

    private final Map<Long, Order> orders = new LinkedHashMap<>();

    public void save(Order order) {
        orders.put(order.getId(), order);
    }

    public Optional<Order> findById(long id) {
        return Optional.ofNullable(orders.get(id));
    }

    public List<Order> findByUser(User user) {
        return orders.values().stream()
                .filter(o -> o.getUser().getEmail().equals(user.getEmail()))
                .collect(Collectors.toList());
    }

    public List<Order> findAll() {
        return new ArrayList<>(orders.values());
    }

    public int count() {
        return orders.size();
    }
}
