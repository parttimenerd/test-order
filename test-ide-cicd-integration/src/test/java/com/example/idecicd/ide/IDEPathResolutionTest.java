package com.example.idecicd.ide;

import com.example.idecicd.TestEnvironmentSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for IDE path resolution bugs (P5-IDE-001, 004, 007).
 * 
 * Bug Categories:
 * - P5-IDE-001: IDE fails to resolve relative paths in project structure
 * - P5-IDE-004: IDE Path resolution with symlinks fails
 * - P5-IDE-007: IDE fails on paths with spaces and special characters
 */
@DisplayName("IDE Path Resolution Tests")
public class IDEPathResolutionTest {

    private Path testDir;
    private static final String TEST_NAME = "ide-path-resolution";

    @BeforeEach
    void setUp() throws IOException {
        testDir = TestEnvironmentSetup.createTestDirectory(TEST_NAME);
        // Create project structure
        Files.createDirectories(testDir.resolve("src/main/java"));
        Files.createDirectories(testDir.resolve("src/test/java"));
        Files.createDirectories(testDir.resolve("target/classes"));
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSetup.cleanupTestDirectory(TEST_NAME);
    }

    // P5-IDE-001: IDE fails to resolve relative paths in project structure
    @Test
    @DisplayName("P5-IDE-001: Resolve relative path from project root")
    void testRelativePathResolution() throws IOException {
        Path projectRoot = testDir;
        Path sourceFile = projectRoot.resolve("src/main/java/Test.java");
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "Test.java", "public class Test {}");

        // Test relative path resolution
        Path relativePath = projectRoot.relativize(sourceFile);
        assertThat(relativePath.toString()).isEqualTo("src/main/java/Test.java");
        
        // Verify file can be accessed via relative path
        Path resolvedPath = projectRoot.resolve(relativePath);
        assertThat(Files.exists(resolvedPath)).isTrue();
        assertThat(Files.isRegularFile(resolvedPath)).isTrue();
    }

    @Test
    @DisplayName("P5-IDE-001: Resolve nested relative paths")
    void testNestedRelativePath() throws IOException {
        Path projectRoot = testDir;
        Path nestedSource = projectRoot.resolve("src/main/java/com/example/Test.java");
        TestEnvironmentSetup.createTestFile(nestedSource.getParent(), "Test.java", 
                "package com.example; public class Test {}");

        Path relativePath = projectRoot.relativize(nestedSource);
        assertThat(relativePath.toString()).contains("src");
        assertThat(relativePath.toString()).contains("main");
        assertThat(relativePath.toString()).contains("java");
        assertThat(Files.exists(projectRoot.resolve(relativePath))).isTrue();
    }

    @Test
    @DisplayName("P5-IDE-001: Resolve paths with .. navigation")
    void testRelativePathWithDotDot() throws IOException {
        Path projectRoot = testDir;
        Path classFile = projectRoot.resolve("src/main/java/Test.class");
        TestEnvironmentSetup.createTestFile(classFile.getParent(), "Test.class", "CAFEBABE");

        // Navigate from test to main using ..
        Path testDir = projectRoot.resolve("src/test/java");
        Path navigatePath = testDir.resolve("../../main/java/Test.class").normalize();
        
        assertThat(Files.exists(navigatePath)).isTrue();
    }

    // P5-IDE-004: IDE Path resolution with symlinks fails
    @Test
    @DisplayName("P5-IDE-004: Resolve symlink to source file")
    void testSymlinkResolution() throws IOException {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            // Skip on Windows without admin privileges
            return;
        }

        Path sourceFile = testDir.resolve("src/main/java/Original.java");
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "Original.java", 
                "public class Original {}");

        Path symlinkFile = testDir.resolve("Original.java");
        try {
            Files.createSymbolicLink(symlinkFile, sourceFile);
            
            // Verify symlink can be read
            assertThat(Files.exists(symlinkFile)).isTrue();
            assertThat(Files.isSymbolicLink(symlinkFile)).isTrue();
            
            // Verify symlink resolves correctly
            Path resolvedPath = Files.readSymbolicLink(symlinkFile);
            assertThat(resolvedPath).isNotNull();
        } catch (UnsupportedOperationException e) {
            // Symlinks not supported on this system
        }
    }

    @Test
    @DisplayName("P5-IDE-004: Resolve real path through symlink")
    void testSymlinkRealPathResolution() throws IOException {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return;
        }

        Path sourceFile = testDir.resolve("src/main/java/SymSource.java");
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "SymSource.java", 
                "public class SymSource {}");

        Path symlinkDir = testDir.resolve("symlink-src");
        try {
            Files.createSymbolicLink(symlinkDir, sourceFile.getParent());
            
            Path resolvedPath = Files.readSymbolicLink(symlinkDir);
            assertThat(resolvedPath).isNotNull();
            assertThat(Files.isDirectory(symlinkDir)).isTrue();
        } catch (UnsupportedOperationException e) {
            // Symlinks not supported
        }
    }

    // P5-IDE-007: IDE fails on paths with spaces and special characters
    @Test
    @DisplayName("P5-IDE-007: Resolve path with spaces")
    void testPathWithSpaces() throws IOException {
        Path parentDir = testDir.resolve("my project");
        Files.createDirectories(parentDir.resolve("src/main/java"));
        
        Path sourceFile = parentDir.resolve("src/main/java/MyTest.java");
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "MyTest.java", 
                "public class MyTest {}");

        assertThat(Files.exists(sourceFile)).isTrue();
        assertThat(sourceFile.toString()).contains(" ");
    }

    @Test
    @DisplayName("P5-IDE-007: Resolve path with special characters")
    void testPathWithSpecialCharacters() throws IOException {
        Path parentDir = testDir.resolve("my-project_v1.0");
        Files.createDirectories(parentDir.resolve("src/main/java"));
        
        Path sourceFile = parentDir.resolve("src/main/java/TestClass-2024.java");
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "TestClass-2024.java", 
                "public class TestClass2024 {}");

        assertThat(Files.exists(sourceFile)).isTrue();
        String fileName = sourceFile.getFileName().toString();
        assertThat(fileName).contains("-").contains(".java");
    }

    @Test
    @DisplayName("P5-IDE-007: Resolve path with unicode characters")
    void testPathWithUnicodeCharacters() throws IOException {
        Path parentDir = testDir.resolve("projet_café");
        Files.createDirectories(parentDir.resolve("src/main/java"));
        
        Path sourceFile = parentDir.resolve("src/main/java/Café.java");
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "Café.java", 
                "public class Cafe {}");

        assertThat(Files.exists(sourceFile)).isTrue();
    }

    @Test
    @DisplayName("P5-IDE-007: Resolve absolute path conversions")
    void testAbsolutePathConversion() throws IOException {
        Path sourceFile = testDir.resolve("src/main/java/Absolute.java");
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "Absolute.java", 
                "public class Absolute {}");

        // Convert to absolute and verify
        Path absolutePath = sourceFile.toAbsolutePath();
        assertThat(absolutePath.isAbsolute()).isTrue();
        assertThat(Files.exists(absolutePath)).isTrue();
    }
}
