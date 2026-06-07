package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

			assertThrows(java.util.concurrent.TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));

			releaseFirstLock.countDown();
			first.get(5, TimeUnit.SECONDS);
			assertEquals("second", second.get(5, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void withFileLockIsReentrantFromSameThread() throws IOException {
		Path target = tempDir.resolve("reentrant.bin");
		// Calling withFileLock from within an already-held withFileLock on the same
		// path (same thread) should not deadlock.
		String result = PersistenceSupport.withFileLock(target,
				() -> PersistenceSupport.withFileLock(target, () -> "inner"));
		assertEquals("inner", result);
	}

	@Test
	void withFileLockCreatesParentDirectories() throws IOException {
		// Target file in a directory that doesn't exist yet.
		Path target = tempDir.resolve("nested/dir/state.bin");
		assertFalse(Files.exists(target.getParent()));
		String result = PersistenceSupport.withFileLock(target, () -> "created");
		assertEquals("created", result);
		// The parent directory must have been created.
		assertTrue(Files.isDirectory(target.getParent()));
	}

	@Test
	void moveIntoPlaceRejectsSymlinkTarget() throws IOException {
		Path realFile = tempDir.resolve("real.bin");
		Path symlink = tempDir.resolve("link.bin");
		Files.writeString(realFile, "real");
		Files.createSymbolicLink(symlink, realFile);

		Path temp = PersistenceSupport.temporarySibling(symlink);
		Files.writeString(temp, "new");

		// moveIntoPlace should throw IOException and must NOT overwrite through the
		// symlink (security guard against CWE-59).
		assertThrows(IOException.class, () -> PersistenceSupport.moveIntoPlace(temp, symlink));
		// The real file should still contain "real" (not overwritten).
		assertEquals("real", Files.readString(realFile));
	}

	@Test
	void cleanupStaleTempsDeletesOldFilesLeavesRecent() throws IOException {
		// Create a fresh .tmp file (should be kept).
		Path freshTmp = tempDir.resolve("fresh.bin.tmp");
		Files.writeString(freshTmp, "fresh");

		// Create a stale .tmp file: set last-modified to 20 minutes ago.
		Path staleTmp = tempDir.resolve("stale.bin.tmp");
		Files.writeString(staleTmp, "stale");
		Instant staleTime = Instant.now().minus(20, ChronoUnit.MINUTES);
		Files.setLastModifiedTime(staleTmp, FileTime.from(staleTime));

		// Also create a non-.tmp file (should always be kept).
		Path nonTmp = tempDir.resolve("normal.bin");
		Files.writeString(nonTmp, "normal");

		PersistenceSupport.cleanupStaleTemps(tempDir);

		assertFalse(Files.exists(staleTmp), "Stale .tmp file (20 min old) should be deleted");
		assertTrue(Files.exists(freshTmp), "Fresh .tmp file should be kept");
		assertTrue(Files.exists(nonTmp), "Non-.tmp file should never be deleted");
	}

	@Test
	void cleanupStaleTemps_nullDirectory_doesNotThrow() {
		// Null directory should be silently ignored.
		PersistenceSupport.cleanupStaleTemps(null);
	}

	@Test
	void cleanupStaleTemps_nonexistentDirectory_doesNotThrow() {
		PersistenceSupport.cleanupStaleTemps(tempDir.resolve("nonexistent"));
	}
}
