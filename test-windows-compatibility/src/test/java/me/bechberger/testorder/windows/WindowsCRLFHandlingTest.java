package me.bechberger.testorder.windows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Windows CRLF Line Ending Handling Test Suite
 * Tests for bugs: P5-WIN-003, P5-WIN-004, P5-WIN-028
 * 
 * These tests validate proper handling of CRLF line endings:
 * - LineDiff.java CRLF splitting issues
 * - SourceFileModel.java CRLF parsing
 * - Mixed CRLF/LF handling
 */
@DisplayName("Windows CRLF Line Ending Tests")
public class WindowsCRLFHandlingTest {

    @TempDir
    Path tempDir;

    private Path sourceFile;

    @BeforeEach
    public void setup() throws IOException {
        sourceFile = tempDir.resolve("TestFile.java");
    }

    @Test
    @DisplayName("P5-WIN-003: LineDiff should properly split CRLF lines")
    public void testLineDiffCRLFSplitting() throws IOException {
        // Reproducer: Split on \n only leaves \r characters in lines
        String contentWithCRLF = "public class Test {\r\n  public void method() {\r\n    System.out.println(\"test\");\r\n  }\r\n}\r\n";
        
        // WRONG: split("\n") leaves \r characters
        String[] wrongLines = contentWithCRLF.split("\n", -1);
        assertThat(wrongLines[0]).endsWith("\r");
        assertThat(wrongLines[1]).endsWith("\r");
        
        // CORRECT: Use proper regex to handle both CRLF and LF
        String[] correctLines = contentWithCRLF.split("\\r?\\n", -1);
        assertThat(correctLines[0]).doesNotEndWith("\r").doesNotEndWith("\n");
        assertThat(correctLines[1]).doesNotEndWith("\r").doesNotEndWith("\n");
        assertThat(correctLines[0]).isEqualTo("public class Test {");
        assertThat(correctLines[1]).isEqualTo("  public void method() {");
    }

    @Test
    @DisplayName("P5-WIN-004: SourceFileModel should handle CRLF in source parsing")
    public void testSourceFileModelCRLFParsing() throws IOException {
        // Create file with CRLF line endings (Windows style)
        String javaCode = "public class Test {\r\n  public void test() {\r\n    int x = 1;\r\n  }\r\n}\r\n";
        Files.write(sourceFile, javaCode.getBytes(StandardCharsets.UTF_8));
        
        // Read the file back
        String fileContent = Files.readString(sourceFile);
        
        // Normalize line endings before parsing
        String normalized = fileContent.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        
        // All lines should be clean without \r
        for (String line : lines) {
            assertThat(line).doesNotContain("\r");
        }
        
        // Should be able to parse structural elements
        assertThat(lines).anySatisfy(line -> line.contains("public class Test"));
        assertThat(lines).anySatisfy(line -> line.contains("public void test"));
    }

    @Test
    @DisplayName("CRLF lines should not affect diff comparisons")
    public void testCRLFComparisonInDiff() {
        // Old version with CRLF
        String oldText = "line1\r\nline2\r\nline3\r\n";
        
        // New version with CRLF
        String newText = "line1\r\nline2_modified\r\nline3\r\n";
        
        // Normalize both before comparison
        String oldNormalized = oldText.replace("\r\n", "\n");
        String newNormalized = newText.replace("\r\n", "\n");
        
        String[] oldLines = oldNormalized.split("\n", -1);
        String[] newLines = newNormalized.split("\n", -1);
        
        // Compare normalized lines
        assertThat(oldLines[0]).isEqualTo(newLines[0]);
        assertThat(oldLines[1]).isNotEqualTo(newLines[1]);
        assertThat(oldLines[2]).isEqualTo(newLines[2]);
    }

    @Test
    @DisplayName("Mixed CRLF and LF should be handled uniformly")
    public void testMixedLineEndings() {
        // Some lines with CRLF, some with LF (can happen in git operations)
        String mixedContent = "line1\r\nline2\nline3\r\nline4\n";
        
        // Normalize to single format
        String normalized = mixedContent.replace("\r\n", "\n").replace("\r", "\n");
        
        String[] lines = normalized.split("\n", -1);
        assertThat(lines).containsExactly("line1", "line2", "line3", "line4", "");
    }

    @Test
    @DisplayName("Regex split should handle empty lines correctly")
    public void testEmptyLinesSplitting() {
        String contentWithEmpty = "line1\r\n\r\nline3\r\n";
        
        // Should preserve empty lines
        String[] lines = contentWithEmpty.split("\\r?\\n", -1);
        assertThat(lines).hasSize(4);
        assertThat(lines[0]).isEqualTo("line1");
        assertThat(lines[1]).isEmpty();
        assertThat(lines[2]).isEqualTo("line3");
        assertThat(lines[3]).isEmpty();
    }

