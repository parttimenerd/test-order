package com.example.app;

/**
 * Calculator that delegates to MathHelper.
 */
public class Calculator {

	private final MathHelper mathHelper = new MathHelper();

	public int add(int a, int b) {
		return mathHelper.add(a, b);
	}

	public int multiply(int a, int b) {
		return mathHelper.multiply(a, b);
	}

	public int power(int base, int exponent) {
		// Changed: Added a comment
		int result = 1;
		for (int i = 0; i < exponent; i++) {
			result = mathHelper.multiply(result, base);
		}
		return result;
	}
}
