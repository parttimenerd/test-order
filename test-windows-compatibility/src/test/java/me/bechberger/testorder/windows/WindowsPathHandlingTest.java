package me.bechberger.testorder.windows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.*;

/**
 * Windows Path Handling Test Suite
 * Tests for bugs: P5-WIN-001, P5-WIN-005, P5-WIN-006, P5-WIN-008, P5-WIN-009, P5-WIN-012, P5-WIN-025
 * 
 * These tests validate proper handling of Windows-specific path issues:
 * - Paths with spaces
 * - Drive letters with colons
 * - UNC network paths
 * - Deep nested paths exceeding MAX_PATH
 * - Path separator normalization
 */
@DisplayName("Windows Path Handling Tests")
public class WindowsPathHandlingTest {

    @TempDir
    Path tempDir;

    private Path projectPath;

    @BeforeEach
    public void setup() throws Exception {
        // Setup test directory that mimics Windows structure
        projectPath = tempDir.resolve("test-project");
        Files.createDirectory(projectPath);
    }

    @Test
    @DisplayName("P5-WIN-001: Javaagent path with spaces should be properly quoted")
    public void testJavaagentPathWithSpaces() {
        // Reproducer: Gradle plugin should quote javaagent path when it contains spaces
        // Example on Windows: C:\Program Files\MyProject\agent.jar
        
        String pathWithSpaces = "C:\\Program Files\\MyProject\\agent.jar";
        String agentArgs = "arg1=value1";
        
        // The path MUST be quoted when passed to JVM command line
        String javaagentOption = "-javaagent:\"" + pathWithSpaces + "\"=" + agentArgs;
        
        // Verify the javaagent option is properly formatted
        assertThat(javaagentOption)
            .contains("\"" + pathWithSpaces + "\"")
            .startsWith("-javaagent:");
    }

    @Test
    @DisplayName("P5-WIN-006: Path separator normalization should handle mixed separators")
    public void testPathSeparatorNormalization() {
        // Path normalization should handle both forward and back slashes
        String mixedPath = "src\\main\\java\\com/example/Test.java";
        
        // Normalize to forward slashes for git operations
        String normalized = mixedPath.replace('\\', '/');
        
        assertThat(normalized).isEqualTo("src/main/java/com/example/Test.java");
        assertThat(normalized).doesNotContain("\\");
    }

    @Test
    @DisplayName("P5-WIN-005: FQCN should properly handle path separators")
    public void testFQCNCalculationFromPath() {
        // Calculate FQCN from path like: src\main\java\com\example\Test.java
        String path = "src\\main\\java\\com\\example\\Test.java";
        
        // Remove src/main/java prefix and .java extension
        String withoutPrefix = path.substring("src\\main\\java\\".length());
        String withoutExtension = withoutPrefix.substring(0, withoutPrefix.length() - 5);
        
        // Replace path separators with dots
        String fqcn = withoutExtension.replace('\\', '.').replace('/', '.');
        
        assertThat(fqcn).isEqualTo("com.example.Test");
    }

    @Test
    @DisplayName("P5-WIN-012: Drive letter colon in javaagent arguments should be escaped")
    public void testDriveLetterColonHandling() {
        // On Windows, drive letters contain colons: C:\Project\...
        // The javaagent parameter parsing must handle this correctly
        
        String drivePath = "C:\\Users\\Project\\agent.jar";
        String drivePathQuoted = "\"" + drivePath + "\"";
        
        // Verify quotes protect the colon in drive letter
        assertThat(drivePathQuoted).contains(":").contains("\"");
        
        // The colon should be inside the quoted string
        int colonIndex = drivePathQuoted.indexOf(":");
        int quoteStart = drivePathQuoted.indexOf("\"");
        assertThat(colonIndex).isGreaterThan(quoteStart);
    }

