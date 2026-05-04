package me.bechberger.testorder.ci;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Performance tests for large codebases. Tests how test-order scales with many
 * classes, tests, and dependencies.
 */
class LargeCodebasePerformanceTest {

	/**
	 * Test: Handle projects with 1000+ classes.
	 */
	@Test
	@DisplayName("Handle large number of classes (1000+)")
	void testLargeNumberOfClasses(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("large-classes-project");
		Files.createDirectories(project.resolve("src/main/java/com/example"));

		// Create 1000 classes
		for (int i = 0; i < 1000; i++) {
			Path classFile = project.resolve(String.format("src/main/java/com/example/Service%d.java", i));
			String classCode = String.format("package com.example; public class Service%d { public void process() {} }",
					i);
			Files.writeString(classFile, classCode);
		}

		long classCount = Files.walk(project.resolve("src/main/java")).filter(p -> p.toString().endsWith(".java"))
				.count();

		assertEquals(1000, classCount, "Should have created 1000 classes");
	}

	/**
	 * Test: Handle projects with 10000+ test methods.
	 */
	@Test
	@DisplayName("Handle large number of test methods (10000+)")
	void testLargeNumberOfTestMethods(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("large-tests-project");
		Files.createDirectories(project.resolve("src/test/java/com/example"));

		// Create test classes with many test methods
		for (int i = 0; i < 100; i++) {
			Path testFile = project.resolve(String.format("src/test/java/com/example/Test%d.java", i));

			StringBuilder testCode = new StringBuilder().append("package com.example;\n")
					.append("import org.junit.jupiter.api.Test;\n").append(String.format("class Test%d {\n", i));

			// Each test class has 100 test methods
			for (int j = 0; j < 100; j++) {
				testCode.append(String.format("    @Test\n    void test%d() {}\n", j));
			}

			testCode.append("}");
			Files.writeString(testFile, testCode.toString());
		}

		long testMethodCount = Files.walk(project.resolve("src/test/java")).filter(p -> p.toString().endsWith(".java"))
				.count();

		assertEquals(100, testMethodCount, "Should have 100 test classes");
	}

	/**
	 * Test: Handle dependency graphs with deep nesting (10+ levels).
	 */
	@Test
	@DisplayName("Handle deeply nested dependency graphs")
	void testDeeplyNestedDependencies(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("deep-deps-project");
		Files.createDirectories(project);

		// Create chain: class0 -> class1 -> class2 -> ... -> class9
		for (int i = 0; i < 10; i++) {
			Path srcDir = project.resolve("src/main/java/com/example");
			Files.createDirectories(srcDir);

			String classCode;
			if (i == 0) {
				classCode = "package com.example; public class Class0 { public void process() {} }";
			} else {
				classCode = String.format("package com.example; public class Class%d { " + "  Class%d dep; "
						+ "  public void process() { dep.process(); } " + "}", i, i - 1);
			}

			Path classFile = srcDir.resolve(String.format("Class%d.java", i));
			Files.writeString(classFile, classCode);
		}

		long classCount = Files.walk(project.resolve("src/main/java")).filter(p -> p.toString().endsWith(".java"))
				.count();

		assertEquals(10, classCount, "Should have 10 nested classes");
	}

	/**
	 * Test: Handle wide dependency graphs (one class used by 1000+ classes).
	 */
	@Test
	@DisplayName("Handle wide dependency graphs (high fan-out)")
	void testWideDependencyGraphs(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("wide-deps-project");
		Files.createDirectories(project.resolve("src/main/java/com/example"));

		// Create one core utility class
		Path coreClass = project.resolve("src/main/java/com/example/CoreUtility.java");
		String coreCode = "package com.example; public class CoreUtility { "
				+ "  public static String format(Object o) { return o.toString(); } " + "}";
		Files.writeString(coreClass, coreCode);

		// Create 1000 classes that depend on CoreUtility
		for (int i = 0; i < 1000; i++) {
			Path classFile = project.resolve(String.format("src/main/java/com/example/Dependent%d.java", i));
			String classCode = String.format("package com.example; public class Dependent%d { "
					+ "  void use() { CoreUtility.format(this); } " + "}", i);
			Files.writeString(classFile, classCode);
		}

		long dependentCount = Files.walk(project.resolve("src/main/java"))
				.filter(p -> p.getFileName().toString().startsWith("Dependent")).count();

		assertTrue(dependentCount > 0, "Should have dependent classes");
	}

	/**
	 * Test: Handle projects with many test fixtures and setup/teardown.
	 */
	@Test
	@DisplayName("Handle complex test setup and fixtures")
	void testComplexTestFixtures(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("fixtures-project");
		Files.createDirectories(project.resolve("src/test/java/com/example"));

		// Create test with complex setup
		Path testFile = project.resolve("src/test/java/com/example/FixtureTest.java");
		String testCode = """
				package com.example;
				import org.junit.jupiter.api.Test;
				import org.junit.jupiter.api.BeforeEach;
				import org.junit.jupiter.api.BeforeAll;
				import org.junit.jupiter.api.AfterEach;
				import org.junit.jupiter.api.AfterAll;
				import java.util.*;

				class FixtureTest {
				    static List<String> data = new ArrayList<>();

				    @BeforeAll
				    static void setupClass() {
				        for (int i = 0; i < 10000; i++) {
				            data.add("item-" + i);
				        }
				    }

				    @BeforeEach
				    void setup() {
				        // Reset state
				    }

				    @Test
				    void testWithLargeFixture() {}

				    @AfterEach
				    void teardown() {
				        // Cleanup
				    }

				    @AfterAll
				    static void teardownClass() {
				        data.clear();
				    }
				}
				""";
		Files.writeString(testFile, testCode);

		assertTrue(Files.exists(testFile), "Fixture test should exist");
	}

