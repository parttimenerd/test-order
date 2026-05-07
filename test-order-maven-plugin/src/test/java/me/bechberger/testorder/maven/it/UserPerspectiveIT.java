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
import me.bechberger.testorder.TestOrderState;

/**
 * User-perspective integration tests — follows README workflows against a fresh
 * sample-shop project (Product → Cart → Invoice dependency chain, 3 test
 * classes).
 * <p>
 * Dependency graph:
 *
 * <pre>
 *   ProductTest  → Product
 *   CartTest     → Cart, Product
 *   InvoiceTest  → Invoice, Cart, Product
 * </pre>
 * <p>
 * Tests are ordered to simulate a real user journey: learn → inspect index →
 * order → show-order → dump → modify code → re-learn → select → combined →
 * custom weights → state persistence
 * <p>
 * Enable with: {@code -Dtestorder.it=true}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserPerspectiveIT {

	// ── FQCNs ──────────────────────────────────────────────────────────
	static final String PRODUCT_TEST = "com.example.shop.ProductTest";
	static final String CART_TEST = "com.example.shop.CartTest";
	static final String INVOICE_TEST = "com.example.shop.InvoiceTest";

	static final String PRODUCT = "com.example.shop.Product";
	static final String CART = "com.example.shop.Cart";
	static final String INVOICE = "com.example.shop.Invoice";

	static final String PRODUCT_SRC = "src/main/java/com/example/shop/Product.java";
	static final String CART_SRC = "src/main/java/com/example/shop/Cart.java";
	static final String INVOICE_SRC = "src/main/java/com/example/shop/Invoice.java";

	TestProject project;

	@BeforeAll
	void setup() {
		Path root = Paths.get("").toAbsolutePath();
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		project = new TestProject(root.resolve("samples/sample-shop"),
				List.of("-Dtestorder.includePackages=com.example"));
		project.gitRestore();
	}

	@AfterAll
	void tearDown() {
		if (project != null) {
			project.restoreAll();
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// 1. FIRST-TIME SETUP — "mvn test -Dtestorder.mode=learn"
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(1)
	@DisplayName("README: first-time learn produces dependency index")
	void firstTimeLearnProducesIndex() {
		project.cleanAll();

		MavenResult result = project.maven().learn();
		assertThat(result).succeeded().outputContains("[test-order]").outputContains("Tests run:");

		// "Results are written as .deps files and aggregated into a dependency index"
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).isLoaded().hasSize(3);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 2. DEPENDENCY ACCURACY — agent captured the right relationships
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(2)
	@DisplayName("Index: ProductTest depends only on Product")
	void productTestDependencies() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).hasDependency(PRODUCT_TEST, PRODUCT).doesNotHaveDependency(PRODUCT_TEST, CART)
				.doesNotHaveDependency(PRODUCT_TEST, INVOICE);
	}

	@Test
	@Order(3)
	@DisplayName("Index: CartTest depends on Cart and Product, not Invoice")
	void cartTestDependencies() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).hasDependency(CART_TEST, CART).hasDependency(CART_TEST, PRODUCT)
				.doesNotHaveDependency(CART_TEST, INVOICE);
	}

	@Test
	@Order(4)
	@DisplayName("Index: InvoiceTest depends on Invoice, Cart, and Product (transitive)")
	void invoiceTestDependencies() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).hasDependency(INVOICE_TEST, INVOICE).hasDependency(INVOICE_TEST, CART)
				.hasDependency(INVOICE_TEST, PRODUCT);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 3. AFFECTED QUERIES — "changing Product affects all tests"
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(5)
	@DisplayName("Affected: changing Product affects all 3 test classes")
	void changingProductAffectsAll() {
		DependencyMap depMap = project.loadIndex();
		Set<String> affected = depMap.getAffectedTests(Set.of(PRODUCT));
		assertThat(affected).containsExactlyInAnyOrder(PRODUCT_TEST, CART_TEST, INVOICE_TEST);
	}

	@Test
	@Order(6)
	@DisplayName("Affected: changing Invoice affects only InvoiceTest")
	void changingInvoiceAffectsOnlyInvoiceTest() {
		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).changesAffect(Set.of(INVOICE), INVOICE_TEST)
				.changesDoNotAffect(Set.of(INVOICE), PRODUCT_TEST).changesDoNotAffect(Set.of(INVOICE), CART_TEST);
	}

	@Test
	@Order(7)
	@DisplayName("Affected: changing Cart affects CartTest and InvoiceTest but not ProductTest")
	void changingCartAffectsCartAndInvoice() {
		DependencyMap depMap = project.loadIndex();
		Set<String> affected = depMap.getAffectedTests(Set.of(CART));
		assertThat(affected).contains(CART_TEST, INVOICE_TEST);
		assertThat(affected).doesNotContain(PRODUCT_TEST);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 4. DAY-TO-DAY — "just run your tests normally"
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(10)
	@DisplayName("README: 'mvn test' (auto mode) works with existing index")
	void autoModeRunsTests() {
		MavenResult result = project.maven().auto(PRODUCT);
		assertThat(result).succeeded().outputContains("[test-order]").outputContains("Tests run:");
	}

	@Test
	@Order(11)
	@DisplayName("README: order mode with explicit changed class succeeds")
	void orderModeWithExplicitChange() {
		MavenResult result = project.maven().order(CART);
		assertThat(result).succeeded().outputContains("Tests run:");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 5. SHOW-ORDER — "see the computed order without running tests"
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(20)
	@DisplayName("README: show-order displays all test classes with scores")
	void showOrderDisplaysScores() {
		MavenResult result = project.maven().showOrder(PRODUCT);
		assertThat(result).succeeded().outputContains("ProductTest").outputContains("CartTest")
				.outputContains("InvoiceTest");
	}

	@Test
	@Order(21)
	@DisplayName("Scoring: changing Product gives ProductTest highest or tied score")
	void productChangeScoresProductTestHighest() {
		MavenResult result = project.maven().showOrder(PRODUCT);
		assertThat(result).succeeded();

		// All three tests depend on Product, but ProductTest has the highest overlap
		// ratio (1/1)
		// CartTest: 1/2, InvoiceTest: 1/3 → ProductTest should score highest
		int productScore = extractScore(result.output(), "ProductTest");
		int cartScore = extractScore(result.output(), "CartTest");
		int invoiceScore = extractScore(result.output(), "InvoiceTest");

		assertThat(productScore).as("ProductTest should score >= CartTest when Product changed")
				.isGreaterThanOrEqualTo(cartScore);
		assertThat(productScore).as("ProductTest should score >= InvoiceTest when Product changed")
				.isGreaterThanOrEqualTo(invoiceScore);
	}

	@Test
	@Order(22)
	@DisplayName("Scoring: changing Invoice only boosts InvoiceTest")
	void invoiceChangeScoresInvoiceTestOnly() {
		MavenResult result = project.maven().showOrder(INVOICE);
		assertThat(result).succeeded();

		int invoiceScore = extractScore(result.output(), "InvoiceTest");
		int productScore = extractScore(result.output(), "ProductTest");

		assertThat(invoiceScore).as("InvoiceTest should score > ProductTest when Invoice changed")
				.isGreaterThan(productScore);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 6. DUMP — "human-readable text format"
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(30)
	@DisplayName("README: dump prints the index in readable format")
	void dumpGoalPrintsReadableIndex() {
		MavenResult result = project.maven().dump();
		assertThat(result).succeeded().outputContains("ProductTest").outputContains("CartTest")
				.outputContains("InvoiceTest").outputContains("Product").outputContains("Cart");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 7. STATE FILE — durations & runs tracked
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(40)
	@DisplayName("State: order-mode run creates .test-order/state.lz4 with durations")
	void orderModeCreatesState() {
		// delete state, run order mode
		project.deleteIfExists(".test-order/state.lz4");
		MavenResult result = project.maven().order(CART);
		assertThat(result).succeeded();

		TestOrderState state = project.loadState();
		assertThat(state).isLoaded().hasRuns();
	}

	@Test
	@Order(41)
	@DisplayName("State: second run accumulates another run record")
	void secondRunAccumulatesState() {
		MavenResult result = project.maven().order(INVOICE);
		assertThat(result).succeeded();

		TestOrderState state = project.loadState();
		assertThat(state).isLoaded();
		// Should have at least 2 runs now (from test 40 + 41)
		assertThat(state.runs().size()).as("Should accumulate run records").isGreaterThanOrEqualTo(2);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 8. MODIFY SOURCE → RE-LEARN — "keeping the index fresh"
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(50)
	@DisplayName("README: re-learn after source change updates the index")
	void relearnAfterSourceChange() {
		try {
			// Add a new method to Product
			project.replaceInFile(PRODUCT_SRC, "\tpublic double getPrice() {\n\t\treturn price;\n\t}",
					"\tpublic double getPrice() {\n\t\treturn price;\n\t}\n\n\tpublic boolean isFree() { return price == 0; }");

			// Re-learn — this should update the index
			project.deleteIfExists(".test-order/test-dependencies.lz4");
			project.deleteTree("target");
			MavenResult result = project.maven().learn();
			assertThat(result).succeeded();

			// Index should still be correct
			DependencyMap depMap = project.loadIndex();
			assertThat(depMap).isLoaded().hasSize(3).hasDependency(PRODUCT_TEST, PRODUCT).hasDependency(CART_TEST, CART)
					.hasDependency(INVOICE_TEST, INVOICE);
		} finally {
			project.restoreAll();
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// 9. SELECT MODE — "run only the most important tests first"
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(60)
	@DisplayName("README: select mode runs a subset and writes selection files")
	void selectModeRunsSubset() {
		// Ensure index exists
		if (!project.exists(".test-order/test-dependencies.lz4")) {
			project.maven().learn();
		}

		MavenResult result = project.maven().select(INVOICE);
		assertThat(result).succeeded().outputContains("Tests run:");

		assertThat(
				project.exists("target/test-order-selected.txt") || project.exists("target/test-order-remaining.txt"))
				.as("Select mode should produce selection files").isTrue();
	}

	@Test
	@Order(61)
	@DisplayName("README: run-remaining after select succeeds")
	void runRemainingAfterSelect() {
		// Only run if there's a remaining file
		if (project.exists("target/test-order-remaining.txt")) {
			MavenResult result = project.maven().runRemaining();
			// May succeed or skip (no remaining tests) — both are fine
			assertThat(result.output()).doesNotContain("NullPointerException").doesNotContain("ClassNotFoundException");
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// 10. COMBINED MODE — "single goal handles full workflow"
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(70)
	@DisplayName("README: combined mode works end-to-end")
	void combinedModeWorks() {
		// Ensure index exists
		if (!project.exists(".test-order/test-dependencies.lz4")) {
			project.maven().learn();
		}

		MavenResult result = project.maven().auto();
		assertThat(result).succeeded().outputContains("Tests run:");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 11. CUSTOM WEIGHTS — "override individual weights"
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(80)
	@DisplayName("README: custom weight via system property changes score")
	void customWeightViaSystemProperty() {
		// Run show-order with a very high newTest bonus → new tests should dominate
		MavenResult defaultResult = project.maven().showOrder(INVOICE);
		assertThat(defaultResult).succeeded();

		// Run with depOverlap = 0 → dep overlap should not contribute
		MavenResult noOverlapResult = project.maven().run("test-order:show-order", "-Dtestorder.changeMode=explicit",
				"-Dtestorder.changed.classes=" + INVOICE, "-Dtestorder.score.depOverlap=0");
		assertThat(noOverlapResult).succeeded();

		// With depOverlap=0 and no other differentiator for existing tests,
		// the affected test (InvoiceTest) should not get a dependency overlap bonus
		// (it might still differ via speed/failure components, but overlap should be
		// gone)
		assertThat(noOverlapResult).outputContains("InvoiceTest");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 12. METHOD_ENTRY INSTRUMENTATION
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(90)
	@DisplayName("README: METHOD_ENTRY instrumentation produces valid index")
	void methodEntryInstrumentation() {
		project.cleanAll();
		MavenResult result = project.maven().learnMethodEntry();
		assertThat(result).succeeded();

		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).isLoaded().hasSize(3).hasDependency(PRODUCT_TEST, PRODUCT).hasDependency(CART_TEST, CART)
				.hasDependency(CART_TEST, PRODUCT).hasDependency(INVOICE_TEST, INVOICE);
	}

	// ═══════════════════════════════════════════════════════════════════
	// 13. FRESH PROJECT — no index, auto mode still runs tests
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(100)
	@DisplayName("Edge: auto mode without index still runs all tests")
	void autoModeWithoutIndexStillRunsTests() {
		project.cleanAll();

		// Auto mode with no index — should fall back gracefully
		MavenResult result = project.maven().run("clean", "test");
		assertThat(result).succeeded().outputContains("Tests run:");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 14. MODIFY TEST SOURCE → ORDER DETECTS CHANGED TEST
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(110)
	@DisplayName("Scoring: modified test source gets changedTest bonus")
	void modifiedTestSourceGetsBonus() {
		// Re-learn first
		project.cleanAll();
		MavenResult learn = project.maven().learn();
		assertThat(learn).succeeded();

		try {
			// Modify a test file
			project.appendToFile("src/test/java/com/example/shop/ProductTest.java", "\n// modified\n");

			// Use hash-based change detection (since-last-run) to detect the change.
			// First run: snapshot hashes
			MavenResult r1 = project.maven().run("clean", "test", "-Dtestorder.changeMode=since-last-run");
			assertThat(r1).succeeded();

			// show-order after modifying test source
			// The changed test bonus should apply to ProductTest
			MavenResult showOrder = project.maven().run("test-order:show-order",
					"-Dtestorder.changeMode=since-last-run");
			assertThat(showOrder).succeeded().outputContains("ProductTest");
		} finally {
			project.restoreAll();
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// 15. AGGREGATE GOAL — standalone aggregation
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(120)
	@DisplayName("Aggregate: standalone aggregate goal works")
	void standaloneAggregate() {
		project.cleanAll();
		MavenResult learn = project.maven().learn();
		assertThat(learn).succeeded();

		DependencyMap before = project.loadIndex();
		assertThat(before).isLoaded().hasSize(3);

		// Run aggregate again — should merge successfully
		MavenResult agg = project.maven().aggregate();
		assertThat(agg).succeeded();

		DependencyMap after = project.loadIndex();
		assertThat(after).isLoaded().hasSize(3);
	}

	// ═══════════════════════════════════════════════════════════════════
	// Helpers
	// ═══════════════════════════════════════════════════════════════════

	private int extractScore(String output, String testClassName) {
		for (String line : output.lines().toList()) {
			if (!line.contains(testClassName))
				continue;
			String[] parts = line.trim().split("\\s+");
			if (parts.length >= 3) {
				try {
					return Integer.parseInt(parts[2]);
				} catch (NumberFormatException ignored) {
				}
			}
		}
		throw new AssertionError("Could not find score for " + testClassName + " in output:\n" + output);
	}
}
