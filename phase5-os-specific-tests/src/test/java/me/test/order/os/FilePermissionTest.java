package me.test.order.os;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Tests for file permission handling on macOS/Linux systems.
 * Covers: 755 (rwxr-xr-x), 644 (rw-r--r--), 600 (rw-------), etc.
 */
public class FilePermissionTest {
    private Path testDir;

    @Before
    public void setUp() throws IOException {
        testDir = Paths.get("target/test-permissions");
        Files.createDirectories(testDir);
    }

    @After
    public void tearDown() throws IOException {
        cleanupTestDir();
    }

    @Test
    public void testExecutableFilePermission() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            System.out.println("Skipping on non-Unix OS: " + osName);
            return;
        }

        Path execFile = testDir.resolve("executable.sh");
        Files.write(execFile, "#!/bin/bash\necho 'test'".getBytes());

        // Set 755 permissions (rwxr-xr-x)
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(execFile, perms);

        assertTrue("File should be executable", Files.isExecutable(execFile));
        assertTrue("File should be readable", Files.isReadable(execFile));
        assertTrue("File should be writable", Files.isWritable(execFile));
    }

    @Test
    public void testReadOnlyFilePermission() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            return;
        }

        Path readOnlyFile = testDir.resolve("readonly.txt");
        Files.write(readOnlyFile, "content".getBytes());

        // Set 444 permissions (r--r--r--)
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("r--r--r--");
        Files.setPosixFilePermissions(readOnlyFile, perms);

        assertTrue("File should be readable", Files.isReadable(readOnlyFile));
        assertFalse("File should not be writable", Files.isWritable(readOnlyFile));
    }

    @Test
    public void testNoReadPermission() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            return;
        }

        Path noReadFile = testDir.resolve("noread.txt");
        Files.write(noReadFile, "secret".getBytes());

        // Set 000 permissions (----------)
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("---------");
        Files.setPosixFilePermissions(noReadFile, perms);

        assertFalse("File should not be readable", Files.isReadable(noReadFile));
        assertFalse("File should not be writable", Files.isWritable(noReadFile));
    }

    @Test
    public void testDirectoryPermission755() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            return;
        }

        Path dir = testDir.resolve("dir755");
        Files.createDirectory(dir);

        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(dir, perms);

        assertTrue("Directory should be readable", Files.isReadable(dir));
        assertTrue("Directory should be executable (traversable)", Files.isExecutable(dir));
    }

    @Test
    public void testUmaskAffectsNewFiles() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            return;
        }

        Path newFile = testDir.resolve("umask-test.txt");
        Files.write(newFile, "test".getBytes());

        // File should have been created with umask applied
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(newFile);
        assertNotNull("Permissions should be readable", perms);
        assertTrue("File should be readable by owner", perms.contains(PosixFilePermission.OWNER_READ));
    }

    @Test
    public void testPermissionDeniedError() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("linux") && !osName.contains("mac")) {
            return;
        }

        Path restrictedDir = testDir.resolve("restricted");
        Files.createDirectory(restrictedDir);

        // Create a file inside first
        Files.write(restrictedDir.resolve("file.txt"), "content".getBytes());

        // Remove all permissions from directory
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("---------");
        Files.setPosixFilePermissions(restrictedDir, perms);

        try {
            // Should fail to list directory contents
            Files.list(restrictedDir).close();
            fail("Should throw PermissionDenied exception");
        } catch (Exception e) {
            assertTrue("Should mention permission denied", 
                    e.getMessage() != null && 
                    (e.getMessage().contains("Permission denied") || 
                     e.getMessage().contains("permission")));
        } finally {
            // Restore permissions for cleanup
            Set<PosixFilePermission> restorePerms = PosixFilePermissions.fromString("rwxr-xr-x");
            Files.setPosixFilePermissions(restrictedDir, restorePerms);
        }
    }

    private void cleanupTestDir() throws IOException {
        if (Files.exists(testDir)) {
            // Restore permissions for cleanup
            Files.walk(testDir)
                    .sorted((a, b) -> b.compareTo(a)) // reverse order
                    .forEach(path -> {
                        try {
                            if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
                                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
                                Files.setPosixFilePermissions(path, perms);
                            } else {
                                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-r--r--");
                                Files.setPosixFilePermissions(path, perms);
                            }
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            // Ignore cleanup errors
                        }
                    });
        }
    }
}
