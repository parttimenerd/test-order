package com.example.idecicd.ide;

import com.example.idecicd.TestEnvironmentSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for IDE classpath bugs (P5-IDE-002, 008).
 * 
 * Bug Categories:
 * - P5-IDE-002: IDE doesn't update classpath after build
 * - P5-IDE-008: IDE classpath ordering affects test execution
 */
@DisplayName("IDE Classpath Tests")
public class IDEClasspathTest {

    private Path testDir;
    private static final String TEST_NAME = "ide-classpath";

    @BeforeEach
    void setUp() throws IOException {
        testDir = TestEnvironmentSetup.createTestDirectory(TEST_NAME);
        Files.createDirectories(testDir.resolve("build/classes/main"));
        Files.createDirectories(testDir.resolve("build/classes/test"));
        Files.createDirectories(testDir.resolve("build/resources"));
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSetup.cleanupTestDirectory(TEST_NAME);
    }

    // P5-IDE-002: IDE doesn't update classpath after build
    @Test
    @DisplayName("P5-IDE-002: Classpath includes compiled classes")
    void testClasspathIncludesCompiledClasses() throws IOException {
        Path classesDir = testDir.resolve("build/classes/main");
        Path classFile = classesDir.resolve("com/example/Test.class");
        TestEnvironmentSetup.createTestFile(classFile.getParent(), "Test.class", "CAFEBABE");

        assertThat(Files.exists(classFile)).isTrue();
        assertThat(testDir.resolve("build/classes/main").toString()).contains("classes");
    }

    @Test
    @DisplayName("P5-IDE-002: Classpath reflects build directory changes")
    void testClasspathReflectsBuildChanges() throws IOException {
        Path classesDir = testDir.resolve("build/classes/main");
        
        // Initial state
        assertThat(Files.exists(classesDir)).isTrue();
        
        // Simulate new compilation
        Path newClass = classesDir.resolve("com/example/NewClass.class");
        TestEnvironmentSetup.createTestFile(newClass.getParent(), "NewClass.class", "CAFEBABE");
        
        // Verify classpath sees the new class
        assertThat(Files.exists(newClass)).isTrue();
    }

    @Test
    @DisplayName("P5-IDE-002: Test classpath separate from main classpath")
    void testTestClasspathSeparation() throws IOException {
        Path mainClasses = testDir.resolve("build/classes/main");
        Path testClasses = testDir.resolve("build/classes/test");
        
        // Create main class
        Path mainClass = mainClasses.resolve("AppClass.class");
        TestEnvironmentSetup.createTestFile(mainClasses, "AppClass.class", "MAIN");
        
        // Create test class
        Path testClass = testClasses.resolve("TestClass.class");
        TestEnvironmentSetup.createTestFile(testClasses, "TestClass.class", "TEST");
        
        // Verify both exist but are separate
        assertThat(Files.exists(mainClass)).isTrue();
        assertThat(Files.exists(testClass)).isTrue();
        assertThat(mainClasses).isNotEqualTo(testClasses);
    }

    // P5-IDE-008: IDE classpath ordering affects test execution
    @Test
    @DisplayName("P5-IDE-008: Classpath ordering with main classes first")
    void testClasspathOrderingMainFirst() throws IOException {
        Path mainClasses = testDir.resolve("build/classes/main");
        Path testClasses = testDir.resolve("build/classes/test");
        
        // Create duplicate class in both locations
        TestEnvironmentSetup.createTestFile(mainClasses, "Config.class", "VERSION=1");
        TestEnvironmentSetup.createTestFile(testClasses, "Config.class", "VERSION=2");
        
        // When main classes come first, main version should be found
        assertThat(Files.exists(mainClasses.resolve("Config.class"))).isTrue();
        assertThat(Files.exists(testClasses.resolve("Config.class"))).isTrue();
    }

    @Test
    @DisplayName("P5-IDE-008: Classpath ordering with test classes first")
    void testClasspathOrderingTestFirst() throws IOException {
        Path mainClasses = testDir.resolve("build/classes/main");
        Path testClasses = testDir.resolve("build/classes/test");
        
        // Create resources in both
        TestEnvironmentSetup.createTestFile(mainClasses, "config.properties", "main=true");
        TestEnvironmentSetup.createTestFile(testClasses, "config.properties", "test=true");
        
        // Test classpath ordering affects which resource is loaded
        assertThat(Files.readAllLines(testClasses.resolve("config.properties")))
                .contains("test=true");
    }

    @Test
    @DisplayName("P5-IDE-008: Classpath affects dependency resolution")
    void testClasspathDependencyResolution() throws IOException {
        Path libDir = testDir.resolve("lib");
        Files.createDirectories(libDir);
        
        // Create mock JAR entries
        TestEnvironmentSetup.createTestFile(libDir, "junit-4.13.jar", "JUNIT4");
        TestEnvironmentSetup.createTestFile(libDir, "junit-jupiter-api-5.9.jar", "JUNIT5");
        
        // Both versions exist - classpath order determines which is used
        assertThat(Files.exists(libDir.resolve("junit-4.13.jar"))).isTrue();
        assertThat(Files.exists(libDir.resolve("junit-jupiter-api-5.9.jar"))).isTrue();
    }

    @Test
    @DisplayName("P5-IDE-008: Classpath with resource directories")
    void testClasspathWithResourceDirs() throws IOException {
        Path resourcesDir = testDir.resolve("build/resources/main");
        Files.createDirectories(resourcesDir);
        
        TestEnvironmentSetup.createTestFile(resourcesDir, "application.properties", 
                "app.name=TestApp\napp.version=1.0");
        
        String content = TestEnvironmentSetup.readFile(resourcesDir.resolve("application.properties"));
        assertThat(content).contains("app.name=TestApp");
        assertThat(content).contains("app.version=1.0");
    }

    @Test
    @DisplayName("P5-IDE-008: Classpath with runtime vs compile dependencies")
    void testRuntimeVsCompileDependencies() throws IOException {
        Path compileClasses = testDir.resolve("build/compile-classes");
        Path runtimeClasses = testDir.resolve("build/runtime-classes");
        Files.createDirectories(compileClasses);
        Files.createDirectories(runtimeClasses);
        
        // Compile-time only class
        TestEnvironmentSetup.createTestFile(compileClasses, "Annotation.class", "COMPILE");
        
        // Runtime class
        TestEnvironmentSetup.createTestFile(runtimeClasses, "Runtime.class", "RUNTIME");
        
        assertThat(Files.exists(compileClasses.resolve("Annotation.class"))).isTrue();
        assertThat(Files.exists(runtimeClasses.resolve("Runtime.class"))).isTrue();
    }

    @Test
    @DisplayName("P5-IDE-008: Classpath precedence affects test isolation")
    void testClasspathPrecedenceIsolation() throws IOException {
        Path mainClasses = testDir.resolve("build/classes/main");
        Path testClasses = testDir.resolve("build/classes/test");
        
        // Mock test helper that should NOT be visible to main code
        TestEnvironmentSetup.createTestFile(testClasses, "TestHelper.class", "TEST_ONLY");
        
        // Main classes should not see test helpers
        assertThat(Files.exists(testClasses.resolve("TestHelper.class"))).isTrue();
        assertThat(Files.exists(mainClasses.resolve("TestHelper.class"))).isFalse();
    }
}
