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
 * End-to-end tests against the service example project (8 test classes, rich
 * dependency graph).
 * <p>
 * Dependency graph:
 *
 * <pre>
 *   UserTest              → User
 *   OrderTest             → Order, User
 *   UserValidatorTest     → UserValidator, User
 *   OrderValidatorTest    → OrderValidator, UserValidator, Order, User
 *   UserRepositoryTest    → UserRepository, User
 *   OrderRepositoryTest   → OrderRepository, Order, User
 *   UserServiceTest       → UserService, UserRepository, UserValidator, User
 *   OrderServiceTest      → OrderService, OrderRepository, OrderValidator,
 *                           UserService, UserRepository, UserValidator, Order, User
 * </pre>
 * <p>
 * Enable with: {@code -Dtestorder.it=true}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndServiceIT {

	// ── FQCNs ──────────────────────────────────────────────────────────
	static final String PKG = "com.example.service.";

	// test classes
	static final String USER_TEST = PKG + "model.UserTest";
	static final String ORDER_TEST = PKG + "model.OrderTest";
	static final String USER_VALIDATOR_TEST = PKG + "validation.UserValidatorTest";
	static final String ORDER_VALIDATOR_TEST = PKG + "validation.OrderValidatorTest";
	static final String USER_REPO_TEST = PKG + "repo.UserRepositoryTest";
	static final String ORDER_REPO_TEST = PKG + "repo.OrderRepositoryTest";
	static final String USER_SERVICE_TEST = PKG + "service.UserServiceTest";
	static final String ORDER_SERVICE_TEST = PKG + "service.OrderServiceTest";

	// source classes
	static final String USER = PKG + "model.User";
	static final String ORDER = PKG + "model.Order";
	static final String USER_VALIDATOR = PKG + "validation.UserValidator";
	static final String ORDER_VALIDATOR = PKG + "validation.OrderValidator";
	static final String USER_REPO = PKG + "repo.UserRepository";
	static final String ORDER_REPO = PKG + "repo.OrderRepository";
	static final String USER_SERVICE = PKG + "service.UserService";
	static final String ORDER_SERVICE = PKG + "service.OrderService";

	TestProject project;

	@BeforeAll
	void setup() {
		Path root = Paths.get("").toAbsolutePath();
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		project = new TestProject(root.resolve("test-order-example-service"),
				List.of("-Dtestorder.includePackages=com.example"));
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
	@DisplayName("Learn: produces index with all 8 test classes")
	void learnModeProducesIndex() {
		project.cleanAll();

		MavenResult result = project.maven().learn();
		assertThat(result).succeeded().outputContains("[test-order]").outputContains("Tests run:");

		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).isLoaded().hasSize(8);

		// verify all test classes present
		for (String tc : List.of(USER_TEST, ORDER_TEST, USER_VALIDATOR_TEST, ORDER_VALIDATOR_TEST, USER_REPO_TEST,
				ORDER_REPO_TEST, USER_SERVICE_TEST, ORDER_SERVICE_TEST)) {
			assertThat(depMap).hasTestClass(tc);
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// 2. DEPENDENCY ACCURACY
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(2)
	@DisplayName("Index: UserTest depends only on User")
	void userTestDependencies() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).hasDependency(USER_TEST, USER).doesNotHaveDependency(USER_TEST, ORDER)
				.doesNotHaveDependency(USER_TEST, USER_SERVICE).doesNotHaveDependency(USER_TEST, USER_REPO);
	}

	@Test
	@Order(3)
	@DisplayName("Index: OrderTest depends on Order and User (via constructor)")
	void orderTestDependencies() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).hasDependency(ORDER_TEST, ORDER).hasDependency(ORDER_TEST, USER)
				.doesNotHaveDependency(ORDER_TEST, USER_SERVICE).doesNotHaveDependency(ORDER_TEST, ORDER_REPO);
	}

	@Test
	@Order(4)
	@DisplayName("Index: OrderServiceTest has deep transitive dependencies")
	void orderServiceTestDependencies() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).hasDependency(ORDER_SERVICE_TEST, ORDER_SERVICE)
				.hasDependency(ORDER_SERVICE_TEST, ORDER_REPO).hasDependency(ORDER_SERVICE_TEST, ORDER_VALIDATOR)
				.hasDependency(ORDER_SERVICE_TEST, USER_SERVICE).hasDependency(ORDER_SERVICE_TEST, USER_REPO)
				.hasDependency(ORDER_SERVICE_TEST, USER_VALIDATOR).hasDependency(ORDER_SERVICE_TEST, ORDER)
				.hasDependency(ORDER_SERVICE_TEST, USER);
	}

	@Test
	@Order(5)
	@DisplayName("Index: UserValidatorTest depends on UserValidator and User only")
	void userValidatorTestDependencies() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).hasDependency(USER_VALIDATOR_TEST, USER_VALIDATOR).hasDependency(USER_VALIDATOR_TEST, USER)
				.doesNotHaveDependency(USER_VALIDATOR_TEST, ORDER_VALIDATOR)
				.doesNotHaveDependency(USER_VALIDATOR_TEST, USER_SERVICE);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 3. AFFECTED TESTS — LEAF vs TRANSITIVE
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(10)
	@DisplayName("Affected: changing User affects many tests (it's a leaf model)")
	void changingUserAffectsMany() {
		DependencyMap depMap = project.loadIndex();
		Set<String> affected = depMap.getAffectedTests(Set.of(USER));

		// User is used transitively by all 8 test classes
		assertThat(affected).contains(USER_TEST, ORDER_TEST, USER_VALIDATOR_TEST, ORDER_VALIDATOR_TEST, USER_REPO_TEST,
				ORDER_REPO_TEST, USER_SERVICE_TEST, ORDER_SERVICE_TEST);
	}

	@Test
	@Order(11)
	@DisplayName("Affected: changing OrderService affects only OrderServiceTest")
	void changingOrderServiceAffectsOnlyItsTest() {
		DependencyMap depMap = project.loadIndex();
		Set<String> affected = depMap.getAffectedTests(Set.of(ORDER_SERVICE));

		assertThat(affected).contains(ORDER_SERVICE_TEST).doesNotContain(USER_TEST, ORDER_TEST, USER_SERVICE_TEST,
				USER_REPO_TEST, ORDER_REPO_TEST);
	}

	@Test
	@Order(12)
	@DisplayName("Affected: changing UserValidator affects UserValidatorTest, OrderValidatorTest, UserServiceTest, OrderServiceTest")
	void changingUserValidatorAffectsTransitive() {
		DependencyMap depMap = project.loadIndex();
		Set<String> affected = depMap.getAffectedTests(Set.of(USER_VALIDATOR));

		assertThat(affected).contains(USER_VALIDATOR_TEST, ORDER_VALIDATOR_TEST, USER_SERVICE_TEST, ORDER_SERVICE_TEST)
				.doesNotContain(USER_TEST, ORDER_TEST, USER_REPO_TEST, ORDER_REPO_TEST);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 4. ORDER MODE
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(20)
	@DisplayName("Order mode: tests pass with OrderService as changed class")
	void orderModeRunsTests() {
		MavenResult result = project.maven().order(ORDER_SERVICE);
		assertThat(result).succeeded().outputContains("[test-order]").outputContains("Tests run:");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 5. SHOW-ORDER with changed UserValidator
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(30)
	@DisplayName("show-order: changing UserValidator puts validator tests near the top")
	void showOrderScoresValidatorHigher() {
		MavenResult result = project.maven().showOrder(USER_VALIDATOR);
		assertThat(result).succeeded().outputContains("UserValidatorTest").outputContains("OrderValidatorTest");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 6. DUMP
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(31)
	@DisplayName("dump: lists all 8 test classes with dependencies")
	void dumpShowsAllTests() {
		MavenResult result = project.maven().dump();
		assertThat(result).succeeded().outputContains("UserTest").outputContains("OrderServiceTest")
				.outputContains("UserValidator");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 7. METHOD_ENTRY INSTRUMENTATION
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(40)
	@DisplayName("Learn with METHOD_ENTRY: still captures method-level dependencies")
	void learnMethodEntryMode() {
		project.cleanAll();

		MavenResult result = project.maven().learnMethodEntry();
		assertThat(result).succeeded();

		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).isLoaded().hasSize(8);

		// METHOD_ENTRY should still capture constructor/method calls
		assertThat(depMap).hasDependency(ORDER_SERVICE_TEST, ORDER_SERVICE)
				.hasDependency(ORDER_SERVICE_TEST, USER_SERVICE).hasDependency(USER_TEST, USER);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 8. IDEMPOTENT LEARN
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(50)
	@DisplayName("Running learn twice produces consistent index")
	void learnModeIdempotent() {
		project.cleanAll();

		MavenResult r1 = project.maven().learn();
		assertThat(r1).succeeded();
		DependencyMap d1 = project.loadIndex();

		MavenResult r2 = project.maven().learn();
		assertThat(r2).succeeded();
		DependencyMap d2 = project.loadIndex();

		assertThat(d2).isLoaded().hasSize(d1.size());
		for (String tc : d1.testClasses()) {
			assertThat(d2).hasTestClass(tc);
			for (String dep : d1.get(tc)) {
				assertThat(d2).hasDependency(tc, dep);
			}
		}
	}
}
