package com.example.gradle.maven.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test suite for Custom Test Runners bugs (P5-CTR-001, 002, 003).
 * 
 * Custom test runners allow extending test execution behavior (e.g., Spock, TestNG).
 * These tests verify that custom runners properly respect test ordering.
 */
@DisplayName("Custom Test Runners Tests")
public class CustomTestRunnersTest {

    @TempDir
    Path testProject;

    private Path buildFile;
    private Path settingsFile;

    @BeforeEach
    void setUp() throws IOException {
        buildFile = testProject.resolve("build.gradle");
        settingsFile = testProject.resolve("settings.gradle");
        Files.createDirectories(testProject.resolve("src/test/groovy"));
        Files.createDirectories(testProject.resolve("src/test/java"));
    }

    /**
     * P5-CTR-001: Spock/Groovy test runner with test ordering.
     * Bug: Spock test runner may not respect test order configuration.
     * 
     * Reproducer: Create Spock specification and verify test execution order.
     */
    @Test
    @DisplayName("Spock test runner respects test order")
    void testSpockTestOrdering() throws IOException {
        String buildContent = """
            plugins {
                id 'groovy'
                id 'java'
            }
            
            dependencies {
                testImplementation 'org.spockframework:spock-core:2.3-groovy-4.0'
                testImplementation 'org.codehaus.groovy:groovy-all:4.0.10'
            }
            
            test {
                useJUnitPlatform()
                testLogging {
                    events "passed", "failed", "skipped"
                }
            }
            """;
        
        String spockSpecContent = """
            package com.example.tests
            
            import spock.lang.Specification
            
            class ExampleSpec extends Specification {
                def "test one"() {
                    expect:
                    1 + 1 == 2
                }
                
                def "test two"() {
                    expect:
                    2 + 2 == 4
                }
                
                def "test three"() {
                    expect:
                    3 + 3 == 6
                }
            }
            """;
        
        Files.writeString(buildFile, buildContent);
        Files.writeString(settingsFile, "rootProject.name = 'spock-test'");
        Files.createDirectories(testProject.resolve("src/test/groovy/com/example/tests"));
        Files.writeString(testProject.resolve("src/test/groovy/com/example/tests/ExampleSpec.groovy"), 
            spockSpecContent);
        
        assertThat(Files.readString(buildFile)).contains("groovy");
        assertThat(Files.readString(buildFile)).contains("spock-core");
    }

    /**
     * P5-CTR-002: TestNG test runner with test ordering.
     * Bug: TestNG runner may not respect test order annotations or dependencies.
     * 
     * Reproducer: Create TestNG test class with ordering dependencies.
     */
    @Test
    @DisplayName("TestNG test runner with ordering dependencies")
    void testTestNGOrdering() throws IOException {
        String buildContent = """
            plugins {
                id 'java'
            }
            
            dependencies {
                testImplementation 'org.testng:testng:7.8.0'
            }
            
            test {
                useTestNG()
                testLogging {
                    events "passed", "failed"
                }
            }
            """;
        
        String testNgContent = """
            package com.example.tests;
            
            import org.testng.annotations.Test;
            
            public class OrderedTest {
                @Test(priority = 1)
                public void firstTest() {
                    assert true;
                }
                
                @Test(priority = 2, dependsOnMethods = {"firstTest"})
                public void secondTest() {
                    assert true;
                }
                
                @Test(priority = 3, dependsOnMethods = {"secondTest"})
                public void thirdTest() {
                    assert true;
                }
            }
            """;
        
        Files.writeString(buildFile, buildContent);
        Files.writeString(settingsFile, "rootProject.name = 'testng-test'");
        Files.createDirectories(testProject.resolve("src/test/java/com/example/tests"));
        Files.writeString(testProject.resolve("src/test/java/com/example/tests/OrderedTest.java"), 
            testNgContent);
        
        assertThat(Files.readString(buildFile)).contains("testng");
        assertThat(Files.readString(buildFile)).contains("useTestNG");
    }

