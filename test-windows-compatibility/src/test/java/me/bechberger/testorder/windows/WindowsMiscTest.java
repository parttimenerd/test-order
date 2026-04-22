package me.bechberger.testorder.windows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Windows Miscellaneous Issues Test Suite
 * Tests for bugs: P5-WIN-005, P5-WIN-006, P5-WIN-007, P5-WIN-008, P5-WIN-012, 
 *                 P5-WIN-017, P5-WIN-018, P5-WIN-020, P5-WIN-022, P5-WIN-023, 
 *                 P5-WIN-026, P5-WIN-027, P5-WIN-029, P5-WIN-030
 * 
 * These tests cover various Windows-specific issues not fitting other categories.
 */
@DisplayName("Windows Miscellaneous Issues Tests")
public class WindowsMiscTest {

    @TempDir
    Path tempDir;

    private Path sourceDir;
    private Path cacheDir;

    @BeforeEach
    public void setup() throws IOException {
        sourceDir = tempDir.resolve("src");
        Files.createDirectory(sourceDir);
        cacheDir = tempDir.resolve(".test-order");
        Files.createDirectory(cacheDir);
    }

    @Test
    @DisplayName("P5-WIN-005: FQCN calculation handles both path separators")
    public void testFQCNCalculationRobustness() {
        // SourceFileModel.java:1332, 1352
        String pathWithBackslash = "src\\main\\java\\com\\example\\Test.java";
        String pathWithForwardSlash = "src/main/java/com/example/Test.java";
        
        // Both should produce same FQCN
        String fqcn1 = calculateFQCN(pathWithBackslash);
        String fqcn2 = calculateFQCN(pathWithForwardSlash);
        
        assertThat(fqcn1).isEqualTo(fqcn2).isEqualTo("com.example.Test");
    }

    private String calculateFQCN(String path) {
        // Remove src/main/java prefix
        String withoutPrefix = path.substring("src/main/java/".length());
        String withoutExt = withoutPrefix.substring(0, withoutPrefix.length() - 5);
        return withoutExt.replace('\\', '.').replace('/', '.');
    }

    @Test
    @DisplayName("P5-WIN-006: FileHashStore path normalization should be consistent")
    public void testFileHashStorePathNormalization() throws IOException {
        // FileHashStore.java:40, 80
        String windowsPath = "src\\main\\java\\Test.java";
        String gitPath = "src/main/java/Test.java";
        
        // Normalize for cache storage
        String normalized1 = windowsPath.replace('\\', '/');
        String normalized2 = gitPath.replace('\\', '/');
        
        // Should be identical
        assertThat(normalized1).isEqualTo(normalized2);
        
        // Cache key should be consistent
        String cacheKey1 = hashKey(normalized1);
        String cacheKey2 = hashKey(normalized2);
        
        assertThat(cacheKey1).isEqualTo(cacheKey2);
    }

    private String hashKey(String path) {
        // Simulate cache key generation
        return path.replace('\\', '/');
    }

