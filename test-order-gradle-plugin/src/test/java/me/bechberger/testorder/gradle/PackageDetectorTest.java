package me.bechberger.testorder.gradle;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void detectSourcePackagesFindsDeepSingleChildChain() throws IOException {
        Path sourceRoot = tempDir.resolve("src/main/java/a/b/c/d/e");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Foo.java"), "class Foo {}\n");
        Project project = ProjectBuilder.builder().build();

        assertEquals(List.of("a.b.c.d.e"),
                PackageDetector.detectSourcePackages(tempDir.resolve("src/main/java"), project.getLogger()));
    }

    @Test
    void minimisePrefixesRemovesSubsumedAndDuplicateEntries() {
        assertEquals(List.of("com.foo", "org.example"),
                PackageDetector.minimisePrefixes(List.of("com.foo", "com.foo.bar", "org.example", "com.foo")));
    }

    @Test
    void resolveIncludePackagesFallsBackToGroupIdWhenSourcesMissing() {
        Project project = ProjectBuilder.builder().build();

        assertEquals("com.example.app",
                PackageDetector.resolveIncludePackages(null, true, "com.example.app",
                        tempDir.resolve("src/main/java"), project.getLogger()));
    }

    @Test
    void resolveIncludePackagesMinimisesDetectedUserAndGroupPrefixes() throws IOException {
        Path sourceRoot = tempDir.resolve("src/main/java/com/example/app/service");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("AppService.java"), "class AppService {}\n");
        Project project = ProjectBuilder.builder().build();

        assertEquals("com.example,org.extra",
                PackageDetector.resolveIncludePackages("org.extra, com.example.app.internal", true, "com.example",
                        tempDir.resolve("src/main/java"), project.getLogger()));
    }
}
