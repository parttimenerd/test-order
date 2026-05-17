package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/**
 * Resolves a project's {@code .test-order} storage directory inside the user's
 * home folder ({@code ~/.test-order/<project-name>-<hash>/}) instead of in the
 * project tree. This makes test-order data survive {@code git clean -fdx}.
 * <p>
 * Each project directory stores a {@code metadata.properties} file with:
 * <ul>
 * <li>{@code projectPath} — the canonical path the project was first linked
 * from</li>
 * <li>{@code projectName} — human-readable project name (Maven artifactId,
 * directory name)</li>
 * <li>{@code createdAt} — ISO timestamp</li>
 * </ul>
 * <p>
 * <b>Move detection:</b> If the project directory has moved, the resolver
 * checks for orphaned entries with the same project name and relinks them
 * (updating the stored path).
 * <p>
 * <b>Conflict detection:</b> If a different project already occupies the
 * computed slot, the resolver falls back to a new slot with a longer hash
 * suffix.
 */
public final class HomeStorageResolver {

	private static final String HOME_DIR_NAME = ".test-order";
	private static final String METADATA_FILE = "metadata.properties";
	private static final String PROP_PROJECT_PATH = "projectPath";
	private static final String PROP_PROJECT_NAME = "projectName";
	private static final String PROP_CREATED_AT = "createdAt";

	private final Path homeBase;

	public HomeStorageResolver() {
		this(Path.of(System.getProperty("user.home")).resolve(HOME_DIR_NAME));
	}

	/** Visible for testing. */
	HomeStorageResolver(Path homeBase) {
		this.homeBase = homeBase;
	}

	/**
	 * Resolves (and creates) the home-based storage directory for the given
	 * project.
	 *
	 * @param projectRoot
	 *            canonical project root directory
	 * @param projectName
	 *            human-readable name (e.g. Maven artifactId)
	 * @param log
	 *            plugin logger (may be null for silent operation)
	 * @return the storage directory under {@code ~/.test-order/}
	 */
	public Path resolve(Path projectRoot, String projectName, PluginLog log) throws IOException {
		Path canonicalRoot = projectRoot.toAbsolutePath().normalize();
		String dirName = buildDirName(projectName, canonicalRoot);
		Path candidate = homeBase.resolve(dirName);

		// Fast path: directory exists and metadata matches this project
		if (Files.isDirectory(candidate)) {
			Properties meta = loadMetadata(candidate);
			if (meta != null) {
				String storedPath = meta.getProperty(PROP_PROJECT_PATH, "");
				if (storedPath.equals(canonicalRoot.toString())) {
					return candidate; // exact match
				}
				// Same slot but different path — could be a project move
				String storedName = meta.getProperty(PROP_PROJECT_NAME, "");
				if (storedName.equals(projectName) && isOrphaned(Path.of(storedPath))) {
					// The old location no longer exists → treat as a move
					if (log != null) {
						log.info("[test-order] Project appears to have moved from " + storedPath + " → " + canonicalRoot
								+ ". Relinking home storage.");
					}
					updateMetadataPath(candidate, canonicalRoot);
					return candidate;
				}
				// True conflict: different project with same name at a different path
				if (log != null) {
					log.warn("[test-order] Home storage conflict: '" + dirName + "' is already used by project at "
							+ storedPath + ". Creating a separate storage directory.");
				}
				candidate = resolveConflict(projectName, canonicalRoot);
			}
		}

		// Check for orphaned entries with the same project name (project was moved)
		Path orphan = findOrphanedEntry(projectName, canonicalRoot);
		if (orphan != null) {
			if (log != null) {
				Properties meta = loadMetadata(orphan);
				String oldPath = meta != null ? meta.getProperty(PROP_PROJECT_PATH, "?") : "?";
				log.info("[test-order] Found existing data for '" + projectName + "' (previously at " + oldPath
						+ "). Relinking to " + canonicalRoot + ".");
			}
			updateMetadataPath(orphan, canonicalRoot);
			return orphan;
		}

		// New project — create directory and write metadata
		Files.createDirectories(candidate);
		writeMetadata(candidate, canonicalRoot, projectName);
		if (log != null) {
			log.info("[test-order] Storing data in home directory: " + candidate);
		}
		return candidate;
	}

	// ── Internal helpers ─────────────────────────────────────────────

	/**
	 * Builds a directory name like {@code myproject-a1b2c3d4} from the project name
	 * and an 8-char hash of the canonical path.
	 */
	static String buildDirName(String projectName, Path canonicalRoot) {
		String safeName = sanitize(projectName);
		String hash = shortHash(canonicalRoot.toString(), 8);
		return safeName + "-" + hash;
	}

