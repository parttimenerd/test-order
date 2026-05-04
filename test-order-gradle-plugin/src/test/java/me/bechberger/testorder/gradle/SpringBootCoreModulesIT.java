package me.bechberger.testorder.gradle;

import me.bechberger.testorder.DependencyMap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Heavy end-to-end checks against the embedded Spring Boot Gradle build.
 * Enable explicitly with -Dtestorder.it=true.
 */
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpringBootCoreModulesIT {

    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(15);
    private static final String CORE_TEST = "org.springframework.boot.SpringApplicationTests";
    private static final List<String> CORE_TEST_MODULE_TESTS = List.of(
            "org.springframework.boot.test.system.OutputCaptureTests",
            "org.springframework.boot.test.context.SpringBootContextLoaderTests");

    private static final String CORE_MODULE = ":core:spring-boot";
    private static final String CORE_TEST_MODULE = ":core:spring-boot-test";
    private static final String CORE_CHANGED_CLASS = "org.springframework.boot.SpringApplication";
    private static final String CORE_CHANGED_FILE_CLASS = "org.springframework.boot.Banner";
    private static final String CORE_TEST_MODULE_CHANGED_CLASS =
            "org.springframework.boot.test.context.SpringBootContextLoader";
    private static final String OUTPUT_CAPTURE_DEPENDENCY =
            "org.springframework.boot.test.system.OutputCapture";
    private static final Pattern SHOW_ORDER_ROW_PATTERN = Pattern.compile("^(\\d+)\\.\\s+(\\S+)\\s+(.*)$");

    @TempDir
    static Path tempDir;

    private static Path repoRoot;
    private static Path springBootDir;
    private static Path coreModuleDir;
    private static Path coreTestModuleDir;
    private static Path scopedInitScript;
    private static String springJavaHome;
    private static String publishJavaHome;
    private static boolean artifactsPublished;

    @BeforeAll
    static void setup() throws IOException {
        repoRoot = locateRepoRoot();
        springBootDir = repoRoot.resolve("spring-boot");
        Assumptions.assumeTrue(Files.isDirectory(springBootDir), "spring-boot checkout is required");

        coreModuleDir = springBootDir.resolve("core/spring-boot");
        coreTestModuleDir = springBootDir.resolve("core/spring-boot-test");
        springJavaHome = findSpringJavaHome().orElse(null);
        publishJavaHome = findPublishJavaHome().orElse(null);
        Assumptions.assumeTrue(springJavaHome != null, "A Java 25+ home is required for Spring Boot runs");
        Assumptions.assumeTrue(publishJavaHome != null, "A Java 17-24 home is required to publish test-order artifacts");

        scopedInitScript = writeScopedInitScript(List.of(CORE_MODULE, CORE_TEST_MODULE));
        publishArtifactsIfNeeded();
    }

    @Test
    @Order(1)
    @DisplayName("Spring Boot core learn mode writes index, deps, and state")
    void coreLearnModeProducesArtifacts() throws IOException {
        cleanGeneratedArtifacts(coreModuleDir);

        CommandResult result = runSpringBoot(springBootDir,
                "--rerun-tasks",
                CORE_MODULE + ":test",
                "-Dtestorder.mode=learn",
                "--tests", CORE_TEST);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("[test-order] Configuring learn mode"));
        assertArtifactsExist(coreModuleDir);
        assertTrue(countDepsFiles(coreModuleDir) >= 1, "Expected .deps files for the filtered learn run");
        assertTrackedDependency(coreModuleDir, CORE_TEST, CORE_CHANGED_CLASS);
    }

    @Test
    @Order(2)
    @DisplayName("Spring Boot core show-order prints scores for an explicit changed class")
    void coreShowOrderDisplaysScores() throws IOException {
        ensureCoreLearnBaseline();

        CommandResult result = runSpringBoot(springBootDir,
                CORE_MODULE + ":testOrderShowOrder",
                "-Dtestorder.changed.classes=" + CORE_CHANGED_CLASS);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Predicted test execution order"));
        assertTrue(result.output().contains("Changed classes: " + CORE_CHANGED_CLASS));
        ShowOrderRow row = showOrderRow(result.output(), "SpringApplicationTests");
        assertTrue(row.score() > 0, "Expected a positive score for SpringApplicationTests:\n" + result.output());
        assertTrue(row.depOverlap() > 0, "Expected dependency overlap for SpringApplicationTests:\n" + result.output());
    }

    @Test
    @Order(3)
    @DisplayName("Spring Boot core order mode succeeds with an explicit change")
    void coreOrderModeWorks() throws IOException {
        ensureCoreLearnBaseline();

        CommandResult result = runSpringBoot(springBootDir,
                "--rerun-tasks",
                CORE_MODULE + ":test",
                "-Dtestorder.mode=order",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=" + CORE_CHANGED_CLASS,
                "--tests", CORE_TEST);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("[test-order] Configuring order mode"));
        assertTrue(result.output().contains("[test-order] Using explicitly provided changed classes"));
        assertTrue(Files.exists(coreModuleDir.resolve(".test-order/state.lz4")));
        assertTrue(Files.exists(coreModuleDir.resolve(".test-order/hashes.lz4")));
    }

    @Test
    @Order(4)
    @DisplayName("Spring Boot core auto mode switches to order after learn")
    void coreAutoModeUsesLearnedIndex() throws IOException {
        ensureCoreLearnBaseline();

        CommandResult result = runSpringBoot(springBootDir,
                "--rerun-tasks",
                CORE_MODULE + ":test",
                "--tests", CORE_TEST);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("[test-order] Configuring order mode"));
    }

    @Test
    @Order(5)
    @DisplayName("Spring Boot core skip mode leaves no plugin artifacts behind")
    void coreSkipModeDisablesPlugin() throws IOException {
        cleanGeneratedArtifacts(coreModuleDir);

        CommandResult result = runSpringBoot(springBootDir,
                "--rerun-tasks",
                CORE_MODULE + ":test",
                "-Dtestorder.mode=skip",
                "--tests", CORE_TEST);

        assertEquals(0, result.exitCode(), result.output());
        assertFalse(result.output().contains("[test-order] Configuring learn mode"));
        assertFalse(result.output().contains("[test-order] Configuring order mode"));
        assertFalse(Files.exists(coreModuleDir.resolve(".test-order/test-dependencies.lz4")),
                "Skip mode should not create an aggregated dependency index");
        assertFalse(Files.isDirectory(coreModuleDir.resolve("build/test-order-deps")),
                "Skip mode should not create a deps directory");
    }

    @Test
    @Order(6)
    @DisplayName("Spring Boot test module learn mode writes index, deps, and state")
    void coreTestModuleLearnModeProducesArtifacts() throws IOException {
        cleanGeneratedArtifacts(coreTestModuleDir);

        CommandResult result = runSpringBoot(springBootDir,
                "--rerun-tasks",
                CORE_TEST_MODULE + ":test",
                "-Dtestorder.mode=learn",
                "--tests", CORE_TEST_MODULE_TESTS.get(0),
                "--tests", CORE_TEST_MODULE_TESTS.get(1));

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("[test-order] Configuring learn mode"));
        assertArtifactsExist(coreTestModuleDir);
        assertTrue(countDepsFiles(coreTestModuleDir) >= 2, "Expected .deps files for the filtered learn run");
        assertTrackedDependency(coreTestModuleDir, CORE_TEST_MODULE_TESTS.get(0), OUTPUT_CAPTURE_DEPENDENCY);
        assertTrackedDependency(coreTestModuleDir, CORE_TEST_MODULE_TESTS.get(1), CORE_TEST_MODULE_CHANGED_CLASS);
    }

    @Test
    @Order(7)
    @DisplayName("Spring Boot test module supports all instrumentation modes")
    void coreTestModuleSupportsAllInstrumentationModes() throws IOException {
        for (String mode : List.of("METHOD_ENTRY", "FULL", "FULL_METHOD", "FULL_MEMBER")) {
            cleanGeneratedArtifacts(coreTestModuleDir);
            CommandResult result = runSpringBoot(springBootDir,
                    "--rerun-tasks",
                    CORE_TEST_MODULE + ":test",
                    "-Dtestorder.mode=learn",
                    "-Dtestorder.instrumentation.mode=" + mode,
                    "--tests", CORE_TEST_MODULE_TESTS.get(0),
                    "--tests", CORE_TEST_MODULE_TESTS.get(1));

            assertEquals(0, result.exitCode(), "Instrumentation mode " + mode + " failed:\n" + result.output());
            assertArtifactsExist(coreTestModuleDir);
            assertTrue(countDepsFiles(coreTestModuleDir) >= 2,
                    "Expected .deps files for instrumentation mode " + mode);
            assertTrackedDependency(coreTestModuleDir, CORE_TEST_MODULE_TESTS.get(0), OUTPUT_CAPTURE_DEPENDENCY);
            assertTrackedDependency(coreTestModuleDir, CORE_TEST_MODULE_TESTS.get(1), CORE_TEST_MODULE_CHANGED_CLASS);

            DependencyMap aggregated = aggregateDependencyMapFromDeps(coreTestModuleDir);
            if ("FULL_METHOD".equals(mode) || "FULL_MEMBER".equals(mode)) {
                assertTrue(aggregated.hasMethodDeps(),
                        "Expected per-method dependency data for instrumentation mode " + mode);
            }
        }
    }

    @Test
    @Order(8)
    @DisplayName("Spring Boot core auto, since-last-run, and uncommitted detect a real source edit")
    void coreChangeModesDetectEditedClass() throws IOException {
        ensureCoreLearnBaseline();

        Path sourceFile = coreModuleDir.resolve("src/main/java/org/springframework/boot/Banner.java");
        String original = Files.readString(sourceFile);
        try {
            Files.writeString(sourceFile, original + "\n// test-order integration marker\n");
            for (String mode : List.of("auto", "since-last-run", "uncommitted")) {
                CommandResult result = runSpringBoot(springBootDir,
                        CORE_MODULE + ":testOrderShowOrder",
                        "-Dtestorder.changeMode=" + mode);

                assertEquals(0, result.exitCode(), "Change mode " + mode + " failed:\n" + result.output());
                assertTrue(result.output().contains("Changed classes: " + CORE_CHANGED_FILE_CLASS),
                        "Expected " + mode + " to detect " + CORE_CHANGED_FILE_CLASS + ":\n" + result.output());
            }
        } finally {
            Files.writeString(sourceFile, original);
        }
    }

    @Test
    @Order(9)
    @DisplayName("Spring Boot core since-last-commit detects a changed class in a temporary worktree")
    void coreSinceLastCommitDetectsHeadChange() throws IOException {
        Path worktreeDir = tempDir.resolve("spring-boot-worktree");
        CommandResult addWorktree = runCommand(repoRoot,
                Map.of(),
                "git", "-C", springBootDir.toString(), "worktree", "add", "--detach", worktreeDir.toString(), "HEAD");
        assertEquals(0, addWorktree.exitCode(), addWorktree.output());

        try {
            Path worktreeCoreModule = worktreeDir.resolve("core/spring-boot");
            cleanGeneratedArtifacts(worktreeCoreModule);
            Path worktreeInitScript = writeScopedInitScriptFor(worktreeDir, List.of(CORE_MODULE));

            CommandResult learn = runSpringBoot(worktreeDir,
                    worktreeDir,
                    worktreeInitScript,
                    "--rerun-tasks",
                    CORE_MODULE + ":test",
                    "-Dtestorder.mode=learn",
                    "--tests", CORE_TEST);
            assertEquals(0, learn.exitCode(), learn.output());

            Path sourceFile = worktreeCoreModule.resolve("src/main/java/org/springframework/boot/Banner.java");
            String original = Files.readString(sourceFile);
            Files.writeString(sourceFile, original + "\n// since-last-commit marker\n");

            assertEquals(0, runCommand(worktreeDir, Map.of(),
                    "git", "config", "user.name", "Copilot Test").exitCode());
            assertEquals(0, runCommand(worktreeDir, Map.of(),
                    "git", "config", "user.email", "copilot@example.com").exitCode());
            assertEquals(0, runCommand(worktreeDir, Map.of(),
                    "git", "add", "core/spring-boot/src/main/java/org/springframework/boot/Banner.java").exitCode());
            CommandResult commit = runCommand(worktreeDir, Map.of(),
                    "git", "commit", "-m", "Temp change for test-order integration");
            assertEquals(0, commit.exitCode(), commit.output());

            CommandResult result = runSpringBoot(worktreeDir,
                    worktreeDir,
                    worktreeInitScript,
                    CORE_MODULE + ":testOrderShowOrder",
                    "-Dtestorder.changeMode=since-last-commit");

            assertEquals(0, result.exitCode(), result.output());
            assertTrue(result.output().contains("Changed classes: " + CORE_CHANGED_FILE_CLASS),
                    "Expected since-last-commit to detect " + CORE_CHANGED_FILE_CLASS + ":\n" + result.output());
        } finally {
            runCommand(repoRoot, Map.of(),
                    "git", "-C", springBootDir.toString(), "worktree", "remove", "--force", worktreeDir.toString());
        }
    }

    private static void ensureCoreLearnBaseline() throws IOException {
        if (Files.exists(coreModuleDir.resolve(".test-order/test-dependencies.lz4"))) {
            return;
        }
        cleanGeneratedArtifacts(coreModuleDir);
        CommandResult result = runSpringBoot(springBootDir,
                "--rerun-tasks",
                CORE_MODULE + ":test",
                "-Dtestorder.mode=learn",
                "--tests", CORE_TEST);
        assertEquals(0, result.exitCode(), result.output());
        assertArtifactsExist(coreModuleDir);
    }

    private static void assertArtifactsExist(Path moduleDir) {
        assertTrue(Files.exists(moduleDir.resolve(".test-order/test-dependencies.lz4")),
                "Expected aggregated dependency index in " + moduleDir);
        assertTrue(Files.exists(moduleDir.resolve(".test-order/state.lz4")),
                "Expected state file in " + moduleDir);
        assertTrue(Files.exists(moduleDir.resolve(".test-order/hashes.lz4")),
                "Expected source hash snapshot in " + moduleDir);
        assertTrue(Files.exists(moduleDir.resolve(".test-order/test-hashes.lz4")),
                "Expected test hash snapshot in " + moduleDir);
        assertTrue(Files.isDirectory(moduleDir.resolve("build/test-order-deps")),
                "Expected .deps directory in " + moduleDir);
    }

    private static long countDepsFiles(Path moduleDir) throws IOException {
        try (var files = Files.walk(moduleDir.resolve("build/test-order-deps"))) {
            return files.filter(path -> path.getFileName().toString().endsWith(".deps")).count();
        }
    }

    private static void assertTrackedDependency(Path moduleDir, String testClass, String dependency) throws IOException {
        DependencyMap aggregated = aggregateDependencyMapFromDeps(moduleDir);
        DependencyMap index = loadDependencyMap(moduleDir);

        assertEquals(aggregated, index,
                "Expected aggregated dependency index to match emitted .deps files for " + moduleDir);
        assertTrue(index.testClasses().contains(testClass),
                "Expected " + testClass + " in dependency index for " + moduleDir);
        assertTrue(index.get(testClass).contains(dependency),
                "Expected " + testClass + " to depend on " + dependency + " in the aggregated index");
        assertTrue(readDepsFile(moduleDir, testClass).contains(dependency),
                "Expected " + testClass + ".deps to contain " + dependency);
    }

    private static DependencyMap loadDependencyMap(Path moduleDir) throws IOException {
        return DependencyMap.load(moduleDir.resolve(".test-order/test-dependencies.lz4"));
    }

    private static DependencyMap aggregateDependencyMapFromDeps(Path moduleDir) throws IOException {
        return DependencyMap.aggregate(moduleDir.resolve("build/test-order-deps"));
    }

    private static Set<String> readDepsFile(Path moduleDir, String testClass) throws IOException {
        return Files.readAllLines(moduleDir.resolve("build/test-order-deps").resolve(testClass + ".deps")).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static ShowOrderRow showOrderRow(String output, String simpleClassName) {
        String line = output.lines()
                .filter(candidate -> candidate.contains(simpleClassName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a show-order row for " + simpleClassName
                        + " but none was present.\n" + output));
        Matcher matcher = SHOW_ORDER_ROW_PATTERN.matcher(line.trim());
        assertTrue(matcher.matches(), "Unexpected show-order row format: " + line);

        // The tail columns are: Score  Deps  Fail  Changed  Duration
        // Fail, Changed, and Duration may be empty or contain non-numeric values (e.g. "3007ms").
        // Split by whitespace and parse only the first two numeric tokens (score and deps).
        String[] pieces = matcher.group(3).trim().split("\\s+");
        assertTrue(pieces.length >= 2, "Expected at least score and deps columns in row: " + line);
        int score = Integer.parseInt(pieces[0]);
        int depOverlap = Integer.parseInt(pieces[1]);
        return new ShowOrderRow(
                Integer.parseInt(matcher.group(1)),
                matcher.group(2),
                score,
                depOverlap);
    }

    private static void cleanGeneratedArtifacts(Path moduleDir) throws IOException {
        deleteTree(moduleDir.resolve(".test-order"));
        deleteTree(moduleDir.resolve("build/test-order-deps"));
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path currentDir, IOException exc) throws IOException {
                Files.delete(currentDir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static synchronized void publishArtifactsIfNeeded() throws IOException {
        if (artifactsPublished) {
            return;
        }
        CommandResult mavenInstall = runCommand(repoRoot,
                Map.of("JAVA_HOME", publishJavaHome),
                "mvn", "-q", "-pl", "test-order-agent,test-order-core,test-order-junit", "-am", "install", "-DskipTests");
        assertEquals(0, mavenInstall.exitCode(), mavenInstall.output());

        CommandResult gradlePublish = runCommand(repoRoot.resolve("test-order-gradle-plugin"),
                Map.of("JAVA_HOME", publishJavaHome),
                gradleWrapper(repoRoot.resolve("test-order-gradle-plugin")).toString(),
                "publishToMavenLocal", "--quiet");
        assertEquals(0, gradlePublish.exitCode(), gradlePublish.output());
        artifactsPublished = true;
    }

    private static CommandResult runSpringBoot(Path workingDir, String... args) throws IOException {
        return runSpringBoot(springBootDir, workingDir, scopedInitScript, args);
    }

    private static CommandResult runSpringBoot(Path baseDir, Path workingDir, Path initScript, String... args)
            throws IOException {
        String[] command = new String[args.length + 5];
        command[0] = gradleWrapper(baseDir).toString();
        command[1] = "--no-daemon";
        command[2] = "--no-build-cache";
        command[3] = "--init-script";
        command[4] = initScript.toString();
        System.arraycopy(args, 0, command, 5, args.length);
        return runCommand(workingDir,
                Map.of("JAVA_HOME", springJavaHome),
                command);
    }

    private static CommandResult runCommand(Path workingDir, Map<String, String> extraEnv, String... command)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDir.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.putAll(extraEnv);
        String javaHome = extraEnv.get("JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty()) {
            env.put("PATH", javaHome + "/bin:" + env.getOrDefault("PATH", ""));
        }

        System.err.println("[IT] Running: " + String.join(" ", command));
        Process process = builder.start();

        // Read output in a separate thread to avoid pipe-buffer deadlock
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = input.read(buf)) != -1) {
                    captured.write(buf, 0, n);
                    System.err.write(buf, 0, n); // forward progress to test runner stderr
                }
            } catch (IOException e) {
                // stream closed
            }
        }, "process-output-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            if (!process.waitFor(COMMAND_TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
                process.destroyForcibly();
                fail("Command timed out after " + COMMAND_TIMEOUT.toMinutes() + " minutes: "
                        + String.join(" ", command) + "\nPartial output:\n"
                        + captured.toString(StandardCharsets.UTF_8));
            }
            reader.join(5000); // give the reader thread a moment to finish
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            fail("Command interrupted: " + String.join(" ", command));
        }
        return new CommandResult(process.exitValue(), captured.toString(StandardCharsets.UTF_8));
    }

    private static Path writeScopedInitScript(List<String> projectPaths) throws IOException {
        return writeScopedInitScriptFor(springBootDir, projectPaths);
    }

    private static Path writeScopedInitScriptFor(Path springBootRoot, List<String> projectPaths) throws IOException {
        Path script = Files.createTempFile(tempDir, "test-order-spring-", ".gradle");
        String quotedProjects = projectPaths.stream()
                .map(path -> "'" + path + "'")
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        Files.writeString(script, """
                initscript {
                    repositories {
                        mavenLocal()
                        mavenCentral()
                    }
                    dependencies {
                        classpath 'me.bechberger:test-order-gradle-plugin:0.1.0-SNAPSHOT'
                    }
                }

                def testOrderTargets = [%s] as Set

                projectsLoaded {
                    allprojects { project ->
                        if (project.projectDir.name == 'buildSrc') {
                            return
                        }
                        if (!testOrderTargets.contains(project.path)) {
                            return
                        }
                        project.plugins.withId('java') {
                            project.repositories {
                                mavenLocal()
                            }
                            project.apply plugin: me.bechberger.testorder.gradle.TestOrderPlugin
                        }
                    }
                }
                """.formatted(quotedProjects));
        return script;
    }

    private static Path gradleWrapper(Path projectDir) {
        return projectDir.resolve(System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "gradlew.bat"
                : "gradlew");
    }

    private static Path locateRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        if (current.getFileName().toString().equals("test-order-gradle-plugin")) {
            return current.getParent();
        }
        return current;
    }

    private static Optional<String> findSpringJavaHome() {
        return findSpecificJavaHome("25+")
                .or(() -> findSpecificJavaHome("25"))
                .or(() -> findSpecificJavaHome("26"))
                .or(() -> findJavaHomeAtLeast(25));
    }

    private static Optional<String> findPublishJavaHome() {
        return findSpecificJavaHome("21")
                .or(() -> findSpecificJavaHome("24"))
                .or(() -> findSpecificJavaHome("17"))
                .or(SpringBootCoreModulesIT::findJavaHomeForPluginBuild);
    }

    private static Optional<String> findJavaHomeForPluginBuild() {
        int current = Runtime.version().feature();
        if (current >= 17 && current <= 24) {
            return Optional.of(Path.of(System.getProperty("java.home")).toString());
        }
        return discoverJavaHomes().stream()
                .filter(home -> home.version() >= 17 && home.version() <= 24)
                .sorted((left, right) -> Integer.compare(preferredPluginVersion(right.version()),
                        preferredPluginVersion(left.version())))
                .map(home -> home.path().toString())
                .findFirst();
    }

    private static Optional<String> findJavaHomeAtLeast(int version) {
        if (Runtime.version().feature() >= version) {
            return Optional.of(Path.of(System.getProperty("java.home")).toString());
        }
        return discoverJavaHomes().stream()
                .filter(home -> home.version() >= version)
                .max((left, right) -> Integer.compare(left.version(), right.version()))
                .map(home -> home.path().toString());
    }

    private static Optional<String> findSpecificJavaHome(String versionSelector) {
        String propertyName = "testorder.java." + versionSelector.replace("+", "plus") + ".home";
        String configured = System.getProperty(propertyName);
        if (configured != null && !configured.isBlank()) {
            return Optional.of(configured);
        }
        for (String envName : List.of("JAVA_" + versionSelector.replace("+", "_PLUS") + "_HOME",
                "JAVA_" + versionSelector.replace("+", "") + "_HOME")) {
            String value = System.getenv(envName);
            if (value != null && !value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static List<JavaHome> discoverJavaHomes() {
        List<Path> candidates = List.of(
                Path.of(System.getProperty("java.home")),
                Path.of(System.getProperty("user.home"), ".sdkman/candidates/java"),
                Path.of(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines"),
                Path.of("/Library/Java/JavaVirtualMachines")
        );
        return candidates.stream()
                .flatMap(path -> expandJavaHomeCandidates(path).stream())
                .distinct()
                .map(SpringBootCoreModulesIT::probeJavaHome)
                .flatMap(Optional::stream)
                .toList();
    }

    private static List<Path> expandJavaHomeCandidates(Path candidate) {
        if (Files.isDirectory(candidate.resolve("bin"))) {
            return List.of(candidate);
        }
        if (!Files.isDirectory(candidate)) {
            return List.of();
        }
        try (var children = Files.list(candidate)) {
            return children
                    .map(path -> {
                        Path contentsHome = path.resolve("Contents/Home");
                        return Files.isDirectory(contentsHome) ? contentsHome : path;
                    })
                    .filter(path -> Files.isDirectory(path.resolve("bin")))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Optional<JavaHome> probeJavaHome(Path javaHome) {
        Path javaBin = javaHome.resolve("bin/java");
        if (!Files.isRegularFile(javaBin)) {
            return Optional.empty();
        }
        try {
            CommandResult result = runCommand(repoRoot != null ? repoRoot : Path.of("").toAbsolutePath(),
                    Map.of(),
                    javaBin.toString(), "-version");
            if (result.exitCode() != 0) {
                return Optional.empty();
            }
            return parseJavaMajor(result.output()).map(version -> new JavaHome(javaHome, version));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> parseJavaMajor(String output) {
        for (String line : output.lines().toList()) {
            int versionStart = line.indexOf('"');
            int versionEnd = line.indexOf('"', versionStart + 1);
            if (versionStart < 0 || versionEnd < 0) {
                continue;
            }
            String version = line.substring(versionStart + 1, versionEnd);
            String[] parts = version.split("[.-]");
            if (parts.length == 0) {
                continue;
            }
            try {
                if ("1".equals(parts[0]) && parts.length > 1) {
                    return Optional.of(Integer.parseInt(parts[1]));
                }
                return Optional.of(Integer.parseInt(parts[0]));
            } catch (NumberFormatException ignored) {
                // try next line
            }
        }
        return Optional.empty();
    }

    private static int preferredPluginVersion(int version) {
        if (version == 21) {
            return 1000;
        }
        if (version == 17) {
            return 900;
        }
        return version;
    }

    private record CommandResult(int exitCode, String output) {}
    private record JavaHome(Path path, int version) {}
    private record ShowOrderRow(int rank, String displayName, int score, int depOverlap) {}
}
