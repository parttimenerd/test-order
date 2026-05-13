package me.bechberger.testorder.ops.detection;

import java.util.*;

/**
 * Tracks what is known about each test during detection. Mutable — accumulates
 * evidence across runs.
 */
public class TestKnowledge {

	private final Set<String> confirmedPolluters = new HashSet<>();
	private final Set<String> confirmedSetters = new HashSet<>();
	private final Set<String> eliminatedPolluters = new HashSet<>();
	private Boolean passesAlone = null;
	private int failureCount = 0;

	public Set<String> confirmedPolluters() {
		return confirmedPolluters;
	}

	public Set<String> confirmedSetters() {
		return confirmedSetters;
	}

	public Set<String> eliminatedPolluters() {
		return eliminatedPolluters;
	}

	public Boolean passesAlone() {
		return passesAlone;
	}

	public void setPassesAlone(boolean value) {
		this.passesAlone = value;
	}

	public int failureCount() {
		return failureCount;
	}

	public void incrementFailureCount() {
		this.failureCount++;
	}
}
