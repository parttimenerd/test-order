package me.bechberger.testorder.gradle;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.PersistenceSupport;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestScorer;
import me.bechberger.testorder.TestSelector;
import me.bechberger.testorder.changes.ChangeComplexity;
import me.bechberger.testorder.changes.ChangeDetector;
import me.bechberger.testorder.changes.ChangeDetectionSupport;
import me.bechberger.testorder.changes.FileHashStore;
import me.bechberger.testorder.changes.HashSnapshotSupport;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;
import me.bechberger.testorder.changes.StructuralDiff;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;
import me.bechberger.testorder.OptimizationDefaults;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.testing.Test;
import org.gradle.process.CommandLineArgumentProvider;

import me.bechberger.testorder.DashboardGenerator;
import me.bechberger.testorder.DashboardGenerator.ScoredTest;
import me.bechberger.testorder.dashboard.DashboardResources;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Gradle plugin for JUnit test class priority ordering based on runtime dependency telemetry.
 * <p>
 * Supports the following modes:
 * <ul>
 *   <li><b>learn</b> — attaches the instrumentation agent to collect test→dependency data</li>
 *   <li><b>order</b> — injects PriorityClassOrderer to reorder tests by predicted relevance</li>
 *   <li><b>optimize</b> — runs tests in order mode, then tunes scoring weights from run history</li>
 *   <li><b>auto</b> — selects learn or order automatically based on whether an index file exists</li>
 *   <li><b>skip</b> — disables plugin behaviour for this run</li>
 * </ul>
 * <p>
 * Apply via {@code id "me.bechberger.test-order"} in the plugins block, or
 * via an init script for external projects.
 */
public class TestOrderPlugin implements Plugin<Project> {

    static final String EXTENSION_NAME = "testOrder";
    static final String AGENT_CONFIG_NAME = "testOrderAgent";
    /** Group and version for the test-order artifacts. Must match the build. */
    static final String GROUP_ID = "me.bechberger";
    static final String VERSION = "0.1.0-SNAPSHOT";

    @Override
    public void apply(Project project) {
        // Guard: only apply once (init-script + build.gradle can both try)
        if (project.getExtensions().findByName(EXTENSION_NAME) != null) {
            project.getLogger().info("[test-order] Plugin already applied to {}, skipping", project.getPath());
            return;
        }

        TestOrderExtensionConfigurator extensionConfigurator = new TestOrderExtensionConfigurator(this);
        LearnModeConfigurator learnModeConfigurator = new LearnModeConfigurator(this);
        OrderModeConfigurator orderModeConfigurator = new OrderModeConfigurator(this);
        UtilityTaskRegistrar utilityTaskRegistrar = new UtilityTaskRegistrar(this);

        TestOrderExtensionConfigurator.ConfiguredPlugin configured = extensionConfigurator.configure(project);
        TestOrderExtension ext = configured.extension();
        Configuration agentConf = configured.agentConfiguration();

        utilityTaskRegistrar.register(project, ext, agentConf);
        project.afterEvaluate(p -> configureTestTasks(p, ext, agentConf,
                learnModeConfigurator, orderModeConfigurator));
    }

    static Configuration createHiddenConfiguration(Project project, String name,
                                                   boolean transitive) {
        // Avoid duplicate configurations (idempotent for init-script reapply)
        Configuration existing = project.getConfigurations().findByName(name);
        if (existing != null) return existing;
        Configuration conf = project.getConfigurations().create(name);
        conf.setVisible(false);
        conf.setTransitive(transitive);
        conf.setDescription("test-order internal: " + name);
        return conf;
    }

    /**
     * Adds test-order-junit and test-order-core (shaded) as testRuntimeOnly dependencies.
     * Also adds test-order-testng when TestNG is detected on the test classpath.
     * This is the Gradle-idiomatic way to add JARs to the test classpath and ensures
     * compatibility with Gradle 9.x's stricter module/classpath handling.
     */
    void addTestOrderTestDependencies(Project project) {
        // Thin JUnit JAR (ServiceLoader + 3 classes) — non-transitive
        ExternalModuleDependency junitDep = (ExternalModuleDependency) project.getDependencies().create(
                GROUP_ID + ":test-order-junit:" + VERSION);
        junitDep.setTransitive(false);
        project.getDependencies().add("testRuntimeOnly", junitDep);

        // Shaded core fat JAR ("all" classifier) — non-transitive, all deps bundled inside
        ExternalModuleDependency coreDep = (ExternalModuleDependency) project.getDependencies().create(
                Map.of("group", GROUP_ID, "name", "test-order-core", "version", VERSION, "classifier", "all"));
        coreDep.setTransitive(false);
        project.getDependencies().add("testRuntimeOnly", coreDep);

        // TestNG support: add after evaluation so user dependencies are resolved
        project.afterEvaluate(p -> {
            if (isTestNGOnTestClasspath(p)) {
                ExternalModuleDependency testngDep = (ExternalModuleDependency) p.getDependencies().create(
                        GROUP_ID + ":test-order-testng:" + VERSION);
                testngDep.setTransitive(false);
                p.getDependencies().add("testRuntimeOnly", testngDep);
                p.getLogger().lifecycle("[test-order] TestNG detected — adding test-order-testng support");
            }
        });
    }

    /**
     * Returns true when TestNG is declared as a test dependency in the project.
     */
    static boolean isTestNGOnTestClasspath(Project project) {
        return project.getConfigurations().stream()
                .filter(c -> c.getName().toLowerCase().contains("test"))
                .flatMap(c -> c.getDependencies().stream())
                .anyMatch(d -> "org.testng".equals(d.getGroup()) && "testng".equals(d.getName()));
    }

    // -----------------------------------------------------------------------
    // Test task configuration
    // -----------------------------------------------------------------------

