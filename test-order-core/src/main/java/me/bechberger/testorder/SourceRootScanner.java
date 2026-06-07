package me.bechberger.testorder;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Walks a Maven source root (e.g. {@code src/main/java}) and yields the
 * top-level FQN of every {@code .java} file found, derived from its path
 * relative to the root.
 *
 * <p>
 * Used by the lifecycle participant to enumerate all classes across the reactor
 * before any module instruments, so a single reactor-wide
 * {@code class-id-map.bin} can be pre-allocated.
 *
 * <p>
 * This is purely structural: it doesn't parse {@code package} declarations. It
 * assumes the standard Maven layout where directory structure mirrors package
 * structure. Files where this assumption doesn't hold (rare) will be registered
 * under a slightly-wrong FQN, but the per-module prepare's
 * {@code getOrRegisterClass} will catch the real FQN at instrumentation time.
 *
 * <p>
 * Skips {@code package-info.java} and {@code module-info.java} (placeholders,
 * not real classes), hidden files and directories (dot-prefixed), and files
 * whose path components would produce malformed FQNs (e.g. starting with a
 * dot).
 *
 * <p>
 * Robust against symlink cycles, missing files mid-scan, and per-file
 * permission errors: a problem with one file does NOT abort the entire scan.
 */
public final class SourceRootScanner {

	private SourceRootScanner() {
	}

	public static Set<String> scanFqns(Path sourceRoot) {
		Set<String> result = new LinkedHashSet<>();
		if (sourceRoot == null) {
			return result;
		}
		Path normalized;
		try {
			normalized = sourceRoot.toAbsolutePath().normalize();
		} catch (java.nio.file.InvalidPathException | SecurityException e) {
			return result;
		}
		if (!Files.isDirectory(normalized)) {
			return result;
		}
		Path root = normalized;
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					// Skip hidden directories (.git, .idea, etc.) — except the source root
					// itself, which the caller may legitimately have placed under a dot path.
					if (!dir.equals(root)) {
						String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
						if (dirName.startsWith(".")) {
							return FileVisitResult.SKIP_SUBTREE;
						}
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					try {
						String name = file.getFileName().toString();
						if (!name.endsWith(".java")) {
							return FileVisitResult.CONTINUE;
						}
						if ("package-info.java".equals(name) || "module-info.java".equals(name)) {
							return FileVisitResult.CONTINUE;
						}
						// Hidden file (e.g. ".foo.java") would yield a malformed FQN; skip.
						if (name.startsWith(".")) {
							return FileVisitResult.CONTINUE;
						}
						Path relative = root.relativize(file);
						String relStr = relative.toString();
						if (relStr.length() <= 5) {
							// File is exactly ".java" — no class name, skip.
							return FileVisitResult.CONTINUE;
						}
						String withoutExt = relStr.substring(0, relStr.length() - 5);
						String fqn = withoutExt.replace(java.io.File.separatorChar, '.').replace('/', '.');
						if (fqn.isEmpty() || fqn.startsWith(".") || fqn.contains("..")) {
							// Defensive: any path component that would produce a malformed FQN
							// (leading dot, double-dot from a symlink up-traversal). Skip.
							return FileVisitResult.CONTINUE;
						}
						result.add(fqn);
					} catch (RuntimeException ignored) {
						// Best-effort: malformed paths or transient I/O don't abort the scan.
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) {
					// Single-file failure (broken symlink, denied permission) does not
					// abort the whole scan.
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException | SecurityException e) {
			// Best-effort: catastrophic failure (e.g. root vanished mid-walk) returns
			// what we collected so far.
		}
		return result;
	}
}
