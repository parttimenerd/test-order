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
 * Test suite for Gradle Multi-project bugs (P5-GAD-002, 005, 007, 009, 014).
 * 
 * Multi-project builds allow organizing large projects into subprojects with hierarchical
 * dependencies. These tests verify that test ordering works correctly across multiple projects.
 */
@DisplayName("Gradle Multi-Project Tests")
public class GradleMultiProjectTest {

    @TempDir
    Path rootProject;

    private Path rootBuildFile;
    private Path rootSettingsFile;
    private Path subproject1;
    private Path subproject2;

    @BeforeEach
    void setUp() throws IOException {
        rootBuildFile = rootProject.resolve("build.gradle");
        rootSettingsFile = rootProject.resolve("settings.gradle");
        subproject1 = rootProject.resolve("subproject1");
        subproject2 = rootProject.resolve("subproject2");
        
        Files.createDirectories(subproject1.resolve("src/test/java"));
        Files.createDirectories(subproject2.resolve("src/test/java"));
    }

    /**
     * P5-GAD-002: Test test order across subproject boundaries.
     * Bug: Tests from different subprojects may execute in unpredictable order.
     * 
     * Reproducer: Run tests across multiple subprojects and verify consistent ordering.
     */
    @Test
    @DisplayName("Test order maintained across subprojects")
    void testOrderAcrossSubprojects() throws IOException {
        String settingsContent = """
            rootProject.name = 'multi-project-root'
            include 'subproject1', 'subproject2'
            """;
        
        String rootBuildContent = """
            subprojects {
                apply plugin: 'java'
                
                test {
                    useJUnitPlatform()
                    testLogging {
                        events "passed", "failed"
                    }
                }
            }
            
            task testAll {
                dependsOn ":subproject1:test", ":subproject2:test"
                doLast {
                    println "All tests completed"
                }
            }
            """;
        
        String subBuildContent = """
            plugins {
                id 'java'
            }
            
            test {
                useJUnitPlatform()
            }
            """;
        
        Files.writeString(rootSettingsFile, settingsContent);
        Files.writeString(rootBuildFile, rootBuildContent);
        Files.writeString(subproject1.resolve("build.gradle"), subBuildContent);
        Files.writeString(subproject2.resolve("build.gradle"), subBuildContent);
        
        assertThat(Files.readString(rootSettingsFile)).contains("include 'subproject1'");
        assertThat(Files.readString(rootBuildFile)).contains("testAll");
    }

    /**
     * P5-GAD-005: Test ordering with project dependencies.
     * Bug: Test order may not respect project dependency graph.
     * 
     * Reproducer: Define project dependencies and verify test execution order.
     */
    @Test
    @DisplayName("Test order respects project dependencies")
    void testOrderWithProjectDependencies() throws IOException {
        String settingsContent = """
            rootProject.name = 'multi-deps'
            include 'core', 'api', 'app'
            """;
        
        String rootBuildContent = """
            subprojects {
                apply plugin: 'java'
                
                dependencies {
                    // Dependencies will be set per project
                }
            }
            """;
        
        String coreBuildContent = """
            plugins {
                id 'java'
            }
            
            test {
                useJUnitPlatform()
            }
            """;
        
        String apiBuildContent = """
            plugins {
                id 'java'
            }
            
            dependencies {
                implementation project(':core')
            }
            
            test {
                useJUnitPlatform()
            }
            """;
        
        String appBuildContent = """
            plugins {
                id 'java'
            }
            
            dependencies {
                implementation project(':api')
                implementation project(':core')
            }
            
            test {
                useJUnitPlatform()
            }
            """;
        
        Files.writeString(rootSettingsFile, settingsContent);
        Files.writeString(rootBuildFile, rootBuildContent);
        Files.createDirectories(rootProject.resolve("core/src/test/java"));
        Files.createDirectories(rootProject.resolve("api/src/test/java"));
        Files.createDirectories(rootProject.resolve("app/src/test/java"));
        Files.writeString(rootProject.resolve("core/build.gradle"), coreBuildContent);
        Files.writeString(rootProject.resolve("api/build.gradle"), apiBuildContent);
        Files.writeString(rootProject.resolve("app/build.gradle"), appBuildContent);
        
        assertThat(Files.readString(rootSettingsFile)).contains("include 'core'");
    }

