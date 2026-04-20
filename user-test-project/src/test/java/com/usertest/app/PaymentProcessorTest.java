package com.usertest.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PaymentProcessor Tests")
class PaymentProcessorTest {
    private final PaymentProcessor processor = new PaymentProcessor();

    @Test
    @DisplayName("Valid card validation")
    void testValidCardValidation() {
        assertTrue(processor.validateCard("1234567890123456"));
        assertFalse(processor.validateCard("123456789012345"));
        assertFalse(processor.validateCard("12345678901234ab"));
        assertFalse(processor.validateCard(null));
    }

    @Test
    @DisplayName("Discount calculation")
    void testDiscountCalculation() {
        assertEquals(80.0, processor.applyDiscount(100.0, 20.0), 0.01);
        assertEquals(100.0, processor.applyDiscount(100.0, 0.0), 0.01);
        assertEquals(50.0, processor.applyDiscount(100.0, 50.0), 0.01);
    }

    @Test
    @DisplayName("Payment processing")
    void testPaymentProcessing() {
        assertTrue(processor.processPayment("1234567890123456", 100.0));
        assertFalse(processor.processPayment("invalid", 100.0));
        assertFalse(processor.processPayment("1234567890123456", -10.0));
        assertFalse(processor.processPayment("1234567890123456", 0.0));
    }

    @Test
    @DisplayName("Multiple validations")
    void testMultipleValidations() {
        for (int i = 0; i < 1000; i++) {
            processor.validateCard("1234567890123456");
            processor.applyDiscount(100.0, 10.0);
        }
    }
}
