package me.bechberger.testorder.maven.it;

import static me.bechberger.testorder.maven.it.TestOrderAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * User scenario integration test: E-commerce checkout system
 *
 * Tests a realistic scenario with 3 test classes for payment/order processing:
 * - PaymentProcessorTest: tests payment validation and discount logic -
 * UserValidatorTest: tests user input validation (email, password, username) -
 * OrderServiceTest: tests order creation (depends on PaymentProcessor)
 *
 * Scenarios covered: 1. Learn mode builds dependency index 2. Order mode
 * prioritizes tests based on code changes 3. Failure tracking - recently failed
 * tests run first
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.user.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserScenarioIT {

	private static final String PROJECT_DIR = "user-test-project";

	private static final String PAYMENT_PROCESSOR_TEST = "com.usertest.app.PaymentProcessorTest";
	private static final String USER_VALIDATOR_TEST = "com.usertest.app.UserValidatorTest";
	private static final String ORDER_SERVICE_TEST = "com.usertest.app.OrderServiceTest";

	private static final String PAYMENT_PROCESSOR = "com.usertest.app.PaymentProcessor";
	private static final String USER_VALIDATOR = "com.usertest.app.UserValidator";
	private static final String ORDER_SERVICE = "com.usertest.app.OrderService";

	private TestProject project;

	@BeforeAll
	void setup() {
		var root = java.nio.file.Paths.get("").toAbsolutePath();
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		project = new TestProject(root.resolve(PROJECT_DIR));
	}

	@AfterAll
	void tearDown() {
		if (project != null) {
			project.restoreAll();
		}
	}

	@Test
	@Order(1)
	@DisplayName("Learn mode creates dependency index with 3 test classes")
	void testLearnModeCreatesIndex() {
		project.cleanAll();

		MavenResult result = project.maven().learn();
		assertThat(result).succeeded();
		assertThat(result).outputContains("[test-order]");
		assertThat(result).outputContains("Tests run: 12");

		// Verify index was created and has all 3 test classes
		var depMap = project.loadIndex();
		assertThat(depMap).isLoaded().hasSize(3).hasTestClass(PAYMENT_PROCESSOR_TEST).hasTestClass(USER_VALIDATOR_TEST)
				.hasTestClass(ORDER_SERVICE_TEST);
	}

	@Test
	@Order(2)
	@DisplayName("Dependencies: PaymentProcessorTest depends on payment logic")
	void testPaymentProcessorDependencies() {
		var depMap = project.loadIndex();

		assertThat(depMap).hasDependency(PAYMENT_PROCESSOR_TEST, PAYMENT_PROCESSOR)
				.hasDependency(PAYMENT_PROCESSOR_TEST, PAYMENT_PROCESSOR_TEST);
	}

	@Test
	@Order(3)
	@DisplayName("Dependencies: UserValidatorTest has independent dependencies")
	void testUserValidatorDependencies() {
		var depMap = project.loadIndex();

		assertThat(depMap).hasDependency(USER_VALIDATOR_TEST, USER_VALIDATOR)
				.doesNotHaveDependency(USER_VALIDATOR_TEST, PAYMENT_PROCESSOR)
				.doesNotHaveDependency(USER_VALIDATOR_TEST, ORDER_SERVICE);
	}

	@Test
	@Order(4)
	@DisplayName("Dependencies: OrderServiceTest transitively depends on PaymentProcessor")
	void testOrderServiceTransitiveDependencies() {
		var depMap = project.loadIndex();

		// OrderServiceTest directly depends on OrderService, which depends on
		// PaymentProcessor
		assertThat(depMap).hasDependency(ORDER_SERVICE_TEST, ORDER_SERVICE).hasDependency(ORDER_SERVICE_TEST,
				PAYMENT_PROCESSOR);
	}

	@Test
	@Order(5)
	@DisplayName("Show order: PaymentProcessor change prioritizes affected tests")
	void testOrderWhenPaymentProcessorChanges() {
		MavenResult result = project.maven().showOrder(PAYMENT_PROCESSOR);

		assertThat(result).succeeded();
		assertThat(result).outputContains("PaymentProcessorTest");

		// Extract test order from output - look for abbreviated names
		String[] lines = result.output().split("\n");
		int paymentIdx = -1, orderIdx = -1, userIdx = -1;

		for (int i = 0; i < lines.length; i++) {
			if (lines[i].contains("PaymentProcessorTest"))
				paymentIdx = i;
			if (lines[i].contains("OrderServiceTest"))
				orderIdx = i;
			if (lines[i].contains("UserValidatorTest"))
				userIdx = i;
		}

		// PaymentProcessorTest should come before OrderServiceTest
		// OrderServiceTest should come before UserValidatorTest
		assertThat(paymentIdx).as("PaymentProcessorTest should appear in output").isGreaterThanOrEqualTo(0);
		assertThat(orderIdx).as("OrderServiceTest should appear in output").isGreaterThanOrEqualTo(0);
		assertThat(userIdx).as("UserValidatorTest should appear in output").isGreaterThanOrEqualTo(0);
		assertThat(paymentIdx).as("PaymentProcessorTest should come before OrderServiceTest").isLessThan(orderIdx);
		assertThat(orderIdx).as("OrderServiceTest should come before UserValidatorTest").isLessThan(userIdx);
	}

	@Test
	@Order(6)
	@DisplayName("Show order: UserValidator change prioritizes UserValidatorTest")
	void testOrderWhenUserValidatorChanges() {
		MavenResult result = project.maven().showOrder(USER_VALIDATOR);

		assertThat(result).succeeded();
		assertThat(result).outputContains("UserValidatorTest");

		String[] lines = result.output().split("\n");
		int paymentIdx = -1, orderIdx = -1, userIdx = -1;

		for (int i = 0; i < lines.length; i++) {
			if (lines[i].contains("PaymentProcessorTest"))
				paymentIdx = i;
			if (lines[i].contains("OrderServiceTest"))
				orderIdx = i;
			if (lines[i].contains("UserValidatorTest"))
				userIdx = i;
		}

		// UserValidatorTest should be prioritized first
		assertThat(userIdx).as("UserValidatorTest should appear in output").isGreaterThanOrEqualTo(0);
		assertThat(paymentIdx).as("PaymentProcessorTest should appear in output").isGreaterThanOrEqualTo(0);
		assertThat(orderIdx).as("OrderServiceTest should appear in output").isGreaterThanOrEqualTo(0);
		assertThat(userIdx).as("UserValidatorTest should be prioritized first").isLessThan(paymentIdx);
		assertThat(userIdx).as("UserValidatorTest should come before OrderServiceTest").isLessThan(orderIdx);
	}

	@Test
	@Order(7)
	@DisplayName("Order mode with changes runs tests in prioritized order")
	void testOrderModeRunsTestsInOrder() {
		// Reset to ensure clean state
		project.cleanAll();
		MavenResult learnResult = project.maven().learn();
		assertThat(learnResult).succeeded();

		// Run tests with PaymentProcessor marked as changed
		MavenResult result = project.maven().order(PAYMENT_PROCESSOR);
		assertThat(result).succeeded().outputContains("Tests run: 12").outputContains(PAYMENT_PROCESSOR_TEST)
				.outputContains(USER_VALIDATOR_TEST).outputContains(ORDER_SERVICE_TEST);
	}

	@Test
	@Order(8)
	@DisplayName("Failure tracking: introduce and track test failure")
	void testFailureTracking() {
		// Introduce a bug in OrderService.calculateTotal()
		project.replaceInFile("src/main/java/com/usertest/app/OrderService.java", "return subtotal * (1 + taxRate);",
				"return subtotal; // bug: forgot to add tax");

		// Run tests - OrderServiceTest should fail
		MavenResult failResult = project.maven().order();
		assertThat(failResult).failed().outputContains("testCalculateTotal");

		// Verify state file was created with failure info
		var state = project.loadState();
		assertThat(state).isNotNull();
		// The state should track this failure for future prioritization

		// Fix the bug
		project.replaceInFile("src/main/java/com/usertest/app/OrderService.java",
				"return subtotal; // bug: forgot to add tax", "return subtotal * (1 + taxRate);");

		// Run tests again - should pass
		MavenResult passResult = project.maven().order();
		assertThat(passResult).succeeded().outputContains("Tests run: 12");
	}

	@Test
	@Order(9)
	@DisplayName("Dump index shows human-readable dependency information")
	void testDumpIndexReadability() {
		MavenResult result = project.maven().dump();
		assertThat(result).succeeded();

		// Verify all test classes appear in dump
		assertThat(result).outputContains("PaymentProcessorTest");
		assertThat(result).outputContains("UserValidatorTest");
		assertThat(result).outputContains("OrderServiceTest");

		// Verify dependencies are shown
		assertThat(result).outputContains("PaymentProcessor");
		assertThat(result).outputContains("UserValidator");
		assertThat(result).outputContains("OrderService");
	}

	@Test
	@Order(10)
	@DisplayName("Multiple changes: both PaymentProcessor and UserValidator changed")
	void testMultipleChanges() {
		MavenResult result = project.maven().showOrder(PAYMENT_PROCESSOR, USER_VALIDATOR);

		assertThat(result).succeeded();

		// When both are changed, both dependent tests should have high scores
		// PaymentProcessorTest and UserValidatorTest should run before OrderServiceTest
		String[] lines = result.output().split("\n");
		int paymentIdx = -1, orderIdx = -1, userIdx = -1;

		for (int i = 0; i < lines.length; i++) {
			if (lines[i].contains("PaymentProcessorTest"))
				paymentIdx = i;
			if (lines[i].contains("OrderServiceTest"))
				orderIdx = i;
			if (lines[i].contains("UserValidatorTest"))
				userIdx = i;
		}

		// Both PaymentProcessorTest and UserValidatorTest should come before
		// OrderServiceTest
		assertThat(Math.min(paymentIdx, userIdx)).isLessThan(orderIdx);
	}
}
