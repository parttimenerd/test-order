package me.bechberger.testorder.changes;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import java.util.TreeSet;

/**
 * Persists and loads the set of "uncertain" FQCNs that selective learn mode
 * must instrument.
 *
 * <p>
 * Format: UTF-8 newline-delimited FQCNs, one per line. Blank lines are ignored.
 * No LZ4 — the file is tiny (a few KB at most) and is read once at agent
 * premain.
 */
public final class UncertainClassesStore {

	private UncertainClassesStore() {
	}

	/**
	 * Writes {@code fqcns} to {@code file}. Parent directories are created if
	 * necessary. Uses a temp-file + atomic rename.
	 */
	public static void save(Path file, Set<String> fqcns) throws IOException {
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			for (String fqcn : new TreeSet<>(fqcns)) {
				w.write(fqcn);
				w.newLine();
			}
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/**
	 * Loads the set from {@code file}. Returns {@code null} when {@code file} does
	 * not exist — callers treat {@code null} as "no gating, instrument everything".
	 */
	public static Set<String> load(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		Set<String> result = new TreeSet<>();
		try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = r.readLine()) != null) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty()) {
					result.add(trimmed);
				}
			}
		}
		return Set.copyOf(result);
	}
}
