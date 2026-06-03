package me.bechberger.testorder.gradle;

import me.bechberger.testorder.ops.PluginLog;
import me.bechberger.testorder.ops.detection.JUnitXmlParser;
import me.bechberger.testorder.ops.detection.TestRunner;
import me.bechberger.testorder.ops.detection.TestRunnerSupport;

import org.gradle.api.logging.Logger;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * {@link TestRunner} implementation that executes tests via Gradle in a subprocess.
 * Uses the {@code FixedOrderClassOrderer} to control class execution order precisely.
 * Parses JUnit XML reports from {@code build/test-results/test/} to determine pass/fail status.
 * <p>
 * Mirrors the MavenTestRunner design — subprocess-based to avoid contaminating
 * the host Gradle daemon with test classpath artifacts.
 */
class GradleTestRunner implements TestRunner {

    private final Path projectDir;
    private final Logger log;
    private final Path reportDir;
    private final Path runtimeDir;
    private final TestRunnerSupport support;

    /** Deadline (epoch millis) after which subprocesses should be killed. */
    private volatile long deadlineMillis = Long.MAX_VALUE;

    /** Track whether we've already warned about deadline-killed processes. */
    private boolean deadlineKillWarningShown = false;

    /** Sentinel exit-code returned by {@link #drainAndAwait} when the deadline was exceeded. */
    private static final int DEADLINE_EXCEEDED = Integer.MIN_VALUE;

    GradleTestRunner(Path projectDir, Logger log) {
        this.projectDir = projectDir;
        this.log = log;
        this.reportDir = projectDir.resolve("build/test-results/test");
        this.runtimeDir = projectDir.resolve("build/test-order-detect-runtime");
        PluginLog pluginLog = new PluginLog() {
            @Override public void info(String m) { log.info(m); }
            @Override public void warn(String m) { log.warn(m); }
            @Override public void debug(String m) { log.debug(m); }
        };
        this.support = new TestRunnerSupport(runtimeDir, reportDir, pluginLog);
    }

    @Override
    public void setDeadline(long deadlineMillis) {
        this.deadlineMillis = deadlineMillis;
    }

    @Override
    public boolean deadlineExceeded() {
        return System.currentTimeMillis() > deadlineMillis;
    }

    @Override
    public boolean supportsMethodOrdering() {
        return true;
    }

    @Override
    public boolean supportsLearnPhase() {
        return true;
    }

    @Override
    public boolean runLearnPhase(String instrumentationMode) {
        return runLearnPhase(instrumentationMode, null);
    }

