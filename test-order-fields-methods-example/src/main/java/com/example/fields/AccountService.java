package com.example.fields;

/**
 * Account service that maintains account state through fields.
 * Tests dependency on field initialization and updates.
 */
public class AccountService {
    
    private volatile boolean initialized = false;
    private String accountOwner;
    private double balance = 0.0;
    private int transactionCount = 0;
    private String currency = "USD";
    
    public void initialize(String owner, double initialBalance) {
        this.accountOwner = owner;
        this.balance = initialBalance;
        this.transactionCount = 0;
        this.initialized = true;
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    public String getAccountOwner() {
        return accountOwner;
    }
    
    public double getBalance() {
        return balance;
    }
    
    public void deposit(double amount) {
        if (!initialized) {
            throw new IllegalStateException("Account not initialized");
        }
        balance += amount;
        transactionCount++;
    }
    
    public void withdraw(double amount) {
        if (!initialized) {
            throw new IllegalStateException("Account not initialized");
        }
        if (balance < amount) {
            throw new IllegalArgumentException("Insufficient funds");
        }
        balance -= amount;
        transactionCount++;
    }
    
    public int getTransactionCount() {
        return transactionCount;
    }
    
    public void setCurrency(String curr) {
        this.currency = curr;
    }
    
    public String getCurrency() {
        return currency;
    }
    
    public void reset() {
        initialized = false;
        accountOwner = null;
        balance = 0.0;
        transactionCount = 0;
        currency = "USD";
    }
}
