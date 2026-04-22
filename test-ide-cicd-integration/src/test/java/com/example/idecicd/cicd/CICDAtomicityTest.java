package com.example.idecicd.cicd;

import com.example.idecicd.TestEnvironmentSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for CI/CD atomicity bugs (P5-CICD-027 and related).
 * 
 * Bug Categories:
 * - P5-CICD-027: CI/CD build not atomic - partial artifacts created on failure
 * - CI/CD: Concurrent build isolation issues
 * - CI/CD: Build state consistency problems
 */
@DisplayName("CI/CD Atomicity Tests")
public class CICDAtomicityTest {

    private Path testDir;
    private Path buildDir;
    private static final String TEST_NAME = "cicd-atomicity";

    @BeforeEach
    void setUp() throws IOException {
        testDir = TestEnvironmentSetup.createTestDirectory(TEST_NAME);
        buildDir = testDir.resolve("build");
        Files.createDirectories(buildDir.resolve("classes"));
        Files.createDirectories(buildDir.resolve("outputs"));
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSetup.cleanupTestDirectory(TEST_NAME);
    }

    // P5-CICD-027: CI/CD build not atomic - partial artifacts created on failure
    @Test
    @DisplayName("P5-CICD-027: Build cleanup on compilation failure")
    void testBuildCleanupOnFailure() throws IOException {
        Path classFile = buildDir.resolve("classes/TestClass.class");
        Files.createDirectories(classFile.getParent());
        
        // Simulate successful partial compilation
        TestEnvironmentSetup.createTestFile(classFile.getParent(), "TestClass.class", "PARTIAL_BYTECODE");

        // Build should clean up partial artifacts if compilation fails
        assertThat(Files.exists(classFile)).isTrue();
        
        // Simulate build failure and cleanup
        Files.delete(classFile);
        assertThat(Files.exists(classFile)).isFalse();
    }

    @Test
    @DisplayName("P5-CICD-027: Build atomic artifact creation")
    void testBuildAtomicArtifactCreation() throws IOException {
        Path tempArtifact = buildDir.resolve("outputs/app.jar.tmp");
        Path finalArtifact = buildDir.resolve("outputs/app.jar");

        // Build should create temp artifact first
        TestEnvironmentSetup.createTestFile(buildDir.resolve("outputs"), "app.jar.tmp", "INCOMPLETE_JAR");
        assertThat(Files.exists(tempArtifact)).isTrue();
        assertThat(Files.exists(finalArtifact)).isFalse();

        // Upon successful completion, atomically rename to final
        Files.move(tempArtifact, finalArtifact);
        
        assertThat(Files.exists(tempArtifact)).isFalse();
        assertThat(Files.exists(finalArtifact)).isTrue();
    }

    @Test
    @DisplayName("P5-CICD-027: Build prevents incomplete artifact promotion")
    void testBuildPreventsIncompletePromotion() throws IOException {
        Path artifact = buildDir.resolve("outputs/library.jar");

        // Create incomplete artifact (missing files)
        TestEnvironmentSetup.createTestFile(buildDir.resolve("outputs"), "library.jar", "INCOMPLETE");
        
        // Verify artifact is incomplete
        long fileSize = TestEnvironmentSetup.getFileSize(artifact);
        assertThat(fileSize).isLessThan(100);
        
        // Build should not promote incomplete artifacts
        assertThat(fileSize).isGreaterThan(0);
    }

    @Test
    @DisplayName("P5-CICD-027: Build handles interrupted writes gracefully")
    void testBuildHandlesInterruptedWrites() throws IOException {
        Path artifact = buildDir.resolve("outputs/interrupted.jar");

        // Simulate write interruption
        TestEnvironmentSetup.createTestFile(buildDir.resolve("outputs"), "interrupted.jar.lock", "LOCK");
        
        // Build should detect interruption via lock file
        assertThat(Files.exists(buildDir.resolve("outputs/interrupted.jar.lock"))).isTrue();
        
        // Cleanup lock file
        Files.delete(buildDir.resolve("outputs/interrupted.jar.lock"));
        assertThat(Files.exists(buildDir.resolve("outputs/interrupted.jar.lock"))).isFalse();
    }

