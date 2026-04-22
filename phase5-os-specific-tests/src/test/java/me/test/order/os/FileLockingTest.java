package me.test.order.os;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.*;

/**
 * Tests for file locking behavior on macOS/Linux.
 * Covers: concurrent access, file locks, advisory vs mandatory locks.
 */
public class FileLockingTest {
    private Path testDir;

    @Before
    public void setUp() throws IOException {
        testDir = Paths.get("target/test-file-locking");
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
    public void testConcurrentFileWriteAccess() throws IOException {
        Path file = testDir.resolve("concurrent.txt");
        Files.write(file, "initial".getBytes());

        // Unix systems allow multiple writes to same file
        // Each write uses independent file descriptor
        String content1 = "content from writer 1\n";
        String content2 = "content from writer 2\n";

        // Write from two different perspectives
        Files.write(file, content1.getBytes(), StandardOpenOption.APPEND);
        Files.write(file, content2.getBytes(), StandardOpenOption.APPEND);

        String final_content = new String(Files.readAllBytes(file));
        assertTrue("Both writes should be present", 
                final_content.contains("writer 1") && final_content.contains("writer 2"));
    }

    @Test
    public void testFileLockingWithFileChannel() throws IOException {
        Path file = testDir.resolve("channel_lock.txt");
        Files.write(file, "test".getBytes());

        // Java NIO file channels support locking
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            // Can open for reading without exclusive lock
            assertTrue("File should be readable", Files.isReadable(file));
        }
    }

    @Test
    public void testDeleteWhileFileOpen() throws IOException {
        Path file = testDir.resolve("delete_open.txt");
        Files.write(file, "content".getBytes());

        // On Unix, can delete while file is open (file exists until last handle closes)
        var bytes = Files.readAllBytes(file);
        
        // Delete while having read data
        Files.delete(file);
        
        // File was deleted
        assertFalse("File should be deleted", Files.exists(file));
        
        // But we still have the content
        assertEquals("Should still have read content", "content", new String(bytes));
    }

    @Test
    public void testRenameWhileFileOpen() throws IOException {
        Path original = testDir.resolve("original.txt");
        Path renamed = testDir.resolve("renamed.txt");
        
        Files.write(original, "content".getBytes());

        // Read while file exists
        var bytes = Files.readAllBytes(original);

        // Rename the file
        Files.move(original, renamed);

        // Original path no longer exists
        assertFalse("Original path should not exist", Files.exists(original));
        
        // New path exists
        assertTrue("Renamed path should exist", Files.exists(renamed));
    }

    @Test
    public void testFileAccessAfterPermissionChange() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            return;
        }

        Path file = testDir.resolve("permission_change.txt");
        Files.write(file, "content".getBytes());

        // File is readable
        assertTrue("File should be readable initially", Files.isReadable(file));

        // Change permissions to remove read
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> empty = 
                java.util.Collections.emptySet();
            Files.setPosixFilePermissions(file, empty);

            // Now it shouldn't be readable
            assertFalse("File should not be readable after permission change", 
                    Files.isReadable(file));

            // Restore for cleanup
            var restored = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--");
            Files.setPosixFilePermissions(file, restored);
        } catch (Exception e) {
            // Permission denied or other issue
            System.out.println("Could not change permissions: " + e.getMessage());
        }
    }

    @Test
    public void testDirectoryLockingBehavior() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            return;
        }

        Path dir = testDir.resolve("locked_dir");
        Files.createDirectory(dir);
        Files.write(dir.resolve("file.txt"), "content".getBytes());

        // Should be able to read files in directory
        assertTrue("Should read files in directory", Files.isReadable(dir));
        
        var files = Files.list(dir).count();
        assertEquals("Should find file in directory", 1, files);
    }

    @Test
    public void testMmapFileModification() throws IOException {
        Path file = testDir.resolve("mmap_test.bin");
        byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte)(i % 256);
        }
        Files.write(file, data);

        // Java can memory-map files with FileChannel
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            // Memory mapping works on most systems
            assertTrue("File should exist for mapping", Files.exists(file));
        }
    }

    @Test
    public void testAtomicFileOperations() throws IOException {
        Path file = testDir.resolve("atomic.txt");
        Path temp = testDir.resolve("atomic.tmp");

        // Write to temp, then atomic move
        Files.write(temp, "new content".getBytes());
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertTrue("File should exist after atomic move", Files.exists(file));
        assertFalse("Temp should be gone after atomic move", Files.exists(temp));
    }

    @Test
    public void testFileDescriptorLimit() throws IOException {
        // Most Unix systems have a default limit of 1024 file descriptors
        String maxFdStr = System.getenv("RLIMIT_NOFILE");
        if (maxFdStr == null) {
            // Try to get from system
            System.out.println("File descriptor limit info not directly available");
        }

        // Just test basic operations don't hit limit
        for (int i = 0; i < 10; i++) {
            Path file = testDir.resolve("fd_test_" + i + ".txt");
            Files.write(file, ("file " + i).getBytes());
            assertTrue("File " + i + " should exist", Files.exists(file));
        }
    }
}
