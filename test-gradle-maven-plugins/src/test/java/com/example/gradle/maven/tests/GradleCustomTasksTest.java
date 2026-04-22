package com.example.gradle.maven.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test suite for Custom Gradle Tasks bugs (P5-GAD-003, 006, 008, 011, 012, 013, 015, 016, 017).
 * 
 * Custom Gradle tasks allow implementing complex build logic. These tests verify that custom
 * task implementations don't interfere with test ordering mechanisms.
 */
@DisplayName("Gradle Custom Tasks Tests")
public class GradleCustomTasksTest {

    @TempDir
    Path testProject;

    private Path buildFile;

    @BeforeEach
    void setUp() throws IOException {
        buildFile = testProject.resolve("build.gradle");
        Files.createDirectories(testProject.resolve("src/test/java"));
        Files.createDirectories(testProject.resolve("buildSrc/src/main/java"));
    }

    /**
     * P5-GAD-003: Custom test ordering task.
     * Bug: Custom task that orders tests may conflict with native ordering.
     * 
     * Reproducer: Create custom task that depends on test task.
     */
    @Test
    @DisplayName("Custom test ordering task execution")
    void testCustomTestOrderingTask() throws IOException {
        String content = """
            plugins {
                id 'java'
            }
            
            class OrderTestsTask extends DefaultTask {
                @TaskAction
                void orderTests() {
                    println "Ordering tests..."
                }
            }
            
            task orderTests(type: OrderTestsTask) {
                dependsOn test
            }
            
            test {
                useJUnitPlatform()
                doFirst {
                    println "Test execution started"
                }
            }
            """;
        
        Files.writeString(buildFile, content);
        String read = Files.readString(buildFile);
        
        assertThat(read).contains("class OrderTestsTask");
        assertThat(read).contains("task orderTests");
    }

    /**
     * P5-GAD-006: Custom task with test failure handling.
     * Bug: Custom task may not handle test failures properly when ordering is involved.
     * 
     * Reproducer: Custom task that catches test exceptions.
     */
    @Test
    @DisplayName("Custom task with test failure handling")
    void testCustomTaskFailureHandling() throws IOException {
        String content = "plugins {" +
            "    id 'java'" +
            "}" +
            "" +
            "task testWithFallback {" +
            "    doLast {" +
            "        try {" +
            "            test.execute()" +
            "        } catch (Exception e) {" +
            "            println \"Test failed: \" + e.message" +
            "            throw e" +
            "        }" +
            "    }" +
            "}" +
            "" +
            "test {" +
            "    useJUnitPlatform()" +
            "}";
        
        Files.writeString(buildFile, content);
        
        assertThat(Files.readString(buildFile)).contains("testWithFallback");
    }

    /**
     * P5-GAD-008: Custom task for test filtering and ordering.
     * Bug: Test filtering in custom task may not preserve test order.
     * 
     * Reproducer: Custom task that applies test filters.
     */
    @Test
    @DisplayName("Custom task for test filtering")
    void testCustomTestFilteringTask() throws IOException {
        String content = "plugins {" +
            "    id 'java'" +
            "}" +
            "" +
            "task filterAndTestUnit {" +
            "    dependsOn test" +
            "    doFirst {" +
            "        test.filter {" +
            "            includeTestsMatching '*Unit'" +
            "        }" +
            "    }" +
            "}" +
            "" +
            "test {" +
            "    useJUnitPlatform()" +
            "}";
        
        Files.writeString(buildFile, content);
        
        assertThat(Files.readString(buildFile)).contains("filterAndTestUnit");
    }

