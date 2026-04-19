package com.myapp.service;

import com.myapp.model.Product;
import java.util.ArrayList;
import java.util.List;

public class OrderService {
    private final List<Product> cart = new ArrayList<>();

    public void addToCart(Product product) {
        cart.add(product);
    }

    public double getTotal() {
        return cart.stream().mapToDouble(Product::getPrice).sum();
    }

    public double getTotalWithDiscount(double discountPercent) {
        return cart.stream()
                .mapToDouble(p -> p.applyDiscount(discountPercent))
                .sum();
    }

    public int getCartSize() {
        return cart.size();
    }

    public void clearCart() {
        cart.clear();
    }
}
