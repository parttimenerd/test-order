package com.example.shop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests Invoice — depends on Invoice, Cart, and Product.
 */
class InvoiceTest {

	@Test
	void subtotalMatchesCartTotal() {
		Cart cart = new Cart();
		cart.add(new Product("A", 10.0));
		cart.add(new Product("B", 20.0));
		Invoice inv = new Invoice("Alice", cart);
		assertEquals(30.0, inv.getSubtotal(), 0.001);
	}

	@Test
	void taxCalculation() {
		Cart cart = new Cart();
		cart.add(new Product("X", 100.0));
		Invoice inv = new Invoice("Bob", cart);
		assertEquals(10.0, inv.getTax(10), 0.001);
	}

	@Test
	void totalIncludesTax() {
		Cart cart = new Cart();
		cart.add(new Product("Y", 200.0));
		Invoice inv = new Invoice("Carol", cart);
		assertEquals(220.0, inv.getTotal(10), 0.001);
	}

	@Test
	void renderContainsCustomerAndProducts() {
		Cart cart = new Cart();
		cart.add(new Product("Widget", 15.0));
		Invoice inv = new Invoice("Dave", cart);
		String output = inv.render(5);
		assertTrue(output.contains("Dave"));
		assertTrue(output.contains("Widget"));
		assertTrue(output.contains("Total"));
	}
}