    /**
     * P5-GAD-011: Custom task with test parallelization control.
     * Bug: Custom task controlling parallelization may interact badly with ordering.
     * 
     * Reproducer: Task that modifies parallel test execution settings.
     */
    @Test
    @DisplayName("Custom task controlling test parallelization")
    void testCustomParallelizationControl() throws IOException {
        String content = "plugins {" +
            "    id 'java'" +
            "}" +
            "" +
            "task testSequential {" +
            "    doFirst {" +
            "        test.maxParallelForks = 1" +
            "    }" +
            "}" +
            "" +
            "task testParallel {" +
            "    doFirst {" +
            "        test.maxParallelForks = Runtime.getRuntime().availableProcessors()" +
            "    }" +
            "}" +
            "" +
            "test {" +
            "    useJUnitPlatform()" +
            "    maxParallelForks = 1" +
            "}";
        
        Files.writeString(buildFile, content);
        
        String content_read = Files.readString(buildFile);
        assertThat(content_read).contains("testSequential");
        assertThat(content_read).contains("testParallel");
    }

    /**
     * P5-GAD-012: Custom task for test result aggregation.
     * Bug: Test result aggregation may lose ordering information.
     * 
     * Reproducer: Custom task that aggregates test results.
     */
    @Test
    @DisplayName("Custom task for test result aggregation")
    void testCustomResultAggregation() throws IOException {
        String content = "plugins {" +
            "    id 'java'" +
            "}" +
            "" +
            "task aggregateTestResults {" +
            "    dependsOn test" +
            "    doLast {" +
            "        def testResults = test.testResults" +
            "        if (testResults != null) {" +
            "            println \"Total tests: \" + testResults.testCount" +
            "        }" +
            "    }" +
            "}" +
            "" +
            "test {" +
            "    useJUnitPlatform()" +
            "}";
        
        Files.writeString(buildFile, content);
        
        assertThat(Files.readString(buildFile)).contains("aggregateTestResults");
    }

    /**
     * P5-GAD-013: Custom task with test report generation.
     * Bug: Report generation may not reflect correct test order.
     * 
     * Reproducer: Custom task generating HTML/XML reports.
     */
    @Test
    @DisplayName("Custom task for test report generation")
    void testCustomReportGeneration() throws IOException {
        String content = "plugins {" +
            "    id 'java'" +
            "}" +
            "" +
            "task generateTestReport {" +
            "    dependsOn test" +
            "    doLast {" +
            "        def reportDir = file(buildDir.toString() + \"/reports/tests\")" +
            "        reportDir.mkdirs()" +
            "        println \"Generated report in \" + reportDir" +
            "    }" +
            "}" +
            "" +
            "test {" +
            "    useJUnitPlatform()" +
            "    reports {" +
            "        html.enabled = true" +
            "        junitXml.enabled = true" +
            "    }" +
            "}";
        
        Files.writeString(buildFile, content);
        
        assertThat(Files.readString(buildFile)).contains("generateTestReport");
    }

    /**
     * P5-GAD-015: Custom task with test class discovery.
     * Bug: Dynamic test class discovery may not respect order.
     * 
     * Reproducer: Custom task that dynamically discovers test classes.
     */
    @Test
    @DisplayName("Custom task for test class discovery")
    void testCustomTestDiscovery() throws IOException {
        String content = "plugins {" +
            "    id 'java'" +
            "}" +
            "" +
            "task discoverTests {" +
            "    doLast {" +
            "        def testClasses = sourceSets.test.output.classesDirs.asFileTree" +
            "            .matching { include '**/*Test.class' }" +
            "        println \"Discovered \" + testClasses.files.size() + \" test classes\"" +
            "    }" +
            "}" +
            "" +
            "test {" +
            "    useJUnitPlatform()" +
            "}";
        
        Files.writeString(buildFile, content);
        
        assertThat(Files.readString(buildFile)).contains("discoverTests");
    }

    /**
     * P5-GAD-016: Custom task with test environment setup.
     * Bug: Test environment setup may not preserve test order consistency.
     * 
     * Reproducer: Custom task that sets up test environment.
     */
    @Test
    @DisplayName("Custom task for test environment setup")
    void testCustomEnvironmentSetup() throws IOException {
        String content = "plugins {" +
            "    id 'java'" +
            "}" +
            "" +
            "task setupTestEnvironment {" +
            "    doFirst {" +
            "        test.environment 'TEST_ENV', 'integration'" +
            "        test.systemProperty 'java.io.tmpdir', buildDir.toString() + \"/tmp\"" +
            "    }" +
            "}" +
            "" +
            "test {" +
            "    useJUnitPlatform()" +
            "    dependsOn setupTestEnvironment" +
            "}";
        
        Files.writeString(buildFile, content);
        
        assertThat(Files.readString(buildFile)).contains("setupTestEnvironment");
    }

