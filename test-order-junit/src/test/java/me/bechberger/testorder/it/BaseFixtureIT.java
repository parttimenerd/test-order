package me.bechberger.testorder.it;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Base class for integration tests that run real test-order workflows on
 * fixture projects. Provides common utilities for: - Copying fixture projects
 * to temp directories - Running Maven goals - Validating test order changes
 */
public abstract class BaseFixtureIT {

	private static final String TEST_ORDER_PLUGIN_VERSION = System.getProperty("testorder.plugin.version",
			"0.1.0");

	/**
	 * Copy a fixture project from test-fixtures to a temporary directory. Ensures
	 * each fixture has the necessary compiler configuration for independent
	 * execution.
	 *
	 * Necessary because: 1. Fixtures must be independent (no dependency on
	 * workspace structure) 2. test-order creates state files that shouldn't pollute
	 * the fixture directory 3. Multiple tests should not interfere with each
	 * other's state 4. Fixtures need to compile with -parameters flag for Spring
	 * Framework reflection
	 */
	protected Path copyFixtureToTemp(String fixtureName, @TempDir Path tempDir) throws Exception {
		Path fixtureSource = Path.of("test-fixtures", fixtureName);
		assertTrue(Files.exists(fixtureSource), "Fixture not found: " + fixtureName);

		Path tempFixture = tempDir.resolve(fixtureName);
		Files.createDirectories(tempFixture);

		// Copy the parent POM so that <relativePath>../pom.xml</relativePath> resolves
		Path parentPom = Path.of("test-fixtures", "pom.xml");
		if (Files.exists(parentPom)) {
			Files.copy(parentPom, tempDir.resolve("pom.xml"), StandardCopyOption.REPLACE_EXISTING);
		}

		// Recursively copy fixture
		Files.walk(fixtureSource).forEach(sourcePath -> {
			try {
				Path relative = fixtureSource.relativize(sourcePath);
				Path target = tempFixture.resolve(relative);
				if (Files.isDirectory(sourcePath)) {
					Files.createDirectories(target);
				} else {
					Files.copy(sourcePath, target, StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});

		// Ensure the fixture pom.xml has the necessary compiler plugin configuration
		// if it's missing (for compatibility with Spring Framework and other frameworks
		// that need reflection parameter access)
		ensureCompilerPluginConfiguration(tempFixture);

		return tempFixture;
	}

	/**
	 * Ensure the fixture pom.xml includes maven-compiler-plugin with -parameters
	 * flag. This handles fixtures that may not have inherited this from a parent
	 * POM.
	 */
	private void ensureCompilerPluginConfiguration(Path fixtureDir) throws Exception {
		Path pomFile = fixtureDir.resolve("pom.xml");
		String pomContent = Files.readString(pomFile);
		if (pomContent.contains("<arg>-parameters</arg>")) {
			return;
		}

		// Check if maven-compiler-plugin is already configured
		if (pomContent.contains("maven-compiler-plugin")) {
			String configuredPlugin = """
					            <plugin>
					                <groupId>org.apache.maven.plugins</groupId>
					                <artifactId>maven-compiler-plugin</artifactId>
					                <version>3.13.0</version>
					                <configuration>
					                    <source>17</source>
					                    <target>17</target>
					                    <compilerArgs>
					                        <arg>-parameters</arg>
					                    </compilerArgs>
					                </configuration>
					            </plugin>
					""";

			String updatedExisting = pomContent.replaceFirst(
					"(?s)<plugin>\\s*<groupId>org\\.apache\\.maven\\.plugins</groupId>\\s*<artifactId>maven-compiler-plugin</artifactId>\\s*(?:<version>[^<]+</version>\\s*)?</plugin>",
					configuredPlugin);

			if (!updatedExisting.equals(pomContent)) {
				Files.writeString(pomFile, updatedExisting);
				return;
			}
		}

		// If not present, add it to the build/plugins section
		String pluginConfig = """
				            <plugin>
				                <groupId>org.apache.maven.plugins</groupId>
				                <artifactId>maven-compiler-plugin</artifactId>
				                <version>3.13.0</version>
				                <configuration>
				                    <source>17</source>
				                    <target>17</target>
				                    <compilerArgs>
				                        <arg>-parameters</arg>
				                    </compilerArgs>
				                </configuration>
				            </plugin>
				""";

		// Find <plugins> section and add before </plugins>
		String updated = pomContent.replaceFirst("(<build>.*?<plugins>)", "$1\n" + pluginConfig);

		// If no <build><plugins> section exists, create one
		if (updated.equals(pomContent)) {
			// Try to add before </project>
			updated = pomContent.replaceFirst("</project>", "\n    <build>\n        <plugins>\n" + pluginConfig
					+ "\n        </plugins>\n    </build>\n</project>");
		}

		Files.writeString(pomFile, updated);
	}

	/**
	 * Run Maven command in the given project directory. Returns the build output
	 * for assertion.
	 */
	protected String runMaven(Path projectDir, String... goals) throws Exception {
		ProcessBuilder pb = new ProcessBuilder();
		pb.command("mvn");
		for (String goal : goals) {
			pb.command().add(resolveGoal(goal));
		}
		pb.directory(projectDir.toFile());
		pb.redirectErrorStream(true);

		Process process = pb.start();
		String output = new String(process.getInputStream().readAllBytes());
		int exitCode = process.waitFor();

		if (exitCode != 0) {
			fail("Maven failed with exit code " + exitCode + ":\n" + output);
		}

		return output;
	}

	private static String resolveGoal(String goal) {
		if (goal == null || !goal.startsWith("test-order:")) {
			return goal;
		}
		String mojo = goal.substring("test-order:".length());
		return "me.bechberger:test-order-maven-plugin:" + TEST_ORDER_PLUGIN_VERSION + ":" + mojo;
	}

	/**
	 * Extract test class names from Maven output to validate reordering. Parses
	 * output like: "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0"
	 */
	protected int getTestCount(String output) {
		// Pattern: "Tests run: \d+"
		java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("Tests run: (\\d+)");
		java.util.regex.Matcher matcher = pattern.matcher(output);
		int lastCount = -1;
		while (matcher.find()) {
			lastCount = Integer.parseInt(matcher.group(1));
		}
		if (lastCount >= 0) {
			return lastCount;
		}
		fail("Could not extract test count from Maven output: " + output);
		return -1;
	}

	/**
	 * Verify test-order state file was created (indicates learn mode worked).
	 */
	protected void assertStateFileExists(Path projectDir) {
		Path stateFile = projectDir.resolve(".test-order");
		assertTrue(Files.exists(stateFile), "test-order state file not created at: " + stateFile);
	}

	/**
	 * Verify test-order index files exist (dependency map, hashes).
	 */
	protected void assertIndexFilesExist(Path projectDir) {
		Path stateDir = projectDir.resolve(".test-order");
		assertTrue(Files.exists(stateDir), ".test-order directory not created");
		Path hashes = stateDir.resolve("hashes.lz4");
		assertTrue(Files.exists(hashes), ".test-order/hashes.lz4 not found");
		Path dependencies = stateDir.resolve("test-dependencies.lz4");
		Path fallback = stateDir.resolve("test-dependencies.lz4.collector-fallback");
		assertTrue(Files.exists(dependencies) || Files.exists(fallback), ".test-order/test-dependencies.lz4 not found");
	}

	/**
	 * Assert that test output shows no errors or failures.
	 */
	protected void assertTestsPassed(String output) {
		assertFalse(output.contains("ERROR"), "Maven output contains ERROR");
		assertFalse(output.contains("FAILURE"), "Maven output contains FAILURE");
		assertTrue(output.contains("BUILD SUCCESS") || !output.contains("BUILD FAILURE"),
				"Maven build did not succeed");
	}
}
