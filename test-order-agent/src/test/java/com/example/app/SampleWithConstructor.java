package com.example.app;

public class SampleWithConstructor {

	private final int value;

	public SampleWithConstructor(int value) {
		this.value = value;
	}

	public SampleWithConstructor() {
		this(42);
	}

	public int getValue() {
		return value;
	}
}
