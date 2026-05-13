package me.bechberger.testorder.gradle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.ops.TestClassDiscovery;

/**
 * Guards Gradle workflow parity with Maven: nested classes must be discoverable
 * as separate executable test classes.
 */
class NestedClassDiscoveryParityTest {

	@TempDir
	Path tempDir;

	@Test
	void scanTestClasses_keepsNestedAndSkipsAnonymousArtifacts() throws IOException {
		Path testClasses = tempDir.resolve("test-classes/com/example");
		Files.createDirectories(testClasses);

		Files.write(testClasses.resolve("OuterTest.class"), new byte[0]);
		Files.write(testClasses.resolve("OuterTest$Nested.class"), new byte[0]);
		Files.write(testClasses.resolve("OuterTest$1.class"), new byte[0]);

		Set<String> classes = TestClassDiscovery.scanTestClasses(tempDir.resolve("test-classes"));

		assertTrue(classes.contains("com.example.OuterTest"));
		assertTrue(classes.contains("com.example.OuterTest$Nested"));
		assertFalse(classes.contains("com.example.OuterTest$1"));
	}
}
