package com.example.idecicd;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

/**
 * Utility class for test infrastructure setup and teardown.
 */
public class TestEnvironmentSetup {

    private static final String TEST_TEMP_DIR = "test-ide-cicd-temp";

    public static Path createTestDirectory(String name) throws IOException {
        Path baseDir = Paths.get(System.getProperty("java.io.tmpdir"), TEST_TEMP_DIR);
        Path testDir = baseDir.resolve(name);
        Files.createDirectories(testDir);
        return testDir;
    }

    public static void cleanupTestDirectory(String name) {
        try {
            Path baseDir = Paths.get(System.getProperty("java.io.tmpdir"), TEST_TEMP_DIR);
            Path testDir = baseDir.resolve(name);
            if (Files.exists(testDir)) {
                deleteDirectory(testDir);
            }
        } catch (IOException e) {
            System.err.println("Failed to cleanup: " + e.getMessage());
        }
    }

    public static void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            System.err.println("Failed to delete: " + p + " - " + e.getMessage());
                        }
                    });
        }
    }

    public static Path createTestFile(Path dir, String filename, String content) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(filename);
        Files.write(file, content.getBytes());
        return file;
    }

    public static void setFilePermissions(Path file, PosixFilePermission... permissions) throws IOException {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            // Skip on Windows
            return;
        }
        Set<PosixFilePermission> perms = new HashSet<>();
        for (PosixFilePermission perm : permissions) {
            perms.add(perm);
        }
        try {
            Files.setPosixFilePermissions(file, perms);
        } catch (Exception e) {
            System.err.println("Warning: Could not set permissions: " + e.getMessage());
        }
    }

    public static String readFile(Path file) throws IOException {
        return new String(Files.readAllBytes(file));
    }

    public static boolean fileExists(Path path) {
        return Files.exists(path);
    }

    public static boolean isDirectory(Path path) {
        return Files.isDirectory(path);
    }

    public static boolean isReadable(Path path) {
        return Files.isReadable(path);
    }

    public static boolean isWritable(Path path) {
        return Files.isWritable(path);
    }

    public static long getFileSize(Path path) throws IOException {
        return Files.size(path);
    }
}
