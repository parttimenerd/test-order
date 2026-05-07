package me.bechberger.testorder.it;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for TestNG projects. Validates that test-order workflows
 * (learn, order, auto) run successfully on a project using TestNG instead of
 * JUnit Jupiter.
 */
public class TestNGFrameworkIT extends BaseFixtureIT {

	private static final int EXPECTED_TEST_COUNT = 16;

	@Test
	public void testTestNGFixtureWithTestOrder(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-testng", tempDir);

		// Baseline TestNG execution
		String baselineOutput = runMaven(fixtureDir, "clean", "test");
		assertTestsPassed(baselineOutput);
		assertEquals(EXPECTED_TEST_COUNT, getTestCount(baselineOutput),
				"Expected " + EXPECTED_TEST_COUNT + " TestNG tests in fixture");

		// Auto mode should run end-to-end on TestNG fixture
		String autoOutput = runMaven(fixtureDir, "test-order:auto", "test");
		assertTestsPassed(autoOutput);
		assertEquals(EXPECTED_TEST_COUNT, getTestCount(autoOutput),
				"Expected " + EXPECTED_TEST_COUNT + " TestNG tests in auto mode");
		assertIndexFilesExist(fixtureDir);

		// Re-running auto mode should preserve successful execution
		String secondOutput = runMaven(fixtureDir, "test-order:auto", "test");
		assertTestsPassed(secondOutput);
		assertEquals(EXPECTED_TEST_COUNT, getTestCount(secondOutput),
				"Test count should remain stable across test-order runs");
	}

	@Test
	public void testTestNGLearnModeBuildsIndex(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-testng", tempDir);

		String learnOutput = runMaven(fixtureDir, "clean", "test-order:prepare", "test");
		assertTestsPassed(learnOutput);
		assertIndexFilesExist(fixtureDir);
	}

	@Test
	public void testTestNGOrderModeAfterLearn(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-testng", tempDir);

		// Learn dependencies
		String learnOutput = runMaven(fixtureDir, "clean", "test-order:prepare", "test");
		assertTestsPassed(learnOutput);
		assertIndexFilesExist(fixtureDir);

		// Run again with order applied
		String orderOutput = runMaven(fixtureDir, "test-order:auto", "test");
		assertTestsPassed(orderOutput);
		assertEquals(EXPECTED_TEST_COUNT, getTestCount(orderOutput), "Order mode should run all TestNG tests");
	}

	/**
	 * Verify that reordering actually works: learn dependencies, then simulate a
	 * change to a source class and verify the test that exercises it runs first.
	 */
	@Test
	public void testTestNGReorderingPrioritizesAffectedClass(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-testng", tempDir);

		// Learn dependencies
		String learnOutput = runMaven(fixtureDir, "clean", "test-order:prepare", "test");
		assertTestsPassed(learnOutput);
		assertIndexFilesExist(fixtureDir);

		// Run show-order with a simulated change to CollectionHelper
		// This should boost CollectionHelperTest to the top
		String showOrderOutput = runMaven(fixtureDir, "test-order:show-order",
				"-Dtestorder.changed.classes=com.example.util.CollectionHelper");

		// Verify CollectionHelperTest appears before the other test classes in the
		// output
		int collectionPos = showOrderOutput.indexOf("CollectionHelperTest");
		int stringPos = showOrderOutput.indexOf("StringHelperTest");
		int mathPos = showOrderOutput.indexOf("MathHelperTest");
		assertTrue(collectionPos >= 0, "CollectionHelperTest should appear in show-order output");
		assertTrue(stringPos >= 0, "StringHelperTest should appear in show-order output");
		assertTrue(mathPos >= 0, "MathHelperTest should appear in show-order output");
		assertTrue(collectionPos < stringPos,
				"CollectionHelperTest should be ranked before StringHelperTest when CollectionHelper is changed");
		assertTrue(collectionPos < mathPos,
				"CollectionHelperTest should be ranked before MathHelperTest when CollectionHelper is changed");

		// Now simulate a change to StringHelper instead — StringHelperTest should come
		// first
		String showOrderOutput2 = runMaven(fixtureDir, "test-order:show-order",
				"-Dtestorder.changed.classes=com.example.util.StringHelper");

		int collectionPos2 = showOrderOutput2.indexOf("CollectionHelperTest");
		int stringPos2 = showOrderOutput2.indexOf("StringHelperTest");
		assertTrue(stringPos2 >= 0, "StringHelperTest should appear in show-order output");
		assertTrue(collectionPos2 >= 0, "CollectionHelperTest should appear in show-order output");
		assertTrue(stringPos2 < collectionPos2,
				"StringHelperTest should be ranked before CollectionHelperTest when StringHelper is changed");
	}
}