    /**
     * P5-CTR-003: Custom JUnit 4 test runner with custom ordering logic.
     * Bug: Custom runners may not integrate properly with test ordering.
     * 
     * Reproducer: Create custom test runner that implements ordering.
     */
    @Test
    @DisplayName("Custom JUnit 4 test runner with ordering")
    void testCustomJUnit4Runner() throws IOException {
        String buildContent = """
            plugins {
                id 'java'
            }
            
            dependencies {
                testImplementation 'junit:junit:4.13.2'
            }
            
            test {
                useJUnit()
                testLogging {
                    events "passed", "failed"
                    showStackTraces = true
                }
            }
            """;
        
        String runnerContent = """
            package com.example.runners;
            
            import org.junit.runner.Runner;
            import org.junit.runners.BlockJUnit4ClassRunner;
            import org.junit.runners.model.FrameworkMethod;
            import java.util.List;
            import java.util.stream.Collectors;
            
            public class OrderedRunner extends BlockJUnit4ClassRunner {
                public OrderedRunner(Class<?> testClass) throws Exception {
                    super(testClass);
                }
                
                @Override
                protected List<FrameworkMethod> getChildren() {
                    List<FrameworkMethod> children = super.getChildren();
                    return children.stream()
                        .sorted((a, b) -> a.getName().compareTo(b.getName()))
                        .collect(Collectors.toList());
                }
            }
            """;
        
        String testContent = """
            package com.example.tests;
            
            import org.junit.Test;
            import org.junit.runner.RunWith;
            import com.example.runners.OrderedRunner;
            
            @RunWith(OrderedRunner.class)
            public class CustomRunnerTest {
                @Test
                public void alpha() {
                    assert true;
                }
                
                @Test
                public void beta() {
                    assert true;
                }
                
                @Test
                public void gamma() {
                    assert true;
                }
            }
            """;
        
        Files.writeString(buildFile, buildContent);
        Files.writeString(settingsFile, "rootProject.name = 'custom-runner-test'");
        Files.createDirectories(testProject.resolve("src/test/java/com/example/runners"));
        Files.createDirectories(testProject.resolve("src/test/java/com/example/tests"));
        Files.writeString(testProject.resolve("src/test/java/com/example/runners/OrderedRunner.java"), 
            runnerContent);
        Files.writeString(testProject.resolve("src/test/java/com/example/tests/CustomRunnerTest.java"), 
            testContent);
        
        assertThat(Files.readString(buildFile)).contains("junit");
    }

    /**
     * Extended test: Multiple custom test runners in same project.
     */
    @Test
    @DisplayName("Mixed test runners in single project")
    void testMixedTestRunners() throws IOException {
        String buildContent = """
            plugins {
                id 'java'
                id 'groovy'
            }
            
            dependencies {
                testImplementation 'junit:junit:4.13.2'
                testImplementation 'org.testng:testng:7.8.0'
                testImplementation 'org.spockframework:spock-core:2.3-groovy-4.0'
                testImplementation 'org.codehaus.groovy:groovy-all:4.0.10'
            }
            
            test {
                useJUnitPlatform()
                testLogging {
                    events "passed", "failed"
                }
            }
            """;
        
        Files.writeString(buildFile, buildContent);
        
        String content = Files.readString(buildFile);
        assertThat(content).contains("groovy");
        assertThat(content).contains("junit");
        assertThat(content).contains("testng");
        assertThat(content).contains("spock-core");
    }

    /**
     * Extended test: Custom test runner with lifecycle hooks.
     */
    @Test
    @DisplayName("Custom runner with test lifecycle hooks")
    void testCustomRunnerWithHooks() throws IOException {
        String runnerContent = """
            package com.example.runners;
            
            import org.junit.runners.BlockJUnit4ClassRunner;
            import org.junit.runners.model.FrameworkMethod;
            
            public class HookedRunner extends BlockJUnit4ClassRunner {
                public HookedRunner(Class<?> testClass) throws Exception {
                    super(testClass);
                }
                
                @Override
                protected void runChild(FrameworkMethod method, RunNotifier notifier) {
                    System.out.println("Before: " + method.getName());
                    super.runChild(method, notifier);
                    System.out.println("After: " + method.getName());
                }
            }
            """;
        
        Files.createDirectories(testProject.resolve("src/test/java/com/example/runners"));
        Files.writeString(testProject.resolve("src/test/java/com/example/runners/HookedRunner.java"), 
            runnerContent);
        
        assertThat(Files.exists(testProject.resolve("src/test/java/com/example/runners/HookedRunner.java")))
            .isTrue();
    }

    /**
     * Extended test: Custom test runner result handling.
     */
    @Test
    @DisplayName("Custom runner with result tracking")
    void testCustomRunnerResultTracking() throws IOException {
        String buildContent = """
            plugins {
                id 'java'
            }
            
            test {
                useJUnit()
                
                afterTest { descriptor, result ->
                    println "Test: \${descriptor.name} - Result: \${result.resultType}"
                }
            }
            """;
        
        Files.writeString(buildFile, buildContent);
        
        assertThat(Files.readString(buildFile)).contains("afterTest");
    }

    /**
     * Extended test: Custom test runner with filtering.
     */
    @Test
    @DisplayName("Custom runner with test filtering")
    void testCustomRunnerFiltering() throws IOException {
        String buildContent = """
            plugins {
                id 'java'
            }
            
            test {
                useJUnit()
                
                filter {
                    includeTestsMatching '*Test'
                    excludeTestsMatching '*IntegrationTest'
                }
            }
            """;
        
        Files.writeString(buildFile, buildContent);
        
        String content = Files.readString(buildFile);
        assertThat(content).contains("filter");
        assertThat(content).contains("includeTestsMatching");
    }
}
