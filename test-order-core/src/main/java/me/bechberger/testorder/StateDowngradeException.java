package me.bechberger.testorder;

/**
 * Thrown when a state file was written by a newer plugin version and cannot be
 * read by the current (older) version. This replaces the generic
 * {@link IllegalArgumentException} to provide clear recovery guidance.
 */
public class StateDowngradeException extends RuntimeException {

	public StateDowngradeException(String message) {
		super(message);
	}
}
