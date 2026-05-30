package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UncertainClassesStoreTest {

	@TempDir
	Path tempDir;

	@Test
	void roundTripPreservesAllEntries() throws Exception {
		Set<String> fqcns = Set.of("com.example.Foo", "com.example.Bar", "org.other.Baz");
		Path file = tempDir.resolve("uncertain.txt");

		UncertainClassesStore.save(file, fqcns);
		Set<String> loaded = UncertainClassesStore.load(file);

		assertNotNull(loaded);
		assertEquals(fqcns, loaded);
	}

	@Test
	void emptySetRoundTrips() throws Exception {
		Path file = tempDir.resolve("empty.txt");
		UncertainClassesStore.save(file, Set.of());
		Set<String> loaded = UncertainClassesStore.load(file);
		assertNotNull(loaded);
		assertTrue(loaded.isEmpty());
	}

	@Test
	void missingFileReturnsNull() throws Exception {
		Path file = tempDir.resolve("nonexistent.txt");
		assertNull(UncertainClassesStore.load(file));
	}

	@Test
	void createsParentDirectories() throws Exception {
		Path file = tempDir.resolve("sub/dir/uncertain.txt");
		UncertainClassesStore.save(file, Set.of("com.example.X"));
		Set<String> loaded = UncertainClassesStore.load(file);
		assertNotNull(loaded);
		assertTrue(loaded.contains("com.example.X"));
	}
}
