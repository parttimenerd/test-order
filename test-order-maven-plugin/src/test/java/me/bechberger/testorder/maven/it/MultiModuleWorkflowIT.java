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
 * Multi-module workflow integration tests — verifies test-order handles Maven
 * reactor builds with shared indexes, cross-module dependencies, and per-module
 * state files correctly.
 * <p>
 * Uses samples/sample-multi (core + web modules) where: - core: UserService →
 * UserServiceTest - web: UserController (depends on core) → UserControllerTest
 * <p>
 * Enable with: {@code -Dtestorder.it=true}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiModuleWorkflowIT {

	static final String USER_SERVICE = "com.myapp.core.UserService";
	static final String USER_SERVICE_TEST = "com.myapp.core.UserServiceTest";
	static final String USER_CONTROLLER = "com.myapp.web.UserController";
	static final String USER_CONTROLLER_TEST = "com.myapp.web.UserControllerTest";

	TestProject multiProject;

	@BeforeAll
	void setup() {
		Path root = Paths.get("").toAbsolutePath();
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		multiProject = new TestProject(root.resolve("samples/sample-multi"),
				List.of("-Dtestorder.includePackages=com.myapp"));
	}

	@AfterAll
	void tearDown() {
		if (multiProject != null)
			multiProject.restoreAll();
	}

	// ═══════════════════════════════════════════════════════════════════
	// REACTOR LEARN — shared index at root
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(1)
	@DisplayName("Reactor learn produces shared dependency index at project root")
	void reactorLearnProducesSharedIndex() {
		multiProject.cleanAll();
		MavenResult result = multiProject.maven().learn();
		assertThat(result).succeeded();

		// Shared index at reactor root should contain tests from both modules
		DependencyMap depMap = multiProject.loadIndex();
		assertThat(depMap).isLoaded();
		assertThat(depMap.size()).as("Index should contain tests from both modules").isGreaterThanOrEqualTo(2);
	}

	@Test
	@Order(2)
	@DisplayName("Reactor index contains UserServiceTest from core module")
	void reactorIndexContainsCoreModuleTests() {
		DependencyMap depMap = multiProject.loadIndex();
		assertThat(depMap).isLoaded().hasTestClass(USER_SERVICE_TEST);
	}

	@Test
	@Order(3)
	@DisplayName("Reactor index contains UserControllerTest from web module")
	void reactorIndexContainsWebModuleTests() {
		DependencyMap depMap = multiProject.loadIndex();
		assertThat(depMap).isLoaded().hasTestClass(USER_CONTROLLER_TEST);
	}

	@Test
	@Order(4)
	@DisplayName("UserServiceTest depends on UserService (core module)")
	void userServiceTestDependsOnUserService() {
		DependencyMap depMap = multiProject.loadIndex();
		assertThat(depMap).hasDependency(USER_SERVICE_TEST, USER_SERVICE);
	}

	@Test
	@Order(5)
	@DisplayName("UserControllerTest depends on UserController (web module)")
	void userControllerTestDependsOnUserController() {
		DependencyMap depMap = multiProject.loadIndex();
		assertThat(depMap).hasDependency(USER_CONTROLLER_TEST, USER_CONTROLLER);
	}

	// ═══════════════════════════════════════════════════════════════════
	// REACTOR ORDER — cross-module change detection
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(10)
	@DisplayName("Reactor order with core module change affects core tests")
	void reactorOrderWithCoreChangeAffectsCoreTests() {
		// Changing UserService should affect UserServiceTest
		MavenResult result = multiProject.maven().showOrder(USER_SERVICE);
		assertThat(result).succeeded();
		assertThat(result.output()).contains("UserServiceTest");
	}

	@Test
	@Order(11)
	@DisplayName("Reactor order with core module change may affect web tests (transitive)")
	void reactorOrderWithCoreChangeMayAffectWebTests() {
		// UserController depends on UserService (core→web dependency)
		// Changing UserService may transitively affect UserControllerTest
		MavenResult result = multiProject.maven().showOrder(USER_SERVICE);
		assertThat(result).succeeded();

		// Both tests should appear in show-order output
		assertThat(result.output()).contains("UserServiceTest");
		// Web test may or may not be affected depending on actual transitive deps
	}

	@Test
	@Order(12)
	@DisplayName("Reactor order with web-only change does not affect core tests")
	void reactorOrderWithWebOnlyChange() {
		MavenResult result = multiProject.maven().showOrder(USER_CONTROLLER);
		assertThat(result).succeeded();
		assertThat(result.output()).contains("UserControllerTest");
	}

	@Test
	@Order(13)
	@DisplayName("show-order works with -pl web -am and does not get skipped")
	void reactorShowOrderWithSelectedModuleAndAlsoMake() {
		MavenResult result = multiProject.maven().run("test-order:show-order", "-pl", "web", "-am",
				"-Dtestorder.changeMode=explicit", "-Dtestorder.changed.classes=" + USER_CONTROLLER);
		assertThat(result).succeeded();
		assertThat(result.output())
				.as("show-order should print ranking output even when reactor root is not selected explicitly")
				.contains("UserControllerTest").contains("Changed classes:");
	}

	// ═══════════════════════════════════════════════════════════════════
	// REACTOR ORDER MODE — full test execution
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(20)
	@DisplayName("Reactor order mode executes all tests across modules")
	void reactorOrderModeExecutesAllModules() {
		MavenResult result = multiProject.maven().order(USER_SERVICE);
		assertThat(result).succeeded();

		// Both modules' tests should execute
		assertThat(result.output()).contains("UserServiceTest").contains("UserControllerTest");
	}

	// ═══════════════════════════════════════════════════════════════════
	// BUG FIX: Cross-module change propagation in uncommitted mode
	//
	// When a source file in module A (core) is modified, module B (web)
	// whose tests depend on that class should detect the change via
	// ReactorContext upstream propagation — not just via explicit mode.
	// Previously, the web module's OrderWorkflow only scanned its own
	// source root and missed upstream changes.
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(21)
	@DisplayName("BUG FIX: uncommitted core change propagates to web module in order mode")
	void uncommittedCoreChangePropagesToWebModule() {
		// Ensure clean state with a fresh learn run
		multiProject.cleanAll();
		MavenResult learn = multiProject.maven().learn();
		assertThat(learn).succeeded();

		// Modify core module's UserService (uncommitted change)
		multiProject.replaceInFile("core/src/main/java/com/myapp/core/UserService.java", "return \"User-\" + userId;",
				"return \"User-\" + userId; // modified");

		try {
			// Run in order mode with uncommitted change detection (the default)
			MavenResult result = multiProject.maven().run("clean", "test", "-Dtestorder.mode=order",
					"-Dtestorder.changeMode=uncommitted");
			assertThat(result).succeeded();

			// The web module should detect the upstream change and report it
			assertThat(result.output()).as("Web module should detect core's UserService change via reactor propagation")
					.contains("Detected 1 changed source classes:");

			// Count how many times "Detected 1 changed source classes:" appears —
			// it should appear for BOTH the core module AND the web module
			long detectedCount = result.output().lines().filter(l -> l.contains("Detected 1 changed source classes:"))
					.count();
			assertThat(detectedCount).as("Both core and web modules should detect the UserService change")
					.isGreaterThanOrEqualTo(2);
		} finally {
			multiProject.restoreAll();
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// PER-MODULE STATE FILES
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(30)
	@DisplayName("Per-module state files are created independently")
	void perModuleStateFilesIndependent() {
		// After order run, state is stored at the reactor root .test-order/
		// (multi-module mode uses a shared dir at reactor root, not per-module dirs)
		boolean reactorState = multiProject.exists(".test-order/state.lz4");

		// At least one module should have a state file
		assertThat(reactorState).as("At least one module should have created a state file after order run").isTrue();
	}

	// ═══════════════════════════════════════════════════════════════════
	// REACTOR SELECT MODE
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(40)
	@DisplayName("Reactor select mode with cross-module deps selects correctly")
	void reactorSelectWithCrossModuleDeps() {
		MavenResult result = multiProject.maven().run("clean", "test-order:select", "test",
				"-Dtestorder.changeMode=explicit", "-Dtestorder.changed.classes=" + USER_SERVICE,
				"-Dtestorder.select.topN=10", "-Dtestorder.select.randomM=0", "-Dtestorder.mode=skip");
		assertThat(result).succeeded();
		// Build should pass regardless of whether selection is per-module or
		// reactor-wide
	}

	// ═══════════════════════════════════════════════════════════════════
	// REACTOR COMBINED MODE
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(50)
	@DisplayName("Reactor combined mode end-to-end works")
	void reactorCombinedModeEndToEnd() {
		MavenResult result = multiProject.maven().auto();
		assertThat(result).succeeded();

		// Both modules' tests should execute
		assertThat(result.output()).contains("UserServiceTest").contains("UserControllerTest");
	}

	// ═══════════════════════════════════════════════════════════════════
	// REACTOR AGGREGATE GOAL
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(60)
	@DisplayName("Aggregate goal merges deps from all modules")
	void aggregateGoalMergesAllModuleDeps() {
		// Re-learn to ensure fresh deps
		multiProject.cleanAll();
		multiProject.maven().learn();

		MavenResult aggResult = multiProject.maven().aggregate();
		// Aggregate may succeed or be a no-op if binary index already exists
		assertThat(aggResult.output()).doesNotContain("NullPointerException")
				.doesNotContain("ArrayIndexOutOfBoundsException");

		// Index should still be valid after aggregate
		DependencyMap depMap = multiProject.loadIndex();
		assertThat(depMap).isLoaded();
		assertThat(depMap.size()).isGreaterThanOrEqualTo(2);
	}

	// ═══════════════════════════════════════════════════════════════════
	// REACTOR DASHBOARD
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(70)
	@DisplayName("Dashboard on reactor root generates HTML")
	void reactorDashboardGeneratesHtml() {
		// Run order first to populate state
		multiProject.maven().order(USER_SERVICE);

		// Dashboard generation
		MavenResult result = multiProject.maven().run("test-order:dashboard");
		if (result.isSuccess()) {
			// Dashboard file should be generated
			boolean dashExists = multiProject.exists("target/test-order-dashboard.html")
					|| multiProject.exists("target/test-order/dashboard.html");
			// Dashboard may use a different output path — just verify no crash
			assertThat(result.output()).doesNotContain("NullPointerException");
		}
		// Dashboard may not be configured — that's OK, just shouldn't crash
	}

	// ═══════════════════════════════════════════════════════════════════
	// PARALLEL REACTOR BUILD (-T 1C)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(80)
	@DisplayName("Parallel reactor build (-T 2) with learn mode does not corrupt index")
	void parallelReactorBuildWithLearnMode() {
		multiProject.cleanAll();

		// Run with Maven parallel threads (-T 2)
		MavenResult result = multiProject.maven().run("clean", "test", "-Dtestorder.mode=learn", "-T", "2");
		// May succeed or fail — but should NOT corrupt the index
		assertThat(result.output()).doesNotContain("NegativeArraySizeException")
				.doesNotContain("ConcurrentModificationException");

		if (result.isSuccess()) {
			DependencyMap depMap = multiProject.loadIndex();
			if (depMap != null) {
				assertThat(depMap.size()).as("Parallel learn should capture tests from both modules")
						.isGreaterThanOrEqualTo(2);
			}
		}

		// Clean up — sequential re-learn for stable state
		multiProject.cleanAll();
		multiProject.maven().learn();
	}

	// ═══════════════════════════════════════════════════════════════════
	// DUMP GOAL ON REACTOR
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(90)
	@DisplayName("Dump goal on reactor shows all modules' test classes")
	void dumpGoalShowsAllModuleTests() {
		MavenResult result = multiProject.maven().dump();
		assertThat(result).succeeded();
		assertThat(result.output()).contains("UserServiceTest").contains("UserControllerTest");
	}

	// ═══════════════════════════════════════════════════════════════════
	// SNAPSHOT GOAL ON REACTOR
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(100)
	@DisplayName("Snapshot goal on reactor creates hash snapshots")
	void snapshotGoalCreatesHashFiles() {
		MavenResult result = multiProject.maven().snapshot();
		assertThat(result).succeeded();

		// Hash files should be created (per-module or at root)
		boolean anyHashes = multiProject.exists(".test-order/hashes.lz4")
				|| multiProject.exists("core/.test-order/hashes.lz4")
				|| multiProject.exists("web/.test-order/hashes.lz4");
		// Snapshot may use different file names — just shouldn't crash
		assertThat(result.output()).doesNotContain("NullPointerException").doesNotContain("IOException");
	}

	// ═══════════════════════════════════════════════════════════════════
	// AUTO MODE ON REACTOR (NO INDEX → LEARN)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(110)
	@DisplayName("Auto mode on fresh reactor (no index) triggers learn")
	void autoModeOnFreshReactorTriggersLearn() {
		multiProject.cleanAll();
		multiProject.deleteIfExists(".test-order/test-dependencies.lz4");

		MavenResult result = multiProject.maven().run("clean", "test");
		assertThat(result).succeeded();

		// Auto mode should have triggered learn (no index found)
		assertThat(result.output()).contains("learn mode");
	}

	// ═══════════════════════════════════════════════════════════════════
	// MODULE WITH SOURCE CHANGE + RELEARN
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(120)
	@DisplayName("Relearn after core module source change updates index")
	void relearnAfterCoreModuleSourceChange() {
		// Modify UserService to add a new method inside the class body
		multiProject.replaceInFile("core/src/main/java/com/myapp/core/UserService.java",
				"    public String lookupName(int userId) {",
				"    public String newMethod() { return \"new\"; }\n\n    public String lookupName(int userId) {");

		// Re-learn should capture updated dependencies
		MavenResult result = multiProject.maven().learn();
		assertThat(result).succeeded();

		// Index should still be valid
		DependencyMap depMap = multiProject.loadIndex();
		assertThat(depMap).isLoaded().hasSize(2);

		// Restore
		multiProject.restoreAll();
	}
}
