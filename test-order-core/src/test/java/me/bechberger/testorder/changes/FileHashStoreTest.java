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
        // create a source tree
        Path srcRoot = tempDir.resolve("src");
        Files.createDirectories(srcRoot.resolve("com/example"));
        Files.writeString(srcRoot.resolve("com/example/Foo.java"), "public class Foo {}");
        Files.writeString(srcRoot.resolve("com/example/Bar.java"), "public class Bar {}");

        FileHashStore store = FileHashStore.scan(srcRoot);
        assertEquals(2, store.getHashes().size());

        // save and reload
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

        // modify one file
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

        // add a new file
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

        // delete a file
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
}
