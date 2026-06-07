package me.bechberger.testorder;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pure-JDK utility for restoring offline-instrumented class backups. Mirrors
 * the logic of {@code OfflineInstrumentor.restore()} using only
 * {@code java.nio.file.*} so it can safely run in JVM shutdown hooks even after
 * the plugin classloader has been torn down.
 *
 * <p>
 * Backup directories are registered from the plugin at instrumentation time via
 * {@link #register(Path)}. The restore happens at session end (triggered by
 * {@code CollectorLifecycleParticipant}) or, as a fallback, in a JVM shutdown
 * hook.
 *
 * <p>
 * Named static inner classes ({@link CopyVisitor}, {@link DeleteVisitor}) are
 * used instead of anonymous classes so they can be pre-loaded by name before
 * Maven's classloader teardown, guaranteeing the shutdown hook can run them.
 *
 * <p>
 * <b>Defensive guarantees:</b>
 * <ul>
 * <li>Per-backup {@link ReentrantLock} serializes concurrent restore attempts
 * (e.g. {@code afterSessionEnd} racing the shutdown hook).</li>
 * <li>Restore completes the file copy phase fully before deleting the marker;
 * this means a crash mid-restore leaves the marker in place and the next
 * session's {@code restoreLeftoverInstrumentation} sweep will retry.</li>
 * <li>If <i>any</i> file copy fails, the marker is preserved so a follow-up
 * sweep can finish the job. We refuse to leave a half-instrumented classpath
 * silently.</li>
 * </ul>
 */
public final class ClassBackupRestorer {

