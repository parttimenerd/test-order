package me.bechberger.testorder.changes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import me.bechberger.testorder.TestOrderLogger;

/**
 * Detects whether a change touches <em>non-source</em> inputs — i.e. inputs
 * that are invisible to the source-based change detection (which only sees
 * {@code .java}/{@code .kt} files, see {@link GitChangeDetector}) but can still
 * change test behavior:
 *
 * <ul>
 * <li>files under {@code src/main/resources} or {@code src/test/resources} (any
 * file type, including deleted files)</li>
 * <li>build files: {@code pom.xml}, {@code build.gradle},
 * {@code build.gradle.kts}, {@code settings.gradle},
 * {@code settings.gradle.kts} at the project root or at any directory
 * <em>above</em> it up to the git root (a parent {@code pom.xml} or the Gradle
 * {@code settings.gradle} affects every module below it)</li>
 * </ul>
 *
 * The "config-like resources" naming from the plan ({@code .properties},
 * {@code .yaml}, {@code .yml}, {@code .xml}) is subsumed by the resource-root
 * rule: every file under a resource root counts, regardless of extension.
 *
 * <p>
 * Detection mirrors {@link GitChangeDetector} conventions and runs through the
 * shared {@link GitSupport}/{@link GitTimeout} helpers (GitTimeout semantics
 * apply to every git call). Unlike {@link GitChangeDetector} — which assumes
 * the project root equals the git root — this class runs git from the <em>git
 * root</em> with a module-relative prefix, because {@code git status
 * --porcelain} and {@code git diff} both print repo-root-relative paths even
 * when invoked from a subdirectory.
 *
 * <p>
 * Fail-safe: when git is unavailable (no repository, no {@code git} binary,
 * timeout, or any git failure) the detector returns
 * {@link Result#NOT_DETERMINED} — callers treat that as "unchanged", preserving
 * the pre-existing behavior.
 */
public final class NonSourceChangeDetector {

	/** Tri-state detection outcome. */
	public enum Result {
		/** At least one in-scope non-source file changed. */
		CHANGED,
		/** No in-scope non-source file changed. */
		UNCHANGED,
		/** Git unavailable or git inspection failed — outcome unknown. */
		NOT_DETERMINED
	}

	/**
	 * Detection outcome plus the matched file paths (repo-root-relative, sorted,
	 * deduplicated) for logging.
	 */
	public record Detection(Result result, List<String> files) {

		public static final Detection NOT_DETERMINED = new Detection(Result.NOT_DETERMINED, List.of());

		/** {@code true} only for {@link Result#CHANGED}. */
		public boolean changed() {
			return result == Result.CHANGED;
		}
	}

	private static final String MAIN_RESOURCES = "src/main/resources";
	private static final String TEST_RESOURCES = "src/test/resources";

