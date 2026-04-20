package me.bechberger.testorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void temporarySiblingUsesTmpSuffix() {
        Path target = tempDir.resolve("state.bin");
        Path temp = PersistenceSupport.temporarySibling(target);
        assertEquals(tempDir.resolve("state.bin.tmp"), temp);
    }

    @Test
    void resolveLoadPathPrefersTargetWhenPresent() throws IOException {
        Path target = tempDir.resolve("state.bin");
        Files.writeString(target, "target");
        Files.writeString(PersistenceSupport.temporarySibling(target), "temp");

        assertEquals(target, PersistenceSupport.resolveLoadPath(target));
    }

    @Test
    void resolveLoadPathFallsBackToTempWhenTargetMissing() throws IOException {
        Path target = tempDir.resolve("state.bin");
        Path temp = PersistenceSupport.temporarySibling(target);
        Files.writeString(temp, "temp-only");

        assertEquals(temp, PersistenceSupport.resolveLoadPath(target));
    }

    @Test
    void resolveLoadPathReturnsTargetWhenNeitherExists() {
        Path target = tempDir.resolve("state.bin");
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(PersistenceSupport.temporarySibling(target)));

        assertEquals(target, PersistenceSupport.resolveLoadPath(target));
    }

    @Test
    void moveIntoPlaceReplacesTargetAndRemovesTemp() throws IOException {
        Path target = tempDir.resolve("state.bin");
        Path temp = PersistenceSupport.temporarySibling(target);
        Files.writeString(target, "old");
        Files.writeString(temp, "new");

        PersistenceSupport.moveIntoPlace(temp, target);

        assertTrue(Files.exists(target));
        assertFalse(Files.exists(temp));
        assertEquals("new", Files.readString(target));
    }

    @Test
    void withFileLockExecutesActionAndCreatesLockSibling() throws IOException {
        Path target = tempDir.resolve("state.bin");

        String value = PersistenceSupport.withFileLock(target, () -> {
            Files.writeString(target, "locked");
            return Files.readString(target);
        });

        assertEquals("locked", value);
        assertEquals("locked", Files.readString(target));
        assertTrue(Files.exists(PersistenceSupport.lockSibling(target)));
    }

    @Test
    void withFileLockSerializesConcurrentCallersInSameJvm() throws Exception {
        Path target = tempDir.resolve("state.bin");
        CountDownLatch firstLockHeld = new CountDownLatch(1);
        CountDownLatch releaseFirstLock = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                try {
                    PersistenceSupport.withFileLock(target, () -> {
                        firstLockHeld.countDown();
                        try {
                            assertTrue(releaseFirstLock.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while waiting to release first lock", e);
                        }
                        return null;
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertTrue(firstLockHeld.await(5, TimeUnit.SECONDS));

            Future<String> second = executor.submit(() -> {
                try {
                    return PersistenceSupport.withFileLock(target, () -> "second");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> second.get(200, TimeUnit.MILLISECONDS));

            releaseFirstLock.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals("second", second.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
