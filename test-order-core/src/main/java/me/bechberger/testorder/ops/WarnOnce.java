package me.bechberger.testorder.ops;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logs a warning at most once per process lifetime for a given key. Useful for
 * deprecation notices and recurring failures (e.g., ML training) that would
 * otherwise spam the build log every time a mojo or task is invoked.
 */
public final class WarnOnce {

	private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

	private WarnOnce() {
	}

	/**
	 * Emit {@code message} via {@code log.warn(...)} unless {@code key} has already
	 * been warned.
	 */
	public static void warn(PluginLog log, String key, String message) {
		if (SEEN.add(key)) {
			log.warn(message);
		}
	}

	/** Test-only: clear the dedup set so each test starts fresh. */
	static void resetForTesting() {
		SEEN.clear();
	}
}
