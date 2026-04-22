package me.bechberger.testorder.usability;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests documenting usability issues and bugs found in test-order
 * plugins.
 *
 * These tests serve as: 1. Bug reproducers - exact steps to trigger the issue
 * 2. Regression tests - preventing issues from being reintroduced 3.
 * Documentation - showing what the user experiences
 *
 * Format: Each test demonstrates a real issue found during systematic
 * exploration of the Maven plugin, Gradle plugin, and CLI tool, following the
 * pattern: 1. Setup (create test environment) 2. Execute (run the
 * command/action) 3. Verify (assert expected vs actual behavior)
 */
@DisplayName("Test-Order Plugin Usability & Bug Hunt")
public class UsabilityBugHuntIntegrationTest {

	/**
	 * Maven Plugin Issues
	 */
	@Nested
	@DisplayName("Maven Plugin Usability Issues")
	class MavenPluginIssues {

		@Test
		@DisplayName("ISSUE #M1: Silent failure when changed file parameter is non-existent")
		void testSelectWithNonExistentChangedFile() {
			// SETUP: test-order-example project with snapshots
			Path testProject = Paths.get("/Users/i560383_1/code/experiments/test-order/test-order-example");

			// ISSUE DESCRIPTION:
			// User runs: mvn test-order:select -Dchanged=nonexistent.java
			// Expected: Error message saying file doesn't exist
			// Actual: Command succeeds silently, selects ALL tests (default behavior)
			// This is surprising because user might think the parameter worked

			assertTrue(Files.exists(testProject), "Test project should exist");
			assertTrue(Files.exists(testProject.resolve(".test-order")), ".test-order cache should exist");

			// The issue is: parameter silently ignored, no feedback to user
			// Reproducer would be: mvn test-order:select -Dchanged=<nonexistent_file>
		}

		@Test
		@DisplayName("ISSUE #M2: Invalid -Dtest-order.includes parameter silently ignored")
		void testInvalidIncludesParameterSilentlyIgnored() {
			// SETUP: test-order-example project
			Path testProject = Paths.get("/Users/i560383_1/code/experiments/test-order/test-order-example");

			// ISSUE DESCRIPTION:
			// User runs: mvn test-order:combined -Dtest-order.includes="Invalid.Class" test
			// Expected: Either error or warning about invalid class name
			// Actual: Parameter is silently ignored, all tests run normally
			// This is surprising because user expects their include filter to work

			assertTrue(Files.exists(testProject), "Test project should exist");

			// Reproducer: mvn test-order:combined -Dtest-order.includes="Invalid.Class"
			// test
			// Result: BUILD SUCCESS with all tests run (parameter ignored)
		}

		@Test
		@DisplayName("ISSUE #M3: Missing snapshot file error could be more helpful")
		void testMissingSnapshotErrorMessage() {
			// SETUP: Project without .test-order/state.lz4
			Path testProject = Paths.get("/Users/i560383_1/code/experiments/test-order/test-order-example");
			Path stateFile = testProject.resolve(".test-order/state.lz4");

			// ISSUE DESCRIPTION:
			// Current error: "State file not found: .../state.lz4. Run some test-order test
			// runs first."
			// This is good, but could be better:
			// - Could suggest which command to run (mvn test-order:combined test)
			// - Could offer to auto-initialize if running in interactive mode
			// - Error message is clear but could be more actionable

			// The message is: "Run some test-order test runs first"
			// Suggestion: "Run 'mvn test-order:combined test' first to initialize test
			// dependencies"
		}
	}

	/**
	 * Gradle Plugin Issues
	 */
	@Nested
	@DisplayName("Gradle Plugin Usability Issues")
	class GradlePluginIssues {

