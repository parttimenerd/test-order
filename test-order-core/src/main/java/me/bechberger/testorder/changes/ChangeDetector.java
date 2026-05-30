package me.bechberger.testorder.changes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import me.bechberger.testorder.TestOrderLogger;

/**
 * Unified change detection interface supporting multiple modes.
 */
public class ChangeDetector {

	public enum Mode {
		SINCE_LAST_RUN, SINCE_LAST_COMMIT, UNCOMMITTED, EXPLICIT;

		/**
		 * Parses a mode string, accepting both hyphenated (since-last-run) and
		 * underscore (SINCE_LAST_RUN) formats, case-insensitively.
		 */
		public static Mode parse(String value) {
			if (value == null || value.isBlank())
				return SINCE_LAST_RUN;
			String normalized = value.toUpperCase().replace('-', '_');
			try {
				return valueOf(normalized);
			} catch (IllegalArgumentException e) {
				String hint = "AUTO".equals(normalized)
						? " (note: 'auto' is resolved by the Maven/Gradle plugin layer and is not available in the standalone CLI)"
						: "";
				throw new IllegalArgumentException("Invalid change detection mode '" + value + "'. Valid modes: "
						+ "since-last-run, since-last-commit, uncommitted, explicit" + hint);
			}
		}
	}

	/**
	 * Detects changed classes using the specified mode. For SINCE_LAST_COMMIT mode,
	 * also merges in any uncommitted changes so that staged/unstaged work is never
	 * missed. Updates the hash snapshot for SINCE_LAST_RUN mode.
	 */
	public static Set<String> detect(Mode mode, Path projectRoot, Path sourceRoot, Path hashFile,
			String explicitClasses) throws IOException {
		return doDetect(mode, projectRoot, sourceRoot, hashFile, explicitClasses, false);
	}

	/**
	 * Detects changes without updating the hash snapshot. Use this for read-only
	 * queries (e.g., show-order).
	 */
	public static Set<String> detectReadOnly(Mode mode, Path projectRoot, Path sourceRoot, Path hashFile,
			String explicitClasses) throws IOException {
		return doDetect(mode, projectRoot, sourceRoot, hashFile, explicitClasses, true);
	}

	private static Set<String> doDetect(Mode mode, Path projectRoot, Path sourceRoot, Path hashFile,
			String explicitClasses, boolean readOnly) throws IOException {
		Path absoluteSourceRoot = projectRoot.resolve(sourceRoot);
		try {
			return switch (mode) {
				case SINCE_LAST_RUN -> detectSinceLastRun(absoluteSourceRoot, hashFile, !readOnly);
				case SINCE_LAST_COMMIT, UNCOMMITTED -> {
					Path gitRoot = findGitRoot(projectRoot);
					String gitPrefix = toGitPrefix(projectRoot, sourceRoot, gitRoot);
					yield switch (mode) {
						case SINCE_LAST_COMMIT -> mergeUncommitted(
								GitChangeDetector.changedSinceLastCommit(gitRoot, gitPrefix), gitRoot, gitPrefix);
						case UNCOMMITTED -> GitChangeDetector.uncommittedChanges(gitRoot, gitPrefix);
						default -> throw new AssertionError();
					};
				}
				case EXPLICIT -> parseExplicit(explicitClasses);
			};
		} catch (IOException e) {
			if (mode == Mode.SINCE_LAST_COMMIT || mode == Mode.UNCOMMITTED) {
				if (hashFile == null) {
					TestOrderLogger.warn(
							"Git-based change detection failed and no hash file available for fallback: {}",
							e.getMessage());
					throw new IOException("Git-based change detection failed and no hash file available for fallback",
							e);
				}
				TestOrderLogger.warn("Git-based change detection failed, falling back to hash-based detection: {}",
						e.getMessage());
				return detectSinceLastRun(absoluteSourceRoot, hashFile, !readOnly);
			}
			throw e;
		}
	}

	/** Best-effort merge of uncommitted changes on top of a base result. */
	private static Set<String> mergeUncommitted(Set<String> base, Path gitRoot, String gitPrefix) {
		try {
			Set<String> uncommitted = GitChangeDetector.uncommittedChanges(gitRoot, gitPrefix);
			if (!uncommitted.isEmpty()) {
				Set<String> merged = new TreeSet<>(base);
				merged.addAll(uncommitted);
				return merged;
			}
		} catch (IOException e) {
			// best-effort — don't fail the primary detection
		}
		return base;
	}

