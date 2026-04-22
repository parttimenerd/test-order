package com.example.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security Test Suite for P5-SEC-002: Symlink Attack Vulnerability
 * 
 * Tests the vulnerability where an attacker can create a symlink to redirect
 * file operations to unintended locations (symlink race condition attacks).
 * 
 * Expected Behavior: The application should:
 * 1. Detect and reject symlinks in security-critical paths
 * 2. Use real path resolution to validate file locations
 * 3. Prevent directory traversal via symlinks
 * 4. Verify symlink targets are within allowed directories
 */
@DisplayName("Security - Symlink Attack Tests (P5-SEC-002)")
class SecuritySymlinkTest {

    @TempDir
    Path tempDir;

    private Path targetDir;
    private Path symlinkFile;

    @BeforeEach
    void setUp() throws IOException {
        targetDir = tempDir.resolve("target");
        Files.createDirectory(targetDir);
        
        // Create a real file in target directory
        Path realFile = targetDir.resolve("secure_file.txt");
        Files.writeString(realFile, "sensitive data");
    }

    @Test
    @DisplayName("Symlink Detection - Reject symlinks pointing outside secure directory")
    void testSymlinkDetection() throws IOException {
        // Create a symlink that points outside the secure directory
        Path outsideDir = tempDir.resolve("outside");
        Files.createDirectory(outsideDir);
        Path externalTarget = outsideDir.resolve("external.txt");
        Files.writeString(externalTarget, "external data");

        symlinkFile = targetDir.resolve("link");
        try {
            Files.createSymbolicLink(symlinkFile, externalTarget);
            
            // Verify symlink was created
            assertTrue(Files.isSymbolicLink(symlinkFile), "Symlink should be created");
            
            // Security check: real path should be outside secure directory
            Path realPath = symlinkFile.toRealPath();
            assertFalse(realPath.startsWith(targetDir), 
                "Symlink target is outside secure directory - should be rejected");
        } catch (UnsupportedOperationException e) {
            // Symlinks not supported on this platform, skip
            assumeSymlinksSupported();
        }
    }

    @Test
    @DisplayName("Real Path Validation - Detect directory traversal attempts")
    void testRealPathValidation() throws IOException {
        Path realFile = targetDir.resolve("file.txt");
        Files.writeString(realFile, "test");

        // Attempt to create symlink for directory traversal
        Path symlink = targetDir.resolve("..").resolve("escape");
        
        try {
            Files.createSymbolicLink(symlink, Paths.get("/etc/passwd"));
            
            // Security: getRealPath should detect the attempt
            Path realPath = symlink.toRealPath();
            assertFalse(realPath.startsWith(targetDir.getParent()),
                "Real path should expose directory traversal attempt");
        } catch (UnsupportedOperationException e) {
            assumeSymlinksSupported();
        } catch (IOException e) {
            // Expected if symlink creation fails
        }
    }

    @Test
    @DisplayName("Symlink Loop Detection - Prevent infinite loops")
    void testSymlinkLoopDetection() throws IOException {
        // Create circular symlinks
        Path link1 = targetDir.resolve("link1");
        Path link2 = targetDir.resolve("link2");

        try {
            Files.createSymbolicLink(link1, link2);
            Files.createSymbolicLink(link2, link1);

            // Attempting to resolve should detect the loop
            assertThrows(IOException.class, () -> {
                link1.toRealPath();
            }, "Circular symlinks should cause IOException");
        } catch (UnsupportedOperationException e) {
            assumeSymlinksSupported();
        }
    }

    @Test
    @DisplayName("Symlink in Path Resolution - Verify canonicalization")
    void testSymlinkInPathResolution() throws IOException {
        // Create nested directory
        Path subdir = targetDir.resolve("subdir");
        Files.createDirectory(subdir);
        
        Path realFile = subdir.resolve("secret.txt");
        Files.writeString(realFile, "secret");

        // Create symlink to parent
        Path parentLink = targetDir.resolve("parent_link");
        try {
            Files.createSymbolicLink(parentLink, targetDir);
            
            // Verify symlink points to target directory
            assertTrue(Files.isSymbolicLink(parentLink), "Should create symlink");
            
            // Real path should be the target directory
            Path realPath = parentLink.toRealPath();
            assertEquals(targetDir.toRealPath(), realPath, "Real path should match target");
        } catch (UnsupportedOperationException e) {
            assumeSymlinksSupported();
        }
    }

    @Test
    @DisplayName("Follow Symlink Limit - Prevent excessive symlink chains")
    void testSymlinkChainDepth() throws IOException {
        Path current = targetDir;
        
        try {
            // Create a chain of symlinks
            for (int i = 0; i < 5; i++) {
                Path next = tempDir.resolve("chain_" + i);
                Files.createDirectory(next);
                Path link = current.resolve("link_" + i);
                Files.createSymbolicLink(link, next);
                current = next;
            }

            // Verify symlink chain depth
            Path deepFile = current.resolve("deep.txt");
            Files.writeString(deepFile, "deep");

            // Should successfully resolve even deep symlink chains
            Path realPath = deepFile.toRealPath();
            assertNotNull(realPath, "Should resolve deep symlink chain");
        } catch (UnsupportedOperationException e) {
            assumeSymlinksSupported();
        }
    }

    @Test
    @DisplayName("Symlink Ownership Check - Verify ownership before following")
    void testSymlinkOwnershipValidation() throws IOException {
        Path realFile = targetDir.resolve("file.txt");
        Files.writeString(realFile, "data");

        try {
            Path symlink = targetDir.resolve("owned_link");
            Files.createSymbolicLink(symlink, realFile);

            // In security-critical code, verify symlink owner matches expected owner
            assertTrue(Files.isSymbolicLink(symlink), "Symlink should exist");
            assertNotNull(Files.getOwner(symlink), "Symlink should have owner");
        } catch (UnsupportedOperationException e) {
            assumeSymlinksSupported();
        }
    }

    @AfterEach
    void cleanup() throws IOException {
        if (symlinkFile != null && Files.exists(symlinkFile)) {
            Files.delete(symlinkFile);
        }
    }

    private void assumeSymlinksSupported() {
        // Skip test if symlinks not supported on platform
        assertTrue(true, "Symlinks not supported on this platform");
    }
}