	/** Build files counted as non-source inputs. */
	private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts",
			"settings.gradle", "settings.gradle.kts");

	/**
	 * Git roots for which the HEAD~1-unavailable warning has already been emitted
	 * (same dedup convention as {@link GitChangeDetector}).
	 */
	private static final Set<Path> warnedUnavailableRoots = Collections.synchronizedSet(new java.util.HashSet<>());

	private NonSourceChangeDetector() {
	}

	/**
	 * Detects non-source changes for the project rooted at {@code projectRoot}
	 * inside the git work tree rooted at {@code gitRoot}.
	 *
	 * @param projectRoot
	 *            the project (module) directory; resources under its
	 *            {@code src/{main,test}/resources} and build files at or above it
	 *            are in scope
	 * @param gitRoot
	 *            the git work tree root the commands run in (as resolved by
	 *            {@code ChangeDetector.findGitRoot}); when it equals
	 *            {@code projectRoot} the whole repository is in scope
	 * @param sinceLastCommit
	 *            {@code true} diffs {@code HEAD~1..HEAD} (falling back to all
	 *            tracked in-scope files when {@code HEAD~1} is unavailable, like
	 *            {@link GitChangeDetector#changedSinceLastCommit}); {@code false}
	 *            inspects the working tree via {@code git status --porcelain},
	 *            including untracked files
	 * @return the tri-state outcome plus the matched file list; never {@code null}
	 */
	public static Detection detect(Path projectRoot, Path gitRoot, boolean sinceLastCommit) {
		return detect(projectRoot, gitRoot, sinceLastCommit, null);
	}

	/**
	 * As {@link #detect(Path, Path, boolean)} with a shared repo-level snapshot:
	 * when {@code shared} was validated against the same git root, its cached
	 * repo-wide listings are filtered in-memory instead of spawning pathspec'd git
	 * commands, and the (already-run) state validation is not repeated. Any cache
	 * failure falls back to the direct git behavior below.
	 */
	public static Detection detect(Path projectRoot, Path gitRoot, boolean sinceLastCommit,
			RepoChangeListings.Snapshot shared) {
		if (projectRoot == null || gitRoot == null) {
			return Detection.NOT_DETERMINED;
		}
		Path root = gitRoot.toAbsolutePath().normalize();
		if (!Files.isDirectory(root)) {
			return Detection.NOT_DETERMINED;
		}
		try {
			RepoChangeListings.Snapshot snapshot = shared != null && shared.gitRoot().equals(root)
					? shared
					: RepoChangeListings.acquire(root);
			if (snapshot != null) {
				return detectFromSnapshot(projectRoot, root, sinceLastCommit, snapshot);
			}
		} catch (IOException | RuntimeException e) {
			// fall through to the direct path
		}
		return detectDirect(projectRoot, gitRoot, sinceLastCommit);
	}

	/**
	 * Cached variant: filters the repo-wide listings of a validated snapshot down
	 * to the in-scope non-source inputs. The pathspec filter replicates git's
	 * matching for the exact spec set {@link #pathspecs(String)} builds, so the
	 * resulting path set is identical to the direct path's pathspec'd command
	 * output (including its rename handling: a rename whose sides match different
	 * pathspecs contributes only the matching side, exactly like the direct path's
	 * pathspec'd {@code D}/{@code AM} output forms).
	 */
	private static Detection detectFromSnapshot(Path projectRoot, Path root, boolean sinceLastCommit,
			RepoChangeListings.Snapshot snapshot) throws IOException {
		String prefix = modulePrefix(root, projectRoot);
		List<String> changedPaths;
		if (sinceLastCommit) {
			RepoChangeListings.LastCommitData lastCommit = snapshot.lastCommit();
			if (lastCommit.diffFailed) {
				// Same over-detection fallback as the direct path (one-time warning).
				if (warnedUnavailableRoots.add(root)) {
					TestOrderLogger.warn(
							"git revision HEAD~1 is unavailable; treating all tracked non-source files as changed");
				}
				changedPaths = snapshot.trackedPaths();
			} else {
				changedPaths = new ArrayList<>();
				for (String path : lastCommit.diffPaths) {
					if (matchesPathspecs(path, prefix)) {
						changedPaths.add(path);
					}
				}
			}
		} else {
			changedPaths = new ArrayList<>();
			for (String path : snapshot.statusPorcelainPaths()) {
				if (matchesPathspecs(path, prefix)) {
					changedPaths.add(path);
				}
			}
		}
		Set<String> matched = filterInScope(changedPaths, prefix);
		if (matched.isEmpty()) {
			return new Detection(Result.UNCHANGED, List.of());
		}
		return new Detection(Result.CHANGED, List.copyOf(matched));
	}

	/**
	 * Direct git path — the pre-cache behavior, kept as the fail-safe fallback.
	 */
	private static Detection detectDirect(Path projectRoot, Path gitRoot, boolean sinceLastCommit) {
		if (projectRoot == null || gitRoot == null) {
			return Detection.NOT_DETERMINED;
		}
		Path root = gitRoot.toAbsolutePath().normalize();
		if (!Files.isDirectory(root)) {
			return Detection.NOT_DETERMINED;
		}
		try {
			List<String> inside = GitSupport.runGit(root, false, "rev-parse", "--is-inside-work-tree");
			if (inside.size() != 1 || !inside.get(0).equals("true")) {
				return Detection.NOT_DETERMINED;
			}
		} catch (IOException e) {
			// git binary missing/unusable — fail safe
			return Detection.NOT_DETERMINED;
		}
		String prefix = modulePrefix(root, projectRoot);
		List<String> changedPaths;
		try {
			changedPaths = sinceLastCommit ? changesSinceLastCommit(root, prefix) : uncommittedChanges(root, prefix);
		} catch (IOException e) {
			// any git failure → outcome unknown; callers treat this as unchanged
			return Detection.NOT_DETERMINED;
		}
		Set<String> matched = filterInScope(changedPaths, prefix);
		if (matched.isEmpty()) {
			return new Detection(Result.UNCHANGED, List.of());
		}
		return new Detection(Result.CHANGED, List.copyOf(matched));
	}

	/**
	 * {@code module/} prefix of the project relative to the git root, or {@code ""}
	 * when the project is the git root itself. Callers pass the git root resolved
	 * by {@code ChangeDetector.findGitRoot}, so the project root is always inside
	 * the work tree.
	 */
	private static String modulePrefix(Path gitRoot, Path projectRoot) {
		Path project = projectRoot.toAbsolutePath().normalize();
		if (project.equals(gitRoot) || !project.startsWith(gitRoot)) {
			return "";
		}
		return gitRoot.relativize(project).toString().replace('\\', '/') + "/";
	}

	/**
	 * Changed in-scope paths between {@code HEAD~1} and {@code HEAD}. Mirrors
	 * {@link GitChangeDetector#changedSinceLastCommit}: when {@code HEAD~1} is
	 * unavailable (shallow clone, first commit), all tracked in-scope files are
	 * treated as changed (safe over-detection) after a one-time warning.
	 */
	private static List<String> changesSinceLastCommit(Path gitRoot, String prefix) throws IOException {
		List<String> specs = pathspecs(prefix);
		try {
			return GitSupport.runGit(gitRoot, true,
					gitCommand(List.of("diff", "--name-only", "HEAD~1", "HEAD"), specs));
		} catch (IOException e) {
			if (warnedUnavailableRoots.add(gitRoot.toAbsolutePath().normalize())) {
				TestOrderLogger
						.warn("git revision HEAD~1 is unavailable; treating all tracked non-source files as changed");
			}
			return GitSupport.runGit(gitRoot, true, gitCommand(List.of("ls-files"), specs));
		}
	}

	/**
	 * Builds the git argument array: the fixed args, then {@code --}, then the
	 * pathspecs.
	 */
	private static String[] gitCommand(List<String> fixedArgs, List<String> specs) {
		List<String> command = new ArrayList<>(fixedArgs);
		command.add("--");
		command.addAll(specs);
		return command.toArray(new String[0]);
	}

	/**
	 * In-scope paths with uncommitted changes (staged, unstaged, untracked,
	 * deleted), mirroring {@link GitChangeDetector#uncommittedChanges} including
	 * its rename handling ({@code R old -> new} marks both sides).
	 * {@code --untracked-files=all} lists files inside untracked directories
	 * individually instead of collapsing them to {@code dir/} entries that would
	 * evade file-based matching.
	 */
	private static List<String> uncommittedChanges(Path gitRoot, String prefix) throws IOException {
		List<String> statusLines = GitSupport.runGit(gitRoot, true,
				gitCommand(List.of("status", "--porcelain", "--untracked-files=all"), pathspecs(prefix)));
		List<String> paths = new ArrayList<>();
		for (String line : statusLines) {
			// porcelain format: XY path (or XY old -> new)
			if (line.length() < 4) {
				continue;
			}
			String path = line.substring(3);
			int arrowIdx = path.indexOf(" -> ");
			if (arrowIdx >= 0) {
				paths.add(path.substring(0, arrowIdx));
				path = path.substring(arrowIdx + 4);
			}
			paths.add(path);
		}
		return paths;
	}

	/**
	 * Git pathspecs limiting the queried paths to the in-scope non-source inputs
	 * (resource roots plus build files) relative to the git root.
	 */
	private static List<String> pathspecs(String prefix) {
		List<String> specs = new ArrayList<>();
		specs.add(prefix + MAIN_RESOURCES);
		specs.add(prefix + TEST_RESOURCES);
		if (prefix.isEmpty()) {
			for (String buildFile : BUILD_FILES) {
				specs.add(buildFile);
			}
		} else {
			// Any depth: the project's own build files plus ancestor build files
			// (parent pom.xml, Gradle settings at the repo root). Out-of-scope hits
			// (e.g. sibling or nested-module build files) are filtered afterwards.
			for (String buildFile : BUILD_FILES) {
				specs.add(":(glob)**/" + buildFile);
			}
		}
		return specs;
	}

	/**
	 * Java replication of git's pathspec matching for the exact spec set
	 * {@link #pathspecs(String)} builds — used by the cached path to filter the
	 * repo-wide listing without a pathspec'd git invocation. Semantics (verified
	 * against git 2.x):
	 * <ul>
	 * <li>a directory pathspec matches the directory itself and everything under it
	 * (component-boundary aware)</li>
	 * <li>{@code :(glob)**} + {@code /<name>} matches {@code <name>} at any depth,
	 * including the repository root</li>
	 * <li>a literal pathspec matches only the exact path</li>
	 * </ul>
	 * Paths are matched after decoding git's C-style quoting (git matches raw
	 * paths), while {@link #filterInScope} keeps operating on the raw output text
	 * exactly like the direct path does.
	 */
	static boolean matchesPathspecs(String repoPath, String prefix) {
		String path = RepoChangeListings.unquoteGitPath(repoPath);
		if (isDirectorySpecMatch(path, prefix + MAIN_RESOURCES)
				|| isDirectorySpecMatch(path, prefix + TEST_RESOURCES)) {
			return true;
		}
		int slash = path.lastIndexOf('/');
		String name = slash < 0 ? path : path.substring(slash + 1);
		if (!BUILD_FILES.contains(name)) {
			return false;
		}
		if (prefix.isEmpty()) {
			// literal specs: pom.xml, build.gradle, ... at the git root only
			return path.equals(name);
		}
		// :(glob)**/<name> — any depth, including the git root
		return true;
	}

	private static boolean isDirectorySpecMatch(String path, String spec) {
		return path.equals(spec) || path.startsWith(spec + "/");
	}

	/**
	 * Filters repo-root-relative paths down to the in-scope non-source inputs:
	 * files under the project's resource roots and build files at or above the
	 * project root.
	 */
	private static Set<String> filterInScope(List<String> repoPaths, String prefix) {
		Set<String> matched = new TreeSet<>();
		for (String repoPath : repoPaths) {
			String path = repoPath.replace('\\', '/');
			if (isResourceInScope(path, prefix) || isBuildFileInScope(path, prefix)) {
				matched.add(path);
			}
		}
		return matched;
	}

	/**
	 * {@code true} when the path lies under one of the project's resource roots.
	 */
	private static boolean isResourceInScope(String repoPath, String prefix) {
		if (!prefix.isEmpty() && !repoPath.startsWith(prefix)) {
			return false;
		}
		String projectRelative = prefix.isEmpty() ? repoPath : repoPath.substring(prefix.length());
		// Strip a trailing slash from directory-style porcelain entries (defensive;
		// --untracked-files=all already expands directories to files).
		if (projectRelative.endsWith("/")) {
			projectRelative = projectRelative.substring(0, projectRelative.length() - 1);
		}
		return projectRelative.startsWith(MAIN_RESOURCES + "/") || projectRelative.equals(MAIN_RESOURCES)
				|| projectRelative.startsWith(TEST_RESOURCES + "/") || projectRelative.equals(TEST_RESOURCES);
	}

	/**
	 * {@code true} when the path is a build file at or above the project root: the
	 * project's own build file, or a parent {@code pom.xml} / Gradle
	 * {@code settings.gradle} in an ancestor directory (which affects the project's
	 * build). Build files in unrelated sibling or nested directories are not in
	 * scope.
	 */
	private static boolean isBuildFileInScope(String repoPath, String prefix) {
		int slash = repoPath.lastIndexOf('/');
		String name = slash < 0 ? repoPath : repoPath.substring(slash + 1);
		if (!BUILD_FILES.contains(name)) {
			return false;
		}
		String dir = slash < 0 ? "" : repoPath.substring(0, slash);
		String dirPrefix = dir.isEmpty() ? "" : dir + "/";
		return prefix.startsWith(dirPrefix);
	}
}
