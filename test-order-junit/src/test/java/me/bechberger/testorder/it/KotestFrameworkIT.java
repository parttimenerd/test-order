package me.bechberger.testorder.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test for Kotlin Kotest projects.
 * Validates that test-order workflows run successfully on a Kotlin + Kotest fixture.
 */
public class KotestFrameworkIT extends BaseFixtureIT {

    @Test
    public void testKotestFixtureWithTestOrder(@TempDir Path tempDir) throws Exception {
        Path fixtureDir = copyFixtureToTemp("fixture-kotest", tempDir);

        // Baseline Kotest execution through JUnit platform
        String baselineOutput = runMaven(fixtureDir, "clean", "test");
        assertTestsPassed(baselineOutput);
        int baselineCount = getTestCount(baselineOutput);
        assertEquals(5, baselineCount, "Expected 5 Kotest cases in fixture");

        // Combined mode should run end-to-end on Kotest fixture
        String combinedOutput = runMaven(fixtureDir, "test-order:combined", "test");
        assertTestsPassed(combinedOutput);
        int combinedCount = getTestCount(combinedOutput);
        assertEquals(5, combinedCount, "Expected 5 Kotest cases when running test-order combined mode");
        assertIndexFilesExist(fixtureDir);

        // Re-running combined mode should preserve successful Kotest execution
        String secondCombinedOutput = runMaven(fixtureDir, "test-order:combined", "test");
        assertTestsPassed(secondCombinedOutput);
        int secondRunCount = getTestCount(secondCombinedOutput);
        assertEquals(5, secondRunCount, "Kotest case count should remain stable across test-order runs");
    }
}
