package com.example.service.model;

/**
 * Simple order model.
 */
public class Order {
	private final long id;
	private final User user;
	private final String product;
	private final int quantity;

	public Order(long id, User user, String product, int quantity) {
		this.id = id;
		this.user = user;
		this.product = product;
		this.quantity = quantity;
	}

	public long getId() {
		return id;
	}
	public User getUser() {
		return user;
	}
	public String getProduct() {
		return product;
	}
	public int getQuantity() {
		return quantity;
	}

	public double total(double unitPrice) {
		return quantity * unitPrice;
	}

	public int total() {
		return quantity;
	}

	@Override
	public String toString() {
		return "Order{id=" + id + ", user=" + user + ", product='" + product + "', quantity=" + quantity + "}";
	}
}
