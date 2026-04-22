package me.bechberger.testorder.plugin.it;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;

/**
 * Wraps a Maven project directory for integration testing.
 * <p>
 * Provides helpers to run Maven goals, read internal files, modify source
 * files, and restore them after tests.
 */
public class TestProject {

	private final Path projectDir;
	private final MavenRunner maven;
	private final Map<Path, byte[]> savedFiles = new LinkedHashMap<>();

	public TestProject(Path projectDir) {
		this(projectDir, List.of());
	}

	public TestProject(Path projectDir, List<String> defaultMavenArgs) {
		this.projectDir = projectDir.toAbsolutePath();
		this.maven = new MavenRunner(this.projectDir, defaultMavenArgs);
	}

	/** Resolve a path relative to the project root. */
	public Path path(String relativePath) {
		return projectDir.resolve(relativePath);
	}

	// ── Maven execution ───────────────────────────────────────────────

	public MavenRunner maven() {
		return maven;
	}

	// ── File modification with automatic restore ──────────────────────

	/**
	 * Replace a string in a source file. The original is saved for later restore.
	 *
	 * @return this for chaining
	 */
	public TestProject replaceInFile(String relativePath, String oldText, String newText) {
		Path file = path(relativePath);
		try {
			byte[] original = Files.readAllBytes(file);
			savedFiles.putIfAbsent(file, original);
			String content = new String(original, StandardCharsets.UTF_8);
			if (!content.contains(oldText)) {
				throw new IllegalArgumentException("File " + relativePath + " does not contain: " + oldText);
			}
			Files.writeString(file, content.replace(oldText, newText));
			return this;
		} catch (IOException e) {
			throw new RuntimeException("Failed to modify " + file, e);
		}
	}

	/**
	 * Append content to a source file. The original is saved for later restore.
	 *
	 * @return this for chaining
	 */
	public TestProject appendToFile(String relativePath, String content) {
		Path file = path(relativePath);
		try {
			byte[] original = Files.readAllBytes(file);
			savedFiles.putIfAbsent(file, original);
			Files.writeString(file, new String(original, StandardCharsets.UTF_8) + content);
			return this;
		} catch (IOException e) {
			throw new RuntimeException("Failed to append to " + file, e);
		}
	}

	/** Restore all previously modified files to their original content. */
	public void restoreAll() {
		for (var entry : savedFiles.entrySet()) {
			try {
				Files.write(entry.getKey(), entry.getValue());
			} catch (IOException e) {
				System.err.println("WARNING: Failed to restore " + entry.getKey() + ": " + e.getMessage());
			}
		}
		savedFiles.clear();
	}

	// ── Clean generated files ─────────────────────────────────────────

	/** Delete the dependency index, state file, hash files, and target dir. */
	public TestProject cleanAll() {
		deleteTree(".test-order");
		deleteTree("target");
		return this;
	}

	/** Delete a file relative to the project root. */
	public void deleteIfExists(String relativePath) {
		try {
			Files.deleteIfExists(path(relativePath));
		} catch (IOException e) {
			// ignore
		}
	}

	/** Delete a directory tree relative to the project root. */
	public void deleteTree(String relativePath) {
		Path dir = path(relativePath);
		if (!Files.exists(dir))
			return;
		try {
			Files.walkFileTree(dir, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}
				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					Files.delete(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			// best effort
		}
	}

	// ── Reading internal files ────────────────────────────────────────

	/**
	 * Load the dependency index (test-dependencies.lz4). Returns null if it doesn't
	 * exist.
	 */
	public DependencyMap loadIndex() {
		return loadIndex(".test-order/test-dependencies.lz4");
	}

	/** Load a dependency index from a custom path. */
	public DependencyMap loadIndex(String relativePath) {
		Path idx = path(relativePath);
		if (!Files.exists(idx))
			return null;
		try {
			return DependencyMap.load(idx);
		} catch (IOException e) {
			throw new RuntimeException("Failed to load index: " + idx, e);
		}
	}

	/** Load the test-order state file. Returns null if it doesn't exist. */
	public TestOrderState loadState() {
		return loadState(".test-order/state.lz4");
	}

	/** Load a state file from a custom path. */
	public TestOrderState loadState(String relativePath) {
		Path file = path(relativePath);
		if (!Files.exists(file))
			return null;
		try {
			return TestOrderState.load(file);
		} catch (IOException e) {
			throw new RuntimeException("Failed to load state: " + file, e);
		}
	}

	/** Read the verbose agent log file. Returns null if it doesn't exist. */
	public String readVerboseLog(String relativePath) {
		Path file = path(relativePath);
		if (!Files.exists(file))
			return null;
		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new RuntimeException("Failed to read verbose log: " + file, e);
		}
	}

	/** List the .deps files in the deps directory. */
	public List<String> listDepsFiles() {
		return listDepsFiles("target/test-order-deps");
	}

	/** List .deps files in a specific directory. */
	public List<String> listDepsFiles(String relativePath) {
		Path dir = path(relativePath);
		if (!Files.isDirectory(dir))
			return List.of();
		try (var stream = Files.list(dir)) {
			return stream.map(Path::getFileName).map(Path::toString).filter(n -> n.endsWith(".deps")).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/** Read a file as string. Returns null if it doesn't exist. */
	public String readFile(String relativePath) {
		Path file = path(relativePath);
		if (!Files.exists(file))
			return null;
		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new RuntimeException("Failed to read " + file, e);
		}
	}

	/** Check if a file exists. */
	public boolean exists(String relativePath) {
		return Files.exists(path(relativePath));
	}

	public Path getProjectDir() {
		return projectDir;
	}

	@Override
	public String toString() {
		return "TestProject[" + projectDir.getFileName() + "]";
	}
}
