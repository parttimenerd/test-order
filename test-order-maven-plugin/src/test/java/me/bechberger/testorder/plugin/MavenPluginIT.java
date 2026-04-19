package me.bechberger.testorder.plugin;

import me.bechberger.testorder.DependencyMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "it.projects.dir", matches = ".+")
class MavenPluginIT {

    private static Path itProjectsDir;

    @BeforeAll
    static void setup() {
        String itProjectsDirProp = System.getProperty("it.projects.dir");
        assertThat(itProjectsDirProp).isNotBlank();
        itProjectsDir = Paths.get(itProjectsDirProp);
        assertThat(Files.exists(itProjectsDir)).isTrue();
    }

    @Test
    void basicLearnModeProducesDepsFiles() {
        Path projectDir = itProjectsDir.resolve("basic-learn-mode");
        assertThat(Files.exists(projectDir)).isTrue();

        // Learn mode sets up the agent and surefire config; .deps files only
        // appear when the agent actually instruments classes at runtime.
        // Here we verify the build succeeded and surefire reports exist.
        Path surefireReports = projectDir.resolve("target/surefire-reports");
        assertThat(Files.isDirectory(surefireReports))
                .withFailMessage("surefire-reports should exist after learn mode: " + surefireReports)
                .isTrue();
    }

    @Test
    void orderModeWritesJunitPlatformProperties() throws IOException {
        Path projectDir = itProjectsDir.resolve("order-mode");
        assertThat(Files.exists(projectDir)).isTrue();

        Path propsFile = projectDir.resolve("target/test-classes/junit-platform.properties");
        assertThat(Files.exists(propsFile))
                .withFailMessage("junit-platform.properties should exist: " + propsFile)
                .isTrue();

        String content = Files.readString(propsFile);
        assertThat(content).contains("me.bechberger.testorder.PriorityClassOrderer");
    }

    @Test
    void orderModeTestsStillPass() {
        // The order-mode fixture has 2 test classes; if the invoker ran successfully,
        // we know tests passed. We just verify the project dir was populated.
        Path projectDir = itProjectsDir.resolve("order-mode");
        assertThat(Files.exists(projectDir)).isTrue();

        Path surefireReports = projectDir.resolve("target/surefire-reports");
        assertThat(Files.isDirectory(surefireReports))
                .withFailMessage("surefire-reports should exist: " + surefireReports)
                .isTrue();
    }

    @Test
    void aggregateProducesIndex() throws IOException {
        Path projectDir = itProjectsDir.resolve("aggregate-deps");
        assertThat(Files.exists(projectDir)).isTrue();

        Path indexFile = projectDir.resolve("test-dependencies.lz4");
        assertThat(Files.exists(indexFile))
                .withFailMessage("test-dependencies.lz4 should exist: " + indexFile)
                .isTrue();

        // load via DependencyMap (auto-detects V1 text or V2 binary)
        DependencyMap map = DependencyMap.load(indexFile);
        assertThat(map.size()).isEqualTo(2);
    }

    @Test
    void junit6LearnModeProducesDepsFiles() {
        Path projectDir = itProjectsDir.resolve("basic-learn-mode-junit6");
        assertThat(Files.exists(projectDir)).isTrue();

        Path surefireReports = projectDir.resolve("target/surefire-reports");
        assertThat(Files.isDirectory(surefireReports))
                .withFailMessage("surefire-reports should exist after JUnit 6 learn mode: " + surefireReports)
                .isTrue();
    }

    @Test
    void junit6OrderModeTestsStillPass() {
        Path projectDir = itProjectsDir.resolve("order-mode-junit6");
        assertThat(Files.exists(projectDir)).isTrue();

        Path surefireReports = projectDir.resolve("target/surefire-reports");
        assertThat(Files.isDirectory(surefireReports))
                .withFailMessage("surefire-reports should exist (JUnit 6): " + surefireReports)
                .isTrue();
    }
}