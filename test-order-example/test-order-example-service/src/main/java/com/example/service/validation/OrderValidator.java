package com.example.service.validation;

import com.example.service.model.Order;

/**
 * Validates orders.
 */
public class OrderValidator {

	private final UserValidator userValidator = new UserValidator();

	public boolean isValid(Order order) {
		if (order == null)
			return false;
		if (order.getUser() == null || !userValidator.isValid(order.getUser()))
			return false;
		if (order.getProduct() == null || order.getProduct().isBlank())
			return false;
		return order.getQuantity() > 0;
	}
}
