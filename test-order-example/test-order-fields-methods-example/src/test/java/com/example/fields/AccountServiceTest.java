package com.example.fields;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests AccountService - depends on initialized field and balance field. Tests
 * demonstrate field dependency scoring.
 */
class AccountServiceTest {

	private AccountService account;

	@BeforeEach
	void setUp() {
		account = new AccountService();
	}

	@Test
	void testInitialization() {
		assertFalse(account.isInitialized());
		account.initialize("John", 1000);
		assertTrue(account.isInitialized());
		assertEquals("John", account.getAccountOwner());
		assertEquals(1000, account.getBalance());
	}

	@Test
	void testDeposit() {
		account.initialize("Jane", 500);
		account.deposit(250);
		assertEquals(750, account.getBalance());
		assertEquals(1, account.getTransactionCount());
	}

	@Test
	void testWithdraw() {
		account.initialize("Bob", 1000);
		account.withdraw(300);
		assertEquals(700, account.getBalance());
		assertEquals(1, account.getTransactionCount());
	}

	@Test
	void testInsufficientFunds() {
		account.initialize("Alice", 100);
		assertThrows(IllegalArgumentException.class, () -> account.withdraw(200));
		assertEquals(100, account.getBalance());
	}

	@Test
	void testCurrency() {
		account.setCurrency("EUR");
		assertEquals("EUR", account.getCurrency());
	}

	@Test
	void testReset() {
		account.initialize("Tom", 500);
		account.deposit(100);
		account.reset();
		assertFalse(account.isInitialized());
		assertEquals(0, account.getBalance());
		assertEquals(0, account.getTransactionCount());
	}
}