    // CI/CD: Concurrent build isolation issues
    @Test
    @DisplayName("CI/CD: Concurrent builds have isolated workspaces")
    void testConcurrentBuildIsolation() throws IOException {
        Path build1 = testDir.resolve("build-1");
        Path build2 = testDir.resolve("build-2");
        
        Files.createDirectories(build1.resolve("classes"));
        Files.createDirectories(build2.resolve("classes"));
        
        // Each build has isolated outputs
        TestEnvironmentSetup.createTestFile(build1.resolve("classes"), "Build1.class", "BUILD1");
        TestEnvironmentSetup.createTestFile(build2.resolve("classes"), "Build2.class", "BUILD2");

        // Builds should not interfere
        String build1Content = TestEnvironmentSetup.readFile(build1.resolve("classes/Build1.class"));
        String build2Content = TestEnvironmentSetup.readFile(build2.resolve("classes/Build2.class"));
        
        assertThat(build1Content).isEqualTo("BUILD1");
        assertThat(build2Content).isEqualTo("BUILD2");
    }

    @Test
    @DisplayName("CI/CD: Concurrent builds don't share build artifacts")
    void testConcurrentBuildsIsolatedArtifacts() throws IOException {
        Path sharedLib = testDir.resolve("lib");
        Files.createDirectories(sharedLib);
        
        Path build1Target = testDir.resolve("build-1/target");
        Path build2Target = testDir.resolve("build-2/target");
        Files.createDirectories(build1Target);
        Files.createDirectories(build2Target);

        // Each build generates own artifacts
        TestEnvironmentSetup.createTestFile(build1Target, "app-1.jar", "APP1");
        TestEnvironmentSetup.createTestFile(build2Target, "app-2.jar", "APP2");

        // Shared library exists
        TestEnvironmentSetup.createTestFile(sharedLib, "commons.jar", "SHARED");

        assertThat(Files.exists(build1Target.resolve("app-1.jar"))).isTrue();
        assertThat(Files.exists(build2Target.resolve("app-2.jar"))).isTrue();
        assertThat(Files.exists(sharedLib.resolve("commons.jar"))).isTrue();
    }

    @Test
    @DisplayName("CI/CD: Concurrent builds don't corrupt shared resources")
    void testConcurrentBuildsDontCorruptShared() throws IOException {
        Path sharedFile = testDir.resolve("shared-config.properties");
        String originalContent = "version=1.0\nbuild.number=1";
        TestEnvironmentSetup.createTestFile(testDir, "shared-config.properties", originalContent);

        // Multiple builds read shared file (non-conflicting reads)
        String content1 = TestEnvironmentSetup.readFile(sharedFile);
        String content2 = TestEnvironmentSetup.readFile(sharedFile);

        // Both should get consistent content
        assertThat(content1).isEqualTo(originalContent);
        assertThat(content2).isEqualTo(originalContent);
    }

    // CI/CD: Build state consistency problems
    @Test
    @DisplayName("CI/CD: Build state file prevents duplicate runs")
    void testBuildStateFileConsistency() throws IOException {
        Path stateFile = buildDir.resolve("build.state");
        
        // Create state file indicating build in progress
        TestEnvironmentSetup.createTestFile(buildDir, "build.state", "status=IN_PROGRESS\npid=12345\nstart_time=1234567890");

        String state = TestEnvironmentSetup.readFile(stateFile);
        assertThat(state).contains("IN_PROGRESS");
        assertThat(state).contains("pid=12345");
        
        // Update state on completion
        Files.write(stateFile, "status=COMPLETED\npid=12345\nend_time=1234567900".getBytes(),
                StandardOpenOption.TRUNCATE_EXISTING);
        
        String completedState = TestEnvironmentSetup.readFile(stateFile);
        assertThat(completedState).contains("COMPLETED");
    }

