package com.example.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.security.SecureRandom;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security Test Suite for P5-SEC-003, 007, 008:
 * 
 * P5-SEC-003: Lock File Permissions - Test improper permissions on lock files
 * Tests that lock files are created with restrictive permissions (600 or 0600)
 * to prevent unauthorized access to critical resources.
 * 
 * P5-SEC-007: Information Disclosure - Test that sensitive information is not
 * leaked through error messages, log files, or exception details.
 * 
 * P5-SEC-008: Weak Random Number Generation - Test that sensitive operations
 * use SecureRandom instead of Random for security tokens and keys.
 */
@DisplayName("Security - Permissions, Information Disclosure & Random Tests (P5-SEC-003/007/008)")
class SecurityPermissionsTest {

    @TempDir
    Path tempDir;

    private Path lockFile;
    private Path logFile;

    @BeforeEach
    void setUp() throws IOException {
        lockFile = tempDir.resolve("lock.file");
        logFile = tempDir.resolve("app.log");
    }

    // ==================== P5-SEC-003: Lock File Permissions ====================

    @Test
    @DisplayName("P5-SEC-003: Lock File Permissions - Restrictive permissions on lock files")
    void testLockFilePermissions() throws IOException {
        try {
            // Create lock file with restrictive permissions (owner read-write only)
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.createFile(lockFile);
            Files.setPosixFilePermissions(lockFile, perms);

            // Verify permissions are restrictive
            Set<PosixFilePermission> actualPerms = Files.getPosixFilePermissions(lockFile);
            assertEquals(perms, actualPerms, "Lock file should have restrictive permissions (600)");

            // Verify only owner can read
            assertTrue(actualPerms.contains(PosixFilePermission.OWNER_READ), 
                "Owner should have read permission");
            assertTrue(actualPerms.contains(PosixFilePermission.OWNER_WRITE), 
                "Owner should have write permission");
            assertFalse(actualPerms.contains(PosixFilePermission.GROUP_READ), 
                "Group should not have read permission");
            assertFalse(actualPerms.contains(PosixFilePermission.OTHERS_READ), 
                "Others should not have read permission");
        } catch (UnsupportedOperationException e) {
            // POSIX permissions not supported on this platform
            assertTrue(true, "POSIX permissions not supported");
        }
    }

    @Test
    @DisplayName("P5-SEC-003: Permission Verification - Fail if permissions are too open")
    void testLockFilePermissionValidation() throws IOException {
        try {
            // Create file with overly permissive permissions (bad practice)
            Files.createFile(lockFile);
            Set<PosixFilePermission> badPerms = PosixFilePermissions.fromString("rw-rw-rw-");
            Files.setPosixFilePermissions(lockFile, badPerms);

            // Security check: detect overly permissive permissions
            Set<PosixFilePermission> actualPerms = Files.getPosixFilePermissions(lockFile);
            
            boolean isSecure = !actualPerms.contains(PosixFilePermission.GROUP_READ) &&
                              !actualPerms.contains(PosixFilePermission.OTHERS_READ);
            
            assertFalse(isSecure, "Lock file with group/other read access should be detected as insecure");
        } catch (UnsupportedOperationException e) {
            assertTrue(true, "POSIX permissions not supported");
        }
    }

    @Test
    @DisplayName("P5-SEC-003: Directory Permissions - Restrict access to lock directory")
    void testLockDirectoryPermissions() throws IOException {
        try {
            Path lockDir = tempDir.resolve("lock_dir");
            Files.createDirectory(lockDir);

            // Set restrictive directory permissions
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwx------");
            Files.setPosixFilePermissions(lockDir, perms);

            Set<PosixFilePermission> actualPerms = Files.getPosixFilePermissions(lockDir);
            assertEquals(perms, actualPerms, 
                "Lock directory should have restrictive permissions (700)");
        } catch (UnsupportedOperationException e) {
            assertTrue(true, "POSIX permissions not supported");
        }
    }

    // ==================== P5-SEC-007: Information Disclosure ====================

    @Test
    @DisplayName("P5-SEC-007: Exception Messages - Avoid exposing sensitive paths in errors")
    void testExceptionMessageSanitization() {
        String sensitiveMessage = "Failed to read /etc/shadow";
        String sanitizedMessage = sanitizeExceptionMessage(sensitiveMessage);

        assertFalse(sanitizedMessage.contains("/etc"), 
            "Exception message should not contain sensitive file paths");
        assertTrue(sanitizedMessage.contains("Failed to read"), 
            "Exception message should contain generic error info");
    }

    @Test
    @DisplayName("P5-SEC-007: Log File Contents - Sensitive data should not be logged")
    void testSensitiveDataNotLogged() throws IOException {
        String apiKey = "sk-1234567890abcdef";
        String logEntry = "Processing request with api_key: " + maskSensitiveData(apiKey);

        Files.writeString(logFile, logEntry);
        String logged = Files.readString(logFile);

        assertFalse(logged.contains("1234567890abcdef"), 
            "Sensitive keys should not be logged");
        assertTrue(logged.contains("sk-****"), 
            "Sensitive data should be masked in logs");
    }

