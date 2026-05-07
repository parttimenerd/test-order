package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/**
 * Computes a fingerprint of project dependencies to detect when they change.
 * <p>
 * Supports two strategies:
 * <ul>
 * <li><b>Classpath fingerprint</b> (preferred) — hashes the resolved classpath
 * JARs by name + size + last-modified time. This catches SNAPSHOT updates,
 * transitive dependency changes, and version bumps even when build files don't
 * change textually.</li>
 * <li><b>Build-file fingerprint</b> (fallback) — hashes pom.xml / build.gradle
 * content. Used when the resolved classpath is not available.</li>
 * </ul>
 * When dependencies change, the test-order dependency index may become stale
 * because tests might now exercise new library classes or different code paths.
 * The fingerprint enables the auto-mode logic to schedule an early re-learn.
 */
public final class BuildFileFingerprint {

	private static final List<String> BUILD_FILE_NAMES = List.of("pom.xml", "build.gradle", "build.gradle.kts",
			"gradle.lockfile", "gradle/libs.versions.toml");

	private BuildFileFingerprint() {
	}

	/**
	 * Computes a fingerprint from the resolved test classpath entries. Uses file
	 * name + size + last-modified time for JARs, which catches:
	 * <ul>
	 * <li>SNAPSHOT dependency rebuilds (timestamp/size changes)</li>
	 * <li>Version bumps (file name changes)</li>
	 * <li>Added/removed transitive dependencies</li>
	 * </ul>
	 * Project output directories (non-JAR classpath entries) are excluded since
	 * source-code changes are tracked separately.
	 *
	 * @param classpathEntries
	 *            resolved classpath entries (JAR files and/or directories)
	 * @return hex-encoded SHA-256 fingerprint, or {@code null} if no JARs found
	 */
	public static String computeFromClasspath(Collection<Path> classpathEntries) {
		if (classpathEntries == null || classpathEntries.isEmpty()) {
			return null;
		}

		MessageDigest digest = sha256();
		if (digest == null)
			return null;

		boolean anyJar = false;
		// Sort for deterministic ordering regardless of classpath order
		List<Path> sorted = classpathEntries.stream()
				.filter(p -> p != null && Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar"))
				.sorted().toList();

		for (Path jar : sorted) {
			try {
				String name = jar.getFileName().toString();
				long size = Files.size(jar);
				long modified = Files.getLastModifiedTime(jar).toMillis();
				// Hash: name + size + lastModified — fast, no need to read file content
				digest.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				digest.update(longToBytes(size));
				digest.update(longToBytes(modified));
				anyJar = true;
			} catch (IOException ignored) {
				// Skip unreadable JARs
			}
		}

		return anyJar ? HexFormat.of().formatHex(digest.digest()) : null;
	}

	/**
	 * Fallback: computes a SHA-256 fingerprint of build declaration files (pom.xml,
	 * build.gradle, etc.) found under the given project root. Returns {@code null}
	 * if no build files exist or all are unreadable.
	 */
	public static String computeFromBuildFiles(Path projectRoot) {
		if (projectRoot == null || !Files.isDirectory(projectRoot)) {
			return null;
		}

		MessageDigest digest = sha256();
		if (digest == null)
			return null;

		boolean anyFile = false;
		for (String name : BUILD_FILE_NAMES) {
			Path buildFile = projectRoot.resolve(name);
			if (Files.isRegularFile(buildFile)) {
				try {
					byte[] content = Files.readAllBytes(buildFile);
					digest.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
					digest.update(content);
					anyFile = true;
				} catch (IOException ignored) {
					// Skip unreadable files
				}
			}
		}

		return anyFile ? HexFormat.of().formatHex(digest.digest()) : null;
	}

	/**
	 * Convenience: tries classpath fingerprint first, falls back to build files.
	 */
	public static String compute(Collection<Path> classpathEntries, Path projectRoot) {
		String fp = computeFromClasspath(classpathEntries);
		return fp != null ? fp : computeFromBuildFiles(projectRoot);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			return null;
		}
	}

	private static byte[] longToBytes(long value) {
		return new byte[] { (byte) (value >>> 56), (byte) (value >>> 48), (byte) (value >>> 40), (byte) (value >>> 32),
				(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value };
	}
}
