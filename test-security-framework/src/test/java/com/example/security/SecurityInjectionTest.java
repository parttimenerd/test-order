package com.example.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security Test Suite for P5-SEC-004 and P5-SEC-005:
 * 
 * P5-SEC-004: Command/Code Injection Attacks - Test prevention of shell command injection,
 * SQL injection, and code injection vulnerabilities by validating and sanitizing user input.
 * 
 * P5-SEC-005: Input Validation - Test that all user inputs are validated against expected
 * patterns, lengths, and character sets before use in critical operations.
 */
@DisplayName("Security - Injection & Input Validation Tests (P5-SEC-004/005)")
class SecurityInjectionTest {

    @TempDir
    Path tempDir;

    // ==================== P5-SEC-004: Command Injection ====================

    @Test
    @DisplayName("P5-SEC-004: Shell Command Injection - Detect shell metacharacters")
    void testShellCommandInjectionDetection() {
        String[] maliciousInputs = {
            "file.txt; rm -rf /",
            "input.txt && cat /etc/passwd",
            "data.txt | grep password",
            "$(whoami)",
            "`id`",
            "test\nmalicious command"
        };

        for (String input : maliciousInputs) {
            assertFalse(isValidFileName(input), 
                "Shell injection attempt should be rejected: " + input);
        }
    }

    @Test
    @DisplayName("P5-SEC-004: Path Traversal Prevention - Block directory traversal sequences")
    void testPathTraversalPrevention() {
        String[] traversalAttempts = {
            "../../../etc/passwd",
            "..\\..\\..\\windows\\system32",
            "./../../secret.txt",
            "....//....//....//etc/passwd",
            "file.txt; cd /tmp; "
        };

        for (String input : traversalAttempts) {
            assertFalse(isValidFileName(input), 
                "Path traversal attempt should be rejected: " + input);
        }
    }

    @Test
    @DisplayName("P5-SEC-004: SQL Injection Prevention - Escape SQL special characters")
    void testSqlInjectionPrevention() {
        String[] sqlInjections = {
            "'; DROP TABLE users; --",
            "1' OR '1'='1",
            "admin'--",
            "'; DELETE FROM users; --",
            "1; UPDATE users SET admin=1; --"
        };

        for (String input : sqlInjections) {
            String sanitized = sanitizeSqlInput(input);
            assertFalse(containsSqlInjectionPatterns(sanitized), 
                "SQL injection attempt should be sanitized: " + input);
        }
    }

    @Test
    @DisplayName("P5-SEC-004: LDAP Injection Prevention - Escape LDAP special characters")
    void testLdapInjectionPrevention() {
        String[] ldapInjections = {
            "admin*",
            "admin)(|(cn=",
            "*)(uid=*",
            "admin*))(&(|(uid=*"
        };

        for (String input : ldapInjections) {
            String sanitized = sanitizeLdapInput(input);
            assertFalse(containsLdapWildcards(sanitized), 
                "LDAP injection attempt should be sanitized: " + input);
        }
    }

    @Test
    @DisplayName("P5-SEC-004: XML Injection Prevention - Escape XML entities")
    void testXmlInjectionPrevention() {
        String[] xmlInjections = {
            "<script>alert('xss')</script>",
            "<!--<injection>-->",
            "<?xml version=\"1.0\"?><!DOCTYPE x[<!ENTITY foo \"bar\">]>",
            "&nbsp;&nbsp;&nbsp;"
        };

        for (String input : xmlInjections) {
            String sanitized = sanitizeXmlInput(input);
            assertFalse(sanitized.contains("<script"), 
                "XML/HTML injection should be escaped: " + input);
        }
    }

    @Test
    @DisplayName("P5-SEC-004: JavaScript/XSS Prevention - Escape dangerous characters")
    void testXssInjectionPrevention() {
        String[] xssAttempts = {
            "<img src=x onerror=\"alert('xss')\">",
            "javascript:alert('xss')",
            "<svg onload=alert('xss')>",
            "eval('malicious code')"
        };

        for (String input : xssAttempts) {
            String sanitized = sanitizeHtmlInput(input);
            assertFalse(sanitized.contains("<"), 
                "XSS attempt should be escaped: " + input);
        }
    }

    @Test
    @DisplayName("P5-SEC-004: Log Injection Prevention - Sanitize log entries")
    void testLogInjectionPrevention() {
        String[] logInjections = {
            "User login\nAdmin: true",
            "Request\rERROR: critical failure",
            "Message\x1b[31m[INJECTED]"
        };

        for (String input : logInjections) {
            String sanitized = sanitizeLogEntry(input);
            assertFalse(sanitized.contains("\n"), "Newlines should be removed from logs");
            assertFalse(sanitized.contains("\r"), "Carriage returns should be removed from logs");
        }
    }

    // ==================== P5-SEC-005: Input Validation ====================

    @ParameterizedTest
    @DisplayName("P5-SEC-005: File Name Validation - Only allow safe characters")
    @ValueSource(strings = {
        "valid_file.txt",
        "document123.pdf",
        "my-report.docx",
        "Archive_2024.zip"
    })
    void testValidFileNames(String validName) {
        assertTrue(isValidFileName(validName), 
            "Valid file name should be accepted: " + validName);
    }

    @ParameterizedTest
    @DisplayName("P5-SEC-005: Reject Invalid File Names - Detect dangerous patterns")
    @ValueSource(strings = {
        "../secret.txt",
        "file\0name.txt",
        "file\nname.txt",
        "file|name.txt",
        "file<name>.txt"
    })
    void testInvalidFileNames(String invalidName) {
        assertFalse(isValidFileName(invalidName), 
            "Invalid file name should be rejected: " + invalidName);
    }

