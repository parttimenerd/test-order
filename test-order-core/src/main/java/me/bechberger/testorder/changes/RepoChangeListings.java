package me.bechberger.testorder.changes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-scoped cache of repo-level git change listings, shared across the
 * per-module analyses of a multi-module build (select/auto paths run
 * {@link StructuralDiff} and {@link NonSourceChangeDetector} for every module;
 * without sharing, a 100-module reactor pays ~1,000 git subprocess spawns per
 * build).
 *
 * <p>
 * <b>Invalidation story (no stale data across builds).</b> Gradle and Maven
 * both run inside long-lived daemon JVMs, and test-order-core has no per-build
 * scope object the cache could attach to (it would have to be wired in from the
 * mojos / Gradle plugin, which this change deliberately does not touch). The
 * cache therefore self-invalidates <em>soundly</em> instead of relying on TTLs
 * or HEAD-sha keys: every access re-runs a single
 * {@code git status --porcelain=v2 --untracked-files=all --branch} and compares
 * the full output (plus the content of untracked {@code .gitignore} files and
 * {@code .git/info/exclude}, which shape the untracked listing without
 * appearing in the status output) against the fingerprint captured when the
 * listings were computed. The porcelain-v2 output captures the HEAD oid, the
 * staged state (X column), the unstaged state (Y column), every untracked path
 * and every conflict entry — i.e. everything the cached listings are a pure
 * function of: path sets and git-object contents. Worktree file contents are
 * never cached (callers read them fresh), so a content-only change of an
 * already-modified file correctly keeps the cache valid. A fingerprint mismatch
 * recomputes every listing; a match proves them identical. No timestamps, no
 * HEAD-sha-only keying.
 *
 * <p>
 * <b>Thread safety.</b> Entries live in a {@link ConcurrentHashMap}; a
 * fingerprint mismatch atomically installs a fresh entry. Lazy listing
 * computations are serialized per entry and published immutably, so Gradle
 * {@code --parallel} module tasks can share an entry concurrently.
 *
 * <p>
 * <b>Fail-safety.</b> {@link #acquire} returns {@code null} when git is
 * unavailable, times out, or produces unexpected output; every consumer falls
 * back to its pre-existing direct-git behavior in that case. Listing
 * computations replicate the exact commands, error handling and output ordering
 * of the direct paths, so cached results are byte-identical.
 */
public final class RepoChangeListings {

	private RepoChangeListings() {
	}

	/**
	 * Opaque handle to a validated repo-level listing set. Consumers pass it to the
	 * {@link StructuralDiff}/{@link NonSourceChangeDetector} overloads that accept
	 * a shared snapshot, which lets both analyses of one module reuse a single
	 * validation (and therefore a single extra git spawn per module).
	 */
	public static final class Snapshot {

		private final Entry entry;

		Snapshot(Entry entry) {
			this.entry = entry;
		}

		Path gitRoot() {
			return entry.gitRoot;
		}

		Set<String> uncommittedPaths() throws IOException {
			return entry.uncommittedPaths();
		}

		LastCommitData lastCommit() throws IOException {
			return entry.lastCommit();
		}

		List<String> statusPorcelainPaths() throws IOException {
			return entry.statusPorcelainPaths();
		}

		List<String> trackedPaths() throws IOException {
			return entry.trackedPaths();
		}

		Map<String, String> oldSources(String ref, Collection<String> paths) throws IOException {
			return entry.oldSources(ref, paths);
		}
	}

	// ── Public API ───────────────────────────────────────────────────

	/**
	 * Validates the current git state of the repository rooted at {@code gitRoot}
	 * and returns a snapshot whose listings reflect it, or {@code null} when git is
	 * unavailable or the state cannot be captured (callers fall back to their
	 * direct-git behavior).
	 *
	 * <p>
	 * Costs exactly one git spawn when the cached listings are still valid;
	 * recomputes the listings (a handful of spawns) once per worktree-state change.
	 */
	public static Snapshot acquire(Path gitRoot) {
		try {
			// Canonical (link-following) key: on macOS, /tmp/x and rev-parse's
			// /private/tmp/x are the same worktree — without canonicalization they would
			// produce two cache entries and defeat cross-module sharing.
			Path root = canonicalize(gitRoot.toAbsolutePath().normalize());
			String fingerprint = computeFingerprint(root);
			Entry entry = ENTRIES.compute(root,
					(key, existing) -> existing != null && fingerprint.equals(existing.fingerprint)
							? existing
							: new Entry(root, fingerprint));
			return new Snapshot(entry);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Resolves the git work-tree root for {@code projectRoot}, mirroring
	 * {@code git rev-parse --show-toplevel} (the resolution {@link StructuralDiff}
	 * always performed per module). The mapping is cached per process and re-proven
	 * cheaply (the {@code .git} entry must still exist and contain the project
	 * root); any doubt falls back to a fresh {@code rev-parse}, as does the first
	 * access. Cached entries are re-validated against the repository state by the
	 * {@link #acquire} fingerprint check that every consumer runs right after
	 * resolution.
	 *
	 * @throws IOException
	 *             when {@code projectRoot} is not inside a git work tree (same
	 *             failure the direct path produces)
	 */
	public static Path resolveGitRoot(Path projectRoot) throws IOException {
		// Canonical (link-following) key so that /tmp/x and /private/tmp/x hit the
		// same cache entry (see {@link #canonicalize}).
		Path key = canonicalize(projectRoot.toAbsolutePath().normalize());
		Path cached = GIT_ROOTS.get(key);
		if (cached != null && Files.exists(cached.resolve(".git")) && key.startsWith(cached)) {
			return cached;
		}
		List<String> lines = GitSupport.runGit(key, false, "rev-parse", "--show-toplevel");
		if (lines.isEmpty()) {
			throw new IOException("Failed to resolve git root for " + projectRoot);
		}
		Path fresh = canonicalize(Path.of(lines.get(0)).toAbsolutePath().normalize());
		GIT_ROOTS.put(key, fresh);
		return fresh;
	}

	/**
	 * Canonicalizes a path with {@link Path#toRealPath()} (link-following) so that
	 * symlinked spellings of the same directory map to one cache key, falling back
	 * to {@link Path#normalize()} when the path cannot be resolved (e.g. it does
	 * not exist yet).
	 */
	private static Path canonicalize(Path path) {
		try {
			return path.toRealPath();
		} catch (IOException e) {
			return path.normalize();
		}
	}

	// ── Snapshot internals ───────────────────────────────────────────

	private static final ConcurrentHashMap<Path, Entry> ENTRIES = new ConcurrentHashMap<>();

	/** projectRoot → gitRoot mappings resolved by {@link #resolveGitRoot}. */
	private static final ConcurrentHashMap<Path, Path> GIT_ROOTS = new ConcurrentHashMap<>();

	private static final class Entry {

		final Path gitRoot;
		final String fingerprint;

		private final Object lock = new Object();
		private volatile Set<String> uncommittedPaths;
		private volatile LastCommitData lastCommit;
		private volatile List<String> statusPorcelainPaths;
		private volatile List<String> trackedPaths;
		private final Map<String, RefData> refs = new HashMap<>();

		Entry(Path gitRoot, String fingerprint) {
			this.gitRoot = gitRoot;
			this.fingerprint = fingerprint;
		}

		/**
		 * Union of {@code diff --name-only}, {@code diff --cached --name-only} and
		 * {@code ls-files --others --exclude-standard}, repo-root-relative.
		 */
		Set<String> uncommittedPaths() throws IOException {
			Set<String> paths = uncommittedPaths;
			if (paths == null) {
				synchronized (lock) {
					if (uncommittedPaths == null) {
						Set<String> collected = new TreeSet<>();
						collected.addAll(GitSupport.runGit(gitRoot, false, "diff", "--name-only"));
						collected.addAll(GitSupport.runGit(gitRoot, false, "diff", "--cached", "--name-only"));
						collected.addAll(
								GitSupport.runGit(gitRoot, false, "ls-files", "--others", "--exclude-standard"));
						uncommittedPaths = collected;
					}
					paths = uncommittedPaths;
				}
			}
			return paths;
		}

		/** {@code rev-parse --verify HEAD~1} result plus the HEAD~1..HEAD diff. */
		LastCommitData lastCommit() throws IOException {
			LastCommitData data = lastCommit;
			if (data == null) {
				synchronized (lock) {
					if (lastCommit == null) {
						lastCommit = computeLastCommit();
					}
					data = lastCommit;
				}
			}
			return data;
		}

		/**
		 * All tracked files ({@code git ls-files}), repo-wide — the HEAD~1-unavailable
		 * fallback listing.
		 */
		List<String> trackedPaths() throws IOException {
			List<String> paths = trackedPaths;
			if (paths == null) {
				synchronized (lock) {
					if (trackedPaths == null) {
						trackedPaths = GitSupport.runGit(gitRoot, true, "ls-files");
					}
					paths = trackedPaths;
				}
			}
			return paths;
		}

		/**
		 * Parsed paths of {@code git status --porcelain --untracked-files=all},
		 * repo-wide.
		 */
		List<String> statusPorcelainPaths() throws IOException {
			List<String> paths = statusPorcelainPaths;
			if (paths == null) {
				synchronized (lock) {
					if (statusPorcelainPaths == null) {
						List<String> lines = GitSupport.runGit(gitRoot, true, "status", "--porcelain",
								"--untracked-files=all");
						statusPorcelainPaths = parseStatusLines(lines);
					}
					paths = statusPorcelainPaths;
				}
			}
			return paths;
		}

		/**
		 * Old (ref) contents for the given repo-root-relative paths — lazily resolved
		 * via one full {@code ls-tree} per ref and one {@code cat-file --batch} per
		 * batch of newly requested paths.
		 */
		Map<String, String> oldSources(String ref, Collection<String> paths) throws IOException {
			if (paths.isEmpty()) {
				return Map.of();
			}
			RefData data;
			synchronized (lock) {
				data = refs.get(ref);
				if (data == null) {
					data = new RefData(computeLsTree(ref));
					refs.put(ref, data);
				}
			}
			synchronized (data) {
				List<String> unseen = new ArrayList<>();
				for (String path : paths) {
					if (!data.fetched.contains(path)) {
						unseen.add(path);
					}
				}
				if (!unseen.isEmpty()) {
					Map<String, String> oidToRelPath = new LinkedHashMap<>();
					for (String path : unseen) {
						String oid = data.oidByPath.get(path);
						if (oid != null) {
							oidToRelPath.put(oid, path);
						}
					}
					if (!oidToRelPath.isEmpty()) {
						data.contents.putAll(StructuralDiff.fetchBlobContents(gitRoot, oidToRelPath));
					}
					data.fetched.addAll(unseen);
				}
			}
			Map<String, String> out = new LinkedHashMap<>();
			for (String path : paths) {
				out.put(path, data.contents.get(path));
			}
			return out;
		}

		private LastCommitData computeLastCommit() {
			boolean hasHeadParent = false;
			try {
				hasHeadParent = !GitSupport.runGit(gitRoot, false, "rev-parse", "--verify", "HEAD~1").isEmpty();
			} catch (IOException e) {
				hasHeadParent = false;
			}
			Set<String> paths = new TreeSet<>();
			boolean diffFailed = false;
			try {
				paths.addAll(GitSupport.runGit(gitRoot, true, "diff", "--name-only", "HEAD~1", "HEAD"));
			} catch (IOException e) {
				diffFailed = true;
			}
			return new LastCommitData(hasHeadParent, paths, diffFailed);
		}

		/** Full-tree {@code ls-tree} for a ref, path → oid (raw output keys). */
		private Map<String, String> computeLsTree(String ref) {
			Map<String, String> oidByPath = new LinkedHashMap<>();
			List<String> lsTreeOutput;
			try {
				lsTreeOutput = GitSupport.runGit(gitRoot, false, "ls-tree", "-r", ref, "--");
			} catch (IOException e) {
				// ref may not exist (e.g. brand-new repo) — same fallback as the direct path
				lsTreeOutput = List.of();
			}
			for (String line : lsTreeOutput) {
				int tab = line.indexOf('\t');
				if (tab < 0) {
					continue;
				}
				String metaSection = line.substring(0, tab);
				String path = line.substring(tab + 1);
				String[] meta = metaSection.split(" ", 3);
				if (meta.length < 3) {
					continue;
				}
				oidByPath.put(path, meta[2]);
			}
			return oidByPath;
		}
	}

	/** {@code HEAD~1}-related listings, computed once per entry. */
	static final class LastCommitData {

		final boolean hasHeadParent;
		final Set<String> diffPaths;
		final boolean diffFailed;

		LastCommitData(boolean hasHeadParent, Set<String> diffPaths, boolean diffFailed) {
			this.hasHeadParent = hasHeadParent;
			this.diffPaths = diffPaths;
			this.diffFailed = diffFailed;
		}
	}

	private static final class RefData {

		final Map<String, String> oidByPath;
		final Map<String, String> contents = new LinkedHashMap<>();
		final Set<String> fetched = new HashSet<>();

		RefData(Map<String, String> oidByPath) {
			this.oidByPath = oidByPath;
		}
	}

	/** Parses {@code status --porcelain} lines into repo-root-relative paths. */
	static List<String> parseStatusLines(List<String> statusLines) {
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

	// ── Fingerprint ──────────────────────────────────────────────────

	/**
	 * Full state fingerprint of the worktree: the porcelain-v2 status output (HEAD
	 * oid, staged/unstaged/conflict entries, untracked paths) plus the content
	 * hashes of untracked {@code .gitignore} files and {@code .git/info/exclude}
	 * (both shape the untracked listing without changing their status lines when
	 * only their content changes).
	 */
	private static String computeFingerprint(Path gitRoot) throws IOException {
		List<String> lines = GitSupport.runGit(gitRoot, true, "status", "--porcelain=v2", "--untracked-files=all",
				"--branch");
		StringBuilder sb = new StringBuilder();
		for (String line : lines) {
			sb.append(line).append('\n');
			if (line.startsWith("? ")) {
				String path = unquoteGitPath(line.substring(2));
				if (path.endsWith("/.gitignore") || path.equals(".gitignore")) {
					sb.append(contentHashOrMissing(gitRoot.resolve(path))).append('\n');
				}
			}
		}
		sb.append(contentHashOrMissing(gitRoot.resolve(".git/info/exclude"))).append('\n');
		return sb.toString();
	}

	private static String contentHashOrMissing(Path file) {
		try {
			if (!Files.isRegularFile(file)) {
				return "<missing>";
			}
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(Files.readAllBytes(file));
			StringBuilder hex = new StringBuilder();
			for (byte b : hash) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (IOException | NoSuchAlgorithmException e) {
			return "<unreadable>";
		}
	}

	/**
	 * Decodes git's C-style path quoting ({@code core.quotePath}) so paths can be
	 * matched against real filesystem paths. Unquoted input is returned unchanged;
	 * malformed quoted input is returned as-is.
	 */
	static String unquoteGitPath(String path) {
		if (path.length() < 2 || path.charAt(0) != '"' || path.charAt(path.length() - 1) != '"') {
			return path;
		}
		String body = path.substring(1, path.length() - 1);
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		for (int i = 0; i < body.length(); i++) {
			char c = body.charAt(i);
			if (c != '\\') {
				out.write(c);
				continue;
			}
			if (++i >= body.length()) {
				return path; // dangling escape — give up on decoding
			}
			char esc = body.charAt(i);
			switch (esc) {
				case 'n' -> out.write('\n');
				case 't' -> out.write('\t');
				case 'r' -> out.write('\r');
				case '"' -> out.write('"');
				case '\\' -> out.write('\\');
				default -> {
					if (esc >= '0' && esc <= '7') {
						int value = esc - '0';
						int digits = 1;
						while (digits < 3 && i + 1 < body.length() && body.charAt(i + 1) >= '0'
								&& body.charAt(i + 1) <= '7') {
							value = value * 8 + (body.charAt(++i) - '0');
							digits++;
						}
						if (value > 255) {
							return path;
						}
						out.write(value);
					} else {
						return path; // unknown escape — give up on decoding
					}
				}
			}
		}
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}
}