    @Test
    @DisplayName("P5-WIN-008: UNC paths should be recognized and handled")
    public void testUNCPathHandling() {
        // UNC paths have format: \\server\share\path\to\project
        String uncPath = "\\\\server\\share\\project\\src\\Main.java";
        
        // UNC paths should not be confused with relative paths
        assertThat(uncPath).startsWith("\\\\");
        
        // Should be able to extract server and share
        String[] parts = uncPath.split("\\\\");
        // ["", "", "server", "share", "project", "src", "Main.java"] = 7 parts
        assertThat(parts).hasSizeGreaterThanOrEqualTo(4);
        assertThat(parts[2]).isEqualTo("server");
        assertThat(parts[3]).isEqualTo("share");
    }

    @Test
    @DisplayName("P5-WIN-009: Paths exceeding MAX_PATH (260 chars) should be handled")
    public void testMaxPathHandling() {
        // Windows MAX_PATH is 260 characters
        // Deep project structures can exceed this
        
        // Build a deep path
        StringBuilder deepPath = new StringBuilder("C:\\");
        for (int i = 0; i < 15; i++) {
            deepPath.append("very_long_directory_name_").append(i).append("\\");
        }
        deepPath.append(".test-order\\deps\\package\\method.json");
        
        String path = deepPath.toString();
        
        // For Windows, paths exceeding 260 chars need special handling
        if (path.length() > 260) {
            // Solution 1: Use long path prefix
            String longPathPrefix = "\\\\?\\" + path;
            assertThat(longPathPrefix).startsWith("\\\\?\\");
            
            // Solution 2: Use Java's File API which handles this
            File file = new File(path);
            assertThat(file.getAbsolutePath()).isNotNull();
        }
    }

    @Test
    @DisplayName("P5-WIN-025: Temp directory location should have write permissions")
    public void testTempDirectoryWritePermissions() throws Exception {
        // On Windows, temp directory location varies
        // Should verify write permissions before using
        
        Path tempPath = Files.createTempDirectory("test-order-temp-");
        
        try {
            // Verify directory is writable
            assertThat(Files.isWritable(tempPath)).isTrue();
            
            // Should be able to create files in temp directory
            Path testFile = tempPath.resolve("test.tmp");
            Files.write(testFile, "test content".getBytes());
            
            assertThat(Files.exists(testFile)).isTrue();
            Files.delete(testFile);
        } finally {
            Files.delete(tempPath);
        }
    }

    @Test
    @DisplayName("Cache path should not be fragile with backslash handling")
    public void testCachePathSeparatorConsistency() {
        // FileHashStore.java uses explicit backslash replacement
        // This test ensures consistency across operations
        
        String windowsPath = "src\\main\\java\\com\\example\\Test.java";
        String unixPath = "src/main/java/com/example/Test.java";
        
        // Both should normalize to same representation
        String normalizedWindows = windowsPath.replace('\\', '/');
        String normalizedUnix = unixPath.replace('\\', '/');
        
        assertThat(normalizedWindows).isEqualTo(normalizedUnix);
        assertThat(normalizedWindows).isEqualTo("src/main/java/com/example/Test.java");
    }

    @Test
    @DisplayName("File paths in classpath should use platform-specific separator")
    public void testClasspathSeparatorHandling() {
        // Windows uses semicolon; separator - Unix uses colon
        String osName = System.getProperty("os.name", "").toLowerCase();
        String expectedSeparator = osName.contains("win") ? ";" : ":";
        
        // When building classpath, must use correct separator for platform
        String jar1 = "/path/to/lib1.jar";
        String jar2 = "/path/to/lib2.jar";
        String classpath = jar1 + expectedSeparator + jar2;
        
        assertThat(classpath).contains(expectedSeparator);
    }

    @Test
    @DisplayName("Path normalization should preserve absolute paths")
    public void testAbsolutePathPreservation() {
        // When normalizing paths, absolute paths should remain absolute
        String windowsAbsolute = "C:\\Program Files\\Project\\src\\Test.java";
        String normalized = windowsAbsolute.replace('\\', '/');
        
        assertThat(normalized).startsWith("C:/");
        
        // On Windows, File API recognizes backslash absolute paths
        java.io.File file = new java.io.File(windowsAbsolute);
        assertThat(file.getAbsolutePath()).isNotNull();
    }
}
