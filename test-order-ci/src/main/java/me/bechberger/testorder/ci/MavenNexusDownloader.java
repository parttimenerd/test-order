package me.bechberger.testorder.ci;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Downloads the test-order dependency index from a Maven / Nexus / Artifactory
 * repository using a standard Maven artifact URL convention:
 *
 * <pre>
 * {@code
 * ci:
 *   maven:
 *     url: https://repo.example.com/repository/snapshots
 *     groupId: com.example
 *     artifactId: my-service
 *     version: LATEST          # or a fixed version; LATEST resolves via maven-metadata.xml
 *     classifier: test-deps    # optional (default: test-deps)
 *     extension: lz4           # optional (default: lz4)
 *     auth: bearer             # optional: bearer | basic | none (default: none)
 *     token-env: NEXUS_TOKEN   # optional
 * }
 * </pre>
 *
 * <p>
 * When {@code version=LATEST}, the downloader fetches
 * {@code <url>/<groupId>/<artifactId>/maven-metadata.xml} and resolves the
 * latest release or snapshot version from there before downloading the
 * artifact.
 */
public class MavenNexusDownloader implements DepDownloader {
	private static final Logger logger = LoggerFactory.getLogger(MavenNexusDownloader.class);

	/** Maximum download size: 500 MB. */
	private static final long MAX_DOWNLOAD_BYTES = 500L * 1024 * 1024;

	private final CiConfig.MavenConfig config;
	private final String token;
	private final OkHttpClient httpClient;
	private final boolean skipUrlSafetyChecks;

	MavenNexusDownloader(CiConfig.MavenConfig config, String token, boolean skipUrlSafetyChecks,
			OkHttpClient httpClient) {
		this.config = config;
		this.token = HttpDownloader.sanitizeToken(token);
		this.skipUrlSafetyChecks = skipUrlSafetyChecks;
		this.httpClient = httpClient;
	}

	public MavenNexusDownloader(CiConfig.MavenConfig config, String token, OkHttpClient httpClient) {
		this(config, token, false, httpClient);
	}

	@Override
	public String getName() {
		return "Maven/Nexus";
	}

	@Override
	public void validate() throws DepDownloadException {
		if (!config.isValid()) {
			throw new DepDownloadException("Invalid Maven config: url, groupId, and artifactId are required");
		}
		if (!skipUrlSafetyChecks) {
			validateUrl(config.getUrl());
		}
	}

	static void validateUrl(String url) throws DepDownloadException {
		// Delegate to HttpDownloader for consistent SSRF prevention (handles localhost
		// name checks, private-IP ranges, decimal/hex IP encoding bypass, etc.)
		HttpDownloader.validateUrl(url);
	}

	@Override
	public boolean download(Path outputPath) throws IOException, DepDownloadException {
		validate();

		String version = config.getVersion();
		if ("LATEST".equalsIgnoreCase(version) || "RELEASE".equalsIgnoreCase(version)) {
			version = resolveLatestVersion();
		}

		String artifactUrl = buildArtifactUrl(version);
		logger.info("Downloading Maven artifact from: {}", artifactUrl);
		doDownload(artifactUrl, outputPath);
		return true;
	}

	/**
	 * Fetches maven-metadata.xml and extracts the latest version string.
	 */
	private String resolveLatestVersion() throws IOException, DepDownloadException {
		String groupPath = config.getGroupId().replace('.', '/');
		String metadataUrl = config.getUrl().replaceAll("/+$", "") + "/" + groupPath + "/" + config.getArtifactId()
				+ "/maven-metadata.xml";

		logger.debug("Resolving latest version from: {}", metadataUrl);
		Request req = buildRequest(metadataUrl);
		try (Response resp = httpClient.newCall(req).execute()) {
			if (!resp.isSuccessful() || resp.body() == null) {
				throw new DepDownloadException(
						"Failed to fetch maven-metadata.xml: HTTP " + resp.code() + " from " + metadataUrl);
			}
			String xml = resp.body().string();
			String version = extractXmlValue(xml, "release");
			if (version == null) {
				version = extractXmlValue(xml, "latest");
			}
			if (version == null) {
				throw new DepDownloadException(
						"Could not resolve latest version from maven-metadata.xml: " + metadataUrl);
			}
			logger.info("Resolved latest version: {}", version);
			return version;
		}
	}

	private String buildArtifactUrl(String version) {
		String groupPath = config.getGroupId().replace('.', '/');
		String classifier = config.getClassifier() != null ? "-" + config.getClassifier() : "";
		String ext = config.getExtension() != null ? config.getExtension() : "lz4";
		String base = config.getUrl().replaceAll("/+$", "");
		return base + "/" + groupPath + "/" + config.getArtifactId() + "/" + version + "/" + config.getArtifactId()
				+ "-" + version + classifier + "." + ext;
	}

	private void doDownload(String url, Path outputPath) throws IOException, DepDownloadException {
		Request req = buildRequest(url);
		try (Response resp = httpClient.newCall(req).execute()) {
			if (!resp.isSuccessful()) {
				throw new DepDownloadException(
						String.format("HTTP %d %s downloading artifact from %s", resp.code(), resp.message(), url));
			}
			if (resp.body() == null) {
				throw new DepDownloadException("Empty response body from: " + url);
			}
			long contentLength = resp.body().contentLength();
			if (contentLength > MAX_DOWNLOAD_BYTES) {
				throw new DepDownloadException(String.format("Artifact too large: %d bytes exceeds limit of %d bytes",
						contentLength, MAX_DOWNLOAD_BYTES));
			}
			try (InputStream in = resp.body().byteStream();
					FileOutputStream out = new FileOutputStream(outputPath.toFile())) {
				byte[] buf = new byte[8192];
				int n;
				long total = 0;
				while ((n = in.read(buf)) != -1) {
					total += n;
					if (total > MAX_DOWNLOAD_BYTES) {
						out.close();
						java.nio.file.Files.deleteIfExists(outputPath);
						throw new DepDownloadException("Artifact exceeded size limit during download");
					}
					out.write(buf, 0, n);
				}
			} catch (DepDownloadException e) {
				throw e;
			} catch (IOException e) {
				java.nio.file.Files.deleteIfExists(outputPath);
				throw e;
			}
		}
		logger.info("Downloaded artifact to: {}", outputPath);
	}

	private Request buildRequest(String url) {
		Request.Builder rb = new Request.Builder().url(url);
		if (token != null && !token.isEmpty()) {
			String auth = config.getAuth() != null ? config.getAuth() : "none";
			if ("bearer".equalsIgnoreCase(auth)) {
				rb.header("Authorization", "Bearer " + token);
			} else if ("basic".equalsIgnoreCase(auth)) {
				rb.header("Authorization", "Basic " + token);
			}
		}
		return rb.build();
	}

	/**
	 * Extracts the text content of the first occurrence of {@code <tag>...}</tag>.
	 */
	private static String extractXmlValue(String xml, String tag) {
		String open = "<" + tag + ">";
		String close = "</" + tag + ">";
		int start = xml.indexOf(open);
		if (start < 0)
			return null;
		int end = xml.indexOf(close, start);
		if (end < 0)
			return null;
		String val = xml.substring(start + open.length(), end).strip();
		return val.isEmpty() ? null : val;
	}
}
