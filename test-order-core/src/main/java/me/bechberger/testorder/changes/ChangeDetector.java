package me.bechberger.testorder.changes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

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
			return valueOf(value.toUpperCase().replace('-', '_'));
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
		String gitPrefix = toGitPrefix(projectRoot, sourceRoot);
		Path absoluteSourceRoot = projectRoot.resolve(sourceRoot);
		try {
			return switch (mode) {
				case SINCE_LAST_RUN -> detectSinceLastRun(absoluteSourceRoot, hashFile, !readOnly);
				case SINCE_LAST_COMMIT -> mergeUncommitted(
						GitChangeDetector.changedSinceLastCommit(projectRoot, gitPrefix), projectRoot, gitPrefix);
				case UNCOMMITTED -> GitChangeDetector.uncommittedChanges(projectRoot, gitPrefix);
				case EXPLICIT -> parseExplicit(explicitClasses);
			};
		} catch (IOException e) {
			if (mode == Mode.SINCE_LAST_COMMIT || mode == Mode.UNCOMMITTED) {
				TestOrderLogger.warn("Git-based change detection failed, falling back to hash-based detection: {}",
						e.getMessage());
				return detectSinceLastRun(absoluteSourceRoot, hashFile, !readOnly);
			}
			throw e;
		}
	}

	/** Best-effort merge of uncommitted changes on top of a base result. */
	private static Set<String> mergeUncommitted(Set<String> base, Path projectRoot, String gitPrefix) {
		try {
			Set<String> uncommitted = GitChangeDetector.uncommittedChanges(projectRoot, gitPrefix);
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
	 * Converts a source root to a git-relative prefix path. Handles both absolute
	 * and relative sourceRoot paths. E.g., for projectRoot=/home/proj,
	 * sourceRoot=src/main/java → "src/main/java/"
	 */
	static String toGitPrefix(Path projectRoot, Path sourceRoot) {
		Path relative;
		if (sourceRoot.isAbsolute()) {
			relative = projectRoot.relativize(sourceRoot);
		} else {
			relative = sourceRoot;
		}
		String prefix = relative.toString().replace('\\', '/');
		if (prefix.isBlank()) {
			return "";
		}
		if (!prefix.endsWith("/"))
			prefix += "/";
		return prefix;
	}

	private static Set<String> detectSinceLastRun(Path absoluteSourceRoot, Path hashFile, boolean updateSnapshot)
			throws IOException {
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
