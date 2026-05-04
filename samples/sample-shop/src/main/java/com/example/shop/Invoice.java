package com.example.shop;

/**
 * Creates an invoice from a cart. Depends on {@link Cart} and {@link Product}.
 */
public class Invoice {

	private final Cart cart;
	private final String customer;

	public Invoice(String customer, Cart cart) {
		this.customer = customer;
		this.cart = cart;
	}

	public String getCustomer() {
		return customer;
	}

	public double getSubtotal() {
		return cart.total();
	}

	public double getTax(double rate) {
		return getSubtotal() * rate / 100;
	}

	public double getTotal(double taxRate) {
		return getSubtotal() + getTax(taxRate);
	}

	public String render(double taxRate) {
		StringBuilder sb = new StringBuilder();
		sb.append("Invoice for ").append(customer).append("\n");
		for (Product p : cart.getItems()) {
			sb.append("  ").append(p).append("\n");
		}
		sb.append(String.format(java.util.Locale.US, "Subtotal: $%.2f%n", getSubtotal()));
		sb.append(String.format(java.util.Locale.US, "Tax (%.1f%%): $%.2f%n", taxRate, getTax(taxRate)));
		sb.append(String.format(java.util.Locale.US, "Total: $%.2f%n", getTotal(taxRate)));
		return sb.toString();
	}
}