    @Test
    @DisplayName("P5-SEC-005: Email Validation - Enforce RFC 5322 patterns")
    void testEmailValidation() {
        assertTrue(isValidEmail("user@example.com"), "Valid email should be accepted");
        assertTrue(isValidEmail("user.name@example.co.uk"), "Valid email with dots should be accepted");
        
        assertFalse(isValidEmail("invalid.email@"), "Incomplete email should be rejected");
        assertFalse(isValidEmail("user@.com"), "Missing domain should be rejected");
        assertFalse(isValidEmail("user name@example.com"), "Spaces in email should be rejected");
    }

    @Test
    @DisplayName("P5-SEC-005: URL Validation - Restrict to allowed schemes")
    void testUrlValidation() {
        assertTrue(isValidUrl("https://example.com"), "HTTPS URL should be valid");
        assertTrue(isValidUrl("http://example.com"), "HTTP URL should be valid");
        
        assertFalse(isValidUrl("javascript:alert('xss')"), "JavaScript URL should be rejected");
        assertFalse(isValidUrl("file:///etc/passwd"), "File URL should be rejected");
        assertFalse(isValidUrl("data:text/html,<script>alert('xss')</script>"), "Data URL should be rejected");
    }

    @Test
    @DisplayName("P5-SEC-005: Integer Validation - Check range and overflow")
    void testIntegerValidation() {
        assertTrue(isValidInteger("123"), "Valid integer should be accepted");
        assertTrue(isValidInteger("0"), "Zero should be accepted");
        assertTrue(isValidInteger("-456"), "Negative integer should be accepted");
        
        assertFalse(isValidInteger("abc"), "Non-numeric string should be rejected");
        assertFalse(isValidInteger("12.34"), "Float should be rejected");
        assertFalse(isValidInteger("12e5"), "Scientific notation should be rejected");
    }

    @Test
    @DisplayName("P5-SEC-005: Length Validation - Enforce maximum field lengths")
    void testLengthValidation() {
        String shortString = "valid";
        String longString = "a".repeat(1001);

        assertTrue(validateLength(shortString, 10), "Short string should pass validation");
        assertFalse(validateLength(longString, 1000), "String exceeding max length should fail");
    }

    @Test
    @DisplayName("P5-SEC-005: Character Set Validation - Only allow expected characters")
    void testCharacterSetValidation() {
        assertTrue(isAlphanumeric("abc123"), "Alphanumeric should be valid");
        assertFalse(isAlphanumeric("abc-123"), "Dash should be rejected");
        assertFalse(isAlphanumeric("abc@123"), "Special character should be rejected");
    }

    @Test
    @DisplayName("P5-SEC-005: Whitespace Handling - Trim and validate trimmed input")
    void testWhitespaceHandling() {
        String input = "  sensitive data  ";
        String trimmed = input.trim();
        
        assertTrue(trimmed.equals("sensitive data"), "Trimming should remove whitespace");
        assertFalse(trimmed.startsWith(" "), "Trimmed string should not start with space");
        assertFalse(trimmed.endsWith(" "), "Trimmed string should not end with space");
    }

    @Test
    @DisplayName("P5-SEC-005: Null and Empty Input - Reject empty/null values where required")
    void testNullAndEmptyValidation() {
        assertFalse(isValidRequired(""), "Empty string should be invalid for required field");
        assertFalse(isValidRequired(null), "Null should be invalid for required field");
        assertTrue(isValidRequired("data"), "Non-empty string should be valid");
    }

    // ==================== Helper Methods ====================

    private boolean isValidFileName(String filename) {
        if (filename == null || filename.isEmpty()) return false;
        
        // Reject dangerous patterns
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return false;
        }
        
        // Reject null bytes and control characters
        if (filename.contains("\0") || filename.matches(".*[\\r\\n\\t\\|<>].*")) {
            return false;
        }
        
        // Only allow alphanumeric, dash, underscore, and dot
        return filename.matches("[a-zA-Z0-9._-]+");
    }

    private String sanitizeSqlInput(String input) {
        // Escape single quotes
        return input.replace("'", "''");
    }

    private boolean containsSqlInjectionPatterns(String input) {
        Pattern[] patterns = {
            Pattern.compile("(DROP|DELETE|UPDATE|INSERT|UNION|SELECT|EXEC|EXECUTE)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("--"),
            Pattern.compile(";")
        };
        
        for (Pattern p : patterns) {
            if (p.matcher(input).find()) {
                return true;
            }
        }
        return false;
    }

    private String sanitizeLdapInput(String input) {
        return input.replace("*", "\\2a")
                   .replace("(", "\\28")
                   .replace(")", "\\29")
                   .replace("\0", "\\00");
    }

    private boolean containsLdapWildcards(String input) {
        return input.contains("*");
    }

    private String sanitizeXmlInput(String input) {
        return input.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }

    private String sanitizeHtmlInput(String input) {
        return input.replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    private String sanitizeLogEntry(String input) {
        return input.replaceAll("[\\r\\n\\x00-\\x1f]", "");
    }

    private boolean isValidEmail(String email) {
        return email != null && email.matches("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    }

    private boolean isValidUrl(String url) {
        if (url == null) return false;
        return (url.startsWith("http://") || url.startsWith("https://")) && 
               url.length() > 10;
    }

    private boolean isValidInteger(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean validateLength(String value, int maxLength) {
        return value != null && value.length() <= maxLength;
    }

    private boolean isAlphanumeric(String value) {
        return value != null && value.matches("[a-zA-Z0-9]*");
    }

    private boolean isValidRequired(String value) {
        return value != null && !value.isEmpty();
    }
}
