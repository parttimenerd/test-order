package me.bechberger.testorder.ci;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.bechberger.util.json.JSONParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Downloads dependency files from GitHub Actions artifacts. Uses GitHub REST
 * API to find and download the latest artifact from a workflow run.
 */
public class GitHubActionsDownloader implements DepDownloader {
	private static final Logger logger = LoggerFactory.getLogger(GitHubActionsDownloader.class);
	private static final String GH_API_BASE = "https://api.github.com";

	private final CiConfig.GithubConfig config;
	private final String token;
	private final OkHttpClient httpClient;

	public GitHubActionsDownloader(CiConfig.GithubConfig config, String token) {
		this(config, token, CiHttpClientFactory.buildDefault());
	}

	/**
	 * Constructor that accepts a pre-built {@link OkHttpClient}, e.g. one
	 * configured with a proxy.
	 */
	GitHubActionsDownloader(CiConfig.GithubConfig config, String token, OkHttpClient httpClient) {
		this.config = config;
		this.token = HttpDownloader.sanitizeToken(token);
		this.httpClient = httpClient;
	}

	@Override
	public void validate() throws DepDownloadException {
		if (!config.isValid()) {
			throw new DepDownloadException("Invalid GitHub config: missing owner, repo, workflow, or artifact-name");
		}
		if (config.getOwner() == null || config.getOwner().isEmpty()) {
			throw new DepDownloadException("GitHub owner is required");
		}
		if (config.getRepo() == null || config.getRepo().isEmpty()) {
			throw new DepDownloadException("GitHub repo is required");
		}
	}

	@Override
	public boolean download(Path outputPath) throws IOException, DepDownloadException {
		validate();

		logger.info("Downloading from GitHub Actions: {}/{}", config.getOwner(), config.getRepo());

		try {
			// Step 1: Get latest workflow run
			String encodedWorkflow = URLEncoder.encode(config.getWorkflow(), StandardCharsets.UTF_8);
			String encodedBranch = URLEncoder.encode(config.getBranch(), StandardCharsets.UTF_8);
			String workflowRunUrl = String.format(
					"%s/repos/%s/%s/actions/workflows/%s/runs?status=success&branch=%s&per_page=1", GH_API_BASE,
					config.getOwner(), config.getRepo(), encodedWorkflow, encodedBranch);

			Map<String, Object> runResponse = fetchJson(workflowRunUrl);
			@SuppressWarnings("unchecked")
			List<Object> runs = (List<Object>) runResponse.get("workflow_runs");

			if (runs == null || runs.isEmpty()) {
				throw new DepDownloadException(String.format("No successful workflow runs found for %s on branch %s",
						config.getWorkflow(), config.getBranch()));
			}

			@SuppressWarnings("unchecked")
			Map<String, Object> firstRun = (Map<String, Object>) runs.get(0);
			Object runIdObj = firstRun.get("id");
			if (!(runIdObj instanceof Number)) {
				throw new DepDownloadException("Unexpected GitHub API response: workflow run missing numeric 'id'");
			}
			long runId = ((Number) runIdObj).longValue();
			logger.info("Found latest workflow run: {}", runId);

			// Step 2+3: List artifacts from the run, paginating since GitHub caps
			// per_page at 100 and a run can have many artifacts.
			String artifactsBaseUrl = String.format("%s/repos/%s/%s/actions/runs/%d/artifacts?per_page=100",
					GH_API_BASE, config.getOwner(), config.getRepo(), runId);

			long artifactId = -1;
			for (int page = 1;; page++) {
				Map<String, Object> artifactsResponse = fetchJson(artifactsBaseUrl + "&page=" + page);
				@SuppressWarnings("unchecked")
				List<Object> artifacts = (List<Object>) artifactsResponse.get("artifacts");
				if (artifacts == null || artifacts.isEmpty())
					break;
				for (Object artifactElem : artifacts) {
					@SuppressWarnings("unchecked")
					Map<String, Object> artifact = (Map<String, Object>) artifactElem;
					if (config.getArtifactName().equals(artifact.get("name"))) {
						Object aidObj = artifact.get("id");
						if (!(aidObj instanceof Number)) {
							throw new DepDownloadException(
									"Unexpected GitHub API response: artifact missing numeric 'id'");
						}
						artifactId = ((Number) aidObj).longValue();
						break;
					}
				}
				if (artifactId != -1 || artifacts.size() < 100)
					break;
			}

			if (artifactId == -1) {
				throw new DepDownloadException(
						String.format("Artifact '%s' not found in workflow run %d", config.getArtifactName(), runId));
			}

			logger.info("Found artifact: {} (ID: {})", config.getArtifactName(), artifactId);

			// Step 4: Download the artifact
			String downloadUrl = String.format("%s/repos/%s/%s/actions/artifacts/%d/zip", GH_API_BASE,
					config.getOwner(), config.getRepo(), artifactId);

			return downloadFile(downloadUrl, outputPath);

		} catch (DepDownloadException e) {
			throw e;
		} catch (Exception e) {
			throw new DepDownloadException("Failed to download from GitHub Actions: " + e.getMessage(), e);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> fetchJson(String url) throws IOException, DepDownloadException {
		Request.Builder requestBuilder = new Request.Builder().url(url);

		if (token != null && !token.isEmpty()) {
			requestBuilder.header("Authorization", "token " + token);
		}

		Request request = requestBuilder.build();

		try (Response response = httpClient.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				throw new DepDownloadException(
						String.format("GitHub API request failed: %d %s", response.code(), response.message()));
			}

			if (response.body() == null) {
				throw new DepDownloadException("GitHub API response body is empty");
			}

			String body = response.body().string();
			Object parsed = JSONParser.parse(body);
			if (!(parsed instanceof Map)) {
				throw new DepDownloadException("GitHub API returned unexpected JSON structure (expected object)");
			}
			return (Map<String, Object>) parsed;
		}
	}

	private static final long MAX_DOWNLOAD_BYTES = 500L * 1024 * 1024;

	private boolean downloadFile(String downloadUrl, Path outputPath) throws IOException, DepDownloadException {
		Request.Builder requestBuilder = new Request.Builder().url(downloadUrl);

		if (token != null && !token.isEmpty()) {
			requestBuilder.header("Authorization", "token " + token);
		}

		Request request = requestBuilder.build();

		try (Response response = httpClient.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				throw new DepDownloadException(
						String.format("Download failed: %d %s", response.code(), response.message()));
			}

			if (response.body() == null) {
				throw new DepDownloadException("Download response body is empty");
			}

			try (InputStream input = response.body().byteStream();
					FileOutputStream output = new FileOutputStream(outputPath.toFile())) {
				byte[] buffer = new byte[8192];
				int bytesRead;
				long totalRead = 0;
				while ((bytesRead = input.read(buffer)) != -1) {
					totalRead += bytesRead;
					if (totalRead > MAX_DOWNLOAD_BYTES) {
						throw new DepDownloadException(
								"Download too large: exceeds " + MAX_DOWNLOAD_BYTES + " bytes limit");
					}
					output.write(buffer, 0, bytesRead);
				}
			}

			logger.info("Downloaded to: {}", outputPath);
			return true;
		}
	}

	@Override
	public String getName() {
		return "GitHub Actions";
	}
}