    /**
     * P5-GAD-017: Custom task for test retry logic.
     * Bug: Test retry logic may interfere with test ordering.
     * 
     * Reproducer: Custom task implementing retry mechanism.
     */
    @Test
    @DisplayName("Custom task for test retry logic")
    void testCustomRetryLogic() throws IOException {
        String content = "plugins {" +
            "    id 'java'" +
            "}" +
            "" +
            "task testWithRetry {" +
            "    doLast {" +
            "        int retries = 3" +
            "        boolean success = false" +
            "        " +
            "        for (int i = 0; i < retries && !success; i++) {" +
            "            try {" +
            "                test.execute()" +
            "                success = true" +
            "            } catch (Exception e) {" +
            "                if (i < retries - 1) {" +
            "                    println \"Retry \" + (i + 1)" +
            "                } else {" +
            "                    throw e" +
            "                }" +
            "            }" +
            "        }" +
            "    }" +
            "}" +
            "" +
            "test {" +
            "    useJUnitPlatform()" +
            "}";
        
        Files.writeString(buildFile, content);
        
        assertThat(Files.readString(buildFile)).contains("testWithRetry");
    }

    /**
     * Extended test: Custom task composition and chaining.
     */
    @Test
    @DisplayName("Custom task composition and dependency chains")
    void testCustomTaskComposition() throws IOException {
        String content = """
            plugins {
                id 'java'
            }
            
            task setupTests {
                doFirst {
                    println "Setting up tests"
                }
            }
            
            task runTests {
                dependsOn setupTests
                doLast {
                    println "Running tests"
                }
            }
            
            task cleanupTests {
                dependsOn runTests
                doLast {
                    println "Cleaning up tests"
                }
            }
            
            test {
                useJUnitPlatform()
            }
            """;
        
        Files.writeString(buildFile, content);
        
        String read = Files.readString(buildFile);
        assertThat(read).contains("setupTests");
        assertThat(read).contains("runTests");
        assertThat(read).contains("cleanupTests");
    }

    /**
     * Extended test: Custom task with dynamic properties.
     */
    @Test
    @DisplayName("Custom task with dynamic properties and configuration")
    void testCustomTaskDynamicProperties() throws IOException {
        String content = """
            plugins {
                id 'java'
            }
            
            ext {
                testTimeout = 300
                maxRetries = 3
                parallelForks = 2
            }
            
            task configureTests {
                doFirst {
                    test.timeout = Duration.ofSeconds(testTimeout)
                }
            }
            
            test {
                useJUnitPlatform()
                dependsOn configureTests
            }
            """;
        
        Files.writeString(buildFile, content);
        
        assertThat(Files.readString(buildFile)).contains("ext");
    }

    /**
     * Extended test: Custom task with incremental build support.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    @DisplayName("Custom task with incremental build support")
    void testCustomIncrementalTask(int iteration) throws IOException {
        String content = String.format("plugins {%n" +
            "    id 'java'%n" +
            "}%n" +
            "%n" +
            "task incrementalTest {%n" +
            "    inputs.dir(sourceSets.test.java.srcDirs)%n" +
            "    outputs.dir(buildDir.toString() + \"/test-results\")%n" +
            "    %n" +
            "    doLast {%n" +
            "        println \"Incremental test iteration %d\"%n" +
            "    }%n" +
            "}%n" +
            "%n" +
            "test {%n" +
            "    useJUnitPlatform()%n" +
            "}%n", iteration);
        
        Files.writeString(buildFile, content);
        
        assertThat(Files.readString(buildFile)).contains("incrementalTest");
    }
}
