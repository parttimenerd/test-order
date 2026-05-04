package com.example.service.service;

import java.util.List;
import java.util.Optional;

import com.example.service.model.Order;
import com.example.service.model.User;
import com.example.service.repo.OrderRepository;
import com.example.service.validation.OrderValidator;

/**
 * Service layer for order operations.
 */
public class OrderService {

	private final OrderRepository orderRepository;
	private final OrderValidator orderValidator;
	private final UserService userService;

	public OrderService(OrderRepository orderRepository, OrderValidator orderValidator, UserService userService) {
		this.orderRepository = orderRepository;
		this.orderValidator = orderValidator;
		this.userService = userService;
	}

	public Order placeOrder(String email, long id, String product, int quantity) {
		User user = userService.findByEmail(email)
				.orElseThrow(() -> new IllegalStateException("User not found: " + email));
		Order order = new Order(id, user, product, quantity);
		if (!orderValidator.isValid(order)) {
			throw new IllegalArgumentException("Invalid order");
		}
		orderRepository.save(order);
		return order;
	}

	public List<Order> ordersForUser(String email) {
		User user = userService.findByEmail(email)
				.orElseThrow(() -> new IllegalStateException("User not found: " + email));
		return orderRepository.findByUser(user);
	}

	public Optional<Order> findById(long id) {
		return orderRepository.findById(id);
	}

	public int totalOrders() {
		return orderRepository.count();
	}
}
