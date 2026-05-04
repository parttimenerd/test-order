package me.bechberger.testorder.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for Kotlin Kotest projects. Validates that test-order
 * workflows run successfully on a Kotlin + Kotest fixture.
 *
 * Limitations: - Class-level ordering is supported (Kotest specs map to test
 * classes). - Method-level ordering may not align with Kotest's DSL-based test
 * definitions. - Dependency tracking works on compiled bytecode (Kotlin → JVM,
 * no language-specific handling).
 */
public class KotestFrameworkIT extends BaseFixtureIT {

	/**
	 * Baseline Kotest execution and combined mode workflow.
	 */
	@Test
	public void testKotestFixtureWithTestOrder(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-kotest", tempDir);

		// Baseline Kotest execution through JUnit platform
		String baselineOutput = runMaven(fixtureDir, "clean", "test");
		assertTestsPassed(baselineOutput);
		int baselineCount = getTestCount(baselineOutput);
		assertEquals(5, baselineCount, "Expected 5 Kotest cases in fixture");

		// Combined mode should run end-to-end on Kotest fixture
		String combinedOutput = runMaven(fixtureDir, "test-order:auto", "test");
		assertTestsPassed(combinedOutput);
		int combinedCount = getTestCount(combinedOutput);
		assertEquals(5, combinedCount, "Expected 5 Kotest cases when running test-order combined mode");
		assertIndexFilesExist(fixtureDir);

		// Re-running combined mode should preserve successful Kotest execution
		String secondCombinedOutput = runMaven(fixtureDir, "test-order:auto", "test");
		assertTestsPassed(secondCombinedOutput);
		int secondRunCount = getTestCount(secondCombinedOutput);
		assertEquals(5, secondRunCount, "Kotest case count should remain stable across test-order runs");
	}

	/**
	 * Verify learn mode successfully instruments Kotest tests and builds dependency
	 * index. This test validates that the agent can capture method-level
	 * dependencies in Kotest bytecode.
	 */
	@Test
	public void testKotestLearnModeBuildsIndex(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-kotest", tempDir);

		// Run in explicit learn mode
		String learnOutput = runMaven(fixtureDir, "clean", "test-order:prepare", "test");
		assertTestsPassed(learnOutput);

		// Learn mode should create dependency index
		assertIndexFilesExist(fixtureDir);
	}

	/**
	 * Verify order mode runs Kotest tests after dependency learning. This test
	 * validates class-level prioritization works correctly with Kotest on JUnit
	 * Platform. Note: Kotest specs are treated as single test classes; method-level
	 * ordering may differ from Jupiter.
	 */
	@Test
	public void testKotestOrderModeAfterLearn(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-kotest", tempDir);

		// First, learn dependencies using prepare mode
		String learnOutput = runMaven(fixtureDir, "clean", "test-order:prepare", "test");
		assertTestsPassed(learnOutput);
		assertIndexFilesExist(fixtureDir);

		// Then run again and verify tests still pass (order mode applied automatically)
		String orderOutput = runMaven(fixtureDir, "test-order:auto", "test");
		assertTestsPassed(orderOutput);
		int orderCount = getTestCount(orderOutput);
		assertEquals(5, orderCount, "Order mode should run all Kotest cases");
	}

	/**
	 * Verify Kotest works with method-level ordering enabled (even if no-op for
	 * spec styles). This test validates that enabling method ordering doesn't break
	 * Kotest execution.
	 */
	@Test
	public void testKotestWithMethodOrderingEnabled(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-kotest", tempDir);

		// Run combined mode with method ordering enabled
		// Note: Kotest spec styles may not have traditional method descriptors,
		// so this tests that the ordering doesn't error out on Kotest.
		String output = runMaven(fixtureDir, "clean", "-Dtestorder.methodOrder.enabled=true", "test-order:auto",
				"test");
		assertTestsPassed(output);
		int count = getTestCount(output);
		assertEquals(5, count, "Kotest with method ordering should still run all cases");
	}
}
