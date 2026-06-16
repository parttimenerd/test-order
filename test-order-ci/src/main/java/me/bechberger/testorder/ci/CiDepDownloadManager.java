package me.bechberger.testorder.ci;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.OkHttpClient;

/**
 * Coordinator for downloading CI dependency files. Handles config loading,
 * downloader selection, and error handling.
 *
 * <p>
 * Provider selection order (first match wins):
 * <ol>
 * <li>Explicit {@code download-config.yml} — uses whichever section is present
 * (github / gitlab / maven / http).</li>
 * <li>Auto-detect from environment — if {@code CI=true} and no config file
 * exists, the provider is inferred from provider-specific env vars:
 * <ul>
 * <li>{@code GITHUB_ACTIONS=true} → GitHub Actions downloader</li>
 * <li>{@code GITLAB_CI=true} → GitLab CI downloader</li>
 * </ul>
 * Auto-detection only works when the required env vars (repo slug, token, …)
 * are also set; otherwise the manager returns {@link Optional#empty()} from
 * {@link #downloadIfConfigured}.</li>
 * </ol>
 */
public class CiDepDownloadManager {
	private static final Logger logger = LoggerFactory.getLogger(CiDepDownloadManager.class);

	private final CiConfig config;
	private final OkHttpClient httpClient;
	private final String githubToken;
	private final String gitlabToken;
	private final String httpToken;
	private final String mavenToken;

	public CiDepDownloadManager(CiConfig config) {
		this.config = config;
		this.httpClient = buildHttpClient(config.getProxy());
		this.githubToken = getEnv("GITHUB_TOKEN", "GH_TOKEN");
		this.gitlabToken = config.getGitlab() != null ? getEnv(config.getGitlab().getTokenEnv()) : null;
		this.httpToken = config.getHttp() != null ? getEnv(config.getHttp().getTokenEnv()) : null;
		this.mavenToken = config.getMaven() != null && config.getMaven().getTokenEnv() != null
				? getEnv(config.getMaven().getTokenEnv())
				: null;
		warnMissingTokenEnvVars(config);
	}

	private void warnMissingTokenEnvVars(CiConfig config) {
		if (config.getGitlab() != null && config.getGitlab().getTokenEnv() != null && gitlabToken == null) {
			logger.warn("[test-order] GitLab token env var '{}' is not set — download may fail with 401/403",
					config.getGitlab().getTokenEnv());
		}
		if (config.getHttp() != null && config.getHttp().getTokenEnv() != null && httpToken == null) {
			logger.warn("[test-order] HTTP token env var '{}' is not set — download may fail with 401/403",
					config.getHttp().getTokenEnv());
		}
		if (config.getMaven() != null && config.getMaven().getTokenEnv() != null && mavenToken == null) {
			logger.warn("[test-order] Maven token env var '{}' is not set — download may fail with 401/403",
					config.getMaven().getTokenEnv());
		}
	}

	private static OkHttpClient buildHttpClient(CiConfig.ProxyConfig proxyConfig) {
		OkHttpClient base = CiHttpClientFactory.buildDefault();
		if (proxyConfig == null) {
			return base;
		}
		Proxy.Type proxyType = "socks5".equalsIgnoreCase(proxyConfig.getType()) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
		return base.newBuilder()
				.proxy(new Proxy(proxyType, new InetSocketAddress(proxyConfig.getHost(), proxyConfig.getPort())))
				.build();
	}

