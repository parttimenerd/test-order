package me.bechberger.testorder.plugin;

import me.bechberger.testorder.TestOrderState;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;

/**
 * Prepares the test execution environment by configuring Surefire for either
 * learn mode (agent attachment, reuseForks=false) or order mode (ClassOrderer injection).
 */
@Mojo(name = "prepare", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class PrepareMojo extends AbstractTestOrderMojo {

    /**
     * Operation mode:
     * <ul>
     *   <li><b>learn</b> – always instrument and build the dependency index.</li>
     *   <li><b>order</b> – always run in priority order; warns if new test classes are found
     *       that are not yet in the index.</li>
     *   <li><b>auto</b> (default) – runs learn mode automatically when the dependency index
     *       is missing or when compiled test classes are detected that are not yet indexed;
     *       otherwise runs in order mode.</li>
     * </ul>
     */
    @Parameter(property = "testorder.mode", defaultValue = "auto")
    private String mode;

    /** Comma-separated additional package prefixes to instrument (merged with auto-detected source packages) */
    @Parameter(property = "testorder.includePackages")
    private String includePackages;

    /** When true (default) and no source packages are detected, fall back to the project groupId
     *  as an instrumentation filter. Source packages from src/main/java are always auto-detected. */
    @Parameter(property = "testorder.filterByGroupId", defaultValue = "true")
    private boolean filterByGroupId;

    /** Instrumentation mode: FULL (default), METHOD_ENTRY, FULL_METHOD, or FULL_MEMBER */
    @Parameter(property = "testorder.instrumentationMode", defaultValue = "FULL")
    private String instrumentationMode;

    /** Score bonus for new test classes not in the dependency index */
    @Parameter(property = "testorder.score.newTest", defaultValue = "15")
    private int scoreNewTest;

    /** Score bonus for test classes whose source was modified */
    @Parameter(property = "testorder.score.changedTest", defaultValue = "9")
    private int scoreChangedTest;

    /** Maximum score bonus from failure frequency */
    @Parameter(property = "testorder.score.maxFailure", defaultValue = "5")
    private int scoreMaxFailure;

    /** Score bonus for tests with below-median duration */
    @Parameter(property = "testorder.score.speed", defaultValue = "1")
    private int scoreSpeed;

    /** Score penalty for tests with above-median duration */
    @Parameter(property = "testorder.score.speedPenalty", defaultValue = "1")
    private int scoreSpeedPenalty;

    /** Max score from dependency overlap (ratio-based) */
    @Parameter(property = "testorder.score.depOverlap", defaultValue = "5")
    private int scoreDepOverlap;

    /** Score bonus based on change complexity of overlapping dependencies */
    @Parameter(property = "testorder.score.changeComplexity", defaultValue = "2")
    private int scoreChangeComplexity;

    /** Optional fixed bonus when a test overlaps changed static field members */
    @Parameter(property = "testorder.score.staticFieldBonus", defaultValue = "0")
    private int scoreStaticFieldBonus;

    /** Set-cover coverage bonus weight (0 = disabled, uses depOverlap instead) */
    @Parameter(property = "testorder.score.coverageBonus", defaultValue = "0")
    private int scoreCoverageBonus;

    /** Auto mode: switch to learn periodically after this many order-mode runs (0 = disabled). Ensures index stays fresh. */
    @Parameter(property = "testorder.autoLearnRunThreshold", defaultValue = "0")
    private int autoLearnRunThreshold;

    /** Auto mode: switch to learn when changed-class count reaches this threshold (0 = disabled). */
    @Parameter(property = "testorder.autoLearnDiffThreshold", defaultValue = "0")
    private int autoLearnDiffThreshold;

    private static final Set<String> VALID_MODES = Set.of("auto", "learn", "order");
    private static final Set<String> VALID_INSTR_MODES = Set.of("METHOD_ENTRY", "FULL", "FULL_METHOD", "FULL_MEMBER");
    private static final Set<String> VALID_CHANGE_MODES = Set.of("auto", "since-last-run", "since-last-commit", "uncommitted", "explicit");

    @Override
    public void execute() throws MojoExecutionException {
        initContext();

        if (!VALID_MODES.contains(mode)) {
            throw new MojoExecutionException("Invalid mode '" + mode + "'. Valid values: " + VALID_MODES);
        }
        if (!VALID_INSTR_MODES.contains(instrumentationMode.toUpperCase())) {
            throw new MojoExecutionException("Invalid instrumentationMode '" + instrumentationMode + "'. Valid values: " + VALID_INSTR_MODES);
        }
        if (!VALID_CHANGE_MODES.contains(changeMode)) {
            throw new MojoExecutionException("Invalid changeMode '" + changeMode + "'. Valid values: " + VALID_CHANGE_MODES);
        }
        Path idxPath = resolveIndexPath();

        if ("learn".equals(mode)) {
            // explicit learn mode — always instrument
            switchToLearnMode();
            return;
        }

        // For both "order" and "auto": ensure we have an aggregated index if only .deps files exist
        if (!Files.exists(idxPath)) {
            Path depsDirPath = ctx.resolveDepsDir(depsDir);
            if (Files.isDirectory(depsDirPath) && hasDepsFiles(depsDirPath)) {
                getLog().info("[test-order] No index found but .deps files exist — auto-aggregating.");
                autoAggregate(depsDirPath, idxPath);
            }
        }

        if (!Files.exists(idxPath)) {
            if ("auto".equals(mode)) {
                getLog().info("[test-order] No dependency index found — switching to learn mode automatically.");
                switchToLearnMode();
            } else {
                getLog().info("[test-order] No index found and mode is 'order' — skipping.");
            }
            return;
        }

        // Index exists — check for new test classes and auto-learn thresholds
        boolean shouldAutoLearn = false;
        Set<String> changedTestsNow = detectChangedTestClasses();
        Set<String> newTests = new java.util.LinkedHashSet<>(findNewTestClasses(idxPath));
        // Only treat changed test sources as "new" to avoid repeatedly flagging old/non-runnable compiled test classes.
        newTests.retainAll(changedTestsNow);
        if (!newTests.isEmpty()) {
            shouldAutoLearn = true;
            String names = newTests.stream().sorted().limit(5)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            if (newTests.size() > 5) names += " (... " + (newTests.size() - 5) + " more)";
            if ("auto".equals(mode)) {
                getLog().info("[test-order] New test class(es) detected: " + names
                        + " — switching to learn mode automatically.");
            } else {
                getLog().warn("[test-order] New test class(es) not yet in the dependency index: " + names);
                getLog().warn("[test-order] Run 'mvn test -Dtestorder.mode=learn' to index them for accurate ordering.");
            }
        }

        // Check auto-learn run threshold in auto mode
        if (!shouldAutoLearn && "auto".equals(mode) && autoLearnRunThreshold > 0) {
            TestOrderState state = loadState();
            int runsSince = state.runsSinceLearn();
            if (runsSince >= autoLearnRunThreshold) {
                getLog().info("[test-order] Run count since last learn (" + runsSince
                        + ") reached threshold (" + autoLearnRunThreshold + ")"
                        + " — switching to learn mode automatically to refresh index.");
                shouldAutoLearn = true;
            }
        }

        if (!shouldAutoLearn && "auto".equals(mode) && autoLearnDiffThreshold > 0) {
            Set<String> changedNow = detectChangedClasses();
            if (changedNow.size() >= autoLearnDiffThreshold) {
                getLog().info("[test-order] Changed-class count (" + changedNow.size()
                        + ") reached threshold (" + autoLearnDiffThreshold + ")"
                        + " — switching to learn mode automatically to refresh index.");
                shouldAutoLearn = true;
            }
        }

        if (shouldAutoLearn && "auto".equals(mode)) {
            switchToLearnMode();
            return;
        }

        executeOrderMode();
    }

    private void switchToLearnMode() throws MojoExecutionException {
        String effectiveInclude = resolveIncludePackages(includePackages, filterByGroupId, project, getLog());
        configureLearnMode(instrumentationMode, effectiveInclude, true);
        TestOrderState state = loadState();
        state.resetRunsSinceLearn();
        try {
            state.save(ctx.resolveStateFile(stateFile));
        } catch (IOException e) {
            getLog().warn("[test-order] Could not reset runsSinceLearn: " + e.getMessage());
        }
    }

    private void executeOrderMode() throws MojoExecutionException {
        getLog().info("[test-order] Order mode: injecting PriorityClassOrderer");

        Set<String> changed = detectChangedClasses(false);
        if (changed.isEmpty()) {
            getLog().info("[test-order] No changed classes detected — running tests in default order.");
        } else {
            getLog().info("[test-order] Changed classes: " + changed);
        }

        Set<String> changedTests = detectChangedTestClasses(false);
        if (!changedTests.isEmpty()) {
            getLog().info("[test-order] Changed test classes: " + changedTests);
        }

        Set<String> changedMethods = Set.of();
        if (methodOrderingEnabled) {
            changedMethods = detectChangedMethods();
            if (!changedMethods.isEmpty()) {
                getLog().info("[test-order] Changed test methods: " + changedMethods);
            }
        }

        Map<String, Integer> scores = Map.of(
                "newTest", scoreNewTest,
                "changedTest", scoreChangedTest,
                "maxFailure", scoreMaxFailure,
                "speed", scoreSpeed,
                "speedPenalty", scoreSpeedPenalty,
                "depOverlap", scoreDepOverlap,
                "changeComplexity", scoreChangeComplexity,
                "staticFieldBonus", scoreStaticFieldBonus,
                "coverageBonus", scoreCoverageBonus);

        writeOrdererConfig(changed, changedTests, changedMethods, scores);
    }
}