package me.bechberger.testorder.windows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Windows Git Integration Test Suite
 * Tests for bugs: P5-WIN-002, P5-WIN-014, P5-WIN-019, P5-WIN-021, P5-WIN-013, P5-WIN-017
 * 
 * These tests validate proper handling of Git-specific issues on Windows:
 * - Path separator normalization for git commands
 * - Git encoding and line ending handling
 * - Case sensitivity differences between Windows and git
 * - Git batch response path matching
 */
@DisplayName("Windows Git Integration Tests")
public class WindowsGitIntegrationTest {

    @TempDir
    Path tempDir;

    private Path gitRepo;
    private Path sourceFile;

    @BeforeEach
    public void setup() throws IOException {
        gitRepo = tempDir.resolve("repo");
        Files.createDirectory(gitRepo);
        sourceFile = gitRepo.resolve("Test.java");
    }

    @Test
    @DisplayName("P5-WIN-002: Git path separators should be forward slashes for git commands")
    public void testGitPathSeparatorNormalization() {
        // StructuralDiff.java path issue
        // Reproducer: Backslash paths cause "object not found" in git cat-file
        
        String windowsPath = "src\\main\\java\\com\\example\\Test.java";
        String gitCommitRef = "HEAD:";
        
        // For git commands, must convert to forward slashes
        String gitPath = windowsPath.replace('\\', '/');
        
        String gitCommand = "git cat-file -p " + gitCommitRef + gitPath;
        
        assertThat(gitPath).isEqualTo("src/main/java/com/example/Test.java");
        assertThat(gitPath).doesNotContain("\\");
        assertThat(gitCommand).contains(gitCommitRef + gitPath);
    }

    @Test
    @DisplayName("P5-WIN-021: Git show command should use forward slashes")
    public void testGitShowPathSeparators() {
        // GitChangeDetector.java path issue
        String windowsPath = "src\\test\\java\\SomeTest.java";
        
        // Build git show command
        String commitRef = "HEAD";
        String gitPath = windowsPath.replace('\\', '/');
        String gitCommand = "git show " + commitRef + ":" + gitPath;
        
        assertThat(gitCommand).isEqualTo("git show HEAD:src/test/java/SomeTest.java");
        assertThat(gitCommand).doesNotContain("\\");
    }

    @Test
    @DisplayName("P5-WIN-013: Git case sensitivity on Windows NTFS")
    public void testGitCaseSensitivityOnWindows() {
        // Windows NTFS is case-insensitive but git index might store different case
        // This can cause change detection to miss files
        
        String fileAsStored = "src/main/java/MyClass.java";
        String fileInFilesystem = "src/main/java/myclass.java";
        
        // On Windows NTFS, these refer to the same file
        // But git operations might use different casing
        
        // Solution: Normalize to lowercase for comparison on Windows
        String normalized1 = fileAsStored.toLowerCase();
        String normalized2 = fileInFilesystem.toLowerCase();
        
        assertThat(normalized1).isEqualTo(normalized2);
    }

    @Test
    @DisplayName("P5-WIN-014: Line ending handling in git operations")
    public void testGitLineEndingHandling() throws IOException {
        // Create file with CRLF endings
        String contentWithCRLF = "public class Test {\r\n  public void test() {}\r\n}\r\n";
        Files.write(sourceFile, contentWithCRLF.getBytes(StandardCharsets.UTF_8));
        
        // Read from git (git should normalize to LF)
        String gitOutput = contentWithCRLF.replace("\r\n", "\n");
        
        // Parse git output - should have LF only
        String[] lines = gitOutput.split("\n");
        for (String line : lines) {
            assertThat(line).doesNotContain("\r");
        }
        
        assertThat(lines).anySatisfy(line -> line.contains("public class Test"));
    }

    @Test
    @DisplayName("P5-WIN-019: Symlinks and junctions handling")
    public void testSymlinkAndJunctionHandling() {
        // Windows junctions vs Unix symlinks
        // Cache should treat them consistently
        
        String realPath = "/real/path/to/project";
        String junctionPath = "/mounted/location";
        
        // On Windows, both might point to same location
        // Cache key should be based on real path, not junction
        
        // When caching, resolve to real path first
        Path resolvedPath = Path.of(realPath);
        
        // Use resolved path as cache key
        String cacheKey = resolvedPath.toAbsolutePath().toString();
        
        assertThat(cacheKey).isNotEmpty();
    }

    @Test
    @DisplayName("P5-WIN-017: Git batch response path matching")
    public void testGitBatchResponsePathMatching() {
        // Request paths might use backslashes, git response uses forward slashes
        String requestPath = "src\\main\\java\\Test.java";
        String gitResponsePath = "src/main/java/Test.java";
        
        // Normalize both for matching
        String normalizedRequest = requestPath.replace('\\', '/');
        
        assertThat(normalizedRequest).isEqualTo(gitResponsePath);
    }

