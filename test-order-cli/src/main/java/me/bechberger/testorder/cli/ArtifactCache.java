package me.bechberger.testorder.cli;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Manages caching of downloaded CI artifacts. Stores artifacts in
 * .test-order-cache/ with metadata (timestamp, source, checksum). Provides
 * cleanup and version tracking capabilities.
 */
public class ArtifactCache {
	private static final Logger logger = LoggerFactory.getLogger(ArtifactCache.class);
	private static final String CACHE_DIR = ".test-order-cache";
	private static final String METADATA_FILE = "artifacts.json";
	private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	private final Path cacheDir;
	private final Map<String, CacheEntry> metadata;

	public ArtifactCache() {
		this.cacheDir = Paths.get(CACHE_DIR);
		this.metadata = loadMetadata();
	}

	/**
	 * Save a downloaded artifact to cache
	 */
	public Path cacheArtifact(Path downloadedFile, String source, String artifactName) throws IOException {
		Files.createDirectories(cacheDir);

		// Generate cache filename with timestamp
		String timestamp = String.valueOf(System.currentTimeMillis());
		String filename = sanitizeFilename(artifactName) + "-" + timestamp + ".zip";
		Path cachedPath = cacheDir.resolve(filename);

		// Copy file to cache
		Files.copy(downloadedFile, cachedPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

		// Record metadata
		CacheEntry entry = new CacheEntry(filename, source, artifactName, Instant.now().toString(),
				calculateChecksum(cachedPath));
		metadata.put(filename, entry);
		saveMetadata();

		logger.info("Cached artifact: {} -> {}", artifactName, filename);
		return cachedPath;
	}

	/**
	 * Get list of all cached artifacts
	 */
	public List<CacheEntry> listCached() {
		return new ArrayList<>(metadata.values());
	}

	/**
	 * Get most recent cached artifact with given name
	 */
	public Optional<Path> getLatestCached(String artifactName) {
		return metadata.values().stream().filter(e -> e.getName().equals(artifactName))
				.sorted(Comparator.comparing(CacheEntry::getTimestamp).reversed()).findFirst()
				.map(e -> cacheDir.resolve(e.getFilename()));
	}

	/**
	 * Clean up old cached artifacts (keep last N versions)
	 */
	public void cleanup(int keepVersions) throws IOException {
		Map<String, List<CacheEntry>> byName = new LinkedHashMap<>();
		for (CacheEntry entry : metadata.values()) {
			byName.computeIfAbsent(entry.getName(), k -> new ArrayList<>()).add(entry);
		}

		for (List<CacheEntry> entries : byName.values()) {
			entries.sort(Comparator.comparing(CacheEntry::getTimestamp).reversed());
			for (int i = keepVersions; i < entries.size(); i++) {
				CacheEntry entry = entries.get(i);
				Path filePath = cacheDir.resolve(entry.getFilename());
				if (Files.exists(filePath)) {
					Files.delete(filePath);
					metadata.remove(entry.getFilename());
					logger.info("Deleted cached artifact: {}", entry.getFilename());
				}
			}
		}
		saveMetadata();
	}

	/**
	 * Clear all cached artifacts
	 */
	public void clearAll() throws IOException {
		if (Files.exists(cacheDir)) {
			try (Stream<Path> paths = Files.list(cacheDir)) {
				List<Path> filesToDelete = paths.filter(p -> !p.getFileName().toString().equals(METADATA_FILE))
						.collect(java.util.stream.Collectors.toList());

				for (Path p : filesToDelete) {
					try {
						Files.delete(p);
					} catch (IOException e) {
						logger.warn("Failed to delete: {}", p);
					}
				}
			}
		}
		metadata.clear();
		saveMetadata();
	}

	private String calculateChecksum(Path file) throws IOException {
		// Simple checksum using file size and first 1KB hash
		long size = Files.size(file);
		byte[] buffer = new byte[Math.min(1024, (int) size)];
		try (InputStream is = Files.newInputStream(file)) {
			is.read(buffer);
		}
		String hex = bytesToHex(buffer);
		// Ensure we have at least 8 chars for substring
		return String.format("%d-%s", size, hex.substring(0, Math.min(8, hex.length())));
	}

	private String bytesToHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	private String sanitizeFilename(String name) {
		return name.replaceAll("[^a-zA-Z0-9._-]", "-");
	}

	private Map<String, CacheEntry> loadMetadata() {
		Path metadataPath = cacheDir.resolve(METADATA_FILE);
		if (!Files.exists(metadataPath)) {
			return new LinkedHashMap<>();
		}

		try {
			String json = Files.readString(metadataPath);
			JsonObject jsonObj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
			Map<String, CacheEntry> entries = new LinkedHashMap<>();

			for (String key : jsonObj.keySet()) {
				JsonObject entryJson = jsonObj.getAsJsonObject(key);
				CacheEntry entry = new CacheEntry(entryJson.get("filename").getAsString(),
						entryJson.get("source").getAsString(), entryJson.get("name").getAsString(),
						entryJson.get("timestamp").getAsString(), entryJson.get("checksum").getAsString());
				entries.put(key, entry);
			}
			return entries;
		} catch (IOException e) {
			logger.warn("Failed to load cache metadata: {}", e.getMessage());
			return new LinkedHashMap<>();
		}
	}

	private void saveMetadata() throws IOException {
		Files.createDirectories(cacheDir);
		Path metadataPath = cacheDir.resolve(METADATA_FILE);
		String json = gson.toJson(metadata);
		Files.writeString(metadataPath, json);
	}

	/**
	 * Metadata for a cached artifact
	 */
	public static class CacheEntry {
		private final String filename;
		private final String source;
		private final String name;
		private final String timestamp;
		private final String checksum;

		public CacheEntry(String filename, String source, String name, String timestamp, String checksum) {
			this.filename = filename;
			this.source = source;
			this.name = name;
			this.timestamp = timestamp;
			this.checksum = checksum;
		}

		public String getFilename() {
			return filename;
		}
		public String getSource() {
			return source;
		}
		public String getName() {
			return name;
		}
		public String getTimestamp() {
			return timestamp;
		}
		public String getChecksum() {
			return checksum;
		}
	}
}
