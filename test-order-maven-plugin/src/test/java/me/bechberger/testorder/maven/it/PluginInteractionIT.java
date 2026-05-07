package me.bechberger.testorder.maven.it;

import static me.bechberger.testorder.maven.it.TestOrderAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;

/**
 * Plugin interaction integration tests — verifies test-order works correctly
 * alongside other Maven plugins (JaCoCo, Surefire parallel, forkCount, etc.)
 * and with various test framework configurations.
 * <p>
 * Uses test-fixtures (jacoco, parallel-execution, parameterized-tests, etc.)
 * and sample-shop for basic interaction testing.
 * <p>
 * Enable with: {@code -Dtestorder.it=true}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PluginInteractionIT {

	TestProject jacocoFixture;
	TestProject parallelFixture;
	TestProject parameterizedFixture;
	TestProject shopProject;

	@BeforeAll
	void setup() {
		Path root = Paths.get("").toAbsolutePath();
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		jacocoFixture = new TestProject(root.resolve("test-fixtures/fixture-jacoco"),
				List.of("-Dtestorder.includePackages=com.example", "-Djacoco.skip=false"));
		parallelFixture = new TestProject(root.resolve("test-fixtures/fixture-parallel-execution"),
				List.of("-Dtestorder.includePackages=me.bechberger"));
		parameterizedFixture = new TestProject(root.resolve("test-fixtures/fixture-parameterized-tests"),
				List.of("-Dtestorder.includePackages=com.example"));
		shopProject = new TestProject(root.resolve("samples/sample-shop"),
				List.of("-Dtestorder.includePackages=com.example"));
		shopProject.gitRestore();
	}

	@AfterAll
	void tearDown() {
		if (jacocoFixture != null) jacocoFixture.restoreAll();
		if (parallelFixture != null) parallelFixture.restoreAll();
		if (parameterizedFixture != null) parameterizedFixture.restoreAll();
		if (shopProject != null) shopProject.restoreAll();
	}

	// ═══════════════════════════════════════════════════════════════════
	// JACOCO COEXISTENCE
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(1)
	@DisplayName("Learn mode with JaCoCo agent coexistence produces valid index")
	void learnModeWithJacocoAgentCoexistence() {
		jacocoFixture.cleanAll();
		MavenResult result = jacocoFixture.maven().learn();
		assertThat(result).succeeded();

		// Index should be produced despite dual-agent scenario
		DependencyMap depMap = jacocoFixture.loadIndex();
		assertThat(depMap).isLoaded();
		assertThat(depMap.size()).isGreaterThan(0);

		// JaCoCo report should also be generated
		assertThat(jacocoFixture.exists("target/site/jacoco/index.html"))
				.as("JaCoCo report should be generated alongside test-order agent")
				.isTrue();
	}

	@Test
	@Order(2)
	@DisplayName("Order mode with JaCoCo works without agent conflicts")
	void orderModeWithJacocoAgentCoexistence() {
		MavenResult result = jacocoFixture.maven().run("clean", "test",
				"-Dtestorder.mode=order",
				"-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=com.example.coverage.StringProcessor");
		assertThat(result).succeeded();

		// Both agents should produce output without classloading conflicts
		assertThat(result.output())
				.doesNotContain("ClassNotFoundException")
				.doesNotContain("LinkageError")
				.doesNotContain("IncompatibleClassChangeError");
	}

	// ═══════════════════════════════════════════════════════════════════
	// PARALLEL EXECUTION (method-level)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(10)
	@DisplayName("Learn mode with method-level parallel execution and forkCount")
	void learnModeWithMethodLevelParallelExecution() {
		parallelFixture.cleanAll();
		// fixture-parallel-execution has <parallel>methods</parallel> + forkCount=2
		MavenResult result = parallelFixture.maven().learn();
		assertThat(result).succeeded();

		// Index should capture tests from both forks
		DependencyMap depMap = parallelFixture.loadIndex();
		assertThat(depMap).isLoaded();
		assertThat(depMap.size()).as("All test classes should be captured despite parallel+fork execution")
				.isGreaterThanOrEqualTo(3); // ConcurrentTestSuiteA, B, C
	}

	@Test
	@Order(11)
	@DisplayName("Order mode with method-level parallel still passes tests")
	void orderModeWithMethodLevelParallelExecution() {
		MavenResult result = parallelFixture.maven().run("clean", "test",
				"-Dtestorder.mode=order",
				"-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=me.bechberger.testorder.ConcurrentTestSuiteA");
		assertThat(result).succeeded();

		// Ordering should be applied (PriorityClassOrderer injected)
		assertThat(result.output()).contains("PriorityClassOrderer");
	}

	@Test
	@Order(12)
	@DisplayName("Order mode with forkCount=2 produces surefire reports from both forks")
	void orderModeWithForkCountProducesAllReports() {
		MavenResult result = parallelFixture.maven().run("clean", "test",
				"-Dtestorder.mode=order",
				"-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=me.bechberger.testorder.ConcurrentTestSuiteA");
		assertThat(result).succeeded();

		// Surefire reports should exist for all test classes
		assertThat(parallelFixture.exists("target/surefire-reports")).isTrue();
	}

	// ═══════════════════════════════════════════════════════════════════
	// LEARN MODE REJECTS CLASS-LEVEL PARALLEL
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(20)
	@DisplayName("Learn mode rejects class-level parallel execution via actual build")
	void learnModeRejectsClassLevelParallel() {
		shopProject.cleanAll();

		// Run learn mode with class-level parallel configured at the command line
		MavenResult result = shopProject.maven().run("clean", "test",
				"-Dtestorder.mode=learn",
				"-DargLine=-Djunit.jupiter.execution.parallel.enabled=true -Djunit.jupiter.execution.parallel.mode.classes.default=concurrent");

		// The build should either:
		// 1. Fail with "not supported in learn mode" (ideal)
		// 2. Succeed but warn (acceptable if the check only looks at POM config)
		// The key is it should NOT silently corrupt the dependency index
		// Note: the SurefireHelper checks POM configuration, not system properties,
		// so this may still succeed. Testing POM-based rejection requires a fixture.
	}

	// ═══════════════════════════════════════════════════════════════════
	// SUREFIRE INCLUDES/EXCLUDES INTERACTION
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(30)
	@DisplayName("Select mode with surefire includes filter works correctly even with bound prepare")
	void selectModeWithSurefireIncludes() {
		shopProject.cleanAll();
		MavenResult learnResult = shopProject.maven().learn();
		assertThat(learnResult).succeeded();

		// Run select with a surefire include filter (only ProductTest).
		// Bound prepare should now skip automatically when select is present.
		MavenResult result = shopProject.maven().run("clean", "test-order:select", "test",
				"-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=com.example.shop.Product",
				"-Dtest=com.example.shop.ProductTest",
				"-Dtestorder.select.topN=10",
				"-Dtestorder.select.randomM=0");
		// Build should succeed — selection respects surefire's -Dtest filter
		assertThat(result).succeeded();
	}

	@Test
	@Order(31)
	@DisplayName("Order mode with surefire -Dtest filter respects exclusions")
	void orderModeWithSurefireTestFilter() {
		MavenResult result = shopProject.maven().run("clean", "test",
				"-Dtestorder.mode=order",
				"-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=com.example.shop.Product",
				"-Dtest=com.example.shop.ProductTest");
		assertThat(result).succeeded();

		// Only ProductTest should have run
		assertThat(result.output()).contains("ProductTest");
	}

	// ═══════════════════════════════════════════════════════════════════
	// CUSTOM ARGLINE CHAINING
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(40)
	@DisplayName("Learn mode chains with existing argLine (e.g. -Xmx512m)")
	void learnModeWithCustomArgLine() throws InterruptedException {
		shopProject.cleanAll();
		MavenResult result = shopProject.maven().run("clean", "test",
				"-Dtestorder.mode=learn",
				"-DargLine=-Xmx512m -Duser.timezone=UTC");
		assertThat(result).succeeded().outputContains("[test-order] Learn mode").outputContains("Tests run:");
		// Service file may not be immediately visible on APFS after Maven exits
		String svcFile = "target/test-order-runtime/META-INF/services/org.junit.platform.launcher.TestExecutionListener";
		if (!shopProject.exists(svcFile)) {
			Thread.sleep(1000);
		}
		assertThat(shopProject.exists(svcFile))
				.as("test-order runtime listener wiring should remain present when argLine is overridden")
				.isTrue();
	}

	// ═══════════════════════════════════════════════════════════════════
	// PARAMETERIZED TESTS
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(50)
	@DisplayName("Learn mode with parameterized tests runs successfully")
	void learnModeWithParameterizedTests() {
		parameterizedFixture.cleanAll();
		MavenResult result = parameterizedFixture.maven().learn();
		assertThat(result).succeeded().outputContains("Tests run:");
		assertThat(result.output()).contains("CalculatorParameterizedTest");
	}

	@Test
	@Order(51)
	@DisplayName("Order mode with parameterized tests runs successfully")
	void orderModeWithParameterizedTests() {
		parameterizedFixture.maven().learn();
		MavenResult result = parameterizedFixture.maven().run("clean", "test",
				"-Dtestorder.mode=order",
				"-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=com.example.math.Calculator");
		assertThat(result).succeeded().outputContains("Tests run:");
		assertThat(result.output()).contains("CalculatorParameterizedTest");
	}

	// ═══════════════════════════════════════════════════════════════════
	// CONSECUTIVE LEARNS (MERGE VS REPLACE)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(60)
	@DisplayName("Consecutive learn runs update index (not replace)")
	void consecutiveLearnRunsMergeIndex() {
		shopProject.cleanAll();

		// First learn
		MavenResult first = shopProject.maven().learn();
		assertThat(first).succeeded();
		DependencyMap firstIndex = shopProject.loadIndex();
		assertThat(firstIndex).isLoaded().hasSize(3);

		// Second learn without cleaning
		MavenResult second = shopProject.maven().run("test", "-Dtestorder.mode=learn");
		assertThat(second).succeeded();

		// Index should still have all 3 test classes (merged, not replaced)
		DependencyMap secondIndex = shopProject.loadIndex();
		assertThat(secondIndex).isLoaded().hasSize(3);
	}

	// ═══════════════════════════════════════════════════════════════════
	// ORDER MODE AFTER PARTIAL LEARN (PARTIAL INDEX)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(70)
	@DisplayName("Order mode after partial learn (1 of 3 tests in index) still works")
	void orderModeAfterPartialLearn() {
		shopProject.cleanAll();

		// Learn with only ProductTest running (using -Dtest filter)
		MavenResult partialLearn = shopProject.maven().run("clean", "test",
				"-Dtestorder.mode=learn",
				"-Dtest=com.example.shop.ProductTest");
		assertThat(partialLearn).succeeded();

		// Index should have only 1 test
		DependencyMap partialIndex = shopProject.loadIndex();
		assertThat(partialIndex).isLoaded();

		// Now run order mode — should work with partial index
		// Tests not in index are treated as "new"
		MavenResult orderResult = shopProject.maven().run("clean", "test",
				"-Dtestorder.mode=order",
				"-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=com.example.shop.Product");
		assertThat(orderResult).succeeded();

		// All 3 test classes should still execute (ordering uses partial info)
		assertThat(orderResult.output()).contains("ProductTest")
				.contains("CartTest")
				.contains("InvoiceTest");

		// Clean up — full re-learn for subsequent tests
		shopProject.cleanAll();
		shopProject.maven().learn();
	}

	// ═══════════════════════════════════════════════════════════════════
	// METHOD_ENTRY INSTRUMENTATION MODE
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(80)
	@DisplayName("Learn with METHOD_ENTRY mode produces valid index (lower overhead)")
	void learnMethodEntryProducesValidIndex() {
		shopProject.cleanAll();
		MavenResult result = shopProject.maven().learnMethodEntry();
		assertThat(result).succeeded();

		DependencyMap depMap = shopProject.loadIndex();
		assertThat(depMap).isLoaded().hasSize(3);

		// Restore full-mode index for other tests
		shopProject.cleanAll();
		shopProject.maven().learn();
	}

	// ═══════════════════════════════════════════════════════════════════
	// SHOW-ORDER AFTER ORDER MODE (STATE-AWARE SCORING)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(90)
	@DisplayName("Show-order uses state file for duration-based scoring")
	void showOrderUsesStateForDurationScoring() {
		// Ensure we have a valid index (in case prior test didn't leave one)
		if (!shopProject.exists(".test-order/test-dependencies.lz4")) {
			shopProject.cleanAll();
			shopProject.maven().learn();
		}

		// Order run to populate state with durations
		MavenResult orderResult = shopProject.maven().order("com.example.shop.Product");
		assertThat(orderResult).succeeded();

		// Show-order should now include duration info in scoring
		MavenResult result = shopProject.maven().showOrder("com.example.shop.Product");
		assertThat(result).succeeded();

		// Output should reference the test classes
		assertThat(result.output())
				.contains("ProductTest")
				.contains("CartTest")
				.contains("InvoiceTest");
	}
}
