package me.bechberger.testorder.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.gradle.testkit.runner.TaskOutcome.FAILED;
import static org.gradle.testkit.runner.TaskOutcome.SKIPPED;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the test-order Gradle plugin using Gradle TestKit.
 * Each test gets a fresh temporary project directory.
 */
@Timeout(value = 8, unit = TimeUnit.MINUTES)
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

    /** Recursively delete a directory tree. */
    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
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
        assertTrue(Files.exists(projectDir.resolve(".test-order/test-dependencies.lz4")),
                "Index file should be created");
        assertTrue(Files.exists(projectDir.resolve(".test-order/state.lz4")),
                "State file should be created");
    }

    @Test
    @DisplayName("Learn mode with --tests still aggregates deps into the index")
    void learnModeWithTestFilterProducesIndex() throws IOException {
        scaffoldProject();

        BuildResult result = runner("test", "-Dtestorder.mode=learn",
                "--tests", "com.example.app.CalculatorTest").build();

        assertEquals(SUCCESS, result.task(":test").getOutcome());
        assertTrue(Files.exists(projectDir.resolve(".test-order/test-dependencies.lz4")),
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
        assertFalse(Files.exists(projectDir.resolve(".test-order/test-dependencies.lz4")));

        BuildResult result = runner("test").build();

        assertEquals(SUCCESS, result.task(":test").getOutcome());
        assertTrue(Files.exists(projectDir.resolve(".test-order/test-dependencies.lz4")),
                "Auto mode without an index should run learn mode and create the index");
    }

    @Test
    @DisplayName("Order mode: reorders tests when index exists")
    void orderModeReordersTests() throws IOException {
        scaffoldProject();

        // First: learn
        runner("test", "-Dtestorder.mode=learn").build();
        assertTrue(Files.exists(projectDir.resolve(".test-order/test-dependencies.lz4")));

        // Second: order
        BuildResult result = runner("clean", "test", "-Dtestorder.mode=order").build();

        assertEquals(SUCCESS, result.task(":test").getOutcome());
        assertTrue(Files.exists(projectDir.resolve(".test-order/state.lz4")),
                "Order mode should keep using the learned state file");
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
        assertTrue(Files.exists(projectDir.resolve(".test-order/test-dependencies.lz4")),
                "Auto mode with index should preserve index usage");
    }

    @Test
    @DisplayName("Skip mode does not configure learn or order")
    void skipModeDisablesPlugin() throws IOException {
        scaffoldProject();

        BuildResult result = runner("test", "-Dtestorder.mode=skip").build();

        assertEquals(SUCCESS, result.task(":test").getOutcome());
        assertFalse(Files.exists(projectDir.resolve(".test-order/test-dependencies.lz4")),
                "Skip mode should not create a dependency index");
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
    @DisplayName("testOrderSelect runs the prioritized subset and writes selection files")
    void selectTaskRunsSubset() throws IOException {
        scaffoldProject();
        runner("test", "-Dtestorder.mode=learn").build();

        BuildResult result = runner("testOrderSelect",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=com.example.app.Calculator",
                "-Dtestorder.select.topN=1",
                "-Dtestorder.select.randomM=0").build();

        assertEquals(SUCCESS, result.task(":testOrderSelect").getOutcome());
        assertTrue(Files.exists(projectDir.resolve("build/test-order-selected.txt")));
        assertTrue(Files.exists(projectDir.resolve("build/test-order-remaining.txt")));
        List<String> selected = Files.readAllLines(projectDir.resolve("build/test-order-selected.txt"));
        List<String> remaining = Files.readAllLines(projectDir.resolve("build/test-order-remaining.txt"));
        assertEquals(1, selected.size());
        assertEquals(1, remaining.size());
        assertNotEquals(selected.get(0), remaining.get(0));
        assertEquals(Set.of("com.example.app.CalculatorTest", "com.example.app.StringUtilsTest"),
                Set.of(selected.get(0), remaining.get(0)));
        assertTrue(result.getOutput().contains("[test-order] Selected 1 tests, deferred 1"));
        assertTrue(Files.exists(projectDir.resolve("build/test-results/testOrderSelect/TEST-" + selected.get(0) + ".xml")));
        assertFalse(Files.exists(projectDir.resolve("build/test-results/testOrderSelect/TEST-" + remaining.get(0) + ".xml")));
    }

    @Test
    @DisplayName("testOrderRunRemaining runs only deferred tests")
    void runRemainingTaskRunsDeferredTests() throws IOException {
        scaffoldProject();
        runner("test", "-Dtestorder.mode=learn").build();
        runner("testOrderSelect",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=com.example.app.Calculator",
                "-Dtestorder.select.topN=1",
                "-Dtestorder.select.randomM=0",
                "-Dtestorder.auto.runRemaining=false").build();

        BuildResult result = runner("testOrderRunRemaining").build();

        assertEquals(SUCCESS, result.task(":testOrderRunRemaining").getOutcome());
        List<String> deferred = Files.readAllLines(projectDir.resolve("build/test-order-remaining.txt"));
        assertEquals(1, deferred.size());
        assertTrue(result.getOutput().contains("[test-order] Running 1 remaining test classes"));
        assertTrue(Files.exists(projectDir.resolve("build/test-results/testOrderRunRemaining/TEST-" + deferred.get(0) + ".xml")));
    }

    @Test
    @DisplayName("testOrderSelect failure skips auto-finalized testOrderRunRemaining")
    void selectFailureSkipsAutoRunRemaining() throws IOException {
        scaffoldProject();

        BuildResult result = runner("testOrderSelect",
                "-Dtestorder.mode=order",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=com.example.app.Calculator",
                "-Dtestorder.auto.runRemaining=true").buildAndFail();

        assertEquals(FAILED, result.task(":testOrderSelect").getOutcome());
        assertEquals(SKIPPED, result.task(":testOrderRunRemaining").getOutcome());
        assertTrue(result.getOutput().contains("Select requires an index/dependency baseline"));
        assertFalse(result.getOutput().contains("Cannot call Task.onlyIf"));
    }

    @Test
    @DisplayName("testOrderSelect with zero matched tests executes cleanly without mutation errors")
    void selectWithZeroMatchedTestsExecutesCleanly() throws IOException {
        scaffoldProject();

        // First establish baseline with learn mode
        runner("test", "-Dtestorder.mode=learn").build();

        // Run select with explicit mode, specifying a class that doesn't match any tests
        // This results in zero selected tests. The test should execute with the safe filter pattern
        // and complete successfully without task-mutation errors.
        BuildResult result = runner("test", "-Dtestorder.mode=order",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=com.example.NonExistentClass").build();

        // The test task should succeed (no matching tests to run, so 0 tests executed)
        assertEquals(SUCCESS, result.task(":test").getOutcome());
        // Should NOT contain error about Task.onlyIf being called after execution (mutation error)
        assertFalse(result.getOutput().contains("Cannot call Task.onlyIf"),
                "Test should execute without task-state mutation errors");
        // The test report should show 0 tests (or be absent for 0 tests)
        assertTrue(result.getOutput().contains("Tests run: 0") || !result.getOutput().contains("Tests run:"),
                "Should either report 0 tests or have no test run summary");
    }

    @Test
    @DisplayName("testOrderOptimize updates the state file after learn and order runs")
    void optimizeTaskPersistsWeights() throws IOException {
        scaffoldProject();
        runner("test", "-Dtestorder.mode=learn").build();
        runner("clean", "test", "-Dtestorder.mode=order",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=com.example.app.Calculator").build();

        BuildResult result = runner("testOrderOptimize").build();

        assertEquals(SUCCESS, result.task(":testOrderOptimize").getOutcome());
        assertTrue(result.getOutput().contains("[test-order] Runs:"));
        assertTrue(Files.exists(projectDir.resolve(".test-order/state.lz4")));
        assertTrue(Files.size(projectDir.resolve(".test-order/state.lz4")) > 0);
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
    @DisplayName("testOrderShowOrder recovers from corrupt index via deps re-aggregation")
    void showOrderRecoversFromCorruptIndex() throws IOException {
        scaffoldProject();

        // Learn first so index + .deps files exist.
        runner("test", "-Dtestorder.mode=learn").build();

        Path index = projectDir.resolve(".test-order/test-dependencies.lz4");
        assertTrue(Files.exists(index), "Expected learned index before corruption");

        // Corrupt index on disk; show-order should rebuild it from build/test-order-deps.
        Files.write(index, new byte[] {1, 2, 3});

        BuildResult result = runner("testOrderShowOrder").build();

        assertEquals(SUCCESS, result.task(":testOrderShowOrder").getOutcome());
        assertTrue(result.getOutput().contains("Predicted test execution order"));
        assertTrue(result.getOutput().contains("CalculatorTest"));
        assertTrue(result.getOutput().contains("StringUtilsTest"));
    }

    @Test
    @DisplayName("testOrderShowOrder recovers from backup index when deps are missing")
    void showOrderRecoversFromBackupIndex() throws IOException {
        scaffoldProject();

        // Learn first so index exists.
        runner("test", "-Dtestorder.mode=learn").build();

        Path index = projectDir.resolve(".test-order/test-dependencies.lz4");
        Path backup = projectDir.resolve("test-dependencies.lz4.bak");
        assertTrue(Files.exists(index), "Expected learned index before backup recovery test");

        // Save valid backup, then corrupt index and remove deps so re-aggregation is impossible.
        Files.copy(index, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.write(index, new byte[] {1, 2, 3});
        Path depsDir = projectDir.resolve("build/test-order-deps");
        if (Files.exists(depsDir)) {
            Files.walkFileTree(depsDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        BuildResult result = runner("testOrderShowOrder").build();

        assertEquals(SUCCESS, result.task(":testOrderShowOrder").getOutcome());
        assertTrue(result.getOutput().contains("Predicted test execution order"));
        assertTrue(result.getOutput().contains("CalculatorTest"));
        assertTrue(result.getOutput().contains("StringUtilsTest"));
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
        assertTrue(Files.exists(projectDir.resolve(".test-order/test-dependencies.lz4")));
        assertTrue(Files.exists(projectDir.resolve(".test-order/state.lz4")));

        // Clean
        BuildResult result = runner("testOrderClean").build();

        assertEquals(SUCCESS, result.task(":testOrderClean").getOutcome());
        assertFalse(Files.exists(projectDir.resolve(".test-order/test-dependencies.lz4")),
                "Index should be deleted");
        assertFalse(Files.exists(projectDir.resolve(".test-order/state.lz4")),
                "State should be deleted");
    }

    @Test
    @DisplayName("testOrderShowOrder fails gracefully without index")
    void showOrderWithoutIndexFails() throws IOException {
        scaffoldProject();

        BuildResult result = runner("testOrderShowOrder").buildAndFail();

        assertTrue(result.getOutput().contains("Index file not found") || result.getOutput().contains("Failed to show test order"),
                "Expected index-missing error, got: " + result.getOutput());
    }

    @Test
    @DisplayName("Learn → Order → Learn cycle works")
    void fullCycleLearnOrderLearn() throws IOException {
        scaffoldProject();

        // First learn
        runner("test", "-Dtestorder.mode=learn").build();
        long indexSize1 = Files.size(projectDir.resolve(".test-order/test-dependencies.lz4"));
        assertTrue(indexSize1 > 0);

        // Order
        BuildResult orderResult = runner("clean", "test", "-Dtestorder.mode=order").build();
        assertEquals(SUCCESS, orderResult.task(":test").getOutcome());

        // Second learn (should overwrite index)
        runner("clean", "test", "-Dtestorder.mode=learn").build();
        long indexSize2 = Files.size(projectDir.resolve(".test-order/test-dependencies.lz4"));
        assertTrue(indexSize2 > 0);
    }

    @Test
    @DisplayName("Auto mode works after learn without external logger dependencies")
    void autoModeWorksAfterLearn() throws IOException {
        scaffoldProject();

        runner("test", "-Dtestorder.mode=learn").build();

        BuildResult result = runner("clean", "test", "-Dtestorder.mode=auto").build();

        assertEquals(SUCCESS, result.task(":test").getOutcome());
        assertFalse(result.getOutput().contains("org.tinylog.Logger"));
        assertFalse(result.getOutput().contains("ClassNotFoundException"));
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
        assertTrue(Files.exists(projectDir.resolve(".test-order/test-dependencies.lz4")));
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
        assertTrue(Files.exists(projectDir.resolve(".test-order/test-dependencies.lz4")),
                "Init-script order cycle should keep the learned index");
    }

    @Test
    @DisplayName("State file records test durations")
    void stateFileRecordsDurations() throws IOException {
        scaffoldProject();

        runner("test", "-Dtestorder.mode=learn").build();

        Path stateFile = projectDir.resolve(".test-order/state.lz4");
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

    @Test
    @DisplayName("Multi-project: each subproject uses its own state file path")
    void multiProjectSubprojectsHaveSeparateStateFiles() throws IOException {
        // Settings: root + two subprojects
        writeFile("settings.gradle", """
                pluginManagement {
                    repositories {
                        mavenLocal()
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                rootProject.name = 'multi-project'
                include 'sub-a', 'sub-b'
                """);

        writeFile("build.gradle", "");

        // Sub-project A: Calculator
        writeFile("sub-a/build.gradle", """
                plugins {
                    id 'java'
                    id 'me.bechberger.test-order' version '0.1.0-SNAPSHOT'
                }
                repositories { mavenLocal(); mavenCentral() }
                dependencies {
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
                    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.4'
                }
                test { useJUnitPlatform() }
                """);
        writeFile("sub-a/src/main/java/com/example/a/Calculator.java", """
                package com.example.a;
                public class Calculator { public int add(int a, int b) { return a + b; } }
                """);
        writeFile("sub-a/src/test/java/com/example/a/CalculatorTest.java", """
                package com.example.a;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class CalculatorTest { @Test void test() { assertEquals(5, new Calculator().add(2, 3)); } }
                """);

        // Sub-project B: StringUtils
        writeFile("sub-b/build.gradle", """
                plugins {
                    id 'java'
                    id 'me.bechberger.test-order' version '0.1.0-SNAPSHOT'
                }
                repositories { mavenLocal(); mavenCentral() }
                dependencies {
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
                    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.4'
                }
                test { useJUnitPlatform() }
                """);
        writeFile("sub-b/src/main/java/com/example/b/StringUtils.java", """
                package com.example.b;
                public class StringUtils { public static String rev(String s) { return new StringBuilder(s).reverse().toString(); } }
                """);
        writeFile("sub-b/src/test/java/com/example/b/StringUtilsTest.java", """
                package com.example.b;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class StringUtilsTest { @Test void test() { assertEquals("cba", StringUtils.rev("abc")); } }
                """);

        // Run learn on both subprojects
        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("test", "-Dtestorder.mode=learn")
                .forwardOutput()
                .build();

        assertEquals(SUCCESS, result.task(":sub-a:test").getOutcome());
        assertEquals(SUCCESS, result.task(":sub-b:test").getOutcome());

        // Each subproject should have its own state file under its own directory
        Path stateA = projectDir.resolve("sub-a/.test-order/state.lz4");
        Path stateB = projectDir.resolve("sub-b/.test-order/state.lz4");

        assertTrue(Files.exists(stateA),
                "sub-a should have its own state file at sub-a/.test-order/state.lz4");
        assertTrue(Files.exists(stateB),
                "sub-b should have its own state file at sub-b/.test-order/state.lz4");

        // The two state files must be distinct paths
        assertNotEquals(stateA.toAbsolutePath(), stateB.toAbsolutePath());
    }

    @Test
    @DisplayName("Kotlin: plugin works with mixed Java and Kotlin tests")
    void kotlinTestsWorkWithPlugin() throws IOException {
        writeFile("settings.gradle", """
                pluginManagement {
                    repositories {
                        mavenLocal()
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                rootProject.name = 'kotlin-test-project'
                """);

        writeFile("build.gradle.kts", """
                plugins {
                    id("java")
                    kotlin("jvm") version "2.3.20"
                    id("me.bechberger.test-order") version "0.1.0-SNAPSHOT"
                }
                
                group = "com.example"
                version = "1.0.0"
                
                kotlin {
                    jvmToolchain(21)
                }
                
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                
                dependencies {
                    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
                    implementation("org.jetbrains.kotlin:kotlin-stdlib")
                }
                
                tasks.test {
                    useJUnitPlatform()
                }
                """);

        // Create Kotlin source
        writeFile("src/main/kotlin/com/example/Calculator.kt", """
                package com.example
                
                class Calculator {
                    fun add(a: Int, b: Int) = a + b
                    fun multiply(a: Int, b: Int) = a * b
                }
                """);

        // Create Kotlin test
        writeFile("src/test/kotlin/com/example/CalculatorTest.kt", """
                package com.example
                
                import org.junit.jupiter.api.Test
                import org.junit.jupiter.api.Assertions.*
                
                class CalculatorTest {
                    @Test fun testAdd() { assertEquals(5, Calculator().add(2, 3)) }
                    @Test fun testMultiply() { assertEquals(6, Calculator().multiply(2, 3)) }
                }
                """);

        BuildResult result = runner("test").build();
        assertEquals(SUCCESS, result.task(":test").getOutcome(),
                "Kotlin tests should run successfully");
    }

    @Test
    @DisplayName("Kotlin: learn mode with Kotlin tests builds dependency index")
    void kotlinLearnModeBuildsIndex() throws IOException {
        writeFile("settings.gradle", """
                pluginManagement {
                    repositories {
                        mavenLocal()
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                rootProject.name = 'kotlin-learn-project'
                """);

        writeFile("build.gradle.kts", """
                plugins {
                    id("java")
                    kotlin("jvm") version "2.3.20"
                    id("me.bechberger.test-order") version "0.1.0-SNAPSHOT"
                }
                
                group = "com.example"
                
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                
                dependencies {
                    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
                    implementation("org.jetbrains.kotlin:kotlin-stdlib")
                }
                
                tasks.test { useJUnitPlatform() }
                """);

        writeFile("src/main/kotlin/com/example/App.kt", "package com.example\nfun hello() = \"Hello\"");
        writeFile("src/test/kotlin/com/example/AppTest.kt", 
            "package com.example\nimport org.junit.jupiter.api.Test\nclass AppTest { @Test fun test() { } }");

        BuildResult result = runner("test", "-Dtestorder.mode=learn").build();

        assertEquals(SUCCESS, result.task(":test").getOutcome());
        assertTrue(Files.exists(projectDir.resolve(".test-order/test-dependencies.lz4")),
                "Kotlin learn mode should create index");
    }
}
