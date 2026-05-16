package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HomeStorageResolverTest {

	@TempDir
	Path tempDir;

	private HomeStorageResolver resolver() {
		return new HomeStorageResolver(tempDir);
	}

	private Path fakeProject(String name) throws IOException {
		Path project = tempDir.resolve("projects").resolve(name);
		Files.createDirectories(project);
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		return project;
	}

	// ── Basic resolution ────────────────────────────────────────────

	@Test
	void resolvesNewProjectAndWritesMetadata() throws IOException {
		Path project = fakeProject("myapp");
		HomeStorageResolver r = resolver();

		Path storage = r.resolve(project, "myapp", null);

		assertTrue(Files.isDirectory(storage), "Storage directory should be created");
		assertTrue(storage.startsWith(tempDir), "Storage should be under home base");
		assertTrue(storage.getFileName().toString().startsWith("myapp-"),
				"Directory name should start with project name");

		// Metadata file should exist
		Path metaFile = storage.resolve("metadata.properties");
		assertTrue(Files.exists(metaFile), "Metadata file should be created");
		Properties props = loadProps(metaFile);
		assertEquals(project.toAbsolutePath().normalize().toString(), props.getProperty("projectPath"));
		assertEquals("myapp", props.getProperty("projectName"));
		assertNotNull(props.getProperty("createdAt"));
	}

	@Test
	void sameProjectReturnsSameDirectory() throws IOException {
		Path project = fakeProject("myapp");
		HomeStorageResolver r = resolver();

		Path first = r.resolve(project, "myapp", null);
		Path second = r.resolve(project, "myapp", null);

		assertEquals(first, second, "Repeated resolution should return same path");
	}

	@Test
	void differentProjectsGetDifferentDirectories() throws IOException {
		Path projectA = fakeProject("alpha");
		Path projectB = fakeProject("beta");
		HomeStorageResolver r = resolver();

		Path storageA = r.resolve(projectA, "alpha", null);
		Path storageB = r.resolve(projectB, "beta", null);

		assertNotEquals(storageA, storageB);
	}

	// ── Move detection ──────────────────────────────────────────────

	@Test
	void detectsProjectMoveAndRelinks() throws IOException {
		Path original = fakeProject("moveable");
		HomeStorageResolver r = resolver();

		Path storage = r.resolve(original, "moveable", null);
		// Write a marker file to verify it's the same directory later
		Files.writeString(storage.resolve("marker.txt"), "hello");

		// "Move" the project: delete the original, create at new location
		Files.delete(original.resolve("pom.xml"));
		Files.delete(original);

		Path newLocation = fakeProject("moveable-new-loc");
		Path relinked = r.resolve(newLocation, "moveable", null);

		// Should reuse the same storage (has our marker)
		assertTrue(Files.exists(relinked.resolve("marker.txt")),
				"Should reuse storage from the old location");
		assertEquals("hello", Files.readString(relinked.resolve("marker.txt")));

		// Metadata should be updated to new path
		Properties props = loadProps(relinked.resolve("metadata.properties"));
		assertEquals(newLocation.toAbsolutePath().normalize().toString(),
				props.getProperty("projectPath"));
	}

	// ── Conflict detection ──────────────────────────────────────────

	@Test
	void conflictingProjectGetsNewSlot() throws IOException {
		Path projectA = fakeProject("conflict-a");
		Path projectB = fakeProject("conflict-b");
		HomeStorageResolver r = resolver();

		// Both use the same name, but different roots → conflict
		Path storageA = r.resolve(projectA, "samename", null);
		Path storageB = r.resolve(projectB, "samename", null);

		assertNotEquals(storageA, storageB, "Conflicting projects should get different directories");
		assertTrue(Files.isDirectory(storageA));
		assertTrue(Files.isDirectory(storageB));
	}

	// ── listProjects ────────────────────────────────────────────────

	@Test
	void listProjectsReturnsAllEntries() throws IOException {
		Path p1 = fakeProject("proj1");
		Path p2 = fakeProject("proj2");
		HomeStorageResolver r = resolver();

		r.resolve(p1, "proj1", null);
		r.resolve(p2, "proj2", null);

		List<HomeStorageResolver.StoredProject> projects = r.listProjects();
		assertEquals(2, projects.size());

		List<String> names = projects.stream().map(HomeStorageResolver.StoredProject::projectName).sorted().toList();
		assertEquals(List.of("proj1", "proj2"), names);
	}

	@Test
	void listProjectsDetectsOrphans() throws IOException {
		Path project = fakeProject("orphan-test");
		HomeStorageResolver r = resolver();

		r.resolve(project, "orphan-test", null);

		// Delete the project to orphan it
		Files.delete(project.resolve("pom.xml"));
		Files.delete(project);

		List<HomeStorageResolver.StoredProject> projects = r.listProjects();
		assertEquals(1, projects.size());
		assertTrue(projects.get(0).orphaned(), "Deleted project should be marked orphaned");
	}

	// ── Sanitize / hash helpers ─────────────────────────────────────

	@Test
	void sanitizeHandlesSpecialCharacters() {
		assertEquals("my-project", HomeStorageResolver.sanitize("My Project"));
		assertEquals("hello-world", HomeStorageResolver.sanitize("hello--world"));
		assertEquals("unknown", HomeStorageResolver.sanitize(""));
		assertEquals("unknown", HomeStorageResolver.sanitize(null));
		assertEquals("a.b-c", HomeStorageResolver.sanitize("a.b-c"));
	}

	@Test
	void shortHashIsDeterministic() {
		String h1 = HomeStorageResolver.shortHash("/foo/bar", 8);
		String h2 = HomeStorageResolver.shortHash("/foo/bar", 8);
		assertEquals(h1, h2);
		assertEquals(8, h1.length());

		// Different input → different hash
		assertNotEquals(h1, HomeStorageResolver.shortHash("/foo/baz", 8));
	}

	@Test
	void buildDirNameCombinesNameAndHash() {
		String dirName = HomeStorageResolver.buildDirName("MyApp", Path.of("/some/path"));
		assertTrue(dirName.startsWith("myapp-"), "Should start with sanitized name");
		assertTrue(dirName.length() > "myapp-".length(), "Should have hash suffix");
	}

	// ── Logging ─────────────────────────────────────────────────────

	@Test
	void logsHomeDirectoryOnFirstResolve() throws IOException {
		Path project = fakeProject("logtest");
		List<String> logs = new ArrayList<>();
		PluginLog log = new PluginLog() {
			@Override public void info(String msg) { logs.add("INFO: " + msg); }
			@Override public void warn(String msg) { logs.add("WARN: " + msg); }
			@Override public void error(String msg) { logs.add("ERROR: " + msg); }
			@Override public void debug(String msg) { logs.add("DEBUG: " + msg); }
		};

		resolver().resolve(project, "logtest", log);

		assertTrue(logs.stream().anyMatch(l -> l.contains("Storing data in home directory")),
				"Should log storage location: " + logs);
	}

	// ── Helpers ─────────────────────────────────────────────────────

	private Properties loadProps(Path file) throws IOException {
		Properties p = new Properties();
		try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			p.load(r);
		}
		return p;
	}
}
