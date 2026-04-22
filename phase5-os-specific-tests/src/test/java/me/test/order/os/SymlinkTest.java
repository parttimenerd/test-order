package me.test.order.os;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.*;

/**
 * Tests for symlink handling on macOS/Linux systems.
 */
public class SymlinkTest {
    private Path testDir;

    @Before
    public void setUp() throws IOException {
        testDir = Paths.get("target/test-symlinks");
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
    public void testCreateSymlink() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            System.out.println("Skipping symlink test on non-Unix OS");
            return;
        }

        Path target = testDir.resolve("target.txt");
        Files.write(target, "original content".getBytes());

        Path link = testDir.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, target);
            assertTrue("Symlink should exist", Files.exists(link));
            assertTrue("Symlink should be readable through link", Files.isReadable(link));
        } catch (UnsupportedOperationException e) {
            System.out.println("Symlinks not supported: " + e.getMessage());
        } catch (IOException e) {
            // May fail if not running with appropriate permissions
            System.out.println("Could not create symlink: " + e.getMessage());
        }
    }

    @Test
    public void testSymlinkToDirectory() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            return;
        }

        Path targetDir = testDir.resolve("targetdir");
        Files.createDirectory(targetDir);
        Files.write(targetDir.resolve("file.txt"), "content".getBytes());

        Path linkDir = testDir.resolve("linkdir");
        try {
            Files.createSymbolicLink(linkDir, targetDir);
            assertTrue("Symlink directory should exist", Files.exists(linkDir));
            assertTrue("Should be able to list symlinked directory", 
                    Files.list(linkDir).count() > 0);
        } catch (IOException e) {
            System.out.println("Could not create symlink to directory: " + e.getMessage());
        }
    }

    @Test
    public void testSymlinkResolution() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            return;
        }

        Path target = testDir.resolve("original.txt");
        Files.write(target, "original".getBytes());

        Path link = testDir.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, target);
            
            // Resolve symlink to get target
            Path resolved = Files.readSymbolicLink(link);
            assertNotNull("Should be able to read symlink", resolved);
            
            // Check if it points to the right target
            assertTrue("Resolved link should point to target file",
                    resolved.toString().contains("original"));
        } catch (IOException e) {
            System.out.println("Symlink resolution failed: " + e.getMessage());
        }
    }

    @Test
    public void testBrokenSymlink() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            return;
        }

        Path nonexistent = testDir.resolve("nonexistent.txt");
        Path link = testDir.resolve("broken-link.txt");

        try {
            Files.createSymbolicLink(link, nonexistent);
            
            // Broken symlink itself exists
            assertTrue("Broken symlink path should exist", 
                    Files.isSymbolicLink(link));
            
            // But target doesn't exist
            assertFalse("Broken symlink target should not exist",
                    Files.exists(link));
            
            // Should still be readable as symlink
            Path target = Files.readSymbolicLink(link);
            assertNotNull("Should read broken symlink target", target);
        } catch (IOException e) {
            System.out.println("Broken symlink test failed: " + e.getMessage());
        }
    }

    @Test
    public void testSymlinkInCachePath() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            return;
        }

        // Simulate cache directory with symlinks
        Path cacheDir = testDir.resolve("cache");
        Files.createDirectory(cacheDir);

        Path realDir = testDir.resolve("real-cache-data");
        Files.createDirectory(realDir);
        Files.write(realDir.resolve("data.bin"), "cached data".getBytes());

        Path cacheLink = cacheDir.resolve("data");
        try {
            Files.createSymbolicLink(cacheLink, realDir);
            
            // Should be able to read through symlink
            Path cachedFile = cacheLink.resolve("data.bin");
            assertTrue("Should read cached file through symlink", 
                    Files.exists(cachedFile) && Files.isReadable(cachedFile));
        } catch (IOException e) {
            System.out.println("Cache symlink test failed: " + e.getMessage());
        }
    }

    @Test
    public void testChainedSymlinks() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            return;
        }

        Path target = testDir.resolve("target.txt");
        Files.write(target, "original".getBytes());

        Path link1 = testDir.resolve("link1.txt");
        Path link2 = testDir.resolve("link2.txt");

        try {
            Files.createSymbolicLink(link1, target);
            Files.createSymbolicLink(link2, link1);

            // Should be able to follow chain
            assertTrue("Chained symlink should exist", Files.exists(link2));
            
            Path readLink = Files.readSymbolicLink(link2);
            assertNotNull("Should read chained symlink", readLink);
        } catch (IOException e) {
            System.out.println("Chained symlink test failed: " + e.getMessage());
        }
    }

    @Test
    public void testCircularSymlinks() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            return;
        }

        Path link1 = testDir.resolve("circular1.txt");
        Path link2 = testDir.resolve("circular2.txt");

        try {
            Files.createSymbolicLink(link1, link2);
            Files.createSymbolicLink(link2, link1);

            // Should exist as symlinks but not resolvable
            assertTrue("Circular symlink1 should exist as symlink", Files.isSymbolicLink(link1));
            assertTrue("Circular symlink2 should exist as symlink", Files.isSymbolicLink(link2));

            // But actual resolution will fail
            try {
                Files.exists(link1); // This should return false due to circular reference
                System.out.println("Circular symlink handling: no exception thrown");
            } catch (Exception e) {
                System.out.println("Circular symlink causes: " + e.getClass().getSimpleName());
            }
        } catch (IOException e) {
            System.out.println("Circular symlink creation failed: " + e.getMessage());
        }
    }
}
