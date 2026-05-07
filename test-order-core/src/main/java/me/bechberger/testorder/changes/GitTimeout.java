package me.bechberger.testorder.changes;

/**
 * Unified git subprocess timeout configuration.
 * All git operations use this single timeout value to avoid inconsistencies.
 */
public final class GitTimeout {

	private GitTimeout() {
	}

	/**
	 * Default timeout (seconds) for git subprocess operations.
	 * Configurable via system property {@code testorder.git.timeout.seconds}.
	 */
	public static final int DEFAULT_SECONDS = 30;

	/**
	 * Returns the configured git timeout in seconds.
	 * Reads from {@code testorder.git.timeout.seconds} system property, falling back to
	 * {@link #DEFAULT_SECONDS}.
	 */
	public static int seconds() {
		String prop = System.getProperty("testorder.git.timeout.seconds");
		if (prop != null) {
			try {
				int val = Integer.parseInt(prop);
				if (val > 0) {
					return val;
				}
			} catch (NumberFormatException ignored) {
				// fall through to default
			}
		}
		return DEFAULT_SECONDS;
	}
}
