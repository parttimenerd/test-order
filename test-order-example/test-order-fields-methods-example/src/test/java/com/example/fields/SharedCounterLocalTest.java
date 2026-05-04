package com.example.fields;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests SharedCounter instance fields. Tests local counter behavior.
 */
class SharedCounterLocalTest {

	private SharedCounter counter;

	@BeforeEach
	void setUp() {
		counter = new SharedCounter();
	}

	@Test
	void testLocalIncrement() {
		counter.incrementLocal();
		assertEquals(1, counter.getLocalCounter());
	}

	@Test
	void testLocalMultipleIncrements() {
		counter.incrementLocal();
		counter.incrementLocal();
		counter.incrementLocal();
		assertEquals(3, counter.getLocalCounter());
	}

	@Test
	void testLocalReset() {
		counter.incrementLocal();
		counter.incrementLocal();
		counter.resetLocal();
		assertEquals(0, counter.getLocalCounter());
	}

	@Test
	void testCombinedCounter() {
		counter.incrementLocal();
		SharedCounter.incrementGlobal();
		SharedCounter.incrementGlobal();
		assertEquals(3, counter.getCombined());
	}
}