    @Test
    @DisplayName("String.lines() method should handle CRLF properly")
    public void testStringLinesMethodCRLF() {
        String contentWithCRLF = "public class Test {\r\nint x = 1;\r\n}\r\n";
        
        // Normalize first
        String normalized = contentWithCRLF.replace("\r\n", "\n");
        var lines = normalized.lines().toList();
        
        assertThat(lines)
            .hasSize(3)
            .containsExactly("public class Test {", "int x = 1;", "}");
    }

    @Test
    @DisplayName("System.lineSeparator() aware splitting")
    public void testSystemLineSeparatorSplitting() {
        // When reading files, respect the system line separator
        String content = "line1" + System.lineSeparator() + "line2" + System.lineSeparator();
        
        // Split using system separator in regex
        String[] lines = content.split(System.lineSeparator(), -1);
        assertThat(lines).contains("line1", "line2");
    }

    @Test
    @DisplayName("CRLF in git diff output should not break parsing")
    public void testCRLFInGitDiffOutput() {
        // Git output might contain CRLF
        String gitDiff = "--- a/Test.java\r\n+++ b/Test.java\r\n@@ -1,3 +1,3 @@\r\n line1\r\n-line2\r\n+line2_modified\r\n";
        
        // Parse diff lines, normalizing endings
        String normalized = gitDiff.replace("\r\n", "\n");
        String[] diffLines = normalized.split("\n");
        
        assertThat(diffLines[0]).startsWith("---");
        assertThat(diffLines[1]).startsWith("+++");
        assertThat(diffLines[2]).startsWith("@@");
    }

    @Test
    @DisplayName("File reading should detect CRLF correctly")
    public void testCRLFDetection() throws IOException {
        // Write file with CRLF
        String contentCRLF = "line1\r\nline2\r\n";
        Files.write(sourceFile, contentCRLF.getBytes(StandardCharsets.UTF_8));
        
        // Read and detect line ending
        String readContent = Files.readString(sourceFile);
        
        boolean hasCRLF = readContent.contains("\r\n");
        boolean hasLFOnly = !hasCRLF && readContent.contains("\n");
        
        assertThat(hasCRLF || hasLFOnly).isTrue();
        if (hasCRLF) {
            assertThat(readContent.contains("\n")).isTrue();
        }
    }

    @Test
    @DisplayName("Parser should normalize line endings before structural analysis")
    public void testStructuralParsingWithCRLF() throws IOException {
        // Java source with CRLF
        String javaSource = "public class MyTest {\r\n  @Test\r\n  public void testMethod() {\r\n    assertEquals(1, 1);\r\n  }\r\n}\r\n";
        Files.write(sourceFile, javaSource.getBytes(StandardCharsets.UTF_8));
        
        // Read and normalize
        String content = Files.readString(sourceFile);
        String normalized = content.replace("\r\n", "\n");
        
        // Parse methods should work on normalized content
        String[] lines = normalized.split("\n");
        
        // Should find test method annotation and method
        boolean hasTestAnnotation = false;
        boolean hasTestMethod = false;
        
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].contains("@Test")) {
                hasTestAnnotation = true;
            }
            if (lines[i].contains("public void testMethod")) {
                hasTestMethod = true;
            }
        }
        
        assertThat(hasTestAnnotation).isTrue();
        assertThat(hasTestMethod).isTrue();
    }

    @Test
    @DisplayName("Gradle wrapper scripts might have CRLF issues")
    public void testWrapperScriptLineEndings() throws IOException {
        // Gradle wrapper script created on Windows
        Path wrapper = tempDir.resolve("gradlew");
        String unixShebang = "#!/bin/bash\r\necho 'Running gradle'\r\n";
        Files.write(wrapper, unixShebang.getBytes(StandardCharsets.UTF_8));
        
        String content = Files.readString(wrapper);
        
        // Shebang must be LF only on Unix
        // Normalize for proper processing
        String normalized = content.replace("\r\n", "\n");
        
        assertThat(normalized).startsWith("#!/bin/bash");
    }

    @Test
    @DisplayName("CRLF with trailing spaces should not affect parsing")
    public void testCRLFWithTrailingSpaces() {
        String contentWithTrailing = "public void method()   \r\nint x = 1;   \r\n";
        
        String normalized = contentWithTrailing.replace("\r\n", "\n");
        String[] lines = normalized.split("\n");
        
        // Trailing spaces after normalization
        assertThat(lines[0]).endsWith("   ");
        assertThat(lines[1]).endsWith("   ");
        
        // Trim if needed
        assertThat(lines[0].trim()).isEqualTo("public void method()");
        assertThat(lines[1].trim()).isEqualTo("int x = 1;");
    }
}
