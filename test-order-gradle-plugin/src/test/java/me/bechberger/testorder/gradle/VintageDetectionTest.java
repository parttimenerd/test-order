package me.bechberger.testorder.gradle;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests verifying that the JUnit Vintage engine detection suppresses the
 * JUnit 4 unsupported warning.
 */
class VintageDetectionTest {

    @TempDir
    Path tempDir;

    @Test
    void isJUnitVintageOnTestClasspath_trueWhenVintagePresent() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPluginManager().apply("java");
        project.getRepositories().mavenCentral();
        project.getDependencies().add("testImplementation", "junit:junit:4.13.2");
        project.getDependencies().add("testImplementation", "org.junit.vintage:junit-vintage-engine:5.11.4");

        assertTrue(TestOrderPlugin.isJUnitVintageOnTestClasspath(project));
        assertTrue(TestOrderPlugin.isJUnit4OnTestClasspath(project));
    }

    @Test
    void isJUnitVintageOnTestClasspath_falseWhenOnlyJUnit4() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPluginManager().apply("java");
        project.getRepositories().mavenCentral();
        project.getDependencies().add("testImplementation", "junit:junit:4.13.2");

        assertFalse(TestOrderPlugin.isJUnitVintageOnTestClasspath(project));
        assertTrue(TestOrderPlugin.isJUnit4OnTestClasspath(project));
    }

    @Test
    void isJUnitVintageOnTestClasspath_falseWhenOnlyJUnit5() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPluginManager().apply("java");
        project.getRepositories().mavenCentral();
        project.getDependencies().add("testImplementation", "org.junit.jupiter:junit-jupiter:5.11.4");

        assertFalse(TestOrderPlugin.isJUnitVintageOnTestClasspath(project));
        assertFalse(TestOrderPlugin.isJUnit4OnTestClasspath(project));
        assertTrue(TestOrderPlugin.isJUnit5OnTestClasspath(project));
    }
}
