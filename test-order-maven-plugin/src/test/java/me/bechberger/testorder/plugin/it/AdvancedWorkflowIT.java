package me.bechberger.testorder.plugin.it;

import static me.bechberger.testorder.plugin.it.TestOrderAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;

/**
 * Advanced workflow tests — select mode, combined mode, state persistence,
 * scoring verification, and re-order after source modification.
 * <p>
 * Uses the test-order-example project (CalculatorTest + StringUtilsTest).
 * <p>
 * Enable with: {@code -Dtestorder.it=true}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdvancedWorkflowIT {

	static final String CALCULATOR_TEST = "com.example.app.CalculatorTest";
	static final String STRING_UTILS_TEST = "com.example.app.StringUtilsTest";
	static final String CALCULATOR = "com.example.app.Calculator";
	static final String MATH_HELPER = "com.example.app.MathHelper";
	static final String STRING_UTILS = "com.example.app.StringUtils";

	TestProject project;

	@BeforeAll
	void setup() {
		Path root = Paths.get("").toAbsolutePath();
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		project = new TestProject(root.resolve("test-order-example"),
				List.of("-Dtestorder.includePackages=com.example"));
	}

	@AfterAll
	void tearDown() {
		if (project != null) {
			project.restoreAll();
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// 1. CLEAN LEARN (prerequisite for all subsequent tests)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(1)
	@DisplayName("Learn: clean build produces index")
	void learnProducesIndex() {
		project.cleanAll();
		MavenResult result = project.maven().learn();
		assertThat(result).succeeded();
		assertThat(project.loadIndex()).isLoaded().hasSize(2);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 2. STATE FILE — durations tracked after order-mode run
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(10)
	@DisplayName("State: order-mode run records durations in .test-order-state")
	void orderModeRecordsDurations() {
		MavenResult result = project.maven().order(CALCULATOR);
		assertThat(result).succeeded();

		TestOrderState state = project.loadState();
		assertThat(state).isNotNull();

		// At least one duration should be recorded
		long calcDur = state.getDuration(CALCULATOR_TEST, -1);
		long strDur = state.getDuration(STRING_UTILS_TEST, -1);
		assertThat(calcDur >= 0 || strDur >= 0).as("At least one test duration should be tracked").isTrue();
	}

	// ═══════════════════════════════════════════════════════════════════
	// 2b. SHOW-ORDER FORMAT — column headers present in output
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(15)
	@DisplayName("show-order: output contains expected column headers (#, Test Class, Score, Deps)")
	void showOrderOutputContainsColumnHeaders() {
		MavenResult result = project.maven().showOrder(CALCULATOR);
		assertThat(result).succeeded();

		String output = result.output();
		assertThat(output).contains("#");
		assertThat(output).contains("Test Class");
		assertThat(output).contains("Score");
		assertThat(output).contains("Deps");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 3. SHOW-ORDER — explicit changed classes affect scoring
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(20)
	@DisplayName("show-order: Calculator change gives CalculatorTest higher score")
	void showOrderCalculatorChangeScoresHigher() {
		MavenResult result = project.maven().showOrder(CALCULATOR);
		assertThat(result).succeeded();

		int calcScore = extractScore(result.output(), "CalculatorTest");
		int strScore = extractScore(result.output(), "StringUtilsTest");

		assertThat(calcScore).as("CalculatorTest score should be >= StringUtilsTest score when Calculator changed")
				.isGreaterThanOrEqualTo(strScore);
	}

	@Test
	@Order(21)
	@DisplayName("show-order: StringUtils change gives StringUtilsTest higher score")
	void showOrderStringUtilsChangeScoresHigher() {
		MavenResult result = project.maven().showOrder(STRING_UTILS);
		assertThat(result).succeeded();

		int strScore = extractScore(result.output(), "StringUtilsTest");
		int calcScore = extractScore(result.output(), "CalculatorTest");

		assertThat(strScore).as("StringUtilsTest score should be > CalculatorTest score when StringUtils changed")
				.isGreaterThan(calcScore);
	}

	@Test
	@Order(22)
	@DisplayName("show-order: no changes → both tests score zero dep overlap")
	void showOrderNoChanges() {
		MavenResult result = project.maven().showOrder(); // no changed classes
		assertThat(result).succeeded().outputContains("CalculatorTest").outputContains("StringUtilsTest");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 4. SELECT MODE — fast subset
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(30)
	@DisplayName("select: runs a subset and writes remaining file")
	void selectModeRunsSubset() {
		MavenResult result = project.maven().select(CALCULATOR);
		assertThat(result).succeeded().outputContains("Tests run:");

		// Selected and remaining files should be written
		assertThat(
				project.exists("target/test-order-selected.txt") || project.exists("target/test-order-remaining.txt"))
				.as("Select mode should write selected or remaining file").isTrue();
	}

	// ═══════════════════════════════════════════════════════════════════
	// 5. COMBINED MODE
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(40)
	@DisplayName("combined: runs tests successfully")
	void combinedModeRuns() {
		MavenResult result = project.maven().auto();
		assertThat(result).succeeded().outputContains("Tests run:");
	}

	@Test
	@Order(41)
	@DisplayName("combined mode=order: no index → warns and skips selection, does not learn")
	void combinedOrderModeNoIndexDoesNotLearn() {
		project.cleanAll();
		// No index exists at this point
		MavenResult result = project.maven().autoWithMode("order");
		// Build should succeed (tests still run in default order), but must not trigger
		// a learn pass
		assertThat(result.output()).doesNotContain("[test-order] Learn mode")
				.doesNotContain("[test-order] Saved source hash snapshot");
		// The warning message for missing index should appear
		assertThat(result.output()).containsAnyOf("No dependency index found", "skipping selection", "mode is 'order'");
	}

	@Test
	@Order(42)
	@DisplayName("combined mode=learn: always learns even when index already exists")
	void combinedLearnModeAlwaysLearns() {
		// Ensure index exists from previous combined run
		project.cleanAll();
		project.maven().learn();

		// Now run combined with mode=learn — should still run learn regardless
		MavenResult result = project.maven().autoWithMode("learn");
		assertThat(result).succeeded();
		assertThat(result.output()).contains("[test-order] Learn mode");
	}

	@Test
	@Order(43)
	@DisplayName("combined mode=skip: completes without test-order configuration")
	void combinedSkipModeDoesNothing() {
		MavenResult result = project.maven().autoWithMode("skip");
		assertThat(result).succeeded();
		// skip mode must not configure learn or order selection
		assertThat(result.output()).doesNotContain("[test-order] Learn mode").doesNotContain("[test-order] Order mode")
				.doesNotContain("[test-order] Selected");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 6. METHOD_ENTRY MODE
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(50)
	@DisplayName("Learn METHOD_ENTRY: produces valid index")
	void learnMethodEntryProducesIndex() {
		project.cleanAll();
		MavenResult result = project.maven().learnMethodEntry();
		assertThat(result).succeeded();

		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).isLoaded().hasSize(2);

		// basic dependencies should still be captured
		assertThat(depMap).hasDependency(CALCULATOR_TEST, CALCULATOR).hasDependency(CALCULATOR_TEST, MATH_HELPER)
				.hasDependency(STRING_UTILS_TEST, STRING_UTILS);
	}

	@Test
	@Order(51)
	@DisplayName("Learn METHOD_ENTRY → order: full cycle works")
	void methodEntryFollowedByOrderMode() {
		// index from previous test
		MavenResult result = project.maven().order(CALCULATOR);
		assertThat(result).succeeded().outputContains("Tests run:");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 7. RE-ORDER AFTER SOURCE MODIFICATION
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(60)
	@DisplayName("Modify source → re-learn → index captures new method")
	void relearnAfterSourceChange() {
		project.cleanAll();
		MavenResult r1 = project.maven().learn();
		assertThat(r1).succeeded();

		DependencyMap before = project.loadIndex();
		assertThat(before).isLoaded().hasSize(2);

		try {
			// Add a new method to Calculator
			project.replaceInFile("src/main/java/com/example/app/Calculator.java", "public int add(int a, int b) {", """
					public int negate(int a) {
					    return mathHelper.multiply(a, -1);
					}

					public int add(int a, int b) {""");

			project.deleteIfExists("test-dependencies.lz4");
			project.deleteTree("target");

			MavenResult r2 = project.maven().learn();
			assertThat(r2).succeeded();

			DependencyMap after = project.loadIndex();
			// Dependencies should still be correct after code change
			assertThat(after).isLoaded().hasSize(2).hasDependency(CALCULATOR_TEST, CALCULATOR)
					.hasDependency(CALCULATOR_TEST, MATH_HELPER).hasDependency(STRING_UTILS_TEST, STRING_UTILS);
		} finally {
			project.restoreAll();
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// 8. ERROR HANDLING — order without index
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(70)
	@DisplayName("Order mode without .deps dir fails gracefully (no deps to aggregate)")
	void orderWithoutDepsDir() {
		project.cleanAll();
		// order mode should fall back to aggregation, but with no deps dir
		// and no existing index, it should warn but still run tests
		MavenResult result = project.maven().run("clean", "test", "-Dtestorder.mode=order",
				"-Dtestorder.changeMode=explicit");
		// It may succeed (runs tests in default order) or fail — either is acceptable
		// as long as it doesn't crash with an unhandled exception
		assertThat(result.output()).doesNotContain("NullPointerException").doesNotContain("ClassNotFoundException");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 9. AGGREGATE GOAL (standalone)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(80)
	@DisplayName("Aggregate: explicit aggregate with existing index merges correctly")
	void aggregateGoalStandalone() {
		project.cleanAll();

		// Learn (produces index via direct merge)
		MavenResult r1 = project.maven().learn();
		assertThat(r1).succeeded();

		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).isLoaded().hasSize(2);

		// Running aggregate again should succeed (merges into existing index)
		MavenResult r2 = project.maven().aggregate();
		assertThat(r2).succeeded();

		DependencyMap afterAggregate = project.loadIndex();
		assertThat(afterAggregate).isLoaded().hasSize(2);

		// All original test classes should still be present
		for (String tc : depMap.testClasses()) {
			assertThat(afterAggregate).hasTestClass(tc);
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// Helpers
	// ═══════════════════════════════════════════════════════════════════

	/**
	 * Extract the score for a test class from show-order output. The output uses
	 * abbreviated names: "c.e.app.CalculatorTest" and columns separated by
	 * whitespace (not pipes). Format:
	 *
	 * <pre>
	 *   1.   c.e.app.CalculatorTest      5     1                    27ms
	 * </pre>
	 */
	private int extractScore(String output, String testClassName) {
		for (String line : output.lines().toList()) {
			if (!line.contains(testClassName))
				continue;
			// Columns are whitespace-separated: #, name, score, ...
			String[] parts = line.trim().split("\\s+");
			// parts[0] = "1.", parts[1] = "c.e.app.CalculatorTest", parts[2] = score
			if (parts.length >= 3) {
				try {
					return Integer.parseInt(parts[2]);
				} catch (NumberFormatException ignored) {
					// not a score line
				}
			}
		}
		throw new AssertionError("Could not find score for " + testClassName + " in output:\n" + output);
	}
}
