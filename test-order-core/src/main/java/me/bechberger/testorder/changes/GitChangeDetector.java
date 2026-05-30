package me.bechberger.testorder.changes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import me.bechberger.testorder.TestOrderLogger;

/**
 * Detects changed Java source files using git commands.
 */
public class GitChangeDetector {

	/**
	 * Set of git roots for which the HEAD~1-unavailable warning has already been
	 * emitted. Guards against duplicate warnings when both main and test sources
	 * are checked in the same mojo execution.
	 */
	private static final Set<Path> warnedUnavailableRoots = Collections.synchronizedSet(new HashSet<>());

	/**
	 * Returns FQCNs of Java classes changed between a commit ref and HEAD, under
	 * the given source prefix.
	 */
	public static Set<String> changedSinceCommit(Path projectRoot, String commitRef, String sourcePrefix)
			throws IOException {
		List<String> files = runGit(projectRoot, "diff", "--name-only", commitRef, "HEAD", "--", sourcePrefix);
		return javaFilesToClassNames(files, sourcePrefix, projectRoot, commitRef);
	}

	/**
	 * Shortcut for changes since the previous commit under a given source prefix.
	 * Avoids a separate git rev-parse call by trying the diff directly and falling
	 * back if HEAD~1 is unavailable.
	 */
	public static Set<String> changedSinceLastCommit(Path projectRoot, String sourcePrefix) throws IOException {
		try {
			// Try diff HEAD~1 directly (avoids a separate rev-parse call)
			return changedSinceCommit(projectRoot, "HEAD~1", sourcePrefix);
		} catch (IOException e) {
			// HEAD~1 doesn't exist; fall back to treating all tracked files as changed
			if (warnedUnavailableRoots.add(projectRoot.toAbsolutePath().normalize())) {
				TestOrderLogger
						.warn("git revision HEAD~1 is unavailable; treating all tracked source files as changed");
			}
			List<String> trackedFiles = runGit(projectRoot, "ls-files", "--", sourcePrefix);
			return javaFilesToClassNames(trackedFiles, sourcePrefix, projectRoot, "HEAD");
		}
	}

	/**
	 * Returns FQCNs of Java classes with uncommitted changes under a given source
	 * prefix.
	 */
	public static Set<String> uncommittedChanges(Path projectRoot, String sourcePrefix) throws IOException {
		// Single git status call instead of 3 separate commands (diff, diff --cached,
		// ls-files)
		List<String> statusLines = runGit(projectRoot, "status", "--porcelain", "--", sourcePrefix);
		Set<String> all = new TreeSet<>();
		List<String> paths = new ArrayList<>();
		for (String line : statusLines) {
			// porcelain format: XY filename (or XY orig -> renamed)
			if (line.length() < 4)
				continue;
			String filePath = line.substring(3);
			// Handle renames: "R old -> new"
			int arrowIdx = filePath.indexOf(" -> ");
			if (arrowIdx >= 0) {
				filePath = filePath.substring(arrowIdx + 4);
			}
			paths.add(filePath);
		}
		all.addAll(javaFilesToClassNames(paths, sourcePrefix, projectRoot, "HEAD"));
		return all;
	}