	private Path resolveConflict(String projectName, Path canonicalRoot) throws IOException {
		// Use a longer hash (16 chars) to avoid collision
		String safeName = sanitize(projectName);
		String longHash = shortHash(canonicalRoot.toString(), 16);
		Path resolved = homeBase.resolve(safeName + "-" + longHash);
		Files.createDirectories(resolved);
		writeMetadata(resolved, canonicalRoot, projectName);
		return resolved;
	}

	/**
	 * Scans ~/.test-order/ for entries whose stored project path no longer exists
	 * but whose project name matches. Returns the first match, or null.
	 */
	private Path findOrphanedEntry(String projectName, Path currentRoot) throws IOException {
		if (!Files.isDirectory(homeBase)) {
			return null;
		}
		try (DirectoryStream<Path> dirs = Files.newDirectoryStream(homeBase, Files::isDirectory)) {
			for (Path dir : dirs) {
				Properties meta = loadMetadata(dir);
				if (meta == null)
					continue;
				String storedName = meta.getProperty(PROP_PROJECT_NAME, "");
				String storedPath = meta.getProperty(PROP_PROJECT_PATH, "");
				if (storedName.equals(projectName) && !storedPath.equals(currentRoot.toString())
						&& isOrphaned(Path.of(storedPath))) {
					return dir;
				}
			}
		}
		return null;
	}

	private static boolean isOrphaned(Path projectPath) {
		// A project is orphaned if the directory no longer exists, or if it
		// exists but no longer contains a build file (pom.xml, build.gradle, etc.)
		if (!Files.isDirectory(projectPath)) {
			return true;
		}
		return !Files.exists(projectPath.resolve("pom.xml")) && !Files.exists(projectPath.resolve("build.gradle"))
				&& !Files.exists(projectPath.resolve("build.gradle.kts"));
	}

	private static Properties loadMetadata(Path dir) {
		Path metaFile = dir.resolve(METADATA_FILE);
		if (!Files.isRegularFile(metaFile)) {
			return null;
		}
		Properties props = new Properties();
		try (var reader = Files.newBufferedReader(metaFile, StandardCharsets.UTF_8)) {
			props.load(reader);
			return props;
		} catch (IOException e) {
			return null;
		}
	}

	private static void writeMetadata(Path dir, Path projectRoot, String projectName) throws IOException {
		Properties props = new Properties();
		props.setProperty(PROP_PROJECT_PATH, projectRoot.toString());
		props.setProperty(PROP_PROJECT_NAME, projectName);
		props.setProperty(PROP_CREATED_AT, java.time.Instant.now().toString());
		Path metaFile = dir.resolve(METADATA_FILE);
		try (var writer = Files.newBufferedWriter(metaFile, StandardCharsets.UTF_8)) {
			props.store(writer, "test-order home storage metadata");
		}
	}

	private static void updateMetadataPath(Path dir, Path newProjectRoot) throws IOException {
		Properties props = loadMetadata(dir);
		if (props == null) {
			props = new Properties();
		}
		props.setProperty(PROP_PROJECT_PATH, newProjectRoot.toString());
		Path metaFile = dir.resolve(METADATA_FILE);
		try (var writer = Files.newBufferedWriter(metaFile, StandardCharsets.UTF_8)) {
			props.store(writer, "test-order home storage metadata");
		}
	}

	/**
	 * Returns the first N hex characters of the SHA-256 of the input.
	 */
	static String shortHash(String input, int length) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, length);
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is always available in the JDK
			throw new RuntimeException(e);
		}
	}

	/**
	 * Replaces characters unsafe for directory names with dashes and lowercases.
	 */
	static String sanitize(String name) {
		if (name == null || name.isBlank()) {
			return "unknown";
		}
		return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "-").replaceAll("-{2,}", "-")
				.replaceAll("^-|-$", "");
	}

	/**
	 * Lists all project entries stored under {@code ~/.test-order/} with their
	 * metadata. Useful for diagnostic commands.
	 */
	public List<StoredProject> listProjects() throws IOException {
		List<StoredProject> projects = new ArrayList<>();
		if (!Files.isDirectory(homeBase)) {
			return projects;
		}
		try (DirectoryStream<Path> dirs = Files.newDirectoryStream(homeBase, Files::isDirectory)) {
			for (Path dir : dirs) {
				Properties meta = loadMetadata(dir);
				if (meta != null) {
					projects.add(new StoredProject(dir, meta.getProperty(PROP_PROJECT_NAME, ""),
							meta.getProperty(PROP_PROJECT_PATH, ""), meta.getProperty(PROP_CREATED_AT, ""),
							isOrphaned(Path.of(meta.getProperty(PROP_PROJECT_PATH, "")))));
				}
			}
		}
		return projects;
	}

	/**
	 * A project entry stored in the home directory.
	 */
	public record StoredProject(Path storageDir, String projectName, String projectPath, String createdAt,
			boolean orphaned) {
	}
}
