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
 * Downloads dependency artifacts from GitLab CI pipelines using the GitLab REST
 * API v4. Supports both gitlab.com and self-hosted GitLab instances.
 *
 * <p>
 * Config example:
 *
 * <pre>
 * ci:
 *   gitlab:
 *     base-url: https://gitlab.com     # optional; default gitlab.com
 *     project-id: "my-group/my-project" # or numeric ID
 *     job-name: build
 *     artifact-name: test-order-deps   # cache key; optional
 *     branch: main
 *     token-env: GITLAB_TOKEN
 * </pre>
 */
public class GitLabCiDownloader implements DepDownloader {
	private static final Logger logger = LoggerFactory.getLogger(GitLabCiDownloader.class);

	private final CiConfig.GitLabConfig config;
	private final String token;
	private final OkHttpClient httpClient;

	public GitLabCiDownloader(CiConfig.GitLabConfig config, String token) {
		this(config, token, CiHttpClientFactory.buildDefault());
	}

	/**
	 * Constructor that accepts a pre-built {@link OkHttpClient}, e.g. one
	 * configured with a proxy.
	 */
	GitLabCiDownloader(CiConfig.GitLabConfig config, String token, OkHttpClient httpClient) {
		this.config = config;
		this.token = HttpDownloader.sanitizeToken(token);
		this.httpClient = httpClient;
	}

	@Override
	public void validate() throws DepDownloadException {
		if (!config.isValid()) {
			throw new DepDownloadException("Invalid GitLab config: missing project-id or job-name");
		}
	}

	@Override
	public boolean download(Path outputPath) throws IOException, DepDownloadException {
		validate();

		String projectId = URLEncoder.encode(config.getProjectId(), StandardCharsets.UTF_8);
		logger.info("Downloading from GitLab CI: {}/{}", config.getBaseUrl(), config.getProjectId());

		// Step 1: find the latest successful pipeline on the configured branch
		String encodedBranch = URLEncoder.encode(config.getBranch(), StandardCharsets.UTF_8);
		String pipelinesUrl = String.format(
				"%s/api/v4/projects/%s/pipelines?ref=%s&status=success&order_by=id&sort=desc&per_page=1",
				config.getBaseUrl(), projectId, encodedBranch);

		List<Object> pipelines = fetchJsonArray(pipelinesUrl);
		if (pipelines.isEmpty()) {
			throw new DepDownloadException(String.format("No successful pipelines found for project %s on branch %s",
					config.getProjectId(), config.getBranch()));
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> firstPipeline = (Map<String, Object>) pipelines.get(0);
		long pipelineId = ((Number) firstPipeline.get("id")).longValue();
		logger.info("Found pipeline: {}", pipelineId);

		// Step 2: find the job by name inside that pipeline
		String jobsUrl = String.format("%s/api/v4/projects/%s/pipelines/%d/jobs?scope[]=success&per_page=100",
				config.getBaseUrl(), projectId, pipelineId);

		List<Object> jobs = fetchJsonArray(jobsUrl);
		long jobId = -1;
		for (Object elem : jobs) {
			@SuppressWarnings("unchecked")
			Map<String, Object> job = (Map<String, Object>) elem;
			if (config.getJobName().equals(job.get("name"))) {
				jobId = ((Number) job.get("id")).longValue();
				break;
			}
		}

		if (jobId == -1) {
			throw new DepDownloadException(String.format("Job '%s' not found in pipeline %d (project %s)",
					config.getJobName(), pipelineId, config.getProjectId()));
		}
		logger.info("Found job: {} (ID: {})", config.getJobName(), jobId);

		// Step 3: download the artifact archive for that job
		String artifactsUrl = String.format("%s/api/v4/projects/%s/jobs/%d/artifacts", config.getBaseUrl(), projectId,
				jobId);
		return downloadFile(artifactsUrl, outputPath);
	}

	@SuppressWarnings("unchecked")
	private List<Object> fetchJsonArray(String url) throws IOException, DepDownloadException {
		try (Response response = httpClient.newCall(buildRequest(url)).execute()) {
			if (!response.isSuccessful()) {
				throw new DepDownloadException(
						String.format("GitLab API request failed: %d %s", response.code(), response.message()));
			}
			if (response.body() == null) {
				throw new DepDownloadException("GitLab API response body is empty");
			}
			Object parsed = JSONParser.parse(response.body().string());
			if (!(parsed instanceof List)) {
				throw new DepDownloadException("GitLab API returned unexpected JSON structure (expected array)");
			}
			return (List<Object>) parsed;
		}
	}

	private boolean downloadFile(String url, Path outputPath) throws IOException, DepDownloadException {
		try (Response response = httpClient.newCall(buildRequest(url)).execute()) {
			if (!response.isSuccessful()) {
				throw new DepDownloadException(
						String.format("Artifact download failed: %d %s", response.code(), response.message()));
			}
			if (response.body() == null) {
				throw new DepDownloadException("Artifact response body is empty");
			}
			try (InputStream in = response.body().byteStream();
					FileOutputStream out = new FileOutputStream(outputPath.toFile())) {
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) != -1) {
					out.write(buf, 0, n);
				}
			}
			logger.info("Downloaded artifact to: {}", outputPath);
			return true;
		}
	}

	private Request buildRequest(String url) {
		Request.Builder builder = new Request.Builder().url(url);
		if (token != null && !token.isEmpty()) {
			builder.header("PRIVATE-TOKEN", token);
		}
		return builder.build();
	}

	@Override
	public String getName() {
		return "GitLab CI";
	}
}
