package me.bechberger.testorder.gradle;

import me.bechberger.testorder.ops.detection.TestRunner;

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

    /** Circular buffer of last N lines of subprocess output for diagnostics. */
    private static final int OUTPUT_BUFFER_SIZE = 50;
    private final Deque<String> lastOutputLines = new ArrayDeque<>(OUTPUT_BUFFER_SIZE);

    /** Track whether we've already shown subprocess failure output. */
    private boolean firstFailureOutputShown = false;

    /** Deadline (epoch millis) after which subprocesses should be killed. */
    private volatile long deadlineMillis = Long.MAX_VALUE;

    /** Track whether we've already warned about deadline-killed processes. */
    private boolean deadlineKillWarningShown = false;

    GradleTestRunner(Path projectDir, Logger log) {
        this.projectDir = projectDir;
        this.log = log;
        this.reportDir = projectDir.resolve("build/test-results/test");
        this.runtimeDir = projectDir.resolve("build/test-order-detect-runtime");
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
        log.lifecycle("[test-order] Running learn phase with instrumentation mode: " + instrumentationMode);

        List<String> command = new ArrayList<>(List.of(
                gradleCommand(),
                "test",
                "-Dtestorder.mode=learn",
                "-Dtestorder.instrumentation.mode=" + instrumentationMode,
                "--no-daemon", "--quiet"));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectDir.toFile());
        pb.redirectErrorStream(true);

        try {
            Process proc = pb.start();
            try {
                Deque<String> learnOutputLines = new ArrayDeque<>(OUTPUT_BUFFER_SIZE);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.debug(line);
                        if (learnOutputLines.size() >= OUTPUT_BUFFER_SIZE) {
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
            // Write the order file for FixedOrderClassOrderer to read
            Path orderFile = writeOrderFile(testOrder);

            // Set up junit-platform.properties to register the FixedOrderClassOrderer
            setupRuntimeConfig(runAll);

            // Clean previous reports
            cleanReports();

            // Write an init script that configures the test task in the subprocess
            // to add runtimeDir to the classpath (for junit-platform.properties)
            // and forward the order file path as a system property to the forked JVM.
            // Gradle's -D flags only set system properties on the Gradle process, NOT
            // on the forked test JVM — so an init script is the correct mechanism.
            Path initScript = writeInitScript(
                    "testorder.fixed.order.file", orderFile.toAbsolutePath().toString());

            // Build the Gradle command
            List<String> command = new ArrayList<>(List.of(
                    gradleCommand(), "test",
                    "--no-daemon", "--quiet",
                    "--init-script", initScript.toAbsolutePath().toString(),
                    // Skip test-order ordering in the subprocess (we control order ourselves)
                    "-Dtestorder.mode=skip"));

            if (!runAll) {
                // Gradle test filtering: --tests 'pkg.ClassName'
                for (String fqcn : testOrder) {
                    command.add("--tests");
                    command.add(fqcn);
                }
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);

            proc = pb.start();
            final Process runProc = proc;

            // Capture output in a background thread (so main thread can enforce deadline)
            Thread outputDrainer = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(runProc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        captureOutputLine(line);
                    }
                } catch (IOException ignored) {
                    // Process was killed — expected
                }
            }, "test-order-output-drain");
            outputDrainer.setDaemon(true);
            outputDrainer.start();

            // Wait for process with deadline enforcement
            int exitCode;
            long remaining = deadlineMillis - System.currentTimeMillis();
            if (remaining <= 0) {
                proc.destroyForcibly();
                outputDrainer.join(2000);
                if (!deadlineKillWarningShown) {
                    deadlineKillWarningShown = true;
                    log.warn("[test-order] Time budget expired — skipping remaining test runs.");
                }
                return new TestRunResult(testOrder, Set.of(), new HashSet<>(testOrder));
            }
            boolean finished = proc.waitFor(remaining, TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                outputDrainer.join(2000);
                if (!deadlineKillWarningShown) {
                    deadlineKillWarningShown = true;
                    log.warn("[test-order] Time budget expired mid-run — killed subprocess. "
                            + "Use partial results collected so far.");
                }
                return new TestRunResult(testOrder, Set.of(), new HashSet<>(testOrder));
            }
            exitCode = proc.exitValue();
            outputDrainer.join(2000);

            if (exitCode != 0) {
                if (!firstFailureOutputShown) {
                    firstFailureOutputShown = true;
                    log.warn("[test-order] Subprocess exited with code " + exitCode);
                    log.warn("[test-order] Last output lines:");
                    synchronized (lastOutputLines) {
                        for (String outputLine : lastOutputLines) {
                            log.warn("  " + outputLine);
                        }
                    }
                    log.warn("[test-order] (Subsequent subprocess failures during detection "
                            + "are expected and will not be repeated)");
                } else {
                    log.debug("[test-order] Subprocess exited with code " + exitCode
                            + " (expected during OD detection)");
                }
            }

            return parseResults(runAll ? List.of() : testOrder);
        } catch (InterruptedException e) {
            proc.destroyForcibly();
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
            setupRuntimeConfigForMethods();
            cleanReports();

            // Use init script to forward method order file path to the forked test JVM
            Path initScript = writeInitScript(
                    "testorder.fixed.method.order.file", methodOrderFile.toAbsolutePath().toString());

            List<String> command = new ArrayList<>(List.of(
                    gradleCommand(), "test",
                    "--tests", testClass,
                    "--no-daemon", "--quiet",
                    "--init-script", initScript.toAbsolutePath().toString(),
                    "-Dtestorder.mode=skip"));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            Thread outputDrainer = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        captureOutputLine(line);
                    }
                } catch (IOException ignored) {}
            }, "test-order-method-output-drain");
            outputDrainer.setDaemon(true);
            outputDrainer.start();

            try {
                long remaining = deadlineMillis - System.currentTimeMillis();
                if (remaining <= 0) {
                    proc.destroyForcibly();
                    outputDrainer.join(2000);
                    return new MethodRunResult(testClass, methodOrder, Set.of(), new HashSet<>(methodOrder));
                }
                boolean finished = proc.waitFor(remaining, TimeUnit.MILLISECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    outputDrainer.join(2000);
                    return new MethodRunResult(testClass, methodOrder, Set.of(), new HashSet<>(methodOrder));
                }
                outputDrainer.join(2000);

                return parseMethodResults(testClass, methodOrder);
            } catch (InterruptedException e) {
                proc.destroyForcibly();
                Thread.currentThread().interrupt();
                return new MethodRunResult(testClass, methodOrder, Set.of(), new HashSet<>(methodOrder));
            }
        } catch (IOException e) {
            log.warn("Method-level test execution failed: " + e.getMessage());
            return new MethodRunResult(testClass, methodOrder, Set.of(), new HashSet<>(methodOrder));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Resolve the Gradle wrapper command (prefer wrapper in project, fall back to gradle). */
    private String gradleCommand() {
        // Check for gradlew.bat on Windows first
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

    private void setupRuntimeConfig(boolean runAll) throws IOException {
        Files.createDirectories(runtimeDir);
        Path junitProps = runtimeDir.resolve("junit-platform.properties");
        if (!runAll) {
            Files.writeString(junitProps,
                    "junit.jupiter.testclass.order.default="
                            + "me.bechberger.testorder.junit.FixedOrderClassOrderer\n");
        } else {
            Files.deleteIfExists(junitProps);
        }
    }

    private void setupRuntimeConfigForMethods() throws IOException {
        Files.createDirectories(runtimeDir);
        Path junitProps = runtimeDir.resolve("junit-platform.properties");
        Files.writeString(junitProps,
                "junit.jupiter.testmethod.order.default="
                        + "me.bechberger.testorder.junit.FixedOrderMethodOrderer\n");
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
     * 2. Set the given system property on the forked test JVM
     * <p>
     * Gradle's {@code -D} flags only set properties on the Gradle process, NOT on the
     * forked test JVM. An init script with {@code test.systemProperty()} is the correct
     * mechanism to forward properties to the forked JVM.
     */
    private Path writeInitScript(String sysPropKey, String sysPropValue) throws IOException {
        Files.createDirectories(runtimeDir);
        Path initScript = runtimeDir.resolve("detect-init.gradle");
        // Escape backslashes and single quotes for Groovy string literals
        String escapedRuntimeDir = runtimeDir.toAbsolutePath().toString()
                .replace("\\", "/").replace("'", "\\'");
        String escapedValue = sysPropValue
                .replace("\\", "/").replace("'", "\\'");
        Files.writeString(initScript,
                "allprojects {\n"
                + "    tasks.withType(Test).configureEach {\n"
                + "        classpath += files('" + escapedRuntimeDir + "')\n"
                + "        systemProperty '" + sysPropKey + "', '" + escapedValue + "'\n"
                + "    }\n"
                + "}\n");
        return initScript;
    }

    private void cleanReports() throws IOException {
        if (Files.exists(reportDir)) {
            try (var files = Files.list(reportDir)) {
                files.filter(p -> p.toString().endsWith(".xml")).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }

    private void captureOutputLine(String line) {
        synchronized (lastOutputLines) {
            if (lastOutputLines.size() >= OUTPUT_BUFFER_SIZE) {
                lastOutputLines.pollFirst();
            }
            lastOutputLines.addLast(line);
        }
    }

    // ── Report parsing ───────────────────────────────────────────────
    // JUnit XML format is the same as Surefire — shared parsing logic.

    private TestRunResult parseResults(List<String> executionOrder) {
        Set<String> passed = new HashSet<>();
        Set<String> failed = new HashSet<>();

        if (!Files.exists(reportDir)) {
            return new TestRunResult(executionOrder, Set.of(), new HashSet<>(executionOrder));
        }

        try (var files = Files.list(reportDir)) {
            files.filter(p -> p.getFileName().toString().startsWith("TEST-")
                            && p.getFileName().toString().endsWith(".xml"))
                    .forEach(report -> parseReport(report, passed, failed));
        } catch (IOException e) {
            log.warn("Could not read test reports: " + e.getMessage());
        }

        // Tests not mentioned in reports → treat as failed (crash/OOM/System.exit)
        for (String test : executionOrder) {
            if (!passed.contains(test) && !failed.contains(test)) {
                failed.add(test);
            }
        }

        return new TestRunResult(executionOrder, passed, failed);
    }

    private void parseReport(Path report, Set<String> passed, Set<String> failed) {
        try {
            String content = Files.readString(report);
            // Prefer 'classname' from <testcase> elements (FQCN) over 'name' from
            // <testsuite> (which may be a simple name in some Gradle versions).
            String className = extractFirstTestCaseClassname(content);
            if (className == null) {
                // Fall back to <testsuite name="..."> (typically the FQCN)
                className = extractAttribute(content, "name");
            }
            if (className == null) return;

            int totalTests = parseIntSafe(extractAttribute(content, "tests"));
            int failCount = parseIntSafe(extractAttribute(content, "failures"))
                    + parseIntSafe(extractAttribute(content, "errors"));
            int skipped = parseIntSafe(extractAttribute(content, "skipped"));
            int effective = totalTests - skipped;

            // A class is "passing" if at least one method passes (#49).
            if (failCount > 0 && effective > 0 && failCount >= effective) {
                failed.add(className);
            } else {
                passed.add(className);
            }
        } catch (IOException e) {
            // Skip unreadable reports
        }
    }

    private MethodRunResult parseMethodResults(String testClass, List<String> methodOrder) {
        Set<String> passed = new HashSet<>();
        Set<String> failed = new HashSet<>();

        if (!Files.exists(reportDir)) {
            return new MethodRunResult(testClass, methodOrder, Set.of(), new HashSet<>(methodOrder));
        }

        try (var files = Files.list(reportDir)) {
            files.filter(p -> p.getFileName().toString().startsWith("TEST-")
                            && p.getFileName().toString().endsWith(".xml"))
                    .forEach(report -> parseMethodReport(report, testClass, passed, failed));
        } catch (IOException e) {
            log.warn("Could not read test reports for method parsing: " + e.getMessage());
        }

        for (String method : methodOrder) {
            if (!passed.contains(method) && !failed.contains(method)) {
                // Methods not mentioned in reports → treat as failed
                // (crash/OOM/System.exit), consistent with class-level parsing
                failed.add(method);
            }
        }

        return new MethodRunResult(testClass, methodOrder, passed, failed);
    }

    private void parseMethodReport(Path report, String testClass, Set<String> passed, Set<String> failed) {
        try {
            String content = Files.readString(report);
            int idx = 0;
            while (true) {
                int tcStart = content.indexOf("<testcase ", idx);
                if (tcStart < 0) break;
                int tcEnd = content.indexOf(">", tcStart);
                if (tcEnd < 0) break;

                String tag = content.substring(tcStart, tcEnd + 1);

                // Filter by testClass to avoid picking up stale reports from other classes
                String className = extractAttributeFromTag(tag, "classname");
                if (className != null && !testClass.equals(className)
                        && !testClass.endsWith("." + className)) {
                    idx = tcEnd + 1;
                    continue;
                }

                String methodName = extractAttributeFromTag(tag, "name");

                if (methodName != null) {
                    int parenIdx = methodName.indexOf('(');
                    if (parenIdx > 0) {
                        methodName = methodName.substring(0, parenIdx);
                    }

                    boolean hasFail = false;
                    if (!tag.endsWith("/>")) {
                        int nextClose = content.indexOf("</testcase>", tcEnd);
                        if (nextClose > 0) {
                            String body = content.substring(tcEnd, nextClose);
                            hasFail = body.contains("<failure") || body.contains("<error");
                            idx = nextClose + "</testcase>".length();
                        } else {
                            idx = tcEnd + 1;
                        }
                    } else {
                        idx = tcEnd + 1;
                    }

                    if (hasFail) {
                        failed.add(methodName);
                    } else {
                        passed.add(methodName);
                    }
                } else {
                    idx = tcEnd + 1;
                }
            }
        } catch (IOException e) {
            // Skip unreadable reports
        }
    }

    // ── XML utilities ────────────────────────────────────────────────

    private static String extractAttribute(String xml, String attr) {
        // Search for ' attr="' (space-prefixed) to avoid matching as suffix
        // of another attribute (e.g. 'name' inside 'classname')
        String prefix = " " + attr + "=\"";
        int start = xml.indexOf(prefix);
        if (start < 0) return null;
        start += prefix.length();
        int end = xml.indexOf('"', start);
        if (end < 0) return null;
        return xml.substring(start, end);
    }

    private static String extractAttributeFromTag(String tag, String attr) {
        // Space-prefix to avoid matching as suffix (e.g. 'name' inside 'classname')
        String prefix = " " + attr + "=\"";
        int start = tag.indexOf(prefix);
        if (start < 0) return null;
        start += prefix.length();
        int end = tag.indexOf('"', start);
        if (end < 0) return null;
        return tag.substring(start, end);
    }

    /**
     * Extracts the FQCN from the first {@code <testcase classname="...">} element.
     * Gradle XML reports always include 'classname' on testcase elements with the
     * fully-qualified class name, while the testsuite 'name' may be a simple name.
     */
    private static String extractFirstTestCaseClassname(String xml) {
        int tcStart = xml.indexOf("<testcase ");
        if (tcStart < 0) return null;
        int tcEnd = xml.indexOf(">", tcStart);
        if (tcEnd < 0) return null;
        String tag = xml.substring(tcStart, tcEnd + 1);
        return extractAttributeFromTag(tag, "classname");
    }

    private static int parseIntSafe(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
