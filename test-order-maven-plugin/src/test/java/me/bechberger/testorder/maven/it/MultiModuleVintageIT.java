package me.bechberger.testorder.maven.it;

import static me.bechberger.testorder.maven.it.TestOrderAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import me.bechberger.testorder.DependencyMap;

/**
 * Multi-module integration test for JUnit 4 tests running via JUnit Vintage
 * engine. Verifies that cross-module dependency tracking and selection works
 * correctly when all tests are JUnit 4 running through Vintage.
 * <p>
 * Uses samples/sample-vintage-multi (core + web modules) where:
 * <ul>
 * <li>core: UserService → UserServiceTest (JUnit 4)</li>
 * <li>web: UserController (depends on core) → UserControllerTest (JUnit 4)</li>
 * </ul>
 * <p>
 * Enable with: {@code -Dtestorder.it=true}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiModuleVintageIT {

	static final String USER_SERVICE = "com.example.core.UserService";
	static final String USER_SERVICE_TEST = "com.example.core.UserServiceTest";
	static final String USER_CONTROLLER = "com.example.web.UserController";
	static final String USER_CONTROLLER_TEST = "com.example.web.UserControllerTest";

	TestProject project;

	@BeforeAll
	void setup() {
		Path root = Paths.get("").toAbsolutePath();
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		project = new TestProject(root.resolve("samples/sample-vintage-multi"),
				List.of("-Dtestorder.includePackages=com.example"));
	}

	@AfterAll
	void tearDown() {
		if (project != null) {
			project.restoreAll();
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// 1. LEARN MODE — reactor learn with Vintage tests
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(1)
	@DisplayName("Reactor learn: Vintage JUnit 4 tests produce shared dependency index")
	void reactorLearnProducesSharedIndex() {
		project.cleanAll();
		MavenResult result = project.maven().learn();
		assertThat(result).succeeded();

		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).isLoaded();
		assertThat(depMap.size()).as("Index should contain tests from both modules").isGreaterThanOrEqualTo(2);
	}

	@Test
	@Order(2)
	@DisplayName("Reactor learn: no JUnit 4 warning with Vintage engine present")
	void noJUnit4WarningInReactor() {
		MavenResult result = project.maven().learn();
		assertThat(result).succeeded();
		assertThat(result.output()).as("No JUnit 4 warning when Vintage engine is on classpath")
				.doesNotContain("JUnit 4 tests will not be reordered or tracked");
	}

	@Test
	@Order(3)
	@DisplayName("Reactor index contains UserServiceTest from core module")
	void reactorIndexContainsCoreTest() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).isLoaded().hasTestClass(USER_SERVICE_TEST);
	}

	@Test
	@Order(4)
	@DisplayName("Reactor index contains UserControllerTest from web module")
	void reactorIndexContainsWebTest() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).isLoaded().hasTestClass(USER_CONTROLLER_TEST);
	}

	@Test
	@Order(5)
	@DisplayName("UserServiceTest depends on UserService")
	void userServiceTestDependencies() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).hasDependency(USER_SERVICE_TEST, USER_SERVICE);
	}

	@Test
	@Order(6)
	@DisplayName("UserControllerTest depends on UserController")
	void userControllerTestDependencies() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).hasDependency(USER_CONTROLLER_TEST, USER_CONTROLLER);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 2. SELECT MODE — cross-module selection with Vintage
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(10)
	@DisplayName("Select mode: changing UserService selects UserServiceTest")
	void selectWithCoreChange() {
		MavenResult result = project.maven().select(USER_SERVICE);
		assertThat(result).succeeded();
		assertThat(result.output()).contains("UserServiceTest");
	}

	@Test
	@Order(11)
	@DisplayName("Select mode: changing UserController selects UserControllerTest")
	void selectWithWebChange() {
		MavenResult result = project.maven().select(USER_CONTROLLER);
		assertThat(result).succeeded();
		assertThat(result.output()).contains("UserControllerTest");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 3. ORDER MODE — cross-module execution with Vintage
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(20)
	@DisplayName("Order mode: reactor build with Vintage tests passes")
	void orderModeReactorBuild() {
		MavenResult result = project.maven().order(USER_SERVICE);
		assertThat(result).succeeded().outputContains("Tests run:");
	}
}
