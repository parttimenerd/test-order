package com.example.app;

public class SampleWithFields {

	public static String SHARED_CONFIG = "default";

	private String name;

	public SampleWithFields(String name) {
		this.name = name;
	}

	public String readSharedConfig() {
		return SHARED_CONFIG;
	}

	public void writeSharedConfig(String value) {
		SHARED_CONFIG = value;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String readExternalStatic() {
		return SampleAppClass.class.getName() + SHARED_CONFIG;
	}
}
