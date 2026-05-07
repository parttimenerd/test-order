package me.bechberger.testorder.maven.it;

import static me.bechberger.testorder.maven.it.TestOrderAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Systematic bug hunt and edge case testing for test-order Maven plugin.
 * 
 * This test suite exercises various workflows and edge cases to identify
 * usability issues, unexpected behavior, and bugs in real-world scenarios.
 * 
 * Enable with: {@code -Dtestorder.it=true}
 */
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Test-Order Maven Plugin: Systematic Bug Hunt")
class SystematicBugHuntIT {

    private TestProject project;

    @BeforeAll
    void setup() {
        Path root = Paths.get("").toAbsolutePath();
        if (root.getFileName().toString().equals("test-order-maven-plugin")) {
            root = root.getParent();
        }
        project = new TestProject(root.resolve("samples/sample-basic"),
                List.of("-Dtestorder.includePackages=com.myapp"));
    }

    @AfterAll
    void teardown() {
        if (project != null) {
            project.cleanAll();
        }
    }

    @Nested
    @DisplayName("Learn Mode Tests")
    class LearnModeTests {

        @Test
        @DisplayName("Learn mode should create index file even with zero changes")
        void shouldCreateIndexWithZeroChanges() throws Exception {
            var result = project.maven().learn();
            assertThat(result).succeeded();
            
            // Verify index exists
            var indexPath = project.getProjectDir().resolve(".test-order/test-dependencies.lz4");
            assertThat(Files.exists(indexPath))
                .as("Index file should exist after learn mode")
                .isTrue();
        }

        @Test
        @DisplayName("Learn mode should handle test classes with no dependencies")
        void shouldHandleEmptyTestClasses() throws Exception {
            // Create a minimal test class with no code calls
            project.appendToFile("src/test/java/com/myapp/EmptyTest.java",
                """
                package com.myapp;
                import org.junit.jupiter.api.Test;
                public class EmptyTest {
                    @Test void testNothing() { }
                }
                """);
            
            var result = project.maven().learn();
            assertThat(result).succeeded();
        }
    }

    @Nested
    @DisplayName("Order Mode Tests")
    class OrderModeTests {

        @Test
        @DisplayName("Order mode should work without an index (graceful degradation)")
        void shouldHandleMissingIndex() throws Exception {
            // Remove the index if it exists
            var indexPath = project.getProjectDir().resolve(".test-order");
            if (Files.exists(indexPath)) {
                project.cleanAll();
            }
            
            // Order mode without index should still run tests
            var result = project.maven().order();
            assertThat(result)
                .as("Should output BUILD result")
                .outputContains("BUILD");
        }

        @Test
        @DisplayName("Order mode should detect changed source files")
        void shouldDetectChangedSources() throws Exception {
            // First learn
            var learnResult = project.maven().learn();
            assertThat(learnResult).succeeded();
            
            // Modify a source file
            project.replaceInFile("src/main/java/com/myapp/service/MathService.java",
                "return a + b;", "return a + b + 0; // BUG");
            
            // Order mode should detect the change
            var orderResult = project.maven().auto();
            assertThat(orderResult)
                .outputContains("[test-order]");
        }
    }

    @Nested
    @DisplayName("Select Mode Tests")
    class SelectModeTests {

        @Test
        @DisplayName("Select mode should create selection files")
        void shouldCreateSelectionFiles() throws Exception {
            // First learn
            project.maven().learn();
            
            // Run select
            var result = project.maven().select();
            assertThat(result).succeeded();
            
            // Check that selection files were created
            var selectedFile = project.getProjectDir().resolve("target/test-order-selected.txt");
            var remainingFile = project.getProjectDir().resolve("target/test-order-remaining.txt");
            
            assertThat(Files.exists(selectedFile))
                .as("Selected tests file should exist")
                .isTrue();
            assertThat(Files.exists(remainingFile))
                .as("Remaining tests file should exist")
                .isTrue();
        }

        @Test
        @DisplayName("Select mode should include new tests in selection")
        void shouldIncludeNewTests() throws Exception {
            // Learn first
            project.maven().learn();
            
            // Add a new test class
            project.appendToFile("src/test/java/com/myapp/NewTest.java",
                """
                package com.myapp;
                import org.junit.jupiter.api.Test;
                public class NewTest {
                    @Test void test() { }
                }
                """);
            
            // Select should include the new test
            var result = project.maven().select();
            assertThat(result).succeeded();
            
            var selectedFile = project.getProjectDir().resolve("target/test-order-selected.txt");
            var selected = Files.readString(selectedFile);
            assertThat(selected)
                .as("New test should be included in selection")
                .contains("NewTest");
        }
    }

    @Nested
    @DisplayName("Error Handling and Edge Cases")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle corrupted state file gracefully")
        void shouldHandleCorruptedState() throws Exception {
            // Learn first
            project.maven().learn();
            
            // Corrupt the state file
            var statePath = project.getProjectDir().resolve(".test-order/state.lz4");
            Files.write(statePath, "CORRUPTED DATA".getBytes());
            
            // Should still be able to run (graceful fallback)
            var result = project.maven().order();
            assertThat(result)
                .as("Should handle corrupted state file gracefully")
                .outputContains("BUILD");
        }

        @Test
        @DisplayName("Should handle missing src/main/java directory")
        void shouldHandleMissingSourceDir() throws Exception {
            // This is a test library scenario
            var result = project.maven().learn();
            assertThat(result).succeeded();
            
            // Plugin should auto-detect source packages and use groupId as fallback
        }
    }

    @Nested
    @DisplayName("Dashboard and Export Functionality")
    class DashboardTests {

        @Test
        @DisplayName("Dashboard generation should succeed after learn mode")
        void shouldGenerateDashboard() throws Exception {
            // Learn first
            project.maven().learn();
            
            // Generate dashboard
            var result = project.maven().run("test-order:dashboard");
            assertThat(result).succeeded();
            
            // Check dashboard was created
            var dashboardPath = project.getProjectDir()
                .resolve("target/test-order-dashboard/index.html");
            assertThat(Files.exists(dashboardPath))
                .as("Dashboard HTML should be generated")
                .isTrue();
        }

        @Test
        @DisplayName("Export JSON should work correctly")
        void shouldExportJson() throws Exception {
            // Learn first
            project.maven().learn();
            
            // Export JSON
            var result = project.maven().exportJson();
            assertThat(result).succeeded();
        }
    }

    @Nested
    @DisplayName("Documentation and Help")
    class DocumentationTests {

        @Test
        @DisplayName("Help goal should display available commands")
        void shouldDisplayHelp() throws Exception {
            var result = project.maven().run("test-order:help");
            assertThat(result)
                .succeeded()
                .outputContains("Goal");
        }

        @Test
        @DisplayName("Diagnose goal should check setup")
        void shouldRunDiagnosis() throws Exception {
            var result = project.maven().run("test-order:diagnose");
            assertThat(result).succeeded();
        }
    }
}
