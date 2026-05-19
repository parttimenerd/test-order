package me.bechberger.testorder.maven.it;

import static me.bechberger.testorder.maven.it.TestOrderAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import me.bechberger.testorder.DependencyMap;

/**
 * End-to-end integration tests for JUnit 4 tests running via the JUnit Vintage
 * engine. Verifies that learn, select, and order modes work correctly when
 * tests are written against JUnit 4 but executed through the JUnit Platform via
 * Vintage.
 * <p>
 * Uses samples/sample-vintage which contains:
 * <ul>
 * <li>{@code CalculatorTest} (JUnit 4) → depends on {@code Calculator},
 * {@code MathHelper}</li>
 * <li>{@code StringUtilsTest} (JUnit 4) → depends on {@code StringUtils}</li>
 * </ul>
 * <p>
 * Enable with: {@code -Dtestorder.it=true}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndVintageIT {

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
		project = new TestProject(root.resolve("samples/sample-vintage"),
				List.of("-Dtestorder.includePackages=com.example"));
	}

	@AfterAll
	void tearDown() {
		if (project != null) {
			project.restoreAll();
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// 1. LEARN MODE — Vintage tests are tracked via TelemetryListener
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(1)
	@DisplayName("Learn mode: Vintage-wrapped JUnit 4 tests produce dependency index")
	void learnModeProducesIndex() {
		project.cleanAll();

		MavenResult result = project.maven().learn();
		assertThat(result).succeeded().outputContains("[test-order]").outputContains("Tests run:");

		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).isLoaded().hasSize(2).hasTestClass(CALCULATOR_TEST).hasTestClass(STRING_UTILS_TEST);
	}

	@Test
	@Order(2)
	@DisplayName("Learn mode: no JUnit 4 unsupported warning when Vintage engine is present")
	void noJUnit4WarningWithVintage() {
		// The learn run from test 1 should NOT have produced the JUnit 4 warning
		MavenResult result = project.maven().learn();
		assertThat(result).succeeded();
		assertThat(result.output()).as("Should not warn about JUnit 4 when Vintage engine is present")
				.doesNotContain("JUnit 4 tests will not be reordered or tracked");
	}

	@Test
	@Order(3)
	@DisplayName("Learn mode: correct dependencies for Vintage CalculatorTest")
	void learnModeDependenciesCalculator() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).hasDependency(CALCULATOR_TEST, CALCULATOR).hasDependency(CALCULATOR_TEST, MATH_HELPER)
				.doesNotHaveDependency(CALCULATOR_TEST, STRING_UTILS);
	}

	@Test
	@Order(4)
	@DisplayName("Learn mode: correct dependencies for Vintage StringUtilsTest")
	void learnModeDependenciesStringUtils() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).hasDependency(STRING_UTILS_TEST, STRING_UTILS)
				.doesNotHaveDependency(STRING_UTILS_TEST, CALCULATOR)
				.doesNotHaveDependency(STRING_UTILS_TEST, MATH_HELPER);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 2. SELECT MODE — Vintage tests are correctly selected
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(10)
	@DisplayName("Select mode: changing Calculator selects CalculatorTest")
	void selectModeCalculator() {
		MavenResult result = project.maven().select(CALCULATOR);
		assertThat(result).succeeded();
		assertThat(result.output()).contains("CalculatorTest");
	}

	@Test
	@Order(11)
	@DisplayName("Select mode: changing StringUtils selects StringUtilsTest")
	void selectModeStringUtils() {
		MavenResult result = project.maven().select(STRING_UTILS);
		assertThat(result).succeeded();
		assertThat(result.output()).contains("StringUtilsTest");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 3. ORDER MODE — Vintage tests run with proper ordering
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(20)
	@DisplayName("Order mode: Vintage tests pass with explicit changed classes")
	void orderModeRunsTests() {
		MavenResult result = project.maven().order(CALCULATOR);
		assertThat(result).succeeded().outputContains("[test-order]").outputContains("Tests run:");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 4. SHOW-ORDER — scoring works for Vintage tests
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(30)
	@DisplayName("show-order: displays scored Vintage test classes")
	void showOrderDisplaysScores() {
		MavenResult result = project.maven().showOrder(CALCULATOR);
		assertThat(result).succeeded().outputContains("CalculatorTest").outputContains("StringUtilsTest");
	}
}
