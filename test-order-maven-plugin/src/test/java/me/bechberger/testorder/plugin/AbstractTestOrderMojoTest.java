package me.bechberger.testorder.plugin;

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class AbstractTestOrderMojoTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveIncludePackages_usesGroupIdOnlyAsFallbackWhenNoSourcePackages() {
        MavenProject project = mock(MavenProject.class);
        when(project.getGroupId()).thenReturn("me.bechberger");
        when(project.getBasedir()).thenReturn(tempDir.toFile());

        Path missingSourceRoot = tempDir.resolve("missing-src-main-java");
        when(project.getCompileSourceRoots()).thenReturn(List.of(missingSourceRoot.toString()));

        Log log = mock(Log.class);

        String include = AbstractTestOrderMojo.resolveIncludePackages(null, true, project, log);

        assertThat(include).isEqualTo("me.bechberger");
    }

    @Test
    void resolveIncludePackages_doesNotAppendGroupIdWhenSourcePackagesDetected() throws IOException {
        MavenProject project = mock(MavenProject.class);
        when(project.getGroupId()).thenReturn("me.bechberger");
        when(project.getBasedir()).thenReturn(tempDir.toFile());

        Path sourceRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(sourceRoot.resolve("com/example/app"));
        Files.writeString(sourceRoot.resolve("com/example/app/App.java"),
                "package com.example.app; class App {}\n");
        when(project.getCompileSourceRoots()).thenReturn(List.of(sourceRoot.toString()));

        Log log = mock(Log.class);

        String include = AbstractTestOrderMojo.resolveIncludePackages(null, true, project, log);

        assertThat(include).isEqualTo("com.example.app");
    }

    @Test
    void resolveIncludePackages_mergesUserPackagesWithDetectedPackagesWithoutRedundantPrefixes()
            throws IOException {
        MavenProject project = mock(MavenProject.class);
        when(project.getGroupId()).thenReturn("me.bechberger");
        when(project.getBasedir()).thenReturn(tempDir.toFile());

        Path sourceRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(sourceRoot.resolve("com/example/app"));
        Files.writeString(sourceRoot.resolve("com/example/app/App.java"),
                "package com.example.app; class App {}\n");
        when(project.getCompileSourceRoots()).thenReturn(List.of(sourceRoot.toString()));

        Log log = mock(Log.class);

        String include = AbstractTestOrderMojo.resolveIncludePackages(
                "com.example,org.lib.extra", true, project, log);

        // com.example.app is covered by com.example and should be collapsed.
        assertThat(include).isEqualTo("com.example,org.lib.extra");
    }
}
