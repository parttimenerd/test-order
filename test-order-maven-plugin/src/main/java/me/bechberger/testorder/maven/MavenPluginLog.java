package me.bechberger.testorder.maven;

import org.apache.maven.plugin.logging.Log;

import me.bechberger.testorder.ops.PluginLog;

/** Adapts Maven's {@link Log} to the framework-agnostic {@link PluginLog}. */
final class MavenPluginLog {

	private MavenPluginLog() {
	}

	/** Wraps a Maven {@link Log} as a {@link PluginLog}. */
	static PluginLog wrap(Log log) {
		return new PluginLog() {
			@Override
			public void info(String message) {
				log.info(message);
			}

			@Override
			public void warn(String message) {
				log.warn(message);
			}

			@Override
			public void error(String message) {
				log.error(message);
			}

			@Override
			public void debug(String message) {
				log.debug(message);
			}
		};
	}
}
