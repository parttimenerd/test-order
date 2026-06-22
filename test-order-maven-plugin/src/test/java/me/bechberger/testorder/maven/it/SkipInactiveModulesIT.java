package me.bechberger.testorder.maven.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration tests for the skip-inactive-modules feature.
 *
 * <p>
 * Uses samples/sample-skip-inactive: five-module reactor where
 * core←lib←service←web plus standalone analytics. When only Stats.java
 * (analytics) changes, the other four jar modules have no affected tests and
 * are transitively unrequired → they get skipped.
 *
 * <p>
 * Enable with: {@code -Dtestorder.it=true}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SkipInactiveModulesIT {

	static final String STATS_SRC = "analytics/src/main/java/com/myapp/analytics/Stats.java";

	TestProject project;

	@BeforeAll
	void setup() {
		Path root = Paths.get("").toAbsolutePath();
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		project = new TestProject(root.resolve("samples/sample-skip-inactive"),
				List.of("-Dtestorder.includePackages=com.myapp"));
	}

	@AfterAll
	void tearDown() {
		if (project != null) {
			project.gitRestore();
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// 1. LEARN — build the dependency index
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(1)
	@DisplayName("Learn mode: all five modules compile and test cleanly")
	void learnModeSucceeds() {
		project.cleanAll();
		MavenResult result = project.maven().learn();
		assertThat(result.isSuccess()).as("learn must succeed:\n" + result.output()).isTrue();
		assertThat(result.output()).contains("Tests run:");
	}

	@Test
	@Order(2)
	@DisplayName("Learn mode: index contains tests from all five modules")
	void learnIndexContainsAllTests() {
		var depMap = project.loadIndex();
		assertThat(depMap).isNotNull();
		assertThat(depMap.testClasses()).as("Index should contain test classes from all five modules")
				.anySatisfy(tc -> assertThat(tc).contains("FormatterTest"))
				.anySatisfy(tc -> assertThat(tc).contains("MessageBuilderTest"))
				.anySatisfy(tc -> assertThat(tc).contains("OrderServiceTest"))
				.anySatisfy(tc -> assertThat(tc).contains("OrderControllerTest"))
				.anySatisfy(tc -> assertThat(tc).contains("StatsTest"));
	}

	// ═══════════════════════════════════════════════════════════════════
	// 2. AFFECTED RUN — modify Stats.java only (standalone analytics module)
	//
	// analytics has no dependents → when only analytics is active,
	// core/lib/service/web all have no affected tests and are not
	// transitively required by analytics → they get skipped (4 modules).
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(10)
	@DisplayName("Affected run: four jar modules are skipped when only analytics changes")
	void fourModulesAreSkippedWhenOnlyAnalyticsChanges() {
		// Introduce an uncommitted change to Stats.java
		project.replaceInFile(STATS_SRC, "return values.length == 0 ? 0 : sum / values.length;",
				"return values.length == 0 ? 0.0 : sum / values.length;");

		MavenResult result = project.maven().run("test-order:affected", "test", "-Dtestorder.changeMode=uncommitted",
				"-Dsurefire.failIfNoSpecifiedTests=false", "-Dspotless.check.skip=true");

		assertThat(result.isSuccess()).as("affected run must succeed:\n" + result.output()).isTrue();

		// The skip-inactive feature should report that 4 modules were skipped
		assertThat(result.output()).as("Expected skip-inactive log line for 4 modules").contains("reactor skip:")
				.contains("4 module(s)");
	}

	@Test
	@Order(11)
	@DisplayName("Affected run: analytics test ran (it has affected tests)")
	void analyticsTestRan() {
		MavenResult result = project.maven().run("test-order:affected", "test", "-Dtestorder.changeMode=uncommitted",
				"-Dsurefire.failIfNoSpecifiedTests=false", "-Dspotless.check.skip=true");

		assertThat(result.isSuccess()).as("affected run must succeed:\n" + result.output()).isTrue();
		assertThat(result.output()).as("StatsTest should have run").contains("StatsTest");
	}

	@Test
	@Order(12)
	@DisplayName("Affected run: core/lib/service/web module tests did NOT run")
	void nonAnalyticsModuleTestsDidNotRun() {
		MavenResult result = project.maven().run("test-order:affected", "test", "-Dtestorder.changeMode=uncommitted",
				"-Dsurefire.failIfNoSpecifiedTests=false", "-Dspotless.check.skip=true");

		assertThat(result.isSuccess()).as("affected run must succeed:\n" + result.output()).isTrue();
		// maven.test.skip=true suppresses surefire entirely on skipped modules
		assertThat(result.output()).as("Non-analytics tests should NOT have run").doesNotContain("FormatterTest")
				.doesNotContain("MessageBuilderTest").doesNotContain("OrderServiceTest")
				.doesNotContain("OrderControllerTest");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 3. OPT-OUT — verify -DtestOrder.skipInactiveModules=false runs all modules
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(20)
	@DisplayName("Opt-out: -DtestOrder.skipInactiveModules=false runs all modules")
	void optOutRunsAllModules() {
		// Stats.java still modified from order(10) — file is restored in @AfterAll
		MavenResult result = project.maven().run("test-order:affected", "test", "-Dtestorder.changeMode=uncommitted",
				"-Dtestorder.skipInactiveModules=false", "-Dsurefire.failIfNoSpecifiedTests=false",
				"-Dspotless.check.skip=true");

		assertThat(result.isSuccess()).as("opt-out run must succeed:\n" + result.output()).isTrue();
		assertThat(result.output()).as("Skip-inactive log line should be absent when feature is disabled")
				.doesNotContain("reactor skip: set maven.main.skip");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 4. CLEAN STATE — no change means 0 active modules, no skip
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(30)
	@DisplayName("No changes: 0 active modules → skip-inactive logic does not fire")
	void noChangesNoSkip() {
		// Restore source files so there are no uncommitted diffs
		project.gitRestore();

		MavenResult result = project.maven().run("test-order:affected", "test", "-Dtestorder.changeMode=uncommitted",
				"-Dsurefire.failIfNoSpecifiedTests=false", "-Dspotless.check.skip=true");

		assertThat(result.isSuccess()).as("clean run must succeed:\n" + result.output()).isTrue();
		assertThat(result.output()).as("Should not skip modules when there are no active modules")
				.doesNotContain("reactor skip: set maven.main.skip");
	}
}
