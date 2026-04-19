package com.example.shop;

/**
 * A product with name and price.
 */
public class Product {

    private final String name;
    private final double price;

    public Product(String name, double price) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (price < 0) throw new IllegalArgumentException("price must be >= 0");
        this.name = name;
        this.price = price;
    }

    public String getName() { return name; }
    public double getPrice() { return price; }

    public Product withDiscount(double percent) {
        return new Product(name, price * (1 - percent / 100));
    }

    @Override
    public String toString() {
        return name + " ($" + String.format(java.util.Locale.US, "%.2f", price) + ")";
    }
}
