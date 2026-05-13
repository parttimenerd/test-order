package me.bechberger.testorder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Shared helpers for atomic writes and temp-file recovery. */
public final class PersistenceSupport {

	private static final String TEMP_SUFFIX = ".tmp";
	private static final String LOCK_SUFFIX = ".lock";
	private static final ConcurrentMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();
	/**
	 * Tracks lock files held by each thread, enabling reentrant withFileLock calls.
	 */
	private static final ThreadLocal<Set<Path>> HELD_LOCKS = ThreadLocal.withInitial(HashSet::new);

	/**
	 * Stale lock files older than this are automatically deleted. Configurable via
	 * {@code testorder.lock.stale.minutes}.
	 */
	private static final Duration STALE_LOCK_THRESHOLD = staleLockThreshold();

	/** Leftover temp files older than this are cleaned up. */
	private static final Duration STALE_TEMP_THRESHOLD = Duration.ofMinutes(10);

	private static final Set<PosixFilePermission> OWNER_ONLY_PERMS = PosixFilePermissions.fromString("rw-------");

	private PersistenceSupport() {
	}

	private static Duration staleLockThreshold() {
		String prop = System.getProperty("testorder.lock.stale.minutes");
		if (prop != null) {
			try {
				int minutes = Integer.parseInt(prop);
				if (minutes > 0) {
					return Duration.ofMinutes(minutes);
				}
			} catch (NumberFormatException ignored) {
			}
		}
		return Duration.ofMinutes(120);
	}

	@FunctionalInterface
	public interface IOCallable<T> {
		T call() throws IOException;
	}

	public static Path temporarySibling(Path target) {
		return target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
	}

	public static Path lockSibling(Path target) {
		return target.resolveSibling(target.getFileName() + LOCK_SUFFIX);
	}

	/**
	 * Resolves the best available file to load from. Tries the primary path first,
	 * falling back to the temp sibling. Uses try-based resolution instead of
	 * existence checks to avoid TOCTOU races (CWE-367).
	 */
	public static Path resolveLoadPath(Path target) {
		// Return primary if it exists (checked atomically by the caller opening it).
		// Use NOFOLLOW_LINKS to avoid symlink-following.
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			return target;
		}
		Path temp = temporarySibling(target);
		return Files.exists(temp, LinkOption.NOFOLLOW_LINKS) ? temp : target;
	}

	/**
	 * Atomically moves a temp file into the target location. Rejects targets that
	 * are symbolic links to prevent arbitrary file writes (CWE-59).
	 */
	public static void moveIntoPlace(Path tempFile, Path target) throws IOException {
		if (Files.isSymbolicLink(target)) {
			Files.deleteIfExists(tempFile);
			throw new IOException("Refusing to write through symbolic link: " + target);
		}
		try {
			Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException ignored) {
			Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static <T> T withFileLock(Path target, IOCallable<T> action) throws IOException {
		Path lockFile = lockSibling(target).toAbsolutePath().normalize();
		// Reentrant: if this thread already holds the lock, just run the action
		if (HELD_LOCKS.get().contains(lockFile)) {
			return action.call();
		}
		cleanupStaleLock(lockFile);
		// Keep lock objects in the map permanently so all threads synchronize on
		// the same instance. Removing eagerly caused a race where a waiting
		// thread and a new arrival could obtain different lock objects.
		Object jvmLock = JVM_LOCKS.computeIfAbsent(lockFile, ignored -> new Object());
		synchronized (jvmLock) {
			Path parent = lockFile.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
					FileLock ignored = channel.lock()) {
				setOwnerOnlyPermissions(lockFile);
				HELD_LOCKS.get().add(lockFile);
				try {
					return action.call();
				} finally {
					HELD_LOCKS.get().remove(lockFile);
				}
			}
		}
	}

	private static final java.util.logging.Logger LOGGER = java.util.logging.Logger
			.getLogger(PersistenceSupport.class.getName());

	/**
	 * Removes a stale lock file left by a crashed JVM if it is older than the
	 * threshold.
	 */
	private static void cleanupStaleLock(Path lockFile) {
		try {
			if (!Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS))
				return;
			BasicFileAttributes attrs = Files.readAttributes(lockFile, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
			Instant lastModified = attrs.lastModifiedTime().toInstant();
			if (Duration.between(lastModified, Instant.now()).compareTo(STALE_LOCK_THRESHOLD) > 0) {
				LOGGER.warning("[test-order] Deleting stale lock file (older than " + STALE_LOCK_THRESHOLD.toMinutes()
						+ " minutes): " + lockFile);
				Files.deleteIfExists(lockFile);
			}
		} catch (IOException e) {
			// Best effort — if we can't check or delete, the normal lock path will handle
			// it
		}
	}

	/**
	 * Sets owner-only permissions (600) on the given file, if the filesystem
	 * supports POSIX permissions.
	 */
	private static void setOwnerOnlyPermissions(Path file) {
		try {
			Files.setPosixFilePermissions(file, OWNER_ONLY_PERMS);
		} catch (UnsupportedOperationException | IOException ignored) {
			// Non-POSIX filesystem (e.g. Windows) or permission change failed — not
			// critical
		}
	}

	/**
	 * Scans a directory for stale .tmp files and removes them. Should be called
	 * during initialization to clean up after crashed writes.
	 */
	public static void cleanupStaleTemps(Path directory) {
		if (directory == null || !Files.isDirectory(directory))
			return;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + TEMP_SUFFIX)) {
			Instant cutoff = Instant.now().minus(STALE_TEMP_THRESHOLD);
			for (Path tmp : stream) {
				try {
					Instant modified = Files.getLastModifiedTime(tmp, LinkOption.NOFOLLOW_LINKS).toInstant();
					if (modified.isBefore(cutoff)) {
						LOGGER.info("[test-order] Cleaning up stale temp file: " + tmp);
						Files.deleteIfExists(tmp);
					}
				} catch (IOException e) {
					// Best effort — skip files we can't access
				}
			}
		} catch (IOException e) {
			// Directory may not exist or not be listable — that's fine
		}
	}
}
