package me.bechberger.testorder.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Coordinator for downloading CI dependency files.
 * Handles config loading, downloader selection, caching, and error handling.
 */
public class CiDepDownloadManager {
    private static final Logger logger = LoggerFactory.getLogger(CiDepDownloadManager.class);

    private final CiConfig config;
    private final ArtifactCache cache;
    private final String githubToken;
    private final String httpToken;

    public CiDepDownloadManager(CiConfig config) {
        this.config = config;
        this.cache = new ArtifactCache();
        this.githubToken = getEnv("GITHUB_TOKEN", "GH_TOKEN");
        this.httpToken = config.getHttp() != null ? getEnv(config.getHttp().getTokenEnv()) : null;
    }

    /**
     * Download latest CI dependency file, using cache when possible
     */
    public Path download() throws IOException, DepDownloader.DepDownloadException {
        String artifactName = config.getGithub() != null ? config.getGithub().getArtifactName() : "deps";

        // Check if cached version exists
        Optional<Path> cached = cache.getLatestCached(artifactName);
        if (cached.isPresent()) {
            logger.info("Using cached artifact: {}", cached.get());
            return cached.get();
        }

        // Download fresh copy
        DepDownloader downloader = selectDownloader();
        Path tempFile = Files.createTempFile("ci-deps-", ".zip");

        try {
            downloader.validate();
            downloader.download(tempFile);

            // Cache the downloaded file
            Path cachedPath = cache.cacheArtifact(tempFile, downloader.getName(), artifactName);
            return cachedPath;
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * List cached artifacts
     */
    public void listCache() {
        var entries = cache.listCached();
        if (entries.isEmpty()) {
            logger.info("No cached artifacts");
            return;
        }

        logger.info("Cached artifacts:");
        for (var entry : entries) {
            logger.info("  {} - {} ({})", entry.getName(), entry.getTimestamp(), entry.getSource());
        }
    }

    /**
     * Clean old cached artifacts
     */
    public void cleanupCache(int keepVersions) throws IOException {
        cache.cleanup(keepVersions);
        logger.info("Cleaned up cache (keeping {} versions)", keepVersions);
    }

    /**
     * Clear all cache
     */
    public void clearCache() throws IOException {
        cache.clearAll();
        logger.info("Cleared all cached artifacts");
    }

    /**
     * Select appropriate downloader based on config
     */
    private DepDownloader selectDownloader() throws DepDownloader.DepDownloadException {
        if (config.getGithub() != null) {
            return new GitHubActionsDownloader(config.getGithub(), githubToken);
        } else if (config.getHttp() != null) {
            return new HttpDownloader(config.getHttp(), httpToken);
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
}
