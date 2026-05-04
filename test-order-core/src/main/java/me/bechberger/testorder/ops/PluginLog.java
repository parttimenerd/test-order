package me.bechberger.testorder.ops;

/**
 * Simple logging interface that decouples shared operations from
 * framework-specific loggers (Maven {@code Log}, Gradle {@code Logger}).
 */
public interface PluginLog {

	void info(String message);

	void warn(String message);

	void debug(String message);

	/** A no-op logger that discards all messages. */
	PluginLog NOOP = new PluginLog() {
		@Override
		public void info(String message) {
		}

		@Override
		public void warn(String message) {
		}

		@Override
		public void debug(String message) {
		}
	};
}
