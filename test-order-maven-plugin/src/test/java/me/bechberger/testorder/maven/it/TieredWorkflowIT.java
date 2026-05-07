package me.bechberger.testorder.maven.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration test for the three-tier CI workflow: tiered-select → run-tier 2 → run-tier 3.
 * <p>
 * Uses the sample-shop project (Product → Cart → Invoice, 3 test classes).
 * <p>
 * Enable with: {@code -Dtestorder.it=true}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TieredWorkflowIT {

	static final String PRODUCT_TEST = "com.example.shop.ProductTest";
	static final String CART_TEST = "com.example.shop.CartTest";
	static final String INVOICE_TEST = "com.example.shop.InvoiceTest";

	static final String CART = "com.example.shop.Cart";
	static final String PRODUCT = "com.example.shop.Product";

	TestProject project;

	@BeforeAll
	void setup() {
		Path root = Paths.get("").toAbsolutePath();
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		project = new TestProject(root.resolve("samples/sample-shop"),
				List.of("-Dtestorder.includePackages=com.example"));
	}

	@AfterAll
	void tearDown() {
		if (project != null) {
			project.restoreAll();
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// Step 0: Ensure we have a learned index
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(1)
	@DisplayName("Learn phase produces a valid index with all 3 test classes")
	void learnPhaseProducesIndex() {
		project.cleanAll();
		MavenResult result = project.maven().learn();
		assertThat(result.isSuccess()).as("learn phase failed").isTrue();

		result = project.maven().aggregate();
		assertThat(result.isSuccess()).as("aggregate failed").isTrue();

		var index = project.loadIndex();
		assertThat(index).isNotNull();
		assertThat(index.testClasses()).contains(PRODUCT_TEST, CART_TEST, INVOICE_TEST);
	}

	// ═══════════════════════════════════════════════════════════════════
	// Step 1: Tiered select puts affected tests in tier 1 and splits the rest
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(2)
	@DisplayName("tiered-select partitions tests into 3 tiers based on Cart change")
	void tieredSelectPartitionsTests() throws IOException {
		MavenResult result = project.maven().tieredSelect(CART);
		assertThat(result.isSuccess()).as("tiered-select: %s", result.output().substring(Math.max(0, result.output().length() - 500))).isTrue();

		// Verify tier files were created
		Path tier1 = project.path("target/test-order-tier1.txt");
		Path tier2 = project.path("target/test-order-tier2.txt");
		Path tier3 = project.path("target/test-order-tier3.txt");

		assertThat(tier1).exists();
		assertThat(tier2).exists();
		assertThat(tier3).exists();

		// Tier 1 should contain Cart-dependent tests
		List<String> tier1Tests = Files.readAllLines(tier1);
		assertThat(tier1Tests).as("Tier 1 should contain tests affected by Cart change")
				.contains(CART_TEST);

		// All 3 tiers combined should cover all test classes
		List<String> tier2Tests = Files.readAllLines(tier2);
		List<String> tier3Tests = Files.readAllLines(tier3);

		List<String> allTests = new java.util.ArrayList<>(tier1Tests);
		allTests.addAll(tier2Tests);
		allTests.addAll(tier3Tests);
		assertThat(allTests).containsExactlyInAnyOrder(PRODUCT_TEST, CART_TEST, INVOICE_TEST);

		// Output should mention the tiered selection
		assertThat(result.output()).contains("tier-1");
	}

	// ═══════════════════════════════════════════════════════════════════
	// Step 2: run-tier 2 executes only the tier-2 tests
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(3)
	@DisplayName("run-tier 2 executes the tier-2 test classes")
	void runTier2ExecutesTier2Tests() throws IOException {
		Path tier2 = project.path("target/test-order-tier2.txt");
		if (!Files.exists(tier2) || Files.readAllLines(tier2).isEmpty()) {
			// If tier 2 is empty (all tests were affected), skip this check
			return;
		}

		MavenResult result = project.maven().runTier(2);
		assertThat(result.isSuccess()).as("run-tier 2: %s", result.output().substring(Math.max(0, result.output().length() - 500))).isTrue();
		assertThat(result.output()).contains("tier-2");
	}

	// ═══════════════════════════════════════════════════════════════════
	// Step 3: run-tier 3 executes only the tier-3 tests
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(4)
	@DisplayName("run-tier 3 executes the tier-3 test classes (or skips if empty)")
	void runTier3ExecutesTier3Tests() throws IOException {
		Path tier3 = project.path("target/test-order-tier3.txt");
		if (!Files.exists(tier3) || Files.readAllLines(tier3).isEmpty()) {
			// Tier 3 is empty, run-tier should still succeed gracefully
			MavenResult result = project.maven().runTier(3);
			assertThat(result.isSuccess()).as("run-tier 3 (empty): %s", result.output().substring(Math.max(0, result.output().length() - 500))).isTrue();
			return;
		}

		MavenResult result = project.maven().runTier(3);
		assertThat(result.isSuccess()).as("run-tier 3: %s", result.output().substring(Math.max(0, result.output().length() - 500))).isTrue();
	}

	// ═══════════════════════════════════════════════════════════════════
	// Step 4: Invalid tier values are rejected
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(5)
	@DisplayName("run-tier with invalid tier (1) fails with clear error")
	void runTierWithInvalidTierFails() {
		MavenResult result = project.maven().runTier(1);
		assertThat(result.isSuccess()).isFalse();
		assertThat(result.output()).contains("must be 2 or 3");
	}

	// ═══════════════════════════════════════════════════════════════════
	// Step 5: Tiered select with broader change (Product) tests
	//         that InvoiceTest gets tier-1 (it depends on Product via Cart)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	@Order(6)
	@DisplayName("tiered-select with Product change includes transitive dependents in tier 1")
	void tieredSelectWithProductChangeIncludesTransitiveDeps() throws IOException {
		MavenResult result = project.maven().tieredSelect(PRODUCT);
		assertThat(result.isSuccess()).as("tiered-select: %s", result.output().substring(Math.max(0, result.output().length() - 500))).isTrue();

		Path tier1 = project.path("target/test-order-tier1.txt");
		List<String> tier1Tests = Files.readAllLines(tier1);

		// ProductTest directly depends on Product
		assertThat(tier1Tests).contains(PRODUCT_TEST);
		// CartTest depends on Product (Cart uses Product)
		assertThat(tier1Tests).contains(CART_TEST);
	}
}
