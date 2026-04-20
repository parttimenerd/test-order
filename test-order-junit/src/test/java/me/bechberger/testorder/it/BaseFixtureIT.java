package me.bechberger.testorder.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Base class for integration tests that run real test-order workflows on fixture projects.
 * Provides common utilities for:
 * - Copying fixture projects to temp directories
 * - Running Maven goals
 * - Validating test order changes
 */
public abstract class BaseFixtureIT {

    /**
     * Copy a fixture project from test-fixtures to a temporary directory.
     * Necessary because:
     * 1. Fixtures must be independent (no dependency on workspace structure)
     * 2. test-order creates state files that shouldn't pollute the fixture directory
     * 3. Multiple tests should not interfere with each other's state
     */
    protected Path copyFixtureToTemp(String fixtureName, @TempDir Path tempDir) throws Exception {
        Path fixtureSource = Path.of("test-fixtures", fixtureName);
        assertTrue(Files.exists(fixtureSource), "Fixture not found: " + fixtureName);

        Path tempFixture = tempDir.resolve(fixtureName);
        Files.createDirectories(tempFixture);

        // Recursively copy fixture
        Files.walk(fixtureSource)
                .forEach(sourcePath -> {
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

        return tempFixture;
    }

    /**
     * Run Maven command in the given project directory.
     * Returns the build output for assertion.
     */
    protected String runMaven(Path projectDir, String... goals) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        pb.command("mvn");
        for (String goal : goals) {
            pb.command().add(goal);
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

    /**
     * Extract test class names from Maven output to validate reordering.
     * Parses output like: "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0"
     */
    protected int getTestCount(String output) {
        // Pattern: "Tests run: \d+"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("Tests run: (\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher(output);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
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
        Path hashes = projectDir.resolve(".test-order-hashes.lz4");
        assertTrue(Files.exists(hashes), ".test-order-hashes.lz4 not found");
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
