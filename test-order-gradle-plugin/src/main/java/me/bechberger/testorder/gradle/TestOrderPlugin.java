package me.bechberger.testorder.gradle;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.PersistenceSupport;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestSelector;
import me.bechberger.testorder.changes.ChangeDetectionSupport;
import org.gradle.api.GradleException;
import me.bechberger.testorder.ops.AggregateOperation;
import me.bechberger.testorder.ops.CleanOperation;
import me.bechberger.testorder.ops.ChangeDetectionOps;
import me.bechberger.testorder.ops.DashboardServerOperation;
import me.bechberger.testorder.ops.DumpOperation;
import me.bechberger.testorder.ops.ExportJsonOperation;
import me.bechberger.testorder.ops.HashSnapshotOperation;
import me.bechberger.testorder.ops.JUnitPlatformValidator;
import me.bechberger.testorder.ops.ModeResolverOperation;
import me.bechberger.testorder.ops.OptimizeOperation;
import me.bechberger.testorder.ops.ParameterValidator;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.PluginLog;
import me.bechberger.testorder.ops.WeightResolverOperation;
import me.bechberger.testorder.ops.coverage.CoverageAnalysis;
import me.bechberger.testorder.ops.coverage.CoverageOperation;
import me.bechberger.testorder.ops.workflows.AutoWorkflow;
import me.bechberger.testorder.ops.workflows.DashboardWorkflow;
import me.bechberger.testorder.ops.workflows.OrderWorkflow;
import me.bechberger.testorder.ops.workflows.ShowMethodOrderWorkflow;
import me.bechberger.testorder.ops.workflows.ShowOrderWorkflow;
import me.bechberger.testorder.ops.workflows.ShowWorkflow;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.testing.Test;
import org.gradle.process.CommandLineArgumentProvider;

