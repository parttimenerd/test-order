package me.bechberger.testorder.changes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
	 */
	public static Set<String> changedSinceLastCommit(Path projectRoot, String sourcePrefix) throws IOException {
		if (!gitRevisionExists(projectRoot, "HEAD~1")) {
			if (warnedUnavailableRoots.add(projectRoot.toAbsolutePath().normalize())) {
				TestOrderLogger
						.warn("git revision HEAD~1 is unavailable; treating all tracked source files as changed");
			}
			List<String> trackedFiles = runGit(projectRoot, "ls-files", "--", sourcePrefix);
			return javaFilesToClassNames(trackedFiles, sourcePrefix, projectRoot, "HEAD");
		}
		return changedSinceCommit(projectRoot, "HEAD~1", sourcePrefix);
	}

	/**
	 * Returns FQCNs of Java classes with uncommitted changes under a given source
	 * prefix.
	 */
	public static Set<String> uncommittedChanges(Path projectRoot, String sourcePrefix) throws IOException {
		Set<String> all = new TreeSet<>();
		// unstaged
		List<String> unstaged = runGit(projectRoot, "diff", "--name-only", "--", sourcePrefix);
		all.addAll(javaFilesToClassNames(unstaged, sourcePrefix, projectRoot, "HEAD"));
		// staged
		List<String> staged = runGit(projectRoot, "diff", "--cached", "--name-only", "--", sourcePrefix);
		all.addAll(javaFilesToClassNames(staged, sourcePrefix, projectRoot, "HEAD"));
		// untracked (new files not yet added to git)
		List<String> untracked = runGit(projectRoot, "ls-files", "--others", "--exclude-standard", "--", sourcePrefix);
		all.addAll(javaFilesToClassNames(untracked, sourcePrefix, projectRoot, null));
		return all;
	}

	private static Set<String> javaFilesToClassNames(List<String> gitPaths, String sourcePrefix, Path projectRoot,
			String commitRef) {
		Set<String> classNames = new TreeSet<>();
		for (String path : gitPaths) {
			if (SourceFileModel.isSourceFile(path) && path.startsWith(sourcePrefix)) {
				String relative = path.substring(sourcePrefix.length());
				// Try the file on disk first
				Path sourceFile = projectRoot.resolve(path);
				if (java.nio.file.Files.isRegularFile(sourceFile)) {
					classNames.addAll(SourceFileModel.fileToClassNames(relative, projectRoot.resolve(sourcePrefix)));
				} else {
					// File was deleted — try to read from git
					if (commitRef == null || commitRef.isBlank()) {
						classNames.add(SourceFileModel.pathToClassName(relative));
					} else {
						try {
							String content = readFileFromGit(projectRoot, commitRef, path);
							if (content != null) {
								classNames.addAll(SourceFileModel.fileToClassNames(relative, content));
							} else {
								classNames.add(SourceFileModel.pathToClassName(relative));
							}
						} catch (IOException e) {
							classNames.add(SourceFileModel.pathToClassName(relative));
						}
					}
				}
			}
		}
		return classNames;
	}

	/**
	 * Reads a file's content from a specific git commit. Used to extract class
	 * names from deleted files.
	 */
	private static String readFileFromGit(Path projectRoot, String commitRef, String filePath) throws IOException {
		if (commitRef == null || commitRef.isBlank()) {
			return null;
		}
		List<String> command = List.of("git", "show", commitRef + ":" + filePath);
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(projectRoot.toFile());
		pb.redirectErrorStream(true); // merge stderr into stdout to prevent pipe buffer deadlock
		Process process = pb.start();
		String content;
		try (var is = process.getInputStream()) {
			content = new String(is.readAllBytes());
		}
		try {
			if (!process.waitFor(GitTimeout.seconds(), TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException("git show timed out for " + filePath);
			}
			if (process.exitValue() != 0)
				return null;
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			return null;
		}
		return content;
	}

	private static boolean gitRevisionExists(Path workDir, String revision) {
		try {
			runGit(workDir, "rev-parse", "--verify", revision);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private static List<String> runGit(Path workDir, String... args) throws IOException {
		List<String> command = new ArrayList<>();
		command.add("git");
		Collections.addAll(command, args);

		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(workDir.toFile());
		pb.redirectErrorStream(true); // merge stderr into stdout to prevent pipe buffer deadlock
		Process process = pb.start();

		List<String> lines = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty()) {
					lines.add(trimmed);
				}
			}
		}

		try {
			if (!process.waitFor(GitTimeout.seconds(), TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException(
						"git command timed out after " + GitTimeout.seconds() + "s: " + String.join(" ", command));
			}
			if (process.exitValue() != 0) {
				throw new IOException("git command failed: " + String.join(" ", command) + summarizeGitError(lines));
			}
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("git command interrupted: " + String.join(" ", command), e);
		}
		return lines;
	}

	private static String summarizeGitError(List<String> lines) {
		if (lines.isEmpty()) {
			return "";
		}
		String primary = lines.stream().filter(line -> !line.toLowerCase(Locale.ROOT).startsWith("usage:")).findFirst()
				.orElse(lines.get(0));
		if (primary.length() > 300) {
			primary = primary.substring(0, 300) + "...";
		}
		int additionalLines = lines.size() - 1;
		if (additionalLines <= 0) {
			return " — " + primary;
		}
		return " — " + primary + " (" + additionalLines + " more line" + (additionalLines == 1 ? "" : "s") + ")";
	}
}
