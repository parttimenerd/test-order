package me.bechberger.testorder.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;

/**
 * Downloads dependency files from GitHub Actions artifacts.
 * Uses GitHub REST API to find and download the latest artifact from a workflow run.
 */
public class GitHubActionsDownloader implements DepDownloader {
    private static final Logger logger = LoggerFactory.getLogger(GitHubActionsDownloader.class);
    private static final String GH_API_BASE = "https://api.github.com";

    private final CiConfig.GithubConfig config;
    private final String token;
    private final OkHttpClient httpClient;

    public GitHubActionsDownloader(CiConfig.GithubConfig config, String token) {
        this.config = config;
        this.token = token;
        this.httpClient = new OkHttpClient();
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
            String workflowRunUrl = String.format(
                "%s/repos/%s/%s/actions/workflows/%s/runs?status=success&branch=%s&per_page=1",
                GH_API_BASE, config.getOwner(), config.getRepo(), config.getWorkflow(), config.getBranch()
            );

            JsonObject runResponse = fetchJson(workflowRunUrl);
            JsonArray runs = runResponse.getAsJsonArray("workflow_runs");

            if (runs == null || runs.size() == 0) {
                throw new DepDownloadException(
                    String.format("No successful workflow runs found for %s on branch %s",
                        config.getWorkflow(), config.getBranch())
                );
            }

            long runId = runs.get(0).getAsJsonObject().get("id").getAsLong();
            logger.info("Found latest workflow run: {}", runId);

            // Step 2: List artifacts from the run
            String artifactsUrl = String.format(
                "%s/repos/%s/%s/actions/runs/%d/artifacts",
                GH_API_BASE, config.getOwner(), config.getRepo(), runId
            );

            JsonObject artifactsResponse = fetchJson(artifactsUrl);
            JsonArray artifacts = artifactsResponse.getAsJsonArray("artifacts");

            if (artifacts == null || artifacts.size() == 0) {
                throw new DepDownloadException(
                    String.format("No artifacts found in workflow run %d", runId)
                );
            }

            // Step 3: Find the artifact we want
            long artifactId = -1;
            for (JsonElement artifactElem : artifacts) {
                JsonObject artifact = artifactElem.getAsJsonObject();
                if (config.getArtifactName().equals(artifact.get("name").getAsString())) {
                    artifactId = artifact.get("id").getAsLong();
                    break;
                }
            }

            if (artifactId == -1) {
                throw new DepDownloadException(
                    String.format("Artifact '%s' not found in workflow run %d",
                        config.getArtifactName(), runId)
                );
            }

            logger.info("Found artifact: {} (ID: {})", config.getArtifactName(), artifactId);

            // Step 4: Download the artifact
            String downloadUrl = String.format(
                "%s/repos/%s/%s/actions/artifacts/%d/zip",
                GH_API_BASE, config.getOwner(), config.getRepo(), artifactId
            );

            return downloadFile(downloadUrl, outputPath);

        } catch (DepDownloadException e) {
            throw e;
        } catch (Exception e) {
            throw new DepDownloadException("Failed to download from GitHub Actions: " + e.getMessage(), e);
        }
    }

    private JsonObject fetchJson(String url) throws IOException, DepDownloadException {
        Request.Builder requestBuilder = new Request.Builder().url(url);

        if (token != null && !token.isEmpty()) {
            requestBuilder.header("Authorization", "token " + token);
        }

        Request request = requestBuilder.build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new DepDownloadException(
                    String.format("GitHub API request failed: %d %s", response.code(), response.message())
                );
            }

            if (response.body() == null) {
                throw new DepDownloadException("GitHub API response body is empty");
            }

            String body = response.body().string();
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    private boolean downloadFile(String downloadUrl, Path outputPath) throws IOException, DepDownloadException {
        Request.Builder requestBuilder = new Request.Builder().url(downloadUrl);

        if (token != null && !token.isEmpty()) {
            requestBuilder.header("Authorization", "token " + token);
        }

        Request request = requestBuilder.build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new DepDownloadException(
                    String.format("Download failed: %d %s", response.code(), response.message())
                );
            }

            if (response.body() == null) {
                throw new DepDownloadException("Download response body is empty");
            }

            try (InputStream input = response.body().byteStream();
                 FileOutputStream output = new FileOutputStream(outputPath.toFile())) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
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
