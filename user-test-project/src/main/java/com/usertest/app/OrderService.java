package com.usertest.app;

public class OrderService {
    private PaymentProcessor paymentProcessor = new PaymentProcessor();

    public boolean createOrder(String customerId, double amount, String cardNumber) {
        if (customerId == null || customerId.isEmpty()) {
            return false;
        }
        return paymentProcessor.processPayment(cardNumber, amount);
    }

    public double calculateTotal(double subtotal, double taxRate) {
        return subtotal * (1 + taxRate);
    }

    public String formatOrderId(String prefix, long id) {
        return prefix + "-" + String.format("%08d", id);
    }
}