    void configureTestTasks(Project project, TestOrderExtension ext,
                            Configuration agentConf,
                            LearnModeConfigurator learnModeConfigurator,
                            OrderModeConfigurator orderModeConfigurator) {
        String resolvedMode = orderModeConfigurator.resolveMode(ext, project);

        project.getTasks().withType(Test.class).configureEach(testTask -> {
            if (testTask.getName().startsWith("testOrder")) {
                return;
            }
            // Suppress "restricted method" warnings from bundled lz4 native access
            // (System.loadLibrary, sun.misc.Unsafe). Flag supported on JDK 16+;
            // the plugin already requires JDK 17+ so this is always safe.
            testTask.jvmArgs("--enable-native-access=ALL-UNNAMED");

            if ("learn".equals(resolvedMode)) {
                learnModeConfigurator.configure(project, ext, testTask, agentConf);
            } else if ("order".equals(resolvedMode)) {
                orderModeConfigurator.configure(project, ext, testTask);
            } else if ("optimize".equals(resolvedMode)) {
                // Optimize mode: run tests in priority order, then tune scoring weights
                orderModeConfigurator.configure(project, ext, testTask);
                testTask.doLast("testOrderOptimizeWeights", t -> {
                    Path statePath = ext.getStateFile().get().getAsFile().toPath();
                    if (!Files.exists(statePath)) {
                        project.getLogger().warn(
                                "[test-order] Optimize mode: state file not found at {} — skipping weight optimisation.",
                                statePath);
                        return;
                    }
                    withStateFileLock(statePath, () -> {
                        TestOrderState state = TestOrderState.load(statePath);
                        long withFailures = state.runs().stream()
                                .filter(r -> r.totalFailures() > 0).count();
                        TestOrderState.OptimizeResult optimized = state.optimize();
                        if (optimized == null) {
                            project.getLogger().info(
                                    "[test-order] Need at least {} runs with failures to optimise (have {}).",
                                    OptimizationDefaults.MIN_RUNS_FOR_OPTIMISATION, withFailures);
                            return null;
                        }
                        state.setWeights(optimized.weights());
                        try {
                            state.save(statePath);
                            project.getLogger().lifecycle(
                                    "[test-order] Optimised scoring weights saved: {}",
                                    optimized.weights().format());
                        } catch (IOException e) {
                            project.getLogger().warn(
                                    "[test-order] Failed to save optimised weights: {}", e.getMessage());
                        }
                        if (optimized.overfit()) {
                            project.getLogger().warn(
                                    "[test-order] Overfitting detected — default weights used instead.");
                        }
                        return null;
                    });
                });
            }
            // "skip" or unrecognized → do nothing (just add junit jar for telemetry)
        });
    }

    // -----------------------------------------------------------------------
    // Mode resolution
    // -----------------------------------------------------------------------

    /**
     * Resolves the effective mode string: "learn", "order", or "skip".
     * Precedence: -Dtestorder.mode > -Ptestorder.mode > testOrder.mode DSL > auto-detect.
     */
    String resolveMode(TestOrderExtension ext, Project project) {
        String mode = ext.getMode().get();

        // Check Gradle property / system property override
        String propMode = gradleOrSystemProperty(project, "testorder.mode");
        if (propMode != null) mode = propMode;

        if ("learn".equalsIgnoreCase(mode)) return "learn";
        if ("order".equalsIgnoreCase(mode)) return "order";
        if ("optimize".equalsIgnoreCase(mode)) return "optimize";
        if ("skip".equalsIgnoreCase(mode) || "off".equalsIgnoreCase(mode)
                || "none".equalsIgnoreCase(mode)) return "skip";
        if ("auto".equalsIgnoreCase(mode) || "combined".equalsIgnoreCase(mode)
                || mode.isEmpty()) {
            // fall through to auto-detect below
        } else {
            throw new GradleException("[test-order] Invalid mode: '" + mode
                    + "'. Valid values: auto, learn, order, optimize, skip");
        }

        // auto: learn if no index file exists, order otherwise
        File indexFile = ext.getIndexFile().getAsFile().get();
        if (!indexFile.exists()) {
            project.getLogger().lifecycle(
                    "[test-order] No index file found — auto-selecting learn mode. "
                    + "Pass -Dtestorder.mode=skip to disable.");
            return "learn";
        }
        return "order";
    }

    // -----------------------------------------------------------------------
    // Learn mode
    // -----------------------------------------------------------------------

    void configureLearnMode(Project project, TestOrderExtension ext,
                            Test testTask, Configuration agentConf) {
        project.getLogger().lifecycle("[test-order] Configuring learn mode for task :{}",
                testTask.getName());

        // Resolve effective instrumentation mode: CLI property overrides extension DSL
        String instrMode = ext.getInstrumentationMode().get();
        String propInstrMode = gradleOrSystemProperty(project, "testorder.instrumentationMode");
        if (propInstrMode == null) {
            propInstrMode = gradleOrSystemProperty(project, "testorder.instrumentation.mode");
        }
        if (propInstrMode != null && !propInstrMode.isBlank()) {
            instrMode = propInstrMode;
        }

        // System properties for the forked test JVM
        testTask.systemProperty("testorder.learn", "true");
        testTask.systemProperty("testorder.instrumentation.mode", instrMode.toUpperCase());
        testTask.systemProperty("testorder.state.path",
                ext.getStateFile().get().getAsFile().getAbsolutePath());

        // Attach agent lazily via CommandLineArgumentProvider (configuration-cache safe)
        testTask.getJvmArgumentProviders().add(
                new AgentArgumentProvider(project, ext, agentConf, instrMode.toUpperCase()));

        // Snapshot source and test hashes before tests run, so that future
        // SINCE_LAST_RUN change detection has a baseline to compare against.
        testTask.doFirst("snapshotHashes", t -> {
            snapshotHashes(project, ext);
        });
        testTask.doLast("aggregateDeps", t -> {
            aggregateDependencyFiles(project, ext, false);
        });
    }

    /**
     * Provides the {@code -javaagent} argument at execution time.
     * All values are captured eagerly as plain strings at configuration time
     * so the provider is serializable for Gradle's configuration cache.
     * The agent classpath is stored as a {@link org.gradle.api.file.FileCollection}
     * (not a raw {@link Configuration}) so Gradle can track and serialize it.
     */
    private static class AgentArgumentProvider implements CommandLineArgumentProvider {
        private final org.gradle.api.file.FileCollection agentClasspath;
        private final String instrumentationMode;
        private final String depsDirPath;
        private final String indexFilePath;
        private final String includePackages;
        private final String verboseFile;

