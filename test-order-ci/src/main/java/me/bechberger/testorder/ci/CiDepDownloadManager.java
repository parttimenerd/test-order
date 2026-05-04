package me.bechberger.testorder.ci;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.OkHttpClient;

/**
 * Coordinator for downloading CI dependency files. Handles config loading,
 * downloader selection, and error handling.
 */
public class CiDepDownloadManager {
	private static final Logger logger = LoggerFactory.getLogger(CiDepDownloadManager.class);

	private final CiConfig config;
	private final OkHttpClient httpClient;
	private final String githubToken;
	private final String gitlabToken;
	private final String httpToken;

	public CiDepDownloadManager(CiConfig config) {
		this.config = config;
		this.httpClient = buildHttpClient(config.getProxy());
		this.githubToken = getEnv("GITHUB_TOKEN", "GH_TOKEN");
		this.gitlabToken = config.getGitlab() != null ? getEnv(config.getGitlab().getTokenEnv()) : null;
		this.httpToken = config.getHttp() != null ? getEnv(config.getHttp().getTokenEnv()) : null;
	}

	private static OkHttpClient buildHttpClient(CiConfig.ProxyConfig proxyConfig) {
		OkHttpClient.Builder builder = new OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
				.readTimeout(60, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS);
		if (proxyConfig != null) {
			Proxy.Type proxyType = "socks5".equalsIgnoreCase(proxyConfig.getType())
					? Proxy.Type.SOCKS
					: Proxy.Type.HTTP;
			builder.proxy(new Proxy(proxyType, new InetSocketAddress(proxyConfig.getHost(), proxyConfig.getPort())));
		}
		return builder.build();
	}

	/**
	 * Download the CI dependency index to a temporary file and return its path.
	 */
	public Path download() throws IOException, DepDownloader.DepDownloadException {
		DepDownloader downloader = selectDownloader();
		Path tempFile = Files.createTempFile("ci-deps-", ".zip");
		try {
			downloader.validate();
			downloader.download(tempFile);
			return tempFile;
		} catch (Exception e) {
			Files.deleteIfExists(tempFile);
			throw e;
		}
	}

	/**
	 * Select appropriate downloader based on config
	 */
	private DepDownloader selectDownloader() throws DepDownloader.DepDownloadException {
		if (config.getGithub() != null) {
			return new GitHubActionsDownloader(config.getGithub(), githubToken, httpClient);
		} else if (config.getGitlab() != null) {
			return new GitLabCiDownloader(config.getGitlab(), gitlabToken, httpClient);
		} else if (config.getHttp() != null) {
			return new HttpDownloader(config.getHttp(), httpToken, false, null, httpClient);
		} else {
			throw new DepDownloader.DepDownloadException("No valid CI configuration found");
		}
	}

	/**
	 * Get environment variable from multiple possible names
	 */
	private String getEnv(String... names) {
		for (String name : names) {
			if (name != null && !name.isEmpty()) {
				String value = System.getenv(name);
				if (value != null && !value.isEmpty()) {
					return value;
				}
			}
		}
		return null;
	}

	// ------------------------------------------------------------------
	// Static convenience API for build-tool integration (Maven/Gradle)
	// ------------------------------------------------------------------

	/**
	 * Look for a CI config file in {@code projectDir} and, if found, download the
	 * dependency index directly to {@code indexTarget}.
	 *
	 * @param projectDir
	 *            the project root directory (contains {@code .test-order/})
	 * @param indexTarget
	 *            where to write the downloaded index file
	 * @return the path to the index file, or empty if no config was found or the
	 *         download failed
	 */
	public static Optional<Path> downloadIfConfigured(Path projectDir, Path indexTarget) {
		if (!CiConfigParser.configExistsIn(projectDir)) {
			return Optional.empty();
		}
		try {
			CiConfig config = CiConfigParser.parseFromProjectDir(projectDir);
			if (config == null) {
				return Optional.empty();
			}
			CiDepDownloadManager mgr = new CiDepDownloadManager(config);
			Path downloaded = mgr.download();
			try {
				Files.createDirectories(indexTarget.getParent());
				Files.copy(downloaded, indexTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} finally {
				Files.deleteIfExists(downloaded);
			}
			logger.info("CI index written to {}", indexTarget);
			return Optional.of(indexTarget);
		} catch (Exception e) {
			logger.warn("CI download failed (falling back to local): {}", e.getMessage());
			return Optional.empty();
		}
	}
}
