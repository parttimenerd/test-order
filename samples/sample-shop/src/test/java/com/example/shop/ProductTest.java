package com.example.shop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests Product — depends only on Product.
 */
class ProductTest {

	@Test
	void createProduct() {
		Product p = new Product("Widget", 9.99);
		assertEquals("Widget", p.getName());
		assertEquals(9.99, p.getPrice(), 0.001);
	}

	@Test
	void discount() {
		Product p = new Product("Gadget", 100.0);
		Product discounted = p.withDiscount(10);
		assertEquals(90.0, discounted.getPrice(), 0.001);
	}

	@Test
	void invalidNameThrows() {
		assertThrows(IllegalArgumentException.class, () -> new Product("", 5.0));
	}

	@Test
	void negativePriceThrows() {
		assertThrows(IllegalArgumentException.class, () -> new Product("X", -1));
	}

	@Test
	void toStringFormat() {
		Product p = new Product("Pen", 1.50);
		String s = p.toString();
		assertTrue(s.contains("Pen"));
		assertTrue(s.contains("$"));
		assertTrue(s.contains("1.50"));
	}
}
