package me.bechberger.testorder.ci;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * Creates a default {@link OkHttpClient} with configurable timeouts via system
 * properties:
 * <ul>
 * <li>{@code testorder.ci.connect.timeout.seconds} (default 30)</li>
 * <li>{@code testorder.ci.read.timeout.seconds} (default 60)</li>
 * <li>{@code testorder.ci.write.timeout.seconds} (default 30)</li>
 * </ul>
 */
final class CiHttpClientFactory {

	private CiHttpClientFactory() {
	}

	static OkHttpClient buildDefault() {
		int connectTimeout = getTimeoutProperty("testorder.ci.connect.timeout.seconds", 30);
		int readTimeout = getTimeoutProperty("testorder.ci.read.timeout.seconds", 60);
		int writeTimeout = getTimeoutProperty("testorder.ci.write.timeout.seconds", 30);
		return new OkHttpClient.Builder().connectTimeout(connectTimeout, TimeUnit.SECONDS)
				.readTimeout(readTimeout, TimeUnit.SECONDS).writeTimeout(writeTimeout, TimeUnit.SECONDS).build();
	}

	private static int getTimeoutProperty(String key, int defaultSeconds) {
		String prop = System.getProperty(key);
		if (prop != null) {
			try {
				int val = Integer.parseInt(prop);
				if (val > 0)
					return val;
			} catch (NumberFormatException ignored) {
			}
		}
		return defaultSeconds;
	}
}