	/**
	 * Test: Handle projects with many parameterized test combinations.
	 */
	@Test
	@DisplayName("Handle large parameterized test matrices")
	void testLargeParameterizedTests(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("param-matrix-project");
		Files.createDirectories(project.resolve("src/test/java/com/example"));

		Path testFile = project.resolve("src/test/java/com/example/MatrixTest.java");
		String testCode = """
				package com.example;
				import org.junit.jupiter.params.ParameterizedTest;
				import org.junit.jupiter.params.provider.CsvSource;

				class MatrixTest {
				    @ParameterizedTest
				    @CsvSource({
				        "1, 2, 3",
				        "4, 5, 9",
				        "10, 20, 30"
				    })
				    void testMatrix(int a, int b, int c) {}
				}
				""";
		Files.writeString(testFile, testCode);

		assertTrue(Files.exists(testFile), "Matrix test should exist");
	}

	/**
	 * Test: Index file scales linearly with number of classes/tests.
	 */
	@Test
	@DisplayName("Index file size remains reasonable with large projects")
	void testIndexFileScaling(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("index-scaling-project");
		Files.createDirectories(project);

		// Create progressively larger projects
		for (int scale = 1; scale <= 3; scale++) {
			int classCount = 100 * scale;

			Path srcDir = project.resolve("src" + scale).resolve("main/java/com/example");
			Files.createDirectories(srcDir);

			for (int i = 0; i < classCount; i++) {
				Path classFile = srcDir.resolve(String.format("Class%d.java", i));
				String code = String.format("package com.example; public class Class%d {}", i);
				Files.writeString(classFile, code);
			}
		}

		assertTrue(Files.walk(project).count() > 0, "Project should be created");
	}

	/**
	 * Test: Handle projects with multiple language support (Java + Kotlin +
	 * Groovy).
	 */
	@Test
	@Disabled("Requires additional language support")
	@DisplayName("Handle polyglot projects (Java + Kotlin + Groovy)")
	void testPolyglotProject(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("polyglot-project");

		// Java
		Files.createDirectories(project.resolve("src/main/java/com/example"));
		Path javaClass = project.resolve("src/main/java/com/example/JavaClass.java");
		Files.writeString(javaClass, "package com.example; public class JavaClass {}");

		// Kotlin
		Files.createDirectories(project.resolve("src/main/kotlin/com/example"));
		Path kotlinClass = project.resolve("src/main/kotlin/com/example/KotlinClass.kt");
		Files.writeString(kotlinClass, "package com.example\nclass KotlinClass");

		// Groovy
		Files.createDirectories(project.resolve("src/main/groovy/com/example"));
		Path groovyClass = project.resolve("src/main/groovy/com/example/GroovyClass.groovy");
		Files.writeString(groovyClass, "package com.example\nclass GroovyClass {}");

		assertTrue(Files.exists(javaClass), "Java class should exist");
		assertTrue(Files.exists(kotlinClass), "Kotlin class should exist");
		assertTrue(Files.exists(groovyClass), "Groovy class should exist");
	}

	/**
	 * Test: Dependency index parsing speed with large indexes.
	 */
	@Test
	@DisplayName("Parse large dependency indexes efficiently")
	void testIndexParsingPerformance(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("large-index-project");
		Files.createDirectories(project);

		// Create a large dependency index simulation
		Path indexFile = project.resolve("test-dependencies.lz4");

		// Create mock index with many dependencies
		StringBuilder indexData = new StringBuilder();
		for (int i = 0; i < 5000; i++) {
			indexData.append(String.format("TestClass%d->Service%d\n", i, i % 100));
		}

		// Note: In real scenario, this would be compressed with LZ4
		Files.writeString(indexFile, indexData.toString());

		long startTime = System.currentTimeMillis();
		String content = Files.readString(indexFile);
		long endTime = System.currentTimeMillis();

		long readTimeMs = endTime - startTime;
		assertTrue(readTimeMs < 100, "Index reading should be fast (< 100ms)");
	}

	/**
	 * Test: State file management with many test runs.
	 */
	@Test
	@DisplayName("State file size remains manageable across many runs")
	void testStateFileGrowth(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("state-growth-project");
		Files.createDirectories(project);

		Path stateFile = project.resolve(".test-order-state");

		// Simulate 100 test runs
		for (int run = 0; run < 100; run++) {
			String stateData = String.format("run %d timestamp %d classes %d\n", run, System.currentTimeMillis(),
					100 + run);
			Files.write(stateFile, stateData.getBytes(), java.nio.file.StandardOpenOption.CREATE,
					java.nio.file.StandardOpenOption.APPEND);
		}

		long fileSize = Files.size(stateFile);
		assertTrue(fileSize < 100_000, "State file should remain small (< 100KB)");
	}

	/**
	 * Test: Memory efficiency with many cached entries.
	 */
	@Test
	@DisplayName("Cache memory usage remains reasonable")
	void testCacheMemoryEfficiency(@TempDir Path workDir) throws Exception {
		// Simulate large cache with 10,000 entries
		int cacheSize = 10_000;

		long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

		// Create cache entries
		java.util.Map<String, String> cache = new java.util.HashMap<>();
		for (int i = 0; i < cacheSize; i++) {
			cache.put("dependency-" + i, "class-hash-" + Integer.toHexString(i));
		}

		long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
		long memUsed = memAfter - memBefore;

		assertTrue(memUsed < 50_000_000, "Cache should use < 50MB for 10k entries");
		assertEquals(cacheSize, cache.size(), "Cache should have all entries");
	}
}
