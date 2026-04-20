package me.bechberger.testorder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Shared helpers for atomic writes and temp-file recovery. */
public final class PersistenceSupport {

    private static final String TEMP_SUFFIX = ".tmp";
    private static final String LOCK_SUFFIX = ".lock";
    private static final ConcurrentMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    private PersistenceSupport() {}

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

    public static Path resolveLoadPath(Path target) {
        if (Files.exists(target)) {
            return target;
        }
        Path temp = temporarySibling(target);
        return Files.exists(temp) ? temp : target;
    }

    public static void moveIntoPlace(Path tempFile, Path target) throws IOException {
        try {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static <T> T withFileLock(Path target, IOCallable<T> action) throws IOException {
        Path lockFile = lockSibling(target).toAbsolutePath().normalize();
        Object jvmLock = JVM_LOCKS.computeIfAbsent(lockFile, ignored -> new Object());
        synchronized (jvmLock) {
            Path parent = lockFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return action.call();
            } finally {
                JVM_LOCKS.remove(lockFile, jvmLock);
            }
        }
    }
}
