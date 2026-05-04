package com.example.service.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.service.model.Order;
import com.example.service.repo.OrderRepository;
import com.example.service.repo.UserRepository;
import com.example.service.validation.OrderValidator;
import com.example.service.validation.UserValidator;

class OrderServiceTest {

	private OrderService orderService;
	private UserService userService;

	@BeforeEach
	void setUp() {
		UserRepository userRepo = new UserRepository();
		UserValidator userValidator = new UserValidator();
		userService = new UserService(userRepo, userValidator);

		OrderRepository orderRepo = new OrderRepository();
		OrderValidator orderValidator = new OrderValidator();
		orderService = new OrderService(orderRepo, orderValidator, userService);

		// Register a user for tests
		userService.register("Alice", "alice@example.com");
	}

	@Test
	void placeOrder() {
		Order order = orderService.placeOrder("alice@example.com", 1L, "Widget", 3);
		assertEquals("Widget", order.getProduct());
		assertEquals(3, order.getQuantity());
	}

	@Test
	void placeOrderUnknownUserThrows() {
		assertThrows(IllegalStateException.class, () -> orderService.placeOrder("nobody@example.com", 1L, "Widget", 1));
	}

	@Test
	void placeInvalidOrderThrows() {
		assertThrows(IllegalArgumentException.class, () -> orderService.placeOrder("alice@example.com", 1L, "", 1));
	}

	@Test
	void ordersForUser() {
		orderService.placeOrder("alice@example.com", 1L, "Widget", 1);
		orderService.placeOrder("alice@example.com", 2L, "Gadget", 2);
		List<Order> orders = orderService.ordersForUser("alice@example.com");
		assertEquals(2, orders.size());
	}

	@Test
	void ordersForUnknownUserThrows() {
		assertThrows(IllegalStateException.class, () -> orderService.ordersForUser("nobody@example.com"));
	}

	@Test
	void findById() {
		orderService.placeOrder("alice@example.com", 42L, "Widget", 1);
		assertTrue(orderService.findById(42L).isPresent());
		assertTrue(orderService.findById(999L).isEmpty());
	}

	@Test
	void totalOrders() {
		assertEquals(0, orderService.totalOrders());
		orderService.placeOrder("alice@example.com", 1L, "Widget", 1);
		assertEquals(1, orderService.totalOrders());
	}
}