        AgentArgumentProvider(Project project, TestOrderExtension ext,
                              Configuration agentConf, String instrumentationMode) {
            this.agentClasspath = agentConf;
            this.instrumentationMode = instrumentationMode;
            this.depsDirPath = ext.getDepsDir().get().getAsFile().getAbsolutePath();
            this.indexFilePath = ext.getIndexFile().get().getAsFile().getAbsolutePath();
            this.verboseFile = ext.getVerboseFile().get();
            // Resolve include packages at configuration time
            String configured = ext.getIncludePackages().get();
            if (!configured.isEmpty()) {
                this.includePackages = configured;
            } else {
                Path sourceRoot = resolveMainSourceRoot(project);
                this.includePackages = PackageDetector.resolveIncludePackages(
                        null,
                        ext.getFilterByGroupId().get(),
                        String.valueOf(project.getGroup()),
                        sourceRoot,
                        project.getLogger());
            }
        }

        @Override
        public Iterable<String> asArguments() {
            File agentJar = agentClasspath.getSingleFile();

            StringBuilder agentArgs = new StringBuilder();
            agentArgs.append("outputDir=").append(depsDirPath);
            agentArgs.append(",mode=").append(instrumentationMode);
            agentArgs.append(",indexFile=").append(indexFilePath);

            if (includePackages != null) {
                agentArgs.append(",includePackages=").append(includePackages.replace(",", ";"));
            }

            if (!verboseFile.isEmpty()) {
                agentArgs.append(",verboseFile=").append(Path.of(verboseFile).toAbsolutePath());
            }

            return List.of(
                    "-Xshare:off",
                    "-javaagent:" + quoteIfNeeded(agentJar.getAbsolutePath()) + "=" + agentArgs);
        }
    }

    // -----------------------------------------------------------------------
    // Order mode
    // -----------------------------------------------------------------------

    void configureOrderMode(Project project, TestOrderExtension ext, Test testTask) {
        File indexFile = ext.getIndexFile().getAsFile().get();
        if (!indexFile.exists()) {
            project.getLogger().warn("[test-order] Index file {} not found — skipping order mode. "
                    + "Run with -Dtestorder.mode=learn first.", indexFile);
            return;
        }
        project.getLogger().lifecycle("[test-order] Configuring order mode for task :{}",
                testTask.getName());

        // Inject PriorityClassOrderer as the JUnit 5 global class orderer
        testTask.systemProperty("junit.jupiter.testclass.order.default",
                "me.bechberger.testorder.PriorityClassOrderer");

        // Core config properties — PriorityClassOrderer reads these via System.getProperty()
        testTask.systemProperty("testorder.index.path", indexFile.getAbsolutePath());
        testTask.systemProperty("testorder.state.path",
                ext.getStateFile().get().getAsFile().getAbsolutePath());
        testTask.systemProperty("testorder.changeMode", ext.getChangeMode().get());
        testTask.systemProperty("testorder.project.root",
                project.getProjectDir().getAbsolutePath());

        // Forward debug flag so PriorityClassOrderer can emit verbose scoring output
        String debugFlag = gradleOrSystemProperty(project, "testorder.debug");
        if ("true".equalsIgnoreCase(debugFlag)) {
            testTask.systemProperty("testorder.debug", "true");
        }

        // Source root for structural diff / complexity computation
        Path sourceRoot = resolveMainSourceRoot(project);
        testTask.systemProperty("testorder.source.root", sourceRoot.toAbsolutePath().toString());

        // Method ordering
        if (ext.getMethodOrderingEnabled().get()) {
            testTask.systemProperty("testorder.methodOrder.enabled", "true");
        }

        // Weights file
        String weightsFile = ext.getWeightsFile().get();
        if (!weightsFile.isEmpty()) {
            testTask.systemProperty("testorder.weights.file",
                    Path.of(weightsFile).toAbsolutePath().toString());
        }

        // Scoring weight overrides — only pass when explicitly configured so that
        // PriorityClassOrderer can use optimizer-tuned weights from the state file.
        setScorePropertyIfPresent(testTask, ext.getScoreNewTest(), "newTest");
        setScorePropertyIfPresent(testTask, ext.getScoreChangedTest(), "changedTest");
        setScorePropertyIfPresent(testTask, ext.getScoreMaxFailure(), "maxFailure");
        setScorePropertyIfPresent(testTask, ext.getScoreSpeed(), "speed");
        setScorePropertyIfPresent(testTask, ext.getScoreSpeedPenalty(), "speedPenalty");
        setScorePropertyIfPresent(testTask, ext.getScoreDepOverlap(), "depOverlap");
        setScorePropertyIfPresent(testTask, ext.getScoreChangeComplexity(), "changeComplexity");
        setScorePropertyIfPresent(testTask, ext.getScoreStaticFieldBonus(), "staticFieldBonus");
        setScorePropertyIfPresent(testTask, ext.getScoreCoverageBonus(), "coverageBonus");

        // Change detection runs at execution time (not during configuration)
        testTask.doFirst("detectChangedClasses", t -> {
            injectChangedClasses(project, ext, (Test) t);
        });
    }

    /**
     * Runs change detection and injects the result as system properties
     * on the test task. Executed in doFirst (task execution phase).
     */
    private static void injectChangedClasses(Project project, TestOrderExtension ext,
                                              Test testTask) {
        String changeModeStr = ext.getChangeMode().get();
        ensureSupportedChangeMode(changeModeStr);
        String explicitChanged = ext.getChangedClasses().get();

        // Override from Gradle/system property
        String propChanged = gradleOrSystemProperty(project, "testorder.changed.classes");
        if (propChanged != null && !propChanged.isEmpty()) {
            testTask.systemProperty("testorder.changed.classes", propChanged);
            project.getLogger().lifecycle("[test-order] Using explicitly provided changed classes");
            detectAndInjectChangedTestClasses(project, ext, testTask, changeModeStr);
            return;
        }

        // If explicit mode with a provided list, inject it directly
        if ("explicit".equalsIgnoreCase(changeModeStr) && !explicitChanged.isEmpty()) {
            testTask.systemProperty("testorder.changed.classes", explicitChanged);
            return;
        }

        // Use ChangeDetector for source class changes
        try {
            Path projectRoot = project.getProjectDir().toPath();
            Path sourceRoot = resolveMainSourceRoot(project);
            Path hashFile = ext.getHashFile().get().getAsFile().toPath();
                Set<String> changed = ChangeDetectionSupport.detectChangedClasses(
                    changeModeStr,
                    projectRoot,
                    sourceRoot,
                    hashFile,
                    explicitChanged.isEmpty() ? null : explicitChanged,
                    false);
            if (!changed.isEmpty()) {
                testTask.systemProperty("testorder.changed.classes",
                        String.join(",", changed));
                project.getLogger().lifecycle("[test-order] Detected {} changed classes",
                        changed.size());
            } else {
                project.getLogger().info("[test-order] No changed classes detected");
            }
        } catch (IOException e) {
            project.getLogger().warn("[test-order] Change detection failed: {}", e.getMessage());
        }

        // Also detect changed test classes (for the changedTest scoring bonus)
        detectAndInjectChangedTestClasses(project, ext, testTask, changeModeStr);
    }

