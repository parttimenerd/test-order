package me.bechberger.testorder.changes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Shared git subprocess utilities used by {@link GitChangeDetector} and
 * {@link StructuralDiff}.
 */
final class GitSupport {

	private GitSupport() {
	}

	/**
	 * Runs a git command in {@code workDir} and returns its stdout lines (stripped
	 * of trailing whitespace, empty lines excluded).
	 *
	 * @param throwOnError
	 *            when {@code true}, throws {@link IOException} on timeout, non-zero
	 *            exit, or interruption; when {@code false}, returns an empty list
	 *            instead
	 */
	static List<String> runGit(Path workDir, boolean throwOnError, String... args) throws IOException {
		List<String> command = new ArrayList<>();
		command.add("git");
		Collections.addAll(command, args);

		ProcessBuilder pb = new ProcessBuilder(command);
		pb.directory(workDir.toFile());
		pb.redirectErrorStream(true);
		Process process = pb.start();

		// Drain stdout on a background thread so that waitFor() can honour the timeout
		// even when git hangs without producing output (e.g. waiting for an index
		// lock).
		List<String> lines = new CopyOnWriteArrayList<>();
		Thread drainer = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					String trimmed = line.stripTrailing();
					if (!trimmed.isEmpty()) {
						lines.add(trimmed);
					}
				}
			} catch (IOException ignored) {
			}
		}, "test-order-git-drain");
		drainer.setDaemon(true);
		drainer.start();

		try {
			if (!process.waitFor(GitTimeout.seconds(), TimeUnit.SECONDS)) {
				process.destroyForcibly();
				drainer.interrupt();
				if (throwOnError) {
					throw new IOException(
							"git command timed out after " + GitTimeout.seconds() + "s: " + String.join(" ", command));
				}
				return Collections.emptyList();
			}
			drainer.join(2000);
			if (process.exitValue() != 0) {
				if (throwOnError) {
					throw new IOException("git command failed: " + String.join(" ", command) + summarizeError(lines));
				}
				return Collections.emptyList();
			}
		} catch (InterruptedException e) {
			process.destroyForcibly();
			drainer.interrupt();
			Thread.currentThread().interrupt();
			if (throwOnError) {
				throw new IOException("git command interrupted: " + String.join(" ", command), e);
			}
			return Collections.emptyList();
		}
		return new ArrayList<>(lines);
	}

	private static String summarizeError(List<String> lines) {
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
