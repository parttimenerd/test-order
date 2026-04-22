package me.bechberger.testorder.it;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for JaCoCo code coverage coexistence with test-order.
 * Validates that: - test-order agent and JaCoCo agent don't conflict - Coverage
 * report is still generated and valid - No silent failures or classpath issues
 * - Agent overhead remains acceptable
 */
public class JaCoCoCoexistenceIT extends BaseFixtureIT {

	@Test
	public void testJaCoCoWithTestOrder(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-jacoco", tempDir);

		// Run with JaCoCo prepare-agent goal (configured in pom.xml)
		String output = runMaven(fixtureDir, "clean", "test", "jacoco:report");
		assertTestsPassed(output);
		int testCount = getTestCount(output);
		assertEquals(5, testCount, "Expected 5 tests in JaCoCo fixture");

		// Verify JaCoCo report was generated
		Path jacocoReport = fixtureDir.resolve("target/site/jacoco/index.html");
		assertTrue(java.nio.file.Files.exists(jacocoReport), "JaCoCo report not generated at: " + jacocoReport);
	}

	@Test
	public void testDualAgentNoConflict(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-jacoco", tempDir);

		// Run with both test-order and JaCoCo in combined mode
		String output = runMaven(fixtureDir, "clean", "test-order:learn", "jacoco:report");
		assertTestsPassed(output);

		// Both agents should complete without errors
		assertFalse(output.contains("NoSuchMethodError"), "Dual agent caused NoSuchMethodError");
		assertFalse(output.contains("ClassCastException"), "Dual agent caused ClassCastException");
		assertFalse(output.contains("java.lang.VerifyError"), "Dual agent caused VerifyError");
	}

	@Test
	public void testCoverageReportValidWithTestOrder(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-jacoco", tempDir);

		// Run full workflow: learn + test + coverage
		String output = runMaven(fixtureDir, "clean", "test-order:learn", "jacoco:report");
		assertTestsPassed(output);

		// Verify state files AND coverage report both exist
		assertStateFileExists(fixtureDir);
		Path jacocoReport = fixtureDir.resolve("target/site/jacoco/index.html");
		assertTrue(java.nio.file.Files.exists(jacocoReport), "Coverage report not generated");
	}

	@Test
	public void testAgentStartupOverhead(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-jacoco", tempDir);

		// Time execution with test-order
		long startTime = System.currentTimeMillis();
		runMaven(fixtureDir, "clean", "test-order:learn", "jacoco:report");
		long elapsed = System.currentTimeMillis() - startTime;

		// Reasonable threshold: dual agents should complete in < 30 seconds for 5 tests
		assertTrue(elapsed < 30000, "Dual agent execution took " + elapsed + "ms (threshold: 30s)");
	}
}
