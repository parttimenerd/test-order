package me.bechberger.testorder.plugin.it;

import static me.bechberger.testorder.plugin.it.TestOrderAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import me.bechberger.testorder.DependencyMap;

/**
 * Abstract base for end-to-end integration tests against the test-order Maven
 * plugin.
 * <p>
 * Each subclass specifies a target project via {@link #projectDirName()}. The
 * target project must contain:
 * <ul>
 * <li>{@code CalculatorTest} → depends on {@code Calculator},
 * {@code MathHelper}</li>
 * <li>{@code StringUtilsTest} → depends on {@code StringUtils}</li>
 * </ul>
 * <p>
 * Tests are ordered so that learn mode runs first (builds the index), then
 * subsequent tests use that index.
 * <p>
 * Enable with: {@code -Dtestorder.it=true}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractEndToEndIT {

	// ── Test class & source FQCNs (referenced throughout) ─────────────
	static final String CALCULATOR_TEST = "com.example.app.CalculatorTest";
	static final String STRING_UTILS_TEST = "com.example.app.StringUtilsTest";
	static final String CALCULATOR = "com.example.app.Calculator";
	static final String MATH_HELPER = "com.example.app.MathHelper";
	static final String STRING_UTILS = "com.example.app.StringUtils";

	static final String CALCULATOR_SRC = "src/main/java/com/example/app/Calculator.java";
	static final String STRING_UTILS_SRC = "src/main/java/com/example/app/StringUtils.java";

	TestProject project;

	/** Return the project directory name (relative to workspace root). */
	protected abstract String projectDirName();

	@BeforeAll
	void setup() {
		Path root = Paths.get("").toAbsolutePath();
		// handle running from test-order-maven-plugin or project root
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		project = new TestProject(root.resolve(projectDirName()), List.of("-Dtestorder.includePackages=com.example"));
	}

	@AfterAll
	void tearDown() {
		if (project != null) {
			project.restoreAll();
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// 1. LEARN MODE
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(1)
	@DisplayName("Learn mode: runs tests and produces dependency index")
	void learnModeProducesIndex() {
		project.cleanAll();

		MavenResult result = project.maven().learn();
		assertThat(result).succeeded().outputContains("[test-order]").outputContains("Tests run:");

		// The dependency index should exist now
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).isLoaded().hasSize(2).hasTestClass(CALCULATOR_TEST).hasTestClass(STRING_UTILS_TEST);

		// Verify dependency relationships
		assertThat(depMap).hasDependency(CALCULATOR_TEST, CALCULATOR).hasDependency(CALCULATOR_TEST, MATH_HELPER)
				.doesNotHaveDependency(CALCULATOR_TEST, STRING_UTILS);

		assertThat(depMap).hasDependency(STRING_UTILS_TEST, STRING_UTILS)
				.doesNotHaveDependency(STRING_UTILS_TEST, CALCULATOR)
				.doesNotHaveDependency(STRING_UTILS_TEST, MATH_HELPER);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 2. AFFECTED TEST QUERIES
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(2)
	@DisplayName("Index: changing Calculator affects CalculatorTest but not StringUtilsTest")
	void affectedTestsCalculator() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).isLoaded();

		assertThat(depMap).changesAffect(Set.of(CALCULATOR), CALCULATOR_TEST).changesDoNotAffect(Set.of(CALCULATOR),
				STRING_UTILS_TEST);
	}

	@Test
	@Order(3)
	@DisplayName("Index: changing StringUtils affects StringUtilsTest but not CalculatorTest")
	void affectedTestsStringUtils() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).isLoaded();

		assertThat(depMap).changesAffect(Set.of(STRING_UTILS), STRING_UTILS_TEST)
				.changesDoNotAffect(Set.of(STRING_UTILS), CALCULATOR_TEST);
	}

	@Test
	@Order(4)
	@DisplayName("Index: changing MathHelper affects CalculatorTest (transitive)")
	void affectedTestsMathHelper() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).isLoaded();

		assertThat(depMap).changesAffect(Set.of(MATH_HELPER), CALCULATOR_TEST).changesDoNotAffect(Set.of(MATH_HELPER),
				STRING_UTILS_TEST);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 3. ORDER MODE
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(10)
	@DisplayName("Order mode: tests pass with explicit changed classes")
	void orderModeRunsTests() {
		MavenResult result = project.maven().order(CALCULATOR);
		assertThat(result).succeeded().outputContains("[test-order]").outputContains("Tests run:");
	}

	@Test
	@Order(11)
	@DisplayName("Order mode: junit-platform.properties is written with PriorityClassOrderer")
	void orderModeWritesJunitPlatformProperties() {
		// Order mode from previous test left target around
		String props = project.readFile("target/test-classes/junit-platform.properties");
		assertThat(props).isNotNull().contains("me.bechberger.testorder.PriorityClassOrderer");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 4. AUTO MODE (default)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(20)
	@DisplayName("Auto mode: detects changes and runs tests in order")
	void autoModeWithExplicitChanges() {
		MavenResult result = project.maven().auto(CALCULATOR);
		assertThat(result).succeeded().outputContains("[test-order]").outputContains("Tests run:");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 5. SHOW-ORDER GOAL
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(30)
	@DisplayName("show-order: displays scored test classes")
	void showOrderDisplaysScores() {
		MavenResult result = project.maven().showOrder(CALCULATOR);
		assertThat(result).succeeded().outputContains("CalculatorTest").outputContains("StringUtilsTest");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 6. DUMP GOAL
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(31)
	@DisplayName("dump: prints human-readable index")
	void dumpShowsIndex() {
		MavenResult result = project.maven().dump();
		assertThat(result).succeeded().outputContains(CALCULATOR_TEST).outputContains(CALCULATOR);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 7. STATE FILE
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(40)
	@DisplayName("State file: hash snapshots exist after order-mode run")
	void hashSnapshotsExist() {
		// Hash files should exist after auto/order mode runs with change detection
		assertThat(project.exists(".test-order/hashes.lz4") || project.exists(".test-order/test-hashes.lz4")
				|| project.exists(".test-order-hashes.lz4") || project.exists(".test-order-test-hashes.lz4"))
				.as("At least one hash snapshot file should exist after order-mode runs").isTrue();
	}

	// ═══════════════════════════════════════════════════════════════════
	// 8. VERBOSE AGENT LOGGING
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(50)
	@DisplayName("Verbose mode: agent writes transform and flush logs to specified file")
	void verboseModeWritesLogFile() {
		project.deleteTree("target");
		project.deleteIfExists("test-dependencies.lz4");
		project.deleteIfExists(".test-order/test-dependencies.lz4");

		String logPath = "target/agent-verbose.log";
		MavenResult result = project.maven().learnVerbose(project.path(logPath));
		assertThat(result).succeeded();

		String log = project.readVerboseLog(logPath);
		assertThat(log).isNotNull().contains("[test-order] Verbose logging enabled").contains("[transform] OK:")
				.contains("[flush]");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 9. FILE MODIFICATION + RELEARN
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(60)
	@DisplayName("After modifying Calculator, re-learn still captures correct dependencies")
	void relearnAfterModification() {
		try {
			// modify Calculator: add a new method
			project.replaceInFile(CALCULATOR_SRC, "public int power(int base, int exponent) {", """
					public int subtract(int a, int b) {
					    return mathHelper.add(a, -b);
					}

					public int power(int base, int exponent) {""");

			project.deleteIfExists("test-dependencies.lz4");
			project.deleteTree("target");

			MavenResult result = project.maven().learn();
			assertThat(result).succeeded();

			DependencyMap depMap = project.loadIndex();
			assertThat(depMap).isLoaded().hasSize(2).hasDependency(CALCULATOR_TEST, CALCULATOR)
					.hasDependency(CALCULATOR_TEST, MATH_HELPER);
		} finally {
			project.restoreAll();
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// 10. IDEMPOTENT LEARN
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(70)
	@DisplayName("Running learn mode twice does not corrupt the index")
	void learnModeIdempotent() {
		project.cleanAll();

		// First learn
		MavenResult r1 = project.maven().learn();
		assertThat(r1).succeeded();
		DependencyMap d1 = project.loadIndex();

		// Second learn (index already exists)
		MavenResult r2 = project.maven().learn();
		assertThat(r2).succeeded();
		DependencyMap d2 = project.loadIndex();

		// Same content
		assertThat(d2).isLoaded().hasSize(d1.size());
		for (String tc : d1.testClasses()) {
			assertThat(d2).hasTestClass(tc);
			for (String dep : d1.get(tc)) {
				assertThat(d2).hasDependency(tc, dep);
			}
		}
	}
}
