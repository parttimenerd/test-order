package com.example.shop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shopping cart that holds products. Depends on {@link Product}.
 */
public class Cart {

	private final List<Product> items = new ArrayList<>();

	public void add(Product product) {
		items.add(product);
	}

	public void remove(Product product) {
		items.remove(product);
	}

	public List<Product> getItems() {
		return Collections.unmodifiableList(items);
	}

	public int size() {
		return items.size();
	}

	public double total() {
		return items.stream().mapToDouble(Product::getPrice).sum();
	}

	public void clear() {
		items.clear();
	}
}
