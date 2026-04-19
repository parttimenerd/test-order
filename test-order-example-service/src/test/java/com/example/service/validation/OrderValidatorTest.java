package com.example.service.validation;

import com.example.service.model.Order;
import com.example.service.model.User;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderValidatorTest {

    private final OrderValidator validator = new OrderValidator();

    @Test
    void validOrder() {
        User user = new User("Alice", "alice@example.com");
        assertTrue(validator.isValid(new Order(1L, user, "Widget", 1)));
    }

    @Test
    void nullUserInvalid() {
        assertFalse(validator.isValid(new Order(1L, null, "Widget", 1)));
    }

    @Test
    void invalidUserMakesOrderInvalid() {
        User user = new User("", "alice@example.com");
        assertFalse(validator.isValid(new Order(1L, user, "Widget", 1)));
    }

    @Test
    void nullProductInvalid() {
        User user = new User("Alice", "alice@example.com");
        assertFalse(validator.isValid(new Order(1L, user, null, 1)));
    }

    @Test
    void emptyProductInvalid() {
        User user = new User("Alice", "alice@example.com");
        assertFalse(validator.isValid(new Order(1L, user, "", 1)));
    }

    @Test
    void zeroQuantityInvalid() {
        User user = new User("Alice", "alice@example.com");
        assertFalse(validator.isValid(new Order(1L, user, "Widget", 0)));
    }

    @Test
    void negativeQuantityInvalid() {
        User user = new User("Alice", "alice@example.com");
        assertFalse(validator.isValid(new Order(1L, user, "Widget", -1)));
    }
}
