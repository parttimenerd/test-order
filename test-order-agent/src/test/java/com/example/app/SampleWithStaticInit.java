package com.example.app;

public class SampleWithStaticInit {

	public static final int CONSTANT;

	static {
		CONSTANT = computeConstant();
	}

	private static int computeConstant() {
		return 7 * 6;
	}

	public int getConstant() {
		return CONSTANT;
	}
}