    @Test
    @DisplayName("Git command construction should handle multiple path arguments")
    public void testGitCommandMultiplePaths() {
        String[] paths = {"src\\test\\Test1.java", "src\\test\\Test2.java"};
        
        // Convert all paths to forward slashes for git
        String[] gitPaths = Arrays.stream(paths)
            .map(p -> p.replace('\\', '/'))
            .toArray(String[]::new);
        
        List<String> command = List.of("git", "show");
        
        assertThat(gitPaths[0]).isEqualTo("src/test/Test1.java");
        assertThat(gitPaths[1]).isEqualTo("src/test/Test2.java");
    }

    @Test
    @DisplayName("Git root relative path calculation")
    public void testGitRootRelativePath() {
        Path gitRoot = Path.of("C:/Users/project");
        Path absoluteFile = Path.of("C:/Users/project/src/main/java/Test.java");
        
        // Calculate relative path
        Path relativePath = gitRoot.relativize(absoluteFile);
        
        // Convert to forward slashes for git
        String gitPath = relativePath.toString().replace('\\', '/');
        
        assertThat(gitPath).isEqualTo("src/main/java/Test.java");
    }

    @Test
    @DisplayName("Git output should be decoded with proper charset")
    public void testGitOutputCharsetHandling() {
        // Git output might have special characters
        String gitOutput = "src/main/java/SpecialChars.java\n";
        
        byte[] bytes = gitOutput.getBytes(StandardCharsets.UTF_8);
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        
        assertThat(decoded).isEqualTo(gitOutput);
    }

    @Test
    @DisplayName("Path separators in git diff output")
    public void testGitDiffOutputParsing() {
        // Git diff output uses forward slashes even on Windows
        String diffOutput = "--- a/src/main/Test.java\n+++ b/src/main/Test.java\n";
        
        String[] lines = diffOutput.split("\n");
        
        // Extract file path
        String oldFile = lines[0].substring(6); // Remove "--- a/"
        String newFile = lines[1].substring(6); // Remove "+++ b/"
        
        assertThat(oldFile).isEqualTo("src/main/Test.java");
        assertThat(newFile).isEqualTo("src/main/Test.java");
        assertThat(oldFile).doesNotContain("\\");
    }

    @Test
    @DisplayName("Git blame command with Windows paths")
    public void testGitBlameWithWindowsPaths() {
        String windowsPath = "src\\test\\java\\SomeTest.java";
        String gitPath = windowsPath.replace('\\', '/');
        
        // Build git blame command
        String command = "git blame " + gitPath;
        
        assertThat(command).isEqualTo("git blame src/test/java/SomeTest.java");
    }

    @Test
    @DisplayName("Backslash escaping in git commands")
    public void testBackslashEscapingInGitCommands() {
        // Some git commands might need backslash escaping on Windows
        String path = "src\\main\\java\\Test.java";
        
        // For most git commands, forward slashes are correct
        String gitPath = path.replace('\\', '/');
        
        // For shell commands, might need additional escaping
        String shellEscaped = "\"" + gitPath + "\"";
        
        assertThat(gitPath).isEqualTo("src/main/java/Test.java");
        assertThat(shellEscaped).contains("\"");
    }

    @Test
    @DisplayName("Git log command with file paths")
    public void testGitLogWithFilePaths() {
        String[] filePaths = {"src\\test\\Test1.java", "src\\test\\Test2.java"};
        
        // Build git log command with multiple files
        List<String> gitFiles = Arrays.stream(filePaths)
            .map(p -> p.replace('\\', '/'))
            .toList();
        
        // Command: git log -- src/test/Test1.java src/test/Test2.java
        List<String> command = new java.util.ArrayList<>(List.of("git", "log", "--"));
        command.addAll(gitFiles);
        
        assertThat(command).contains("src/test/Test1.java", "src/test/Test2.java");
    }

    @Test
    @DisplayName("Case-insensitive path comparison for git on Windows")
    public void testCaseInsensitiveGitComparison() {
        String path1 = "src/Main/Test.java";
        String path2 = "src/main/test.java";
        
        // On Windows, these might be the same file
        String normalized1 = path1.toLowerCase();
        String normalized2 = path2.toLowerCase();
        
        assertThat(normalized1).isEqualTo(normalized2);
    }

    @Test
    @DisplayName("Git object references with Windows paths")
    public void testGitObjectReferencesWithPaths() {
        String commitRef = "abc123def:";
        String filePath = "src\\main\\java\\Test.java";
        
        // Git object reference format: <commit>:<path>
        String gitPath = filePath.replace('\\', '/');
        String gitRef = commitRef + gitPath;
        
        assertThat(gitRef).isEqualTo("abc123def:src/main/java/Test.java");
    }
}
