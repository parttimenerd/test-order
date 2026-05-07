package com.myapp.api;

import java.util.List;

/**
 * Simple order model.
 */
public class Order {

    private final String id;
    private final String customer;
    private final List<String> items;

    public Order(String id, String customer, List<String> items) {
        this.id = id;
        this.customer = customer;
        this.items = List.copyOf(items);
    }

    public String getId() {
        return id;
    }

    public String getCustomer() {
        return customer;
    }

    public List<String> getItems() {
        return items;
    }

    public int itemCount() {
        return items.size() /* touched */;
    }

    public boolean containsItem(String item) {
        return items.contains(item);
    }
}
// touched