	/**
	 * Download the CI dependency index to a temporary file and return its path.
	 */
	public Path download() throws IOException, DepDownloader.DepDownloadException {
		DepDownloader downloader = selectDownloader();
		Path tempFile = Files.createTempFile("ci-deps-", ".tmp");
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
		} else if (config.getMaven() != null) {
			return new MavenNexusDownloader(config.getMaven(), mavenToken, httpClient);
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
	// Auto-detect support
	// ------------------------------------------------------------------

	/**
	 * Attempts to build a {@link CiConfig} from provider-specific environment
	 * variables when no explicit config file exists.
	 *
	 * <p>
	 * Currently supports:
	 * <ul>
	 * <li>GitHub Actions ({@code GITHUB_ACTIONS=true}): reads
	 * {@code GITHUB_REPOSITORY}, {@code GITHUB_WORKFLOW}, and uses artifact name
	 * {@code test-order-deps}.</li>
	 * <li>GitLab CI ({@code GITLAB_CI=true}): reads {@code CI_PROJECT_PATH} and
	 * {@code CI_JOB_NAME}.</li>
	 * </ul>
	 *
	 * @return auto-detected config, or {@code null} if env vars are insufficient
	 */
	static CiConfig autoDetect() {
		// GitHub Actions
		if ("true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))) {
			String repo = System.getenv("GITHUB_REPOSITORY"); // "owner/repo"
			String workflow = System.getenv("GITHUB_WORKFLOW_REF"); // "owner/repo/.github/workflows/ci.yml@refs/..."
			if (workflow == null) {
				workflow = System.getenv("GITHUB_WORKFLOW"); // fallback: just workflow name
			}
			if (repo != null && !repo.isEmpty() && workflow != null && !workflow.isEmpty()) {
				String[] parts = repo.split("/", 2);
				if (parts.length == 2) {
					// Extract just the filename from GITHUB_WORKFLOW_REF if needed
					String workflowFile = workflow;
					if (workflowFile.contains("/.github/workflows/")) {
						workflowFile = workflowFile.replaceAll(".*/\\.github/workflows/", "");
						workflowFile = workflowFile.replaceAll("@.*", "");
					}
					logger.debug("Auto-detected GitHub Actions CI: {}/{}, workflow: {}", parts[0], parts[1],
							workflowFile);
					java.util.Map<String, Object> cfg = new java.util.LinkedHashMap<>();
					java.util.Map<String, Object> ciMap = new java.util.LinkedHashMap<>();
					java.util.Map<String, Object> ghMap = new java.util.LinkedHashMap<>();
					ghMap.put("owner", parts[0]);
					ghMap.put("repo", parts[1]);
					ghMap.put("workflow", workflowFile);
					ghMap.put("artifact-name", "test-order-deps");
					// Use GITHUB_REF_NAME as branch hint when running on a push event
					// (GITHUB_EVENT_NAME=push). On PRs it is "N/merge" — not a real branch —
					// so we omit the branch filter and let the API return the latest successful
					// run from any branch. GITHUB_BASE_REF is the PR target branch but the
					// artifact was uploaded by a prior push run, so it may not match.
					String eventName = System.getenv("GITHUB_EVENT_NAME");
					String refName = System.getenv("GITHUB_REF_NAME");
					if ("push".equals(eventName) && refName != null && !refName.isEmpty() && !refName.contains("/")) {
						ghMap.put("branch", refName);
					}
					// No branch key → GitHubActionsDownloader omits &branch= filter
					ciMap.put("github", ghMap);
					cfg.put("ci", ciMap);
					return new CiConfig(cfg);
				}
			}
		}

		// GitLab CI
		if ("true".equalsIgnoreCase(System.getenv("GITLAB_CI"))) {
			String projectPath = System.getenv("CI_PROJECT_PATH");
			String jobName = System.getenv("CI_JOB_NAME");
			String defaultBranch = System.getenv("CI_DEFAULT_BRANCH");
			if (defaultBranch == null || defaultBranch.isEmpty()) {
				defaultBranch = "main";
			}
			if (projectPath != null && !projectPath.isEmpty() && jobName != null && !jobName.isEmpty()) {
				logger.debug("Auto-detected GitLab CI: project={}, job={}", projectPath, jobName);
				java.util.Map<String, Object> cfg = new java.util.LinkedHashMap<>();
				java.util.Map<String, Object> ciMap = new java.util.LinkedHashMap<>();
				java.util.Map<String, Object> glMap = new java.util.LinkedHashMap<>();
				glMap.put("project-id", projectPath);
				glMap.put("job-name", jobName);
				glMap.put("artifact-name", "test-dependencies.lz4");
				glMap.put("branch", defaultBranch);
				ciMap.put("gitlab", glMap);
				cfg.put("ci", ciMap);
				return new CiConfig(cfg);
			}
		}

		return null;
	}

	// ------------------------------------------------------------------
	// Static convenience API for build-tool integration (Maven/Gradle)
	// ------------------------------------------------------------------

	/**
	 * Look for a CI config file in {@code projectDir} and, if found (or
	 * auto-detectable from environment), download the dependency index directly to
	 * {@code indexTarget}.
	 *
	 * <p>
	 * Resolution order:
	 * <ol>
	 * <li>Explicit {@code .test-order/download-config.yml}</li>
	 * <li>Auto-detect from environment variables (see {@link #autoDetect()})</li>
	 * </ol>
	 *
	 * @param projectDir
	 *            the project root directory (contains {@code .test-order/})
	 * @param indexTarget
	 *            where to write the downloaded index file
	 * @return the path to the index file, or empty if no config was found or the
	 *         download failed
	 */
	public static Optional<Path> downloadIfConfigured(Path projectDir, Path indexTarget) {
		CiConfig config = loadConfig(projectDir);
		if (config == null) {
			return Optional.empty();
		}
		return doDownload(config, indexTarget);
	}

	/**
	 * Downloads both the dependency index and (optionally) the state file to
	 * {@code stateTarget}.
	 *
	 * <p>
	 * State download is best-effort: failures are logged as warnings and do not
	 * prevent the index download.
	 *
	 * @param projectDir
	 *            the project root directory
	 * @param indexTarget
	 *            where to write the downloaded index file
	 * @param stateTarget
	 *            where to write the downloaded state file, or {@code null} to skip
	 * @return the path to the index file, or empty if no config was found or the
	 *         index download failed
	 */
	public static Optional<Path> downloadIfConfigured(Path projectDir, Path indexTarget, Path stateTarget) {
		CiConfig config = loadConfig(projectDir);
		if (config == null) {
			return Optional.empty();
		}
		Optional<Path> result = doDownload(config, indexTarget);
		if (result.isPresent() && stateTarget != null) {
			tryDownloadState(config, stateTarget);
		}
		return result;
	}

	private static CiConfig loadConfig(Path projectDir) {
		if (CiConfigParser.configExistsIn(projectDir)) {
			try {
				return CiConfigParser.parseFromProjectDir(projectDir);
			} catch (Exception e) {
				logger.warn("Failed to parse CI config: {}", e.getMessage());
				return null;
			}
		}
		// Fall back to environment variable auto-detection
		CiConfig autoConfig = autoDetect();
		if (autoConfig != null) {
			logger.info("No download-config.yml found — using auto-detected CI provider from environment");
		}
		return autoConfig;
	}

	private static Optional<Path> doDownload(CiConfig config, Path indexTarget) {
		if (!config.getProviders().isEmpty()) {
			for (CiConfig provider : config.getProviders()) {
				Optional<Path> result = doDownload(provider, indexTarget, artifactNameFromConfig(provider));
				if (result.isPresent()) {
					return result;
				}
				logger.warn("Provider {} failed, trying next provider...", sourceNameFromConfig(provider));
			}
			logger.warn("All providers failed for {}", indexTarget);
			return Optional.empty();
		}
		return doDownload(config, indexTarget, artifactNameFromConfig(config));
	}

	private static Optional<Path> doDownload(CiConfig config, Path indexTarget, String artifactName) {
		try {
			CiDepDownloadManager mgr = new CiDepDownloadManager(config);
			Path downloaded = mgr.download();
			try {
				long size = Files.size(downloaded);
				if (size == 0) {
					logger.warn("CI download returned an empty file — skipping");
					return Optional.empty();
				}
				Files.createDirectories(indexTarget.getParent());
				Path tempSibling = me.bechberger.testorder.PersistenceSupport.temporarySibling(indexTarget);
				try {
					Files.copy(downloaded, tempSibling, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					me.bechberger.testorder.PersistenceSupport.moveIntoPlace(tempSibling, indexTarget);
				} catch (Exception copyEx) {
					Files.deleteIfExists(tempSibling);
					throw copyEx;
				}
			} finally {
				Files.deleteIfExists(downloaded);
			}
			logger.info("CI index written to {}", indexTarget);
			return Optional.of(indexTarget);
		} catch (Exception e) {
			logger.warn("CI download failed: {}", e.getMessage());
			return Optional.empty();
		}
	}

	private static String artifactNameFromConfig(CiConfig config) {
		if (config.getGithub() != null) {
			return config.getGithub().getArtifactName();
		} else if (config.getGitlab() != null) {
			return config.getGitlab().getArtifactName();
		} else if (config.getMaven() != null) {
			CiConfig.MavenConfig mv = config.getMaven();
			return mv.getGroupId() + ":" + mv.getArtifactId() + ":" + mv.getClassifier();
		} else if (config.getHttp() != null) {
			String url = config.getHttp().getUrl();
			if (url != null && !url.isEmpty()) {
				String path = url.replaceAll("[?#].*", "");
				int slash = path.lastIndexOf('/');
				String last = slash >= 0 ? path.substring(slash + 1) : path;
				return last.isEmpty() ? "http-artifact" : last;
			}
			return "http-artifact";
		}
		return "ci-artifact";
	}

	private static String sourceNameFromConfig(CiConfig config) {
		if (config.getGithub() != null)
			return "GitHub Actions";
		if (config.getGitlab() != null)
			return "GitLab CI";
		if (config.getMaven() != null)
			return "Maven";
		return "HTTP";
	}

	/**
	 * Best-effort state download: tries to download {@code state.lz4} from the same
	 * provider. Not all providers support state upload, so failure is logged as a
	 * warning only.
	 */
	private static void tryDownloadState(CiConfig config, Path stateTarget) {
		if (config.getGithub() != null) {
			CiConfig stateConfig = new CiConfig(
					config.getGithub().withArtifactName(config.getGithub().getArtifactName() + "-state"), null, null,
					null, null);
			doDownload(stateConfig, stateTarget);
		} else if (config.getGitlab() != null) {
			CiConfig stateConfig = new CiConfig(null, config.getGitlab().withArtifactName("test-state.lz4"), null, null,
					null);
			doDownload(stateConfig, stateTarget);
		} else if (config.getMaven() != null) {
			CiConfig stateConfig = new CiConfig(null, null, config.getMaven().withClassifier("test-state"), null, null);
			doDownload(stateConfig, stateTarget);
		} else {
			logger.debug("State download not supported for HTTP provider");
		}
	}
}