    /**
     * Detects changed test classes and injects the result as a system property.
     * EXPLICIT mode is skipped (explicit classes are main sources, not tests).
     */
    private static void detectAndInjectChangedTestClasses(Project project,
                                                           TestOrderExtension ext,
                                                           Test testTask,
                                                           String changeModeStr) {
        if ("explicit".equalsIgnoreCase(changeModeStr)) return;
        try {
            Path projectRoot = project.getProjectDir().toPath();
            Path testSourceRoot = resolveTestSourceRoot(project);
            Path testHashFile = ext.getTestHashFile().get().getAsFile().toPath();
            Set<String> changedTests = ChangeDetectionSupport.detectChangedTestClasses(
                changeModeStr,
                projectRoot,
                testSourceRoot,
                testHashFile,
                false);
            if (!changedTests.isEmpty()) {
                testTask.systemProperty("testorder.changed.test.classes",
                        String.join(",", changedTests));
                project.getLogger().lifecycle("[test-order] Detected {} changed test classes",
                        changedTests.size());
            }
        } catch (IOException e) {
            project.getLogger().debug("[test-order] Test class change detection failed: {}",
                    e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Task registration
    // -----------------------------------------------------------------------

    void registerTasks(Project project, TestOrderExtension ext,
                       Configuration agentConf) {

        project.getTasks().register("testOrderAggregate", task -> {
            task.setGroup("test-order");
            task.setDescription(
                    "Aggregate .deps files into the binary dependency index (test-dependencies.lz4)");
            task.doLast(t -> {
                aggregateDependencyFiles(project, ext, true);
            });
        });

        project.getTasks().register("testOrderDump", task -> {
            task.setGroup("test-order");
            task.setDescription("Dump the binary dependency index as human-readable text");
            task.doLast(t -> {
                Path indexFile = ext.getIndexFile().get().getAsFile().toPath();
                if (!Files.exists(indexFile)) {
                    throw new GradleException("[test-order] Index file not found: " + indexFile
                            + ". Run tests in learn mode first.");
                }
                try {
                    DependencyMap map = DependencyMap.load(indexFile);
                    Path tmpText = Files.createTempFile("test-order-dump", ".txt");
                    map.saveText(tmpText);
                    Files.readAllLines(tmpText).forEach(System.out::println);
                    Files.deleteIfExists(tmpText);
                } catch (IOException e) {
                    throw new GradleException("Failed to dump dependency index", e);
                }
            });
        });

        project.getTasks().register("testOrderShowOrder", task -> {
            task.setGroup("test-order");
            task.setDescription(
                    "Display the predicted test execution order without running tests");
            task.doLast(t -> {
                Path indexPath = ext.getIndexFile().get().getAsFile().toPath();
                Path statePath = ext.getStateFile().get().getAsFile().toPath();
                if (!Files.exists(indexPath)) {
                    throw new GradleException("[test-order] Index file not found: " + indexPath
                            + ". Run tests in learn mode first.");
                }
                try {
                    DependencyMap depMap = DependencyMap.load(indexPath);
                    TestOrderState state = Files.exists(statePath)
                            ? TestOrderState.load(statePath) : new TestOrderState();

                    Path sourceRoot = resolveMainSourceRoot(project);
                    Set<String> changed = Collections.emptySet();
                    String explicitChanged = ext.getChangedClasses().get();
                    String propChanged = gradleOrSystemProperty(project, "testorder.changed.classes");
                    if (propChanged != null && !propChanged.isBlank()) {
                        changed = Arrays.stream(propChanged.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .collect(Collectors.toCollection(LinkedHashSet::new));
                    } else if (explicitChanged != null && !explicitChanged.isBlank()) {
                        changed = Arrays.stream(explicitChanged.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .collect(Collectors.toCollection(LinkedHashSet::new));
                    } else {
                        try {
                            Path hashFile = ext.getHashFile().get().getAsFile().toPath();
                            String changeModeStr = ext.getChangeMode().get();
                            changed = ChangeDetectionSupport.detectChangedClasses(
                                    changeModeStr,
                                    project.getProjectDir().toPath(),
                                    sourceRoot,
                                    hashFile,
                                    null,
                                    true);
                        } catch (IOException e) {
                            project.getLogger().debug("[test-order] Change detection skipped: {}",
                                    e.getMessage());
                        }
                    }

                    // Also detect changed test classes (for changedTest bonus display)
                    Set<String> changedTests = Collections.emptySet();
                    try {
                        Path testSourceRoot = resolveTestSourceRoot(project);
                        Path testHashFile = ext.getTestHashFile().get().getAsFile().toPath();
                        String changeModeStr = ext.getChangeMode().get();
                        if (!"explicit".equalsIgnoreCase(changeModeStr)) {
                            changedTests = ChangeDetectionSupport.detectChangedTestClasses(
                                    changeModeStr,
                                    project.getProjectDir().toPath(),
                                    testSourceRoot,
                                    testHashFile,
                                    true);
                        }
                    } catch (IOException e) {
                        project.getLogger().debug("[test-order] Test change detection skipped: {}",
                                e.getMessage());
                    }

                    // Structural analysis for precise member-level overlap
                    ChangedMembers changedMembers = null;
                    List<StructuralDiff.FileDiff> structuralDiffs = null;
                    if (!changed.isEmpty()) {
                        try {
                            Path projectRoot = project.getProjectDir().toPath().toAbsolutePath();
                            String changeModeStr = ext.getChangeMode().get();
                            StructuralChangeAnalyzer.AnalysisResult analysis;
                            if ("since-last-commit".equalsIgnoreCase(changeModeStr)
                                    || "SINCE_LAST_COMMIT".equalsIgnoreCase(changeModeStr)) {
                                analysis = StructuralChangeAnalyzer.analyzeSinceLastCommitFull(projectRoot);
                            } else {
                                analysis = StructuralChangeAnalyzer.analyzeUncommittedFull(projectRoot);
                            }
                            changedMembers = analysis.changedMembers();
                            structuralDiffs = analysis.diffs();
                        } catch (IOException e) {
                            project.getLogger().debug("[test-order] Structural analysis skipped: {}",
                                    e.getMessage());
                        }
                    }

                    // Change complexity scoring
                    Map<String, Double> changeComplexityMap = Map.of();
                    if (!changed.isEmpty()) {
                        List<Path> sourceRoots = new ArrayList<>();
                        sourceRoots.add(sourceRoot);
                        // Also check Kotlin source root
                        Path ktRoot = resolveKotlinSourceRoot(project);
                        if (Files.isDirectory(ktRoot)) {
                            sourceRoots.add(ktRoot);
                        }
                        changeComplexityMap = ChangeComplexity.compute(changed, sourceRoots, changedMembers, structuralDiffs);
                    }

                    TestOrderState.ScoringWeights weights = state.weights() != null
                            ? state.weights() : TestOrderState.ScoringWeights.DEFAULT;
                    List<String> testClasses = new ArrayList<>(depMap.testClasses());
                    TestScorer scorer = new TestScorer.Builder(weights, depMap, state, changed, changedTests)
                            .testClassNames(testClasses)
                            .changedMembers(changedMembers)
                            .changeComplexity(changeComplexityMap)
                            .build();
                    Map<String, TestScorer.ScoreResult> scoreCache = new HashMap<>(testClasses.size());
                    for (String testClass : testClasses) {
                        scoreCache.put(testClass, scorer.score(testClass));
                    }
                    testClasses.sort((a, b) -> {
                        int sa = scoreCache.get(a).score();
                        int sb = scoreCache.get(b).score();
                        if (sa != sb) return Integer.compare(sb, sa);
                        // tie-break: shorter duration first, then alphabetical
                        long da = state.getDuration(a, Long.MAX_VALUE);
                        long db = state.getDuration(b, Long.MAX_VALUE);
                        if (da != db) return Long.compare(da, db);
                        return a.compareTo(b);
                    });

                    System.out.println("=== Predicted test execution order ===");
                    if (!changed.isEmpty()) {
                        System.out.println("Changed classes: " + String.join(", ", changed));
                    }
                    if (!changedTests.isEmpty()) {
                        System.out.println("Changed test classes: " + String.join(", ", changedTests));
                    }
                        System.out.println();

                    // Compute column widths
                    int maxName = "Test Class".length();
                    for (String tc : testClasses) {
                        String shortName = shortenClassName(tc);
                        if (shortName.length() > maxName) maxName = shortName.length();
                    }

                    String fmt = "  %-4s %-" + maxName + "s %6s %5s %5s %8s %8s%n";
                    System.out.printf(fmt, "#", "Test Class", "Score", "Deps", "Fail",
                            "Changed", "Duration");
                    System.out.printf(fmt, "\u2014", "\u2014".repeat(maxName),
                            "\u2014".repeat(6), "\u2014".repeat(5), "\u2014".repeat(5),
                            "\u2014".repeat(8), "\u2014".repeat(8));
                    for (int i = 0; i < testClasses.size(); i++) {
                        String tc = testClasses.get(i);
                        TestScorer.ScoreResult sr = scoreCache.get(tc);
                        long dur = state.getDuration(tc, -1);
                        System.out.printf(fmt,
                                (i + 1) + ".",
                                shortenClassName(tc),
                                sr.score(),
                                sr.depOverlap() > 0 ? sr.depOverlap() : "",
                                sr.failScore() > 0 ? String.format("%.1f", sr.failScore()) : "",
                                sr.isChanged() ? "yes" : "",
                                dur >= 0 ? dur + "ms" : "");
                    }
                    System.out.println();
                } catch (IOException e) {
                    throw new GradleException("Failed to show test order", e);
                }
            });
        });

        project.getTasks().register("testOrderOptimize", task -> {
            task.setGroup("test-order");
            task.setDescription("Optimise scoring weights from the recorded run history");
            task.doLast(t -> {
                Path statePath = ext.getStateFile().get().getAsFile().toPath();
                if (!Files.exists(statePath)) {
                    throw new GradleException("[test-order] State file not found: " + statePath
                            + ". Run some test-order test runs first.");
                }
                withStateFileLock(statePath, () -> {
                    TestOrderState state = TestOrderState.load(statePath);
                    long withFailures = state.runs().stream().filter(r -> r.totalFailures() > 0).count();
                    project.getLogger().lifecycle("[test-order] Runs: {} total, {} with failures",
                            state.runs().size(), withFailures);
                    project.getLogger().lifecycle("[test-order] Current weights: {}", state.weights().format());
                    long startMs = System.currentTimeMillis();
                    TestOrderState.OptimizeResult optimized = state.optimize();
                    long elapsedMs = System.currentTimeMillis() - startMs;
                    if (optimized == null) {
                        project.getLogger().warn("[test-order] Need at least {} runs with failures to optimise (have {}).",
                                OptimizationDefaults.MIN_RUNS_FOR_OPTIMISATION, withFailures);
                        return null;
                    }
                    state.setWeights(optimized.weights());
                    state.save(statePath);
                    project.getLogger().lifecycle("[test-order] Optimised weights: {} ({:.1f}s)",
                            optimized.weights().format(), elapsedMs / 1000.0);
                    if (optimized.overfit()) {
                        project.getLogger().warn("[test-order] Overfitting detected — default weights used instead.");
                    }
                    return null;
                });
            });
        });

        project.getTasks().register("testOrderSelect", Test.class, task -> {
            configureDerivedTestTask(project, ext, task);
            task.setGroup("test-order");
            task.setDescription("Run the prioritized selected subset of tests and write remaining tests to disk");
            configureOrderMode(project, ext, task);
            task.doFirst("testOrderSelectPrepare", t -> {
                TestSelector.Selection selection = selectTests(project, ext);
                applySelectedTests((Test) t, selection.selected());
                project.getLogger().lifecycle("[test-order] Selected {} tests, deferred {}",
                        selection.selected().size(), selection.remaining().size());
            });
        });

        project.getTasks().register("testOrderRunRemaining", Test.class, task -> {
            configureDerivedTestTask(project, ext, task);
            task.setGroup("test-order");
            task.setDescription("Run only the deferred tests written by testOrderSelect");
            task.doFirst("testOrderRunRemainingPrepare", t -> {
                Path remainingFile = ext.getRemainingFile().get().getAsFile().toPath();
                if (!Files.exists(remainingFile)) {
                    project.getLogger().lifecycle("[test-order] No remaining-tests file found at {} — nothing to run.",
                            remainingFile);
                    applySelectedTests((Test) t, List.of());
                    return;
                }
                try {
                    List<String> tests = TestSelector.readTestList(remainingFile);
                    if (tests.isEmpty()) {
                        project.getLogger().lifecycle("[test-order] Remaining tests file is empty — skipping tests.");
                    } else {
                        project.getLogger().lifecycle("[test-order] Running {} remaining test classes", tests.size());
                    }
                    applySelectedTests((Test) t, tests);
                } catch (IOException e) {
                    throw new GradleException("Failed to read remaining tests file", e);
                }
            });
        });

        project.getTasks().register("testOrderClean", task -> {
            task.setGroup("test-order");
            task.setDescription("Remove all test-order generated files "
                    + "(index, state, hashes, deps dir)");
            task.doLast(t -> {
                int deleted = 0;
                for (File f : new File[]{
                        ext.getIndexFile().get().getAsFile(),
                        ext.getStateFile().get().getAsFile(),
                        ext.getHashFile().get().getAsFile(),
                        ext.getTestHashFile().get().getAsFile(),
                        ext.getMethodHashFile().get().getAsFile()}) {
                    if (f.exists() && f.delete()) {
                        project.getLogger().lifecycle("[test-order] Deleted {}", f);
                        deleted++;
                    }
                }
                Path depsDir = ext.getDepsDir().get().getAsFile().toPath();
                if (Files.isDirectory(depsDir)) {
                    try (var walk = Files.walk(depsDir)) {
                        walk.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException e) {
                                    project.getLogger().warn("[test-order] Failed to delete {}: {}",
                                            p, e.getMessage());
                                }
                            });
                    } catch (IOException e) {
                        project.getLogger().warn("[test-order] Failed to walk {}: {}",
                                depsDir, e.getMessage());
                    }
                    deleted++;
                    project.getLogger().lifecycle("[test-order] Deleted {}", depsDir);
                }
                if (deleted == 0) {
                    project.getLogger().lifecycle("[test-order] Nothing to clean");
                }
            });
        });
        project.getTasks().register("testOrderDashboard", task -> {
            task.setGroup("test-order");
            task.setDescription(
                    "Generate an HTML dashboard visualising test scoring, dependencies, and run history");
            task.mustRunAfter("test");
            task.doLast(t -> {
                generateDashboard(project, ext);
            });
        });

        project.getTasks().register("testOrderServe", task -> {
            task.setGroup("test-order");
            task.setDescription(
                    "Generate and serve the test-order HTML dashboard on a local HTTP server");
            task.mustRunAfter("test");
            task.doLast(t -> {
                Path outPath = generateDashboard(project, ext);
                serveDashboard(project, outPath.getParent());
            });
        });
    }

    // -----------------------------------------------------------------------
    // Dashboard helpers
    // -----------------------------------------------------------------------

    /** Builds and writes the self-contained dashboard HTML; returns the path to index.html. */
    private Path generateDashboard(Project project, TestOrderExtension ext) {
        Path indexPath = ext.getIndexFile().get().getAsFile().toPath();
        Path statePath = ext.getStateFile().get().getAsFile().toPath();
        Path outDir    = project.getLayout().getBuildDirectory()
                .dir("test-order-dashboard").get().getAsFile().toPath();
        Path htmlOut   = outDir.resolve("index.html");

        if (!Files.exists(indexPath)) {
            throw new GradleException("[test-order] Index file not found: " + indexPath
                    + ". Run tests in learn mode first.");
        }
        try {
            DependencyMap depMap = DependencyMap.load(indexPath);
            TestOrderState state = Files.exists(statePath)
                    ? TestOrderState.load(statePath) : new TestOrderState();

            TestOrderState.ScoringWeights weights = state.weights() != null
                    ? state.weights() : TestOrderState.ScoringWeights.DEFAULT;
            List<String> testClasses = new ArrayList<>(depMap.testClasses());
            TestScorer scorer = new TestScorer.Builder(
                    weights, depMap, state,
                    Collections.emptySet(), Collections.emptySet())
                    .testClassNames(testClasses)
                    .build();

            List<ScoredTest> scored = new ArrayList<>();
            for (String tc : testClasses) {
                TestScorer.ScoreResult r = scorer.score(tc);
                long dur   = state.getDuration(tc, -1);
                double var = state.getDurationVariance(tc, -1.0);
                scored.add(new ScoredTest(tc, r, dur, var));
            }
            scored.sort(Comparator
                    .<ScoredTest, Integer>comparing(s -> s.result().score()).reversed()
                    .thenComparingLong(s -> s.duration() >= 0 ? s.duration() : Long.MAX_VALUE)
                    .thenComparing(ScoredTest::name));

            long medianDuration = DashboardGenerator.computeMedian(scored.stream()
                    .filter(s -> s.duration() >= 0)
                    .mapToLong(ScoredTest::duration)
                    .toArray());

            DashboardGenerator gen = new DashboardGenerator(
                    project.getName(), statePath.toString(),
                    indexPath.toString(), "gradle");
            Map<String, Object> data = gen.buildData(
                    scored, Collections.emptySet(), Collections.emptySet(),
                    state, weights, depMap, medianDuration);

            String template = DashboardResources.assembleTemplate();
            String html = gen.injectIntoTemplate(template, data);

            Files.createDirectories(outDir);
            Files.writeString(htmlOut, html, StandardCharsets.UTF_8);

            project.getLogger().lifecycle("[test-order] Dashboard written to: {}", htmlOut);
            project.getLogger().lifecycle("[test-order] Open in browser: file://{}",
                    htmlOut.toAbsolutePath());
            return htmlOut;
        } catch (IOException e) {
            throw new GradleException("Failed to generate test-order dashboard", e);
        }
    }

    /** Starts a local HTTP server serving the self-contained dashboard HTML. */
    private void serveDashboard(Project project, Path dashboardDir) {
        com.sun.net.httpserver.HttpServer server = null;
        try {
            server =
                    com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
            int port = server.getAddress().getPort();

            server.createContext("/", exchange -> {
                // The HTML is fully self-contained — serve index.html for every request.
                Path htmlFile = dashboardDir.resolve("index.html");
                if (!Files.isRegularFile(htmlFile)) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] body = Files.readAllBytes(htmlFile);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.getResponseHeaders().add("Cache-Control", "no-cache");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
            });

            server.start();
            project.getLogger().lifecycle("[test-order] Dashboard served at: http://localhost:{}",
                    port);
            project.getLogger().lifecycle("[test-order] Press Ctrl+C to stop.");

            try {
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop()
                                .isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(
                            URI.create("http://localhost:" + port + "/"));
                }
            } catch (Exception ignored) { }

            CountDownLatch latch = new CountDownLatch(1);
            com.sun.net.httpserver.HttpServer serverRef = server;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                serverRef.stop(1);
                latch.countDown();
            }));
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (IOException e) {
            throw new GradleException("Failed to start dashboard HTTP server", e);
        } finally {
            if (server != null) {
                server.stop(0);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    /** Resolves the main Java source root for the project. */
    static Path resolveMainSourceRoot(Project project) {
        SourceSetContainer sourceSets =
                project.getExtensions().findByType(SourceSetContainer.class);
        if (sourceSets != null) {
            SourceSet main = sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME);
            if (main != null) {
                for (File dir : main.getJava().getSrcDirs()) {
                    if (dir.isDirectory()) return dir.toPath();
                }
            }
        }
        // Fallback
        return project.getProjectDir().toPath().resolve("src/main/java");
    }

    /** Resolves the test Java source root for the project. */
    static Path resolveTestSourceRoot(Project project) {
        SourceSetContainer sourceSets =
                project.getExtensions().findByType(SourceSetContainer.class);
        if (sourceSets != null) {
            SourceSet test = sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME);
            if (test != null) {
                for (File dir : test.getJava().getSrcDirs()) {
                    if (dir.isDirectory()) return dir.toPath();
                }
            }
        }
        // Fallback
        return project.getProjectDir().toPath().resolve("src/test/java");
    }

    /** Resolves the main Kotlin source root for the project, falling back to src/main/kotlin. */
    static Path resolveKotlinSourceRoot(Project project) {
        SourceSetContainer sourceSets =
                project.getExtensions().findByType(SourceSetContainer.class);
        if (sourceSets != null) {
            SourceSet main = sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME);
            if (main != null) {
                for (File dir : main.getAllJava().getSrcDirs()) {
                    if (dir.isDirectory() && dir.getAbsolutePath().contains("kotlin")) {
                        return dir.toPath();
                    }
                }
            }
        }
        // Fallback
        return project.getProjectDir().toPath().resolve("src/main/kotlin");
    }

    /** Quotes a path string if it contains spaces (for javaagent arguments). */
    private static String quoteIfNeeded(String path) {
        return path.contains(" ") ? "\"" + path + "\"" : path;
    }

    private static boolean aggregateDependencyFiles(Project project, TestOrderExtension ext,
                                                    boolean failIfMissing) {
        Path depsDir = ext.getDepsDir().get().getAsFile().toPath();
        Path indexFile = ext.getIndexFile().get().getAsFile().toPath();
        if (!Files.isDirectory(depsDir)) {
            if (failIfMissing) {
                throw new GradleException("[test-order] No deps directory at " + depsDir
                        + ". Run tests in learn mode first.");
            }
            project.getLogger().info("[test-order] No deps directory at {}, skipping aggregation", depsDir);
            return false;
        }
        try {
            DependencyMap map = DependencyMap.aggregate(depsDir);
            PersistenceSupport.withFileLock(indexFile, () -> {
                map.save(indexFile);
                return null;
            });
            project.getLogger().lifecycle("[test-order] Aggregated deps → {}", indexFile);
            return true;
        } catch (IOException e) {
            throw new GradleException("Failed to aggregate dependency files", e);
        }
    }

    /**
     * Snapshots source and test source file hashes for future SINCE_LAST_RUN
     * change detection.
     */
    private static void snapshotHashes(Project project, TestOrderExtension ext) {
        snapshotSingleDir(resolveMainSourceRoot(project),
                ext.getHashFile().get().getAsFile().toPath(), "source", project);
        snapshotSingleDir(resolveTestSourceRoot(project),
                ext.getTestHashFile().get().getAsFile().toPath(), "test source", project);
    }

    private static void snapshotSingleDir(Path root, Path hashFile, String label,
                                           Project project) {
        try {
            boolean written = PersistenceSupport.withFileLock(hashFile, () ->
                    HashSnapshotSupport.snapshotDirectory(root, hashFile));
            if (written) {
                project.getLogger().info("[test-order] Saved {} hash snapshot: {}", label, hashFile);
            }
        } catch (IOException e) {
            project.getLogger().warn("[test-order] Failed to save {} hash snapshot: {}",
                    label, e.getMessage());
        }
    }

    /** Only sets a score system property when the user explicitly configured it. */
    private static void setScorePropertyIfPresent(Test testTask,
                                                   org.gradle.api.provider.Property<Integer> prop,
                                                   String scoreName) {
        if (prop.isPresent()) {
            testTask.systemProperty("testorder.score." + scoreName,
                    String.valueOf(prop.get()));
        }
    }

    /** Reads a Gradle project property or system property (Gradle property wins). */
    static String gradleOrSystemProperty(Project project, String key) {
        if (project.hasProperty(key)) {
            Object val = project.property(key);
            return val != null ? val.toString() : null;
        }
        return System.getProperty(key);
    }

    /** Shortens a FQCN for display: abbreviates all package segments except the last. */
    private static String shortenClassName(String fqcn) {
        int lastDot = fqcn.lastIndexOf('.');
        if (lastDot < 0) return fqcn;
        String pkg = fqcn.substring(0, lastDot);
        String cls = fqcn.substring(lastDot + 1);
        StringJoiner sj = new StringJoiner(".");
        for (String seg : pkg.split("\\.")) {
            sj.add(seg.substring(0, 1));
        }
        return sj + "." + cls;
    }

    private static void configureDerivedTestTask(Project project, TestOrderExtension ext, Test task) {
        SourceSetContainer sourceSets = project.getExtensions().findByType(SourceSetContainer.class);
        if (sourceSets != null) {
            SourceSet testSourceSet = sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME);
            if (testSourceSet != null) {
                task.setTestClassesDirs(testSourceSet.getOutput().getClassesDirs());
                task.setClasspath(testSourceSet.getRuntimeClasspath());
            }
        }
        task.useJUnitPlatform();
        task.jvmArgs("--enable-native-access=ALL-UNNAMED");
        task.shouldRunAfter(project.getTasks().named("test"));
        task.dependsOn(project.getTasks().named("testClasses"));
        task.systemProperty("testorder.state.path",
                ext.getStateFile().get().getAsFile().getAbsolutePath());
    }

    private static TestSelector.Selection selectTests(Project project, TestOrderExtension ext) {
        Path indexPath = ext.getIndexFile().get().getAsFile().toPath();
        if (!Files.exists(indexPath)) {
            throw new GradleException("[test-order] Index file not found: " + indexPath
                    + ". Run tests in learn mode first.");
        }
        try {
            DependencyMap depMap = DependencyMap.load(indexPath);
            Path statePath = ext.getStateFile().get().getAsFile().toPath();
            TestOrderState state = Files.exists(statePath)
                    ? TestOrderState.load(statePath) : new TestOrderState();
            Set<String> changed = detectChangedClassesForSelection(project, ext);
            Set<String> changedTests = detectChangedTestClassesForSelection(project, ext);
            TestOrderState.ScoringWeights weights = resolveWeights(state, ext);
            Long seed = ext.getSelectSeed().isPresent() ? ext.getSelectSeed().get() : null;
            TestSelector.Selection selection = new TestSelector(
                    depMap,
                    state,
                    changed,
                    changedTests,
                    weights,
                    new TestSelector.Config(resolveSelectTopN(project, ext), resolveSelectRandomM(project, ext), seed)
            ).select();
            TestSelector.writeTestList(selection.selected(), ext.getSelectedFile().get().getAsFile().toPath());
            TestSelector.writeTestList(selection.remaining(), ext.getRemainingFile().get().getAsFile().toPath());
            return selection;
        } catch (IOException e) {
            throw new GradleException("Failed to compute selected tests", e);
        }
    }

    private static TestOrderState.ScoringWeights resolveWeights(TestOrderState state, TestOrderExtension ext)
            throws IOException {
        String weightsFile = ext.getWeightsFile().get();
        if (weightsFile != null && !weightsFile.isBlank()) {
            Path weightsPath = Path.of(weightsFile);
            if (Files.exists(weightsPath)) {
                return TestOrderState.ScoringWeights.loadFromFile(weightsPath).weights();
            }
        }
        return state.weights() != null ? state.weights() : TestOrderState.ScoringWeights.DEFAULT;
    }

    private static Set<String> detectChangedClassesForSelection(Project project, TestOrderExtension ext) {
        ensureSupportedChangeMode(ext.getChangeMode().get());
        String explicitChanged = ext.getChangedClasses().get();
        String propChanged = gradleOrSystemProperty(project, "testorder.changed.classes");
        if (propChanged != null && !propChanged.isBlank()) {
            return ChangeDetectionSupport.parseExplicitClasses(propChanged);
        }
        if ("explicit".equalsIgnoreCase(ext.getChangeMode().get()) && explicitChanged != null && !explicitChanged.isBlank()) {
            return ChangeDetectionSupport.parseExplicitClasses(explicitChanged);
        }
        try {
            Path hashFile = ext.getHashFile().get().getAsFile().toPath();
            return ChangeDetectionSupport.detectChangedClasses(
                    ext.getChangeMode().get(),
                    project.getProjectDir().toPath(),
                    resolveMainSourceRoot(project),
                    hashFile,
                    explicitChanged.isEmpty() ? null : explicitChanged,
                    true);
        } catch (IOException e) {
            project.getLogger().debug("[test-order] Changed-class detection for select skipped: {}", e.getMessage());
            return Set.of();
        }
    }

    private static Set<String> detectChangedTestClassesForSelection(Project project, TestOrderExtension ext) {
        ensureSupportedChangeMode(ext.getChangeMode().get());
        if ("explicit".equalsIgnoreCase(ext.getChangeMode().get())) {
            return Set.of();
        }
        try {
            Path testHashFile = ext.getTestHashFile().get().getAsFile().toPath();
            return ChangeDetectionSupport.detectChangedTestClasses(
                    ext.getChangeMode().get(),
                    project.getProjectDir().toPath(),
                    resolveTestSourceRoot(project),
                    testHashFile,
                    true);
        } catch (IOException e) {
            project.getLogger().debug("[test-order] Changed-test detection for select skipped: {}", e.getMessage());
            return Set.of();
        }
    }

    private static void applySelectedTests(Test task, List<String> tests) {
        if (tests.isEmpty()) {
            task.onlyIf("no tests selected by test-order", t -> false);
            return;
        }
        task.filter(filter -> {
            filter.setFailOnNoMatchingTests(false);
            for (String testClass : tests) {
                filter.includeTestsMatching(testClass);
            }
        });
    }

    private static void ensureSupportedChangeMode(String changeMode) {
        try {
            ChangeDetectionSupport.normalizeMode(changeMode);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unknown changeMode: " + changeMode);
        }
    }

    @FunctionalInterface
    private interface LockAction<T> {
        T run() throws IOException;
    }

    private static <T> T withStateFileLock(Path statePath, LockAction<T> action) {
        try {
            return PersistenceSupport.withFileLock(statePath, action::run);
        } catch (IOException e) {
            throw new GradleException("Failed while holding state lock for " + statePath, e);
        }
    }

    private static int resolveSelectTopN(Project project, TestOrderExtension ext) {
        String override = gradleOrSystemProperty(project, "testorder.select.topN");
        if (override != null && !override.isBlank()) {
            try {
                return Integer.parseInt(override);
            } catch (NumberFormatException e) {
                throw new GradleException("[test-order] Invalid value for testorder.select.topN: '"
                        + override + "' (expected integer)");
            }
        }
        return ext.getSelectTopN().get();
    }

    private static int resolveSelectRandomM(Project project, TestOrderExtension ext) {
        String override = gradleOrSystemProperty(project, "testorder.select.randomM");
        if (override != null && !override.isBlank()) {
            try {
                return Integer.parseInt(override);
            } catch (NumberFormatException e) {
                throw new GradleException("[test-order] Invalid value for testorder.select.randomM: '"
                        + override + "' (expected integer)");
            }
        }
        return ext.getSelectRandomM().get();
    }
}
