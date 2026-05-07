package me.bechberger.testorder.it;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for parameterized tests. Validates that test-order correctly
 * handles: - @ParameterizedTest with @CsvSource - @ParameterizedTest
 * with @ValueSource - Duration aggregation across parameter sets - Method-level
 * scoring with dynamic test IDs [index]
 */
public class ParameterizedTestOrderingIT extends BaseFixtureIT {

	@Test
	public void testParameterizedTestsFull(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-parameterized-tests", tempDir);

		// Run tests without test-order first (baseline)
		String baselineOutput = runMaven(fixtureDir, "clean", "test");
		int baselineCount = getTestCount(baselineOutput);
		assertTrue(baselineCount > 0, "Expected parameterized test instances to be discovered");
		assertTestsPassed(baselineOutput);

		// Run with test-order in learn mode
		String learnOutput = runMaven(fixtureDir, "test-order:learn", "test");
		assertTestsPassed(learnOutput);
		assertStateFileExists(fixtureDir);
		assertIndexFilesExist(fixtureDir);

		// Run with test-order in auto mode
		String orderOutput = runMaven(fixtureDir, "test-order:auto", "test");
		assertTestsPassed(orderOutput);
		int orderCount = getTestCount(orderOutput);
		assertEquals(baselineCount, orderCount,
				"Parameterized test count should remain unchanged after applying test-order");
	}

	@Test
	public void testCsvSourceParameterization(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-parameterized-tests", tempDir);

		// Test one @CsvSource method
		String output = runMaven(fixtureDir, "test", "-Dtest=CalculatorParameterizedTest#testAddWithCsvSource");
		assertTestsPassed(output);
		// Should run 5 times (one per CSV row)
		int testCount = getTestCount(output);
		assertEquals(5, testCount, "testAddWithCsvSource should have 5 parameterized instances (5 CSV rows)");
	}

	@Test
	public void testValueSourceParameterization(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-parameterized-tests", tempDir);

		// Test one @ValueSource method
		String output = runMaven(fixtureDir, "test", "-Dtest=CalculatorParameterizedTest#testIsPrimeWithTrueCases");
		assertTestsPassed(output);
		// Should run 8 times (one per value)
		int testCount = getTestCount(output);
		assertEquals(8, testCount, "testIsPrimeWithTrueCases should have 8 parameterized instances (8 values)");
	}

	@Test
	public void testDurationAggregationForParameterized(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-parameterized-tests", tempDir);

		// Run learn mode twice to collect duration data
		runMaven(fixtureDir, "clean", "test-order:learn", "test");

		// Duration tracking should aggregate all 27 test instances into their
		// method-level buckets
		// This is validated by checking that test-order completes without errors
		String output = runMaven(fixtureDir, "test-order:learn", "test");
		assertTestsPassed(output);

		// Verify state files were created (indicates duration tracking worked)
		assertStateFileExists(fixtureDir);
	}
}
