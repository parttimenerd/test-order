package com.myapp.api;

import java.util.*;

/**
 * In-memory order repository.
 */
public class OrderRepository {

    private final Map<String, Order> orders = new LinkedHashMap<>();

    public void save(Order order) {
        orders.put(order.getId(), order);
    }

    public Optional<Order> findById(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    public List<Order> findByCustomer(String customer) {
        List<Order> result = new ArrayList<>();
        for (Order o : orders.values()) {
            if (o.getCustomer().equals(customer)) {
                result.add(o);
            }
        }
        return result;
    }

    public int count() {
        return orders.size();
    }

    public void clear() {
        orders.clear();
    }
}
