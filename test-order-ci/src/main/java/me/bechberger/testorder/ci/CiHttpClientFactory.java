package me.bechberger.testorder.ci;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
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
				.readTimeout(readTimeout, TimeUnit.SECONDS).writeTimeout(writeTimeout, TimeUnit.SECONDS)
				.dns(SSRF_SAFE_DNS).build();
	}

	/**
	 * DNS resolver that rejects private/localhost addresses at connection time to
	 * prevent SSRF via DNS rebinding (CWE-918). The pre-connect URL validation in
	 * HttpDownloader.validateUrl is TOCTOU-vulnerable because DNS can change
	 * between validation and the actual connection.
	 */
	static final Dns SSRF_SAFE_DNS = hostname -> {
		List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
		for (InetAddress addr : addresses) {
			if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
					|| addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
				throw new UnknownHostException(
						"DNS resolved to private/localhost address for " + hostname + ": " + addr.getHostAddress());
			}
		}
		return addresses;
	};

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
