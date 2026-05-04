package com.example.nested;

/**
 * Four-level deep object graph: Company → Department → Team → Employee. Tests
 * whether the agent tracks field accesses through a chain of dereferences
 * (a.getB().getC().getD().field).
 */
public class Company {

	private final String name;
	private Department engineering;

	public Company(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}

	public void setEngineering(Department dept) {
		this.engineering = dept;
	}

	public Department getEngineering() {
		return engineering;
	}
}