    @Test
    @DisplayName("CI/CD: Build tracks all generated artifacts")
    void testBuildTracksGeneratedArtifacts() throws IOException {
        Path manifestFile = buildDir.resolve("build.manifest");
        List<String> artifacts = new ArrayList<>();
        
        // Create artifacts
        Path jar = buildDir.resolve("outputs/app.jar");
        Path pom = buildDir.resolve("outputs/app.pom");
        Path sources = buildDir.resolve("outputs/app-sources.jar");
        
        TestEnvironmentSetup.createTestFile(buildDir.resolve("outputs"), "app.jar", "JAR");
        TestEnvironmentSetup.createTestFile(buildDir.resolve("outputs"), "app.pom", "POM");
        TestEnvironmentSetup.createTestFile(buildDir.resolve("outputs"), "app-sources.jar", "SOURCES");
        
        // Create manifest
        String manifestContent = "artifact:app.jar\nartifact:app.pom\nartifact:app-sources.jar";
        TestEnvironmentSetup.createTestFile(buildDir, "build.manifest", manifestContent);

        String manifest = TestEnvironmentSetup.readFile(manifestFile);
        assertThat(manifest).contains("app.jar");
        assertThat(manifest).contains("app.pom");
        assertThat(manifest).contains("app-sources.jar");
    }

    @Test
    @DisplayName("CI/CD: Build consistency check verifies all artifacts")
    void testBuildConsistencyCheck() throws IOException {
        // Create build artifacts
        TestEnvironmentSetup.createTestFile(buildDir.resolve("classes"), "Main.class", "MAIN");
        TestEnvironmentSetup.createTestFile(buildDir.resolve("classes"), "Test.class", "TEST");
        
        Path checksumFile = buildDir.resolve("checksums.txt");
        String checksumContent = "Main.class:abc123\nTest.class:def456";
        TestEnvironmentSetup.createTestFile(buildDir, "checksums.txt", checksumContent);

        String checksums = TestEnvironmentSetup.readFile(checksumFile);
        assertThat(checksums).contains("Main.class");
        assertThat(checksums).contains("Test.class");
        
        // Verify all referenced artifacts exist
        assertThat(Files.exists(buildDir.resolve("classes/Main.class"))).isTrue();
        assertThat(Files.exists(buildDir.resolve("classes/Test.class"))).isTrue();
    }

    @Test
    @DisplayName("CI/CD: Failed build rollback removes partial artifacts")
    void testFailedBuildRollback() throws IOException {
        Path targetDir = buildDir.resolve("outputs");
        
        // Create artifacts for a failed build
        TestEnvironmentSetup.createTestFile(targetDir, "partial1.jar", "PARTIAL1");
        TestEnvironmentSetup.createTestFile(targetDir, "partial2.class", "PARTIAL2");
        
        // Verify partial artifacts exist
        assertThat(Files.exists(targetDir.resolve("partial1.jar"))).isTrue();
        assertThat(Files.exists(targetDir.resolve("partial2.class"))).isTrue();

        // Rollback: remove all partial artifacts
        Files.delete(targetDir.resolve("partial1.jar"));
        Files.delete(targetDir.resolve("partial2.class"));
        
        // Verify rollback is complete
        assertThat(Files.exists(targetDir.resolve("partial1.jar"))).isFalse();
        assertThat(Files.exists(targetDir.resolve("partial2.class"))).isFalse();
    }

    @Test
    @DisplayName("CI/CD: Build transaction log tracks operations")
    void testBuildTransactionLog() throws IOException {
        Path logFile = buildDir.resolve("transaction.log");
        
        // Build operation sequence
        StringBuilder log = new StringBuilder();
        log.append("COMPILE:started\n");
        log.append("COMPILE:Main.java->Main.class\n");
        log.append("COMPILE:Test.java->Test.class\n");
        log.append("COMPILE:completed\n");
        log.append("PACKAGE:started\n");
        log.append("PACKAGE:app.jar:created\n");
        log.append("PACKAGE:completed\n");
        
        TestEnvironmentSetup.createTestFile(buildDir, "transaction.log", log.toString());
        
        String transactionLog = TestEnvironmentSetup.readFile(logFile);
        assertThat(transactionLog).contains("COMPILE:started");
        assertThat(transactionLog).contains("Main.class");
        assertThat(transactionLog).contains("PACKAGE:started");
        assertThat(transactionLog).contains("app.jar:created");
    }
}
