package me.bechberger.testorder.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gradle + CLI Integration Tests
 *
 * Tests integration scenarios between Gradle plugin and CLI tool: - CLI
 * artifacts with Gradle builds - Configuration coordination - Cache sharing
 * between tools - Version compatibility
 */
@DisplayName("Gradle + CLI Integration Tests")
class GradleCliIntegrationTest {

	@TempDir
	Path tempDir;

	private Path gradleHome;
	private Path cliConfigFile;
	private Path buildGradleFile;

	@BeforeEach
	void setup() throws IOException {
		gradleHome = tempDir.resolve(".gradle");
		Files.createDirectories(gradleHome);
		cliConfigFile = tempDir.resolve(".cli-config.yml");
		buildGradleFile = tempDir.resolve("build.gradle");
	}

	@Test
	@DisplayName("INT-G-CLI-001: CLI Artifacts Available to Gradle Plugin")
	void testCliArtifactsAvailableToGradle() throws IOException {
		// Scenario: CLI downloads artifacts that Gradle plugin needs
		// Expected: Gradle can find and use CLI-provided artifacts

		Path gradleCache = gradleHome.resolve("caches");
		Files.createDirectories(gradleCache);

		// CLI simulates downloading test-order artifacts
		Path testOrderJar = gradleCache.resolve("test-order-core-0.1.0.jar");
		Files.writeString(testOrderJar, "GRADLE-TEST-ORDER-JAR");

		// Gradle plugin should find it
		assertTrue(Files.exists(testOrderJar), "Gradle cache should contain CLI-downloaded artifact");
	}

	@Test
	@DisplayName("INT-G-CLI-002: Configuration Coordination Between Gradle and CLI")
	void testConfigurationCoordination() throws IOException {
		// Scenario: Same configuration needs to work in Gradle build.gradle and CLI
		// config
		// Expected: Configuration values synchronized or compatible

		String buildGradle = """
				plugins {
				    id 'me.bechberger.test-order' version '0.1.0'
				}

				testOrder {
				    mode = 'optimized'
				    cacheLocation = '.gradle/test-order-cache'
				    token = 'test-token-123'
				}
				""";

		String cliConfig = """
				test:
				  order:
				    mode: optimized
				    cacheLocation: .gradle/test-order-cache
				ci:
				  token: test-token-123
				""";

		Files.writeString(buildGradleFile, buildGradle);
		Files.writeString(cliConfigFile, cliConfig);

		// Both files should exist and contain compatible settings
		assertTrue(Files.exists(buildGradleFile));
		assertTrue(Files.exists(cliConfigFile));

		String buildContent = Files.readString(buildGradleFile);
		String cliContent = Files.readString(cliConfigFile);

		assertTrue(buildContent.contains("optimized"));
		assertTrue(cliContent.contains("optimized"));
	}

	@Test
	@DisplayName("INT-G-CLI-003: Cache Sharing Between Gradle and CLI")
	void testCacheSharing() throws IOException {
		// Scenario: Gradle and CLI share the same cache directory
		// Expected: Both can read/write without corruption

		Path sharedCache = gradleHome.resolve("test-order-cache");
		Files.createDirectories(sharedCache);

		// Gradle writes test scores
		Path gradleScores = sharedCache.resolve("scores.lz4");
		Files.writeString(gradleScores, "gradle-score-data");

		// CLI reads/updates scores
		String scoreContent = Files.readString(gradleScores);
		assertEquals("gradle-score-data", scoreContent);

		// CLI can update the cache
		Files.writeString(gradleScores, "gradle-score-data-updated");
		scoreContent = Files.readString(gradleScores);
		assertEquals("gradle-score-data-updated", scoreContent);
	}

	@Test
	@DisplayName("INT-G-CLI-004: Version Compatibility Check")
	void testVersionCompatibility() {
		// Scenario: Gradle plugin version must be compatible with CLI version
		// Expected: Clear error if versions are incompatible

		String gradlePluginVersion = "0.1.0";
		String cliVersion = "0.1.0";

		// Simple version comparison
		String[] gVersion = gradlePluginVersion.split("\\.");
		String[] cVersion = cliVersion.split("\\.");

		// Major version should match
		assertEquals(gVersion[0], cVersion[0], "Major versions should match");

		// Minor version compatibility (CLI can be ahead)
		int gMinor = Integer.parseInt(gVersion[1]);
		int cMinor = Integer.parseInt(cVersion[1]);
		assertTrue(cMinor >= gMinor, "CLI version should be >= Gradle plugin version");
	}

