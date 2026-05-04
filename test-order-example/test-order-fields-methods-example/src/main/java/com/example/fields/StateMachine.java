package com.example.fields;

/**
 * Complex state machine with field interdependencies. Used to test scoring of
 * correlated field changes.
 */
public class StateMachine {

	private State currentState = State.IDLE;
	private int stateCounter = 0;
	private String lastTransition = "";
	// Added unused field to test if it affects scoring
	private long unusedField = System.currentTimeMillis();

	public enum State {
		IDLE, RUNNING, STOPPED, ERROR
	}

	public void start() {
		if (currentState == State.IDLE) {
			currentState = State.RUNNING;
			stateCounter++;
			lastTransition = "start";
		}
	}

	public void stop() {
		if (currentState == State.RUNNING) {
			currentState = State.STOPPED;
			stateCounter++;
			lastTransition = "stop";
		}
	}

	public void reset() {
		currentState = State.IDLE;
		stateCounter = 0;
		lastTransition = "";
	}

	public State getState() {
		return currentState;
	}

	public int getStateCounter() {
		return stateCounter;
	}

	public String getLastTransition() {
		// Changed - added complexity comment to test complexity scoring
		return lastTransition;
	}

	public void error(String message) {
		currentState = State.ERROR;
		lastTransition = "error: " + message;
	}

	public boolean isRunning() {
		return currentState == State.RUNNING;
	}
}
