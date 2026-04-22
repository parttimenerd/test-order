package me.test.order.os;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * Tests for line ending handling on macOS/Linux.
 * Covers: LF vs CRLF, line separator detection, cross-platform compatibility.
 */
public class LineEndingTest {
    private Path testDir;

    @Before
    public void setUp() throws IOException {
        testDir = Paths.get("target/test-line-endings");
        Files.createDirectories(testDir);
    }

    @After
    public void tearDown() throws IOException {
        Files.walk(testDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // ignore
                    }
                });
    }

    @Test
    public void testDefaultLineSeparator() {
        String lineSep = System.getProperty("line.separator");
        String osName = System.getProperty("os.name").toLowerCase();

        if (osName.contains("linux") || osName.contains("mac")) {
            assertEquals("Unix systems should use LF", "\n", lineSep);
        }
    }

    @Test
    public void testLFLineEndings() throws IOException {
        Path file = testDir.resolve("lf.txt");
        
        // Write with explicit LF
        String content = "line1\nline2\nline3\n";
        Files.write(file, content.getBytes());

        String read = new String(Files.readAllBytes(file));
        assertEquals("Should preserve LF line endings", content, read);
    }

    @Test
    public void testCRLFLineEndings() throws IOException {
        Path file = testDir.resolve("crlf.txt");
        
        // Write with explicit CRLF (Windows style)
        String content = "line1\r\nline2\r\nline3\r\n";
        Files.write(file, content.getBytes());

        String read = new String(Files.readAllBytes(file));
        assertEquals("Should preserve CRLF when written", content, read);
    }

    @Test
    public void testMixedLineEndings() throws IOException {
        Path file = testDir.resolve("mixed.txt");
        
        // Mix of LF and CRLF
        String content = "line1\nline2\r\nline3\r";
        Files.write(file, content.getBytes());

        String read = new String(Files.readAllBytes(file));
        assertEquals("Should preserve mixed line endings", content, read);
    }

    @Test
    public void testLineCountWithDifferentEndings() throws IOException {
        Path lfFile = testDir.resolve("lf_lines.txt");
        Path crlfFile = testDir.resolve("crlf_lines.txt");
        
        String lfContent = "line1\nline2\nline3\n";
        String crlfContent = "line1\r\nline2\r\nline3\r\n";
        
        Files.write(lfFile, lfContent.getBytes());
        Files.write(crlfFile, crlfContent.getBytes());

        long lfLines = Files.lines(lfFile).count();
        long crlfLines = Files.lines(crlfFile).count();

        assertEquals("LF file should have 3 lines", 3, lfLines);
        assertEquals("CRLF file should have 3 lines", 3, crlfLines);
    }

    @Test
    public void testLineEncodingConsistency() throws IOException {
        Path file = testDir.resolve("encoding.txt");
        
        String[] lines = {"hello", "world", "test"};
        String content = String.join("\n", lines) + "\n";
        
        Files.write(file, content.getBytes("UTF-8"));
        
        String read = new String(Files.readAllBytes(file), "UTF-8");
        assertEquals("Should preserve UTF-8 with LF", content, read);
    }

    @Test
    public void testEmptyLinesHandling() throws IOException {
        Path file = testDir.resolve("empty_lines.txt");
        
        String content = "line1\n\n\nline2\n";
        Files.write(file, content.getBytes());

        long lineCount = Files.lines(file).count();
        assertEquals("Should count empty lines", 3, lineCount);
    }

    @Test
    public void testNoTrailingNewline() throws IOException {
        Path file = testDir.resolve("no_trailing.txt");
        
        String content = "line1\nline2\nline3"; // No trailing newline
        Files.write(file, content.getBytes());

        String read = new String(Files.readAllBytes(file));
        assertEquals("Should preserve lack of trailing newline", content, read);

        long lineCount = Files.lines(file).count();
        assertEquals("Should count lines correctly without trailing newline", 3, lineCount);
    }

    @Test
    public void testCarriageReturnOnlyLineEndings() throws IOException {
        Path file = testDir.resolve("cr_only.txt");
        
        // Old Mac style (rare nowadays)
        String content = "line1\rline2\rline3\r";
        Files.write(file, content.getBytes());

        String read = new String(Files.readAllBytes(file));
        assertEquals("Should preserve CR-only endings", content, read);
    }

    @Test
    public void testSystemLineSeparatorInCode() throws IOException {
        Path file = testDir.resolve("sys_sep.txt");
        
        String lineSep = System.getProperty("line.separator");
        String content = "line1" + lineSep + "line2" + lineSep + "line3" + lineSep;
        
        Files.write(file, content.getBytes());

        String read = new String(Files.readAllBytes(file));
        assertEquals("Should use system line separator", content, read);

        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("linux") || osName.contains("mac")) {
            assertTrue("Unix should use LF", content.contains("\n"));
            assertFalse("Unix should not use CRLF", content.contains("\r\n"));
        }
    }
}
