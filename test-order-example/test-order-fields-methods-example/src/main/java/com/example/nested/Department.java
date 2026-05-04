package com.example.nested;

public class Department {

	private final String name;
	private Team frontendTeam;

	public Department(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}

	public void setFrontendTeam(Team team) {
		this.frontendTeam = team;
	}

	public Team getFrontendTeam() {
		return frontendTeam;
	}
}