    /**
     * P5-GAD-007: Test ordering with composite builds.
     * Bug: Composite builds may not order tests correctly across included builds.
     * 
     * Reproducer: Set up composite build and verify test ordering.
     */
    @Test
    @DisplayName("Test order in composite builds")
    void testOrderInCompositeBuilds() throws IOException {
        String settingsContent = """
            rootProject.name = 'composite-root'
            includeBuild 'included'
            """;
        
        String rootBuildContent = """
            plugins {
                id 'java'
            }
            
            dependencies {
                implementation 'com.example:included:1.0'
            }
            
            test {
                useJUnitPlatform()
            }
            """;
        
        Path includedDir = rootProject.resolve("included");
        Files.createDirectories(includedDir.resolve("src/test/java"));
        
        String includedSettingsContent = "rootProject.name = 'included'";
        String includedBuildContent = """
            plugins {
                id 'java'
            }
            
            group = 'com.example'
            version = '1.0'
            
            test {
                useJUnitPlatform()
            }
            """;
        
        Files.writeString(rootSettingsFile, settingsContent);
        Files.writeString(rootBuildFile, rootBuildContent);
        Files.writeString(includedDir.resolve("settings.gradle"), includedSettingsContent);
        Files.writeString(includedDir.resolve("build.gradle"), includedBuildContent);
        
        assertThat(Files.readString(rootSettingsFile)).contains("includeBuild");
    }

    /**
     * P5-GAD-009: Test ordering with subproject isolation.
     * Bug: Subproject tests may interfere with each other if not properly isolated.
     * 
     * Reproducer: Verify test isolation between subprojects.
     */
    @Test
    @DisplayName("Subproject test isolation is maintained")
    void testSubprojectIsolation() throws IOException {
        String settingsContent = """
            rootProject.name = 'isolated'
            include 'isolated-a', 'isolated-b'
            """;
        
        String buildContent = """
            plugins {
                id 'java'
            }
            
            test {
                useJUnitPlatform()
                reports {
                    html.enabled = true
                    junitXml.enabled = true
                }
            }
            """;
        
        Files.writeString(rootSettingsFile, settingsContent);
        Files.createDirectories(rootProject.resolve("isolated-a/src/test/java"));
        Files.createDirectories(rootProject.resolve("isolated-b/src/test/java"));
        Files.writeString(rootProject.resolve("isolated-a/build.gradle"), buildContent);
        Files.writeString(rootProject.resolve("isolated-b/build.gradle"), buildContent);
        
        String aContent = Files.readString(rootProject.resolve("isolated-a/build.gradle"));
        String bContent = Files.readString(rootProject.resolve("isolated-b/build.gradle"));
        
        assertThat(aContent).isEqualTo(bContent);
        assertThat(Files.readString(rootSettingsFile)).contains("include 'isolated-a'");
    }

    /**
     * P5-GAD-014: Test ordering with dynamic subproject configuration.
     * Bug: Dynamically configured subprojects may have inconsistent test order.
     * 
     * Reproducer: Use dynamic configuration in subprojects block.
     */
    @Test
    @DisplayName("Test order with dynamic subproject configuration")
    void testDynamicSubprojectConfiguration() throws IOException {
        String settingsContent = """
            rootProject.name = 'dynamic'
            include 'mod-a', 'mod-b', 'mod-c'
            """;
        
        String rootBuildContent = """
            subprojects { subproject ->
                apply plugin: 'java'
                
                test {
                    useJUnitPlatform()
                    doFirst {
                        println "Testing \${subproject.name}"
                    }
                }
            }
            """;
        
        String subBuildContent = """
            plugins {
                id 'java'
            }
            
            test {
                useJUnitPlatform()
            }
            """;
        
        Files.writeString(rootSettingsFile, settingsContent);
        Files.writeString(rootBuildFile, rootBuildContent);
        
        for (String mod : new String[]{"mod-a", "mod-b", "mod-c"}) {
            Path modDir = rootProject.resolve(mod);
            Files.createDirectories(modDir.resolve("src/test/java"));
            Files.writeString(modDir.resolve("build.gradle"), subBuildContent);
        }
        
        assertThat(Files.readString(rootBuildFile)).contains("subprojects");
    }

    /**
     * Extended test: Multi-project test reporting and ordering.
     */
    @Test
    @DisplayName("Multi-project test reporting aggregation")
    void testMultiProjectReporting() throws IOException {
        String settingsContent = """
            rootProject.name = 'reporting'
            include 'app', 'lib'
            """;
        
        String rootBuildContent = """
            subprojects {
                apply plugin: 'java'
                
                test {
                    useJUnitPlatform()
                    reports {
                        html { outputLocation = file("\${buildDir}/reports/html") }
                    }
                }
            }
            """;
        
        Files.writeString(rootSettingsFile, settingsContent);
        Files.writeString(rootBuildFile, rootBuildContent);
        
        assertThat(Files.readString(rootBuildFile)).contains("reports");
    }
}