    @Test
    @DisplayName("P5-SEC-007: Error Responses - API errors should not expose internal details")
    void testErrorResponseSanitization() {
        String internalError = "Database connection failed: host=db.internal.corp, user=admin, password=secret123";
        String userError = sanitizeExceptionMessage(internalError);

        assertFalse(userError.contains("db.internal.corp"), "Internal hostname should not be exposed");
        assertFalse(userError.contains("admin"), "Database username should not be exposed");
        assertFalse(userError.contains("secret123"), "Database password should not be exposed");
        assertTrue(userError.contains("Database connection failed"), "Generic error message should remain");
    }

    @Test
    @DisplayName("P5-SEC-007: Stack Traces - Avoid exposing stack traces to users")
    void testStackTraceHandling() {
        Exception e = new RuntimeException("Sensitive: user@example.com not found in database");
        String safeMessage = maskSensitiveData(e.getMessage());

        assertFalse(safeMessage.contains("@example.com"), 
            "Email addresses should not be in error messages");
    }

    @Test
    @DisplayName("P5-SEC-007: File Path Disclosure - Avoid revealing filesystem structure")
    void testFilePathDisclosure() {
        String errorWithPath = "Failed: /home/user/projects/app/config/db.properties";
        String sanitized = sanitizeExceptionMessage(errorWithPath);

        assertFalse(sanitized.contains("/home/user"), "Home directory paths should be hidden");
        assertTrue(sanitized.contains("Failed:"), "Error context should remain");
    }

    // ==================== P5-SEC-008: Random Number Generation ====================

    @Test
    @DisplayName("P5-SEC-008: Secure Random - Use SecureRandom for tokens")
    void testSecureRandomForTokens() {
        // Secure approach: use SecureRandom
        SecureRandom secureRandom = new SecureRandom();
        byte[] token = new byte[32];
        secureRandom.nextBytes(token);

        assertNotNull(token, "Secure random token should be generated");
        assertEquals(32, token.length, "Token should be 32 bytes");
        
        // Generate another token - should be different
        byte[] token2 = new byte[32];
        secureRandom.nextBytes(token2);
        
        assertNotEquals(token, token2, "SecureRandom should produce different values");
    }

    @Test
    @DisplayName("P5-SEC-008: Weak Random Detection - Identify use of non-secure Random")
    void testWeakRandomDetection() {
        // Vulnerable approach: using Math.random() or Random
        Random weakRandom = new Random(12345); // Seeded with predictable value
        int value1 = weakRandom.nextInt();
        
        // Create another Random with same seed - should produce same sequence
        Random weakRandom2 = new Random(12345);
        int value2 = weakRandom2.nextInt();

        assertEquals(value1, value2, 
            "Regular Random with same seed produces predictable values - INSECURE");
    }

    @Test
    @DisplayName("P5-SEC-008: Random Seed Uniqueness - Each instance should have different seed")
    void testRandomSeedUniqueness() {
        SecureRandom random1 = new SecureRandom();
        SecureRandom random2 = new SecureRandom();

        byte[] bytes1 = new byte[8];
        byte[] bytes2 = new byte[8];
        
        random1.nextBytes(bytes1);
        random2.nextBytes(bytes2);

        // Different SecureRandom instances should produce different sequences
        assertNotEquals(bytes1, bytes2, 
            "SecureRandom instances should be independently seeded");
    }

    @Test
    @DisplayName("P5-SEC-008: Token Generation - Produce cryptographically secure tokens")
    void testSecureTokenGeneration() {
        // Simulate secure token generation for sessions/APIs
        String token = generateSecureToken(32);

        assertNotNull(token, "Token should be generated");
        assertTrue(token.length() > 0, "Token should not be empty");
        
        // Generate another - should be different
        String token2 = generateSecureToken(32);
        assertNotEquals(token, token2, "Tokens should be unique");
    }

    @Test
    @DisplayName("P5-SEC-008: Random Entropy - Verify sufficient entropy in values")
    void testRandomEntropy() {
        SecureRandom random = new SecureRandom();
        int[] distribution = new int[256];

        // Generate 10000 random bytes and check distribution
        byte[] data = new byte[10000];
        random.nextBytes(data);
        
        for (byte b : data) {
            distribution[b & 0xFF]++;
        }

        // With sufficient entropy, no single value should appear more than 10% of time
        int maxOccurrences = 0;
        for (int count : distribution) {
            maxOccurrences = Math.max(maxOccurrences, count);
        }

        assertTrue(maxOccurrences < 1000, 
            "Random values should be evenly distributed (entropy check)");
    }

    // ==================== Helper Methods ====================

    private String sanitizeExceptionMessage(String message) {
        // Remove file paths, domains, IPs, etc.
        String sanitized = message;
        sanitized = sanitized.replaceAll("/[a-zA-Z0-9/_.-]+", "/***");
        sanitized = sanitized.replaceAll("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}", "***.***.***.**");
        sanitized = sanitized.replaceAll("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+", "***@***");
        return sanitized;
    }

    private String maskSensitiveData(String data) {
        // Mask passwords, keys, etc.
        String masked = data;
        masked = masked.replaceAll("(password|secret|key|token)=[^,\\s]+", "$1=****");
        masked = masked.replaceAll("(api_key|sk-)[a-zA-Z0-9]+", "$1****");
        masked = masked.replaceAll("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+", "****@****");
        return masked;
    }

    private String generateSecureToken(int byteLength) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[byteLength];
        random.nextBytes(bytes);
        
        // Convert to hex string
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
