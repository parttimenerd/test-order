package me.bechberger.testorder;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
 */
public final class ClassBackupRestorer {

	private static final Set<Path> pendingBackups = ConcurrentHashMap.newKeySet();
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
		pendingBackups.add(backupDir);
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
		for (Path backup : pendingBackups) {
			try {
				restore(backup);
			} catch (Exception e) {
				System.err.println(
						"[test-order] ClassBackupRestorer: restore failed for " + backup + ": " + e.getMessage());
			}
		}
		pendingBackups.clear();
	}

	/**
	 * Restores a single backup directory.
	 *
	 * @return true if the backup was present and restored
	 */
	public static boolean restore(Path backupDir) throws IOException {
		if (backupDir == null || !Files.isDirectory(backupDir)) {
			return false;
		}
		Path marker = backupDir.resolve(".instrumented");
		if (!Files.exists(marker)) {
			return false;
		}
		Path classesDir = Path.of(Files.readString(marker).trim());

		Files.walkFileTree(backupDir, new CopyVisitor(backupDir, classesDir));

		// Delete marker so the restore is detected as complete
		Files.deleteIfExists(marker);

		// Clean up backup directory (best-effort)
		Files.walkFileTree(backupDir, new DeleteVisitor(backupDir));

		return true;
	}

	static final class CopyVisitor extends SimpleFileVisitor<Path> {
		private final Path backupDir;
		private final Path classesDir;

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
					Files.createDirectories(target.getParent());
					Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
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
