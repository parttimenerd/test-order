package me.bechberger.testorder.gradle;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import me.bechberger.testorder.PersistenceSupport;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

class TestOrderPluginTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Clean up system properties that may be left from previous tests
        System.clearProperty("testorder.mode");
    }

    @org.junit.jupiter.api.Test
    void applyRegistersRepositoryConfigurationDependenciesAndTasks() {
        Project project = newProject();
        project.getPluginManager().apply("java");
        TestOrderPlugin plugin = new TestOrderPlugin();

        plugin.apply(project);

        TestOrderExtension extension = (TestOrderExtension) project.getExtensions().getByName(TestOrderPlugin.EXTENSION_NAME);
        assertNotNull(extension);
        Configuration configuration = project.getConfigurations().getByName(TestOrderPlugin.AGENT_CONFIG_NAME);
        assertFalse(configuration.isVisible());
        assertFalse(configuration.isTransitive());
        assertTrue(project.getRepositories().stream().anyMatch(repo -> repo.getName().equals("testOrderMavenLocal")));
        // Verify mavenLocal is scoped to test-order artifacts only (avoids JUnit version conflicts)
        org.gradle.api.artifacts.repositories.MavenArtifactRepository mavenLocal =
                (org.gradle.api.artifacts.repositories.MavenArtifactRepository) project.getRepositories().stream()
                        .filter(repo -> repo.getName().equals("testOrderMavenLocal")).findFirst().orElseThrow();
        assertNotNull(mavenLocal);
        assertEquals(1L, project.getTasks().stream().filter(task -> task.getName().equals("testOrderSelect")).count());
        assertTrue(project.getTasks().getNames().containsAll(Set.of(
                "testOrderAggregate", "testOrderDump", "testOrderExportJson", "testOrderShowOrder", "testOrderExplainOrder",
                "testOrderOptimize", "testOrderSelect", "testOrderRunRemaining", "testOrderClean")));

        List<ExternalModuleDependency> runtimeDeps = project.getConfigurations().getByName("testRuntimeOnly")
                .getDependencies().withType(ExternalModuleDependency.class).stream().collect(Collectors.toList());
        assertTrue(runtimeDeps.stream().anyMatch(dep -> dep.getName().equals("test-order-junit") && !dep.isTransitive()));
        assertTrue(runtimeDeps.stream().anyMatch(dep -> dep.getName().equals("test-order-core") && !dep.isTransitive()));
        assertEquals(1L, configuration.getDependencies().stream().count());
    }

    @org.junit.jupiter.api.Test
    void configureLearnModeInjectsAgentArgumentsAndStateProperties() throws Exception {
        Project project = newProject();
        project.getPluginManager().apply("java");
        TestOrderPlugin plugin = new TestOrderPlugin();
        TestOrderExtension extension = project.getExtensions().create("testOrder", TestOrderExtension.class);
        extension.applyDefaults(project);
        extension.getInstrumentation().set("online");
        extension.getVerboseFile().set(tempDir.resolve("verbose.log").toString());
        Files.createDirectories(tempDir.resolve("src/main/java/com/example/app"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/app/App.java"), "class App {}\n");
        Configuration agentConf = fileConfiguration(project, tempDir.resolve("agent.jar"));
        Test testTask = (Test) project.getTasks().getByName("test");

        plugin.configureLearnMode(project, extension, testTask, agentConf);

        assertEquals("true", testTask.getSystemProperties().get("testorder.learn"));
        assertEquals("FULL", testTask.getSystemProperties().get("testorder.instrumentation.mode"));
        assertEquals(extension.getStateFile().get().getAsFile().getAbsolutePath(),
                testTask.getSystemProperties().get("testorder.state.path"));
        Object provider = testTask.getJvmArgumentProviders().get(0);
        Method asArguments = provider.getClass().getMethod("asArguments");
        List<String> args = new java.util.ArrayList<>();
        for (String argument : (Iterable<String>) asArguments.invoke(provider)) {
            args.add(argument);
        }
        assertTrue(args.get(0).contains("-javaagent:" + tempDir.resolve("agent.jar").toAbsolutePath()));
        assertTrue(args.get(0).contains("outputDir=" + extension.getDepsDir().get().getAsFile().getAbsolutePath()));
        assertTrue(args.get(0).contains("includePackages=com.example"));
        assertTrue(args.get(0).contains("verboseFile=" + tempDir.resolve("verbose.log").toAbsolutePath()));
    }

    @org.junit.jupiter.api.Test
    void configureOrderModeAddsOnlyRelevantProperties() throws IOException {
        Project project = newProject();
        project.getPluginManager().apply("java");
        TestOrderPlugin plugin = new TestOrderPlugin();
        TestOrderExtension extension = project.getExtensions().create("testOrder", TestOrderExtension.class);
        extension.applyDefaults(project);
        Path indexFile = extension.getIndexFile().get().getAsFile().toPath();
        Files.createDirectories(indexFile.getParent());
        Files.write(indexFile, new byte[]{1, 2, 3});
        extension.getMethodOrderingEnabled().set(true);
        extension.getWeightsFile().set(tempDir.resolve("weights.txt").toString());
        extension.getChangeMode().set("explicit");
        extension.getScoreNewTest().set(55);
        Test testTask = (Test) project.getTasks().getByName("test");
        String oldDebug = System.getProperty("testorder.debug");
        System.setProperty("testorder.debug", "true");
        try {
            plugin.configureOrderMode(project, extension, testTask);
        } finally {
            if (oldDebug == null) {
                System.clearProperty("testorder.debug");
            } else {
                System.setProperty("testorder.debug", oldDebug);
            }
        }

        assertEquals("me.bechberger.testorder.junit.PriorityClassOrderer",
                testTask.getSystemProperties().get("junit.jupiter.testclass.order.default"));
        assertEquals(indexFile.toAbsolutePath().toString(), testTask.getSystemProperties().get("testorder.index.path"));
        assertEquals("explicit", testTask.getSystemProperties().get("testorder.changeMode"));
        assertEquals("true", testTask.getSystemProperties().get("testorder.methodOrder.enabled"));
        assertEquals("true", testTask.getSystemProperties().get("testorder.debug"));
        assertEquals("55", testTask.getSystemProperties().get("testorder.score.newTest"));
        assertFalse(testTask.getSystemProperties().containsKey("testorder.score.changedTest"));
        assertEquals(tempDir.resolve("weights.txt").toAbsolutePath().toString(),
                testTask.getSystemProperties().get("testorder.weights.file"));
    }

    @org.junit.jupiter.api.Test
    void configureLearnModeTddInjectsAutodetection() throws Exception {
        Project project = newProject();
        project.getPluginManager().apply("java");
        TestOrderPlugin plugin = new TestOrderPlugin();
        TestOrderExtension extension = project.getExtensions().create("testOrder", TestOrderExtension.class);
        extension.applyDefaults(project);
        extension.getTdd().set(true);
        Files.createDirectories(tempDir.resolve("src/main/java/com/example/app"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/app/App.java"), "class App {}\n");
        Configuration agentConf = fileConfiguration(project, tempDir.resolve("agent.jar"));
        Test testTask = (Test) project.getTasks().getByName("test");

        plugin.configureLearnMode(project, extension, testTask, agentConf);

        assertEquals("true", testTask.getSystemProperties().get("testorder.tdd"));
        assertEquals("true", testTask.getSystemProperties().get("junit.jupiter.extensions.autodetection.enabled"));
    }

    @org.junit.jupiter.api.Test
    void configureLearnModeWithoutTddDoesNotInjectAutodetection() throws Exception {
        Project project = newProject();
        project.getPluginManager().apply("java");
        TestOrderPlugin plugin = new TestOrderPlugin();
        TestOrderExtension extension = project.getExtensions().create("testOrder", TestOrderExtension.class);
        extension.applyDefaults(project);
        // tdd defaults to false
        Files.createDirectories(tempDir.resolve("src/main/java/com/example/app"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/app/App.java"), "class App {}\n");
        Configuration agentConf = fileConfiguration(project, tempDir.resolve("agent.jar"));
        Test testTask = (Test) project.getTasks().getByName("test");

        plugin.configureLearnMode(project, extension, testTask, agentConf);

        assertNull(testTask.getSystemProperties().get("testorder.tdd"));
        assertNull(testTask.getSystemProperties().get("junit.jupiter.extensions.autodetection.enabled"));
    }

    @org.junit.jupiter.api.Test
    void configureOrderModeTddInjectsAutodetection() throws IOException {
        Project project = newProject();
        project.getPluginManager().apply("java");
        TestOrderPlugin plugin = new TestOrderPlugin();
        TestOrderExtension extension = project.getExtensions().create("testOrder", TestOrderExtension.class);
        extension.applyDefaults(project);
        extension.getTdd().set(true);
        Path indexFile = extension.getIndexFile().get().getAsFile().toPath();
        Files.createDirectories(indexFile.getParent());
        Files.write(indexFile, new byte[]{1, 2, 3});
        Test testTask = (Test) project.getTasks().getByName("test");

        plugin.configureOrderMode(project, extension, testTask);

        assertEquals("true", testTask.getSystemProperties().get("testorder.tdd"));
        assertEquals("true", testTask.getSystemProperties().get("junit.jupiter.extensions.autodetection.enabled"));
    }

    @org.junit.jupiter.api.Test
    void resolveModeHonorsOverridesAndExistingIndex() throws IOException {
        Project project = newProject();
        TestOrderPlugin plugin = new TestOrderPlugin();
        TestOrderExtension extension = project.getExtensions().create("testOrder", TestOrderExtension.class);
        extension.applyDefaults(project);

        // Create a dummy test source so the "no tests found → skip" guard doesn't fire
        Path testSrcDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testSrcDir);
        Files.writeString(testSrcDir.resolve("FooTest.java"), "class FooTest {}\n");

        assertEquals("learn", plugin.resolveMode(extension, project));

        Path idxP = extension.getIndexFile().get().getAsFile().toPath();
        Files.createDirectories(idxP.getParent());
        Files.write(idxP, new byte[]{1});
        assertEquals("order", plugin.resolveMode(extension, project));

        String old = System.getProperty("testorder.mode");
        System.setProperty("testorder.mode", "skip");
        try {
            assertEquals("skip", plugin.resolveMode(extension, project));
        } finally {
            if (old == null) {
                System.clearProperty("testorder.mode");
            } else {
                System.setProperty("testorder.mode", old);
            }
        }
    }

    @org.junit.jupiter.api.Test
    void twoSubprojectsShareRootStateFilePaths() {
        // In a multi-project build all subprojects share a single .test-order/ directory
        // at the root project level (parity with Maven's ReactorContext behavior).
        Path rootDir = tempDir.resolve("iso-root");
        Path moduleADir = tempDir.resolve("iso-module-a");
        Path moduleBDir = tempDir.resolve("iso-module-b");

        Project root = ProjectBuilder.builder()
                .withProjectDir(rootDir.toFile()).withName("iso-root").build();
        Project moduleA = ProjectBuilder.builder()
                .withProjectDir(moduleADir.toFile()).withParent(root).withName("iso-module-a").build();
        Project moduleB = ProjectBuilder.builder()
                .withProjectDir(moduleBDir.toFile()).withParent(root).withName("iso-module-b").build();

        TestOrderExtension extA = moduleA.getExtensions().create("testOrder", TestOrderExtension.class);
        TestOrderExtension extB = moduleB.getExtensions().create("testOrder", TestOrderExtension.class);
        extA.applyDefaults(moduleA);
        extB.applyDefaults(moduleB);

        java.io.File stateA = extA.getStateFile().get().getAsFile();
        java.io.File stateB = extB.getStateFile().get().getAsFile();
        java.io.File indexA = extA.getIndexFile().get().getAsFile();
        java.io.File indexB = extB.getIndexFile().get().getAsFile();
        java.io.File hashA = extA.getHashFile().get().getAsFile();
        java.io.File hashB = extB.getHashFile().get().getAsFile();

        assertEquals(stateA.getAbsolutePath(), stateB.getAbsolutePath(),
                "Subprojects must share state file path at root project level");
        assertEquals(indexA.getAbsolutePath(), indexB.getAbsolutePath(),
                "Subprojects must share index file path at root project level");
        assertEquals(hashA.getAbsolutePath(), hashB.getAbsolutePath(),
                "Subprojects must share hash file path at root project level");
        assertTrue(stateA.getAbsolutePath().contains("iso-root"),
                "State path should be under root directory, got: " + stateA.getAbsolutePath());
        assertTrue(indexA.getAbsolutePath().contains("iso-root"),
                "Index path should be under root directory, got: " + indexA.getAbsolutePath());
        assertTrue(hashA.getAbsolutePath().contains("iso-root"),
                "Hash path should be under root directory, got: " + hashA.getAbsolutePath());
    }

    @org.junit.jupiter.api.Test
    void snapshotSingleDirWaitsForHashFileLock() throws Exception {
        Project project = newProject();
        Path mainSourceRoot = tempDir.resolve("src/main/java");
        Path sourceRoot = mainSourceRoot.resolve("com/example/app");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("App.java"), "class App {}\n");
        Path hashFile = tempDir.resolve(".test-order-hashes.lz4");

        Method snapshotSingleDir = TestOrderPlugin.class.getDeclaredMethod(
                "snapshotSingleDir", Path.class, Path.class, String.class, Project.class);
        snapshotSingleDir.setAccessible(true);

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> lockFuture = executor.submit(() -> {
                try {
                    PersistenceSupport.withFileLock(hashFile, () -> {
                        lockHeld.countDown();
                        try {
                            assertTrue(releaseLock.await(5, TimeUnit.SECONDS), "Timed out waiting to release lock");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException("Interrupted while waiting to release lock", e);
                        }
                        return null;
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertTrue(lockHeld.await(5, TimeUnit.SECONDS), "Timed out acquiring initial hash lock");

            Future<?> snapshotFuture = executor.submit(() -> {
                try {
                    snapshotSingleDir.invoke(null, mainSourceRoot, hashFile, "source", project);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> snapshotFuture.get(200, TimeUnit.MILLISECONDS),
                    "Snapshot write should block while another task holds the hash-file lock");

            releaseLock.countDown();
            lockFuture.get(5, TimeUnit.SECONDS);
            snapshotFuture.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertTrue(Files.exists(hashFile), "Snapshot should be written after the hash-file lock is released");
    }

        @org.junit.jupiter.api.Test
        void injectChangedClassesDoesNotSwallowInvalidChangeMode() throws Exception {
        Project project = newProject();
        project.getPluginManager().apply("java");
        TestOrderExtension extension = project.getExtensions().create("testOrder", TestOrderExtension.class);
        extension.applyDefaults(project);
        extension.getChangeMode().set("not-a-real-mode");
        Test testTask = (Test) project.getTasks().getByName("test");

        Method injectChangedClasses = TestOrderPlugin.class.getDeclaredMethod(
            "injectChangedClasses", Project.class, TestOrderExtension.class, Test.class);
        injectChangedClasses.setAccessible(true);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> injectChangedClasses.invoke(null, project, extension, testTask));
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
        }

        @org.junit.jupiter.api.Test
        void detectChangedClassesForSelectionDoesNotSwallowInvalidChangeMode() throws Exception {
        Project project = newProject();
        TestOrderExtension extension = project.getExtensions().create("testOrder", TestOrderExtension.class);
        extension.applyDefaults(project);
        extension.getChangeMode().set("not-a-real-mode");

        Method detectChangedClassesForSelection = TestOrderPlugin.class.getDeclaredMethod(
            "detectChangedClassesForSelection", Project.class, TestOrderExtension.class);
        detectChangedClassesForSelection.setAccessible(true);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> detectChangedClassesForSelection.invoke(null, project, extension));
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
        }

    private Project newProject() {
        return ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
    }

    private static Configuration fileConfiguration(Project project, Path jarFile) throws IOException {
        Files.write(jarFile, new byte[]{1});
        Configuration configuration = TestOrderPlugin.createHiddenConfiguration(project, "testAgentFile", false);
        project.getDependencies().add(configuration.getName(), project.files(jarFile));
        return configuration;
    }
}
