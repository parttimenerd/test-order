package com.example.nested;

/** Leaf node of the four-level object graph. */
public class Employee {

	private String name;
	private String title;
	private int level;

	public Employee(String name, String title, int level) {
		this.name = name;
		this.title = title;
		this.level = level;
	}

	public String getName() {
		return name;
	}

	public String getTitle() {
		return title;
	}

	public int getLevel() {
		return level;
	}

	public void promote() {
		level++;
	}
}
