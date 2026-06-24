package me.bechberger.testorder.ci;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
	/** Maximum JSON response size accepted from the GitHub API (10 MB). */
	private static final long MAX_JSON_BYTES = 10L * 1024 * 1024;

	/**
	 * Shared HTTP client for all instances. OkHttpClient is heavyweight (thread
	 * pools, connection pools) and safe for concurrent use, so a single instance is
	 * reused across all GitHubActionsDownloader objects.
	 */
	private static final OkHttpClient DEFAULT_HTTP_CLIENT = CiHttpClientFactory.buildDefault();

	private final CiConfig.GithubConfig config;
	private final String token;
	private final OkHttpClient httpClient;

	public GitHubActionsDownloader(CiConfig.GithubConfig config, String token) {
		this(config, token, DEFAULT_HTTP_CLIENT);
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
	}

	@Override
	public boolean download(Path outputPath) throws IOException, DepDownloadException {
		validate();

		logger.info("Downloading from GitHub Actions: {}/{}", config.getOwner(), config.getRepo());

		try {
			// Step 1: Get latest workflow run
			String encodedWorkflow = URLEncoder.encode(config.getWorkflow(), StandardCharsets.UTF_8);
			String branch = config.getBranch();
			String workflowRunUrl;
			if (branch != null && !branch.isEmpty()) {
				String encodedBranch = URLEncoder.encode(branch, StandardCharsets.UTF_8);
				workflowRunUrl = String.format(
						"%s/repos/%s/%s/actions/workflows/%s/runs?status=success&branch=%s&per_page=1", GH_API_BASE,
						config.getOwner(), config.getRepo(), encodedWorkflow, encodedBranch);
			} else {
				// No branch filter — return latest successful run on any branch.
				// This is the safe default for auto-detected configs where the default
				// branch name is unknown (not "main" in all repos).
				workflowRunUrl = String.format("%s/repos/%s/%s/actions/workflows/%s/runs?status=success&per_page=1",
						GH_API_BASE, config.getOwner(), config.getRepo(), encodedWorkflow);
			}

			Map<String, Object> runResponse = fetchJson(workflowRunUrl);
			@SuppressWarnings("unchecked")
			List<Object> runs = (List<Object>) runResponse.get("workflow_runs");

			if (runs == null || runs.isEmpty()) {
				String branchDesc = (branch != null && !branch.isEmpty()) ? " on branch " + branch : " on any branch";
				throw new DepDownloadException(
						String.format("No successful workflow runs found for %s%s", config.getWorkflow(), branchDesc));
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

			long contentLength = response.body().contentLength();
			// contentLength is -1 when HTTP/2 or when the server omits Content-Length.
			// Guard against both: explicit size check when known, streaming byte-count
			// otherwise so an arbitrarily large body cannot OOM the build JVM.
			if (contentLength > MAX_JSON_BYTES) {
				throw new DepDownloadException("GitHub API response too large: " + contentLength + " bytes exceeds "
						+ MAX_JSON_BYTES + " limit");
			}

			byte[] bodyBytes;
			try (InputStream is = response.body().byteStream()) {
				ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
				byte[] chunk = new byte[8192];
				int n;
				while ((n = is.read(chunk)) >= 0) {
					if (buf.size() + n > MAX_JSON_BYTES) {
						throw new DepDownloadException(
								"GitHub API response exceeded " + MAX_JSON_BYTES + " byte limit during streaming");
					}
					buf.write(chunk, 0, n);
				}
				bodyBytes = buf.toByteArray();
			}
			String body = new String(bodyBytes, StandardCharsets.UTF_8);
			Object parsed = JSONParser.parse(body);
			if (!(parsed instanceof Map)) {
				throw new DepDownloadException("GitHub API returned unexpected JSON structure (expected object)");
			}
			return (Map<String, Object>) parsed;
		}
	}

	private static final long MAX_DOWNLOAD_BYTES = 500L * 1024 * 1024;

	/**
	 * Downloads the artifact ZIP from the GitHub API and extracts the first entry
	 * whose name ends with {@code .lz4} (the dependency index). The GitHub
	 * artifacts API always returns a ZIP archive, not the raw file.
	 */
	private boolean downloadFile(String downloadUrl, Path outputPath) throws IOException, DepDownloadException {
		Request.Builder requestBuilder = new Request.Builder().url(downloadUrl);

		if (token != null && !token.isEmpty()) {
			requestBuilder.header("Authorization", "token " + token);
		}

		Request request = requestBuilder.build();

		try (okhttp3.Response response = httpClient.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				throw new DepDownloadException(
						String.format("Download failed: %d %s", response.code(), response.message()));
			}

			if (response.body() == null) {
				throw new DepDownloadException("Download response body is empty");
			}

			try (InputStream raw = response.body().byteStream();
					ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
				ZipEntry entry;
				while ((entry = zip.getNextEntry()) != null) {
					String name = entry.getName();
					// Zip Slip guard: reject any entry whose canonical name would escape the
					// target directory (e.g. entries with "../" path components).
					if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
						zip.closeEntry();
						continue;
					}
					if (!name.endsWith(".lz4")) {
						zip.closeEntry();
						continue;
					}
					// Found the LZ4 index — write it directly to outputPath
					try (OutputStream out = java.nio.file.Files.newOutputStream(outputPath)) {
						byte[] buf = new byte[8192];
						int n;
						long totalRead = 0;
						while ((n = zip.read(buf)) != -1) {
							totalRead += n;
							if (totalRead > MAX_DOWNLOAD_BYTES) {
								throw new DepDownloadException(
										"Artifact entry too large: exceeds " + MAX_DOWNLOAD_BYTES + " bytes");
							}
							out.write(buf, 0, n);
						}
					}
					logger.info("Extracted {} from artifact ZIP to: {}", name, outputPath);
					return true;
				}
				throw new DepDownloadException("No .lz4 file found inside the GitHub artifact ZIP. "
						+ "Ensure the artifact was uploaded with the dependency index (test-dependencies.lz4).");
			}
		}
	}

	@Override
	public String getName() {
		return "GitHub Actions";
	}
}
