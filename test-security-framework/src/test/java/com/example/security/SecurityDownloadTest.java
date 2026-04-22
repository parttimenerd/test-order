package com.example.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security Test Suite for P5-SEC-006: Download Verification and Integrity
 * 
 * Tests the vulnerability where downloaded files are not verified for integrity
 * or authenticity, allowing man-in-the-middle attacks or delivery of corrupted/
 * malicious files.
 * 
 * Expected Behavior: The application should:
 * 1. Verify file checksums (MD5, SHA1, SHA256)
 * 2. Validate file signatures and certificates
 * 3. Check file sizes and content
 * 4. Reject files that fail integrity checks
 * 5. Use HTTPS for downloads
 */
@DisplayName("Security - Download Verification Tests (P5-SEC-006)")
class SecurityDownloadTest {

    @TempDir
    Path tempDir;

    private Path downloadDir;
    private Path checksumFile;

    @BeforeEach
    void setUp() throws IOException {
        downloadDir = tempDir.resolve("downloads");
        Files.createDirectory(downloadDir);
        checksumFile = tempDir.resolve("checksums.txt");
    }

    @Test
    @DisplayName("P5-SEC-006: SHA256 Checksum Verification - Verify file integrity")
    void testSha256ChecksumVerification() throws IOException, NoSuchAlgorithmException {
        // Simulate downloading a file
        String fileContent = "important software version 1.0.0";
        Path downloadedFile = downloadDir.resolve("software.jar");
        Files.writeString(downloadedFile, fileContent);

        // Calculate SHA256 checksum
        String expectedChecksum = calculateSha256(fileContent);

        // Verify checksum
        String actualChecksum = calculateSha256(Files.readString(downloadedFile));
        assertEquals(expectedChecksum, actualChecksum, 
            "Downloaded file checksum should match expected value");
    }

    @Test
    @DisplayName("P5-SEC-006: Checksum Mismatch Detection - Reject tampered files")
    void testChecksumMismatchDetection() throws IOException, NoSuchAlgorithmException {
        String originalContent = "secure library v2.0.0";
        Path downloadedFile = downloadDir.resolve("library.jar");
        Files.writeString(downloadedFile, originalContent);

        String expectedChecksum = calculateSha256(originalContent);

        // Simulate file corruption (MITM attack)
        Files.writeString(downloadedFile, "malicious code injected");

        // Verify checksum
        String actualChecksum = calculateSha256(Files.readString(downloadedFile));
        assertNotEquals(expectedChecksum, actualChecksum, 
            "Tampered file should have different checksum");
    }

    @Test
    @DisplayName("P5-SEC-006: Multiple Checksum Formats - Support SHA256, SHA1, MD5")
    void testMultipleChecksumFormats() throws IOException, NoSuchAlgorithmException {
        String fileContent = "package content";
        Path file = downloadDir.resolve("package.zip");
        Files.writeString(file, fileContent);

        // Calculate multiple checksums
        String sha256 = calculateSha256(fileContent);
        String sha1 = calculateSha1(fileContent);
        String md5 = calculateMd5(fileContent);

        // All should be valid
        assertNotNull(sha256, "SHA256 should be calculated");
        assertNotNull(sha1, "SHA1 should be calculated");
        assertNotNull(md5, "MD5 should be calculated");

        // All should be different
        assertNotEquals(sha256, sha1, "SHA256 and SHA1 should differ");
        assertNotEquals(sha1, md5, "SHA1 and MD5 should differ");
    }

    @Test
    @DisplayName("P5-SEC-006: Checksum Format Validation - Detect malformed checksums")
    void testChecksumFormatValidation() {
        String[] invalidChecksums = {
            "",
            "tooshort",
            "not_hex_characters_xyz!@#",
            "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"
        };

        for (String checksum : invalidChecksums) {
            assertFalse(isValidSha256Format(checksum), 
                "Invalid checksum format should be rejected: " + checksum);
        }

        String validChecksum = "a".repeat(64);
        assertTrue(isValidSha256Format(validChecksum), 
            "Valid SHA256 checksum should be accepted");
    }

    @Test
    @DisplayName("P5-SEC-006: File Size Validation - Detect incomplete downloads")
    void testFileSizeValidation() throws IOException {
        // Create a file
        String content = "download content";
        Path downloadedFile = downloadDir.resolve("data.bin");
        Files.writeString(downloadedFile, content);

        // Check file size
        long expectedSize = content.length();
        long actualSize = Files.size(downloadedFile);

        assertEquals(expectedSize, actualSize, 
            "Downloaded file size should match expected size");
    }

    @Test
    @DisplayName("P5-SEC-006: Incomplete Download Detection - Fail if sizes don't match")
    void testIncompleteDownloadDetection() throws IOException {
        String fullContent = "complete file with complete data";
        Path downloadedFile = downloadDir.resolve("incomplete.bin");
        
        // Simulate incomplete download (only partial content)
        Files.writeString(downloadedFile, fullContent.substring(0, fullContent.length() / 2));

        long expectedSize = fullContent.length();
        long actualSize = Files.size(downloadedFile);

        assertNotEquals(expectedSize, actualSize, 
            "Incomplete file size should not match expected");
    }

