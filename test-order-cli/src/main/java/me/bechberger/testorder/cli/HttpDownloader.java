package me.bechberger.testorder.cli;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;

/**
 * Downloads dependency files from generic HTTP endpoints.
 * Supports basic and bearer token authentication.
 */
public class HttpDownloader implements DepDownloader {
    private static final Logger logger = LoggerFactory.getLogger(HttpDownloader.class);

    private final CiConfig.HttpConfig config;
    private final String token;
    private final OkHttpClient httpClient;

    public HttpDownloader(CiConfig.HttpConfig config, String token) {
        this.config = config;
        this.token = token;
        this.httpClient = new OkHttpClient();
    }

    @Override
    public void validate() throws DepDownloadException {
        if (!config.isValid()) {
            throw new DepDownloadException("Invalid HTTP config: missing url");
        }
        if (config.getUrl() == null || config.getUrl().isEmpty()) {
            throw new DepDownloadException("HTTP URL is required");
        }
        try {
            new java.net.URL(config.getUrl());
        } catch (java.net.MalformedURLException e) {
            throw new DepDownloadException("Invalid URL: " + config.getUrl(), e);
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

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new DepDownloadException(
                    String.format("HTTP request failed: %d %s", response.code(), response.message())
                );
            }

            if (response.body() == null) {
                throw new DepDownloadException("Response body is empty");
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
        return "HTTP";
    }
}
