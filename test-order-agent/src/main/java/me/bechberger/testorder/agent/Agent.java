package me.bechberger.testorder.agent;

import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.jar.JarFile;

/**
 * Java agent entry point for test dependency recording.
 * Instruments class initializers/methods to track which classes are used during test execution.
 * <p>
 * Example agent string: {@code outputDir=target/test-order-deps,includePackages=com.example;org.app,mode=METHOD_ENTRY}
 */
@Command(name = "test-order-agent", description = "Java agent for test dependency recording",
        version = "0.1.0", mixinStandardHelpOptions = true)
public class Agent implements Callable<Integer> {

    public enum InstrumentationMode {
        /** Records which classes each test enters (method/constructor calls only, no field tracking).
         *  Lowest overhead (~66%), but only captures class-level dependencies. */
        METHOD_ENTRY,
        /** Records method/constructor entries plus foreign static-field accesses.
         *  Default mode. Keeps the low overhead of METHOD_ENTRY while still capturing
         *  class-level dependencies that happen via shared/static state. */
        FULL,
        /** Like FULL, but additionally tracks dependencies per test method instead of per test class.
         *  Setup/teardown calls are excluded — only the test method's own calls are captured.
         *  Enables method-level ordering via dependency overlap scoring. Overhead ~68%. */
        FULL_METHOD,
        /** Like FULL_METHOD, but also records member-level dependencies ({@code class#method},
         *  {@code class#field}) so scoring can pinpoint which specific members a test touches.
         *  Unlike FULL/FULL_METHOD, this mode instruments both instance and static foreign-field access.
         *  Enables precise impact analysis: a test that never calls the changed method won't be
         *  scored as affected. Highest overhead (~121%). */
        FULL_MEMBER
    }

    @Option(names = "--outputDir", description = "Directory for .deps files (default: ${DEFAULT-VALUE})",
            defaultValue = "target/test-order-deps")
    private Path outputDir = Path.of("target/test-order-deps");

    @Option(names = "--includePackages", description = "Semicolon-separated package prefixes to instrument",
            split = ";")
    private List<String> includePackages;

    @Option(names = "--excludePackages", description = "Semicolon-separated package prefixes to skip (optional)",
            split = ";")
    private List<String> excludePackages;

    @Option(names = "--filterStrategy", description = "Class filter strategy: WHITELIST, BLACKLIST, SMART, or WHITELIST_SMART (default: ${DEFAULT-VALUE})",
            defaultValue = "SMART")
    private IntelligentClassFilter.Strategy filterStrategy = IntelligentClassFilter.Strategy.SMART;

    @Option(names = "--skipTestClasses", description = "Skip instrumenting test classes (default: ${DEFAULT-VALUE})",
            defaultValue = "true")
    private boolean skipTestClasses = true;

    @Option(names = "--useHeuristics", description = "Use heuristics to skip generated/synthetic classes (default: ${DEFAULT-VALUE})",
            defaultValue = "true")
    private boolean useHeuristics = true;

    @Option(names = "--autoDetectPackages", description = "Automatically detect user packages from Maven/Gradle (default: ${DEFAULT-VALUE})",
            defaultValue = "true")
    private boolean autoDetectPackages = true;

    @Option(names = "--projectRoot", description = "Project root directory for auto-detection (default: current directory)")
    private Path projectRoot = Path.of(System.getProperty("user.dir"));

    @Option(names = "--mode", description = "Instrumentation mode: METHOD_ENTRY, FULL, FULL_METHOD, or FULL_MEMBER (default: ${DEFAULT-VALUE})",
            defaultValue = "FULL")
    private InstrumentationMode mode = InstrumentationMode.FULL;

    @Option(names = "--indexFile", description = "Binary dependency index file path (default: ${DEFAULT-VALUE})",
            defaultValue = "test-dependencies.lz4")
    private Path indexFile = Path.of("test-dependencies.lz4");

