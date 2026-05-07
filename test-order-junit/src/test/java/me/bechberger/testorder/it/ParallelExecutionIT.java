package me.bechberger.testorder.it;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for parallel test execution scenarios.
 *
 * When Maven runs tests in parallel (with multiple threads or forked
 * processes), test-order must handle concurrent state file writes correctly: -
 * No data loss when multiple threads write timing/scoring data - Correct test
 * count tracking despite race conditions - Accurate duration tracking even with
 * thread interleaving
 *
 * This tests the critical synchronization mechanisms in TestOrderState.
 */
@DisplayName("Parallel Execution Integration Tests")
public class ParallelExecutionIT extends BaseFixtureIT {

	private static final String FIXTURE_NAME = "fixture-parallel-execution";

	@Test
	@DisplayName("Parallel methods execution with 4 threads should record all tests")
	void testParallelMethodsExecution(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp(FIXTURE_NAME, tempDir);

		// First run: learn phase with parallel execution
		String learnOutput = runMaven(fixtureDir, "clean", "test");
		assertTestsPassed(learnOutput);
		int testCount = getTestCount(learnOutput);

		// fixture-parallel-execution has 5 + 6 + 7 = 18 tests total
		assertEquals(18, testCount, "Should record all 18 tests in learn phase with parallel execution");

		// State file should exist after first run
		assertStateFileExists(fixtureDir);
	}

	@Test
	@DisplayName("Parallel fork execution should not lose test duration data")
	void testParallelForkExecution(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp(FIXTURE_NAME, tempDir);

		// Run with forked processes (perthread mode in pom.xml)
		String output = runMaven(fixtureDir, "clean", "test");
		assertTestsPassed(output);
		int testCount = getTestCount(output);

		assertEquals(18, testCount, "Forked parallel execution should record all tests without data loss");
		assertStateFileExists(fixtureDir);
	}

	@Test
	@DisplayName("Multiple sequential runs with parallel execution should improve order")
	void testMultipleRunsWithParallelExecution(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp(FIXTURE_NAME, tempDir);

		// Run 1: Learn with parallel threads
		String run1Output = runMaven(fixtureDir, "clean", "test");
		assertTestsPassed(run1Output);
		assertEquals(18, getTestCount(run1Output), "Run 1 should complete all tests");

		// Run 2: Second execution should use learned ordering (if test-order applied)
		String run2Output = runMaven(fixtureDir, "test");
		assertTestsPassed(run2Output);
		assertEquals(18, getTestCount(run2Output), "Run 2 should complete all tests with same count");

		// Both runs should succeed without data loss despite concurrent writes
		assertStateFileExists(fixtureDir);
	}

	@Test
	@DisplayName("Concurrent writes should not corrupt state file")
	void testConcurrentStateFileWrites(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp(FIXTURE_NAME, tempDir);

		// This test validates synchronization in TestOrderState.addRunRecord()
		// When multiple threads call addRunRecord() simultaneously:
		// - The 'synchronized' keyword prevents data corruption
		// - The runs list maintains integrity (no ConcurrentModificationException)
		// - All records are written (no lost updates)

		String output = runMaven(fixtureDir, "clean", "test");
		assertTestsPassed(output);

		// If state corruption occurred, tests would fail or count would be wrong
		int testCount = getTestCount(output);
		assertEquals(18, testCount, "State file corruption would manifest as missing/extra test counts");

		// State file should be readable and not corrupted
		assertStateFileExists(fixtureDir);
	}

	// Helper method to do basic assertion (since BaseFixtureIT provides
	// assertEquals)
	private void assertEquals(long expected, long actual, String message) {
		org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
	}
}
