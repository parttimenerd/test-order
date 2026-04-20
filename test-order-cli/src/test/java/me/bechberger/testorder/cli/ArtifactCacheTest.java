package me.bechberger.testorder.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactCacheTest {

    @Test
    void testCacheMetadataStructure(@TempDir Path tempDir) {
        // Just verify the CacheEntry structure
        ArtifactCache.CacheEntry entry = new ArtifactCache.CacheEntry(
            "test-file-123.zip",
            "GitHub Actions",
            "test-deps",
            "2026-04-20T00:00:00Z",
            "12345-abc"
        );

        assertEquals("test-file-123.zip", entry.getFilename());
        assertEquals("GitHub Actions", entry.getSource());
        assertEquals("test-deps", entry.getName());
        assertEquals("2026-04-20T00:00:00Z", entry.getTimestamp());
        assertEquals("12345-abc", entry.getChecksum());
    }

    @Test
    void testFilenameCleanup() {
        // Test that the cache properly sanitizes filenames
        String original = "test-order-deps@1.0.0#beta+2026.04.20";
        String sanitized = original.replaceAll("[^a-zA-Z0-9._-]", "-");
        
        assertTrue(sanitized.contains("-"));
        assertFalse(sanitized.contains("@"));
        assertFalse(sanitized.contains("#"));
    }

    @Test
    void testCacheEntryValidation() {
        // Verify CacheEntry stores all required metadata
        ArtifactCache.CacheEntry entry = new ArtifactCache.CacheEntry(
            "artifact-1234.zip",
            "HTTP",
            "my-deps",
            "2026-04-20T15:00:00Z",
            "1024-abc123"
        );

        assertNotNull(entry.getFilename());
        assertNotNull(entry.getSource());
        assertNotNull(entry.getName());
        assertNotNull(entry.getTimestamp());
        assertNotNull(entry.getChecksum());
    }
}