import me.bechberger.testorder.dashboard.DashboardResources;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

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
    static final String VERSION = "0.0.1-SNAPSHOT";

    /**
     * Stores active IndexCollectorServer instances keyed by task path.
     * Uses a static map to avoid calling task.getExtensions() at execution time,
     * which is incompatible with the Gradle configuration cache (Gradle 9+).
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, me.bechberger.testorder.IndexCollectorServer>
            COLLECTOR_REGISTRY = new java.util.concurrent.ConcurrentHashMap<>();

    /** Wraps a Gradle {@link org.gradle.api.logging.Logger} as a {@link PluginLog}. */
    private static PluginLog wrapLog(Project project) {
        return new PluginLog() {
            @Override
            public void info(String message) {
                project.getLogger().lifecycle(message);
            }

            @Override
            public void warn(String message) {
                project.getLogger().warn(message);
            }

            @Override
            public void debug(String message) {
                project.getLogger().debug(message);
            }
        };
    }

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
        if (project.getState().getExecuted()) {
            ext.validateConfiguration(project.getLogger());
            configureTestTasks(project, ext, agentConf,
                    learnModeConfigurator, orderModeConfigurator);
        } else {
            project.afterEvaluate(p -> {
                ext.validateConfiguration(p.getLogger());
                configureTestTasks(p, ext, agentConf,
                        learnModeConfigurator, orderModeConfigurator);
            });
        }

        // Multi-project: register aggregate task that collects deps from all subprojects
        if (!project.getSubprojects().isEmpty()) {
            project.getTasks().register("testOrderAggregateAll", task -> {
                task.setGroup("test-order");
                task.setDescription(
                        "Aggregate .deps files from all subprojects into a shared dependency index");
                // R7-14: depend on subproject aggregate tasks to ensure evaluation order
                for (Project sub : project.getSubprojects()) {
                    sub.getPlugins().withId("me.bechberger.test-order", plugin -> {
                        task.dependsOn(sub.getTasks().named("testOrderAggregate"));
                    });
                }
                task.doLast(t -> {
                    Path indexFile = ext.getIndexFile().get().getAsFile().toPath();
                    PluginLog plog = wrapLog(project);
                    boolean anyWritten = false;
                    for (Project sub : project.getSubprojects()) {
                        TestOrderExtension subExt = sub.getExtensions().findByType(TestOrderExtension.class);
                        if (subExt != null) {
                            Path subDeps = subExt.getDepsDir().get().getAsFile().toPath();
                            if (Files.isDirectory(subDeps)) {
                                try {
                                    // Always use incremental=true when combining multiple subprojects
                                    // so each subproject's data is merged (union) into the shared index
                                    // rather than replacing it. Without this, only the last subproject's
                                    // data survives.
                                    AggregateOperation.Result result =
                                            AggregateOperation.aggregate(subDeps, indexFile, plog, true);
                                    if (result.written()) anyWritten = true;
                                } catch (IOException e) {
                                    plog.warn("[test-order] Failed to aggregate from " + sub.getName()
                                            + ": " + e.getMessage());
                                }
                            }
                        }
                    }
                    // Also aggregate the root project's own deps
                    Path rootDeps = ext.getDepsDir().get().getAsFile().toPath();
                    if (Files.isDirectory(rootDeps)) {
                        try {
                            AggregateOperation.Result rootResult =
                                    AggregateOperation.aggregate(rootDeps, indexFile, plog, true);
                            if (rootResult.written()) anyWritten = true;
                        } catch (IOException e) {
                            plog.warn("[test-order] Failed to aggregate from root project: " + e.getMessage());
                        }
                    }
                    if (anyWritten) {
                        plog.info("[test-order] Multi-project aggregation complete: " + indexFile);
                    } else {
                        List<String> checkedProjects = new ArrayList<>();
                        for (Project sub : project.getSubprojects()) {
                            TestOrderExtension subExt = sub.getExtensions().findByType(TestOrderExtension.class);
                            if (subExt != null) {
                                checkedProjects.add(sub.getName());
                            }
                        }
                        plog.warn("[test-order] No .deps files found in any subproject. "
                                + "Checked: " + (checkedProjects.isEmpty() ? "(none with test-order applied)" : String.join(", ", checkedProjects))
                                + ". Run tests in learn mode first: ./gradlew test -Dtestorder.mode=learn");
                    }
                });
            });
        }
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
        // Annotations JAR (@AlwaysRun, @TestOrder) — compile+runtime so user tests can reference them
        ExternalModuleDependency annotationsDep = (ExternalModuleDependency) project.getDependencies().create(
                GROUP_ID + ":test-order-annotations:" + VERSION);
        annotationsDep.setTransitive(false);
        project.getDependencies().add("testImplementation", annotationsDep);

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
        if (project.getState().getExecuted()) {
            if (isTestNGOnTestClasspath(project)) {
                ExternalModuleDependency testngDep = (ExternalModuleDependency) project.getDependencies().create(
                        GROUP_ID + ":test-order-testng:" + VERSION);
                testngDep.setTransitive(false);
                project.getDependencies().add("testRuntimeOnly", testngDep);
                project.getLogger().lifecycle("[test-order] TestNG detected — adding test-order-testng support");
            }
        } else {
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

    static boolean isJUnit4OnTestClasspath(Project project) {
        return project.getConfigurations().stream()
                .filter(c -> c.getName().toLowerCase().contains("test"))
                .flatMap(c -> c.getDependencies().stream())
                .anyMatch(d -> "junit".equals(d.getGroup()) && "junit".equals(d.getName()));
    }

    static boolean isJUnit5OnTestClasspath(Project project) {
        return project.getConfigurations().stream()
                .filter(c -> c.getName().toLowerCase().contains("test"))
                .flatMap(c -> c.getDependencies().stream())
                .anyMatch(d -> "org.junit.jupiter".equals(d.getGroup()));
    }

    static boolean isJUnitVintageOnTestClasspath(Project project) {
        return project.getConfigurations().stream()
                .filter(c -> c.getName().toLowerCase().contains("test"))
                .flatMap(c -> c.getDependencies().stream())
                .anyMatch(d -> "org.junit.vintage".equals(d.getGroup())
                        && "junit-vintage-engine".equals(d.getName()));
    }

    private void warnJUnit4Unsupported(Project project) {
        if (isJUnit4OnTestClasspath(project)) {
            if (isJUnit5OnTestClasspath(project) || isJUnitVintageOnTestClasspath(project)) {
                // JUnit 4 tests running via Jupiter or via JUnit Vintage engine on the JUnit Platform
                project.getLogger().warn("[test-order] JUnit 4 dependency detected alongside JUnit 5. "
                        + "test-order only supports JUnit 5 (Jupiter) and TestNG — "
                        + "JUnit 4 tests will not be reordered or tracked. "
                        + "Consider migrating to JUnit 5 or using the JUnit Vintage engine.");
            } else {
                project.getLogger().warn("[test-order] JUnit 4 dependency detected but no JUnit 5 (Jupiter) found. "
                        + "test-order does NOT support JUnit 4 — tests will not be reordered or tracked. "
                        + "Please migrate to JUnit 5 or add the JUnit Vintage engine with "
                        + "junit-jupiter-engine on the test classpath.");
            }
        }
    }

    /**
     * M4/M12/M20/L6: Warns about conflicting JUnit Platform configurations that could
     * interfere with test-order's ordering in order mode.
     * Delegates to the shared {@link JUnitPlatformValidator} in core.
     */
    private void warnConflictingJUnitConfig(Project project, Test testTask) {
        Map<String, String> resolvedProps = toStringMap(testTask.getSystemProperties());
        JUnitPlatformValidator validator = new JUnitPlatformValidator(wrapLog(project));
        validator.warnListenerDeactivation(resolvedProps, null);
        validator.warnConflictingOrderers(resolvedProps, null);
        validator.checkJunitPlatformPropertiesFile(project.getProjectDir().toPath());
    }

    /**
     * Warns when class-level parallel execution is configured in learn mode,
     * which would corrupt dependency tracking (C1/M24 equivalent for Gradle).
     * Delegates to the shared {@link JUnitPlatformValidator} in core.
     */
    private void warnParallelInLearnMode(Project project, Test testTask) {
        Map<String, String> resolvedProps = toStringMap(testTask.getSystemProperties());
        JUnitPlatformValidator validator = new JUnitPlatformValidator(wrapLog(project));

        JUnitPlatformValidator.ParallelCheckResult result =
                validator.detectParallelExecution(resolvedProps, null);

        if (result.hasClassLevelParallel()) {
            project.getLogger().warn("[test-order] Class-level parallel execution "
                    + "(mode.classes.default=concurrent) is not supported in learn mode — "
                    + "it would corrupt dependency tracking.");
        }
        if (result.vintageParallel()) {
            project.getLogger().warn("[test-order] JUnit Vintage parallel execution is not "
                    + "supported in learn mode — it would corrupt dependency tracking.");
        }

        // G10: Check jvmArgs for parallel settings passed via -D flags
        String offendingArg = JUnitPlatformValidator.findParallelInJvmArgs(testTask.getJvmArgs());
        if (offendingArg != null) {
            project.getLogger().warn("[test-order] jvmArgs contains parallel class execution "
                    + "config that conflicts with learn mode dependency tracking: " + offendingArg);
        }

        // Check for junit-platform.properties in src/test/resources
        validator.checkJunitPlatformPropertiesParallel(project.getProjectDir().toPath());
    }

    // -----------------------------------------------------------------------
    // Spring detection
    // -----------------------------------------------------------------------

    private static boolean hasSpringTestDependency(Project project) {
        return project.getConfigurations().stream()
                .filter(c -> c.getName().toLowerCase().contains("test"))
                .flatMap(c -> c.getDependencies().stream())
                .anyMatch(d -> ("org.springframework.boot".equals(d.getGroup())
                        && "spring-boot-test".equals(d.getName()))
                        || ("org.springframework".equals(d.getGroup())
                        && "spring-test".equals(d.getName())));
    }

    // -----------------------------------------------------------------------
    // Test task configuration
    // -----------------------------------------------------------------------

    void configureTestTasks(Project project, TestOrderExtension ext,
                            Configuration agentConf,
                            LearnModeConfigurator learnModeConfigurator,
                            OrderModeConfigurator orderModeConfigurator) {
        // Check skip flag: -Dtestorder.skip=true, -Ptestorder.skip=true, or DSL testOrder { skip = true }
        boolean skipPlugin = ext.getSkip().get();
        String propSkip = gradleOrSystemProperty(project, "testorder.skip");
        if ("true".equalsIgnoreCase(propSkip)) {
            skipPlugin = true;
        }
        if (skipPlugin) {
            project.getLogger().lifecycle("[test-order] Plugin skipped (testorder.skip=true)");
            return;
        }

        // Warn about likely typos in testorder.* system/project properties
        warnUnknownProperties(project);

        // Validate parameters early (fail-fast with helpful messages)
        PluginLog plog = wrapLog(project);
        ParameterValidator validator = new ParameterValidator(plog);
        try {
            validator.validateChangeMode(ext.getChangeMode().get());
            validator.validateInstrumentationMode(ext.getInstrumentationMode().get());
            validator.validateExplicitModeRequirements(ext.getChangeMode().get(), ext.getChangedClasses().get());
            validator.validateSelectParameters(ext.getSelectTopN().get(), ext.getSelectRandomM().get());
            validator.validateNonNegative(ext.getAutoLearnRunThreshold().get(), "autoLearnRunThreshold");
            validator.validateNonNegative(ext.getAutoLearnDiffThreshold().get(), "autoLearnDiffThreshold");
        } catch (IllegalArgumentException e) {
            throw new GradleException(e.getMessage(), e);
        }

        warnJUnit4Unsupported(project);

        // Clean up stale temp files from interrupted writes (parity with Maven)
        Path baseDir = project.getRootProject().getProjectDir().toPath().resolve(".test-order");
        me.bechberger.testorder.PersistenceSupport.cleanupStaleTemps(baseDir);

        // Validate cache directory is writable (clear message vs cryptic AccessDeniedException)
        if (Files.exists(baseDir) && !Files.isWritable(baseDir)) {
            String perms = "";
            try {
                perms = java.nio.file.attribute.PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(baseDir));
            } catch (UnsupportedOperationException | IOException ignored) {
            }
            throw new GradleException("[test-order] Cannot write to cache directory: " + baseDir
                    + (perms.isEmpty() ? "" : " (permissions: " + perms + ")")
                    + ". Fix: chmod 755 " + baseDir);
        }

        String resolvedMode = orderModeConfigurator.resolveMode(ext, project);

        project.getTasks().withType(Test.class).configureEach(testTask -> {
            if (testTask.getName().startsWith("testOrder")) {
                return;
            }

            // Per-task mode override: -Dtestorder.mode.<taskName>=order (e.g.
            // -Dtestorder.mode.integrationTest=order) allows users to force specific
            // tasks into a different mode without affecting the default test task.
            String taskMode = gradleOrSystemProperty(project, "testorder.mode." + testTask.getName());
            String effectiveMode = (taskMode != null && !taskMode.isBlank()) ? taskMode : resolvedMode;

            if ("learn".equals(effectiveMode)) {
                learnModeConfigurator.configure(project, ext, testTask, agentConf);
            } else if ("order".equals(effectiveMode)) {
                orderModeConfigurator.configure(project, ext, testTask);
            } else if ("optimize".equals(effectiveMode)) {
                // Optimize mode: run tests in priority order, then evolve scoring weights
                // using a genetic algorithm (Jenetic) over the recorded run history
                orderModeConfigurator.configure(project, ext, testTask);
                testTask.doLast("testOrderOptimizeWeights", t -> {
                    Path statePath = ext.getStateFile().get().getAsFile().toPath();
                    if (!Files.exists(statePath)) {
                        project.getLogger().warn(
                                "[test-order] Optimize mode: state file not found at {} — skipping weight optimisation.",
                                statePath);
                        return;
                    }
                    try {
                        OptimizeOperation.run(statePath, msg -> project.getLogger().lifecycle(msg));
                    } catch (IOException e) {
                        project.getLogger().warn(
                                "[test-order] Failed to optimise weights: {}", e.getMessage());
                    }
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
        if (propMode != null && !propMode.isBlank()) mode = propMode;

        PluginContext pctx = buildPluginContext(project, ext);
        Path rootDir = project.getRootProject().getProjectDir().toPath();
        File indexFile = ext.getIndexFile().getAsFile().get();

        Runnable ciDownload = () -> {
            if (me.bechberger.testorder.ci.CiConfigParser.configExistsIn(rootDir)) {
                me.bechberger.testorder.ci.CiDepDownloadManager
                        .downloadIfConfigured(rootDir, indexFile.toPath())
                        .ifPresent(p -> project.getLogger().lifecycle(
                                "[test-order] CI index downloaded to {}", p));
            }
        };

        // Pass depsDir for auto-aggregation (same as Maven's PrepareMojo)
        Path depsDir = ext.getDepsDir().get().getAsFile().toPath();
        Path effectiveDepsDir = Files.isDirectory(depsDir) ? depsDir : null;

        ModeResolverOperation.ModeDecision decision = AutoWorkflow.resolveMode(
                pctx, mode, ciDownload, effectiveDepsDir);
        project.getLogger().info("[test-order] Mode decision: {} ({})",
                decision.effectiveMode(), decision.reason());
        return decision.effectiveMode();
    }

    // -----------------------------------------------------------------------
    // Learn mode
    // -----------------------------------------------------------------------

    void configureLearnMode(Project project, TestOrderExtension ext,
                            Test testTask, Configuration agentConf) {
        project.getLogger().info("[test-order] Configuring learn mode for task :{}",
                testTask.getName());

        // Learn mode writes new .deps files every run — disable UP-TO-DATE caching so
        // the doLast aggregation hook always executes even when no test sources changed.
        testTask.getOutputs().upToDateWhen(t -> false);

        // Warn about parallel config that corrupts dependency tracking in learn mode
        warnParallelInLearnMode(project, testTask);

        // Resolve effective instrumentation mode: CLI property overrides extension DSL
        String instrMode = ext.getInstrumentationMode().get();
        String propInstrMode = gradleOrSystemProperty(project, "testorder.instrumentation.mode");
        if (propInstrMode != null && !propInstrMode.isBlank()) {
            instrMode = propInstrMode;
        }

        // Resolve instrumentation strategy: offline (default) or online
        String instrStrategy = ext.getInstrumentation().get();
        String propInstrStrategy = gradleOrSystemProperty(project, "testorder.instrumentation");
        if (propInstrStrategy != null && !propInstrStrategy.isBlank()) {
            instrStrategy = propInstrStrategy;
        }

        // System properties for the forked test JVM
        testTask.systemProperty("testorder.learn", "true");
        testTask.systemProperty("testorder.instrumentation.mode", instrMode.toUpperCase());
        testTask.systemProperty("testorder.state.path",
                ext.getStateFile().get().getAsFile().getAbsolutePath());

        // TDD enforcement
        if (ext.getTdd().get()) {
            testTask.systemProperty("testorder.tdd", "true");
            testTask.systemProperty("junit.jupiter.extensions.autodetection.enabled", "true");
        }

        if ("offline".equalsIgnoreCase(instrStrategy)) {
            configureOfflineLearnMode(project, ext, testTask, instrMode.toUpperCase());
        } else {
            // Attach agent lazily via CommandLineArgumentProvider (configuration-cache safe)
            testTask.getJvmArgumentProviders().add(
                    new AgentArgumentProvider(project, ext, agentConf, instrMode.toUpperCase()));

            final boolean selectiveLearn = resolveSelectiveLearn(project, ext);

            // Start IndexCollectorServer for socket-based dep collection (agent mode).
            // Also writes the uncertain-classes file when selectiveLearn=true.
            testTask.doFirst("testOrderStartCollector", t -> {
                try {
                    // Stop any stale collector from a previous run in this daemon
                    // (can happen when tests fail and doLast doesn't execute).
                    me.bechberger.testorder.IndexCollectorServer stale = COLLECTOR_REGISTRY.remove(testTask.getPath());
                    if (stale != null) {
                        stale.stopAndMerge();
                    }

                    // Set compression level for IndexCollectorServer merge
                    String compressionLevel = ext.getCompression().getOrElse("fast");
                    System.setProperty("testorder.compression", compressionLevel);

                    java.nio.file.Path indexFilePath = ext.getIndexFile().get().getAsFile().toPath();
                    me.bechberger.testorder.IndexCollectorServer collector =
                            new me.bechberger.testorder.IndexCollectorServer(indexFilePath);
                    testTask.systemProperty("testorder.collector.port",
                            String.valueOf(collector.getPort()));
                    COLLECTOR_REGISTRY.put(testTask.getPath(), collector);
                    project.getLogger().lifecycle("[test-order] IndexCollectorServer started on port {}",
                            collector.getPort());
                } catch (java.io.IOException ex) {
                    project.getLogger().warn("[test-order] Failed to start IndexCollectorServer: {}",
                            ex.getMessage());
                }

                // Selective learn: compute and persist the uncertain-classes set so the
                // online agent can skip classes that the static graph proves are unaffected.
                if (selectiveLearn) {
                    java.nio.file.Path idxPath = ext.getIndexFile().get().getAsFile().toPath();
                    boolean indexExists = java.nio.file.Files.exists(idxPath);
                    if (indexExists) {
                        try {
                            SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
                            SourceSet mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
                            Path classesDir = mainSourceSet.getOutput().getClassesDirs().getFiles().stream()
                                    .map(File::toPath)
                                    .filter(Files::isDirectory)
                                    .findFirst()
                                    .orElse(null);
                            if (classesDir != null) {
                                Path repoRoot = project.getRootProject().getProjectDir().toPath();
                                java.nio.file.Path hashFilePath = ext.getHashFile().get().getAsFile().toPath();
                                me.bechberger.testorder.changes.ChangeDetector.Mode changeDetectorMode;
                                try {
                                    changeDetectorMode = me.bechberger.testorder.changes.ChangeDetectionSupport
                                            .resolveMode(resolveChangeMode(project, ext), hashFilePath);
                                } catch (java.io.IOException e) {
                                    changeDetectorMode = me.bechberger.testorder.changes.ChangeDetector.Mode.UNCOMMITTED;
                                }
                                me.bechberger.testorder.changes.SelectiveLearnSupport.StaticAnalysisData saData =
                                        me.bechberger.testorder.changes.SelectiveLearnSupport
                                                .computeStaticAnalysisData(repoRoot, classesDir, changeDetectorMode);
                                java.util.Set<String> uncertainClasses = saData != null ? saData.uncertainClasses() : null;
                                if (uncertainClasses != null) {
                                    java.nio.file.Path uncertainFile = ext.getDepsDir().get().getAsFile().toPath()
                                            .resolve("uncertain-classes.txt");
                                    me.bechberger.testorder.changes.UncertainClassesStore.save(uncertainFile, uncertainClasses);
                                    me.bechberger.testorder.changes.StaticAnalysisDataStore.save(
                                            me.bechberger.testorder.changes.StaticAnalysisDataStore.sidecarPath(uncertainFile), saData);
                                    testTask.systemProperty("testorder.learn.uncertainClassesFile",
                                            uncertainFile.toAbsolutePath().toString());
                                    if (!uncertainClasses.isEmpty()) {
                                        project.getLogger().lifecycle("[test-order] Selective learn: instrumenting {} uncertain class(es)",
                                                uncertainClasses.size());
                                    } else {
                                        project.getLogger().lifecycle("[test-order] Selective learn: no source changes detected; agent will instrument nothing");
                                    }
                                }
                            }
                        } catch (java.io.IOException ex) {
                            project.getLogger().warn("[test-order] Selective learn: failed to compute uncertain classes — using full instrumentation: {}",
                                    ex.getMessage());
                        }
                    } else {
                        project.getLogger().lifecycle("[test-order] Selective learn: no existing index — using full instrumentation for initial run");
                    }
                }
            });
        }

        // Snapshot source and test hashes before tests run, so that future
        // SINCE_LAST_RUN change detection has a baseline to compare against.
        testTask.doFirst("snapshotHashes", t -> {
            snapshotHashes(project, ext);
        });
        testTask.doLast("aggregateDeps", t -> {
            // Stop IndexCollectorServer if running (agent mode)
            me.bechberger.testorder.IndexCollectorServer collector =
                    COLLECTOR_REGISTRY.remove(testTask.getPath());
            if (collector != null) {
                int merged = collector.stopAndMerge();
                if (merged > 0) {
                    project.getLogger().lifecycle("[test-order] IndexCollectorServer merged {} test classes via socket",
                            merged);
                }
            }
            aggregateDependencyFiles(project, ext, false);
        });
    }

    /**
     * Configures offline learn mode: instruments classes at build time (no agent),
     * then passes the mapping file path to the test JVM via system properties.
     */
    private void configureOfflineLearnMode(Project project, TestOrderExtension ext,
                                           Test testTask, String instrMode) {
        project.getLogger().lifecycle("[test-order] Offline learn mode: no agent, using build-time instrumentation");

        // Resolve source packages
        String includePackages = ext.getIncludePackages().get();
        if (includePackages.isEmpty()) {
            Path sourceRoot = resolveMainSourceRoot(project);
            includePackages = PackageDetector.resolveIncludePackages(
                    null, ext.getFilterByGroupId().get(),
                    String.valueOf(project.getGroup()), sourceRoot, project.getLogger());
        }
        final String effectivePackages = includePackages;
        final boolean selectiveLearn = resolveSelectiveLearn(project, ext);

        // doFirst: instrument classes before tests run
        testTask.doFirst("testOrderOfflineInstrument", t -> {
            SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
            SourceSet mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            Path classesDir = mainSourceSet.getOutput().getClassesDirs().getFiles().stream()
                    .map(File::toPath)
                    .filter(Files::isDirectory)
                    .findFirst()
                    .orElse(null);
            project.getLogger().debug("[test-order] classesDir for {}: {} (all: {})",
                    project.getPath(), classesDir,
                    mainSourceSet.getOutput().getClassesDirs().getFiles());

            if (classesDir == null) {
                project.getLogger().warn("[test-order] No compiled classes directory found — skipping offline instrumentation");
                return;
            }

            List<String> includes = effectivePackages.isBlank()
                    ? List.of() : List.of(effectivePackages.split(","));
            me.bechberger.testorder.agent.Agent.InstrumentationMode mode =
                    me.bechberger.testorder.agent.Agent.InstrumentationMode.fromString(instrMode);

            // Selective learn: compute uncertain classes if enabled and index exists
            java.util.Set<String> uncertainClasses = null;
            me.bechberger.testorder.changes.SelectiveLearnSupport.StaticAnalysisData saData = null;
            if (selectiveLearn) {
                java.nio.file.Path idxPath = ext.getIndexFile().get().getAsFile().toPath();
                boolean indexExists = java.nio.file.Files.exists(idxPath);
                if (indexExists) {
                    Path repoRoot = project.getRootProject().getProjectDir().toPath();
                    java.nio.file.Path hashFilePath = ext.getHashFile().get().getAsFile().toPath();
                    me.bechberger.testorder.changes.ChangeDetector.Mode changeDetectorMode;
                    try {
                        changeDetectorMode = me.bechberger.testorder.changes.ChangeDetectionSupport
                                .resolveMode(resolveChangeMode(project, ext), hashFilePath);
                    } catch (java.io.IOException e) {
                        changeDetectorMode = me.bechberger.testorder.changes.ChangeDetector.Mode.UNCOMMITTED;
                    }
                    saData = me.bechberger.testorder.changes.SelectiveLearnSupport
                            .computeStaticAnalysisData(repoRoot, classesDir, changeDetectorMode);
                    uncertainClasses = saData != null ? saData.uncertainClasses() : null;
                    if (uncertainClasses != null && !uncertainClasses.isEmpty()) {
                        project.getLogger().lifecycle("[test-order] Selective instrument: {} uncertain class(es) will be instrumented",
                                uncertainClasses.size());
                    } else if (uncertainClasses != null) {
                        // empty set — no structural changes; skip offline instrumentation entirely
                        project.getLogger().lifecycle("[test-order] Selective instrument: no source changes detected; skipping instrumentation");
                        return;
                    }
                } else {
                    project.getLogger().lifecycle("[test-order] Selective instrument: no existing index — using full instrumentation for initial run");
                }
            }

            // Write uncertain-classes.txt for dashboard Static Analysis tab
            if (uncertainClasses != null) {
                try {
                    java.nio.file.Path depsPath = ext.getDepsDir().get().getAsFile().toPath();
                    java.nio.file.Path uncertainFile = depsPath.resolve("uncertain-classes.txt");
                    me.bechberger.testorder.changes.UncertainClassesStore.save(uncertainFile, uncertainClasses);
                    if (saData != null) {
                        me.bechberger.testorder.changes.StaticAnalysisDataStore.save(
                                me.bechberger.testorder.changes.StaticAnalysisDataStore.sidecarPath(uncertainFile), saData);
                    }
                } catch (java.io.IOException e2) {
                    project.getLogger().debug("[test-order] Could not write uncertain-classes file: " + e2.getMessage());
                }
            }

            me.bechberger.testorder.agent.OfflineInstrumentor instrumentor =
                    new me.bechberger.testorder.agent.OfflineInstrumentor(mode, includes, List.of(), uncertainClasses);
            try {
                Path buildDir = project.getLayout().getBuildDirectory().get().getAsFile().toPath();
                Path backupDir = buildDir.resolve(".test-order").resolve("classes-backup");
                me.bechberger.testorder.agent.runtime.ClassIdMapping mapping = instrumentor.instrument(classesDir,
                        backupDir);
                Path mappingDir = buildDir.resolve(".test-order");
                Path mappingFile = mappingDir.resolve("class-id-map.bin");
                mapping.save(mappingFile);
                project.getLogger().lifecycle("[test-order] Instrumented {} classes (skipped {}), mapping: {}",
                        instrumentor.getTransformedCount(), instrumentor.getSkippedCount(), mappingFile);

                // Set system properties for the forked test JVM
                testTask.systemProperty("testorder.offline.mapping", mappingFile.toAbsolutePath().toString());
                testTask.systemProperty("testorder.offline.output",
                        ext.getDepsDir().get().getAsFile().getAbsolutePath());
                testTask.systemProperty("testorder.offline.indexFile",
                        ext.getIndexFile().get().getAsFile().getAbsolutePath());
                testTask.systemProperty("testorder.offline.backupDir", backupDir.toAbsolutePath().toString());

                // Start IndexCollectorServer for socket-based dep collection
                try {
                    // Stop any stale collector from a previous daemon run
                    me.bechberger.testorder.IndexCollectorServer stale = COLLECTOR_REGISTRY.remove(testTask.getPath());
                    if (stale != null) {
                        stale.stopAndMerge();
                    }

                    // Set compression level for IndexCollectorServer merge
                    String compressionLevel = ext.getCompression().getOrElse("fast");
                    System.setProperty("testorder.compression", compressionLevel);

                    java.nio.file.Path indexFilePath = ext.getIndexFile().get().getAsFile().toPath();
                    me.bechberger.testorder.IndexCollectorServer collector =
                            new me.bechberger.testorder.IndexCollectorServer(indexFilePath, mappingFile);
                    testTask.systemProperty("testorder.collector.port",
                            String.valueOf(collector.getPort()));
                    COLLECTOR_REGISTRY.put(testTask.getPath(), collector);
                    project.getLogger().lifecycle("[test-order] IndexCollectorServer started on port {}",
                            collector.getPort());
                } catch (IOException ex) {
                    project.getLogger().warn("[test-order] Failed to start IndexCollectorServer: {}",
                            ex.getMessage());
                }
            } catch (IOException e) {
                throw new GradleException("[test-order] Offline instrumentation failed: " + e.getMessage(), e);
            }
        });

        // doLast: restore original classes after tests complete
        testTask.doLast("testOrderOfflineRestore", t -> {
            Path buildDir = project.getLayout().getBuildDirectory().get().getAsFile().toPath();
            Path backupDir = buildDir.resolve(".test-order").resolve("classes-backup");
            try {
                if (me.bechberger.testorder.agent.OfflineInstrumentor.restore(backupDir)) {
                    project.getLogger().lifecycle("[test-order] Restored original classes (instrumentation reverted).");
                }
            } catch (IOException e) {
                project.getLogger().warn("[test-order] Failed to restore classes: {}", e.getMessage());
            }
            // Stop IndexCollectorServer and merge (if it was started)
            me.bechberger.testorder.IndexCollectorServer collector =
                    COLLECTOR_REGISTRY.remove(testTask.getPath());
            if (collector != null) {
                int merged = collector.stopAndMerge();
                if (merged > 0) {
                    project.getLogger().lifecycle("[test-order] IndexCollectorServer merged {} test classes via socket",
                            merged);
                }
            }
        });

        // Add runtime jar to test classpath (UsageStore accessible without agent).
        // Use a per-task name so that multiple Test tasks in the same project each
        // get their own configuration — avoids "cannot mutate after resolved" errors
        // when a second task's configureEach fires after the first config was resolved.
        String runtimeConfName = "testOrderOfflineRuntime_" + testTask.getName();
        Configuration runtimeConf = createHiddenConfiguration(project, runtimeConfName, false);
        project.getDependencies().add(runtimeConf.getName(),
                GROUP_ID + ":test-order-agent:" + VERSION);
        testTask.setClasspath(testTask.getClasspath().plus(runtimeConf));
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
            File agentJar;
            try {
                agentJar = agentClasspath.getSingleFile();
            } catch (IllegalStateException e) {
                throw new GradleException(
                        "[test-order] Agent JAR not found. Ensure the test-order-agent artifact "
                        + "(me.bechberger:test-order-agent:" + VERSION + ") is available in your repositories. "
                        + "Check that your repository declarations include the artifact source "
                        + "(Maven Central, local Maven repo, or custom repository). "
                        + "Original error: " + e.getMessage(), e);
            }

            String agentArgs = me.bechberger.testorder.AgentArgsBuilder.buildArgs(
                    Path.of(depsDirPath), instrumentationMode,
                    Path.of(indexFilePath), includePackages, verboseFile, true,
                    me.bechberger.testorder.AgentArgsBuilder.preExtractRuntimeJar(
                            agentJar.toPath(), agentJar.toPath().getParent()));

            return List.of(
                    "-javaagent:" + quoteIfNeeded(agentJar.getAbsolutePath()) + "=" + agentArgs);
        }
    }

    // -----------------------------------------------------------------------
    // Order mode
    // -----------------------------------------------------------------------

    void configureOrderMode(Project project, TestOrderExtension ext, Test testTask) {
        File indexFile = ext.getIndexFile().getAsFile().get();
        if (!indexFile.exists()) {
            // Graceful degradation: skip ordering when no index exists yet.
            // Unlike requireIndex() which throws, order mode during the regular 'test' task
            // should not fail — it just skips ordering so users can still run tests normally.
            if (!project.getExtensions().getExtraProperties().has("testorder.indexMissingWarned")) {
                project.getExtensions().getExtraProperties().set("testorder.indexMissingWarned", Boolean.TRUE);
                project.getLogger().warn("[test-order] Index file {} not found — skipping order mode. "
                        + "Run with -Dtestorder.mode=learn first, or run: ./gradlew testOrderDiagnose", indexFile);
            }
            return;
        }
        project.getLogger().info("[test-order] Configuring order mode for task :{}",
                testTask.getName());

        // M4/M12/M20/L6: Warn about conflicting JUnit Platform configurations
        warnConflictingJUnitConfig(project, testTask);

        // Suggest springContextGrouping for Spring projects (parity with Maven)
        if (!ext.getSpringContextGrouping().get() && hasSpringTestDependency(project)) {
            if (!project.getExtensions().getExtraProperties().has("testorder.springHintShown")) {
                project.getExtensions().getExtraProperties().set("testorder.springHintShown", Boolean.TRUE);
                project.getLogger().lifecycle("[test-order] Spring test dependency detected. "
                        + "Consider enabling testOrder { springContextGrouping = true } "
                        + "to reduce Spring context reloads.");
            }
        }

        // Inject PriorityClassOrderer as the JUnit 5 global class orderer
        testTask.systemProperty("junit.jupiter.testclass.order.default",
                "me.bechberger.testorder.junit.PriorityClassOrderer");

        // TDD enforcement
        if (ext.getTdd().get()) {
            testTask.systemProperty("testorder.tdd", "true");
            testTask.systemProperty("junit.jupiter.extensions.autodetection.enabled", "true");
        }

        // Forward debug flag so PriorityClassOrderer can emit verbose scoring output
        String debugFlag = gradleOrSystemProperty(project, "testorder.debug");
        if ("true".equalsIgnoreCase(debugFlag)) {
            testTask.systemProperty("testorder.debug", "true");
        }

        // Set well-known properties directly at configuration time so tests and
        // tools that inspect task properties without executing the task see them.
        testTask.systemProperty("testorder.index.path", indexFile.getAbsolutePath());
        String changeMode = ext.getChangeMode().get();
        String propChangeMode = gradleOrSystemProperty(project, "testorder.changeMode");
        if (propChangeMode != null && !propChangeMode.isBlank()) {
            changeMode = propChangeMode;
        }
        if (changeMode != null && !changeMode.isBlank()) {
            testTask.systemProperty("testorder.changeMode", changeMode);
        }
        if (ext.getMethodOrderingEnabled().get()) {
            testTask.systemProperty("testorder.methodOrder.enabled", "true");
            testTask.systemProperty("junit.jupiter.testmethod.order.default",
                    "me.bechberger.testorder.junit.PriorityMethodOrderer");
        }
        // Score overrides: only inject when the user explicitly configured them
        if (ext.getScoreNewTest().isPresent()) {
            testTask.systemProperty("testorder.score.newTest",
                    String.valueOf(ext.getScoreNewTest().get()));
        }
        if (ext.getScoreChangedTest().isPresent()) {
            testTask.systemProperty("testorder.score.changedTest",
                    String.valueOf(ext.getScoreChangedTest().get()));
        }
        if (ext.getScoreMaxFailure().isPresent()) {
            testTask.systemProperty("testorder.score.maxFailure",
                    String.valueOf(ext.getScoreMaxFailure().get()));
        }
        if (ext.getScoreSpeed().isPresent()) {
            testTask.systemProperty("testorder.score.speed",
                    String.valueOf(ext.getScoreSpeed().get()));
        }
        if (ext.getScoreSpeedPenalty().isPresent()) {
            testTask.systemProperty("testorder.score.speedPenalty",
                    String.valueOf(ext.getScoreSpeedPenalty().get()));
        }
        if (ext.getScoreDepOverlap().isPresent()) {
            testTask.systemProperty("testorder.score.depOverlap",
                    String.valueOf(ext.getScoreDepOverlap().get()));
        }
        if (ext.getScoreChangeComplexity().isPresent()) {
            testTask.systemProperty("testorder.score.changeComplexity",
                    String.valueOf(ext.getScoreChangeComplexity().get()));
        }
        if (ext.getScoreStaticFieldBonus().isPresent()) {
            testTask.systemProperty("testorder.score.staticFieldBonus",
                    String.valueOf(ext.getScoreStaticFieldBonus().get()));
        }
        if (ext.getScoreCoverageBonus().isPresent()) {
            testTask.systemProperty("testorder.score.coverageBonus",
                    String.valueOf(ext.getScoreCoverageBonus().get()));
        }
        if (ext.getScoreKillRateBonus().isPresent()) {
            testTask.systemProperty("testorder.score.killRateBonus",
                    String.valueOf(ext.getScoreKillRateBonus().get()));
        }
        String weightsFile = ext.getWeightsFile().get();
        if (weightsFile != null && !weightsFile.isBlank()) {
            testTask.systemProperty("testorder.weights.file",
                    Path.of(weightsFile).toAbsolutePath().toString());
        }

        // All config (including change detection) runs at execution time
        testTask.doFirst("configureOrderViaWorkflow", t -> {
            // Warn if topN is set in pure order mode — it only applies to select tasks
            String topNProp = gradleOrSystemProperty(project, "testorder.affected.topN");
            if (topNProp != null) {
                project.getLogger().warn("[test-order] testorder.affected.topN is ignored in order mode "
                        + "(all tests run, just re-ordered). "
                        + "Did you mean: ./gradlew testOrderAffected -Dtestorder.affected.topN=" + topNProp + "?");
            }

            PluginContext pctx = buildPluginContext(project, ext);

            // Validate explicit changed classes against the index
            if ("explicit".equalsIgnoreCase(ext.getChangeMode().get())) {
                try {
                    DependencyMap depMap = DependencyMap.load(pctx.indexFile());
                    Set<String> changed = pctx.changedClasses() != null
                            ? splitClasses(pctx.changedClasses())
                            : Set.of();
                    new ParameterValidator(wrapLog(project))
                            .warnUnknownChangedClasses(changed, depMap, ext.getChangeMode().get());
                } catch (IllegalArgumentException e) {
                    throw new GradleException(e.getMessage(), e);
                } catch (IOException e) {
                    project.getLogger().warn("[test-order] Could not validate changed classes: {}", e.getMessage());
                }
            }

            TestOrderState state;
            try {
                Path statePath = ext.getStateFile().get().getAsFile().toPath();
                state = Files.exists(statePath) ? TestOrderState.load(statePath) : new TestOrderState();
            } catch (IOException e) {
                Path statePath = ext.getStateFile().get().getAsFile().toPath();
                throw new GradleException("[test-order] Failed to load state file at " + statePath
                        + ": " + e.getMessage()
                        + ". If the file is corrupt, delete it and re-run tests: rm " + statePath, e);
            }

            // Auto-compact: rebuild index from .deps periodically to remove stale entries
            int autoCompactEvery = ext.getAutoCompactEvery().get();
            if (autoCompactEvery > 0 && state.runsSinceLearn() > 0
                    && state.runsSinceLearn() % autoCompactEvery == 0) {
                Path depsDir = ext.getDepsDir().get().getAsFile().toPath();
                if (Files.isDirectory(depsDir)) {
                    try {
                        long depsCount;
                        try (var stream = Files.list(depsDir)) {
                            depsCount = stream.filter(p -> p.toString().endsWith(".deps")).count();
                        }
                        if (depsCount > 0) {
                            project.getLogger().lifecycle(
                                    "[test-order] Auto-compacting index (every {} runs)", autoCompactEvery);
                            me.bechberger.testorder.ops.IndexCompactionOperation.compact(
                                    depsDir, ext.getIndexFile().get().getAsFile().toPath(),
                                    wrapLog(project));
                        }
                    } catch (IOException e) {
                        project.getLogger().warn("[test-order] Auto-compact failed: {}", e.getMessage());
                    }
                }
            }

            OrderWorkflow.OrderSetupResult result;
            try {
                result = OrderWorkflow.setup(pctx, state);
            } catch (IOException e) {
                throw new GradleException("Failed to set up test ordering", e);
            }

            // Save state if it was modified (e.g., runsSinceLearn reset by fingerprint change) (R7-8)
            try {
                Path statePath = ext.getStateFile().get().getAsFile().toPath();
                state.save(statePath);
            } catch (IOException e) {
                project.getLogger().warn("[test-order] Could not save state: {}", e.getMessage());
            }

            // Snapshot source and test hashes so SINCE_LAST_RUN change detection
            // has a baseline to compare against on the next run.
            snapshotHashes(project, ext);

            // Apply config map as system properties on the test task
            for (var entry : result.configMap().entrySet()) {
                ((Test) t).systemProperty(entry.getKey(), entry.getValue());
            }
        });
    }

    // -----------------------------------------------------------------------
    // Task registration
    // -----------------------------------------------------------------------

    void registerTasks(Project project, TestOrderExtension ext,
                       Configuration agentConf) {

        // All test-order tasks respect the skip flag
        project.getTasks().configureEach(task -> {
            if (task.getName().startsWith("testOrder")) {
                task.onlyIf("testorder.skip is not set", t -> !shouldSkip(project, ext));
            }
        });

        project.getTasks().register("testOrderDownload", task -> {
            task.setGroup("test-order");
            task.setDescription("Download the dependency index from CI (GitHub Actions, GitLab CI, or HTTP)");
            task.doLast(t -> {
                Path projectDir = project.getRootProject().getProjectDir().toPath();
                Path indexFile = ext.getIndexFile().get().getAsFile().toPath();
                Path configPath = projectDir.resolve(".test-order/download-config.yml");
                if (!Files.exists(configPath)) {
                    throw new GradleException(
                            "[test-order] CI download config not found at " + configPath
                            + ". Create .test-order/download-config.yml with your CI provider settings. "
                            + "See docs/CI_REFERENCE.md for configuration examples.");
                }
                java.util.Optional<Path> result =
                        me.bechberger.testorder.ci.CiDepDownloadManager
                                .downloadIfConfigured(projectDir, indexFile);
                if (result.isPresent()) {
                    project.getLogger().lifecycle("[test-order] CI index written to {}", result.get());
                } else {
                    throw new GradleException(
                            "[test-order] CI download failed. Config exists at " + configPath
                            + " but download did not produce a result. "
                            + "Check that required tokens/credentials are set as environment variables "
                            + "and that the source URL is accessible.");
                }
            });
        });

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
                String outputFile = ext.getDumpOutputFile().get();
                String propOutput = gradleOrSystemProperty(project, "testorder.dump.output");
                if (propOutput != null && !propOutput.isBlank()) {
                    outputFile = propOutput;
                }
                try {
                    if (outputFile != null && !outputFile.isBlank()) {
                        DumpOperation.dump(indexFile, Path.of(outputFile), wrapLog(project));
                    } else {
                        DumpOperation.dump(indexFile, System.out, wrapLog(project));
                    }
                } catch (IOException e) {
                    throw new GradleException("Failed to dump dependency index", e);
                }
            });
        });

        project.getTasks().register("testOrderExportJson", task -> {
            task.setGroup("test-order");
            task.setDescription("Export the binary dependency index (and history) as JSON");
            task.doLast(t -> {
                Path indexFile = ext.getIndexFile().get().getAsFile().toPath();
                if (!Files.exists(indexFile)) {
                    throw new GradleException("[test-order] Index file not found: " + indexFile
                            + ". Run tests in learn mode first.");
                }
                Path statePath = ext.getStateFile().get().getAsFile().toPath();
                if (!Files.exists(statePath)) {
                    statePath = null;
                }
                String outputFile = gradleOrSystemProperty(project, "testorder.exportJson.output");
                try {
                    if (outputFile != null && !outputFile.isBlank()) {
                        ExportJsonOperation.export(indexFile, statePath, Path.of(outputFile), wrapLog(project));
                    } else {
                        ExportJsonOperation.export(indexFile, statePath, System.out, wrapLog(project));
                    }
                } catch (IOException e) {
                    throw new GradleException("Failed to export dependency index as JSON", e);
                }
            });
        });

        project.getTasks().register("testOrderAnalyzeMutations", task -> {
            task.setGroup("test-order");
            task.setDescription(
                    "Run PIT mutation testing scoped to indexed test classes and update kill rates in state file");
            task.doLast(t -> {
                Path indexFile = ext.getIndexFile().get().getAsFile().toPath();
                if (!Files.exists(indexFile)) {
                    throw new GradleException("[test-order] Index file not found: " + indexFile
                            + ". Run tests in learn mode first.");
                }
                Path stateFile = ext.getStateFile().get().getAsFile().toPath();
                String outputFileStr = gradleOrSystemProperty(project, "testorder.mutations.outputFile");
                String timeBudgetStr = gradleOrSystemProperty(project, "testorder.mutations.timeBudget");
                String targetClassesStr = gradleOrSystemProperty(project, "testorder.mutations.targetClasses");
                int timeBudget = parseIntOrDefault(timeBudgetStr, 0, "testorder.mutations.timeBudget");
                Path outputPath = outputFileStr != null && !outputFileStr.isBlank()
                        ? Path.of(outputFileStr)
                        : project.getLayout().getBuildDirectory().getAsFile().get().toPath()
                                .resolve("test-mutation-results.json");
                Path projectRoot = project.getProjectDir().toPath().toAbsolutePath();
                try {
                    me.bechberger.testorder.ops.MutationAnalysisOperation
                            .run(new me.bechberger.testorder.ops.MutationAnalysisOperation.Config(indexFile, stateFile,
                                    outputPath, projectRoot, targetClassesStr, timeBudget, wrapLog(project)));
                } catch (IOException e) {
                    throw new GradleException("Mutation analysis failed: " + e.getMessage(), e);
                }
            });
        });

        project.getTasks().register("testOrderShow", task -> {
            task.setGroup("test-order");
            task.setDescription("Unified view of predicted test order, method order, and ML health"
                    + " (replaces testOrderShowOrder / testOrderShowMethodOrder)");
            task.doLast(t -> {
                String classes = gradleOrSystemProperty(project, "testorder.show.classes");
                String methods = gradleOrSystemProperty(project, "testorder.show.methods");
                String ml = gradleOrSystemProperty(project, "testorder.show.ml");
                boolean all = Boolean.parseBoolean(Optional
                        .ofNullable(gradleOrSystemProperty(project, "testorder.show.all"))
                        .orElse("false"));
                boolean explain = Boolean.parseBoolean(Optional
                        .ofNullable(gradleOrSystemProperty(project, "testorder.show.explain"))
                        .orElse("false"));
                boolean fullNames = Boolean.parseBoolean(Optional
                        .ofNullable(gradleOrSystemProperty(project, "testorder.show.fullNames"))
                        .orElse("false"));
                String format = Optional.ofNullable(gradleOrSystemProperty(project, "testorder.show.format"))
                        .orElse("text");
                String filter = gradleOrSystemProperty(project, "testorder.show.filter");
                String topNStr = gradleOrSystemProperty(project, "testorder.show.topN");
                String randomMStr = gradleOrSystemProperty(project, "testorder.show.randomM");
                String seedStr = gradleOrSystemProperty(project, "testorder.show.seed");
                int topN = parseIntOrDefault(topNStr, -1, "testorder.show.topN");
                int randomM = parseIntOrDefault(randomMStr, -1, "testorder.show.randomM");
                Long seed = parseLongOrNull(seedStr, "testorder.show.seed");
                runShowReport(project, ext, classes, methods, ml, all, explain, fullNames,
                        format, filter, topN, randomM, seed);
            });
        });

        project.getTasks().register("testOrderShowOrder", task -> {
            task.setGroup("test-order");
            task.setDescription(
                    "Display the predicted test execution order without running tests"
                            + " (set -Dtestorder.showOrder.explain=true for detailed breakdown)");
            task.doLast(t -> {
                boolean explain = Boolean.parseBoolean(Optional
                        .ofNullable(gradleOrSystemProperty(project, "testorder.showOrder.explain"))
                        .orElse("false"));
                boolean fullNames = Boolean.parseBoolean(Optional
                        .ofNullable(gradleOrSystemProperty(project, "testorder.showOrder.fullNames"))
                        .orElse("false"));
                runShowOrderReport(project, ext, explain, fullNames);
            });
        });

        project.getTasks().register("testOrderExplainOrder", task -> {
            task.setGroup("test-order");
            task.setDescription("Display detailed per-test score explanations");
            task.doLast(t -> runShowOrderReport(project, ext, true, false));
        });

        project.getTasks().register("testOrderShowMethodOrder", task -> {
            task.setGroup("test-order");
            task.setDescription(
                    "Display the predicted test method execution order within test classes"
                            + " (set -Dtestorder.showMethodOrder.explain=true for detailed breakdown)");
            task.doLast(t -> {
                boolean explain = Boolean.parseBoolean(Optional
                        .ofNullable(gradleOrSystemProperty(project, "testorder.showMethodOrder.explain"))
                        .orElse("false"));
                runShowMethodOrderReport(project, ext, explain);
            });
        });

        project.getTasks().register("testOrderExplainMethodOrder", task -> {
            task.setGroup("test-order");
            task.setDescription("Display detailed per-test-method score explanations");
            task.doLast(t -> runShowMethodOrderReport(project, ext, true));
        });

        // Unified explain task: dispatches to class-level or method-level explanation
        // based on whether -Ptest contains '#' (method) or not (class).
        project.getTasks().register("testOrderExplain", task -> {
            task.setGroup("test-order");
            task.setDescription(
                    "Explain why a test (or method) was scored — uses -Ptest=<FQCN> or -Ptest=<FQCN#method>");
            task.doLast(t -> {
                String target = gradleOrSystemProperty(project, "test");
                if (target != null && target.contains("#")) {
                    runShowMethodOrderReport(project, ext, true);
                } else {
                    runShowOrderReport(project, ext, true, false);
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
                // Validate state file is readable before attempting optimization
                try {
                    TestOrderState.load(statePath);
                } catch (IOException e) {
                    throw new GradleException("[test-order] State file at " + statePath
                            + " is corrupt or unreadable: " + e.getMessage()
                            + ". Delete it and re-run tests to regenerate.", e);
                }
                try {
                    OptimizeOperation.Result result = OptimizeOperation.run(
                            statePath, msg -> project.getLogger().lifecycle(msg));
                    if (result != null && result.overfit()) {
                        project.getLogger().warn(
                                "[test-order] Overfitting detected — default weights used instead."
                                + " Collect more test runs before re-optimizing.");
                    }
                } catch (IOException e) {
                    throw new GradleException("Failed to optimise: " + e.getMessage(), e);
                }
            });
        });

        project.getTasks().register("testOrderAffected", Test.class, task -> {
            configureDerivedTestTask(project, ext, task);
            task.setGroup("test-order");
            task.setDescription("Run the prioritized selected subset of tests and write remaining tests to disk");

            // For the standalone select task, default to NOT auto-running remaining (parity
            // with Maven's select goal). Users can opt in via -Dtestorder.auto.runRemaining=true.
            // The implicit auto mode in the 'test' task still uses the extension default (true).
            String propRunRemaining = gradleOrSystemProperty(project, "testorder.auto.runRemaining");
            boolean runRemaining = propRunRemaining != null && Boolean.parseBoolean(propRunRemaining);
            final boolean effectiveRunRemaining = runRemaining;
            if (runRemaining) {
                task.finalizedBy("testOrderRunRemaining");
            }

            // Inject PriorityClassOrderer so ordering works within the subset
            task.systemProperty("junit.jupiter.testclass.order.default",
                    "me.bechberger.testorder.junit.PriorityClassOrderer");
            if (ext.getMethodOrderingEnabled().get()) {
                task.systemProperty("junit.jupiter.testmethod.order.default",
                        "me.bechberger.testorder.junit.PriorityMethodOrderer");
            }
            String debugFlag = gradleOrSystemProperty(project, "testorder.debug");
            if ("true".equalsIgnoreCase(debugFlag)) {
                task.systemProperty("testorder.debug", "true");
            }

            task.doFirst("testOrderAffectedPrepare", t -> {
                Test testTask = (Test) t;

                // Parity with Maven AffectedMojo: when the user passed an explicit --tests
                // CLI filter, leave their selection alone. test-order's selection would
                // either be ignored (if more restrictive) or wrongly widen the run.
                if (hasUserTestFilter(project, testTask)) {
                    project.getLogger().lifecycle(
                            "[test-order] Skipping selection — explicit --tests filter active. "
                            + "test-order will not override your test selection.");
                    return;
                }

                // Parity with Maven SurefireHelper.forceSingleForkForOrdering: PriorityClassOrderer
                // can only reorder classes inside one TestPlan, so multi-fork configs defeat
                // ordering. Pin maxParallelForks=1 / forkEvery=0 (preserving any explicit user
                // override via -Dtestorder.affected.preserveForkConfig=true).
                forceSingleForkForOrdering(project, testTask);

                // Parity with Maven AffectedMojo: validate topN/randomM before running
                // so invalid combinations (e.g. topN=-2, randomM=-1) fail immediately.
                int topN = resolveSelectTopN(project, ext);
                int randomM = resolveSelectRandomM(project, ext);
                try {
                    new ParameterValidator(wrapLog(project)).validateSelectParameters(topN, randomM);
                } catch (IllegalArgumentException e) {
                    throw new GradleException(e.getMessage(), e);
                }

                // Parity with Maven AffectedMojo: topN=-1 means all tests in priority
                // order (no subset), inform the user so the default is not surprising.
                if (topN == -1) {
                    project.getLogger().lifecycle(
                            "[test-order] topN=-1 (default) runs all tests in priority order (no subset selection). "
                            + "To run only the top N, set -Dtestorder.affected.topN=N.");
                }

                PluginContext pctx = buildPluginContext(project, ext);

                // Parity with Maven AffectedMojo: validate explicitly changed classes early
                // so a typo doesn't silently fall through to "no changes detected".
                if ("explicit".equalsIgnoreCase(ext.getChangeMode().get())) {
                    try {
                        DependencyMap depMap = DependencyMap.load(pctx.indexFile());
                        Set<String> changed = pctx.changedClasses() != null
                                ? splitClasses(pctx.changedClasses())
                                : Set.of();
                        new ParameterValidator(wrapLog(project))
                                .warnUnknownChangedClasses(changed, depMap, ext.getChangeMode().get());
                    } catch (IllegalArgumentException e) {
                        throw new GradleException(e.getMessage(), e);
                    } catch (IOException e) {
                        project.getLogger().warn("[test-order] Could not validate changed classes: {}", e.getMessage());
                    }
                }
                // Provide CI download callback and depsDir for auto-aggregation (R7-7/R7-15)
                Path rootDir = project.getRootProject().getProjectDir().toPath();
                File indexFileObj = ext.getIndexFile().getAsFile().get();
                Runnable ciDownloadCb = () -> {
                    if (me.bechberger.testorder.ci.CiConfigParser.configExistsIn(rootDir)) {
                        me.bechberger.testorder.ci.CiDepDownloadManager
                                .downloadIfConfigured(rootDir, indexFileObj.toPath())
                                .ifPresent(p -> project.getLogger().lifecycle(
                                        "[test-order] CI index downloaded to {}", p));
                    }
                };

                // Parity with Maven AffectedMojo.autoAggregateOrFail: when no index
                // exists, try to build one from available .deps files before handing off
                // to AutoWorkflow (which would otherwise skip with a cryptic error).
                // In multi-project builds, collect deps from all subproject build dirs
                // as well as the current project, so a single `testOrderAffected`
                // invocation on a freshly-learned multi-module repo finds all the data.
                Path indexPath = indexFileObj.toPath();
                if (!Files.exists(indexPath)) {
                    autoAggregateAffected(project, ext, indexPath, wrapLog(project));
                }

                Path depsDir = ext.getDepsDir().get().getAsFile().toPath();
                Path effectiveDepsDir = Files.isDirectory(depsDir) ? depsDir : null;
                try {
                    AutoWorkflow.Result result = new AutoWorkflow(
                            pctx, "order", ciDownloadCb, effectiveDepsDir).execute();

                    if (result instanceof AutoWorkflow.Result.OrderSelect os) {
                        if (os.attachLearnAgent()) {
                            project.getLogger().warn(
                                    "[test-order] alwaysLearn=true is currently only supported in the Maven plugin. "
                                    + "Gradle support is pending — flag has no effect on this run.");
                        }
                        TestSelector.Selection selection = os.selection();
                        applySelectedTests((Test) t, selection.selected());
                        if (selection.selected().isEmpty()) {
                            project.getLogger().warn(
                                    "[test-order] 0 tests selected in auto mode. "
                                    + "This may indicate no code changes were detected, or topN/randomM are misconfigured.");
                        }

                        // End-of-selection summary (parity with Maven AutoMojo)
                        var summary = os.selectResult().summary();
                        if (summary != null && summary.totalCount() > 0) {
                            for (String line : summary.format().split("\n")) {
                                project.getLogger().lifecycle(line);
                            }
                        }

                        if (!effectiveRunRemaining && !selection.remaining().isEmpty()) {
                            project.getLogger().warn(
                                    "[test-order] {} tests were NOT selected and will NOT run.",
                                    selection.remaining().size());
                            project.getLogger().warn(
                                    "[test-order] To run them: ./gradlew testOrderRunRemaining");
                            project.getLogger().warn(
                                    "[test-order] To always run remaining, set testOrder '{ autoRunRemaining = true }'"
                                    + " or -Dtestorder.auto.runRemaining=true");
                        }

                        // Apply orderer config as system properties
                        for (var entry : os.ordererConfigMap().entrySet()) {
                            ((Test) t).systemProperty(
                                    entry.getKey(), entry.getValue());
                        }
                    } else if (result instanceof AutoWorkflow.Result.Skip skipResult) {
                        throw new GradleException("[test-order] Select requires an index/dependency baseline before it can"
                                + " prioritize tests. " + skipResult.reason()
                                + " Run testOrderLearn first (or run tests with -Dtestorder.mode=learn).");
                    } else if (result instanceof AutoWorkflow.Result.Learn learnResult) {
                        throw new GradleException("[test-order] Select cannot proceed in learn mode. "
                                + learnResult.reason()
                                + " Run testOrderLearn first, then rerun testOrderAffected.");
                    } else {
                        throw new GradleException("[test-order] Select expected order mode but got: "
                                + result.getClass().getSimpleName());
                    }
                } catch (IOException e) {
                    throw new GradleException(
                            "Failed to run auto order+select workflow", e);
                }
            });
        });

        project.getTasks().register("testOrderRunRemaining", Test.class, task -> {
            configureDerivedTestTask(project, ext, task);
            task.setGroup("test-order");
            task.setDescription("Run only the deferred tests written by testOrderAffected");
            task.onlyIf("skip auto-finalized run-remaining when select failed", t -> {
                Task selectTask = project.getTasks().findByName("testOrderAffected");
                return selectTask == null || selectTask.getState().getFailure() == null;
            });
            task.doFirst("testOrderRunRemainingPrepare", t -> {
                Path remainingFile = ext.getRemainingFile().get().getAsFile().toPath();
                if (!Files.exists(remainingFile)) {
                    project.getLogger().warn("[test-order] No remaining-tests file found at {} — nothing to run.",
                            remainingFile);
                    applySelectedTests((Test) t, List.of());
                    return;
                }
                try {
                    List<String> tests = TestSelector.readTestList(remainingFile);
                    // Rename to .consumed instead of deleting — allows manual recovery if
                    // infrastructure failure prevents test execution (the file is gone otherwise)
                    Path consumed = remainingFile.resolveSibling(remainingFile.getFileName() + ".consumed");
                    Files.move(remainingFile, consumed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    // Validate test classes exist on disk (filter out renamed/deleted classes)
                    Path testOutputDir = project.getLayout().getBuildDirectory()
                            .dir("classes/java/test").get().getAsFile().toPath();
                    if (Files.isDirectory(testOutputDir)) {
                        List<String> missing = tests.stream()
                                .filter(tc -> !Files.exists(testOutputDir.resolve(tc.replace('.', '/') + ".class")))
                                .toList();
                        if (!missing.isEmpty()) {
                            project.getLogger().warn(
                                    "[test-order] {} test class(es) in remaining file not found on disk (renamed/deleted?): {}",
                                    missing.size(),
                                    missing.stream().limit(5).reduce((a, b) -> a + ", " + b).orElse(""));
                            tests = tests.stream()
                                    .filter(tc -> Files.exists(
                                            testOutputDir.resolve(tc.replace('.', '/') + ".class")))
                                    .toList();
                        }
                    }

                    if (tests.isEmpty()) {
                        project.getLogger().warn("[test-order] Remaining tests file is empty — skipping tests.");
                    } else {
                        project.getLogger().lifecycle("[test-order] Running {} remaining test classes", tests.size());
                    }
                    applySelectedTests((Test) t, tests);
                } catch (IOException e) {
                    throw new GradleException("Failed to read remaining tests file", e);
                }
                // Inject orderer config so tests are prioritized and listener records results
                Path indexPath = ext.getIndexFile().get().getAsFile().toPath();
                if (Files.exists(indexPath)) {
                    PluginContext pctx = buildPluginContext(project, ext);
                    Map<String, String> configMap = me.bechberger.testorder.ops.OrdererConfigOperation.buildConfig(
                            new me.bechberger.testorder.ops.OrdererConfigOperation.OrdererInput(
                                    pctx.indexFile().toAbsolutePath().toString(),
                                    pctx.stateFile().toAbsolutePath().toString(),
                                    pctx.weightsFile() != null ? pctx.weightsFile().toAbsolutePath().toString() : null,
                                    Set.of(), Set.of(), Set.of(),
                                    pctx.scoreOverrides(), pctx.methodOrderingEnabled(), pctx.springContextGrouping(),
                                    pctx.projectRoot().toAbsolutePath().toString(),
                                    pctx.sourceRoot() != null ? pctx.sourceRoot().toAbsolutePath().toString() : null,
                                    pctx.changeMode()));
                    for (var entry : configMap.entrySet()) {
                        ((Test) t).systemProperty(entry.getKey(), entry.getValue());
                    }
                }
            });
        });

        project.getTasks().register("testOrderTieredSelect", Test.class, task -> {
            configureDerivedTestTask(project, ext, task);
            task.setGroup("test-order");
            task.setDescription("Run tier-1 (change-affected) tests and write tier-2/tier-3 files for progressive CI");

            task.systemProperty("junit.jupiter.testclass.order.default",
                    "me.bechberger.testorder.junit.PriorityClassOrderer");
            if (ext.getMethodOrderingEnabled().get()) {
                task.systemProperty("junit.jupiter.testmethod.order.default",
                        "me.bechberger.testorder.junit.PriorityMethodOrderer");
            }
            String debugFlag = gradleOrSystemProperty(project, "testorder.debug");
            if ("true".equalsIgnoreCase(debugFlag)) {
                task.systemProperty("testorder.debug", "true");
            }

            task.doFirst("testOrderTieredSelectPrepare", t -> {
                PluginContext pctx = buildPluginContext(project, ext);

                double tier2Fraction = ext.getTieredTier2Fraction().get();
                String propFraction = gradleOrSystemProperty(project, "testorder.tiered.tier2Fraction");
                if (propFraction != null && !propFraction.isBlank()) {
                    try {
                        tier2Fraction = Double.parseDouble(propFraction);
                    } catch (NumberFormatException e) {
                        throw new GradleException(
                                "[test-order] Invalid testorder.tiered.tier2Fraction value '" + propFraction
                                + "'. Must be a number in [0, 1].");
                    }
                }
                if (tier2Fraction < 0 || tier2Fraction > 1) {
                    throw new GradleException(
                            "[test-order] testorder.tiered.tier2Fraction must be in [0, 1], got " + tier2Fraction);
                }
                boolean weightByDuration = ext.getTieredWeightByDuration().get();
                String propWeight = gradleOrSystemProperty(project, "testorder.tiered.weightByDuration");
                if (propWeight != null) {
                    weightByDuration = Boolean.parseBoolean(propWeight);
                }

                Path tier1File = ext.getTieredTier1File().get().getAsFile().toPath();
                Path tier2File = ext.getTieredTier2File().get().getAsFile().toPath();
                Path tier3File = ext.getTieredTier3File().get().getAsFile().toPath();

                try {
                    me.bechberger.testorder.ops.workflows.ChangeAnalysis.Result analysis =
                            me.bechberger.testorder.ops.workflows.ChangeAnalysis.analyze(
                                    pctx, me.bechberger.testorder.ops.workflows.ChangeAnalysis.Options.FOR_SELECTION);

                    Path testClassesDir = resolveTestClassesDir(project);
                    Set<String> alwaysRun = me.bechberger.testorder.ops.AlwaysRunScanner.scan(testClassesDir);

                    me.bechberger.testorder.ops.TieredSelectOperation.TieredSelectResult result =
                            me.bechberger.testorder.ops.TieredSelectOperation.select(
                                    new me.bechberger.testorder.ops.TieredSelectOperation.TieredSelectConfig(
                                            analysis.depMap(), analysis.state(), analysis.changedClasses(),
                                            analysis.changedTests(), analysis.weights(), tier2Fraction,
                                            weightByDuration, alwaysRun, tier1File, tier2File, tier3File,
                                            wrapLog(project)));

                    me.bechberger.testorder.TieredTestSelector.TieredSelection selection = result.selection();

                    if (!selection.tier1().isEmpty()) {
                        applySelectedTests((Test) t, selection.tier1());
                        project.getLogger().lifecycle("[test-order] Tier 1: running {} change-affected tests",
                                selection.tier1().size());
                    } else if (!selection.tier2().isEmpty()) {
                        applySelectedTests((Test) t, selection.tier2());
                        project.getLogger().lifecycle("[test-order] Tier 1 empty — running {} tier-2 tests directly",
                                selection.tier2().size());
                        clearTierFile(tier2File, project);
                    } else if (!selection.tier3().isEmpty()) {
                        applySelectedTests((Test) t, selection.tier3());
                        project.getLogger().lifecycle("[test-order] Tiers 1+2 empty — running {} tier-3 tests directly",
                                selection.tier3().size());
                        clearTierFile(tier3File, project);
                    } else {
                        applySelectedTests((Test) t, List.of());
                        project.getLogger().warn("[test-order] No tests to run (all tiers empty). Run in learn mode first: ./gradlew test -Dtestorder.mode=learn");
                    }

                    // Apply orderer config as system properties (R7-1: inject ordering config)
                    Map<String, String> configMap = me.bechberger.testorder.ops.OrdererConfigOperation.buildConfig(
                            new me.bechberger.testorder.ops.OrdererConfigOperation.OrdererInput(
                                    pctx.indexFile().toAbsolutePath().toString(),
                                    pctx.stateFile().toAbsolutePath().toString(),
                                    pctx.weightsFile() != null ? pctx.weightsFile().toAbsolutePath().toString() : null,
                                    analysis.changedClasses(), analysis.changedTests(), Set.of(),
                                    pctx.scoreOverrides(), pctx.methodOrderingEnabled(), pctx.springContextGrouping(),
                                    pctx.projectRoot().toAbsolutePath().toString(),
                                    pctx.sourceRoot() != null ? pctx.sourceRoot().toAbsolutePath().toString() : null,
                                    pctx.changeMode()));
                    for (var entry : configMap.entrySet()) {
                        ((Test) t).systemProperty(entry.getKey(), entry.getValue());
                    }

                    project.getLogger().lifecycle("[test-order] Tier files written:");
                    project.getLogger().lifecycle("[test-order]   Tier 1 ({} tests): {}",
                            selection.tier1().size(), tier1File);
                    project.getLogger().lifecycle("[test-order]   Tier 2 ({} tests): {}",
                            selection.tier2().size(), tier2File);
                    project.getLogger().lifecycle("[test-order]   Tier 3 ({} tests): {}",
                            selection.tier3().size(), tier3File);

                    java.util.List<String> deferred = java.util.stream.Stream
                            .concat(selection.tier2().stream(), selection.tier3().stream()).toList();
                    me.bechberger.testorder.ops.CiSummaryWriter.writeSummary(
                            new me.bechberger.testorder.ops.CiSummaryWriter.SummaryInput(
                                    analysis.depMap().testClasses().size(),
                                    selection.tier1(), deferred,
                                    analysis.changedClasses(), analysis.changedTests(),
                                    java.util.List.of(), "tiered-select", 1,
                                    project.getLayout().getBuildDirectory().get().getAsFile().toPath()),
                            wrapLog(project));

                } catch (IOException e) {
                    throw new GradleException("Failed to run tiered test selection", e);
                }
            });
        });

        project.getTasks().register("testOrderRunTier", Test.class, task -> {
            configureDerivedTestTask(project, ext, task);
            task.setGroup("test-order");
            task.setDescription("Run tier 2 or tier 3 from a previous testOrderTieredSelect invocation");

            task.systemProperty("junit.jupiter.testclass.order.default",
                    "me.bechberger.testorder.junit.PriorityClassOrderer");
            if (ext.getMethodOrderingEnabled().get()) {
                task.systemProperty("junit.jupiter.testmethod.order.default",
                        "me.bechberger.testorder.junit.PriorityMethodOrderer");
            }

            task.doFirst("testOrderRunTierPrepare", t -> {
                String tierProp = gradleOrSystemProperty(project, "testorder.tiered.currentTier");
                if (tierProp == null || tierProp.isBlank()) {
                    throw new GradleException("[test-order] testorder.tiered.currentTier must be set to 2 or 3. "
                            + "Use: ./gradlew testOrderRunTier -Dtestorder.tiered.currentTier=2");
                }
                int currentTier;
                try {
                    currentTier = Integer.parseInt(tierProp.trim());
                } catch (NumberFormatException e) {
                    throw new GradleException("[test-order] testorder.tiered.currentTier must be 2 or 3, got: " + tierProp);
                }
                if (currentTier != 2 && currentTier != 3) {
                    throw new GradleException("[test-order] testorder.tiered.currentTier must be 2 or 3, got: " + currentTier);
                }

                Path tierFile = currentTier == 2
                        ? ext.getTieredTier2File().get().getAsFile().toPath()
                        : ext.getTieredTier3File().get().getAsFile().toPath();

                if (!Files.exists(tierFile)) {
                    project.getLogger().warn("[test-order] No tests to run (tier-{} file not found at {}). Re-run: ./gradlew testOrderTieredSelect test",
                            currentTier, tierFile);
                    applySelectedTests((Test) t, List.of());
                    return;
                }

                try {
                    List<String> tests = TestSelector.readTestList(tierFile);
                    if (tests.isEmpty()) {
                        project.getLogger().warn("[test-order] No tests to run (tier-{} list is empty). Re-run: ./gradlew testOrderTieredSelect test", currentTier);
                        applySelectedTests((Test) t, List.of());
                    } else {
                        // Apply sharding for tier-3
                        String shardSpec = gradleOrSystemProperty(project, "testorder.tiered.shard");
                        if (shardSpec != null && !shardSpec.isBlank() && currentTier != 3) {
                            project.getLogger().warn("[test-order] testorder.tiered.shard={} is only applied to tier-3; ignored for tier-{}", shardSpec, currentTier);
                        }
                        if (currentTier == 3) {
                            if (shardSpec != null && !shardSpec.isBlank()) {
                                try {
                                    List<String> sharded = me.bechberger.testorder.TieredTestSelector.applyShard(tests, shardSpec);
                                    project.getLogger().lifecycle("[test-order] Shard {}: running {} of {} tier-3 test classes",
                                            shardSpec, sharded.size(), tests.size());
                                    tests = sharded;
                                } catch (IllegalArgumentException e) {
                                    throw new GradleException("[test-order] Invalid shard spec: " + e.getMessage());
                                }
                            }
                        }
                        project.getLogger().lifecycle("[test-order] Running {} tier-{} test classes",
                                tests.size(), currentTier);
                        applySelectedTests((Test) t, tests);
                        me.bechberger.testorder.ops.CiSummaryWriter.writeSummary(
                                new me.bechberger.testorder.ops.CiSummaryWriter.SummaryInput(
                                        tests.size(), tests, java.util.List.of(),
                                        java.util.Set.of(), java.util.Set.of(),
                                        java.util.List.of(), "run-tier", currentTier,
                                        project.getLayout().getBuildDirectory().get().getAsFile().toPath()),
                                wrapLog(project));
                    }
                } catch (IOException e) {
                    throw new GradleException("Failed to read tier-" + currentTier + " tests file", e);
                }
                Path indexPath = ext.getIndexFile().get().getAsFile().toPath();
                if (Files.exists(indexPath)) {
                    try {
                        PluginContext pctx = buildPluginContext(project, ext);
                        me.bechberger.testorder.ops.workflows.ChangeAnalysis.Result analysis =
                                me.bechberger.testorder.ops.workflows.ChangeAnalysis.analyze(
                                        pctx, me.bechberger.testorder.ops.workflows.ChangeAnalysis.Options.FOR_SELECTION);
                        Map<String, String> configMap = me.bechberger.testorder.ops.OrdererConfigOperation.buildConfig(
                                new me.bechberger.testorder.ops.OrdererConfigOperation.OrdererInput(
                                        pctx.indexFile().toAbsolutePath().toString(),
                                        pctx.stateFile().toAbsolutePath().toString(),
                                        pctx.weightsFile() != null ? pctx.weightsFile().toAbsolutePath().toString() : null,
                                        analysis.changedClasses(), analysis.changedTests(), Set.of(),
                                        pctx.scoreOverrides(), pctx.methodOrderingEnabled(), pctx.springContextGrouping(),
                                        pctx.projectRoot().toAbsolutePath().toString(),
                                        pctx.sourceRoot() != null ? pctx.sourceRoot().toAbsolutePath().toString() : null,
                                        pctx.changeMode()));
                        for (var entry : configMap.entrySet()) {
                            ((Test) t).systemProperty(entry.getKey(), entry.getValue());
                        }
                    } catch (IOException e) {
                        throw new GradleException("Failed to build orderer config for run-tier", e);
                    }
                }
            });
        });

        project.getTasks().register("testOrderDetectDependencies", task -> {
            task.setGroup("test-order");
            task.setDescription("Detect order-dependent tests by running permutations");
            task.doLast(t -> {
                autoAggregateIfNeeded(project, ext);

                Path indexPath = ext.getIndexFile().get().getAsFile().toPath();
                Path statePath = ext.getStateFile().get().getAsFile().toPath();
                Path outputDir = project.getRootProject().getProjectDir().toPath()
                        .resolve(".test-order/detection");

                String algorithm = Optional
                        .ofNullable(gradleOrSystemProperty(project, "testorder.detect.algorithm"))
                        .orElse("combined");
                int timeBudget;
                String timeBudgetStr = gradleOrSystemProperty(project, "testorder.detect.timeBudget");
                if (timeBudgetStr != null) {
                    try {
                        timeBudget = Integer.parseInt(timeBudgetStr);
                    } catch (NumberFormatException e) {
                        throw new GradleException(
                                "[test-order] Invalid testorder.detect.timeBudget value '"
                                        + timeBudgetStr + "' — must be an integer");
                    }
                } else {
                    timeBudget = 300;
                }
                if (timeBudget < 0) {
                    throw new GradleException(
                            "[test-order] Invalid timeBudget: " + timeBudget
                                    + ". Must be >= 0 (0 = unlimited).");
                }
                boolean stopOnFirst = Optional
                        .ofNullable(gradleOrSystemProperty(project, "testorder.detect.stopOnFirst"))
                        .map(Boolean::parseBoolean)
                        .orElse(false);
                long seed;
                String seedStr = gradleOrSystemProperty(project, "testorder.detect.seed");
                if (seedStr != null) {
                    try {
                        seed = Long.parseLong(seedStr);
                    } catch (NumberFormatException e) {
                        throw new GradleException(
                                "[test-order] Invalid testorder.detect.seed value '"
                                        + seedStr + "' — must be a number");
                    }
                } else {
                    seed = 42L;
                }
                boolean failOnDetection = Optional
                        .ofNullable(gradleOrSystemProperty(project, "testorder.detect.failOnDetection"))
                        .map(Boolean::parseBoolean)
                        .orElse(false);

                me.bechberger.testorder.ops.DetectDependenciesOperation.Config config =
                        new me.bechberger.testorder.ops.DetectDependenciesOperation.Config(
                                indexPath, statePath, outputDir,
                                algorithm, timeBudget, stopOnFirst, seed,
                                project.getName(), wrapLog(project));

                me.bechberger.testorder.ops.detection.TestRunner runner =
                        new GradleTestRunner(project.getProjectDir().toPath(), project.getLogger());

                try {
                    me.bechberger.testorder.ops.DetectDependenciesOperation.Result result =
                            me.bechberger.testorder.ops.DetectDependenciesOperation.run(config, runner);

                    if (result.hasFindings()) {
                        int classLevel = result.results().size();
                        int methodLevel = result.methodResults().size();
                        int total = classLevel + methodLevel;
                        project.getLogger().warn("[test-order] Detected " + total
                                + " order-dependent finding(s): "
                                + result.victimCount() + " class-level victims, "
                                + result.brittleCount() + " class-level brittles"
                                + (methodLevel > 0 ? ", " + methodLevel + " method-level" : ""));
                        if (result.reportPath() != null) {
                            project.getLogger().warn("[test-order] JSON report: " + result.reportPath());
                        }
                        if (result.markdownReportPath() != null) {
                            project.getLogger().warn("[test-order] Markdown report: "
                                    + result.markdownReportPath());
                        }
                        if (failOnDetection) {
                            throw new GradleException("[test-order] Detected " + total
                                    + " order-dependent test(s). See reports in " + outputDir);
                        }
                    } else {
                        if (timeBudget > 0) {
                            project.getLogger().lifecycle("[test-order] No order-dependent tests "
                                    + "detected within time budget (" + timeBudget + "s). Increase "
                                    + "testorder.detect.timeBudget for more coverage.");
                        } else {
                            project.getLogger().lifecycle(
                                    "[test-order] No order-dependent tests detected.");
                        }
                    }
                } catch (IOException e) {
                    throw new GradleException(
                            "Detect-dependencies failed: " + e.getMessage(), e);
                }
            });
        });

        project.getTasks().register("testOrderClean", task -> {
            task.setGroup("test-order");
            task.setDescription("Remove all test-order generated files "
                    + "(index, state, hashes, deps dir)");
            task.doLast(t -> {
                Path hashFilePath = ext.getHashFile().get().getAsFile().toPath();
                Path testHashFilePath = ext.getTestHashFile().get().getAsFile().toPath();
                Path methodHashFilePath = ext.getMethodHashFile().get().getAsFile().toPath();
                List<Path> files = new ArrayList<>(List.of(
                        ext.getIndexFile().get().getAsFile().toPath(),
                        ext.getStateFile().get().getAsFile().toPath(),
                        hashFilePath,
                        testHashFilePath,
                        methodHashFilePath));
                files.add(HashSnapshotOperation.kotlinHashFile(hashFilePath));
                files.add(HashSnapshotOperation.kotlinHashFile(testHashFilePath));
                files.add(HashSnapshotOperation.kotlinHashFile(methodHashFilePath));
                List<Path> dirs = List.of(ext.getDepsDir().get().getAsFile().toPath());
                CleanOperation.clean(files, dirs, wrapLog(project));
            });
        });
        project.getTasks().register("testOrderDashboard", task -> {
            task.setGroup("test-order");
            task.setDescription(
                    "Generate an HTML dashboard visualising test scoring, dependencies, and run history");
            task.mustRunAfter("test");
            task.doLast(t -> {
                autoAggregateIfNeeded(project, ext);
                Path outPath = generateDashboard(project, ext);
                boolean openBrowser = Boolean.parseBoolean(Optional
                        .ofNullable(gradleOrSystemProperty(project, "testorder.dashboard.open"))
                        .orElse("false"));
                if (openBrowser) {
                    tryOpenBrowser(outPath.toAbsolutePath().toUri());
                }
            });
        });

        project.getTasks().register("testOrderServe", task -> {
            task.setGroup("test-order");
            task.setDescription(
                    "Generate and serve the test-order HTML dashboard on a local HTTP server");
            task.mustRunAfter("test");
            task.doLast(t -> {
                autoAggregateIfNeeded(project, ext);
                int port;
                try {
                    port = Integer.parseInt(Optional
                            .ofNullable(gradleOrSystemProperty(project, "testorder.dashboard.port"))
                            .orElse("0"));
                } catch (NumberFormatException e) {
                    throw new GradleException("[test-order] Invalid testorder.dashboard.port value '"
                            + gradleOrSystemProperty(project, "testorder.dashboard.port")
                            + "' — must be a number");
                }
                int serveSeconds;
                try {
                    serveSeconds = Integer.parseInt(Optional
                            .ofNullable(gradleOrSystemProperty(project, "testorder.dashboard.serveSeconds"))
                            .orElse("0"));
                } catch (NumberFormatException e) {
                    throw new GradleException("[test-order] Invalid testorder.dashboard.serveSeconds value '"
                            + gradleOrSystemProperty(project, "testorder.dashboard.serveSeconds")
                            + "' — must be a number");
                }
                if (serveSeconds < 0) {
                    throw new GradleException(
                            "[test-order] testorder.dashboard.serveSeconds must be >= 0");
                }
                String regenerate = Optional
                        .ofNullable(gradleOrSystemProperty(project, "testorder.dashboard.regenerate"))
                        .orElse("auto");
                Path outDir = project.getLayout().getBuildDirectory()
                        .dir("test-order-dashboard").get().getAsFile().toPath();
                Path htmlPath = outDir.resolve("index.html");
                boolean shouldGenerate = switch (regenerate.toLowerCase(java.util.Locale.ROOT)) {
                    case "true", "yes", "always" -> true;
                    case "false", "no", "never" -> false;
                    default -> !Files.exists(htmlPath);
                };
                if (shouldGenerate) {
                    generateDashboard(project, ext);
                } else if (!Files.exists(htmlPath)) {
                    throw new GradleException("[test-order] Dashboard not found at " + htmlPath
                            + ". Run testOrderDashboard first, or set -Dtestorder.dashboard.regenerate=auto");
                }
                Path statePath = ext.getStateFile().get().getAsFile().toPath();
                boolean openBrowser = Boolean.parseBoolean(Optional
                    .ofNullable(gradleOrSystemProperty(project, "testorder.dashboard.open"))
                    .orElse("false"));
                serveDashboard(project, outDir, statePath, port, serveSeconds, openBrowser);
            });
        });

        project.getTasks().register("testOrderLearn", Test.class, task -> {
            configureDerivedTestTask(project, ext, task);
            task.setGroup("test-order");
            task.setDescription("Run tests in learn mode (always instruments, regardless of current mode)");
            // Configure learn mode directly (no afterEvaluate to avoid mutation-guard errors)
            TestOrderPlugin.this.configureLearnMode(project, ext, task, agentConf);
        });

        project.getTasks().register("testOrderCoverage", task -> {
            task.setGroup("test-order");
            task.setDescription("Analyze dependency-based test coverage and generate reports");
            task.doLast(t -> {
                autoAggregateIfNeeded(project, ext);
                Path indexFile = ext.getIndexFile().get().getAsFile().toPath();
                if (!Files.exists(indexFile)) {
                    throw new GradleException("[test-order] No dependency index found at " + indexFile
                            + " — run tests in learn mode first.");
                }
                int threshold = ext.getCoverageThreshold().get();
                String propThreshold = gradleOrSystemProperty(project, "testorder.coverage.threshold");
                if (propThreshold == null || propThreshold.isBlank()) {
                    propThreshold = gradleOrSystemProperty(project, "coverage.threshold");
                }
                if (propThreshold != null && !propThreshold.isBlank()) {
                    try {
                        threshold = Integer.parseInt(propThreshold);
                    } catch (NumberFormatException e) {
                        throw new GradleException("[test-order] Invalid coverage.threshold value '"
                                + propThreshold + "' — must be a positive integer");
                    }
                }
                Path outputDir = ext.getCoverageOutputDir().get().getAsFile().toPath();
                String propOutputDir = gradleOrSystemProperty(project, "coverage.outputDir");
                if (propOutputDir != null && !propOutputDir.isBlank()) {
                    outputDir = Path.of(propOutputDir);
                }
                PluginLog plog = wrapLog(project);
                try {
                    DependencyMap depMap = DependencyMap.load(indexFile);
                    CoverageAnalysis analysis = CoverageOperation.analyze(depMap, plog);
                    CoverageOperation.writeReports(analysis, outputDir, threshold, plog);
                    CoverageOperation.printSummary(analysis, threshold, System.out);
                } catch (IOException e) {
                    throw new GradleException("Failed to analyze coverage", e);
                }
            });
        });

        project.getTasks().register("testOrderSnapshot", task -> {
            task.setGroup("test-order");
            task.setDescription("Save hash snapshots of source and test files for since-last-run change detection");
            task.doLast(t -> snapshotHashes(project, ext));
        });

        project.getTasks().register("testOrderDiagnose", task -> {
            task.setGroup("test-order");
            task.setDescription("Run diagnostics to detect setup issues");
            task.doLast(t -> {
                boolean failOnError = Boolean.parseBoolean(Optional
                        .ofNullable(gradleOrSystemProperty(project, "testorder.failOnError"))
                        .orElse("false"));
                runDiagnostics(project, ext, failOnError);
            });
        });

        project.getTasks().register("testOrderCompact", task -> {
            task.setGroup("test-order");
            task.setDescription("Rebuild index by compacting .deps files (removes stale entries)");
            task.doLast(t -> runCompact(project, ext));
        });

        project.getTasks().register("testOrderMetrics", task -> {
            task.setGroup("test-order");
            task.setDescription("Export test-order metrics as JSON for CI/CD dashboards and reporting");
            task.doLast(t -> {
                Path indexFile = ext.getIndexFile().get().getAsFile().toPath();
                Path statePath = ext.getStateFile().get().getAsFile().toPath();
                Path testClassesDir = resolveTestClassesDir(project);
                PluginLog plog = wrapLog(project);
                PluginContext pctx = buildPluginContext(project, ext);

                me.bechberger.testorder.ops.TestMetricsExport metrics =
                        me.bechberger.testorder.ops.MetricsWorkflow.generate(
                                project.getName(), indexFile, statePath, testClassesDir,
                                pctx,
                                "Run ./gradlew test -Dtestorder.mode=learn",
                                plog);

                String propOutput = gradleOrSystemProperty(project, "testorder.metrics.output");
                Path output = propOutput != null && !propOutput.isBlank()
                        ? Path.of(propOutput)
                        : project.getLayout().getBuildDirectory()
                                .file("test-order-metrics.json").get().getAsFile().toPath();
                try {
                    me.bechberger.testorder.ops.MetricsWorkflow.writeToFile(metrics, output, plog);
                } catch (IOException e) {
                    throw new GradleException("Failed to write metrics: " + e.getMessage(), e);
                }
            });
        });

        project.getTasks().register("testOrderHelp", task -> {
            task.setGroup("test-order");
            task.setDescription("Display available test-order tasks and configuration options");
            task.doLast(t -> {
                var log = project.getLogger();
                log.lifecycle("");
                log.lifecycle("═══════════════════════════════════════════════════════════");
                log.lifecycle("  test-order — Intelligent Test Prioritization Plugin");
                log.lifecycle("═══════════════════════════════════════════════════════════");
                log.lifecycle("");
                log.lifecycle("QUICK START:");
                log.lifecycle("  1) First run:   ./gradlew test");
                log.lifecycle("  2) Inspect:     ./gradlew testOrderShow");
                log.lifecycle("  3) Dashboard:   ./gradlew testOrderDashboard");
                log.lifecycle("  CI fast path:   ./gradlew testOrderAffected && ./gradlew testOrderRunRemaining");
                log.lifecycle("");
                log.lifecycle("TASKS:");
                log.lifecycle("  test                         Run tests with auto mode (learn or order)");
                log.lifecycle("  testOrderLearn               Force learn mode (collect dependencies)");
                log.lifecycle("  testOrderAffected            Run prioritized test subset");
                log.lifecycle("  testOrderRunRemaining        Run deferred tests from testOrderAffected");
                log.lifecycle("  testOrderTieredSelect        Three-tier CI test selection");
                log.lifecycle("  testOrderRunTier             Run tier 2 or 3 from tiered-select");
                log.lifecycle("  testOrderShow                Unified view: class order, method order, ML health");
                log.lifecycle("  testOrderShowOrder           Display predicted test order");
                log.lifecycle("  testOrderExplainOrder        Detailed per-test score breakdown");
                log.lifecycle("  testOrderShowMethodOrder     Display predicted method order");
                log.lifecycle("  testOrderExplainMethodOrder  Detailed per-method score breakdown");
                log.lifecycle("  testOrderOptimize            Tune scoring weights from history");
                log.lifecycle("  testOrderDashboard           Generate interactive HTML report");
                log.lifecycle("  testOrderServe               Serve dashboard on local HTTP server");
                log.lifecycle("  testOrderDiagnose            Validate setup and detect issues");
                log.lifecycle("  testOrderDetectDependencies  Detect order-dependent tests");
                log.lifecycle("  testOrderCompact             Rebuild index (remove stale entries)");
                log.lifecycle("  testOrderMetrics             Export metrics JSON for CI/CD");
                log.lifecycle("  testOrderAggregate           Aggregate .deps files into index");
                if (!project.getSubprojects().isEmpty()) {
                    log.lifecycle("  testOrderAggregateAll        Aggregate from all subprojects");
                }
                log.lifecycle("  testOrderDump                Dump index as text");
                log.lifecycle("  testOrderExportJson          Export index + history as JSON");
                log.lifecycle("  testOrderCoverage            Analyze test coverage gaps");
                log.lifecycle("  testOrderSnapshot            Save hash snapshots");
                log.lifecycle("  testOrderDownload            Download index from CI");
                log.lifecycle("  testOrderClean               Remove all generated files");
                log.lifecycle("");
                log.lifecycle("CONFIGURATION (build.gradle testOrder { ... }):");
                log.lifecycle("  mode                    auto | learn | order | optimize | skip");
                log.lifecycle("  instrumentationMode     MEMBER (default) | CLASS | METHOD");
                log.lifecycle("  changeMode              auto | since-last-run | since-last-commit | uncommitted | explicit");
                log.lifecycle("  includePackages         Additional package prefixes to instrument");
                log.lifecycle("  methodOrderingEnabled   Enable method-level reordering (default: false)");
                log.lifecycle("  selectTopN              Top-scored tests to always select (default: -1 = all)");
                log.lifecycle("  selectRandomM           Random diverse fast tests to add (default: 10)");
                log.lifecycle("  autoLearnRunThreshold   Re-learn after N order runs (default: 10)");
                log.lifecycle("  autoLearnDiffThreshold  Auto-learn if N+ classes change in one run (default: 0=disabled)");
                log.lifecycle("  autoOptimizeEvery       Run weight optimization every N runs (default: 10)");
                log.lifecycle("  autoCompactEvery        Auto-compact every N runs (default: 50)");
                log.lifecycle("  tieredTier2Fraction     Tier-2 duration/count fraction 0–1 (default: 0.5)");
                log.lifecycle("  springContextGrouping   Group tests by Spring context (default: false)");
                log.lifecycle("  selectiveLearn          Only re-instrument changed classes + transitive callees (default: false)");
                log.lifecycle("  alwaysLearn             Attach learn agent on every ordered run (default: false)");
                log.lifecycle("");
                log.lifecycle("SYSTEM PROPERTIES:");
                log.lifecycle("  -Dtestorder.mode=<mode>           Override mode for this run");
                log.lifecycle("  -Dtestorder.mode.<task>=<mode>    Override mode for a specific test task");
                log.lifecycle("  -Dtestorder.skip=true             Disable plugin entirely");
                log.lifecycle("  -Dtestorder.debug=true            Verbose scoring output");
                log.lifecycle("  -Dtestorder.changeMode=<mode>     Override change detection");
                log.lifecycle("  -Dtestorder.learn.selective=true  Only re-instrument changed classes + transitive callees");
                log.lifecycle("  -Dtestorder.failOnError=true      Fail build on diagnostic errors");
                log.lifecycle("");
                log.lifecycle("SCORE TUNING (-Dtestorder.score.<name>=<weight>):");
                log.lifecycle("  newTest, changedTest, maxFailure, speed, depOverlap, changeComplexity");
                log.lifecycle("");
                log.lifecycle("DOCS: See docs/CLI_REFERENCE.md for full reference");
                log.lifecycle("═══════════════════════════════════════════════════════════");
                log.lifecycle("");
            });
        });
    }

    // -----------------------------------------------------------------------
    // Dashboard helpers
    // -----------------------------------------------------------------------

    /** Builds and writes the self-contained dashboard HTML; returns the path to index.html. */
    private Path generateDashboard(Project project, TestOrderExtension ext) {
        Path outDir = project.getLayout().getBuildDirectory()
                .dir("test-order-dashboard").get().getAsFile().toPath();
        PluginContext pctx = buildPluginContext(project, ext);

        // Compute ML health data if ml-history is available
        java.util.Map<String, Double> mlPredictions = null;
        me.bechberger.testorder.ml.TestHealthReport healthReport = null;
        Path mlHistoryDir = pctx.stateFile() != null
                ? pctx.stateFile().getParent().resolve("ml-history")
                : pctx.projectRoot().resolve(".test-order/ml-history");
        me.bechberger.testorder.ml.MLHealthLoader.LoadResult mlResult =
                me.bechberger.testorder.ml.MLHealthLoader.load(mlHistoryDir);
        if (mlResult.hasData()) {
            healthReport = mlResult.healthReport();
        }

        try {
            String template = DashboardResources.assembleTemplate();
            return new DashboardWorkflow(pctx, template, outDir, mlPredictions, healthReport).generate();
        } catch (IOException e) {
            String detail = e.getMessage() != null ? e.getMessage() : "unknown error";
            throw new GradleException("[test-order] Dashboard generation failed: " + detail
                    + ". Run ./gradlew test first to learn dependencies.", e);
        }
    }

    /** Starts a local HTTP server serving the self-contained dashboard HTML. */
    private void serveDashboard(Project project, Path dashboardDir, Path statePath, int port, int serveSeconds, boolean openBrowser) {
        Path htmlPath = dashboardDir.resolve("index.html");
        try {
            DashboardServerOperation.start(htmlPath, statePath, port, wrapLog(project), null, serveSeconds, openBrowser);
        } catch (IOException e) {
            throw new GradleException("Failed to start dashboard HTTP server", e);
        }
    }

    // -----------------------------------------------------------------------
    // PluginContext builder
    // -----------------------------------------------------------------------

    /**
     * Builds a framework-agnostic {@link PluginContext} from the Gradle extension
     * and project. Used by workflow-delegating methods.
     */
    static PluginContext.Builder buildPluginContextBuilder(Project project, TestOrderExtension ext) {
        Path sourceRoot = resolveMainSourceRoot(project);
        Path testSourceRoot = resolveTestSourceRoot(project);
        List<Path> additionalSourceRoots = new ArrayList<>();
        Path ktRoot = resolveKotlinSourceRoot(project);
        if (Files.isDirectory(ktRoot)) {
            additionalSourceRoots.add(ktRoot);
        }

        // Add source roots from sibling subprojects for cross-module changeComplexity scoring
        if (project.getRootProject() != project) {
            for (Project sub : project.getRootProject().getSubprojects()) {
                if (sub == project) continue;
                Path subSrc = sub.getProjectDir().toPath().resolve("src/main/java");
                if (Files.isDirectory(subSrc)) {
                    additionalSourceRoots.add(subSrc);
                }
                Path subKt = sub.getProjectDir().toPath().resolve("src/main/kotlin");
                if (Files.isDirectory(subKt)) {
                    additionalSourceRoots.add(subKt);
                }
            }
        }

        String weightsFile = ext.getWeightsFile().get();
        Map<String, Integer> scoreOverrides = WeightResolverOperation.buildScoreOverrides(
                orNull(ext.getScoreNewTest()), orNull(ext.getScoreChangedTest()),
                orNull(ext.getScoreMaxFailure()), orNull(ext.getScoreSpeed()),
                orNull(ext.getScoreSpeedPenalty()), orNull(ext.getScoreDepOverlap()),
                orNull(ext.getScoreChangeComplexity()), orNull(ext.getScoreStaticFieldBonus()),
                orNull(ext.getScoreCoverageBonus()), orNull(ext.getScoreKillRateBonus()));

        String explicitChanged = ext.getChangedClasses().get();
        String propChanged = gradleOrSystemProperty(project, "testorder.changed.classes");
        String changedClasses = null;
        if (propChanged != null && !propChanged.isBlank()) {
            changedClasses = propChanged;
        } else if (explicitChanged != null && !explicitChanged.isBlank()) {
            changedClasses = explicitChanged;
        }

        // Resolve changed test classes (parity with Maven's -Dtestorder.changed.test.classes)
        String explicitChangedTests = ext.getChangedTestClasses().get();
        String propChangedTests = gradleOrSystemProperty(project, "testorder.changed.test.classes");
        String changedTestClasses = null;
        if (propChangedTests != null && !propChangedTests.isBlank()) {
            changedTestClasses = propChangedTests;
        } else if (explicitChangedTests != null && !explicitChangedTests.isBlank()) {
            changedTestClasses = explicitChangedTests;
        }

        return PluginContext.builder()
                .projectRoot(project.getRootProject().getProjectDir().toPath().toAbsolutePath())
                .repoRoot(project.getRootProject().getProjectDir().toPath().toAbsolutePath())
                .sourceRoot(sourceRoot)
                .testSourceRoot(testSourceRoot)
                .additionalSourceRoots(additionalSourceRoots)
                .testClassesDir(resolveTestClassesDir(project))
                .classesDir(resolveClassesDir(project))
                .indexFile(ext.getIndexFile().get().getAsFile().toPath())
                .stateFile(ext.getStateFile().get().getAsFile().toPath())
                .depsDir(ext.getDepsDir().get().getAsFile().toPath())
                .hashFile(ext.getHashFile().get().getAsFile().toPath())
                .testHashFile(ext.getTestHashFile().get().getAsFile().toPath())
                .methodHashFile(ext.getMethodHashFile().get().getAsFile().toPath())
                .bytecodeHashFile(ext.getBytecodeHashFile().get().getAsFile().toPath())
                .bytecodeChangeDetectionEnabled(ext.getBytecodeChangeDetectionEnabled().get())
                .bytecodeAugmentDependencyMapEnabled(ext.getBytecodeAugmentDependencyMapEnabled().get())
                .changeMode(resolveChangeMode(project, ext))
                .changedClasses(changedClasses)
                .changedTestClasses(changedTestClasses)
                .weightsFile(weightsFile != null && !weightsFile.isBlank() ? Path.of(weightsFile) : null)
                .scoreOverrides(scoreOverrides)
                .methodOrderingEnabled(ext.getMethodOrderingEnabled().get())
                .groupId(String.valueOf(project.getGroup()))
                .instrumentationMode(ext.getInstrumentationMode().get())
                .includePackages(ext.getIncludePackages().get().isEmpty() ? null : ext.getIncludePackages().get())
                .filterByGroupId(ext.getFilterByGroupId().get())
                .autoLearnRunThreshold(ext.getAutoLearnRunThreshold().get())
                .autoLearnDiffThreshold(ext.getAutoLearnDiffThreshold().get())
                .optimizeEvery(ext.getAutoOptimizeEvery().get())
                .topN(resolveSelectTopN(project, ext))
                .randomM(resolveSelectRandomM(project, ext))
                .seed(ext.getSelectSeed().isPresent() ? ext.getSelectSeed().get() : null)
                .selectedFile(ext.getSelectedFile().get().getAsFile().toPath())
                .remainingFile(ext.getRemainingFile().get().getAsFile().toPath())
                .springContextGrouping(ext.getSpringContextGrouping().get())
                .selectiveLearn(resolveSelectiveLearn(project, ext))
                .alwaysLearn(resolveAlwaysLearn(project, ext))
                .verboseFile(ext.getVerboseFile().get() != null && !ext.getVerboseFile().get().isBlank()
                        ? Path.of(ext.getVerboseFile().get()) : null)
                .projectName(project.getName())
                .pluginVersion(VERSION)
                .log(wrapLog(project));
    }

    static PluginContext buildPluginContext(Project project, TestOrderExtension ext) {
        return buildPluginContextBuilder(project, ext).build();
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
                // Check Java source dirs first (most projects)
                for (File dir : main.getJava().getSrcDirs()) {
                    if (dir.isDirectory()) return dir.toPath();
                }
                // getAllSource() includes resources — exclude them explicitly
                java.util.Set<File> resources = main.getResources().getSrcDirs();
                for (File dir : main.getAllSource().getSourceDirectories()) {
                    if (!resources.contains(dir) && dir.isDirectory()) return dir.toPath();
                }
            }
        }
        // Fallback: check src/main/kotlin before src/main/java
        Path projectDir = project.getProjectDir().toPath();
        Path kotlinDir = projectDir.resolve("src/main/kotlin");
        if (kotlinDir.toFile().isDirectory()) return kotlinDir;
        return projectDir.resolve("src/main/java");
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
                // getAllSource() includes resources — exclude them explicitly
                java.util.Set<File> resources = test.getResources().getSrcDirs();
                for (File dir : test.getAllSource().getSourceDirectories()) {
                    if (!resources.contains(dir) && dir.isDirectory()) return dir.toPath();
                }
            }
        }
        // Fallback: check src/test/kotlin before src/test/java
        Path projectDir = project.getProjectDir().toPath();
        Path kotlinDir = projectDir.resolve("src/test/kotlin");
        if (kotlinDir.toFile().isDirectory()) return kotlinDir;
        return projectDir.resolve("src/test/java");
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

    /** Resolves the first test classes output directory for the project. */
    static Path resolveTestClassesDir(Project project) {
        SourceSetContainer sourceSets =
                project.getExtensions().findByType(SourceSetContainer.class);
        if (sourceSets != null) {
            SourceSet test = sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME);
            if (test != null) {
                for (File dir : test.getOutput().getClassesDirs()) {
                    if (dir.isDirectory()) return dir.toPath();
                }
            }
        }
        // Fallback
        return project.getProjectDir().toPath().resolve("build/classes/java/test");
    }

    /** Resolves the first main classes output directory for the project. */
    static Path resolveClassesDir(Project project) {
        SourceSetContainer sourceSets =
                project.getExtensions().findByType(SourceSetContainer.class);
        if (sourceSets != null) {
            SourceSet main = sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME);
            if (main != null) {
                for (File dir : main.getOutput().getClassesDirs()) {
                    if (dir.isDirectory()) return dir.toPath();
                }
            }
        }
        // No existing classes dir on disk yet (project not compiled, or unusual layout).
        // Returning null lets ChangeAnalysis skip bytecode detection silently —
        // see the Files.isDirectory gate at ChangeAnalysis.java:146.
        project.getLogger().info(
                "[test-order] no main classes dir found for project '{}'; "
                        + "bytecode change detection will be skipped this run",
                project.getName());
        return null;
    }

    /** Quotes a path string if it contains spaces (for javaagent arguments). */
    private static String quoteIfNeeded(String path) {
        return path.contains(" ") ? "\"" + path + "\"" : path;
    }

    /**
     * Returns true when the plugin should be skipped (DSL or system property).
     */
    private static boolean shouldSkip(Project project, TestOrderExtension ext) {
        if (ext.getSkip().get()) return true;
        String propSkip = gradleOrSystemProperty(project, "testorder.skip");
        return "true".equalsIgnoreCase(propSkip);
    }

    /** Converts a {@code Map<String, Object>} to {@code Map<String, String>} (null-safe). */
    private static Map<String, String> toStringMap(Map<String, Object> objectMap) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        if (objectMap != null) {
            for (var entry : objectMap.entrySet()) {
                if (entry.getValue() != null) {
                    result.put(entry.getKey(), entry.getValue().toString());
                }
            }
        }
        return result;
    }

    /**
     * Auto-aggregates .deps files into the index if it doesn't exist yet.
     * If the index exists but is unreadable/corrupt, tries to rebuild it from .deps.
     * Silently does nothing if there are no .deps files.
     */
    private static void autoAggregateIfNeeded(Project project, TestOrderExtension ext) {
        Path indexFile = ext.getIndexFile().get().getAsFile().toPath();
        if (!Files.exists(indexFile)) {
            aggregateDependencyFiles(project, ext, false);
            return;
        }

        try {
            DependencyMap.load(indexFile);
        } catch (IOException e) {
            project.getLogger().warn("[test-order] Existing index appears invalid at {} ({}). "
                    + "Attempting to rebuild from .deps files.", indexFile, e.getMessage());
            // Delete corrupt index so aggregation starts fresh instead of trying to load it
            try {
                Files.deleteIfExists(indexFile);
            } catch (IOException ignored) {
                // best-effort
            }
            boolean rebuilt = aggregateDependencyFiles(project, ext, false);
            if (rebuilt) {
                project.getLogger().lifecycle("[test-order] Rebuilt dependency index from .deps files: {}", indexFile);
                return;
            }

            if (recoverIndexFromBackup(project, ext, indexFile)) {
                return;
            }

            // All recovery attempts failed — throw immediately with a clear diagnostic
            // rather than silently proceeding, which would produce a confusing
            // "Index file not found" error in the downstream task.
            throw new GradleException("[test-order] Dependency index was corrupt and could not be"
                    + " recovered from backups or .deps files. Run tests in learn mode to regenerate: "
                    + indexFile);
        }
    }

    private static boolean recoverIndexFromBackup(Project project, TestOrderExtension ext, Path indexFile) {
        Path projectDir = project.getRootProject().getProjectDir().toPath();
        List<Path> candidates = List.of(
                indexFile.resolveSibling(indexFile.getFileName() + ".bak"),
                projectDir.resolve(".test-order/test-dependencies.lz4.bak"),
                projectDir.resolve("test-dependencies.lz4.bak")
        );

        for (Path candidate : candidates) {
            if (!Files.exists(candidate)) {
                continue;
            }
            try {
                Files.createDirectories(indexFile.getParent());
                Files.copy(candidate, indexFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                DependencyMap.load(indexFile);
                project.getLogger().lifecycle(
                        "[test-order] Recovered dependency index from backup: {} -> {}",
                        candidate, indexFile);
                return true;
            } catch (IOException copyOrLoadError) {
                project.getLogger().warn(
                        "[test-order] Backup index candidate is unusable ({}): {}",
                        candidate, copyOrLoadError.getMessage());
            }
        }

        project.getLogger().warn("[test-order] Could not recover dependency index from backups. "
                + "Run tests in learn mode to regenerate {}.", indexFile);
        return false;
    }

    /** Attempts to open the given URI in the default browser. */
    private static void tryOpenBrowser(java.net.URI uri) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(uri);
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean aggregateDependencyFiles(Project project, TestOrderExtension ext,
                                                    boolean failIfMissing) {
        Path depsDir = ext.getDepsDir().get().getAsFile().toPath();
        Path indexFile = ext.getIndexFile().get().getAsFile().toPath();

        // Process any fallback payload file from a previous run's failed shutdown hook
        try {
            if (me.bechberger.testorder.IndexCollectorServer.processFallbackFile(indexFile)) {
                project.getLogger().lifecycle("[test-order] Processed fallback collector payloads from previous run");
            }
        } catch (IOException e) {
            project.getLogger().warn("[test-order] Failed to process fallback payloads: {}", e.getMessage());
        }

        if (!Files.isDirectory(depsDir)) {
            if (failIfMissing) {
                throw new GradleException("[test-order] No deps directory at " + depsDir
                        + ". Run tests in learn mode first.");
            }
            project.getLogger().info("[test-order] No deps directory at {}, skipping aggregation", depsDir);
            return false;
        }
        try {
            // Use merging aggregation that preserves entries from other modules
            // (important when multiple subprojects share the same index file at root)
            DependencyMap.aggregateFromDepsDirectory(depsDir, indexFile, wrapLog(project));
            return true;
        } catch (IOException e) {
            if (failIfMissing) {
                throw new GradleException("Failed to aggregate dependency files", e);
            }
            project.getLogger().warn("[test-order] Failed to aggregate from .deps: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Snapshots source and test source file hashes for future SINCE_LAST_RUN
     * change detection. Also snapshots per-method hashes when method ordering is enabled.
     */
    private static void snapshotHashes(Project project, TestOrderExtension ext) {
        HashSnapshotOperation.snapshot(
                resolveMainSourceRoot(project),
                ext.getHashFile().get().getAsFile().toPath(),
                resolveTestSourceRoot(project),
                ext.getTestHashFile().get().getAsFile().toPath(),
                (label, path) -> project.getLogger().info("[test-order] Saved {} hash snapshot: {}", label, path),
                (label, msg) -> project.getLogger().warn("[test-order] Failed to save {} hash snapshot: {}", label, msg));
        if (ext.getMethodOrderingEnabled().get()) {
            ChangeDetectionOps.snapshotMethodHashes(
                    resolveTestSourceRoot(project),
                    ext.getMethodHashFile().get().getAsFile().toPath(),
                    wrapLog(project));
        }
    }

    private static void runDiagnostics(Project project, TestOrderExtension ext, boolean failOnError) {
        me.bechberger.testorder.ops.DiagnosticOperation.DiagnosticConfig config =
                new me.bechberger.testorder.ops.DiagnosticOperation.DiagnosticConfig(
                        project.getProjectDir().toPath().toAbsolutePath(),
                        ext.getIndexFile().get().getAsFile().toPath(),
                        ext.getStateFile().get().getAsFile().toPath(),
                        ext.getHashFile().get().getAsFile().toPath(),
                        ext.getTestHashFile().get().getAsFile().toPath(),
                        ext.getMethodHashFile().get().getAsFile().toPath(),
                        ext.getDepsDir().get().getAsFile().toPath(),
                        resolveTestSourceRoot(project),
                        ext.getChangeMode().get(),
                        wrapLog(project));

        me.bechberger.testorder.ops.DiagnosticOperation.DiagnosticReport report =
                me.bechberger.testorder.ops.DiagnosticOperation.diagnose(config);

        me.bechberger.testorder.ops.DiagnosticReportPrinter.print(report, wrapLog(project));

        if (failOnError && report.hasErrors()) {
            long errorCount = report.results().stream().filter(r -> r.isError()).count();
            throw new GradleException("[test-order] Diagnostic found " + errorCount
                    + " error(s). Fix issues or run with -Dtestorder.failOnError=false.");
        }
    }

    private static void runCompact(Project project, TestOrderExtension ext) {
        org.gradle.api.logging.Logger log = project.getLogger();
        try {
            log.lifecycle("");
            log.lifecycle("═══════════════════════════════════════════════════════════");
            log.lifecycle("[test-order] Compacting Index from .deps Files");
            log.lifecycle("═══════════════════════════════════════════════════════════");
            log.lifecycle("");

            me.bechberger.testorder.ops.IndexCompactionOperation.CompactionResult result =
                    me.bechberger.testorder.ops.IndexCompactionOperation.compact(
                            ext.getDepsDir().get().getAsFile().toPath(),
                            ext.getIndexFile().get().getAsFile().toPath(),
                            wrapLog(project));

            log.lifecycle("");
            log.lifecycle("Status: {}", result.description());
            if (result.hasChanges()) {
                log.lifecycle("  Added:   {} test classes", result.addedTests());
                log.lifecycle("  Removed: {} test classes", result.removedTests());
            }
            log.lifecycle("  Index Size: {} bytes", result.newIndexSize());
            log.lifecycle("");
            log.lifecycle("═══════════════════════════════════════════════════════════");
        } catch (Exception e) {
            throw new GradleException("[test-order] Failed to compact index: " + e.getMessage(), e);
        }
    }

    /** Returns the value of a Gradle Property if present, otherwise null. */
    private static Integer orNull(org.gradle.api.provider.Property<Integer> prop) {
        return prop.isPresent() ? prop.get() : null;
    }

    /** Reads a Gradle project property or system property (Gradle property wins). */
    static String gradleOrSystemProperty(Project project, String key) {
        if (project.hasProperty(key)) {
            Object val = project.property(key);
            return val != null ? val.toString() : null;
        }
        return System.getProperty(key);
    }

    /** Resolves changeMode from CLI property override or extension default. */
    private static String resolveChangeMode(Project project, TestOrderExtension ext) {
        String propChangeMode = gradleOrSystemProperty(project, "testorder.changeMode");
        if (propChangeMode != null && !propChangeMode.isBlank()) {
            return propChangeMode;
        }
        return ext.getChangeMode().get();
    }

    /** Resolves selectiveLearn from CLI property override or extension default. */
    private static boolean resolveSelectiveLearn(Project project, TestOrderExtension ext) {
        String prop = gradleOrSystemProperty(project, "testorder.learn.selective");
        if (prop != null && !prop.isBlank()) {
            return Boolean.parseBoolean(prop);
        }
        return ext.getSelectiveLearn().get();
    }

    /** Resolves alwaysLearn from CLI property override or extension default. */
    private static boolean resolveAlwaysLearn(Project project, TestOrderExtension ext) {
        String prop = gradleOrSystemProperty(project, "testorder.auto.alwaysLearn");
        if (prop != null && !prop.isBlank()) {
            return Boolean.parseBoolean(prop);
        }
        return ext.getAlwaysLearn().get();
    }

    private static void runShowReport(Project project, TestOrderExtension ext,
            String classes, String methods, String ml, boolean all, boolean explain,
            boolean fullNames, String format, String filter, int topN, int randomM, Long seed) {
        autoAggregateIfNeeded(project, ext);

        // Resolve auto flags (same logic as ShowMojo)
        boolean effectiveClasses = classes == null || classes.isBlank() || Boolean.parseBoolean(classes);
        Boolean effectiveMethods = resolveAutoFlag(methods);
        Boolean effectiveMl = resolveAutoFlag(ml);
        if (all) {
            effectiveMethods = Boolean.TRUE;
            effectiveMl = Boolean.TRUE;
        }

        PluginContext.Builder ctxBuilder = buildPluginContextBuilder(project, ext)
                .methodOrderingEnabled(effectiveMethods != null ? effectiveMethods : true);
        if (topN >= 0) ctxBuilder.topN(topN);
        if (randomM >= 0) ctxBuilder.randomM(randomM);
        if (seed != null) ctxBuilder.seed(seed);
        PluginContext pctx = ctxBuilder.build();
        ShowWorkflow.Options opts = new ShowWorkflow.Options(
                effectiveClasses, effectiveMethods, effectiveMl,
                explain, fullNames, format, filter, topN, randomM, seed);

        // Resolve ML history dir (same convention as Maven)
        Path mlHistoryDir = pctx.stateFile() != null
                ? pctx.stateFile().getParent().resolve("ml-history")
                : pctx.projectRoot().resolve(".test-order/ml-history");

        try {
            ShowWorkflow.ShowResult result = ShowWorkflow.compute(pctx, opts, mlHistoryDir);

            if (opts.isJson()) {
                System.out.println(me.bechberger.testorder.ops.workflows.ShowJsonFormatter.format(result, filter));
            } else {
                ShowWorkflow.printReport(System.out, result, opts, pctx);
            }
        } catch (IOException e) {
            throw new GradleException("Failed to compute show report: " + e.getMessage(), e);
        }
    }

    private static Boolean resolveAutoFlag(String value) {
        if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value)) {
            return null;
        }
        return Boolean.parseBoolean(value);
    }

    private static int parseIntOrDefault(String value, int defaultValue, String paramName) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new GradleException("[test-order] Invalid integer for " + paramName + ": " + value);
        }
    }

    private static Set<String> splitClasses(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private static Long parseLongOrNull(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new GradleException("[test-order] Invalid long for " + paramName + ": " + value);
        }
    }

    private static void runShowOrderReport(Project project, TestOrderExtension ext,
            boolean explain, boolean fullNames) {
        autoAggregateIfNeeded(project, ext);
        PluginContext pctx = buildPluginContext(project, ext);
        try {
            System.out.println(explain
                    ? "=== Predicted test execution order (explain) ==="
                    : "=== Predicted test execution order ===");
            ShowOrderWorkflow.printReportWithSelectionPreview(
                    pctx, System.out, explain, fullNames, false, false);
        } catch (IOException e) {
            throw new GradleException("Failed to show test order: " + e.getMessage(), e);
        }
    }

    private static void runShowMethodOrderReport(Project project, TestOrderExtension ext, boolean explain) {
        autoAggregateIfNeeded(project, ext);
        PluginContext pctx = buildPluginContextBuilder(project, ext)
                .methodOrderingEnabled(true)
                .build();
        try {
            ShowMethodOrderWorkflow.printReport(pctx, System.out, explain);
        } catch (IOException e) {
            throw new GradleException("Failed to show test method order", e);
        }
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
        if (project.getTasks().findByName("test") != null) {
            task.shouldRunAfter(project.getTasks().named("test"));
        }
        if (project.getTasks().findByName("testClasses") != null) {
            task.dependsOn(project.getTasks().named("testClasses"));
        }
        task.systemProperty("testorder.state.path",
                ext.getStateFile().get().getAsFile().getAbsolutePath());
        // Clear any task-level include patterns that were added by build conventions
        // (e.g. include("**/*Test.class")) so that applySelectedTests can override
        // the set of classes to run without being blocked by naming restrictions.
        task.doFirst("clearIncludes", t -> ((Test) t).setIncludes(java.util.Collections.emptySet()));
    }

    /**
     * Parity with Maven {@code AffectedMojo.autoAggregateOrFail}: when no index
     * exists, attempt to build one from available {@code .deps} files.
     *
     * <p>In multi-project builds, collects deps from ALL subproject build dirs
     * (not just the current project's) so that a root-level
     * {@code testOrderAffected} invocation works after a multi-module
     * {@code testOrderLearn} run without requiring an explicit
     * {@code testOrderAggregateAll} step.
     */
    private static void autoAggregateAffected(Project project, TestOrderExtension ext,
            Path indexPath, PluginLog log) {
        // Collect all candidate deps directories: current project + all other projects
        // in the build. In multi-project builds each subproject writes its .deps files
        // under its own build/test-order-deps/, so we must scan all of them.
        Set<Path> candidates = new LinkedHashSet<>();
        candidates.add(ext.getDepsDir().get().getAsFile().toPath());
        for (Project sub : project.getRootProject().getAllprojects()) {
            if (sub == project) continue;
            Path subDeps = sub.getLayout().getBuildDirectory().dir("test-order-deps").get()
                    .getAsFile().toPath();
            candidates.add(subDeps);
        }

        for (Path deps : candidates) {
            if (!Files.isDirectory(deps)) continue;
            try {
                AggregateOperation.Result agg = AggregateOperation.aggregate(deps, indexPath, log, true);
                if (agg.written()) {
                    log.info("[test-order] Auto-aggregated " + agg.depsFileCount() + " .deps files from "
                            + deps + " → " + indexPath);
                }
            } catch (IOException e) {
                log.warn("[test-order] Auto-aggregation from " + deps + " failed (ignored): " + e.getMessage());
            }
        }
    }

    /**
     * True when the build was invoked with an explicit {@code --tests} filter
     * targeting this task, or when {@code -Dtest=...} was set. Mirrors
     * {@code AffectedMojo}'s {@code -Dtest} skip-selection guard.
     */
    private static boolean hasUserTestFilter(Project project, Test task) {
        // In Gradle, users use --tests to filter tests, which targets the `test` task
        // directly — not testOrderAffected. So there is no equivalent of Maven's -Dtest=...
        // guard here. We keep the method for future extensibility but always return false.
        return false;
    }

    /**
     * Pin {@code maxParallelForks=1} and {@code forkEvery=0} so PriorityClassOrderer
     * can reorder selected classes within one TestPlan. Set
     * {@code -Dtestorder.affected.preserveForkConfig=true} to skip. Mirrors
     * {@code SurefireHelper.forceSingleForkForOrdering}.
     */
    private static void forceSingleForkForOrdering(Project project, Test task) {
        String preserve = gradleOrSystemProperty(project, "testorder.affected.preserveForkConfig");
        if (preserve != null && Boolean.parseBoolean(preserve)) {
            project.getLogger().lifecycle(
                    "[test-order] testorder.affected.preserveForkConfig=true — leaving Test maxParallelForks/forkEvery"
                    + " untouched. Selected tests may not execute in priority order if maxParallelForks>1 or forkEvery>0.");
            return;
        }
        int prevMax = task.getMaxParallelForks();
        long prevEvery = task.getForkEvery();
        boolean changedMax = prevMax != 1;
        boolean changedEvery = prevEvery != 0L;
        task.setMaxParallelForks(1);
        task.setForkEvery(0L);
        if (changedMax || changedEvery) {
            StringBuilder msg = new StringBuilder("[test-order] Overriding Test");
            if (changedMax) {
                msg.append(" maxParallelForks=").append(prevMax).append("→1");
            }
            if (changedEvery) {
                if (changedMax) {
                    msg.append(",");
                }
                msg.append(" forkEvery=").append(prevEvery).append("→0");
            }
            msg.append(" so PriorityClassOrderer can reorder selected classes within one JVM.");
            msg.append(" Set -Dtestorder.affected.preserveForkConfig=true to keep your config.");
            project.getLogger().lifecycle(msg.toString());
        }
    }

    private static void applySelectedTests(Test task, List<String> tests) {
        if (tests.isEmpty()) {
            task.filter(filter -> {
                filter.setFailOnNoMatchingTests(false);
                // Use a guaranteed non-matching pattern to produce a no-op test run
                // without mutating task execution conditions during doFirst.
                filter.includeTestsMatching("__testorder__no_selected_tests__");
            });
            return;
        }
        task.filter(filter -> {
            filter.setFailOnNoMatchingTests(false);
            for (String testClass : tests) {
                filter.includeTestsMatching(testClass);
            }
        });
    }

    private static void clearTierFile(Path tierFile, Project project) {
        try {
            if (Files.exists(tierFile)) {
                Files.writeString(tierFile, "");
            }
        } catch (IOException e) {
            project.getLogger().warn("[test-order] Could not clear tier file {}: {}", tierFile, e.getMessage());
        }
    }

    private static void ensureSupportedChangeMode(String changeMode) {
        try {
            ChangeDetectionSupport.normalizeMode(changeMode);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unknown changeMode: " + changeMode);
        }
    }

    private static int resolveSelectTopN(Project project, TestOrderExtension ext) {
        String override = gradleOrSystemProperty(project, "testorder.affected.topN");
        if (override != null && !override.isBlank()) {
            int value;
            try {
                value = Integer.parseInt(override);
            } catch (NumberFormatException e) {
                throw new GradleException("[test-order] Invalid value for testorder.affected.topN: '"
                        + override + "' (expected integer)");
            }
            if (value == 0) {
                throw new GradleException("[test-order] selectTopN=0 is invalid: it selects no top-scored tests, "
                        + "but new tests and @AlwaysRun tests are still included, which is confusing. "
                        + "Use selectTopN=-1 to select all change-affected tests, "
                        + "or selectTopN=N (N >= 1) to select the top N tests.");
            }
            return value;
        }
        return ext.getSelectTopN().get();
    }

    private static int resolveSelectRandomM(Project project, TestOrderExtension ext) {
        String override = gradleOrSystemProperty(project, "testorder.affected.randomM");
        if (override != null && !override.isBlank()) {
            try {
                return Integer.parseInt(override);
            } catch (NumberFormatException e) {
                throw new GradleException("[test-order] Invalid value for testorder.affected.randomM: '"
                        + override + "' (expected integer)");
            }
        }
        return ext.getSelectRandomM().get();
    }

    // -----------------------------------------------------------------------
    // Change detection helpers (called via reflection in tests)
    // -----------------------------------------------------------------------

    /**
     * Injects changed-class system properties into the test task.
     * Validates the change mode first; throws {@link IllegalArgumentException}
     * if the change mode is not supported.
     */
    private static void injectChangedClasses(Project project, TestOrderExtension ext, Test testTask) {
        ensureSupportedChangeMode(ext.getChangeMode().get());
        PluginContext pctx = buildPluginContext(project, ext);
        Set<String> changed = ChangeDetectionOps.detectChangedClassesWithKotlin(
                ext.getChangeMode().get(), pctx.projectRoot(), pctx.sourceRoot(),
                pctx.hashFile(), pctx.changedClasses(), true, wrapLog(project));
        if (!changed.isEmpty()) {
            testTask.systemProperty("testorder.changed.classes", String.join(",", changed));
        }
    }

    /**
     * Detects changed classes for the selection workflow.
     * Validates the change mode first; throws {@link IllegalArgumentException}
     * if the change mode is not supported.
     */
    private static Set<String> detectChangedClassesForSelection(Project project, TestOrderExtension ext) {
        ensureSupportedChangeMode(ext.getChangeMode().get());
        PluginContext pctx = buildPluginContext(project, ext);
        return ChangeDetectionOps.detectChangedClassesWithKotlin(
                ext.getChangeMode().get(), pctx.projectRoot(), pctx.sourceRoot(),
                pctx.hashFile(), pctx.changedClasses(), true, wrapLog(project));
    }

    // -----------------------------------------------------------------------
    // Property typo detection
    // -----------------------------------------------------------------------

    /**
     * Warn about testorder.* properties set via -D or -P that don't match any known key.
     */
    private static void warnUnknownProperties(Project project) {
        // Check system properties
        java.util.List<String> sysWarnings = me.bechberger.testorder.ops.PropertySuggestion
                .findUnknownKeys(System.getProperties().stringPropertyNames());
        for (String w : sysWarnings) {
            project.getLogger().warn("[test-order] " + w);
        }
        // Check Gradle project properties (-P)
        java.util.List<String> projWarnings = me.bechberger.testorder.ops.PropertySuggestion
                .findUnknownKeys(project.getProperties().keySet());
        for (String w : projWarnings) {
            project.getLogger().warn("[test-order] " + w);
        }
    }
}
