package me.bechberger.testorder.ci;

import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Downloads dependency files from generic HTTP endpoints. Supports basic and
 * bearer token authentication.
 */
public class HttpDownloader implements DepDownloader {
	private static final Logger logger = LoggerFactory.getLogger(HttpDownloader.class);

	/** Maximum download size: 500 MB. */
	private static final long MAX_DOWNLOAD_BYTES = 500L * 1024 * 1024;

	private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

	private final CiConfig.HttpConfig config;
	private final String token;
	private final OkHttpClient httpClient;
	private final boolean skipUrlSafetyChecks;
	/**
	 * Expected SHA-256 hex digest of the downloaded file, or null to skip
	 * verification.
	 */
	private final String expectedChecksum;

	/** Maximum number of retries on HTTP 429 (rate limited). */
	private static final int MAX_RETRIES = 3;

	public HttpDownloader(CiConfig.HttpConfig config, String token) {
		this(config, token, false);
	}

	/**
	 * @param skipUrlSafetyChecks
	 *            when true, skips SSRF validation (for testing with MockWebServer)
	 */
	HttpDownloader(CiConfig.HttpConfig config, String token, boolean skipUrlSafetyChecks) {
		this(config, token, skipUrlSafetyChecks, null);
	}

	/**
	 * @param skipUrlSafetyChecks
	 *            when true, skips SSRF validation (for testing with MockWebServer)
	 * @param expectedChecksum
	 *            SHA-256 hex digest to verify after download, or null to skip
	 */
	HttpDownloader(CiConfig.HttpConfig config, String token, boolean skipUrlSafetyChecks, String expectedChecksum) {
		this(config, token, skipUrlSafetyChecks, expectedChecksum, CiHttpClientFactory.buildDefault());
	}

	/**
	 * Constructor that accepts a pre-built {@link OkHttpClient}, e.g. one
	 * configured with a proxy.
	 */
	HttpDownloader(CiConfig.HttpConfig config, String token, boolean skipUrlSafetyChecks, String expectedChecksum,
			OkHttpClient httpClient) {
		this.config = config;
		this.token = sanitizeToken(token);
		this.skipUrlSafetyChecks = skipUrlSafetyChecks;
		this.expectedChecksum = expectedChecksum;
		this.httpClient = httpClient;
	}

	/**
	 * Strips CR and LF characters from tokens to prevent HTTP header injection
	 * (CWE-113).
	 */
	static String sanitizeToken(String token) {
		if (token == null)
			return null;
		String sanitized = token.replaceAll("[\\r\\n]", "");
		if (!sanitized.equals(token)) {
			logger.warn("Token contained illegal CR/LF characters which were removed");
		}
		return sanitized;
	}

	@Override
	public void validate() throws DepDownloadException {
		if (!config.isValid()) {
			throw new DepDownloadException("Invalid HTTP config: missing url");
		}
		if (config.getUrl() == null || config.getUrl().isEmpty()) {
			throw new DepDownloadException("HTTP URL is required");
		}
		if (!skipUrlSafetyChecks) {
			validateUrl(config.getUrl());
		}
	}

	/**
	 * Validates that the URL uses an allowed scheme and does not target localhost,
	 * private, or link-local IP ranges (SSRF prevention, CWE-918).
	 */
	static void validateUrl(String url) throws DepDownloadException {
		URI uri;
		try {
			uri = new URI(url);
		} catch (URISyntaxException e) {
			throw new DepDownloadException("Invalid URL: " + url, e);
		}

		String scheme = uri.getScheme();
		if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
			throw new DepDownloadException(
					"URL scheme '" + scheme + "' is not allowed. Only http and https are supported.");
		}

		String host = uri.getHost();
		if (host == null || host.isEmpty()) {
			throw new DepDownloadException("URL has no host: " + url);
		}

