package com.usertest.app;

public class PaymentProcessor {
    public boolean validateCard(String cardNumber) {
        return cardNumber != null && cardNumber.length() == 16 && cardNumber.matches("\\d+");
    }

    public double applyDiscount(double amount, double discountPercent) {
        return amount * (1 - discountPercent / 100.0);
    }

    public boolean processPayment(String cardNumber, double amount) {
        if (!validateCard(cardNumber)) {
            return false;
        }
        if (amount <= 0) {
            return false;
        }
        return true;
    }
}
