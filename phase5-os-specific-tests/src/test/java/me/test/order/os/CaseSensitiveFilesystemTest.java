package me.test.order.os;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * Tests for case-sensitive filesystem handling.
 * Covers: Linux (case-sensitive), macOS (case-insensitive by default).
 */
public class CaseSensitiveFilesystemTest {
    private Path testDir;

    @Before
    public void setUp() throws IOException {
        testDir = Paths.get("target/test-case-sensitive");
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
    public void testCreateFilesWithDifferentCase() throws IOException {
        Path file1 = testDir.resolve("test.txt");
        Path file2 = testDir.resolve("TEST.txt");

        Files.write(file1, "lowercase".getBytes());

        boolean isLinux = System.getProperty("os.name").toLowerCase().contains("linux");
        boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");

        if (isLinux) {
            // Linux is case-sensitive, should allow both
            Files.write(file2, "uppercase".getBytes());
            assertTrue("Both files should exist on Linux", Files.exists(file1) && Files.exists(file2));
        } else if (isMac) {
            // macOS is case-insensitive by default
            // Attempting to write file2 may overwrite file1 or fail
            try {
                Files.write(file2, "uppercase".getBytes());
                // On case-insensitive macOS, this might succeed but map to same file
                System.out.println("macOS created second file with different case");
            } catch (IOException e) {
                System.out.println("macOS rejected second file with different case: " + e.getMessage());
            }
        }
    }

    @Test
    public void testCaseSensitivePackageNames() throws IOException {
        // Create files that differ only in case
        String osName = System.getProperty("os.name").toLowerCase();

        Path dir1 = testDir.resolve("Package");
        Path dir2 = testDir.resolve("package");
        
        Files.createDirectory(dir1);

        boolean isLinux = osName.contains("linux");
        
        if (isLinux) {
            // Should be able to create both on Linux
            Files.createDirectory(dir2);
            assertTrue("Linux should have both directories", 
                    Files.exists(dir1) && Files.exists(dir2));
        } else {
            // macOS might not allow this
            try {
                Files.createDirectory(dir2);
                System.out.println("macOS allowed directory with different case");
            } catch (IOException e) {
                System.out.println("macOS rejected directory with different case");
            }
        }
    }

    @Test
    public void testFileSystemCaseSensitivityBehavior() throws IOException {
        Path file = testDir.resolve("MyFile.txt");
        Files.write(file, "content".getBytes());

        String osName = System.getProperty("os.name").toLowerCase();

        // Check if filesystem is case-sensitive
        Path variantCase = testDir.resolve("myfile.txt");
        boolean caseSensitive = !Files.exists(variantCase);

        if (osName.contains("linux")) {
            assertTrue("Linux should be case-sensitive", caseSensitive);
        } else if (osName.contains("mac")) {
            assertFalse("macOS default HFS+ is case-insensitive", caseSensitive);
        }

        System.out.println("Filesystem case sensitivity: " + (caseSensitive ? "sensitive" : "insensitive"));
    }

    @Test
    public void testClassPathWithMixedCase() throws IOException {
        // Create a Java class file reference with different cases
        Path dir = testDir.resolve("src/main/java");
        Files.createDirectories(dir);

        Path class1 = dir.resolve("TestClass.java");
        Files.write(class1, "public class TestClass {}".getBytes());

        String osName = System.getProperty("os.name").toLowerCase();
        boolean isLinux = osName.contains("linux");

        if (isLinux) {
            // On Linux, TestClass.java and testclass.java are different
            Path class2 = dir.resolve("testclass.java");
            Files.write(class2, "public class testclass {}".getBytes());
            
            assertTrue("Both classes should exist on Linux",
                    Files.exists(class1) && Files.exists(class2));
        }
    }

    @Test
    public void testCaseSensitiveFileSearch() throws IOException {
        Path target = testDir.resolve("TargetFile.txt");
        Files.write(target, "target".getBytes());

        // Try to find with different case
        Path searchPath = testDir.resolve("targetfile.txt");

        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("linux")) {
            // Should not find on case-sensitive filesystem
            assertFalse("Linux should not find case-variant", Files.exists(searchPath));
        } else if (osName.contains("mac")) {
            // May or may not find on case-insensitive filesystem
            System.out.println("macOS case-insensitive search result: " + Files.exists(searchPath));
        }
    }
}