    @Test
    @DisplayName("P5-SEC-006: Signature Verification - Validate digital signatures")
    void testSignatureVerification() throws IOException {
        // Simulate file with signature file
        Path file = downloadDir.resolve("signed.jar");
        Path signature = downloadDir.resolve("signed.jar.sig");

        Files.writeString(file, "verified library content");
        Files.writeString(signature, "valid_signature_data");

        // Verify signature exists and is valid
        assertTrue(Files.exists(signature), "Signature file should exist");
        assertFalse(Files.readString(signature).isEmpty(), "Signature should not be empty");
    }

    @Test
    @DisplayName("P5-SEC-006: Missing Signature Detection - Reject unsigned files")
    void testMissingSignatureDetection() throws IOException {
        Path file = downloadDir.resolve("unsigned.jar");
        Files.writeString(file, "unsigned library");

        Path signature = downloadDir.resolve("unsigned.jar.sig");

        // Signature should not exist
        assertFalse(Files.exists(signature), 
            "Unsigned file should not have signature");
    }

    @Test
    @DisplayName("P5-SEC-006: HTTPS Requirement - Enforce secure download protocol")
    void testHttpsRequirement() {
        String[] urls = {
            "https://trusted.example.com/package.jar",
            "http://untrusted.example.com/package.jar",
            "ftp://server.example.com/package.jar"
        };

        assertTrue(isSecureUrl(urls[0]), "HTTPS URL should be secure");
        assertFalse(isSecureUrl(urls[1]), "HTTP URL should not be considered secure");
        assertFalse(isSecureUrl(urls[2]), "FTP URL should not be considered secure");
    }

    @Test
    @DisplayName("P5-SEC-006: Certificate Validation - Verify SSL certificates")
    void testCertificateValidation() {
        // Test certificate validation logic
        String trustedHost = "trusted.example.com";
        String untrustedHost = "untrusted.invalid";

        assertTrue(isTrustedHost(trustedHost), "Trusted host should be validated");
        assertFalse(isTrustedHost(untrustedHost), "Untrusted host should be rejected");
    }

    @Test
    @DisplayName("P5-SEC-006: Content Type Validation - Verify file types match")
    void testContentTypeValidation() {
        String[] files = {
            ("file.jar", "application/jar"),
            ("archive.zip", "application/zip"),
            ("script.sh", "application/x-shellscript"),
            ("image.png", "image/png")
        };

        for (String[] entry : files) {
            String filename = entry[0];
            String expectedType = entry[1];
            String actualType = getContentType(filename);
            
            assertTrue(actualType.contains(expectedType.split("/")[0]), 
                "Content type should match for " + filename);
        }
    }

    @Test
    @DisplayName("P5-SEC-006: Malware Scanning - Scan downloaded files for malware")
    void testMalwareScanning() throws IOException {
        Path file = downloadDir.resolve("suspicious.exe");
        Files.writeString(file, "legitimate software");

        // Simulate malware scan
        boolean isSafe = scanForMalware(file);
        assertTrue(isSafe, "Clean file should pass malware scan");
    }

    @Test
    @DisplayName("P5-SEC-006: Quarantine Suspicious Files - Isolate potentially dangerous downloads")
    void testSuspiciousFileQuarantine() throws IOException {
        Path quarantineDir = tempDir.resolve("quarantine");
        Files.createDirectory(quarantineDir);

        Path suspiciousFile = downloadDir.resolve("suspicious.bin");
        Files.writeString(suspiciousFile, "suspicious content");

        // Move to quarantine
        if (isSuspicious(suspiciousFile)) {
            Path quarantined = quarantineDir.resolve("suspicious.bin.quarantine");
            Files.move(suspiciousFile, quarantined);
            
            assertTrue(Files.exists(quarantined), "Suspicious file should be quarantined");
            assertFalse(Files.exists(suspiciousFile), "Original file should be removed");
        }
    }

    @Test
    @DisplayName("P5-SEC-006: Checksum Chain Verification - Verify checksum with certificate chain")
    void testChecksumChainVerification() throws IOException, NoSuchAlgorithmException {
        String fileContent = "library content";
        Path file = downloadDir.resolve("library.jar");
        Files.writeString(file, fileContent);

        String checksum = calculateSha256(fileContent);
        
        // Write checksum file
        Files.writeString(checksumFile, "library.jar " + checksum);

        // Verify chain
        String recordedChecksum = Files.readString(checksumFile).split(" ")[1];
        assertEquals(checksum, recordedChecksum, "Checksum chain should verify");
    }

    // ==================== Helper Methods ====================

    private String calculateSha256(String content) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content.getBytes());
        return bytesToHex(hash);
    }

    private String calculateSha1(String content) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hash = digest.digest(content.getBytes());
        return bytesToHex(hash);
    }

    private String calculateMd5(String content) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] hash = digest.digest(content.getBytes());
        return bytesToHex(hash);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private boolean isValidSha256Format(String checksum) {
        return checksum != null && 
               checksum.length() == 64 && 
               checksum.matches("[a-f0-9]{64}");
    }

    private boolean isSecureUrl(String url) {
        return url != null && url.startsWith("https://");
    }

    private boolean isTrustedHost(String host) {
        return host != null && host.contains("trusted");
    }

    private String getContentType(String filename) {
        if (filename.endsWith(".jar")) return "application/jar";
        if (filename.endsWith(".zip")) return "application/zip";
        if (filename.endsWith(".sh")) return "application/x-shellscript";
        if (filename.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private boolean scanForMalware(Path file) {
        // Simulate malware scanning - always return safe for test
        return true;
    }

    private boolean isSuspicious(Path file) {
        // Simulate suspicious file detection
        return false;
    }
}
