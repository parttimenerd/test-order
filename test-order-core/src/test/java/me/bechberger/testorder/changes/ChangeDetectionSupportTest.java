package me.bechberger.testorder.changes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChangeDetectionSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void parseModeSupportsKnownValues() throws IOException {
        assertEquals(ChangeDetector.Mode.SINCE_LAST_RUN, ChangeDetectionSupport.parseMode("since-last-run"));
        assertEquals(ChangeDetector.Mode.SINCE_LAST_COMMIT, ChangeDetectionSupport.parseMode("since-last-commit"));
        assertEquals(ChangeDetector.Mode.UNCOMMITTED, ChangeDetectionSupport.parseMode("uncommitted"));
        assertEquals(ChangeDetector.Mode.EXPLICIT, ChangeDetectionSupport.parseMode("explicit"));
    }

    @Test
    void parseModeRejectsUnknownValue() {
        assertThrows(IOException.class, () -> ChangeDetectionSupport.parseMode("bogus"));
    }

    @Test
    void resolveModeUsesSnapshotPresenceInAutoMode() throws Exception {
        Path hashFile = tempDir.resolve("hashes.lz4");
        assertEquals(ChangeDetector.Mode.SINCE_LAST_COMMIT,
                ChangeDetectionSupport.resolveMode("auto", hashFile));
        Files.writeString(hashFile, "x");
        assertEquals(ChangeDetector.Mode.SINCE_LAST_RUN,
                ChangeDetectionSupport.resolveMode("auto", hashFile));
    }

    @Test
    void detectChangedClassesReturnsExplicitSetWithoutGit() throws Exception {
        Set<String> changed = ChangeDetectionSupport.detectChangedClasses(
                "auto",
                tempDir,
                tempDir.resolve("src/main/java"),
                tempDir.resolve("hashes.lz4"),
                "com.example.A, com.example.B",
                true);
        assertEquals(Set.of("com.example.A", "com.example.B"), changed);
    }

    @Test
    void detectChangedTestsSkipsExplicitMode() throws Exception {
        Set<String> changedTests = ChangeDetectionSupport.detectChangedTestClasses(
                "explicit",
                tempDir,
                tempDir.resolve("src/test/java"),
                tempDir.resolve("test-hashes.lz4"),
                true);
        assertEquals(Set.of(), changedTests);
    }
}
