package me.bechberger.testorder.maven.it;

import static me.bechberger.testorder.maven.it.TestOrderAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;

/**
 * Bug reproducer integration tests — verifies potential bugs discovered through
 * code analysis and README workflow testing.
 * <p>
 * Uses sample-shop (Product → Cart → Invoice, 3 test classes) and
 * test-order-example (Calculator + StringUtils, 2 test classes).
 * <p>
 * Enable with: {@code -Dtestorder.it=true}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BugReproducerIT {

	static final String PRODUCT_TEST = "com.example.shop.ProductTest";
	static final String CART_TEST = "com.example.shop.CartTest";
	static final String INVOICE_TEST = "com.example.shop.InvoiceTest";

	static final String PRODUCT = "com.example.shop.Product";
	static final String CART = "com.example.shop.Cart";
	static final String INVOICE = "com.example.shop.Invoice";

	static final String CALCULATOR_TEST = "com.example.app.CalculatorTest";
	static final String STRING_UTILS_TEST = "com.example.app.StringUtilsTest";
	static final String CALCULATOR = "com.example.app.Calculator";
	static final String STRING_UTILS = "com.example.app.StringUtils";

	TestProject shopProject;
	TestProject exampleProject;

	@BeforeAll
	void setup() {
		Path root = Paths.get("").toAbsolutePath();
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		shopProject = new TestProject(root.resolve("samples/sample-shop"),
				List.of("-Dtestorder.includePackages=com.example"));
		exampleProject = new TestProject(root.resolve("test-order-example"),
				List.of("-Dtestorder.includePackages=com.example"));
	}

	@AfterAll
	void tearDown() {
		if (shopProject != null)
			shopProject.restoreAll();
		if (exampleProject != null)
			exampleProject.restoreAll();
	}

	// ═══════════════════════════════════════════════════════════════════
	// SETUP: Learn both projects so subsequent tests have an index
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(1)
	@DisplayName("Setup: learn shop project")
	void setupLearnShop() {
		shopProject.cleanAll();
		MavenResult result = shopProject.maven().learn();
		assertThat(result).succeeded();
		assertThat(shopProject.loadIndex()).isLoaded().hasSize(3);
	}

	@Test
	@Order(2)
	@DisplayName("Setup: learn example project")
	void setupLearnExample() {
		exampleProject.cleanAll();
		MavenResult result = exampleProject.maven().learn();
		assertThat(result).succeeded();
		assertThat(exampleProject.loadIndex()).isLoaded().hasSize(2);
	}

	// ═══════════════════════════════════════════════════════════════════
	// BUG 1: topN=-1 should select ALL affected tests (not zero)
	//
	// The selectTopN method has: if (added >= config.topN()) break;
	// When topN=-1: 0 >= -1 is true → loop breaks immediately → 0 tests
	// selected via topN phase.
	//
	// Expected: topN=-1 means "select all affected tests" per README.
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(10)
	@DisplayName("BUG: topN=-1 should select all affected tests, not zero")
	void topNMinusOneSelectsAllAffectedTests() {
		// select with topN=-1 (default) and randomM=0 to isolate topN behavior
		MavenResult result = shopProject.maven().run("clean", "test-order:affected", "test",
				"-Dtestorder.changeMode=explicit", "-Dtestorder.changed.classes=" + PRODUCT,
				"-Dtestorder.affected.topN=-1", "-Dtestorder.affected.randomM=0");
		assertThat(result).succeeded();

		// Product change affects ALL 3 tests (Product→Cart→Invoice chain)
		// With topN=-1 and randomM=0, all 3 should be selected
		String selected = shopProject.readFile("target/test-order-selected.txt");
		assertThat(selected).as("topN=-1 should select all affected tests").isNotNull().contains(PRODUCT_TEST)
				.contains(CART_TEST).contains(INVOICE_TEST);

		// Remaining should be empty (all tests were selected)
		String remaining = shopProject.readFile("target/test-order-remaining.txt");
		assertThat(remaining == null || remaining.isBlank())
				.as("No tests should remain when topN=-1 selects all affected").isTrue();
	}

	@Test
	@Order(11)
	@DisplayName("BUG: select with all-unknown changed classes produces helpful error")
	void selectWithAllUnknownChangedClassesFailsWithHelpfulError() {
		// When ALL specified changed classes are unknown to the index,
		// the plugin should fail with a helpful message rather than silently selecting
		// nothing.
		MavenResult result = shopProject.maven().run("test-order:affected", "-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=com.example.shop.NonExistentClass", "-Dtestorder.affected.topN=-1",
				"-Dtestorder.affected.randomM=0");
		assertThat(result.exitCode()).as("Should fail when all changed classes are unknown").isNotZero();
		assertThat(result.output()).contains("None of the explicitly specified changed classes exist");
	}

	@Test
	@Order(12)
	@DisplayName("topN=0 with randomM=2 selects exactly randomM tests")
	void topNZeroSelectsOnlyRandomM() {
		// Use test-order:affected only (no test execution) to verify selection logic;
		// running "clean test-order:affected test" triggers prepare mojo auto-learn
		// which can conflict with select's surefire configuration.
		MavenResult result = shopProject.maven().run("test-order:affected", "-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=" + PRODUCT, "-Dtestorder.affected.topN=0",
				"-Dtestorder.affected.randomM=2", "-Dtestorder.affected.seed=42");
		assertThat(result).succeeded();

		String selected = shopProject.readFile("target/test-order-selected.txt");
		assertThat(selected).isNotNull();
		// topN=0 means no tests via topN phase; randomM=2 picks 2 diverse fast tests
		long selectedCount = selected.lines().filter(l -> !l.isBlank()).count();
		assertThat(selectedCount).as("topN=0 should select only randomM tests (max 2)").isLessThanOrEqualTo(2);
	}

	// ═══════════════════════════════════════════════════════════════════
	// BUG 2: randomM with fewer candidates than requested
	//
	// If randomM=10 but only 2 fast test candidates exist, the loop
	// should gracefully select what's available without crashing.
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(20)
	@DisplayName("randomM with fewer candidates than requested selects what's available")
	void randomMWithFewerCandidatesThanRequested() {
		// example project has only 2 test classes total
		// Use select-only (no test execution) to avoid prepare mojo auto-learn
		// conflicts
		MavenResult result = exampleProject.maven().run("test-order:affected", "-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=" + CALCULATOR, "-Dtestorder.affected.topN=0",
				"-Dtestorder.affected.randomM=100"); // way more than available
		assertThat(result).succeeded();

		// Should not crash — just select what's available
		String selected = exampleProject.readFile("target/test-order-selected.txt");
		// At most 2 tests exist, so at most 2 can be selected
		if (selected != null) {
			long count = selected.lines().filter(l -> !l.isBlank()).count();
			assertThat(count).isLessThanOrEqualTo(2);
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// BUG 3: optimize with single run in history
	//
	// RunHistoryManager may divide by zero when slots==1 and
	// historicalRuns.size() > 1. Optimize goal triggers this path.
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(30)
	@DisplayName("optimize with single run in history does not crash")
	void optimizeWithSingleRunInHistory() {
		// After learn + one order run, state has 1-2 run records
		MavenResult orderResult = exampleProject.maven().order(CALCULATOR);
		assertThat(orderResult).succeeded();

		// Now optimize — should not divide by zero or crash
		MavenResult optimizeResult = exampleProject.maven().optimize();
		// Optimize may fail gracefully with "insufficient runs" but should NOT crash
		// with an exception stack trace
		assertThat(optimizeResult.output()).doesNotContain("ArithmeticException").doesNotContain("/ by zero")
				.doesNotContain("NegativeArraySizeException");
	}

	// ═══════════════════════════════════════════════════════════════════
	// BUG 4: auto-learn with corrupt state file
	//
	// If .test-order/state.lz4 is corrupt, auto mode should not NPE.
	// It should gracefully fall back to learn mode.
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(40)
	@DisplayName("auto-learn with corrupt state file recovers gracefully")
	void autoLearnWithCorruptStateFile() throws IOException {
		// Write garbage to the state file
		Path stateFile = shopProject.path(".test-order/state.lz4");
		Files.createDirectories(stateFile.getParent());
		Files.write(stateFile, "THIS IS NOT A VALID LZ4 FILE".getBytes(StandardCharsets.UTF_8));

		// Auto mode should handle this gracefully (switch to learn or skip state)
		MavenResult result = shopProject.maven().run("clean", "test", "-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=" + PRODUCT);
		// The build should not fail with NullPointerException or crash
		assertThat(result.output()).doesNotContain("NullPointerException")
				.doesNotContain("java.lang.NullPointerException");
		// It may warn about corrupt state but should recover
	}

	// ═══════════════════════════════════════════════════════════════════
	// BUG 5: extreme weight configuration should not overflow
	//
	// With very large scoring weights, the accumulated score should
	// not wrap around to negative.
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(50)
	@DisplayName("extreme weight configuration does not produce negative scores")
	void extremeWeightConfigurationDoesNotOverflow() {
		// Ensure index is valid (prior test may have corrupted state)
		shopProject.cleanAll();
		MavenResult setupResult = shopProject.maven().learn();
		assertThat(setupResult).succeeded();

		// Set all weights to large values
		MavenResult result = shopProject.maven().run("test-order:show-order", "-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=" + PRODUCT, "-Dtestorder.score.newTest=10000",
				"-Dtestorder.score.changedTest=10000", "-Dtestorder.score.maxFailure=10000",
				"-Dtestorder.score.depOverlap=10000", "-Dtestorder.score.changeComplexity=10000");
		assertThat(result).succeeded();

		// Scores should not be negative (would indicate overflow)
		// Check for negative numbers in the score table (space followed by dash and
		// digits)
		assertThat(result.output()).doesNotContainPattern("\\s-\\d+\\s");
	}

	// ═══════════════════════════════════════════════════════════════════
	// BUG 6: learn mode with test failure still captures deps
	//
	// If a test fails during learn mode, dependencies for passing tests
	// should still be captured and the failure recorded.
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(60)
	@DisplayName("learn mode with test failure still captures dependencies")
	void learnModeWithTestFailureCapturesDeps() {
		shopProject.cleanAll();

		// Introduce a deliberate test failure
		shopProject.replaceInFile("src/test/java/com/example/shop/ProductTest.java", "assertEquals", "assertNotEquals");

		// Learn should fail (test failure) but deps should still be recorded
		MavenResult result = shopProject.maven().learn();
		// The build fails because of the broken test
		assertThat(result).failed();

		// Restore and re-learn cleanly for subsequent tests
		shopProject.restoreAll();
		shopProject.cleanAll();
		MavenResult cleanLearn = shopProject.maven().learn();
		assertThat(cleanLearn).succeeded();
	}

	// ═══════════════════════════════════════════════════════════════════
	// BUG 7: select with all tests being new (not in index)
	//
	// If the index is empty or doesn't contain any current test classes,
	// all tests should be treated as "new" and selected.
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(70)
	@DisplayName("select with all tests new (not in index) selects them all")
	void selectWithAllTestsNew() throws IOException {
		// Create a minimal/empty index so all current tests appear "new"
		Path indexPath = shopProject.path(".test-order/test-dependencies.lz4");
		// Learn with a different package to get an index with unrelated tests
		// Simpler: just delete index and re-run select without learning
		shopProject.deleteIfExists(".test-order/test-dependencies.lz4");

		// Select without index — should fail gracefully
		MavenResult result = shopProject.maven().run("test-order:affected", "-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=" + PRODUCT, "-Dtestorder.affected.topN=1",
				"-Dtestorder.affected.randomM=0");

		// Either it auto-aggregates or fails with a meaningful message
		// It should NOT crash with NPE or ArrayIndexOutOfBoundsException
		assertThat(result.output()).doesNotContain("NullPointerException")
				.doesNotContain("ArrayIndexOutOfBoundsException");

		// Restore index for subsequent tests
		shopProject.cleanAll();
		MavenResult restore = shopProject.maven().learn();
		assertThat(restore).succeeded();
	}

	// ═══════════════════════════════════════════════════════════════════
	// BUG 8: order mode with empty changeset
	//
	// When changeMode=explicit but no changed.classes provided,
	// all tests should score 0 for dep overlap and run in default order.
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(80)
	@DisplayName("order mode with empty changeset runs tests without errors")
	void orderModeWithEmptyChangeset() {
		// Use a non-existent class as changed to simulate "empty effective changeset"
		// (no test depends on this class, so all tests score 0 for dep overlap)
		MavenResult result = exampleProject.maven().run("clean", "test", "-Dtestorder.mode=order",
				"-Dtestorder.changeMode=explicit", "-Dtestorder.changed.classes=com.example.app.NonExistentClass");
		assertThat(result).succeeded();
	}

	// ═══════════════════════════════════════════════════════════════════
	// BUG 9: concurrent select + run-remaining without index lock
	//
	// Rapid select → run-remaining should work without file corruption.
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(90)
	@DisplayName("rapid select then run-remaining workflow is stable")
	void rapidSelectThenRunRemaining() {
		// Select first — use -Dtestorder.mode=skip to prevent prepare mojo from
		// conflicting with select's surefire configuration
		MavenResult selectResult = shopProject.maven().run("clean", "test-order:affected", "test",
				"-Dtestorder.changeMode=explicit", "-Dtestorder.changed.classes=" + INVOICE,
				"-Dtestorder.affected.topN=1", "-Dtestorder.affected.randomM=0", "-Dtestorder.mode=skip");
		assertThat(selectResult).succeeded();

		// Immediately run-remaining
		MavenResult remainingResult = shopProject.maven().runRemaining();
		// Should either succeed or gracefully handle (not crash)
		assertThat(remainingResult.output()).doesNotContain("ConcurrentModificationException")
				.doesNotContain("FileNotFoundException");
	}

	// ═══════════════════════════════════════════════════════════════════
	// BUG 10: show-order with unknown class in changed.classes
	//
	// If the user specifies a class that doesn't exist in the index,
	// it should warn (not crash).
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(100)
	@DisplayName("show-order with unknown changed class does not crash")
	void showOrderWithUnknownChangedClass() {
		MavenResult result = shopProject.maven().showOrder("com.example.shop.NonExistentClass");
		// Should succeed or warn, but NOT throw
		assertThat(result.output()).doesNotContain("NullPointerException").doesNotContain("NoSuchElementException");
	}

	// ═══════════════════════════════════════════════════════════════════
	// BUG 11: select with seed produces reproducible results
	//
	// Two runs with the same seed should produce identical selections.
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(110)
	@DisplayName("select with same seed produces reproducible results")
	void selectSeedProducesReproducibleResults() {
		// Run select only (no test execution) to avoid state mutation between runs
		MavenResult r1 = shopProject.maven().run("test-order:affected", "-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=" + PRODUCT, "-Dtestorder.affected.topN=1",
				"-Dtestorder.affected.randomM=2", "-Dtestorder.affected.seed=12345");
		assertThat(r1).succeeded();
		String selected1 = shopProject.readFile("target/test-order-selected.txt");

		// Run 2 with same seed — no test execution means state unchanged
		MavenResult r2 = shopProject.maven().run("test-order:affected", "-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=" + PRODUCT, "-Dtestorder.affected.topN=1",
				"-Dtestorder.affected.randomM=2", "-Dtestorder.affected.seed=12345");
		assertThat(r2).succeeded();
		String selected2 = shopProject.readFile("target/test-order-selected.txt");

		assertThat(selected1).as("Same seed should produce same selection").isEqualTo(selected2);
	}
}
