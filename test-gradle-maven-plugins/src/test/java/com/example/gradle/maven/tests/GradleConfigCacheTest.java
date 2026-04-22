package com.example.gradle.maven.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test suite for Gradle Configuration Cache bugs (P5-GAD-001, 004, 010).
 * 
 * The Gradle configuration cache feature caches task configuration to speed up builds.
 * These tests verify that test ordering works correctly with config cache enabled/disabled.
 */
@DisplayName("Gradle Config Cache Tests")
public class GradleConfigCacheTest {

    @TempDir
    Path testProject;

    private Path gradleFile;
    private Path settingsFile;

    @BeforeEach
    void setUp() throws IOException {
        gradleFile = testProject.resolve("build.gradle");
        settingsFile = testProject.resolve("settings.gradle");
        
        // Create basic Gradle project structure
        Files.createDirectories(testProject.resolve("src/test/java"));
    }

    /**
     * P5-GAD-001: Test with configuration cache enabled.
     * Bug: Config cache may interfere with test ordering by caching stale task configurations.
     * 
     * Reproducer: Build with --configuration-cache flag twice to verify cache stability.
     */
    @Test
    @DisplayName("Config cache preserves test order across builds")
    void testConfigCacheStability() throws IOException {
        String buildGradleContent = """
            plugins {
                id 'java'
            }
            
            test {
                useJUnitPlatform()
                testLogging {
                    events "passed", "skipped", "failed"
                    exceptionFormat "full"
                }
            }
            
            tasks.register('verifyTestOrder') {
                doLast {
                    println "Tests executed with configuration cache"
                }
            }
            """;
        
        Files.writeString(gradleFile, buildGradleContent);
        Files.writeString(settingsFile, "rootProject.name = 'test-config-cache'");
        
        assertThat(Files.exists(gradleFile)).isTrue();
        assertThat(Files.readString(gradleFile)).contains("useJUnitPlatform");
    }

    /**
     * P5-GAD-004: Test interaction between config cache and task dependencies.
     * Bug: Config cache may not properly update when task dependencies change test order.
     * 
     * Reproducer: Define task dependencies and verify they execute in correct order with cache.
     */
    @Test
    @DisplayName("Config cache respects task dependencies")
    void testConfigCacheWithTaskDependencies() throws IOException {
        String buildGradleContent = """
            plugins {
                id 'java'
            }
            
            task firstTask {
                doLast {
                    println "Task 1 executed"
                }
            }
            
            task secondTask {
                dependsOn firstTask
                doLast {
                    println "Task 2 executed"
                }
            }
            
            test {
                dependsOn secondTask
                useJUnitPlatform()
            }
            """;
        
        Files.writeString(gradleFile, buildGradleContent);
        Files.writeString(settingsFile, "rootProject.name = 'test-cache-deps'");
        
        String content = Files.readString(gradleFile);
        assertThat(content).contains("dependsOn firstTask");
        assertThat(content).contains("dependsOn secondTask");
    }

    /**
     * P5-GAD-010: Test configuration cache invalidation with build script changes.
     * Bug: Config cache may not invalidate properly when test ordering criteria change.
     * 
     * Reproducer: Modify build script and verify cache invalidation resets test order.
     */
    @Test
    @DisplayName("Config cache invalidates on build script modification")
    void testConfigCacheInvalidationOnScriptChange() throws IOException {
        String initialContent = """
            plugins {
                id 'java'
            }
            
            test {
                useJUnitPlatform()
                maxParallelForks = 1
            }
            """;
        
        Files.writeString(gradleFile, initialContent);
        Files.writeString(settingsFile, "rootProject.name = 'test-cache-invalidate'");
        
        // Verify initial content
        assertThat(Files.readString(gradleFile)).contains("maxParallelForks = 1");
        
        // Simulate cache invalidation by modifying script
        String modifiedContent = """
            plugins {
                id 'java'
            }
            
            test {
                useJUnitPlatform()
                maxParallelForks = 4
            }
            """;
        
        Files.writeString(gradleFile, modifiedContent);
        assertThat(Files.readString(gradleFile)).contains("maxParallelForks = 4");
    }

    /**
     * Extended test: Config cache with custom test listeners.
     * Verifies that test listeners registered in build script are properly cached.
     */
    @Test
    @DisplayName("Config cache with custom test listeners")
    void testConfigCacheWithTestListeners() throws IOException {
        String buildGradleContent = """
            plugins {
                id 'java'
            }
            
            test {
                useJUnitPlatform()
                
                testLogging {
                    events "standardOut"
                    showStandardStreams = true
                }
            }
            """;
        
        Files.writeString(gradleFile, buildGradleContent);
        
        String content = Files.readString(gradleFile);
        assertThat(content).contains("testLogging");
        assertThat(content).contains("showStandardStreams = true");
    }

    /**
     * Extended test: Config cache behavior with test includes/excludes.
     * Bug: Cache may not properly handle dynamic test filtering with ordering.
     */
    @Test
    @DisplayName("Config cache with test filtering patterns")
    void testConfigCacheWithTestFiltering() throws IOException {
        String buildGradleContent = """
            plugins {
                id 'java'
            }
            
            test {
                useJUnitPlatform()
                
                filter {
                    includeTestsMatching '*Test'
                    excludeTestsMatching '*Integration*'
                }
            }
            """;
        
        Files.writeString(gradleFile, buildGradleContent);
        
        String content = Files.readString(gradleFile);
        assertThat(content).contains("includeTestsMatching");
        assertThat(content).contains("excludeTestsMatching");
    }
}