	/**
	 * Converts a source root to a git-relative prefix path. Uses {@code git
	 * rev-parse --show-toplevel} to find the real git root, so that nested projects
	 * (e.g. {@code samples/sample-basic}) produce correct prefixes like
	 * {@code samples/sample-basic/src/main/java/} instead of just
	 * {@code src/main/java/}.
	 */
	static String toGitPrefix(Path projectRoot, Path sourceRoot) {
		return toGitPrefix(projectRoot, sourceRoot, findGitRoot(projectRoot));
	}

	/**
	 * Converts a source root to a git-relative prefix path using the provided git
	 * root, avoiding a redundant {@code git rev-parse} subprocess.
	 */
	static String toGitPrefix(Path projectRoot, Path sourceRoot, Path gitRoot) {
		Path absoluteSource;
		if (sourceRoot.isAbsolute()) {
			absoluteSource = sourceRoot;
		} else {
			absoluteSource = projectRoot.resolve(sourceRoot);
		}
		Path relative;
		try {
			relative = gitRoot.relativize(absoluteSource.toAbsolutePath().normalize());
		} catch (IllegalArgumentException e) {
			// Fallback: if git root and source are on different roots (e.g. different
			// Windows drives)
			try {
				relative = sourceRoot.isAbsolute() ? projectRoot.relativize(sourceRoot) : sourceRoot;
			} catch (IllegalArgumentException e2) {
				// Last resort: use sourceRoot as-is
				relative = sourceRoot;
			}
		}
		String prefix = relative.toString().replace('\\', '/');
		if (prefix.isBlank()) {
			return "";
		}
		if (!prefix.endsWith("/"))
			prefix += "/";
		return prefix;
	}

	/**
	 * Finds the git repository root via {@code git rev-parse --show-toplevel}.
	 * Falls back to projectRoot if git is unavailable.
	 * <p>
	 * Cached per projectRoot to avoid repeated subprocess calls in reactor builds
	 * where the git root is the same for all modules.
	 */
	private static final ConcurrentHashMap<Path, Path> gitRootCache = new ConcurrentHashMap<>();

	private static Path findGitRoot(Path projectRoot) {
		Path normalized = projectRoot.toAbsolutePath().normalize();
		return gitRootCache.computeIfAbsent(normalized, ChangeDetector::resolveGitRoot);
	}

	private static Path resolveGitRoot(Path projectRoot) {
		try {
			ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--show-toplevel");
			pb.directory(projectRoot.toFile());
			pb.redirectErrorStream(true);
			Process process = pb.start();
			try {
				String output;
				try (var is = process.getInputStream()) {
					output = new String(is.readAllBytes()).trim();
				}
				if (!process.waitFor(GitTimeout.seconds(), java.util.concurrent.TimeUnit.SECONDS)) {
					process.destroyForcibly();
					return projectRoot.toAbsolutePath().normalize();
				}
				if (process.exitValue() == 0 && !output.isEmpty()) {
					return Path.of(output).toAbsolutePath().normalize();
				}
			} catch (IOException e) {
				process.destroyForcibly();
				// fall through to default
			} catch (InterruptedException e) {
				process.destroyForcibly();
				Thread.currentThread().interrupt();
				return projectRoot.toAbsolutePath().normalize();
			}
		} catch (IOException e) {
			// fall through to default
		}
		return projectRoot.toAbsolutePath().normalize();
	}

	private static Set<String> detectSinceLastRun(Path absoluteSourceRoot, Path hashFile, boolean updateSnapshot)
			throws IOException {
		if (hashFile == null) {
			throw new IOException("since-last-run mode requires a hash file path (--hash-file / testorder.hashFile)");
		}
		FileHashStore current = FileHashStore.scan(absoluteSourceRoot);
		Set<String> changed;
		if (Files.exists(hashFile)) {
			FileHashStore previous = FileHashStore.load(hashFile);
			Set<String> changedFiles = current.getChangedFiles(previous);
			changed = SourceFileModel.filesToClassNames(changedFiles, absoluteSourceRoot);
		} else {
			// no previous snapshot → all files are "changed"
			changed = SourceFileModel.filesToClassNames(current.getHashes().keySet(), absoluteSourceRoot);
		}
		if (updateSnapshot) {
			current.save(hashFile);
		}
		return changed;
	}

	private static Set<String> parseExplicit(String explicitClasses) {
		if (explicitClasses == null || explicitClasses.isBlank()) {
			return Collections.emptySet();
		}
		Set<String> result = new TreeSet<>();
		for (String cls : explicitClasses.split(",")) {
			String trimmed = cls.trim();
			if (!trimmed.isEmpty()) {
				result.add(trimmed);
			}
		}
		return result;
	}
}
