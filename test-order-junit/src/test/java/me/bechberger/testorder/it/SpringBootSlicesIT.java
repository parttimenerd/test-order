package me.bechberger.testorder.it;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for Spring Boot test slices. Validates that test-order works
 * correctly with: - @DataJpaTest (database-only context) - @WebMvcTest
 * (servlet+Spring context) - @SpringBootTest (full application context)
 *
 * Tests that slices maintain proper context isolation and don't pollute each
 * other's state.
 */
public class SpringBootSlicesIT extends BaseFixtureIT {

	@Test
	public void testSpringBootSlicesFull(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-spring-boot-slices", tempDir);

		// Run tests without test-order first (baseline)
		String baselineOutput = runMaven(fixtureDir, "clean", "test");
		int baselineCount = getTestCount(baselineOutput);
		assertEquals(9, baselineCount, "Expected 9 tests in Spring Boot fixture");
		assertTestsPassed(baselineOutput);

		// Run with test-order in learn mode
		String learnOutput = runMaven(fixtureDir, "test-order:learn", "test");
		assertTestsPassed(learnOutput);
		assertStateFileExists(fixtureDir);
		assertIndexFilesExist(fixtureDir);

		// Run with test-order in auto mode (orders when index exists)
		String orderOutput = runMaven(fixtureDir, "test-order:auto", "test");
		assertTestsPassed(orderOutput);
		int orderCount = getTestCount(orderOutput);
		assertEquals(9, orderCount, "Test count should remain 9 after reordering");
	}

	@Test
	public void testDataJpaTestIsolation(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-spring-boot-slices", tempDir);

		// @DataJpaTest tests should only load database context
		// Run specific test class
		String output = runMaven(fixtureDir, "test", "-Dtest=PetRepositoryDataJpaTest");
		assertTestsPassed(output);
		int testCount = getTestCount(output);
		assertEquals(3, testCount, "PetRepositoryDataJpaTest should have 3 tests");
	}

	@Test
	public void testWebMvcTestIsolation(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-spring-boot-slices", tempDir);

		// @WebMvcTest tests should only load servlet+Spring context
		String output = runMaven(fixtureDir, "test", "-Dtest=PetControllerWebMvcTest");
		assertTestsPassed(output);
		int testCount = getTestCount(output);
		assertEquals(3, testCount, "PetControllerWebMvcTest should have 3 tests");
	}

	@Test
	public void testSpringBootTestFull(@TempDir Path tempDir) throws Exception {
		Path fixtureDir = copyFixtureToTemp("fixture-spring-boot-slices", tempDir);

		// @SpringBootTest loads full application context
		String output = runMaven(fixtureDir, "test", "-Dtest=PetShopIntegrationTest");
		assertTestsPassed(output);
		int testCount = getTestCount(output);
		assertEquals(3, testCount, "PetShopIntegrationTest should have 3 tests");
	}
}
