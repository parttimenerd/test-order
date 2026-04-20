package me.bechberger.testorder.changes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileHashStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void scanAndSaveRoundTrip() throws IOException {
        Path srcRoot = tempDir.resolve("src");
        Files.createDirectories(srcRoot.resolve("com/example"));
        Files.writeString(srcRoot.resolve("com/example/Foo.java"), "public class Foo {}");
        Files.writeString(srcRoot.resolve("com/example/Bar.java"), "public class Bar {}");

        FileHashStore store = FileHashStore.scan(srcRoot);
        assertEquals(2, store.getHashes().size());

        Path hashFile = tempDir.resolve("hashes.gz");
        store.save(hashFile);

        FileHashStore loaded = FileHashStore.load(hashFile);
        assertEquals(store, loaded);
    }

    @Test
    void detectChangedFile() throws IOException {
        Path srcRoot = tempDir.resolve("src");
        Files.createDirectories(srcRoot.resolve("com/example"));
        Files.writeString(srcRoot.resolve("com/example/Foo.java"), "public class Foo {}");
        Files.writeString(srcRoot.resolve("com/example/Bar.java"), "public class Bar {}");

        FileHashStore before = FileHashStore.scan(srcRoot);
        Files.writeString(srcRoot.resolve("com/example/Foo.java"), "public class Foo { int x; }");
        FileHashStore after = FileHashStore.scan(srcRoot);
        Set<String> changed = after.getChangedFiles(before);

        assertEquals(1, changed.size());
        assertTrue(changed.contains("com/example/Foo.java"));
    }

    @Test
    void detectNewFile() throws IOException {
        Path srcRoot = tempDir.resolve("src");
        Files.createDirectories(srcRoot.resolve("com/example"));
        Files.writeString(srcRoot.resolve("com/example/Foo.java"), "public class Foo {}");

        FileHashStore before = FileHashStore.scan(srcRoot);
        Files.writeString(srcRoot.resolve("com/example/Baz.java"), "public class Baz {}");
        FileHashStore after = FileHashStore.scan(srcRoot);
        Set<String> changed = after.getChangedFiles(before);

        assertEquals(1, changed.size());
        assertTrue(changed.contains("com/example/Baz.java"));
    }

    @Test
    void detectDeletedFile() throws IOException {
        Path srcRoot = tempDir.resolve("src");
        Files.createDirectories(srcRoot.resolve("com/example"));
        Files.writeString(srcRoot.resolve("com/example/Foo.java"), "public class Foo {}");
        Files.writeString(srcRoot.resolve("com/example/Bar.java"), "public class Bar {}");

        FileHashStore before = FileHashStore.scan(srcRoot);
        Files.delete(srcRoot.resolve("com/example/Bar.java"));
        FileHashStore after = FileHashStore.scan(srcRoot);
        Set<String> changed = after.getChangedFiles(before);

        assertEquals(1, changed.size());
        assertTrue(changed.contains("com/example/Bar.java"));
    }

    @Test
    void filesToClassNames() {
        Set<String> paths = Set.of("com/example/Foo.java", "com/example/util/Helper.java");
        Set<String> classNames = SourceFileModel.filesToClassNames(paths);
        assertEquals(Set.of("com.example.Foo", "com.example.util.Helper"), classNames);
    }

    @Test
    void noChanges() throws IOException {
        Path srcRoot = tempDir.resolve("src");
        Files.createDirectories(srcRoot.resolve("com/example"));
        Files.writeString(srcRoot.resolve("com/example/Foo.java"), "public class Foo {}");

        FileHashStore store1 = FileHashStore.scan(srcRoot);
        FileHashStore store2 = FileHashStore.scan(srcRoot);
        Set<String> changed = store2.getChangedFiles(store1);
        assertTrue(changed.isEmpty());
    }

    @Test
    void emptySourceRoot() throws IOException {
        Path srcRoot = tempDir.resolve("empty");
        Files.createDirectories(srcRoot);
        FileHashStore store = FileHashStore.scan(srcRoot);
        assertTrue(store.getHashes().isEmpty());
    }

    @Test
    void nonExistentSourceRoot() throws IOException {
        Path srcRoot = tempDir.resolve("nonexistent");
        FileHashStore store = FileHashStore.scan(srcRoot);
        assertTrue(store.getHashes().isEmpty());
    }

    @Test
    void scanProducesForwardSlashPaths() throws IOException {
        Path srcRoot = tempDir.resolve("src");
        Files.createDirectories(srcRoot.resolve("com/example/util"));
        Files.writeString(srcRoot.resolve("com/example/util/Helper.java"), "public class Helper {}");

        FileHashStore store = FileHashStore.scan(srcRoot);
        for (String key : store.getHashes().keySet()) {
            assertFalse(key.contains("\\"),
                    "Path key should use forward slashes, got: " + key);
        }
        assertTrue(store.getHashes().containsKey("com/example/util/Helper.java"),
                "Key should be 'com/example/util/Helper.java', actual keys: " + store.getHashes().keySet());
    }

    // ── Tier 3d: cross-platform paths & corrupt-file load ──────────────────────────

    @Test
    void roundTripPreservesForwardSlashKeysAfterLoad() throws IOException {
        Path srcRoot = tempDir.resolve("src");
        Files.createDirectories(srcRoot.resolve("com/example/util"));
        Files.writeString(srcRoot.resolve("com/example/util/Service.java"), "class Service {}");

        FileHashStore store = FileHashStore.scan(srcRoot);
        Path hashFile = tempDir.resolve("hashes.lz4");
        store.save(hashFile);

        FileHashStore loaded = FileHashStore.load(hashFile);
        for (String key : loaded.getHashes().keySet()) {
            assertFalse(key.contains("\\"),
                    "Loaded keys must use forward slashes, got: " + key);
        }
        assertTrue(loaded.getHashes().containsKey("com/example/util/Service.java"),
                "Key must be 'com/example/util/Service.java' after round-trip; actual: "
                        + loaded.getHashes().keySet());
    }

    @Test
    void getChangedFilesProducesNoFalsePositivesWhenContentUnchanged() throws IOException {
        Path srcRoot = tempDir.resolve("src");
        Files.createDirectories(srcRoot.resolve("a/b/c"));
        Files.writeString(srcRoot.resolve("a/b/c/Deep.java"), "class Deep {}");

        FileHashStore before = FileHashStore.scan(srcRoot);
        Path hashFile = tempDir.resolve("hashes.lz4");
        before.save(hashFile);

        FileHashStore after = FileHashStore.scan(srcRoot);
        Set<String> changed = after.getChangedFiles(FileHashStore.load(hashFile));
        assertTrue(changed.isEmpty(),
                "No false positives expected when content is unchanged after round-trip; got: " + changed);
    }

    @Test
    void loadTruncatedLz4FileThrowsIOException() throws IOException {
        Path hashFile = tempDir.resolve("truncated.lz4");
        byte[] truncated = {0x04, 0x22, 0x4D, 0x18, 0x60, 0x70, 0x73};
        Files.write(hashFile, truncated);
        assertThrows(IOException.class, () -> FileHashStore.load(hashFile),
                "Loading a truncated LZ4 file must throw IOException");
    }

    @Test
    void loadCompletelyRandomBytesThrowsIOException() throws IOException {
        Path hashFile = tempDir.resolve("random.lz4");
        byte[] random = new byte[64];
        for (int i = 0; i < random.length; i++) random[i] = (byte)(i * 13 + 7);
        Files.write(hashFile, random);
        assertThrows(IOException.class, () -> FileHashStore.load(hashFile),
                "Loading random bytes as LZ4 must throw IOException");
    }

    @Test
    void loadNormalizesBackslashKeysToForwardSlash() throws IOException {
        // Simulate a hash file written with Windows-style backslash paths
        Path hashFile = tempDir.resolve("backslash.lz4");
        try (var lz4os = new net.jpountz.lz4.LZ4FrameOutputStream(Files.newOutputStream(hashFile));
             var pw = new java.io.PrintWriter(new java.io.OutputStreamWriter(lz4os))) {
            pw.println("src\\main\\java\\Foo.java\tabc123");
            pw.println("src\\main\\java\\Bar.java\tdef456");
        }

        FileHashStore loaded = FileHashStore.load(hashFile);
        assertTrue(loaded.getHashes().containsKey("src/main/java/Foo.java"),
                "Backslash key must be normalized to forward slash on load");
        assertTrue(loaded.getHashes().containsKey("src/main/java/Bar.java"),
                "Backslash key must be normalized to forward slash on load");
        assertFalse(loaded.getHashes().containsKey("src\\main\\java\\Foo.java"),
                "Raw backslash key must not survive normalization");

        // Verify cross-platform comparison works: scan produces forward-slash keys
        Path srcRoot = tempDir.resolve("root/src/main/java");
        Files.createDirectories(srcRoot);
        Files.writeString(srcRoot.resolve("Foo.java"), "class Foo {}");
        Files.writeString(srcRoot.resolve("Bar.java"), "class Bar {}");
        FileHashStore scanned = FileHashStore.scan(tempDir.resolve("root"));

        // getChangedFiles must detect content change, not false-positive on path mismatch
        Set<String> changed = scanned.getChangedFiles(loaded);
        assertTrue(changed.contains("src/main/java/Foo.java"),
                "Hash mismatch should be detected after normalization, not path mismatch");
    }
}
