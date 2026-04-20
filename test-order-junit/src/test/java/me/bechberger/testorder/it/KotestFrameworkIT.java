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

        // Learn mode should collect data and produce state/index artifacts
        String learnOutput = runMaven(fixtureDir, "test-order:learn");
        assertTestsPassed(learnOutput);
        assertStateFileExists(fixtureDir);
        assertIndexFilesExist(fixtureDir);

        // Order mode should run successfully against the generated state
        String orderOutput = runMaven(fixtureDir, "test-order:order");
        assertTestsPassed(orderOutput);
        int orderCount = getTestCount(orderOutput);
        assertEquals(5, orderCount, "Kotest case count should remain stable after ordering");
    }
}
