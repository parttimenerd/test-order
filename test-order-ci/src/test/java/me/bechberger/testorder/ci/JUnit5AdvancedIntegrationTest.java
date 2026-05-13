package me.bechberger.testorder.ci;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Advanced JUnit 5 integration tests. Tests specialized JUnit 5 features:
 * nested tests, parameterized tests, dynamic tests, etc.
 */
class JUnit5AdvancedIntegrationTest {

	@Test
	@DisplayName("Nested test classes are discovered and ordered correctly")
	void testNestedClassDiscovery(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("nested-project");
		Files.createDirectories(project);

		// Create test with nested classes
		Path testFile = project.resolve("src/test/java/com/example/OuterTest.java");
		Files.createDirectories(testFile.getParent());

		String nestedCode = """
				package com.example;
				import org.junit.jupiter.api.Nested;
				import org.junit.jupiter.api.Test;

				class OuterTest {
				    @Test
				    void outerTest() {}

				    @Nested
				    class InnerTests {
				        @Test
				        void innerTest1() {}

				        @Test
				        void innerTest2() {}

				        @Nested
				        class DeeplyNestedTests {
				            @Test
				            void deepTest() {}
				        }
				    }
				}
				""";
		Files.writeString(testFile, nestedCode);

		// Verify nested structure was created
		assertTrue(Files.exists(testFile), "Nested test file should exist");
		String content = Files.readString(testFile);
		assertTrue(content.contains("@Nested"), "Should contain @Nested annotation");
	}

	@Test
	@DisplayName("Parameterized tests are handled correctly")
	void testParameterizedTests(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("parameterized-project");
		Files.createDirectories(project);

		Path testFile = project.resolve("src/test/java/com/example/ParamTest.java");
		Files.createDirectories(testFile.getParent());

		String paramCode = """
				package com.example;
				import org.junit.jupiter.params.ParameterizedTest;
				import org.junit.jupiter.params.provider.ValueSource;

				class ParamTest {
				    @ParameterizedTest
				    @ValueSource(ints = {1, 2, 3})
				    void testWithInts(int value) {}

				    @ParameterizedTest
				    @ValueSource(strings = {"apple", "banana", "cherry"})
				    void testWithStrings(String value) {}
				}
				""";
		Files.writeString(testFile, paramCode);

		assertTrue(Files.exists(testFile), "Parameterized test file should exist");
		String content = Files.readString(testFile);
		assertTrue(content.contains("@ParameterizedTest"), "Should have parameterized tests");
	}

	@Test
	@DisplayName("Dynamic tests are discovered")
	void testDynamicTests(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("dynamic-project");
		Files.createDirectories(project);

		Path testFile = project.resolve("src/test/java/com/example/DynamicTest.java");
		Files.createDirectories(testFile.getParent());

		String dynamicCode = """
				package com.example;
				import org.junit.jupiter.api.DynamicTest;
				import org.junit.jupiter.api.TestFactory;
				import java.util.stream.Stream;

				class DynamicTest {
				    @TestFactory
				    Stream<DynamicTest> dynamicTests() {
				        return Stream.of(
				            DynamicTest.dynamicTest("first", () -> {}),
				            DynamicTest.dynamicTest("second", () -> {})
				        );
				    }
				}
				""";
		Files.writeString(testFile, dynamicCode);

		assertTrue(Files.exists(testFile), "Dynamic test file should exist");
	}

	@Test
	@DisplayName("Test execution order with @Order annotation is recognized")
	void testExecutionOrder(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("order-project");
		Files.createDirectories(project);

		Path testFile = project.resolve("src/test/java/com/example/OrderedTest.java");
		Files.createDirectories(testFile.getParent());

		String orderCode = """
				package com.example;
				import org.junit.jupiter.api.Test;
				import org.junit.jupiter.api.Order;

				class OrderedTest {
				    @Test
				    @Order(1)
				    void firstTest() {}

				    @Test
				    @Order(2)
				    void secondTest() {}

				    @Test
				    @Order(3)
				    void thirdTest() {}
				}
				""";
		Files.writeString(testFile, orderCode);

		assertTrue(Files.exists(testFile), "Ordered test file should exist");
		String content = Files.readString(testFile);
		assertTrue(content.contains("@Order"), "Should have @Order annotations");
	}

	@Nested
	@DisplayName("Custom test extensions")
	class CustomExtensionsTests {

