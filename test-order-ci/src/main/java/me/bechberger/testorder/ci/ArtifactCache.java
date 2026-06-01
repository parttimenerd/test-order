package me.bechberger.testorder.ci;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.HexFormat;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.PrettyPrinter;

/**
 * Manages caching of downloaded CI artifacts. Stores artifacts in
 * .test-order-cache/ with metadata (timestamp, source, checksum). Provides
 * cleanup and version tracking capabilities.
 */
public class ArtifactCache {
	private static final Logger logger = LoggerFactory.getLogger(ArtifactCache.class);
	private static final String CACHE_DIR = ".test-order-cache";
	private static final String METADATA_FILE = "artifacts.json";

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
	 * Get most recent cached artifact with given name.
	 */
	public Optional<Path> getLatestCached(String artifactName) {
		return getLatestCached(artifactName, null);
	}

	/**
	 * Get most recent cached artifact with given name that is not older than
	 * {@code maxAge}. Pass {@code null} for no TTL check.
	 */
	public Optional<Path> getLatestCached(String artifactName, Duration maxAge) {
		return metadata.values().stream().filter(e -> e.getName().equals(artifactName))
				.filter(e -> maxAge == null || !isTooOld(e, maxAge))
				.sorted(Comparator.comparing(CacheEntry::getTimestamp).reversed()).findFirst()
				.map(e -> cacheDir.resolve(e.getFilename())).filter(Files::exists);
	}

	private boolean isTooOld(CacheEntry entry, Duration maxAge) {
		try {
			return Instant.now().isAfter(Instant.parse(entry.getTimestamp()).plus(maxAge));
		} catch (java.time.format.DateTimeParseException e) {
			logger.debug("Could not parse timestamp '{}': {}", entry.getTimestamp(), e.getMessage());
			return false;
		}
	}

	/**
	 * Delete all cached artifacts older than {@code maxAge}.
	 */
	public void cleanupOlderThan(Duration maxAge) throws IOException {
		Instant cutoff = Instant.now().minus(maxAge);
		List<String> toRemove = new ArrayList<>();
		for (Map.Entry<String, CacheEntry> e : metadata.entrySet()) {
			try {
				if (Instant.parse(e.getValue().getTimestamp()).isBefore(cutoff)) {
					Files.deleteIfExists(cacheDir.resolve(e.getValue().getFilename()));
					toRemove.add(e.getKey());
					logger.info("Deleted stale artifact: {}", e.getValue().getFilename());
				}
			} catch (Exception ex) {
				logger.warn("Could not parse timestamp for {}: {}", e.getKey(), ex.getMessage());
			}
		}
		toRemove.forEach(metadata::remove);
		if (!toRemove.isEmpty()) {
			saveMetadata();
		}
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
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream is = Files.newInputStream(file)) {
				byte[] buf = new byte[8192];
				int n;
				while ((n = is.read(buf)) != -1) {
					digest.update(buf, 0, n);
				}
			}
			return "sha256:" + HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-256 not available", e);
		}
	}

	private String sanitizeFilename(String name) {
		return name.replaceAll("[^a-zA-Z0-9._-]", "-");
	}

	@SuppressWarnings("unchecked")
	private Map<String, CacheEntry> loadMetadata() {
		Path metadataPath = cacheDir.resolve(METADATA_FILE);
		if (!Files.exists(metadataPath)) {
			return new LinkedHashMap<>();
		}

		try {
			String json = Files.readString(metadataPath);
			Object parsed = JSONParser.parse(json);
			if (!(parsed instanceof Map)) {
				logger.warn("Cache metadata has unexpected format, resetting");
				return new LinkedHashMap<>();
			}
			Map<String, Object> jsonObj = (Map<String, Object>) parsed;
			Map<String, CacheEntry> entries = new LinkedHashMap<>();

			for (Map.Entry<String, Object> e : jsonObj.entrySet()) {
				if (!(e.getValue() instanceof Map)) {
					continue;
				}
				Map<String, Object> entryJson = (Map<String, Object>) e.getValue();
				// Guard against missing fields in the JSON — treat absent keys as empty string
				// rather than storing null, which would NPE on getName()/getFilename() callers.
				CacheEntry entry = new CacheEntry(jsonString(entryJson, "filename"), jsonString(entryJson, "source"),
						jsonString(entryJson, "name"), jsonString(entryJson, "timestamp"),
						jsonString(entryJson, "checksum"));
				if (entry.getFilename().isEmpty() || entry.getName().isEmpty()) {
					logger.warn("Skipping cache entry with missing filename or name: key={}", e.getKey());
					continue;
				}
				entries.put(e.getKey(), entry);
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
		// Convert CacheEntry map to plain maps for JSON serialization
		Map<String, Object> jsonMap = new LinkedHashMap<>();
		for (Map.Entry<String, CacheEntry> e : metadata.entrySet()) {
			CacheEntry ce = e.getValue();
			Map<String, Object> entryMap = new LinkedHashMap<>();
			entryMap.put("filename", ce.getFilename());
			entryMap.put("source", ce.getSource());
			entryMap.put("name", ce.getName());
			entryMap.put("timestamp", ce.getTimestamp());
			entryMap.put("checksum", ce.getChecksum());
			jsonMap.put(e.getKey(), entryMap);
		}
		String json = PrettyPrinter.prettyPrint(jsonMap);
		// Write atomically via temp file to avoid corruption on crash
		Path tempFile = cacheDir.resolve(METADATA_FILE + ".tmp");
		try {
			Files.writeString(tempFile, json);
			Files.move(tempFile, metadataPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.deleteIfExists(tempFile);
			throw e;
		}
	}

	private static String jsonString(Map<String, Object> map, String key) {
		Object val = map.get(key);
		return val instanceof String s ? s : "";
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