    @Override
    public boolean runLearnPhase(String instrumentationMode, Path targetIndexFile) {
        log.lifecycle("[test-order] Running learn phase with instrumentation mode: " + instrumentationMode);

        List<String> command = new ArrayList<>(List.of(
                gradleCommand(),
                "test",
                "-Dtestorder.mode=learn",
                "-Dtestorder.instrumentation.mode=" + instrumentationMode,
                "--no-daemon", "--quiet"));

        if (targetIndexFile != null) {
            // targetIndexFile must be forwarded to the forked test JVM via an init
            // script — Gradle's -D flags only reach the Gradle process, not the JVM.
            try {
                Path initScript = writeInitScript(
                        "testorder.index.path", targetIndexFile.toAbsolutePath().toString());
                command.add("--init-script");
                command.add(initScript.toAbsolutePath().toString());
            } catch (IOException e) {
                log.warn("[test-order] Could not write init script for targetIndexFile: " + e.getMessage());
            }
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectDir.toFile());
        pb.redirectErrorStream(true);

        try {
            Process proc = pb.start();
            try {
                Deque<String> learnOutputLines = new ArrayDeque<>(50);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.debug(line);
                        if (learnOutputLines.size() >= 50) {
                            learnOutputLines.pollFirst();
                        }
                        learnOutputLines.addLast(line);
                    }
                }
                int exitCode = proc.waitFor();
                if (exitCode == 0) {
                    log.lifecycle("[test-order] Learn phase completed successfully.");
                    return true;
                } else {
                    log.warn("[test-order] Learn phase failed (exit code " + exitCode + ")");
                    log.warn("[test-order] Last output lines from learn phase:");
                    for (String outputLine : learnOutputLines) {
                        log.warn("  " + outputLine);
                    }
                    return false;
                }
            } catch (InterruptedException e) {
                proc.destroyForcibly();
                Thread.currentThread().interrupt();
                log.warn("[test-order] Learn phase interrupted");
                return false;
            }
        } catch (IOException e) {
            log.warn("[test-order] Learn phase failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public TestRunResult run(List<String> testOrder) {
        if (testOrder.isEmpty()) {
            return new TestRunResult(List.of(), Set.of(), Set.of());
        }

        boolean runAll = testOrder.size() == 1 && "*".equals(testOrder.get(0));

        Process proc = null;
        try {
            Path orderFile = writeOrderFile(testOrder);
            support.setupRuntimeConfig(runAll);
            support.cleanReports();

            // Write an init script that configures the test task in the subprocess
            // to add runtimeDir to the classpath (for junit-platform.properties)
            // and forward the order file path as a system property to the forked JVM.
            // Gradle's -D flags only set system properties on the Gradle process, NOT
            // on the forked test JVM — so an init script is the correct mechanism.
            // testorder.mode=skip must also be forwarded via the init script so the
            // telemetry listener in the forked JVM does not corrupt the state file
            // with artificial data from detection probe runs.
            Path initScript = writeInitScript(Map.of(
                    "testorder.fixed.order.file", orderFile.toAbsolutePath().toString(),
                    "testorder.mode", "skip"));

            List<String> command = new ArrayList<>(List.of(
                    gradleCommand(), "test",
                    "--no-daemon", "--quiet",
                    "--init-script", initScript.toAbsolutePath().toString()));

            if (!runAll) {
                for (String fqcn : testOrder) {
                    command.add("--tests");
                    command.add(fqcn);
                }
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);

            proc = pb.start();
            int exitCode = drainAndAwait(proc, "test-order-output-drain");
            if (exitCode == DEADLINE_EXCEEDED) {
                return new TestRunResult(testOrder, Set.of(), new HashSet<>(testOrder));
            }

            support.logSubprocessExitIfNeeded(exitCode);

            return JUnitXmlParser.parseClassResults(reportDir, runAll ? List.of() : testOrder,
                    true, JUnitXmlParser.MissingReportPolicy.FAIL);
        } catch (InterruptedException e) {
            if (proc != null) proc.destroyForcibly();
            Thread.currentThread().interrupt();
            return new TestRunResult(testOrder, Set.of(), new HashSet<>(testOrder));
        } catch (IOException e) {
            log.warn("Test execution failed: " + e.getMessage());
            return new TestRunResult(testOrder, Set.of(), new HashSet<>(testOrder));
        }
    }

    @Override
    public MethodRunResult runMethods(String testClass, List<String> methodOrder) {
        if (methodOrder.isEmpty()) {
            return new MethodRunResult(testClass, List.of(), Set.of(), Set.of());
        }

        try {
            Path methodOrderFile = writeMethodOrderFile(methodOrder);
            support.setupRuntimeConfigForMethods();
            support.cleanReports();

            Path initScript = writeInitScript(Map.of(
                    "testorder.fixed.method.order.file", methodOrderFile.toAbsolutePath().toString(),
                    "testorder.mode", "skip"));

            List<String> command = new ArrayList<>(List.of(
                    gradleCommand(), "test",
                    "--tests", testClass,
                    "--no-daemon", "--quiet",
                    "--init-script", initScript.toAbsolutePath().toString()));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            int exitCode = drainAndAwait(proc, "test-order-method-output-drain");
            if (exitCode == DEADLINE_EXCEEDED) {
                return new MethodRunResult(testClass, methodOrder, Set.of(), new HashSet<>(methodOrder));
            }
            support.logSubprocessExitIfNeeded(exitCode);

            return JUnitXmlParser.parseMethodResults(reportDir, testClass, methodOrder,
                    JUnitXmlParser.MissingReportPolicy.FAIL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new MethodRunResult(testClass, methodOrder, Set.of(), new HashSet<>(methodOrder));
        } catch (IOException e) {
            log.warn("Method-level test execution failed: " + e.getMessage());
            return new MethodRunResult(testClass, methodOrder, Set.of(), new HashSet<>(methodOrder));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Drains stdout from {@code proc} on a background thread while enforcing the
     * deadline. Returns the process exit code, or {@link #DEADLINE_EXCEEDED} if the
     * time budget ran out (the process is force-killed in that case).
     */
    private int drainAndAwait(Process proc, String threadName) throws InterruptedException {
        Thread outputDrainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    support.captureOutputLine(line);
                }
            } catch (IOException ignored) {
            }
        }, threadName);
        outputDrainer.setDaemon(true);
        outputDrainer.start();

        long remaining = deadlineMillis - System.currentTimeMillis();
        if (remaining <= 0) {
            proc.destroyForcibly();
            outputDrainer.join(2000);
            warnDeadlineOnce("[test-order] Time budget expired — skipping remaining test runs.");
            return DEADLINE_EXCEEDED;
        }
        boolean finished = proc.waitFor(remaining, TimeUnit.MILLISECONDS);
        if (!finished) {
            proc.destroyForcibly();
            outputDrainer.join(2000);
            warnDeadlineOnce("[test-order] Time budget expired mid-run — killed subprocess. "
                    + "Use partial results collected so far.");
            return DEADLINE_EXCEEDED;
        }
        outputDrainer.join(2000);
        return proc.exitValue();
    }

    private void warnDeadlineOnce(String message) {
        if (!deadlineKillWarningShown) {
            deadlineKillWarningShown = true;
            log.warn(message);
        }
    }

    /** Resolve the Gradle wrapper command (prefer wrapper in project, fall back to gradle). */
    private String gradleCommand() {
        Path wrapperBat = projectDir.resolve("gradlew.bat");
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                && Files.exists(wrapperBat)) {
            return wrapperBat.toAbsolutePath().toString();
        }
        Path wrapper = projectDir.resolve("gradlew");
        if (Files.isExecutable(wrapper)) {
            return wrapper.toAbsolutePath().toString();
        }
        return "gradle";
    }

    private Path writeOrderFile(List<String> testOrder) throws IOException {
        Path orderFile = projectDir.resolve("build/test-order-detect.txt");
        Files.createDirectories(orderFile.getParent());
        Files.writeString(orderFile, String.join("\n", testOrder));
        return orderFile;
    }

    private Path writeMethodOrderFile(List<String> methodOrder) throws IOException {
        Path orderFile = projectDir.resolve("build/test-order-method-detect.txt");
        Files.createDirectories(orderFile.getParent());
        Files.writeString(orderFile, String.join("\n", methodOrder));
        return orderFile;
    }

    /**
     * Write a Gradle init script that configures the test task in the subprocess to:
     * 1. Add runtimeDir to the test classpath (so junit-platform.properties is found)
     * 2. Set the given system properties on the forked test JVM
     * <p>
     * Gradle's {@code -D} flags only set properties on the Gradle process, NOT on the
     * forked test JVM. An init script with {@code test.systemProperty()} is the correct
     * mechanism to forward properties to the forked JVM.
     */
    private Path writeInitScript(String sysPropKey, String sysPropValue) throws IOException {
        return writeInitScript(Map.of(sysPropKey, sysPropValue));
    }

    private Path writeInitScript(Map<String, String> sysProps) throws IOException {
        Files.createDirectories(runtimeDir);
        Path initScript = runtimeDir.resolve("detect-init.gradle");
        String escapedRuntimeDir = runtimeDir.toAbsolutePath().toString()
                .replace("\\", "/").replace("'", "\\'");
        StringBuilder sb = new StringBuilder();
        sb.append("allprojects {\n");
        sb.append("    tasks.withType(Test).configureEach {\n");
        sb.append("        classpath += files('").append(escapedRuntimeDir).append("')\n");
        for (Map.Entry<String, String> entry : sysProps.entrySet()) {
            String escapedKey = entry.getKey().replace("'", "\\'");
            String escapedValue = entry.getValue().replace("\\", "/").replace("'", "\\'");
            sb.append("        systemProperty '").append(escapedKey).append("', '").append(escapedValue).append("'\n");
        }
        sb.append("    }\n");
        sb.append("}\n");
        Files.writeString(initScript, sb.toString());
        return initScript;
    }
}