		if (isPrivateOrLocalhost(host)) {
			throw new DepDownloadException("URL targets a private or localhost address which is not allowed: " + host);
		}
	}

	private static boolean isPrivateOrLocalhost(String host) {
		String lower = host.toLowerCase();
		if (lower.equals("localhost") || lower.equals("localhost.localdomain") || lower.startsWith("127.")
				|| lower.equals("[::1]") || lower.equals("0.0.0.0")) {
			return true;
		}
		// Reject non-dotted numeric hosts (decimal/hex/octal IP encoding used for SSRF
		// bypass)
		// e.g. "2130706433" (decimal 127.0.0.1), "0x7f000001" (hex 127.0.0.1)
		if (lower.matches("^(0x)?[0-9a-f]+$")) {
			return true;
		}
		try {
			InetAddress addr = InetAddress.getByName(host);
			return addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
					|| addr.isAnyLocalAddress();
		} catch (java.net.UnknownHostException e) {
			// If we can't resolve, allow — DNS might not be available at validation time
			return false;
		}
	}

	@Override
	public boolean download(Path outputPath) throws IOException, DepDownloadException {
		validate();

		logger.info("Downloading from HTTP: {}", config.getUrl());

		Request.Builder requestBuilder = new Request.Builder().url(config.getUrl());

		// Add authentication if configured
		if (token != null && !token.isEmpty()) {
			String auth = config.getAuth() != null ? config.getAuth() : "bearer";
			if ("bearer".equalsIgnoreCase(auth)) {
				requestBuilder.header("Authorization", "Bearer " + token);
			} else if ("basic".equalsIgnoreCase(auth)) {
				requestBuilder.header("Authorization", "Basic " + token);
			}
		}

		Request request = requestBuilder.build();

		for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
			try (Response response = httpClient.newCall(request).execute()) {
				if (response.code() == 429) {
					if (attempt >= MAX_RETRIES) {
						throw new DepDownloadException(
								"Rate limited (HTTP 429) after " + (MAX_RETRIES + 1) + " attempts");
					}
					long waitSeconds = parseRetryAfter(response, attempt);
					logger.warn("Rate limited (HTTP 429). Retrying in {} seconds (attempt {}/{})", waitSeconds,
							attempt + 1, MAX_RETRIES);
					try {
						Thread.sleep(waitSeconds * 1000);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new DepDownloadException("Download interrupted during rate-limit wait", e);
					}
					continue;
				}

				if (!response.isSuccessful()) {
					throw new DepDownloadException(
							String.format("HTTP request failed: %d %s", response.code(), response.message()));
				}

				if (response.body() == null) {
					throw new DepDownloadException("Response body is empty");
				}

				// Enforce download size limit to prevent DoS (CLI-HIGH-7)
				long contentLength = response.body().contentLength();
				if (contentLength > MAX_DOWNLOAD_BYTES) {
					throw new DepDownloadException(
							String.format("Download too large: %d bytes exceeds limit of %d bytes", contentLength,
									MAX_DOWNLOAD_BYTES));
				}

				MessageDigest digest = createSha256Digest();

				try (InputStream input = response.body().byteStream();
						FileOutputStream output = new FileOutputStream(outputPath.toFile())) {
					byte[] buffer = new byte[8192];
					int bytesRead;
					long totalRead = 0;
					while ((bytesRead = input.read(buffer)) != -1) {
						totalRead += bytesRead;
						if (totalRead > MAX_DOWNLOAD_BYTES) {
							throw new DepDownloadException(
									String.format("Download exceeded size limit of %d bytes", MAX_DOWNLOAD_BYTES));
						}
						output.write(buffer, 0, bytesRead);
						if (digest != null) {
							digest.update(buffer, 0, bytesRead);
						}
					}
				}

				verifyChecksum(digest);
				logger.info("Downloaded to: {}", outputPath);
				return true;
			}
		}
		// Should not reach here due to the throw inside the loop
		throw new DepDownloadException("Download failed after retries");
	}

	/**
	 * Parses the Retry-After header, falling back to exponential backoff.
	 */
	private static long parseRetryAfter(Response response, int attempt) {
		String retryAfter = response.header("Retry-After");
		if (retryAfter != null) {
			try {
				long seconds = Long.parseLong(retryAfter.strip());
				if (seconds > 0 && seconds <= 300) {
					return seconds;
				}
			} catch (NumberFormatException ignored) {
				// Not a numeric value — fall through to exponential backoff
			}
		}
		// Exponential backoff: 1s, 4s, 16s
		return (long) Math.pow(4, attempt);
	}

	private MessageDigest createSha256Digest() {
		if (expectedChecksum == null)
			return null;
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			logger.warn("SHA-256 not available; skipping checksum verification");
			return null;
		}
	}

	private void verifyChecksum(MessageDigest digest) throws DepDownloadException {
		if (digest == null || expectedChecksum == null)
			return;
		String actual = HexFormat.of().formatHex(digest.digest());
		if (!actual.equalsIgnoreCase(expectedChecksum)) {
			throw new DepDownloadException("Checksum mismatch: expected " + expectedChecksum + " but got " + actual);
		}
		logger.info("Checksum verified: SHA-256 = {}", actual);
	}

	@Override
	public String getName() {
		return "HTTP";
	}
}
