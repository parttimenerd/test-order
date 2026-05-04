package me.bechberger.testorder.gradle;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.PersistenceSupport;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestSelector;
import me.bechberger.testorder.changes.ChangeDetectionSupport;
import me.bechberger.testorder.changes.HashSnapshotSupport;
import org.gradle.api.GradleException;
import me.bechberger.testorder.ops.AggregateOperation;
import me.bechberger.testorder.ops.CleanOperation;
import me.bechberger.testorder.ops.ChangeDetectionOps;
import me.bechberger.testorder.ops.DashboardServerOperation;
import me.bechberger.testorder.ops.DumpOperation;
import me.bechberger.testorder.ops.ExportJsonOperation;
import me.bechberger.testorder.ops.HashSnapshotOperation;
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
import me.bechberger.testorder.ops.workflows.ShowOrderWorkflow;
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
        project.afterEvaluate(p -> configureTestTasks(p, ext, agentConf,
                learnModeConfigurator, orderModeConfigurator));

        // Multi-project: register aggregate task that collects deps from all subprojects
        if (!project.getSubprojects().isEmpty()) {
            project.getTasks().register("testOrderAggregateAll", task -> {
                task.setGroup("test-order");
                task.setDescription(
                        "Aggregate .deps files from all subprojects into a shared dependency index");
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
                                    AggregateOperation.Result result =
                                            AggregateOperation.aggregate(subDeps, indexFile, plog);
                                    if (result.written()) anyWritten = true;
                                } catch (IOException e) {
                                    plog.warn("[test-order] Failed to aggregate from " + sub.getName()
                                            + ": " + e.getMessage());
                                }
                            }
                        }
                    }
                    // Also aggregate the root project's own deps
                    if (aggregateDependencyFiles(project, ext, false)) {
                        anyWritten = true;
                    }
                    if (anyWritten) {
                        plog.info("[test-order] Multi-project aggregation complete: " + indexFile);
                    } else {
                        plog.warn("[test-order] No .deps files found in any subproject. "
                                + "Run tests in learn mode first.");
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

    private void warnJUnit4Unsupported(Project project) {
        if (isJUnit4OnTestClasspath(project)) {
            if (isJUnit5OnTestClasspath(project)) {
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
     */
    private void warnConflictingJUnitConfig(Project project, Test testTask) {
        Map<String, Object> sysProps = testTask.getSystemProperties();

        // M4: Check for listener deactivation that would silence TelemetryListener
        Object deactivate = sysProps.get("junit.platform.execution.listeners.deactivate");
        if (deactivate != null) {
            String val = deactivate.toString();
            if (val.contains("*") || val.toLowerCase().contains("telemetry")) {
                project.getLogger().warn("[test-order] junit.platform.execution.listeners.deactivate={} "
                        + "may disable TelemetryListener — test-order will not record any data.", val);
            }
        }

        // M12: Check for competing orderer via auto-detection
        Object autoDetect = sysProps.get("junit.jupiter.extensions.autodetection.enabled");
        if ("true".equalsIgnoreCase(String.valueOf(autoDetect))) {
            project.getLogger().warn("[test-order] junit.jupiter.extensions.autodetection.enabled=true — "
                    + "a third-party ClassOrderer/MethodOrderer on the classpath could override "
                    + "test-order's PriorityClassOrderer/PriorityMethodOrderer.");
        }

        // M20: Check for conflicting global method orderer
        Object methodOrderer = sysProps.get("junit.jupiter.testmethod.order.default");
        if (methodOrderer != null && !methodOrderer.toString().contains("PriorityMethodOrderer")) {
            project.getLogger().warn("[test-order] junit.jupiter.testmethod.order.default={} "
                    + "overrides test-order's PriorityMethodOrderer for classes without "
                    + "explicit @TestMethodOrder.", methodOrderer);
        }

        // L6: Check for user's junit-platform.properties in src/test/resources
        Path userProps = project.getProjectDir().toPath()
                .resolve("src/test/resources/junit-platform.properties");
        if (Files.exists(userProps)) {
            try {
                String content = Files.readString(userProps);
                if (content.contains("junit.jupiter.testclass.order.default")
                        || content.contains("junit.jupiter.testmethod.order.default")) {
                    project.getLogger().warn("[test-order] src/test/resources/junit-platform.properties "
                            + "contains orderer configuration that may conflict with test-order. "
                            + "System properties set by test-order take precedence.");
                }
                if (content.contains("junit.platform.execution.listeners.deactivate")) {
                    project.getLogger().warn("[test-order] src/test/resources/junit-platform.properties "
                            + "contains listener deactivation config — this may disable "
                            + "test-order's TelemetryListener.");
                }
            } catch (IOException ignored) {
                // best-effort check
            }
        }
    }

    /**
     * Warns when class-level parallel execution is configured in learn mode,
     * which would corrupt dependency tracking (C1/M24 equivalent for Gradle).
     */
    private void warnParallelInLearnMode(Project project, Test testTask) {
        Map<String, Object> sysProps = testTask.getSystemProperties();

        // Check JUnit Jupiter class-level parallel
        Object parallelEnabled = sysProps.get("junit.jupiter.execution.parallel.enabled");
        Object classesDefault = sysProps.get("junit.jupiter.execution.parallel.mode.classes.default");
        if ("true".equalsIgnoreCase(String.valueOf(parallelEnabled))
                && "concurrent".equalsIgnoreCase(String.valueOf(classesDefault))) {
            project.getLogger().warn("[test-order] Class-level parallel execution "
                    + "(mode.classes.default=concurrent) is not supported in learn mode — "
                    + "it would corrupt dependency tracking.");
        }

        // Check Vintage parallel
        Object vintageParallel = sysProps.get("junit.vintage.execution.parallel.enabled");
        if ("true".equalsIgnoreCase(String.valueOf(vintageParallel))) {
            project.getLogger().warn("[test-order] JUnit Vintage parallel execution is not "
                    + "supported in learn mode — it would corrupt dependency tracking.");
        }

        // Check for junit-platform.properties in src/test/resources
        Path userProps = project.getProjectDir().toPath()
                .resolve("src/test/resources/junit-platform.properties");
        if (Files.exists(userProps)) {
            try {
                String content = Files.readString(userProps);
                if (content.contains("junit.jupiter.execution.parallel.mode.classes.default=concurrent")
                        || content.contains("junit.vintage.execution.parallel.enabled=true")) {
                    project.getLogger().warn("[test-order] src/test/resources/junit-platform.properties "
                            + "contains parallel configuration that may conflict with learn mode "
                            + "dependency tracking.");
                }
            } catch (IOException ignored) {
                // best-effort
            }
        }
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
        if (propMode != null) mode = propMode;

        PluginContext pctx = buildPluginContext(project, ext);
        Path projectDir = project.getProjectDir().toPath();
        File indexFile = ext.getIndexFile().getAsFile().get();

        Runnable ciDownload = () -> {
            if (me.bechberger.testorder.ci.CiConfigParser.configExistsIn(projectDir)) {
                me.bechberger.testorder.ci.CiDepDownloadManager
                        .downloadIfConfigured(projectDir, indexFile.toPath())
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
        project.getLogger().lifecycle("[test-order] Configuring learn mode for task :{}",
                testTask.getName());

        // Warn about parallel config that corrupts dependency tracking in learn mode
        warnParallelInLearnMode(project, testTask);

        // Resolve effective instrumentation mode: CLI property overrides extension DSL
        String instrMode = ext.getInstrumentationMode().get();
        String propInstrMode = gradleOrSystemProperty(project, "testorder.instrumentation.mode");
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

            String agentArgs = me.bechberger.testorder.AgentArgsBuilder.buildArgs(
                    Path.of(depsDirPath), instrumentationMode,
                    Path.of(indexFilePath), includePackages, verboseFile);

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
            // Emit the warning at most once per project regardless of how many test
            // tasks (test, dockerTest, intTest, …) trigger this method.
            if (!project.getExtensions().getExtraProperties().has("testorder.indexMissingWarned")) {
                project.getExtensions().getExtraProperties().set("testorder.indexMissingWarned", Boolean.TRUE);
                project.getLogger().info("[test-order] Index file {} not found — skipping order mode. "
                        + "Run with -Dtestorder.mode=learn first.", indexFile);
            }
            return;
        }
        project.getLogger().lifecycle("[test-order] Configuring order mode for task :{}",
                testTask.getName());

        // M4/M12/M20/L6: Warn about conflicting JUnit Platform configurations
        warnConflictingJUnitConfig(project, testTask);

        // Inject PriorityClassOrderer as the JUnit 5 global class orderer
        testTask.systemProperty("junit.jupiter.testclass.order.default",
                "me.bechberger.testorder.PriorityClassOrderer");

        // Forward debug flag so PriorityClassOrderer can emit verbose scoring output
        String debugFlag = gradleOrSystemProperty(project, "testorder.debug");
        if ("true".equalsIgnoreCase(debugFlag)) {
            testTask.systemProperty("testorder.debug", "true");
        }

        // Set well-known properties directly at configuration time so tests and
        // tools that inspect task properties without executing the task see them.
        testTask.systemProperty("testorder.index.path", indexFile.getAbsolutePath());
        String changeMode = ext.getChangeMode().get();
        if (changeMode != null && !changeMode.isBlank()) {
            testTask.systemProperty("testorder.changeMode", changeMode);
        }
        if (ext.getMethodOrderingEnabled().get()) {
            testTask.systemProperty("testorder.methodOrder.enabled", "true");
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
        if (ext.getScoreDepOverlap().isPresent()) {
            testTask.systemProperty("testorder.score.depOverlap",
                    String.valueOf(ext.getScoreDepOverlap().get()));
        }
        if (ext.getScoreCoverageBonus().isPresent()) {
            testTask.systemProperty("testorder.score.coverageBonus",
                    String.valueOf(ext.getScoreCoverageBonus().get()));
        }
        String weightsFile = ext.getWeightsFile().get();
        if (weightsFile != null && !weightsFile.isBlank()) {
            testTask.systemProperty("testorder.weights.file",
                    Path.of(weightsFile).toAbsolutePath().toString());
        }

        // All config (including change detection) runs at execution time
        testTask.doFirst("configureOrderViaWorkflow", t -> {
            PluginContext pctx = buildPluginContext(project, ext);

            // Validate explicit changed classes against the index
            if ("explicit".equalsIgnoreCase(ext.getChangeMode().get())) {
                try {
                    DependencyMap depMap = DependencyMap.load(pctx.indexFile());
                    Set<String> changed = pctx.changedClasses() != null
                            ? Set.of(pctx.changedClasses().split(","))
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
                throw new GradleException("Failed to load test-order state", e);
            }
            OrderWorkflow.OrderSetupResult result;
            try {
                result = OrderWorkflow.setup(pctx, state);
            } catch (IOException e) {
                throw new GradleException("Failed to set up test ordering", e);
            }

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
                Path projectDir = project.getProjectDir().toPath();
                Path indexFile = ext.getIndexFile().get().getAsFile().toPath();
                java.util.Optional<Path> result =
                        me.bechberger.testorder.ci.CiDepDownloadManager
                                .downloadIfConfigured(projectDir, indexFile);
                if (result.isPresent()) {
                    project.getLogger().lifecycle("[test-order] CI index written to {}", result.get());
                } else {
                    throw new GradleException(
                            "[test-order] CI download failed. Ensure .test-order/download-config.yml exists "
                            + "and required tokens are set.");
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

        project.getTasks().register("testOrderOptimize", task -> {
            task.setGroup("test-order");
            task.setDescription("Optimise scoring weights from the recorded run history");
            task.doLast(t -> {
                Path statePath = ext.getStateFile().get().getAsFile().toPath();
                if (!Files.exists(statePath)) {
                    throw new GradleException("[test-order] State file not found: " + statePath
                            + ". Run some test-order test runs first.");
                }
                try {
                    OptimizeOperation.run(statePath, msg -> project.getLogger().lifecycle(msg));
                } catch (IOException e) {
                    throw new GradleException("Failed to optimise: " + e.getMessage(), e);
                }
            });
        });

        project.getTasks().register("testOrderSelect", Test.class, task -> {
            configureDerivedTestTask(project, ext, task);
            task.setGroup("test-order");
            task.setDescription("Run the prioritized selected subset of tests and write remaining tests to disk");

            // When autoRunRemaining is true (default), automatically run remaining tests after
            boolean runRemaining = ext.getAutoRunRemaining().get();
            String propRunRemaining = gradleOrSystemProperty(project, "testorder.auto.runRemaining");
            if (propRunRemaining != null) {
                runRemaining = Boolean.parseBoolean(propRunRemaining);
            }
            if (runRemaining) {
                task.finalizedBy("testOrderRunRemaining");
            }

            // Inject PriorityClassOrderer so ordering works within the subset
            task.systemProperty("junit.jupiter.testclass.order.default",
                    "me.bechberger.testorder.PriorityClassOrderer");
            String debugFlag = gradleOrSystemProperty(project, "testorder.debug");
            if ("true".equalsIgnoreCase(debugFlag)) {
                task.systemProperty("testorder.debug", "true");
            }

            task.doFirst("testOrderSelectAndOrder", t -> {
                PluginContext pctx = buildPluginContext(project, ext);
                try {
                    AutoWorkflow.Result result = new AutoWorkflow(
                            pctx, "order", null, null).execute();

                    if (result instanceof AutoWorkflow.Result.OrderSelect os) {
                        TestSelector.Selection selection = os.selection();
                        applySelectedTests((Test) t, selection.selected());
                        project.getLogger().lifecycle(
                                "[test-order] Selected {} tests, deferred {}",
                                selection.selected().size(),
                                selection.remaining().size());

                        // Apply orderer config as system properties
                        for (var entry : os.ordererConfigMap().entrySet()) {
                            ((Test) t).systemProperty(
                                    entry.getKey(), entry.getValue());
                        }
                    } else {
                        throw new GradleException(
                                "[test-order] Expected order mode but got: "
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
                List<Path> files = List.of(
                        ext.getIndexFile().get().getAsFile().toPath(),
                        ext.getStateFile().get().getAsFile().toPath(),
                        ext.getHashFile().get().getAsFile().toPath(),
                        ext.getTestHashFile().get().getAsFile().toPath(),
                        ext.getMethodHashFile().get().getAsFile().toPath());
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
                int port = Integer.parseInt(Optional
                        .ofNullable(gradleOrSystemProperty(project, "testorder.dashboard.port"))
                        .orElse("0"));
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
                serveDashboard(project, outDir, statePath, port);
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
                String propThreshold = gradleOrSystemProperty(project, "coverage.threshold");
                if (propThreshold != null && !propThreshold.isBlank()) {
                    threshold = Integer.parseInt(propThreshold);
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
            task.doLast(t -> runDiagnostics(project, ext));
        });

        project.getTasks().register("testOrderCompact", task -> {
            task.setGroup("test-order");
            task.setDescription("Rebuild index by compacting .deps files (removes stale entries)");
            task.doLast(t -> runCompact(project, ext));
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
        try {
            String template = DashboardResources.assembleTemplate();
            return new DashboardWorkflow(pctx, template, outDir).generate();
        } catch (IOException e) {
            throw new GradleException("Failed to generate test-order dashboard", e);
        }
    }

    /** Starts a local HTTP server serving the self-contained dashboard HTML. */
    private void serveDashboard(Project project, Path dashboardDir, Path statePath, int port) {
        Path htmlPath = dashboardDir.resolve("index.html");
        try {
            DashboardServerOperation.start(htmlPath, statePath, port, wrapLog(project));
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

        String weightsFile = ext.getWeightsFile().get();
        Map<String, Integer> scoreOverrides = WeightResolverOperation.buildScoreOverrides(
                orNull(ext.getScoreNewTest()), orNull(ext.getScoreChangedTest()),
                orNull(ext.getScoreMaxFailure()), orNull(ext.getScoreSpeed()),
                orNull(ext.getScoreSpeedPenalty()), orNull(ext.getScoreDepOverlap()),
                orNull(ext.getScoreChangeComplexity()), orNull(ext.getScoreStaticFieldBonus()),
                orNull(ext.getScoreCoverageBonus()));

        String explicitChanged = ext.getChangedClasses().get();
        String propChanged = gradleOrSystemProperty(project, "testorder.changed.classes");
        String changedClasses = null;
        if (propChanged != null && !propChanged.isBlank()) {
            changedClasses = propChanged;
        } else if (explicitChanged != null && !explicitChanged.isBlank()) {
            changedClasses = explicitChanged;
        }

        return PluginContext.builder()
                .projectRoot(project.getProjectDir().toPath().toAbsolutePath())
                .sourceRoot(sourceRoot)
                .testSourceRoot(testSourceRoot)
                .additionalSourceRoots(additionalSourceRoots)
                .testClassesDir(resolveTestClassesDir(project))
                .indexFile(ext.getIndexFile().get().getAsFile().toPath())
                .stateFile(ext.getStateFile().get().getAsFile().toPath())
                .depsDir(ext.getDepsDir().get().getAsFile().toPath())
                .hashFile(ext.getHashFile().get().getAsFile().toPath())
                .testHashFile(ext.getTestHashFile().get().getAsFile().toPath())
                .methodHashFile(ext.getMethodHashFile().get().getAsFile().toPath())
                .changeMode(ext.getChangeMode().get())
                .changedClasses(changedClasses)
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
                .verboseFile(ext.getVerboseFile().get() != null && !ext.getVerboseFile().get().isBlank()
                        ? Path.of(ext.getVerboseFile().get()) : null)
                .projectName(project.getName())
                .pluginVersion("gradle")
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

    /**
     * Auto-aggregates .deps files into the index if it doesn't exist yet.
     * Silently does nothing if there are no .deps files.
     */
    private static void autoAggregateIfNeeded(Project project, TestOrderExtension ext) {
        Path indexFile = ext.getIndexFile().get().getAsFile().toPath();
        if (!Files.exists(indexFile)) {
            aggregateDependencyFiles(project, ext, false);
        }
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
        if (!Files.isDirectory(depsDir)) {
            if (failIfMissing) {
                throw new GradleException("[test-order] No deps directory at " + depsDir
                        + ". Run tests in learn mode first.");
            }
            project.getLogger().info("[test-order] No deps directory at {}, skipping aggregation", depsDir);
            return false;
        }
        try {
            AggregateOperation.Result result = AggregateOperation.aggregate(depsDir, indexFile,
                    wrapLog(project));
            return result.written();
        } catch (IOException e) {
            throw new GradleException("Failed to aggregate dependency files", e);
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

    private static void runDiagnostics(Project project, TestOrderExtension ext) {
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

        // Print report
        System.out.println("");
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("[test-order] Diagnostic Report");
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("");
        System.out.println("Health Score: " + report.healthScore() + "%  " + report.overallStatus());
        System.out.println("");
        System.out.println("Checks Performed: " + report.results().size());
        System.out.println("  Errors: " + report.results().stream()
                .filter(r -> r.isError()).count());
        System.out.println("  Warnings: " + report.results().stream()
                .filter(r -> r.isInformational() && !r.isSuccess()).count());
        System.out.println("");

        // Print individual results
        for (var result : report.results()) {
            if (result.isError()) {
                System.out.println("❌ " + result.code() + ": " + result.message());
                for (String suggestion : result.suggestions()) {
                    System.out.println("   → " + suggestion);
                }
            } else if (result.isInformational()) {
                System.out.println("⚠️  " + result.code() + ": " + result.message());
                if (!result.suggestions().isEmpty()) {
                    for (String suggestion : result.suggestions()) {
                        System.out.println("   → " + suggestion);
                    }
                }
            } else {
                System.out.println("✓ " + result.code().getMessage());
            }
            System.out.println("");
        }

        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("");

        // Print summary
        System.out.println("Summary:");
        for (var entry : report.summary().entrySet()) {
            System.out.println("  " + entry.getKey() + ": " + entry.getValue());
        }
        System.out.println("");

        if (!report.isHealthy()) {
            project.getLogger().warn("[test-order] Diagnostic detected issues. Review above and take action if needed.");
        } else {
            project.getLogger().info("[test-order] Setup looks good! ✓");
        }
    }

    private static void runCompact(Project project, TestOrderExtension ext) {
        try {
            System.out.println("");
            System.out.println("═══════════════════════════════════════════════════════════");
            System.out.println("[test-order] Compacting Index from .deps Files");
            System.out.println("═══════════════════════════════════════════════════════════");
            System.out.println("");

            me.bechberger.testorder.ops.IndexCompactionOperation.CompactionResult result =
                    me.bechberger.testorder.ops.IndexCompactionOperation.compact(
                            ext.getDepsDir().get().getAsFile().toPath(),
                            ext.getIndexFile().get().getAsFile().toPath(),
                            wrapLog(project));

            System.out.println("");
            System.out.println("Status: " + result.description());
            if (result.hasChanges()) {
                System.out.println("  Added:   " + result.addedTests() + " test classes");
                System.out.println("  Removed: " + result.removedTests() + " test classes");
            }
            System.out.println("  Index Size: " + result.newIndexSize() + " bytes");
            System.out.println("");
            System.out.println("═══════════════════════════════════════════════════════════");
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
            throw new GradleException("Failed to show test order", e);
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
        task.jvmArgs("--enable-native-access=ALL-UNNAMED");
        task.shouldRunAfter(project.getTasks().named("test"));
        task.dependsOn(project.getTasks().named("testClasses"));
        task.systemProperty("testorder.state.path",
                ext.getStateFile().get().getAsFile().getAbsolutePath());
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

    /**
     * Snapshots a single source directory to the given hash file, waiting for
     * the hash-file lock before writing.
     *
     * @param sourceRoot root directory to scan
     * @param hashFile   output hash file (also used as the lock target)
     * @param label      human-readable label for log messages
     * @param project    Gradle project (used for logging)
     */
    private static void snapshotSingleDir(Path sourceRoot, Path hashFile, String label, Project project) {
        try {
            PersistenceSupport.withFileLock(hashFile, () -> {
                HashSnapshotSupport.snapshotDirectory(sourceRoot, hashFile);
                return null;
            });
        } catch (IOException e) {
            project.getLogger().warn("[test-order] Failed to save {} hash snapshot: {}", label, e.getMessage());
        }
    }
}
