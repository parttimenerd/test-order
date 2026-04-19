package me.bechberger.testorder.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the test-order Gradle plugin using Gradle TestKit.
 * Each test gets a fresh temporary project directory.
 */
class TestOrderPluginIntegrationTest {

    @TempDir
    Path projectDir;

    // ── Scaffolding helpers ──────────────────────────────────────────────

    /** Writes a file relative to projectDir, creating parent dirs. */
    private void writeFile(String relativePath, String content) throws IOException {
        Path target = projectDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    /** Scaffolds a minimal Java + JUnit 5 project with the test-order plugin. */
    private void scaffoldProject() throws IOException {
        writeFile("settings.gradle", """
                pluginManagement {
                    repositories {
                        mavenLocal()
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                rootProject.name = 'test-project'
                """);

        writeFile("build.gradle", """
                plugins {
                    id 'java'
                    id 'me.bechberger.test-order' version '0.1.0-SNAPSHOT'
                }
                group = 'com.example'
                version = '1.0.0'
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                dependencies {
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
                    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.4'
                }
                test {
                    useJUnitPlatform()
                }
                """);

        // Application class
        writeFile("src/main/java/com/example/app/Calculator.java", """
                package com.example.app;
                public class Calculator {
                    public int add(int a, int b) { return a + b; }
                    public int multiply(int a, int b) { return a * b; }
                }
                """);

        writeFile("src/main/java/com/example/app/StringUtils.java", """
                package com.example.app;
                public class StringUtils {
                    public static String reverse(String s) {
                        return new StringBuilder(s).reverse().toString();
                    }
                }
                """);

        // Test classes
        writeFile("src/test/java/com/example/app/CalculatorTest.java", """
                package com.example.app;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class CalculatorTest {
                    @Test void testAdd() { assertEquals(5, new Calculator().add(2, 3)); }
                    @Test void testMultiply() { assertEquals(6, new Calculator().multiply(2, 3)); }
                }
                """);

        writeFile("src/test/java/com/example/app/StringUtilsTest.java", """
                package com.example.app;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class StringUtilsTest {
                    @Test void testReverse() { assertEquals("cba", StringUtils.reverse("abc")); }
                }
                """);
    }

    /** Scaffolds a project that uses an init script instead of the plugins block. */
    private void scaffoldInitScriptProject() throws IOException {
        writeFile("settings.gradle", """
                rootProject.name = 'init-script-project'
                """);

        writeFile("build.gradle", """
                plugins {
                    id 'java'
                }
                group = 'com.example'
                version = '1.0.0'
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                dependencies {
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
                    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.4'
                }
                test {
                    useJUnitPlatform()
                }
                """);

        writeFile("test-order-init.gradle", """
                initscript {
                    repositories {
                        mavenLocal()
                        mavenCentral()
                    }
                    dependencies {
                        classpath 'me.bechberger:test-order-gradle-plugin:0.1.0-SNAPSHOT'
                    }
                }
                projectsLoaded {
                    allprojects { project ->
                        project.plugins.withId('java') {
                            project.apply plugin: me.bechberger.testorder.gradle.TestOrderPlugin
                        }
                    }
                }
                """);

        // Same app + test code
        writeFile("src/main/java/com/example/app/Greeter.java", """
                package com.example.app;
                public class Greeter {
                    public String greet(String name) { return "Hello, " + name + "!"; }
                }
                """);

        writeFile("src/test/java/com/example/app/GreeterTest.java", """
                package com.example.app;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class GreeterTest {
                    @Test void testGreet() { assertEquals("Hello, World!", new Greeter().greet("World")); }
                }
                """);
    }

    private GradleRunner runner(String... args) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(args)
                .forwardOutput();
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Learn mode: produces index and state files")
    void learnModeProducesIndexAndState() throws IOException {
        scaffoldProject();

        BuildResult result = runner("test", "-Dtestorder.mode=learn").build();

        assertEquals(SUCCESS, result.task(":test").getOutcome());
        assertTrue(result.getOutput().contains("[test-order] Configuring learn mode"));
        assertTrue(Files.exists(projectDir.resolve("test-dependencies.lz4")),
                "Index file should be created");
        assertTrue(Files.exists(projectDir.resolve(".test-order-state")),
                "State file should be created");
    }

    @Test
    @DisplayName("Learn mode with --tests still aggregates deps into the index")
    void learnModeWithTestFilterProducesIndex() throws IOException {
        scaffoldProject();

        BuildResult result = runner("test", "-Dtestorder.mode=learn",
                "--tests", "com.example.app.CalculatorTest").build();

        assertEquals(SUCCESS, result.task(":test").getOutcome());
        assertTrue(Files.exists(projectDir.resolve("test-dependencies.lz4")),
                "Index file should be created even when only a filtered test subset runs");
        try (var depsFiles = Files.list(projectDir.resolve("build/test-order-deps"))) {
            assertTrue(depsFiles.anyMatch(path -> path.getFileName().toString().endsWith(".deps")),
                    "Filtered learn runs should still leave .deps files for aggregation");
        }
    }

    @Test
    @DisplayName("Auto mode without index selects learn mode")
    void autoModeWithoutIndexSelectsLearn() throws IOException {
        scaffoldProject();
        assertFalse(Files.exists(projectDir.resolve("test-dependencies.lz4")));

        BuildResult result = runner("test").build();

        assertEquals(SUCCESS, result.task(":test").getOutcome());
        assertTrue(result.getOutput().contains("auto-selecting learn mode"));
    }

    @Test
    @DisplayName("Order mode: reorders tests when index exists")
    void orderModeReordersTests() throws IOException {
        scaffoldProject();

        // First: learn
        runner("test", "-Dtestorder.mode=learn").build();
        assertTrue(Files.exists(projectDir.resolve("test-dependencies.lz4")));

        // Second: order
        BuildResult result = runner("clean", "test", "-Dtestorder.mode=order").build();

        assertEquals(SUCCESS, result.task(":test").getOutcome());
        assertTrue(result.getOutput().contains("[test-order] Configuring order mode"));
    }

    @Test
    @DisplayName("Auto mode with index selects order mode")
    void autoModeWithIndexSelectsOrder() throws IOException {
        scaffoldProject();

        // Learn first
        runner("test", "-Dtestorder.mode=learn").build();

        // Auto should select order
        BuildResult result = runner("clean", "test").build();

        assertEquals(SUCCESS, result.task(":test").getOutcome());
        assertTrue(result.getOutput().contains("Configuring order mode"));
    }

    @Test
    @DisplayName("Skip mode does not configure learn or order")
    void skipModeDisablesPlugin() throws IOException {
        scaffoldProject();

        BuildResult result = runner("test", "-Dtestorder.mode=skip").build();

        assertEquals(SUCCESS, result.task(":test").getOutcome());
        assertFalse(result.getOutput().contains("Configuring learn mode"));
        assertFalse(result.getOutput().contains("Configuring order mode"));
    }

    @Test
    @DisplayName("testOrderShowOrder task works after learn")
    void showOrderTask() throws IOException {
        scaffoldProject();

        // Learn first
        runner("test", "-Dtestorder.mode=learn").build();

        // Show order
        BuildResult result = runner("testOrderShowOrder").build();

        assertEquals(SUCCESS, result.task(":testOrderShowOrder").getOutcome());
        assertTrue(result.getOutput().contains("Predicted test execution order"));
        assertTrue(result.getOutput().contains("CalculatorTest"));
        assertTrue(result.getOutput().contains("StringUtilsTest"));
    }

    @Test
    @DisplayName("testOrderShowOrder honors explicit changed classes")
    void showOrderHonorsExplicitChangedClasses() throws IOException {
        scaffoldProject();

        // Learn first so index/state exist.
        runner("test", "-Dtestorder.mode=learn").build();

        BuildResult result = runner("testOrderShowOrder",
                "-Dtestorder.changed.classes=com.example.app.Calculator").build();

        assertEquals(SUCCESS, result.task(":testOrderShowOrder").getOutcome());
        assertTrue(result.getOutput().contains("Changed classes: com.example.app.Calculator"),
                "show-order should display explicit changed classes");
    }

    @Test
    @DisplayName("testOrderDump task works after learn")
    void dumpTask() throws IOException {
        scaffoldProject();

        runner("test", "-Dtestorder.mode=learn").build();

        BuildResult result = runner("testOrderDump").build();

        assertEquals(SUCCESS, result.task(":testOrderDump").getOutcome());
    }

    @Test
    @DisplayName("testOrderClean removes generated files")
    void cleanTask() throws IOException {
        scaffoldProject();

        // Learn to create files
        runner("test", "-Dtestorder.mode=learn").build();
        assertTrue(Files.exists(projectDir.resolve("test-dependencies.lz4")));
        assertTrue(Files.exists(projectDir.resolve(".test-order-state")));

        // Clean
        BuildResult result = runner("testOrderClean").build();

        assertEquals(SUCCESS, result.task(":testOrderClean").getOutcome());
        assertFalse(Files.exists(projectDir.resolve("test-dependencies.lz4")),
                "Index should be deleted");
        assertFalse(Files.exists(projectDir.resolve(".test-order-state")),
                "State should be deleted");
    }

    @Test
    @DisplayName("testOrderShowOrder fails gracefully without index")
    void showOrderWithoutIndexFails() throws IOException {
        scaffoldProject();

        BuildResult result = runner("testOrderShowOrder").buildAndFail();

        assertTrue(result.getOutput().contains("Index file not found"));
    }

    @Test
    @DisplayName("Learn → Order → Learn cycle works")
    void fullCycleLearnOrderLearn() throws IOException {
        scaffoldProject();

        // First learn
        runner("test", "-Dtestorder.mode=learn").build();
        long indexSize1 = Files.size(projectDir.resolve("test-dependencies.lz4"));
        assertTrue(indexSize1 > 0);

        // Order
        BuildResult orderResult = runner("clean", "test", "-Dtestorder.mode=order").build();
        assertEquals(SUCCESS, orderResult.task(":test").getOutcome());

        // Second learn (should overwrite index)
        runner("clean", "test", "-Dtestorder.mode=learn").build();
        long indexSize2 = Files.size(projectDir.resolve("test-dependencies.lz4"));
        assertTrue(indexSize2 > 0);
    }

    @Test
    @DisplayName("Init script: learn mode works without plugins block")
    void initScriptLearnMode() throws IOException {
        scaffoldInitScriptProject();

        Path initScript = projectDir.resolve("test-order-init.gradle");
        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("test", "-Dtestorder.mode=learn",
                        "--init-script", initScript.toString())
                .forwardOutput()
                .build();

        assertEquals(SUCCESS, result.task(":test").getOutcome());
        assertTrue(result.getOutput().contains("[test-order] Configuring learn mode"));
        assertTrue(Files.exists(projectDir.resolve("test-dependencies.lz4")));
    }

    @Test
    @DisplayName("Init script: learn → order cycle works")
    void initScriptFullCycle() throws IOException {
        scaffoldInitScriptProject();

        Path initScript = projectDir.resolve("test-order-init.gradle");

        // Learn
        GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("test", "-Dtestorder.mode=learn",
                        "--init-script", initScript.toString())
                .forwardOutput()
                .build();

        // Order
        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("clean", "test", "-Dtestorder.mode=order",
                        "--init-script", initScript.toString())
                .forwardOutput()
                .build();

        assertEquals(SUCCESS, result.task(":test").getOutcome());
        assertTrue(result.getOutput().contains("Configuring order mode"));
    }

    @Test
    @DisplayName("State file records test durations")
    void stateFileRecordsDurations() throws IOException {
        scaffoldProject();

        runner("test", "-Dtestorder.mode=learn").build();

        Path stateFile = projectDir.resolve(".test-order-state");
        assertTrue(Files.exists(stateFile));
        assertTrue(Files.size(stateFile) > 0,
                "State file should not be empty");
    }

    @Test
    @DisplayName("Plugin is idempotent — double-apply does not crash")
    void doubleApplyIsIdempotent() throws IOException {
        writeFile("settings.gradle", """
                pluginManagement {
                    repositories {
                        mavenLocal()
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                rootProject.name = 'double-apply-project'
                """);

        // Apply plugin twice (simulates init-script + build.gradle collision)
        writeFile("build.gradle", """
                plugins {
                    id 'java'
                    id 'me.bechberger.test-order' version '0.1.0-SNAPSHOT'
                }
                // Second apply (e.g. from init script)
                apply plugin: 'me.bechberger.test-order'

                group = 'com.example'
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                dependencies {
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
                    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.4'
                }
                test { useJUnitPlatform() }
                """);

        writeFile("src/main/java/com/example/app/Foo.java", """
                package com.example.app;
                public class Foo { public int bar() { return 42; } }
                """);
        writeFile("src/test/java/com/example/app/FooTest.java", """
                package com.example.app;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class FooTest { @Test void test() { assertEquals(42, new Foo().bar()); } }
                """);

        BuildResult result = runner("test", "-Dtestorder.mode=learn").build();
        assertEquals(SUCCESS, result.task(":test").getOutcome());
    }
}
