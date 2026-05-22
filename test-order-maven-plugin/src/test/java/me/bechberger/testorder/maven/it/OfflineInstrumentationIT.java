package me.bechberger.testorder.maven.it;

import static me.bechberger.testorder.maven.it.TestOrderAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import me.bechberger.testorder.DependencyMap;

/**
 * Integration tests for offline (build-time) instrumentation workflow.
 * <p>
 * Verifies the full pipeline:
 * <ol>
 * <li>compile + test-order:instrument → instruments classes in-place</li>
 * <li>test with -Dtestorder.instrumentation=offline → learns without agent</li>
 * <li>Subsequent select/order uses the learned index</li>
 * </ol>
 * <p>
 * Enable with: {@code -Dtestorder.it=true}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OfflineInstrumentationIT {

	static final String PKG = "com.example.service.";

	// Test classes
	static final String USER_TEST = PKG + "model.UserTest";
	static final String ORDER_TEST = PKG + "model.OrderTest";
	static final String USER_SERVICE_TEST = PKG + "service.UserServiceTest";
	static final String ORDER_SERVICE_TEST = PKG + "service.OrderServiceTest";

	// Source classes
	static final String USER = PKG + "model.User";
	static final String ORDER = PKG + "model.Order";
	static final String USER_SERVICE = PKG + "service.UserService";
	static final String ORDER_SERVICE = PKG + "service.OrderService";

	TestProject project;

	@BeforeAll
	void setup() {
		Path root = Paths.get("").toAbsolutePath();
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		project = new TestProject(root.resolve("test-order-example/test-order-example-service"),
				List.of("-Dtestorder.includePackages=com.example"));
	}

	@AfterAll
	void tearDown() {
		if (project != null) {
			project.restoreAll();
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// 1. OFFLINE LEARN
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(1)
	@DisplayName("Offline learn: produces class-id mapping and dependency index")
	void offlineLearnProducesIndex() throws Exception {
		project.cleanAll();

		MavenResult result = project.maven().learnOffline();
		assertThat(result).succeeded().outputContains("[test-order] Offline instrumentation")
				.outputContains("[test-order] Instrumented").outputContains("Tests run:");

		// Mapping file created
		Path mappingFile = project.path("target/.test-order/class-id-map.bin");
		assertTrue(Files.exists(mappingFile));
		assertTrue(Files.size(mappingFile) > 16);

		// Dependency index created
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).isLoaded().hasSize(8);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 2. DEPENDENCY ACCURACY (offline should match online)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(2)
	@DisplayName("Offline index: UserTest depends on User")
	void offlineUserTestDependencies() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).hasDependency(USER_TEST, USER).doesNotHaveDependency(USER_TEST, ORDER)
				.doesNotHaveDependency(USER_TEST, USER_SERVICE);
	}

	@Test
	@Order(3)
	@DisplayName("Offline index: OrderServiceTest has transitive dependencies")
	void offlineOrderServiceTestDependencies() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).hasDependency(ORDER_SERVICE_TEST, ORDER_SERVICE).hasDependency(ORDER_SERVICE_TEST, ORDER)
				.hasDependency(ORDER_SERVICE_TEST, USER);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 3. SELECT AFTER OFFLINE LEARN
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(4)
	@DisplayName("Select after offline learn: User change selects UserTest and dependents")
	void selectAfterOfflineLearn() {
		// Use select with explicit change detection (avoids git dependency)
		MavenResult result = project.maven().select(USER);
		assertThat(result).succeeded().outputContains("Tests run:");

		// UserTest, UserValidatorTest, UserServiceTest, OrderServiceTest,
		// UserRepoTest, OrderRepoTest, OrderValidatorTest should be selected
		// (anything that transitively depends on User)
		assertThat(result).outputContains(USER_TEST.replace(PKG, ""));
	}

	// ═══════════════════════════════════════════════════════════════════
	// 4. DOUBLE-INSTRUMENTATION PROTECTION
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(5)
	@DisplayName("Re-running instrument skips already-instrumented classes")
	void doubleInstrumentationSkips() {
		// Run instrument again (classes still instrumented from step 1)
		MavenResult result = project.maven().run("compile", "test-order:instrument",
				"-Dtestorder.includePackages=com.example");
		assertThat(result).succeeded().outputContains("skipped");
	}
}
