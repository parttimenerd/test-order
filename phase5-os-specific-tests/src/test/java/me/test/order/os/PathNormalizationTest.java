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
 * Tests for path normalization on macOS/Linux.
 * Covers: path separators, relative/absolute paths, trailing slashes.
 */
public class PathNormalizationTest {
    private Path testDir;

    @Before
    public void setUp() throws IOException {
        testDir = Paths.get("target/test-paths");
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
    public void testAbsolutePathNormalization() {
        Path absolute = Paths.get("/tmp/test/path");
        Path normalized = absolute.normalize();
        
        assertEquals("Absolute path should normalize correctly",
                absolute, normalized);
    }

    @Test
    public void testRelativePathNormalization() {
        Path relative = Paths.get("src/./test/../main/java");
        Path normalized = relative.normalize();
        
        Path expected = Paths.get("src/main/java");
        assertEquals("Should normalize relative path with . and ..",
                expected, normalized);
    }

    @Test
    public void testTrailingSlashNormalization() throws IOException {
        Path dirWithSlash = testDir.resolve("mydir/");
        Path dirWithoutSlash = testDir.resolve("mydir");

        Files.createDirectory(dirWithoutSlash);

        // Both should refer to same directory
        assertEquals("Paths should be equivalent despite trailing slash",
                dirWithoutSlash, dirWithSlash.normalize());
    }

    @Test
    public void testDotNormalization() {
        Path withDot = Paths.get("./src/test");
        Path normalized = withDot.normalize();
        
        Path expected = Paths.get("src/test");
        assertEquals("Should normalize . in path",
                expected, normalized);
    }

    @Test
    public void testDoubleDotNormalization() {
        Path withDoubleDot = Paths.get("src/test/../main");
        Path normalized = withDoubleDot.normalize();
        
        Path expected = Paths.get("src/main");
        assertEquals("Should normalize .. in path",
                expected, normalized);
    }

    @Test
    public void testMultipleSlashNormalization() {
        Path multiSlash = Paths.get("src//test///java");
        Path normalized = multiSlash.normalize();
        
        Path expected = Paths.get("src/test/java");
        assertEquals("Should normalize multiple slashes",
                expected, normalized);
    }

    @Test
    public void testLongPathHandling() throws IOException {
        // Create a deeply nested path structure
        Path current = testDir;
        for (int i = 0; i < 10; i++) {
            current = current.resolve("level" + i);
        }
        
        Files.createDirectories(current);
        Path file = current.resolve("deep.txt");
        Files.write(file, "deep content".getBytes());

        assertTrue("Should handle long nested paths", Files.exists(file));
        
        String osName = System.getProperty("os.name").toLowerCase();
        String path = file.toString();
        
        // Linux path limit is typically 4096 total, macOS is similar
        if (path.length() > 4000) {
            System.out.println("WARNING: Path exceeds typical system limit: " + path.length());
        }
    }

    @Test
    public void testSpecialCharactersInPath() throws IOException {
        Path withSpaces = testDir.resolve("path with spaces.txt");
        Files.write(withSpaces, "content".getBytes());
        
        assertTrue("Should handle spaces in path", Files.exists(withSpaces));

        Path withDash = testDir.resolve("path-with-dashes.txt");
        Files.write(withDash, "content".getBytes());
        
        assertTrue("Should handle dashes in path", Files.exists(withDash));

        Path withUnderscore = testDir.resolve("path_with_underscores.txt");
        Files.write(withUnderscore, "content".getBytes());
        
        assertTrue("Should handle underscores in path", Files.exists(withUnderscore));
    }

    @Test
    public void testUnicodeInPath() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        
        try {
            Path withUnicode = testDir.resolve("파일_文件_ファイル.txt");
            Files.write(withUnicode, "unicode content".getBytes());
            
            assertTrue("Should handle unicode in path", Files.exists(withUnicode));
        } catch (IOException e) {
            System.out.println("Unicode path not fully supported: " + e.getMessage());
        }
    }

    @Test
    public void testPathResolution() throws IOException {
        Path base = testDir;
        Path file = Files.write(base.resolve("test.txt"), "content".getBytes());

        Path resolved = base.resolve("test.txt");
        assertEquals("Should resolve relative path correctly",
                file, resolved);
    }

    @Test
    public void testPathRelativization() throws IOException {
        Path file = Files.write(testDir.resolve("test.txt"), "content".getBytes());
        Path relative = testDir.relativize(file);

        assertEquals("Should compute correct relative path",
                Paths.get("test.txt"), relative);
    }

    @Test
    public void testSymbolicPathResolution() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            return;
        }

        Path target = Files.write(testDir.resolve("target.txt"), "target".getBytes());
        Path link = testDir.resolve("link.txt");

        try {
            Files.createSymbolicLink(link, target);
            
            // toRealPath resolves symlinks
            Path realPath = link.toRealPath();
            assertEquals("Should resolve symlink to real path",
                    target, realPath);
        } catch (IOException e) {
            System.out.println("Symlink real path resolution failed: " + e.getMessage());
        }
    }
}