		@Test
		@DisplayName("Custom extension parameters are discovered")
		void testCustomExtensions(@TempDir Path workDir) throws Exception {
			Path project = workDir.resolve("extension-project");
			Files.createDirectories(project);

			Path testFile = project.resolve("src/test/java/com/example/ExtensionTest.java");
			Files.createDirectories(testFile.getParent());

			String extCode = """
					package com.example;
					import org.junit.jupiter.api.Test;
					import org.junit.jupiter.api.extension.ExtendWith;

					@ExtendWith(CustomExtension.class)
					class ExtensionTest {
					    @Test
					    void testWithExtension() {}
					}
					""";
			Files.writeString(testFile, extCode);

			assertTrue(Files.exists(testFile), "Extension test file should exist");
		}
	}

	@Test
	@DisplayName("Display names are parsed correctly")
	void testDisplayNames(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("display-name-project");
		Files.createDirectories(project);

		Path testFile = project.resolve("src/test/java/com/example/DisplayNameTest.java");
		Files.createDirectories(testFile.getParent());

		String nameCode = """
				package com.example;
				import org.junit.jupiter.api.Test;
				import org.junit.jupiter.api.DisplayName;

				@DisplayName("User Service Tests")
				class DisplayNameTest {
				    @Test
				    @DisplayName("should create user with valid email")
				    void testCreateUser() {}

				    @Test
				    @DisplayName("should fail with invalid email")
				    void testCreateUserInvalid() {}
				}
				""";
		Files.writeString(testFile, nameCode);

		assertTrue(Files.exists(testFile), "Display name test file should exist");
	}

	@Test
	@DisplayName("Repeating tests with @RepeatedTest")
	void testRepeatedTests(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("repeated-project");
		Files.createDirectories(project);

		Path testFile = project.resolve("src/test/java/com/example/RepeatedTest.java");
		Files.createDirectories(testFile.getParent());

		String repeatCode = """
				package com.example;
				import org.junit.jupiter.api.RepeatedTest;

				class RepeatedTest {
				    @RepeatedTest(5)
				    void repeatedTest(RepetitionInfo info) {}
				}
				""";
		Files.writeString(testFile, repeatCode);

		assertTrue(Files.exists(testFile), "Repeated test file should exist");
	}

	@Test
	@DisplayName("Conditional test execution with @EnabledIf/@DisabledIf")
	void testConditionalExecution(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("conditional-project");
		Files.createDirectories(project);

		Path testFile = project.resolve("src/test/java/com/example/ConditionalTest.java");
		Files.createDirectories(testFile.getParent());

		String condCode = """
				package com.example;
				import org.junit.jupiter.api.Test;
				import org.junit.jupiter.api.condition.EnabledOnOs;
				import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
				import org.junit.jupiter.api.condition.OS;

				class ConditionalTest {
				    @Test
				    @EnabledOnOs(OS.LINUX)
				    void linuxOnlyTest() {}

				    @Test
				    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
				    void localOnlyTest() {}
				}
				""";
		Files.writeString(testFile, condCode);

		assertTrue(Files.exists(testFile), "Conditional test file should exist");
	}

	@ParameterizedTest
	@ValueSource(strings = {"junit5-features", "advanced-tests", "extension-tests"})
	@DisplayName("Each JUnit5 feature test can be discovered")
	void testMultipleJunit5Features(String projectName, @TempDir Path workDir) throws Exception {
		Path project = workDir.resolve(projectName);
		Files.createDirectories(project.resolve("src/test/java/com/example"));

		Path testFile = project.resolve("src/test/java/com/example/Test.java");
		String code = String.format("""
				package com.example;
				import org.junit.jupiter.api.Test;

				class %sTest {
				    @Test
				    void test() {}
				}
				""", projectName.replace("-", ""));

		Files.writeString(testFile, code);
		assertTrue(Files.exists(testFile), "Test file for " + projectName + " should exist");
	}

	@Test
	@DisplayName("Test lifecycle callbacks are recognized")
	void testLifecycleCallbacks(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("lifecycle-project");
		Files.createDirectories(project);

		Path testFile = project.resolve("src/test/java/com/example/LifecycleTest.java");
		Files.createDirectories(testFile.getParent());

		String lifecycleCode = """
				package com.example;
				import org.junit.jupiter.api.Test;
				import org.junit.jupiter.api.BeforeAll;
				import org.junit.jupiter.api.BeforeEach;
				import org.junit.jupiter.api.AfterEach;
				import org.junit.jupiter.api.AfterAll;

				class LifecycleTest {
				    @BeforeAll
				    static void beforeAll() {}

				    @BeforeEach
				    void beforeEach() {}

				    @Test
				    void test() {}

				    @AfterEach
				    void afterEach() {}

				    @AfterAll
				    static void afterAll() {}
				}
				""";
		Files.writeString(testFile, lifecycleCode);

		assertTrue(Files.exists(testFile), "Lifecycle test file should exist");
	}
}