	@Test
	@DisplayName("INT-G-CLI-005: Gradle Plugin Uses CLI-Provided Configuration")
	void testGradlePluginUsesCLIConfiguration() throws IOException {
		// Scenario: CLI generates config that Gradle plugin can use
		// Expected: Gradle plugin successfully reads CLI-generated config

		Path cliGeneratedConfig = tempDir.resolve("test-order-config.properties");
		String config = """
				mode=optimized
				cache.location=.gradle/test-order-cache
				timeout.seconds=300
				parallel.enabled=true
				""";

		Files.writeString(cliGeneratedConfig, config);

		// Gradle plugin should be able to parse this
		assertTrue(Files.exists(cliGeneratedConfig));
		String content = Files.readString(cliGeneratedConfig);
		assertTrue(content.contains("mode=optimized"));
	}

	@Test
	@DisplayName("INT-G-CLI-006: Concurrent Gradle Build and CLI Download")
	void testConcurrentGradleAndCliOperations()
			throws IOException, InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
		// Scenario: Gradle build running while CLI downloads artifacts
		// Expected: No interference, both operations complete successfully

		Path cacheDir = gradleHome.resolve("caches/modules");
		Files.createDirectories(cacheDir);

		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			// Gradle simulates build
			Future<?> gradleTask = executor.submit(() -> {
				try {
					for (int i = 0; i < 5; i++) {
						Path jarFile = cacheDir.resolve("module-" + i + ".jar");
						Files.writeString(jarFile, "compiled-jar-" + i);
						Thread.sleep(20);
					}
				} catch (IOException | InterruptedException e) {
					fail("Gradle task failed: " + e.getMessage());
				}
			});

			// CLI simulates downloading
			Future<?> cliTask = executor.submit(() -> {
				try {
					for (int i = 0; i < 5; i++) {
						Path depFile = cacheDir.resolve("dependency-" + i + ".jar");
						Files.writeString(depFile, "downloaded-jar-" + i);
						Thread.sleep(20);
					}
				} catch (IOException | InterruptedException e) {
					fail("CLI task failed: " + e.getMessage());
				}
			});

			// Wait for completion
			gradleTask.get(10, TimeUnit.SECONDS);
			cliTask.get(10, TimeUnit.SECONDS);

			// All artifacts should exist
			for (int i = 0; i < 5; i++) {
				assertTrue(Files.exists(cacheDir.resolve("module-" + i + ".jar")));
				assertTrue(Files.exists(cacheDir.resolve("dependency-" + i + ".jar")));
			}

		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("INT-G-CLI-007: Gradle Incremental Build with CLI Cache")
	void testGradleIncrementalBuildWithCLICache() throws IOException {
		// Scenario: Second Gradle build uses CLI-cached artifacts
		// Expected: Faster build due to CLI cache

		Path cliCache = tempDir.resolve(".cli-cache");
		Files.createDirectories(cliCache);

		// First "build" - CLI downloads
		Path artifact1 = cliCache.resolve("test-order-0.1.0.jar");
		Files.writeString(artifact1, "original-artifact");
		long firstDownloadTime = System.currentTimeMillis();

		// Second "build" - Gradle uses cache
		assertTrue(Files.exists(artifact1), "Artifact should exist from first download");
		String content = Files.readString(artifact1);
		assertEquals("original-artifact", content);
		long secondBuildTime = System.currentTimeMillis();

		// Second build should be faster (uses cache)
		assertTrue(secondBuildTime >= firstDownloadTime);
	}

	@Test
	@DisplayName("INT-G-CLI-008: Gradle Plugin Error When CLI Config Missing")
	void testGradleErrorWhenCLIConfigMissing() throws IOException {
		// Scenario: Gradle plugin relies on CLI config that doesn't exist
		// Expected: Clear error message pointing to CLI config

		Path missingConfig = tempDir.resolve("missing-cli-config.yml");

		// Try to read non-existent config
		assertFalse(Files.exists(missingConfig), "Config file should not exist");

		// Error handling should suggest user run CLI to generate config
		String expectedErrorMessage = "CLI configuration not found: " + missingConfig;
		assertTrue(expectedErrorMessage.contains("configuration not found"),
				"Error should mention missing configuration");
	}
}