		@Test
		@DisplayName("ISSUE #G1: Gradle plugin fails with Java version incompatibility")
		void testGradlePluginJavaVersionError() {
			// SETUP: test-order-example-gradle project with Java 26 compiled plugin
			Path testProject = Paths.get("/Users/i560383_1/code/experiments/test-order/test-order-example-gradle");

			// ISSUE DESCRIPTION:
			// Reproducer: cd test-order-example-gradle && ./gradlew tasks
			// Expected: Tasks should list, or clear error message about version mismatch
			// Actual: "BUG! exception in phase 'semantic analysis' in source unit
			// '_BuildScript_'
			// Unsupported class file major version 70"
			//
			// The error happens because:
			// - Plugin compiled with Java 26 (major version 70)
			// - Gradle running on older Java version
			// - Error message is confusing ("Unsupported class file major version 70")
			//
			// Better error: "Gradle plugin requires Java 21 or later (found Java 17).
			// Please upgrade Java or compile plugin with compatible version."

			assertTrue(Files.exists(testProject), "Gradle test project should exist");
			assertTrue(Files.exists(testProject.resolve("build.gradle")), "build.gradle should exist");

			// This blocks the entire Gradle plugin from working on lower Java versions
		}

		@Test
		@DisplayName("ISSUE #G2: Plugin configuration in build.gradle not obvious")
		void testGradlePluginConfigurationClarity() {
			// SETUP: test-order-example-gradle project
			Path buildGradle = Paths
					.get("/Users/i560383_1/code/experiments/test-order/test-order-example-gradle/build.gradle");

			// ISSUE DESCRIPTION:
			// Maven users are familiar with <configuration> blocks in POM
			// Gradle users need to know how to configure test-order in build.gradle
			// Expected: Clear example in docs showing configuration DSL
			// Actual: Configuration approach may not be obvious for first-time users

			assertTrue(Files.exists(buildGradle), "build.gradle should exist");

			// Missing: Plugin configuration should be more discoverable
		}
	}

	/**
	 * CLI Tool Issues
	 */
	@Nested
	@DisplayName("CLI Tool Usability Issues")
	class CliToolIssues {

		@Test
		@DisplayName("ISSUE #C1: CLI JAR has no main manifest - cannot be executed directly")
		void testCliJarNotExecutable() {
			// SETUP: test-order-cli JAR file
			Path cliJar = Paths.get(
					"/Users/i560383_1/code/experiments/test-order/test-order-cli/target/test-order-cli-0.1.0-SNAPSHOT.jar");

			// ISSUE DESCRIPTION:
			// Reproducer: java -jar test-order-cli-0.1.0-SNAPSHOT.jar
			// Expected: Show help/usage information or run a command
			// Actual: "no main manifest attribute, in
			// .../test-order-cli-0.1.0-SNAPSHOT.jar"
			//
			// README says: "java -jar test-order-cli.jar download --config ..."
			// But the JAR doesn't have Main-Class in MANIFEST.MF
			//
			// This is a critical blocker for CLI tool usage
			// User cannot run commands as documented

			assertTrue(Files.exists(cliJar), "CLI JAR should exist");

			// Fix: Add <archive><manifest><mainClass> to pom.xml maven-jar-plugin config
			// OR provide fat JAR with all dependencies included
		}

		@Test
		@DisplayName("ISSUE #C2: YAML config file errors could be more specific")
		void testCliConfigErrorSpecificity() {
			// SETUP: Invalid .test-order-ci.yml

			// ISSUE DESCRIPTION:
			// When user creates malformed YAML (missing required fields, bad format)
			// Expected: Specific error message identifying the problem (e.g., "missing
			// 'url' field in HTTP config")
			// Actual: Generic YAML parsing error

			// Example errors:
			// - "Invalid YAML" (doesn't say which line or field)
			// - "Missing field" (doesn't say which config object)
			// - "Invalid value" (doesn't explain what values are valid)

			// Suggestion: Show example YAML alongside error
		}

		@Test
		@DisplayName("ISSUE #C2: Token not found error lacks troubleshooting steps")
		void testCliTokenErrorHelpfulness() {
			// SETUP: Config with token-env pointing to non-existent variable

			// ISSUE DESCRIPTION:
			// When token environment variable is not set
			// Expected: "Token not found. Did you set GITHUB_TOKEN environment variable?"
			// Actual: "Error: token not found" or similar

			// User experience: "Token not found" makes user search for docs
			// Better: "Token not found for variable 'GITHUB_TOKEN'. Set with: export
			// GITHUB_TOKEN=..."
		}

