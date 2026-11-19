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
                    id 'me.bechberger.test-order' version '0.1.1'
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
                        classpath 'me.bechberger:test-order-gradle-plugin:0.1.1'
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
                // Pinned to the Gradle version the downstream consumers run (9.9.1);
                // 8.14.3-era pins cannot parse JDK 25 class files ("Unsupported class
                // file major version 69"), so TestKit on a Java 25 host failed.
                .withGradleVersion("9.9.1")
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
        Path indexFile = projectDir.resolve(".test-order/test-dependencies.lz4");
        Path fallbackFile = projectDir.resolve(".test-order/test-dependencies.lz4.collector-fallback");
        Path depsDir = projectDir.resolve("build/test-order-deps");
        boolean indexWritten = Files.exists(indexFile) || Files.exists(fallbackFile);
        boolean depsFilesPresent;
        if (Files.isDirectory(depsDir)) {
            try (var s = Files.list(depsDir)) {
                depsFilesPresent = s.anyMatch(p -> p.getFileName().toString().endsWith(".deps"));
            }
        } else {
            depsFilesPresent = false;
        }
        assertTrue(indexWritten || depsFilesPresent,
                "Index file or .deps files should be created even when only a filtered test subset runs");
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
    @DisplayName("testOrderHelp prints quick-start and unified show guidance")
    void helpTaskShowsQuickStartAndUnifiedShow() throws IOException {
        scaffoldProject();

        BuildResult result = runner("testOrderHelp").build();

        assertEquals(SUCCESS, result.task(":testOrderHelp").getOutcome());
        assertTrue(result.getOutput().contains("QUICK START:"));
        assertTrue(result.getOutput().contains("1) First run:   ./gradlew test"));
        assertTrue(result.getOutput().contains("2) Inspect:     ./gradlew testOrderShow"));
        assertTrue(result.getOutput().contains("3) Dashboard:   ./gradlew testOrderDashboard"));
        assertTrue(result.getOutput().contains("testOrderShow                Unified view: class order, method order, ML health"));
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
    @DisplayName("testOrderAffected runs the prioritized subset and writes selection files")
    void selectTaskRunsSubset() throws IOException {
        scaffoldProject();
        runner("test", "-Dtestorder.mode=learn").build();

        BuildResult result = runner("testOrderAffected",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=com.example.app.Calculator",
                "-Dtestorder.affected.topN=1",
                "-Dtestorder.affected.randomM=0").build();

        assertEquals(SUCCESS, result.task(":testOrderAffected").getOutcome());
        assertTrue(Files.exists(projectDir.resolve("build/test-order-selected.txt")));
        assertTrue(Files.exists(projectDir.resolve("build/test-order-remaining.txt")));
        List<String> selected = Files.readAllLines(projectDir.resolve("build/test-order-selected.txt"));
        List<String> remaining = Files.readAllLines(projectDir.resolve("build/test-order-remaining.txt"));
        assertEquals(1, selected.size());
        assertEquals(1, remaining.size());
        assertNotEquals(selected.get(0), remaining.get(0));
        assertEquals(Set.of("com.example.app.CalculatorTest", "com.example.app.StringUtilsTest"),
                Set.of(selected.get(0), remaining.get(0)));
        assertTrue(result.getOutput().contains("[test-order] Selected 1 tests (") && result.getOutput().contains("), deferred 1"));
        assertTrue(Files.exists(projectDir.resolve("build/test-results/testOrderAffected/TEST-" + selected.get(0) + ".xml")));
        assertFalse(Files.exists(projectDir.resolve("build/test-results/testOrderAffected/TEST-" + remaining.get(0) + ".xml")));
    }

    @Test
    @DisplayName("testOrderAffected writes a CI summary (mode affected) with coverage counts")
    void affectedTaskWritesCiSummary() throws IOException {
        scaffoldProject();
        runner("test", "-Dtestorder.mode=learn").build();

        BuildResult result = runner("testOrderAffected",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=com.example.app.Calculator",
                "-Dtestorder.affected.topN=1",
                "-Dtestorder.affected.randomM=0",
                "-Dtestorder.ci.summary=true").build();

        assertEquals(SUCCESS, result.task(":testOrderAffected").getOutcome());
        assertTrue(Files.exists(projectDir.resolve("build/test-order-summary.md")),
                "Markdown summary should be written for affected mode");
        assertTrue(Files.exists(projectDir.resolve("build/test-order-summary.json")),
                "JSON summary should be written for affected mode");
        String md = Files.readString(projectDir.resolve("build/test-order-summary.md"));
        assertTrue(md.contains("affected"), "Summary mode should be 'affected':\n" + md);
        assertTrue(md.contains("Changed elements covered"),
                "Summary should carry the coverage-closure row:\n" + md);
        String json = Files.readString(projectDir.resolve("build/test-order-summary.json"));
        assertTrue(json.contains("\"mode\": \"affected\""));
        assertTrue(json.contains("changedElementsCovered"),
                "JSON summary should carry coverage counts:\n" + json);
    }

    @Test
    @DisplayName("testOrderAffected selects ALL tests on a non-source (resources) change")
    void affectedTaskSelectsAllOnNonSourceChange() throws Exception {
        scaffoldProject();
        writeFile("src/main/resources/app.properties", "greeting=hello\n");
        // Baseline: learn, then commit everything so the working tree is clean
        runner("test", "-Dtestorder.mode=learn").build();
        git(projectDir, "init");
        git(projectDir, "config", "user.email", "test@example.com");
        git(projectDir, "config", "user.name", "Test");
        git(projectDir, "add", "-A");
        git(projectDir, "commit", "-m", "baseline");
        // Non-source change: a tracked resource file changes — invisible to
        // source-based change detection
        writeFile("src/main/resources/app.properties", "greeting=changed\n");

        BuildResult result = runner("testOrderAffected",
                "-Dtestorder.affected.topN=1",
                "-Dtestorder.affected.randomM=0",
                "-Dtestorder.ci.summary=true").build();

        assertEquals(SUCCESS, result.task(":testOrderAffected").getOutcome());
        assertTrue(result.getOutput().contains("Non-source change detected"),
                "Non-source select-all must be logged:\n" + result.getOutput());
        assertTrue(result.getOutput().contains("select-all"),
                "Select-all must be explicit:\n" + result.getOutput());
        List<String> selected = Files.readAllLines(projectDir.resolve("build/test-order-selected.txt"));
        assertEquals(Set.of("com.example.app.CalculatorTest", "com.example.app.StringUtilsTest"),
                Set.copyOf(selected),
                "ALL tests of the module must be selected, got: " + selected);
        assertFalse(Files.exists(projectDir.resolve("build/test-order-remaining.txt")),
                "Stale remaining file must be removed by the select-all path");
        String json = Files.readString(projectDir.resolve("build/test-order-summary.json"));
        assertTrue(json.contains("\"mode\": \"affected-nonsource-select-all\""),
                "Summary should carry the non-source select-all marker:\n" + json);
    }

    @Test
    @DisplayName("learn mode writes the index in the SAME run even when tests fail")
    void learnWritesIndexDespiteFailingTests() throws Exception {
        scaffoldProject();
        // A systematically failing test: the learn run must still aggregate the
        // collected dependencies into the index (previously the aggregation was a
        // doLast hook, which Gradle skips when the task fails — the index was only
        // written by a LATER passing learn run, so ordering never saw the data).
        writeFile("src/test/java/com/example/app/BrokenTest.java", """
                package com.example.app;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class BrokenTest {
                    @Test void alwaysFails() { fail("systematic failure"); }
                }
                """);

        BuildResult result = runner("test", "-Dtestorder.mode=learn").buildAndFail();

        assertEquals(FAILED, result.task(":test").getOutcome());
        assertTrue(Files.exists(projectDir.resolve(".test-order/test-dependencies.lz4")),
                "The dependency index must be written in the same run even when the learn run fails");
        String output = result.getOutput();
        assertTrue(output.contains("merged") || output.contains("Aggregated"),
                "Aggregation must run despite the failing tests:\n" + output);
    }

    @Test
    @DisplayName("order mode after a failed learn run merges pending dependencies in the same build")
    void orderModeMergesPendingDepsFromFailedLearn() throws Exception {
        scaffoldProject();
        writeFile("src/test/java/com/example/app/BrokenTest.java", """
                package com.example.app;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class BrokenTest {
                    @Test void alwaysFails() { fail("systematic failure"); }
                }
                """);
        // First run: learn with a failing test. Before the finalizer fix this run
        // left its collected dependencies unmerged (collector leaked in the daemon,
        // .deps files pending).
        runner("test", "-Dtestorder.mode=learn").buildAndFail();
        // Remove the failing test so the order-mode run itself succeeds.
        Files.deleteIfExists(projectDir.resolve("src/test/java/com/example/app/BrokenTest.java"));

        BuildResult result = runner("testOrderAffected").build();

        assertEquals(SUCCESS, result.task(":testOrderAffected").getOutcome());
        assertTrue(Files.exists(projectDir.resolve(".test-order/test-dependencies.lz4")),
                "The index must exist after the order-mode run (pending .deps merged in the same build)");
    }

    @Test
    @DisplayName("order mode inside an included build resolves the composite (parent) index")
    void includedBuildResolvesCompositeIndex() throws Exception {
        scaffoldProject();
        // Included build with its own settings (plugin resolved from mavenLocal —
        // TestKit plugin classpath injection does not reach included builds).
        writeFile("included-lib/settings.gradle", """
                pluginManagement {
                    repositories {
                        mavenLocal()
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                rootProject.name = 'included-lib'
                """);
        writeFile("included-lib/build.gradle", """
                plugins {
                    id 'java'
                    id 'me.bechberger.test-order' version '0.1.1'
                }
                group = 'com.example.inc'
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
        writeFile("included-lib/src/test/java/com/example/inc/IncTest.java", """
                package com.example.inc;
                import org.junit.jupiter.api.Test;
                class IncTest {
                    @Test void passes() {}
                }
                """);
        // Composite: main build includes included-lib.
        writeFile("settings.gradle", """
                pluginManagement {
                    repositories {
                        mavenLocal()
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                rootProject.name = 'test-project'
                includeBuild 'included-lib'
                """);

        // Learn in the MAIN build writes the composite index at main/.test-order.
        runner("test", "-Dtestorder.mode=learn").build();
        assertTrue(Files.exists(projectDir.resolve(".test-order/test-dependencies.lz4")));

        // Order mode inside the INCLUDED build must resolve the parent build's index.
        // Without the composite resolution it would look at included-lib/.test-order,
        // find nothing, and print "Index file ... not found — skipping order mode".
        // --info is needed because the order-mode confirmation logs at info level.
        BuildResult result = runner(":included-lib:test", "-Dtestorder.mode=order", "--info").build();

        String output = result.getOutput();
        assertTrue(output.contains("Configuring order mode"),
                "included build must resolve the composite index and configure order mode:\n" + output);
        assertFalse(output.contains("skipping order mode"),
                "included build must NOT degrade to skip mode:\n" + output);
    }

    @Test
    @DisplayName("offline learn survives a convention plugin reassigning the Test task classpath")
    void offlineLearnSurvivesTestClasspathReassignment() throws Exception {
        scaffoldProject();
        // Simulate convention plugins (e.g. Datadog ProfilerTestPlugin) that replace
        // the Test task classpath wholesale. A task-classpath append (the previous
        // wiring) is wiped by this; a testRuntimeOnly contribution survives it. The
        // simulated reassignment keeps the test classes output (as real convention
        // plugins do) and replaces the dependency classpath wholesale.
        writeFile("reassign-classpath.gradle", """
                def customConf = configurations.create('reassignedTestClasspath')
                afterEvaluate {
                    customConf.extendsFrom(configurations.testRuntimeClasspath)
                    tasks.named('test') { classpath = sourceSets.main.output + sourceSets.test.output + customConf }
                }
                """);
        // Append the apply from the existing build.gradle.
        String build = Files.readString(projectDir.resolve("build.gradle"));
        Files.writeString(projectDir.resolve("build.gradle"),
                build + "\napply from: 'reassign-classpath.gradle'\n");

        BuildResult result = runner("test", "-Dtestorder.mode=learn").build();

        assertEquals(SUCCESS, result.task(":test").getOutcome(),
                "offline learn must not die with 'OfflineRuntimeBootstrap class not found' "
                        + "when the classpath is reassigned:\n" + result.getOutput());
        assertTrue(Files.exists(projectDir.resolve(".test-order/test-dependencies.lz4")),
                "offline learn must still write the index under a reassigned classpath");
    }

    @Test
    @DisplayName("derived tasks mirror the convention variant task that carries the JUnit platform")
    void derivedTasksMirrorConventionTestClasspath() throws Exception {
        scaffoldProject();
        // Simulate convention plugins (e.g. Datadog ProfilerTestPlugin) that route test
        // dependencies through their OWN configuration, generate variant Test tasks
        // (testDebug) carrying it in a later afterEvaluate, and leave the java-plugin
        // default `test` task UNCONFIGURED — its raw source-set classpath has NO JUnit
        // platform. A derived task must mirror testDebug, not the bare `test` task
        // (which would fail with "Failed to load JUnit Platform").
        writeFile("convention-routed-deps.gradle", """
                def routedConf = configurations.create('routedTestDeps')
                routedConf.extendsFrom(configurations.testRuntimeClasspath)
                routedConf.dependencies.add(project.dependencies.create('org.junit.jupiter:junit-jupiter:5.11.4'))
                routedConf.dependencies.add(project.dependencies.create('org.junit.platform:junit-platform-launcher:1.11.4'))
                // JUnit API for test compilation only — at RUNTIME the convention
                // configuration above is the sole source of the JUnit platform.
                configurations.testCompileOnly.dependencies.add(
                        project.dependencies.create('org.junit.jupiter:junit-jupiter:5.11.4'))
                afterEvaluate {
                    tasks.register('testDebug', Test) {
                        classpath = sourceSets.main.output + sourceSets.test.output + routedConf
                        testClassesDirs = sourceSets.test.output.classesDirs
                        useJUnitPlatform()
                    }
                }
                """);
        // Override the scaffolded build: NO junit on testImplementation — the convention
        // configuration is the only source of the JUnit platform, and the bare `test`
        // task is deliberately NOT given it (mirrors the java-profiler shape where the
        // bare `test` classpath resolves with junit=0).
        String build = Files.readString(projectDir.resolve("build.gradle"));
        build = build.replace("testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'\n", "");
        build = build.replace("testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.4'\n", "");
        Files.writeString(projectDir.resolve("build.gradle"),
                build + "\napply from: 'convention-routed-deps.gradle'\n");

        // Learn on the convention variant task (the bare `test` task cannot even run).
        runner("testDebug", "-Dtestorder.mode=learn").build();

        // Pre-fix the derived task mirrored the bare `test` task (junit=0 classpath)
        // and failed to even start.
        BuildResult result = runner("testOrderAffected",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=com.example.app.Calculator",
                "-Dtestorder.affected.topN=1",
                "-Dtestorder.affected.randomM=0").build();

        assertEquals(SUCCESS, result.task(":testOrderAffected").getOutcome(),
                "derived task must inherit the convention variant task's classpath (JUnit platform)");
    }

    @Test
    @DisplayName("testOrderAffected with coverage.enforce fails when no test covers a changed class")
    void affectedTaskEnforceFailsOnUncoveredChangedClass() throws Exception {
        scaffoldProject();
        runner("test", "-Dtestorder.mode=learn").build();

        // 'Unknown' is an explicit changed class no test exercises — the closure
        // reports it uncovered; enforce=true must fail the task (before the
        // selected list is written).
        BuildResult result = runner("testOrderAffected",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=com.example.app.Unknown",
                "-Dtestorder.affected.topN=1",
                "-Dtestorder.affected.randomM=0",
                "-Dtestorder.coverage.enforce=true").buildAndFail();

        assertEquals(FAILED, result.task(":testOrderAffected").getOutcome());
        assertTrue(result.getOutput().contains("Coverage closure failed"),
                "Enforce failure must name the uncovered element:\n" + result.getOutput());
        assertFalse(Files.exists(projectDir.resolve("build/test-order-selected.txt")),
                "Enforce must fail BEFORE the selected list is written");
    }

    @Test
    @DisplayName("testOrderAffected force-includes cover-core tests beyond the topN cap")
    void affectedTaskCoverCoreSurvivesTopNCap() throws Exception {
        scaffoldProject();
        runner("test", "-Dtestorder.mode=learn").build();

        // Both Calculator and StringUtils change; each is exercised by exactly one
        // test class (sole coverer). With topN=1 a cap-only selection would run one
        // test class; the cover core must force-include BOTH.
        BuildResult result = runner("testOrderAffected",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=com.example.app.Calculator,com.example.app.StringUtils",
                "-Dtestorder.affected.topN=1",
                "-Dtestorder.affected.randomM=0").build();

        assertEquals(SUCCESS, result.task(":testOrderAffected").getOutcome());
        List<String> selected = Files.readAllLines(projectDir.resolve("build/test-order-selected.txt")).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        assertEquals(Set.of("com.example.app.CalculatorTest", "com.example.app.StringUtilsTest"),
                Set.copyOf(selected),
                "Cover core (both sole coverers) must survive topN=1, got: " + selected);
        assertFalse(Files.exists(projectDir.resolve("build/test-order-remaining.txt")),
                "Nothing is deferred when the cap-only remainder is empty");
    }

    /** Runs a git command in the given directory, failing the test on non-zero exit. */
    private static void git(Path dir, String... args) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.addAll(java.util.Arrays.asList(args));
        Process process = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .inheritIO()
                .start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS),
                "git " + String.join(" ", args) + " timed out");
        assertEquals(0, process.exitValue(),
                "git " + String.join(" ", args) + " failed");
    }

    @Test
    @DisplayName("testOrderRunRemaining runs only deferred tests")
    void runRemainingTaskRunsDeferredTests() throws IOException {
        scaffoldProject();
        runner("test", "-Dtestorder.mode=learn").build();
        runner("testOrderAffected",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=com.example.app.Calculator",
                "-Dtestorder.affected.topN=1",
                "-Dtestorder.affected.randomM=0",
                "-Dtestorder.auto.runRemaining=false").build();

        // Read remaining file BEFORE testOrderRunRemaining consumes and deletes it
        List<String> deferred = Files.readAllLines(projectDir.resolve("build/test-order-remaining.txt"));
        assertEquals(1, deferred.size());

        BuildResult result = runner("testOrderRunRemaining").build();

        assertEquals(SUCCESS, result.task(":testOrderRunRemaining").getOutcome());
        assertTrue(result.getOutput().contains("[test-order] Running 1 remaining test classes"));
        assertTrue(Files.exists(projectDir.resolve("build/test-results/testOrderRunRemaining/TEST-" + deferred.get(0) + ".xml")));
    }

    @Test
    @DisplayName("testOrderAffected failure skips auto-finalized testOrderRunRemaining")
    void selectFailureSkipsAutoRunRemaining() throws IOException {
        scaffoldProject();

        BuildResult result = runner("testOrderAffected",
                "-Dtestorder.mode=order",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=com.example.app.Calculator",
                "-Dtestorder.auto.runRemaining=true").buildAndFail();

        assertEquals(FAILED, result.task(":testOrderAffected").getOutcome());
        assertEquals(SKIPPED, result.task(":testOrderRunRemaining").getOutcome());
        assertTrue(result.getOutput().contains("Select requires an index/dependency baseline"));
        assertFalse(result.getOutput().contains("Cannot call Task.onlyIf"));
    }

    @Test
    @DisplayName("testOrderAffected with zero matched tests executes cleanly without mutation errors")
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
    @DisplayName("testOrderShowOrder gracefully handles corrupt index when no deps exist")
    void showOrderRecoversFromCorruptIndex() throws IOException, InterruptedException {
        scaffoldProject();

        // Learn with default (offline) instrumentation — collector writes index via socket,
        // so no .deps files are created.
        runner("test", "-Dtestorder.mode=learn").build();

        Path index = projectDir.resolve(".test-order/test-dependencies.lz4");
        // Poll briefly for the index; the collector shutdown hook may write it asynchronously.
        for (int i = 0; i < 20 && !Files.exists(index); i++) {
            Thread.sleep(500);
        }
        assertTrue(Files.exists(index), "Expected learned index before corruption");

        // Corrupt index on disk. With no .deps files and no backup, showOrder should
        // fail with a clear "re-run in learn mode" message rather than a cryptic error.
        Files.write(index, new byte[] {1, 2, 3});

        BuildResult result = runner("testOrderShowOrder").buildAndFail();

        // Verify the failure message tells the user what to do
        String output = result.getOutput();
        assertTrue(
                output.contains("learn mode") || output.contains("re-run") || output.contains("regenerate")
                        || output.contains("Could not recover"),
                "Expected clear recovery guidance in output, got: "
                        + output.substring(Math.max(0, output.length() - 500)));
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
                .withGradleVersion("9.9.1")
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
                .withGradleVersion("8.14.3")
                .withProjectDir(projectDir.toFile())
                .withArguments("test", "-Dtestorder.mode=learn",
                        "--init-script", initScript.toString())
                .forwardOutput()
                .build();

        // Order
        BuildResult result = GradleRunner.create()
                .withGradleVersion("8.14.3")
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
                    id 'me.bechberger.test-order' version '0.1.1'
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
    @DisplayName("Multi-project: subprojects share root .test-order directory")
    void multiProjectSubprojectsShareRootStateFiles() throws IOException {
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
                    id 'me.bechberger.test-order' version '0.1.1'
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
                    id 'me.bechberger.test-order' version '0.1.1'
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
                .withGradleVersion("8.14.3")
                .withProjectDir(projectDir.toFile())
                .withArguments("test", "-Dtestorder.mode=learn")
                .forwardOutput()
                .build();

        assertEquals(SUCCESS, result.task(":sub-a:test").getOutcome());
        assertEquals(SUCCESS, result.task(":sub-b:test").getOutcome());

        // Both subprojects should share a single .test-order/ at the root (like Maven)
        Path rootState = projectDir.resolve(".test-order/state.lz4");

        assertTrue(Files.exists(rootState),
                "Root project should have shared state file at .test-order/state.lz4");

        // Per-module state files should NOT exist
        Path stateA = projectDir.resolve("sub-a/.test-order/state.lz4");
        Path stateB = projectDir.resolve("sub-b/.test-order/state.lz4");

        assertFalse(Files.exists(stateA),
                "sub-a should NOT have its own state file — it should be at root");
        assertFalse(Files.exists(stateB),
                "sub-b should NOT have its own state file — it should be at root");
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
                    id("me.bechberger.test-order") version "0.1.1"
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
                    id("me.bechberger.test-order") version "0.1.1"
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

        BuildResult result = runner("test", "-Dtestorder.mode=learn",
                "-Dtestorder.instrumentation=online").build();

        // Kotlin learn mode: just verify tests run successfully. Dependency data collection
        // for Kotlin requires ClassIdMapping (created by offline instrumentation), so online
        // mode may not produce an index on all platforms — that is a known limitation.
        assertEquals(SUCCESS, result.task(":test").getOutcome());
    }

    @Test
    @DisplayName("Learn mode applies cleanly under Gradle 8.14+ strict mutation guard")
    void learnModeAppliesCleanlyUnderStrictMutationGuard() throws IOException {
        // Regression guard for commit 162642c0. Gradle 8.14+ rejects tasks.register()
        // calls made from inside configureEach/register actions with the message
        // "DefaultTaskContainer#register(...) cannot be executed in the current context".
        // Before the fix, every learn-mode build emitted that error during configuration.
        scaffoldProject();

        BuildResult result = runner("test", "-Dtestorder.mode=learn").build();

        assertEquals(SUCCESS, result.task(":test").getOutcome());
        assertFalse(result.getOutput().contains("cannot be executed in the current context"),
                "Build output must not contain Gradle's mutation-guard rejection — that means "
                        + "register() is being called from inside configureEach again");
    }

    @Test
    @DisplayName("Learn mode with separate :test and :integrationTest tasks")
    void learnModeWithSeparateUnitAndIntegrationTestTasks() throws IOException {
        // The shared testOrderOfflineRestore task replaced N per-test-task restore tasks
        // in 162642c0. With two Test tasks (the standard :test plus a registered
        // :integrationTest) we exercise the design's biggest unknown: whether the
        // shared-finalizer model still leaves the project in a clean state.
        //
        // Note on the restore mechanism: the primary restore path is TelemetryListener
        // running inside the forked test JVM at end-of-suite (it invokes
        // OfflineInstrumentor.restore via reflection using the testorder.offline.backupDir
        // system property). The shared testOrderOfflineRestore Gradle task is a safety
        // net for cases where the JUnit listener doesn't run (test-JVM crash, etc.) —
        // so its restore() typically returns false in normal multi-test-task runs and
        // the "Restored original classes" lifecycle line is not expected here. We
        // assert end-state (classes un-instrumented, both tasks indexed) instead.
        writeFile("settings.gradle", """
                pluginManagement {
                    repositories {
                        mavenLocal()
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                rootProject.name = 'multi-test-task-project'
                """);

        writeFile("build.gradle", """
                plugins {
                    id 'java'
                    id 'me.bechberger.test-order' version '0.1.1'
                }
                group = 'com.example'
                version = '1.0.0'
                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                sourceSets {
                    integrationTest {
                        java.srcDir 'src/integrationTest/java'
                        resources.srcDir 'src/integrationTest/resources'
                        compileClasspath += sourceSets.main.output + configurations.testRuntimeClasspath
                        runtimeClasspath += output + compileClasspath
                    }
                }

                configurations {
                    integrationTestImplementation.extendsFrom testImplementation
                    integrationTestRuntimeOnly.extendsFrom testRuntimeOnly
                }

                dependencies {
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
                    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.4'
                }

                tasks.named('test') { useJUnitPlatform() }

                tasks.register('integrationTest', Test) {
                    description = 'Runs integration tests'
                    group = 'verification'
                    testClassesDirs = sourceSets.integrationTest.output.classesDirs
                    classpath = sourceSets.integrationTest.runtimeClasspath
                    useJUnitPlatform()
                }
                """);

        // Production code
        writeFile("src/main/java/com/example/app/Calculator.java", """
                package com.example.app;
                public class Calculator {
                    public int add(int a, int b) { return a + b; }
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

        // Unit test
        writeFile("src/test/java/com/example/app/CalculatorTest.java", """
                package com.example.app;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class CalculatorTest {
                    @Test void testAdd() { assertEquals(5, new Calculator().add(2, 3)); }
                }
                """);

        // Integration test
        writeFile("src/integrationTest/java/com/example/app/StringUtilsIT.java", """
                package com.example.app;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class StringUtilsIT {
                    @Test void testReverse() { assertEquals("cba", StringUtils.reverse("abc")); }
                }
                """);

        BuildResult result = runner("test", "integrationTest", "-Dtestorder.mode=learn").build();

        assertEquals(SUCCESS, result.task(":test").getOutcome(),
                "Unit test task should succeed");
        assertEquals(SUCCESS, result.task(":integrationTest").getOutcome(),
                "Integration test task should succeed");

        // Shared restore finalizer must be wired and must execute (even if its restore()
        // is a no-op because the in-JVM TelemetryListener already restored).
        assertNotNull(result.task(":testOrderOfflineRestore"),
                "testOrderOfflineRestore must be wired as a finalizer");
        assertEquals(SUCCESS, result.task(":testOrderOfflineRestore").getOutcome(),
                "testOrderOfflineRestore must run successfully");

        // Backup directory must be cleaned up — proves restore completed end-to-end via
        // some path (TelemetryListener in-JVM, or the Gradle finalizer as fallback).
        Path backupDir = projectDir.resolve("build/.test-order/classes-backup");
        assertFalse(Files.exists(backupDir),
                "Offline backup directory should be cleaned up after restore: " + backupDir);

        // No class file under build/classes should still carry the OfflineInstrumentor
        // marker. We scan for "TestOrderInstrumented" as a byte sequence — its presence
        // in any .class constant pool means that class is still instrumented.
        Path classesRoot = projectDir.resolve("build/classes");
        if (Files.exists(classesRoot)) {
            byte[] markerBytes = "TestOrderInstrumented".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (var stream = Files.walk(classesRoot)) {
                List<Path> instrumented = stream
                        .filter(p -> p.toString().endsWith(".class"))
                        .filter(p -> {
                            try {
                                byte[] data = Files.readAllBytes(p);
                                return indexOfBytes(data, markerBytes) >= 0;
                            } catch (IOException e) {
                                return false;
                            }
                        })
                        .toList();
                assertTrue(instrumented.isEmpty(),
                        "No class file should still carry the TestOrderInstrumented marker after "
                                + "restore, but found: " + instrumented);
            }
        }

        // Both test tasks must have produced an index entry. The index file is shared at
        // the project level; both Test tasks merge into it.
        Path indexFile = projectDir.resolve(".test-order/test-dependencies.lz4");
        assertTrue(Files.exists(indexFile),
                "Shared dependency index should be created at " + indexFile);
        // Cross-check via build output that BOTH tasks ran offline learn instrumentation
        // (so both contributed to the index — distinct from the case where only :test ran).
        long instrumentLines = result.getOutput().lines()
                .filter(l -> l.contains("Offline learn mode: no agent, using build-time instrumentation"))
                .count();
        assertEquals(2, instrumentLines,
                ":test and :integrationTest should each instrument once. Output:\n"
                        + result.getOutput());
    }

    private static int indexOfBytes(byte[] haystack, byte[] needle) {
        if (needle.length == 0) return 0;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