    @Option(names = "--verboseFile", description = "File path for verbose agent logging (disabled if not set)")
    private Path verboseFile;

    @Override
    public Integer call() {
        return 0;
    }

    // ── Option parsing ────────────────────────────────────────────────

    public static Agent parse(String agentArgs) {
        Agent agent = new Agent();
        if (agentArgs == null || agentArgs.isBlank()) {
            return agent;
        }
        FemtoCli.runAgent(agent, agentArgs);
        return agent;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public List<String> getIncludePackages() {
        return includePackages == null ? Collections.emptyList() : includePackages;
    }

    public List<String> getExcludePackages() {
        return excludePackages == null ? Collections.emptyList() : excludePackages;
    }

    public IntelligentClassFilter.Strategy getFilterStrategy() {
        return filterStrategy;
    }

    public boolean isSkipTestClasses() {
        return skipTestClasses;
    }

    public boolean isUseHeuristics() {
        return useHeuristics;
    }

    public boolean isAutoDetectPackages() {
        return autoDetectPackages;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public InstrumentationMode getMode() {
        return mode;
    }

    public Path getIndexFile() {
        return indexFile;
    }

    public Path getVerboseFile() {
        return verboseFile;
    }

    // ── Agent entry point ─────────────────────────────────────────────

    public static void premain(String agentArgs, Instrumentation inst) {
        Agent options = Agent.parse(agentArgs);

        // ensure output directory exists
        Path outputDir = options.getOutputDir();
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output directory: " + outputDir, e);
        }

        // system properties for TelemetryListener (on system classpath, not accessible via UsageStore)
        System.setProperty("testorder.learn", "true");
        System.setProperty("testorder.instrumentation.mode", options.getMode().name());

        // extract and add runtime jar to bootstrap classpath
        try {
            inst.appendToBootstrapClassLoaderSearch(new JarFile(getExtractedJARPath().toFile()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to add runtime jar to bootstrap classpath", e);
        }

        // configure AgentLogger if verbose file is set (must be before UsageStore config so it can log)
        if (options.getVerboseFile() != null) {
            try {
                Class<?> loggerClass = Class.forName(
                        "me.bechberger.testorder.agent.runtime.AgentLogger", true, null);
                loggerClass.getMethod("setVerboseFile", String.class)
                        .invoke(null, options.getVerboseFile().toAbsolutePath().toString());
            } catch (Exception e) {
                System.err.println("[test-order] Failed to configure AgentLogger: " + e.getMessage());
            }
        }

        // configure UsageStore via reflection (now on bootstrap classpath)
        try {
            Class<?> usageStoreClass = Class.forName(
                    "me.bechberger.testorder.agent.runtime.UsageStore", true, null);
            Object instance = usageStoreClass.getMethod("getInstance").invoke(null);
            usageStoreClass.getMethod("setOutputDir", String.class)
                    .invoke(instance, outputDir.toAbsolutePath().toString());
            usageStoreClass.getMethod("setIndexFile", String.class)
                    .invoke(instance, options.getIndexFile().toAbsolutePath().toString());
                boolean methodLevel = options.getMode() == InstrumentationMode.FULL_METHOD
                    || options.getMode() == InstrumentationMode.FULL_MEMBER;
                usageStoreClass.getMethod("setMethodLevelRecordingEnabled", boolean.class)
                    .invoke(instance, methodLevel);
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure UsageStore", e);
        }

        inst.addTransformer(new ClassTransformer(options), true);
    }

    private static Path getExtractedJARPath() throws IOException {
        try (InputStream in = Agent.class.getClassLoader().getResourceAsStream("test-order-runtime.jar")) {
            if (in == null) {
                throw new RuntimeException("Could not find test-order-runtime.jar");
            }
            File file = File.createTempFile("test-order-runtime", ".jar");
            file.deleteOnExit();
            Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return file.toPath().toAbsolutePath();
        }
    }
}
