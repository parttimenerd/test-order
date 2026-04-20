package me.bechberger.testorder.plugin;

import me.bechberger.testorder.changes.ChangeDetector;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChangeDetectionHelperTest {

    @TempDir
    Path tempDir;

    @Test
    void parseModeSupportsKnownValues() throws IOException {
        assertEquals(ChangeDetector.Mode.SINCE_LAST_RUN, ChangeDetectionHelper.parseMode("since-last-run"));
        assertEquals(ChangeDetector.Mode.SINCE_LAST_COMMIT, ChangeDetectionHelper.parseMode("since-last-commit"));
        assertEquals(ChangeDetector.Mode.UNCOMMITTED, ChangeDetectionHelper.parseMode("uncommitted"));
        assertEquals(ChangeDetector.Mode.EXPLICIT, ChangeDetectionHelper.parseMode("explicit"));
    }

    @Test
    void parseModeRejectsUnknownValue() {
        IOException ex = assertThrows(IOException.class, () -> ChangeDetectionHelper.parseMode("bogus"));
        assertTrue(ex.getMessage().contains("Unknown changeMode"));
    }

    @Test
    void resolveSourceRootFallsBackToMavenCompileRootThenDefault() {
        MavenProject project = mock(MavenProject.class);
        when(project.getBasedir()).thenReturn(tempDir.toFile());
        when(project.getCompileSourceRoots()).thenReturn(List.of(tempDir.resolve("src/main/java").toString()));

        Path root = ChangeDetectionHelper.resolveSourceRoot(project, null);
        assertEquals(tempDir.resolve("src/main/java"), root);

        when(project.getCompileSourceRoots()).thenReturn(List.of());
        Path fallback = ChangeDetectionHelper.resolveSourceRoot(project, null);
        assertEquals(tempDir.resolve("src/main/java"), fallback);
    }

    @Test
    void resolveTestSourceRootFallsBackToMavenTestRootThenDefault() {
        MavenProject project = mock(MavenProject.class);
        when(project.getBasedir()).thenReturn(tempDir.toFile());
        when(project.getTestCompileSourceRoots()).thenReturn(List.of(tempDir.resolve("src/test/java").toString()));

        Path root = ChangeDetectionHelper.resolveTestSourceRoot(project, null);
        assertEquals(tempDir.resolve("src/test/java"), root);

        when(project.getTestCompileSourceRoots()).thenReturn(List.of());
        Path fallback = ChangeDetectionHelper.resolveTestSourceRoot(project, null);
        assertEquals(tempDir.resolve("src/test/java"), fallback);
    }

    @Test
    void detectChangedClassesExplicitModeReturnsConfiguredClasses() throws Exception {
        MavenProject project = mock(MavenProject.class);
        when(project.getBasedir()).thenReturn(tempDir.toFile());
        when(project.getProperties()).thenReturn(new Properties());

        Path sourceRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(sourceRoot);

        Set<String> changed = ChangeDetectionHelper.detectChangedClasses(
                project,
                "explicit",
                "com.example.Foo,com.example.Bar",
                sourceRoot,
                tempDir.resolve("hashes.lz4"),
                true,
                new org.apache.maven.plugin.logging.SystemStreamLog());

        assertEquals(Set.of("com.example.Foo", "com.example.Bar"), changed);
    }

    @Test
    void detectChangedTestClassesSkipsExplicitMode() throws Exception {
        MavenProject project = mock(MavenProject.class);
        when(project.getBasedir()).thenReturn(tempDir.toFile());
        when(project.getProperties()).thenReturn(new Properties());

        Path testRoot = tempDir.resolve("src/test/java");
        Files.createDirectories(testRoot);

        Set<String> changedTests = ChangeDetectionHelper.detectChangedTestClasses(
                project,
                "explicit",
                testRoot,
                tempDir.resolve("test-hashes.lz4"),
                true,
                new org.apache.maven.plugin.logging.SystemStreamLog());

        assertEquals(Set.of(), changedTests);
    }

    @Test
    void reactorContextUsesGitRootForMultiModuleGitDetection() throws Exception {
        git("init");
        git("config", "user.email", "test@test.com");
        git("config", "user.name", "Test");

        Path moduleDir = tempDir.resolve("module-a");
        Path sourceRoot = moduleDir.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Foo.java"), "package com.example; public class Foo {}\n",
                StandardCharsets.UTF_8);
        git("add", ".");
        git("commit", "-m", "initial");

        Files.writeString(sourceRoot.resolve("Foo.java"),
                "package com.example; public class Foo { int x; }\n", StandardCharsets.UTF_8);

        MavenProject topLevel = mock(MavenProject.class);
        when(topLevel.getBasedir()).thenReturn(tempDir.toFile());
        when(topLevel.getArtifactId()).thenReturn("root");

        MavenProject module = mock(MavenProject.class);
        when(module.getBasedir()).thenReturn(moduleDir.toFile());
        when(module.getArtifactId()).thenReturn("module-a");
        when(module.getProperties()).thenReturn(new Properties());

        MavenSession session = mock(MavenSession.class);
        when(session.getProjects()).thenReturn(List.of(topLevel, module));
        when(session.getTopLevelProject()).thenReturn(topLevel);
        when(session.getUserProperties()).thenReturn(new Properties());
        when(session.getProjectDependencyGraph()).thenReturn(null);

        ReactorContext ctx = new ReactorContext(session, module);
        Set<String> changed = ChangeDetectionHelper.detectChangedClasses(
                ctx,
                "uncommitted",
                null,
                moduleDir.resolve("src/main/java"),
                tempDir.resolve("hashes.lz4"),
                true,
                new org.apache.maven.plugin.logging.SystemStreamLog());

        assertTrue(changed.contains("com.example.Foo"), "changed classes: " + changed);
    }

    private void git(String... args) throws IOException, InterruptedException {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        java.util.Collections.addAll(cmd, args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(tempDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("git " + String.join(" ", args) + " failed with exit code " + exitCode);
        }
    }
}