	private static Set<String> javaFilesToClassNames(List<String> gitPaths, String sourcePrefix, Path projectRoot,
			String commitRef) {
		Set<String> classNames = new TreeSet<>();

		// Collect deleted files for batched git read
		List<String> deletedFilePaths = new ArrayList<>();
		Map<String, String> deletedRelativePaths = new java.util.HashMap<>();

		for (String path : gitPaths) {
			if (SourceFileModel.isSourceFile(path) && path.startsWith(sourcePrefix)) {
				String relative = path.substring(sourcePrefix.length());
				Path sourceFile = projectRoot.resolve(path);
				if (!java.nio.file.Files.isRegularFile(sourceFile)) {
					// File was deleted — collect for batch read
					if (commitRef != null && !commitRef.isBlank()) {
						deletedFilePaths.add(path);
						deletedRelativePaths.put(path, relative);
					} else {
						classNames.add(SourceFileModel.pathToClassName(relative));
					}
				}
			}
		}

		// Batch read deleted files via git cat-file --batch
		if (!deletedFilePaths.isEmpty() && commitRef != null && !commitRef.isBlank()) {
			try {
				Map<String, String> deletedFileContents = readFilesFromGitBatch(projectRoot, commitRef,
						deletedFilePaths);
				for (String path : deletedFilePaths) {
					String relative = deletedRelativePaths.get(path);
					String content = deletedFileContents.getOrDefault(path, null);
					try {
						if (content != null) {
							classNames.addAll(SourceFileModel.fileToClassNames(relative, content));
						} else {
							classNames.add(SourceFileModel.pathToClassName(relative));
						}
					} catch (Exception e) {
						classNames.add(SourceFileModel.pathToClassName(relative));
					}
				}
			} catch (IOException e) {
				// Fallback: use path-based class name derivation for deleted files
				for (String path : deletedFilePaths) {
					String relative = deletedRelativePaths.get(path);
					classNames.add(SourceFileModel.pathToClassName(relative));
				}
			}
		}

		// Process existing files
		for (String path : gitPaths) {
			if (SourceFileModel.isSourceFile(path) && path.startsWith(sourcePrefix)) {
				String relative = path.substring(sourcePrefix.length());
				Path sourceFile = projectRoot.resolve(path);
				if (java.nio.file.Files.isRegularFile(sourceFile)) {
					try {
						classNames
								.addAll(SourceFileModel.fileToClassNames(relative, projectRoot.resolve(sourcePrefix)));
					} catch (Exception ignored) {
						// Fall back to path-based name
						classNames.add(SourceFileModel.pathToClassName(relative));
					}
				}
			}
		}

		return classNames;
	}

	/**
	 * Batch-reads multiple files from git using git cat-file --batch. More
	 * efficient than sequential git show calls for large file counts.
	 */
	private static Map<String, String> readFilesFromGitBatch(Path projectRoot, String commitRef, List<String> filePaths)
			throws IOException {
		if (filePaths.isEmpty()) {
			return Map.of();
		}

		List<String> command = List.of("git", "cat-file", "--batch");
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(projectRoot.toFile());
		pb.redirectErrorStream(true);
		Process process = pb.start();

		Map<String, String> results = new java.util.HashMap<>();
		try (var os = process.getOutputStream(); InputStream is = process.getInputStream()) {
			// Write object names (commitRef:path) to stdin
			var writer = new java.io.PrintWriter(os, true);
			for (String filePath : filePaths) {
				writer.println(commitRef + ":" + filePath);
			}
			writer.flush();
			os.close(); // close stdin to signal EOF

			// Read output: header lines followed by object content.
			// git cat-file --batch reports sizes in BYTES. We read everything as raw
			// bytes to keep the stream byte-aligned. Using BufferedReader for headers
			// would cause it to buffer ahead into the body, breaking alignment.
			for (String filePath : filePaths) {
				String headerLine = readRawLine(is);
				if (headerLine == null)
					break;
				// Format: "<object> <type> <size>"
				String[] parts = headerLine.split(" ");
				if (parts.length < 3)
					continue;
				try {
					int size = Integer.parseInt(parts[2]);
					if (size < 0)
						continue; // git cat-file returns -1 for missing objects

					// Read exactly `size` bytes — always, to keep stream aligned for next entry.
					byte[] contentBytes = new byte[size];
					int totalRead = 0;
					while (totalRead < size) {
						int n = is.read(contentBytes, totalRead, size - totalRead);
						if (n < 0)
							break;
						totalRead += n;
					}
					// Consume the trailing newline byte that git appends after each object body
					is.read();
					if (totalRead == size) {
						results.put(filePath, new String(contentBytes, StandardCharsets.UTF_8));
					}
				} catch (NumberFormatException ignored) {
				}
			}
		}

		try {
			if (!process.waitFor(GitTimeout.seconds(), TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
		}

		return results;
	}

	private static List<String> runGit(Path workDir, String... args) throws IOException {
		return GitSupport.runGit(workDir, true, args);
	}

	/**
	 * Reads a newline-terminated line from a raw InputStream as UTF-8, returning
	 * null on EOF. Used to read git cat-file header lines without buffering ahead
	 * into the object body (which would break byte-exact body reads).
	 */
	private static String readRawLine(InputStream is) throws IOException {
		java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(128);
		int b;
		while ((b = is.read()) != -1) {
			if (b == '\n')
				return buf.toString(StandardCharsets.UTF_8);
			buf.write(b);
		}
		return buf.size() > 0 ? buf.toString(StandardCharsets.UTF_8) : null;
	}
}