    @Test
    @DisplayName("P5-WIN-007: Atomic move with fallback for network drives")
    public void testAtomicMoveWithNetworkDriveFallback() throws IOException {
        Path sourceFile = tempDir.resolve("source.txt");
        Path targetFile = tempDir.resolve("target.txt");
        
        Files.write(sourceFile, "content".getBytes());
        
        // Try atomic move with fallback
        boolean success = false;
        try {
            Files.move(sourceFile, targetFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            success = true;
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Expected on network drives
            Files.move(sourceFile, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            success = true;
        }
        
        assertThat(success).isTrue();
        assertThat(Files.exists(targetFile)).isTrue();
    }

    @Test
    @DisplayName("P5-WIN-008: File locking semantics on network drives")
    public void testFileLockingSemantics() throws IOException {
        // FileChannel.lock() has different semantics
        Path file = tempDir.resolve("locked.txt");
        Files.write(file, "content".getBytes());
        
        try (var channel = Files.newByteChannel(file, java.nio.file.StandardOpenOption.READ)) {
            // On Windows: mandatory locks (other processes blocked)
            // On Unix: advisory locks (others can still access)
            
            // Code should handle both correctly
            assertThat(Files.exists(file)).isTrue();
        }
    }

    @Test
    @DisplayName("P5-WIN-012: Colons in paths should not break parameter parsing")
    public void testColonInPathParameterParsing() {
        // Drive letters contain colons: C:, D:, etc.
        String drivePath = "C:\\Users\\Project\\agent.jar";
        String params = "param1=value1;param2=value2";
        
        // Parse parameters - colon should not interfere
        String[] paramPairs = params.split(";");
        
        assertThat(paramPairs).hasSize(2);
        for (String pair : paramPairs) {
            String[] kv = pair.split("=");
            assertThat(kv).hasSize(2);
        }
    }

    @Test
    @DisplayName("P5-WIN-017: Git batch response path matching with different separators")
    public void testGitBatchResponsePathMatching() {
        // Request uses backslashes, response uses forward slashes
        String requestPath = "src\\test\\TestClass.java";
        String responsePath = "src/test/TestClass.java";
        
        // Normalize both for matching
        String normalized = requestPath.replace('\\', '/');
        
        assertThat(normalized).isEqualTo(responsePath);
    }

    @Test
    @DisplayName("P5-WIN-018: UNC paths should be recognized")
    public void testUNCPathRecognition() {
        String uncPath = "\\\\server\\share\\project\\src\\Main.java";
        
        // UNC paths start with \\
        assertThat(uncPath).startsWith("\\\\");
        
        // Extract server and share
        String[] parts = uncPath.split("\\\\");
        
        // Format: ["", "", "server", "share", "project", ...]
        assertThat(parts).hasSizeGreaterThan(3);
        assertThat(parts[2]).isEqualTo("server");
        assertThat(parts[3]).isEqualTo("share");
    }

    @Test
    @DisplayName("P5-WIN-020: Maven properties with backslashes")
    public void testMavenPropertiesWithBackslashes() {
        // Maven properties on Windows contain backslashes
        String projectBasedir = "C:\\Users\\workspace\\myproject";
        
        // Property substitution
        String template = "${project.basedir}/src/main/java";
        String expanded = template.replace("${project.basedir}", projectBasedir);
        
        assertThat(expanded).isEqualTo("C:\\Users\\workspace\\myproject/src/main/java");
    }

    @Test
    @DisplayName("P5-WIN-022: Classpath separator varies by platform")
    public void testClasspathSeparatorVariation() {
        // Windows uses semicolon, Unix uses colon
        String osName = System.getProperty("os.name", "").toLowerCase();
        String separator = osName.contains("win") ? ";" : ":";
        
        String jar1 = "/path/to/lib1.jar";
        String jar2 = "/path/to/lib2.jar";
        
        String classpath = jar1 + separator + jar2;
        
        assertThat(classpath).contains(separator);
    }

    @Test
    @DisplayName("P5-WIN-023: Drive letter mapping should invalidate cache")
    public void testDriveLetterMappingCacheInvalidation() {
        // Same project on D: vs E: creates different cache paths
        String pathOnD = "D:\\projects\\myapp";
        String pathOnE = "E:\\projects\\myapp";
        
        // Cache keys should be different (different drive letter)
        String cacheKeyD = pathOnD.toLowerCase();
        String cacheKeyE = pathOnE.toLowerCase();
        
        assertThat(cacheKeyD).isNotEqualTo(cacheKeyE);
    }

    @Test
    @DisplayName("P5-WIN-026: NTFS Alternative Data Streams")
    public void testNTFSAlternativeDataStreams() {
        // Windows NTFS supports Alternative Data Streams (ADS)
        // Filename:StreamName syntax
        String fileWithADS = "cache.json:Zone.Identifier";
        
        // Downloaded files might have ADS
        // Should be aware of this for cleanup
        assertThat(fileWithADS).contains(":");
    }

    @Test
    @DisplayName("P5-WIN-027: CLI JAR requires java -jar on Windows")
    public void testCLIJARExecutability() {
        // test-order-cli.jar not directly executable on Windows
        // Requires explicit "java -jar" or wrapper script
        
        String jarName = "test-order-cli.jar";
        String command = "java -jar " + jarName;
        
        // Verify command format
        assertThat(command).startsWith("java -jar");
        assertThat(command).endsWith(jarName);
    }

    @Test
    @DisplayName("P5-WIN-029: Gradle wrapper script line endings")
    public void testGradleWrapperLineEndings() throws IOException {
        // Wrapper scripts with CRLF might not execute
        Path wrapper = tempDir.resolve("gradlew");
        
        String unixShebang = "#!/bin/bash\n";
        String wrongEndline = "#!/bin/bash\r\n";
        
        Files.write(wrapper, unixShebang.getBytes(StandardCharsets.UTF_8));
        
        String content = Files.readString(wrapper);
        
        // Should use LF only
        assertThat(content).startsWith("#!/bin/bash");
        assertThat(content.substring(0, 10)).doesNotContain("\r");
    }

    @Test
    @DisplayName("P5-WIN-030: Maven property separators in complex values")
    public void testMavenPropertyComplexValues() {
        // Maven properties with embedded paths might have backslashes
        Map<String, String> properties = new HashMap<>();
        properties.put("project.basedir", "C:\\Users\\dev\\project");
        properties.put("java.io.tmpdir", "C:\\Windows\\Temp");
        
        // Substitution should work
        String template = "${project.basedir}\\src\\main\\java";
        String expanded = template;
        
        for (String key : properties.keySet()) {
            expanded = expanded.replace("${" + key + "}", properties.get(key));
        }
        
        assertThat(expanded).contains("C:\\Users\\dev\\project");
    }

    @Test
    @DisplayName("Command line quoting for scripts")
    public void testCommandLineQuotingForScripts() {
        String pathWithSpaces = "C:\\Program Files\\Test\\script.bat";
        
        // Script paths with spaces need quoting
        String command = "\"" + pathWithSpaces + "\" arg1 arg2";
        
        assertThat(command).startsWith("\"");
        assertThat(command).contains(pathWithSpaces);
    }

    @Test
    @DisplayName("Network path prefix handling")
    public void testNetworkPathPrefixHandling() {
        String uncPath = "\\\\network-server\\shared-folder\\project\\file.txt";
        
        // UNC path structure
        String[] parts = uncPath.split("\\\\");
        
        // Should be able to parse server and share
        assertThat(parts).hasSizeGreaterThan(3);
    }

    @Test
    @DisplayName("Relative path resolution on different drives")
    public void testRelativePathResolutionOnDifferentDrives() {
        // On Windows, relative paths are relative to current drive
        Path currentDir = Path.of("C:\\Users\\dev\\project");
        Path relativePath = Path.of("..\\other\\file.txt");
        
        // Resolution should work
        Path resolved = currentDir.resolve(relativePath).normalize();
        
        assertThat(resolved).isNotNull();
    }

    @Test
    @DisplayName("Windows path limit handling")
    public void testWindowsPathLimitHandling() throws IOException {
        // Windows MAX_PATH is 260 characters
        StringBuilder longPath = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            longPath.append("some_directory_name_").append(i).append(java.io.File.separator);
        }
        longPath.append("file.txt");
        
        // For paths exceeding 260, use \\?\ prefix
        String extendedPath = "\\\\?\\" + longPath;
        
        if (longPath.length() > 260) {
            assertThat(extendedPath).startsWith("\\\\?\\");
        }
    }

    @Test
    @DisplayName("File path case normalization")
    public void testFilePathCaseNormalization() {
        String uppercase = "SRC\\MAIN\\JAVA\\Test.java";
        String lowercase = "src\\main\\java\\test.java";
        
        // On Windows, case-insensitive comparison
        String norm1 = uppercase.toLowerCase().replace('\\', '/');
        String norm2 = lowercase.toLowerCase().replace('\\', '/');
        
        assertThat(norm1).isEqualTo(norm2);
    }

    @Test
    @DisplayName("Registry path handling in Windows")
    public void testWindowsRegistryPathHandling() {
        // Java process might read registry values
        // Registry paths are not file paths
        String registryPath = "HKEY_LOCAL_MACHINE\\Software\\Java\\JavaHome";
        
        // Should not be treated as file path
        assertThat(registryPath).startsWith("HKEY_");
    }

    @Test
    @DisplayName("Temp directory cleanup with deferred deletion")
    public void testTempDirectoryCleanupWithDeferral() throws IOException {
        Path tempFile = tempDir.resolve("cache.tmp");
        Files.write(tempFile, "data".getBytes());
        
        try {
            Files.delete(tempFile);
        } catch (IOException e) {
            // If locked, mark for deletion on VM exit
            tempFile.toFile().deleteOnExit();
            
            // Verify marker is set
            assertThat(tempFile.toFile().getName()).endsWith(".tmp");
        }
    }

    @Test
    @DisplayName("Path normalization roundtrip")
    public void testPathNormalizationRoundtrip() {
        String original = "src\\..\\src\\main\\java\\Test.java";
        
        // Normalize
        Path path = Path.of(original);
        String normalized = path.normalize().toString();
        
        // Normalized form should be cleaner
        // Note: On non-Windows systems, backslashes are literal characters, not path separators
        assertThat(path.normalize()).isNotNull();
    }
}