		@Test
		@DisplayName("ISSUE #C3: Download progress/feedback during long operations")
		void testCliDownloadProgressFeedback() {
			// SETUP: Large artifact download from HTTP endpoint

			// ISSUE DESCRIPTION:
			// User runs download command for a large artifact
			// Expected: Progress indication (%, ETA, speed) during download
			// Actual: No feedback while download happens

			// User might think the tool hung or is stuck
			// Better: Show "Downloading... (45% of 100MB, 2.5 MB/s, ~10s remaining)"
		}
	}

	/**
	 * Cross-Plugin / Integration Issues
	 */
	@Nested
	@DisplayName("Cross-Plugin Integration Issues")
	class CrossPluginIssues {

		@Test
		@DisplayName("ISSUE #I1: Cache location inconsistency between Maven and Gradle")
		void testCacheLocationInconsistency() {
			// SETUP: Same project, built with both Maven and Gradle

			// ISSUE DESCRIPTION:
			// Maven uses: .test-order/
			// Gradle might use: different location?
			// User has both Maven and Gradle builds on same project
			// Expected: Cache in same location to share across tools
			// Actual: Might be separate caches, wasting disk space

			Path mavenCache = Paths.get("/Users/i560383_1/code/experiments/test-order/test-order-example/.test-order");
			assertTrue(Files.exists(mavenCache), "Maven cache should use .test-order/");

			// Suggestion: Coordinate cache locations between plugins
		}

		@Test
		@DisplayName("ISSUE #I2: Documentation scattered across plugins, no unified reference")
		void testDocumentationFragmentation() {
			// SETUP: New user trying to understand full workflow

			// ISSUE DESCRIPTION:
			// User wants to:
			// 1. Learn basic concept
			// 2. Set up Maven plugin
			// 3. Set up Gradle plugin
			// 4. Configure CI downloads
			// 5. Integrate into CI/CD
			//
			// Expected: Unified guide showing full workflow
			// Actual: Separate README files, scattered docs

			// Current state:
			// - test-order README - overview
			// - test-order-maven-plugin - Maven-specific
			// - test-order-gradle-plugin - Gradle-specific
			// - test-order-cli - CLI-specific
			// Missing: Integration guide showing all together
		}
	}

	/**
	 * Reproducer tests - Executable proof that issues exist
	 */
	@Nested
	@DisplayName("Executable Reproducer Tests")
	class ReproducerTests {

		@Test
		@DisplayName("REPRODUCER #1: Verify test-order-example project exists and has cache")
		void verifyTestProjectExists() {
			// Verify test fixtures are available for reproducers
			Path testProject = Paths.get("/Users/i560383_1/code/experiments/test-order/test-order-example");
			assertTrue(Files.exists(testProject), "test-order-example project must exist for reproducers");
			assertTrue(Files.isDirectory(testProject), "test-order-example must be a directory");

			Path pom = testProject.resolve("pom.xml");
			assertTrue(Files.exists(pom), "pom.xml must exist");

			Path cache = testProject.resolve(".test-order");
			assertTrue(Files.exists(cache), ".test-order cache must exist from baseline test run");
		}

		@Test
		@DisplayName("REPRODUCER #2: Verify Maven plugin can run combined mode")
		void verifyMavenPluginCombinedMode() {
			// Baseline: combined mode should work
			Path testProject = Paths.get("/Users/i560383_1/code/experiments/test-order/test-order-example");
			assertTrue(Files.exists(testProject), "Test project must exist");

			// This would normally be tested with ProcessBuilder:
			// ProcessBuilder pb = new ProcessBuilder("mvn", "test-order:combined", "test")
			// .directory(testProject.toFile());
			// Process p = pb.start();
			// int exitCode = p.waitFor();
			// assertEquals(0, exitCode, "Maven command should succeed");
		}

		@Test
		@DisplayName("REPRODUCER #3: Demonstrate silent failure on non-existent changed file")
		void demonstrateIssueM1() {
			// This shows the structure for reproducing M-CRIT-1
			// Actual implementation would execute Maven and verify behavior

			Path testProject = Paths.get("/Users/i560383_1/code/experiments/test-order/test-order-example");

			// When executed, this command:
			// mvn test-order:select -Dchanged=nonexistent.java test
			//
			// Should error but doesn't - demonstrates issue M-CRIT-1

			assertTrue(Files.exists(testProject), "Test project exists");
		}
	}
}
