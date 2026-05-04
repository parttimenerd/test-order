package com.example.fields;

/**
 * A shared utility that all tests depend on. Tests method and field interaction
 * scoring.
 */
public class SharedCounter {

	private static int globalCounter = 0;
	private int localCounter = 0;
	// Added comment to modify the class

	public static void incrementGlobal() {
		globalCounter++;
	}

	public static int getGlobalCounter() {
		return globalCounter;
	}

	public static void resetGlobal() {
		globalCounter = 0;
	}

	public void incrementLocal() {
		localCounter++;
	}

	public int getLocalCounter() {
		return localCounter;
	}

	public void resetLocal() {
		localCounter = 0;
	}

	public int getCombined() {
		// Modified: Added logic to test method change scoring
		return globalCounter + localCounter;
	}
}
