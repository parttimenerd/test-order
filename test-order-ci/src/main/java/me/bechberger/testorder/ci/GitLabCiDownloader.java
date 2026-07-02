package me.bechberger.testorder.ci;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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
		String baseUrl = config.getBaseUrl().replaceAll("/+$", "");
		String encodedBranch = URLEncoder.encode(config.getBranch(), StandardCharsets.UTF_8);
		String pipelinesUrl = String.format(
				"%s/api/v4/projects/%s/pipelines?ref=%s&status=success&order_by=id&sort=desc&per_page=1", baseUrl,
				projectId, encodedBranch);

		List<Object> pipelines = fetchJsonArray(pipelinesUrl);
		if (pipelines.isEmpty()) {
			throw new DepDownloadException(String.format("No successful pipelines found for project %s on branch %s",
					config.getProjectId(), config.getBranch()));
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> firstPipeline = (Map<String, Object>) pipelines.get(0);
		Object pidObj = firstPipeline.get("id");
		if (!(pidObj instanceof Number)) {
			throw new DepDownloadException("Unexpected GitLab API response: pipeline missing numeric 'id'");
		}
		long pipelineId = ((Number) pidObj).longValue();
		logger.info("Found pipeline: {}", pipelineId);

		// Step 2: find the job by name inside that pipeline — paginate since a
		// pipeline can have more than 100 jobs and GitLab caps per_page at 100.
		String jobsBaseUrl = String.format("%s/api/v4/projects/%s/pipelines/%d/jobs?scope[]=success&per_page=100",
				baseUrl, projectId, pipelineId);

		long jobId = -1;
		outer : for (int page = 1;; page++) {
			String jobsUrl = jobsBaseUrl + "&page=" + page;
			List<Object> jobs = fetchJsonArray(jobsUrl);
			if (jobs.isEmpty())
				break;
			for (Object elem : jobs) {
				@SuppressWarnings("unchecked")
				Map<String, Object> job = (Map<String, Object>) elem;
				if (config.getJobName().equals(job.get("name"))) {
					Object jidObj = job.get("id");
					if (!(jidObj instanceof Number)) {
						throw new DepDownloadException("Unexpected GitLab API response: job missing numeric 'id'");
					}
					jobId = ((Number) jidObj).longValue();
					break outer;
				}
			}
			// If fewer than 100 results were returned, we have reached the last page
			if (jobs.size() < 100)
				break;
		}

		if (jobId == -1) {
			throw new DepDownloadException(String.format("Job '%s' not found in pipeline %d (project %s)",
					config.getJobName(), pipelineId, config.getProjectId()));
		}
		logger.info("Found job: {} (ID: {})", config.getJobName(), jobId);

		// Step 3: download the artifact archive for that job
		String artifactsUrl = String.format("%s/api/v4/projects/%s/jobs/%d/artifacts", baseUrl, projectId, jobId);
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
			long contentLength = response.body().contentLength();
			if (contentLength > MAX_JSON_BYTES) {
				throw new DepDownloadException("GitLab API response too large: " + contentLength + " bytes exceeds "
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
								"GitLab API response exceeded " + MAX_JSON_BYTES + " byte limit during streaming");
					}
					buf.write(chunk, 0, n);
				}
				bodyBytes = buf.toByteArray();
			}
			Object parsed = JSONParser.parse(new String(bodyBytes, StandardCharsets.UTF_8));
			if (!(parsed instanceof List)) {
				throw new DepDownloadException("GitLab API returned unexpected JSON structure (expected array)");
			}
			return (List<Object>) parsed;
		}
	}

	private static final long MAX_JSON_BYTES = 10L * 1024 * 1024;
	private static final long MAX_DOWNLOAD_BYTES = 500L * 1024 * 1024;
	private static final int MAX_ZIP_ENTRIES = 1_000;

	/**
	 * Downloads the artifact ZIP from the GitLab API and extracts the first entry
	 * whose name ends with {@code .lz4} (the dependency index). The GitLab
	 * artifacts API always returns a ZIP archive, not the raw file.
	 */
	private boolean downloadFile(String url, Path outputPath) throws IOException, DepDownloadException {
		try (Response response = httpClient.newCall(buildRequest(url)).execute()) {
			if (!response.isSuccessful()) {
				throw new DepDownloadException(
						String.format("Artifact download failed: %d %s", response.code(), response.message()));
			}
			if (response.body() == null) {
				throw new DepDownloadException("Artifact response body is empty");
			}
			try (InputStream raw = response.body().byteStream();
					ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
				ZipEntry entry;
				int entryCount = 0;
				while ((entry = zip.getNextEntry()) != null) {
					if (++entryCount > MAX_ZIP_ENTRIES) {
						throw new DepDownloadException(
								"Artifact ZIP contains too many entries (limit: " + MAX_ZIP_ENTRIES + ")");
					}
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
				throw new DepDownloadException("No .lz4 file found inside the GitLab artifact ZIP. "
						+ "Ensure the artifact was uploaded with the dependency index (test-dependencies.lz4).");
			}
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
