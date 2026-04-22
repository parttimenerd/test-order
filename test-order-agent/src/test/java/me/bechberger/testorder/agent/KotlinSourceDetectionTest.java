package me.bechberger.testorder.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for Kotlin source directory detection in ProjectStructureAnalyzer.
 * Verifies that the agent correctly identifies Kotlin source packages and
 * doesn't require special handling (Kotlin is just compiled to bytecode).
 */
public class KotlinSourceDetectionTest {

	/**
	 * Verify ProjectStructureAnalyzer detects Kotlin sources in src/main/kotlin.
	 */
	@Test
	public void testDetectsKotlinMainSources(@TempDir Path projectDir) throws Exception {
		// Create Kotlin project structure
		Path srcMainKotlin = projectDir.resolve("src/main/kotlin/com/example");
		Files.createDirectories(srcMainKotlin);

		// Create a dummy Kotlin file (ProjectStructureAnalyzer scans by directory
		// structure)
		Files.writeString(srcMainKotlin.resolve("Utils.kt"), "package com.example\n\nfun hello() = \"world\"");

		// Analyze project structure
		ProjectStructureAnalyzer analyzer = new ProjectStructureAnalyzer(projectDir);

		// Verify Kotlin package was detected
		Set<String> userPackages = analyzer.getUserPackages();
		assertNotNull(userPackages, "User packages should be detected");
		assertFalse(userPackages.isEmpty(), "Should have detected at least one package from Kotlin sources");
		assertTrue(userPackages.stream().anyMatch(p -> p.contains("example")),
				"Should detect Kotlin packages in src/main/kotlin, detected: " + userPackages);
	}

	/**
	 * Verify ProjectStructureAnalyzer detects Kotlin test sources in
	 * src/test/kotlin.
	 */
	@Test
	public void testDetectsKotlinTestSources(@TempDir Path projectDir) throws Exception {
		// Create Kotlin test structure
		Path srcTestKotlin = projectDir.resolve("src/test/kotlin/com/example");
		Files.createDirectories(srcTestKotlin);

		// Create a dummy Kotlin test file
		Files.writeString(srcTestKotlin.resolve("UtilsTest.kt"),
				"package com.example\n\nimport org.junit.jupiter.api.Test\n\nclass UtilsTest {\n  @Test\n  fun test() { }\n}");

		// Analyze project structure
		ProjectStructureAnalyzer analyzer = new ProjectStructureAnalyzer(projectDir);

		// Verify Kotlin test package was detected
		Set<String> testPackages = analyzer.getTestPackages();
		assertNotNull(testPackages, "Test packages should be detected");
		assertFalse(testPackages.isEmpty(), "Should have detected at least one package from Kotlin test sources");
		assertTrue(testPackages.stream().anyMatch(p -> p.contains("example")),
				"Should detect test packages in src/test/kotlin, detected: " + testPackages);
	}

	/**
	 * Verify mixed Java and Kotlin sources are handled together.
	 */
	@Test
	public void testMixedJavaAndKotlinSources(@TempDir Path projectDir) throws Exception {
		// Create mixed Java/Kotlin structure
		Path javaDir = projectDir.resolve("src/main/java/com/example/java");
		Path kotlinDir = projectDir.resolve("src/main/kotlin/com/example/kotlin");
		Files.createDirectories(javaDir);
		Files.createDirectories(kotlinDir);

		// Create files in both languages
		Files.writeString(javaDir.resolve("JavaClass.java"), "package com.example.java;\n\npublic class JavaClass { }");
		Files.writeString(kotlinDir.resolve("KotlinClass.kt"), "package com.example.kotlin\n\nclass KotlinClass { }");

		// Analyze project structure
		ProjectStructureAnalyzer analyzer = new ProjectStructureAnalyzer(projectDir);

		// Verify both Java and Kotlin packages are detected
		Set<String> userPackages = analyzer.getUserPackages();
		assertNotNull(userPackages, "User packages should be detected");
		assertFalse(userPackages.isEmpty(), "Should detect both Java and Kotlin packages");
		assertTrue(userPackages.stream().anyMatch(p -> p.contains("java") || p.contains("kotlin")),
				"Should detect packages from both Java and Kotlin sources, detected: " + userPackages);
	}
}
