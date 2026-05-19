package com.example.app;

public class SampleImplementation implements SampleInterface {

	private final SampleInterface delegate;

	public SampleImplementation(SampleInterface delegate) {
		this.delegate = delegate;
	}

	@Override
	public String doSomething() {
		return "impl:" + (delegate != null ? delegate.doSomething() : "none");
	}
}