	private static final Set<Path> pendingBackups = ConcurrentHashMap.newKeySet();
	private static final ConcurrentHashMap<Path, ReentrantLock> backupLocks = new ConcurrentHashMap<>();
	private static final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);

	private ClassBackupRestorer() {
	}

	/**
	 * Registers a backup directory for restoration. Also ensures a JVM shutdown
	 * hook is registered. Safe to call multiple times.
	 */
	public static void register(Path backupDir) {
		if (backupDir == null) {
			return;
		}
		Path normalized = backupDir.toAbsolutePath().normalize();
		pendingBackups.add(normalized);
		if (shutdownHookRegistered.compareAndSet(false, true)) {
			preloadClasses();
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				for (Path backup : pendingBackups) {
					try {
						restore(backup);
					} catch (Exception e) {
						System.err.println("[test-order] ClassBackupRestorer: restore failed for " + backup + ": "
								+ e.getMessage());
					}
				}
			}, "test-order-class-restore"));
		}
	}

	/**
	 * Pre-loads all named inner classes used by {@link #restore(Path)} so they
	 * survive Maven classloader teardown and can run in the JVM shutdown hook.
	 */
	private static void preloadClasses() {
		try {
			ClassLoader cl = ClassBackupRestorer.class.getClassLoader();
			Class.forName(CopyVisitor.class.getName(), true, cl);
			Class.forName(DeleteVisitor.class.getName(), true, cl);
		} catch (ClassNotFoundException e) {
			// Non-fatal: if pre-loading fails the restore will log an error; the next
			// plain mvn test will restore via PrepareMojo.
		}
	}

	/**
	 * Restores all registered backups immediately and clears the pending set.
	 * Called from the lifecycle participant when available.
	 */
	public static void restoreAll() {
		// Iterate over a snapshot — restore() may take a per-backup lock and we
		// don't want to hold the iterator while another thread modifies the set.
		List<Path> snapshot = new ArrayList<>(pendingBackups);
		for (Path backup : snapshot) {
			try {
				restore(backup);
				pendingBackups.remove(backup);
			} catch (Exception e) {
				System.err.println(
						"[test-order] ClassBackupRestorer: restore failed for " + backup + ": " + e.getMessage());
			}
		}
	}

	/**
	 * Restores a single backup directory.
	 *
	 * <p>
	 * Acquires a per-backup lock so concurrent calls from the lifecycle participant
	 * and the JVM shutdown hook serialize correctly.
	 *
	 * @return true if the backup was present and restored
	 */
	public static boolean restore(Path backupDir) throws IOException {
		if (backupDir == null || !Files.isDirectory(backupDir)) {
			return false;
		}
		Path normalized = backupDir.toAbsolutePath().normalize();
		ReentrantLock lock = backupLocks.computeIfAbsent(normalized, k -> new ReentrantLock());
		lock.lock();
		try {
			return restoreLocked(normalized);
		} finally {
			lock.unlock();
		}
	}

	private static boolean restoreLocked(Path backupDir) throws IOException {
		Path marker = backupDir.resolve(".instrumented");
		if (!Files.exists(marker)) {
			return false;
		}
		String classesDirString;
		try {
			classesDirString = Files.readString(marker).trim();
		} catch (IOException e) {
			System.err.println("[test-order] ClassBackupRestorer: cannot read marker at " + marker
					+ "; preserving for retry: " + e.getMessage());
			return false;
		}
		if (classesDirString.isEmpty()) {
			System.err.println("[test-order] ClassBackupRestorer: marker at " + marker + " is empty; deleting");
			Files.deleteIfExists(marker);
			return false;
		}
		Path classesDir = Path.of(classesDirString);
		if (!Files.isDirectory(classesDir)) {
			// Classes dir gone (e.g. mvn clean removed target/classes). Backup is
			// stranded — clean it up so we don't keep retrying forever.
			System.err.println("[test-order] ClassBackupRestorer: target classes dir " + classesDir
					+ " no longer exists; cleaning up stranded backup " + backupDir);
			Files.deleteIfExists(marker);
			try {
				Files.walkFileTree(backupDir, new DeleteVisitor(backupDir));
			} catch (IOException ignored) {
			}
			return false;
		}

		// Phase 1: copy all backed-up files to the classes dir. Track failures —
		// if anything fails, the marker stays in place so the next session retries.
		CopyVisitor visitor = new CopyVisitor(backupDir, classesDir);
		Files.walkFileTree(backupDir, visitor);
		if (visitor.failures > 0) {
			System.err.println("[test-order] ClassBackupRestorer: " + visitor.failures
					+ " file(s) failed to restore in " + backupDir + "; marker preserved for retry on next build");
			return false;
		}

		// Phase 2: only after a fully successful copy do we delete the marker.
		// This way a crash mid-copy leaves the marker so we retry next time.
		try {
			Files.deleteIfExists(marker);
		} catch (IOException e) {
			System.err.println(
					"[test-order] ClassBackupRestorer: failed to delete marker at " + marker + ": " + e.getMessage());
			// Even though the copy succeeded, leave the marker — a duplicate
			// restore on the next run is harmless.
			return true;
		}

		// Phase 3: delete the backup contents (best-effort). Stale backup files
		// don't break correctness; they just waste disk.
		try {
			Files.walkFileTree(backupDir, new DeleteVisitor(backupDir));
		} catch (IOException ignored) {
		}

		return true;
	}

	static final class CopyVisitor extends SimpleFileVisitor<Path> {
		private final Path backupDir;
		private final Path classesDir;
		int failures;

		CopyVisitor(Path backupDir, Path classesDir) {
			this.backupDir = backupDir;
			this.classesDir = classesDir;
		}

		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
			if (file.toString().endsWith(".class")) {
				try {
					Path relative = backupDir.relativize(file);
					Path target = classesDir.resolve(relative);
					Path parent = target.getParent();
					if (parent != null) {
						Files.createDirectories(parent);
					}
					Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					failures++;
					System.err.println(
							"[test-order] ClassBackupRestorer: failed to restore " + file + ": " + e.getMessage());
				}
			}
			return FileVisitResult.CONTINUE;
		}
	}

	static final class DeleteVisitor extends SimpleFileVisitor<Path> {
		private final Path backupDir;

		DeleteVisitor(Path backupDir) {
			this.backupDir = backupDir;
		}

		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
			try {
				Files.delete(file);
			} catch (IOException ignored) {
			}
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
			if (!dir.equals(backupDir)) {
				try {
					Files.delete(dir);
				} catch (IOException ignored) {
				}
			}
			return FileVisitResult.CONTINUE;
		}
	}
}
