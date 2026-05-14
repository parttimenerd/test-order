package me.bechberger.testorder.gradle;

import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/**
 * Extension DSL for the test-order Gradle plugin.
 * <p>
 * Usage in build.gradle:
 * <pre>
 * testOrder {
 *     mode = "auto"               // auto | learn | order | optimize | skip
 *     instrumentationMode = "FULL" // METHOD_ENTRY | FULL | FULL_METHOD | FULL_MEMBER
 *     includePackages = ""         // comma-separated package prefixes (auto-detected if empty)
 *     changeMode = "auto"          // auto | since-last-run | since-last-commit | uncommitted | explicit
 * }
 * </pre>
 */
public abstract class TestOrderExtension {

    // ---- Mode ----

    /**
     * Operational mode: auto, learn, order, optimize, or skip.
     * <p>
     * Resolution precedence (highest wins):
     * <ol>
     *   <li>CLI system property: {@code -Dtestorder.mode=learn}</li>
     *   <li>Gradle project property: {@code -Ptestorder.mode=learn}</li>
     *   <li>Per-task override: {@code -Dtestorder.mode.integrationTest=order}</li>
     *   <li>This DSL property</li>
     *   <li>Auto-detection (order if index exists, learn otherwise)</li>
     * </ol>
     */
    public abstract Property<String> getMode();

    /** Instrumentation mode: METHOD_ENTRY, FULL, FULL_METHOD, FULL_MEMBER. */
    public abstract Property<String> getInstrumentationMode();

    // ---- Paths ----

    /** Binary dependency index file (.test-order/test-dependencies.lz4). */
    public abstract RegularFileProperty getIndexFile();

    /** State file for durations, failures, and run history (.test-order/state.lz4). */
    public abstract RegularFileProperty getStateFile();

    /** Directory for .deps text files produced in learn mode. */
    public abstract DirectoryProperty getDepsDir();

    /** Source hash file for since-last-run change detection. */
    public abstract RegularFileProperty getHashFile();

    /** Test source hash file. */
    public abstract RegularFileProperty getTestHashFile();

    /** Per-method hash file. */
    public abstract RegularFileProperty getMethodHashFile();

    // ---- Change Detection ----

    /**
     * Change detection mode:
     * <ul>
     *   <li><b>uncommitted</b> (default) — detects changes via {@code git diff} against HEAD</li>
     *   <li><b>since-last-run</b> — compares source hashes against the last test-order run</li>
     *   <li><b>since-last-commit</b> — detects changes since the last git commit</li>
     *   <li><b>explicit</b> — uses only the classes specified in changedClasses/changedTestClasses</li>
     *   <li><b>auto</b> — alias for uncommitted (uses git if available, falls back to since-last-run)</li>
     * </ul>
     */
    public abstract Property<String> getChangeMode();

    /**
     * Explicit changed production source class FQCNs (comma-separated). Only used when
     * changeMode=explicit. Used for dependency-overlap scoring: tests whose dependencies
     * include one of these classes receive a higher priority score.
     * <p>
     * <b>Note:</b> Validation of these classes against the dependency index can only happen
     * at execution time (not configuration time) because the index may not yet exist
     * during Gradle's configuration phase. Use {@code testOrderValidateConfig} or run
     * with {@code --info} to see validation warnings.
     */
    public abstract Property<String> getChangedClasses();

    /**
     * Explicit changed test class FQCNs (comma-separated). Used for CI integrations that
     * detect changed test files externally. These receive a "changed test" bonus score.
     */
    public abstract Property<String> getChangedTestClasses();

    // ---- Instrumentation ----

    /** Comma-separated package prefixes to instrument. Auto-detected from src/main/java if empty. */
    public abstract Property<String> getIncludePackages();

    /**
     * Whether to auto-detect packages from project groupId when source scanning finds nothing.
     * When true and includePackages is empty, the plugin scans src/main/java for top-level packages.
     * If no packages are found (e.g. empty source root), it falls back to the project's groupId
     * as the instrumentation package filter. Set to false to disable this fallback.
     */
    public abstract Property<Boolean> getFilterByGroupId();

    /** Path for verbose agent logging (empty = disabled). */
    public abstract Property<String> getVerboseFile();

    // ---- Ordering ----

    /** Whether method-level ordering is enabled. */
    public abstract Property<Boolean> getMethodOrderingEnabled();

    /** Optional weights override file path. */
    public abstract Property<String> getWeightsFile();

    /**
     * Number of top-scored tests to always select.
     * Use -1 (default) to include all change-affected tests.
     * Use 0 to rely only on selectRandomM for selection.
     */
    public abstract Property<Integer> getSelectTopN();

    /** Number of diverse fast tests to additionally select. */
    public abstract Property<Integer> getSelectRandomM();

    /** Optional random seed for deterministic selection. */
    public abstract Property<Long> getSelectSeed();

    /** File containing the selected test classes. */
    public abstract RegularFileProperty getSelectedFile();

    /** File containing deferred test classes for run-remaining. */
    public abstract RegularFileProperty getRemainingFile();

    // ---- Tiered CI ----

    /** Fraction of remaining test duration budget allocated to tier 2 (0–1). */
    public abstract Property<Double> getTieredTier2Fraction();

    /** If true, tier 2 selects by cumulative duration budget; if false, by count fraction. */
    public abstract Property<Boolean> getTieredWeightByDuration();

    /** Output file for tier-1 test class list. */
    public abstract RegularFileProperty getTieredTier1File();

    /** Output file for tier-2 test class list. */
    public abstract RegularFileProperty getTieredTier2File();

    /** Output file for tier-3 test class list. */
    public abstract RegularFileProperty getTieredTier3File();

    // ---- Scoring Weights ----

    public abstract Property<Integer> getScoreNewTest();
    public abstract Property<Integer> getScoreChangedTest();
    public abstract Property<Integer> getScoreMaxFailure();
    public abstract Property<Integer> getScoreSpeed();
    public abstract Property<Integer> getScoreSpeedPenalty();
    public abstract Property<Integer> getScoreDepOverlap();
    public abstract Property<Integer> getScoreChangeComplexity();
    public abstract Property<Integer> getScoreStaticFieldBonus();
    public abstract Property<Integer> getScoreCoverageBonus();

    // ---- Auto-learn thresholds ----

    /**
     * In auto mode, forces a full re-learn after this many consecutive order-mode
     * runs (0 = disabled, default: 10). Ensures the dependency index stays fresh
     * as the codebase evolves.
     */
    public abstract Property<Integer> getAutoLearnRunThreshold();

    /** In auto mode, force learn when changed-class count reaches this threshold (0 = disabled). */
    public abstract Property<Integer> getAutoLearnDiffThreshold();

    /** In auto mode, run weight optimisation every N order-mode runs (0 = disabled). */
    public abstract Property<Integer> getAutoOptimizeEvery();

    /** Auto-compact the index every N order-mode runs by rebuilding from .deps files (0 = disabled). */
    public abstract Property<Integer> getAutoCompactEvery();

    // ---- Auto-mode behavior ----

    /** Whether to enable Spring context grouping for test scoring. */
    public abstract Property<Boolean> getSpringContextGrouping();

    /** When true, skip all test-order processing. */
    public abstract Property<Boolean> getSkip();

    /**
     * Enable TDD enforcement: new test classes and methods that pass without
     * having failed first are artificially failed with a descriptive error message.
     */
    public abstract Property<Boolean> getTdd();

    /**
     * In auto mode, whether to run deferred (remaining) tests after selected tests.
     * <p>
     * Default: {@code true} for the regular 'test' task (runs all tests).
     * The standalone 'testOrderSelect' task defaults to {@code false} (matching Maven's
     * select goal behavior) — use {@code -Dtestorder.auto.runRemaining=true} to override.
     */
    public abstract Property<Boolean> getAutoRunRemaining();

    // ---- Dump ----

    /** Output file for the dump task. If empty, writes to stdout. */
    public abstract Property<String> getDumpOutputFile();

    // ---- Coverage ----

    /** Minimum number of exercising tests for a class to be "well-tested". */
    public abstract Property<Integer> getCoverageThreshold();

    /** Output directory for coverage reports. */
    public abstract DirectoryProperty getCoverageOutputDir();

    /** Apply defaults. Called once during plugin application. */
    void applyDefaults(Project project) {
        getMode().convention("auto");
        getInstrumentationMode().convention("FULL");

        // In multi-module builds, store all test-order data at the root project
        // level (like Maven's ReactorContext). This ensures a single shared index
        // across all subprojects without requiring manual configuration.
        var rootDir = project.getRootProject().getLayout().getProjectDirectory();
        var localDir = rootDir.dir(".test-order");

        getIndexFile().convention(localDir.file("test-dependencies.lz4"));
        getStateFile().convention(localDir.file("state.lz4"));
        getDepsDir().convention(project.getLayout().getBuildDirectory().dir("test-order-deps"));
        getHashFile().convention(localDir.file("hashes.lz4"));
        getTestHashFile().convention(localDir.file("test-hashes.lz4"));
        getMethodHashFile().convention(localDir.file("method-hashes.lz4"));
        getChangeMode().convention("uncommitted");
        getChangedClasses().convention("");
        getChangedTestClasses().convention("");
        getIncludePackages().convention("");
        getFilterByGroupId().convention(true);
        getVerboseFile().convention("");
        getMethodOrderingEnabled().convention(false);
        getWeightsFile().convention("");
        getSelectTopN().convention(-1);
        getSelectRandomM().convention(10);
        getSelectSeed().convention((Long) null);
        getSelectedFile().convention(project.getLayout().getBuildDirectory().file("test-order-selected.txt"));
        getRemainingFile().convention(project.getLayout().getBuildDirectory().file("test-order-remaining.txt"));
        getTieredTier2Fraction().convention(0.5);
        getTieredWeightByDuration().convention(true);
        getTieredTier1File().convention(project.getLayout().getBuildDirectory().file("test-order-tier1.txt"));
        getTieredTier2File().convention(project.getLayout().getBuildDirectory().file("test-order-tier2.txt"));
        getTieredTier3File().convention(project.getLayout().getBuildDirectory().file("test-order-tier3.txt"));
        getAutoLearnRunThreshold().convention(10);
        getAutoLearnDiffThreshold().convention(0);
        getAutoOptimizeEvery().convention(10);
        getAutoCompactEvery().convention(50);
        getSpringContextGrouping().convention(false);
        getSkip().convention(false);
        getTdd().convention(false);
        getAutoRunRemaining().convention(true);
        getDumpOutputFile().convention("");
        getCoverageThreshold().convention(2);
        getCoverageOutputDir().convention(project.getLayout().getBuildDirectory().dir("coverage-reports"));
        // Score weight conventions intentionally omitted — when not explicitly configured,
        // PriorityClassOrderer uses weights from the state file (possibly optimizer-tuned)
        // or its own built-in defaults (newTest=100, changedTest=80, maxFailure=60, etc.).
        // Setting conventions here would always override those, preventing the optimizer
        // from having any effect.
    }

    /**
     * Validates user-configured values after project evaluation.
     * Logs warnings for invalid or suspicious configuration.
     */
    void validateConfiguration(org.gradle.api.logging.Logger logger) {
        // Validate mode
        java.util.Set<String> validModes = java.util.Set.of("auto", "learn", "order", "optimize", "skip");
        String mode = getMode().getOrElse("auto");
        if (!validModes.contains(mode)) {
            throw new org.gradle.api.GradleException(
                    "[test-order] Invalid mode '" + mode + "'. Valid values: " + validModes);
        }

        // Validate instrumentation mode
        java.util.Set<String> validInstrModes = java.util.Set.of("METHOD_ENTRY", "FULL", "FULL_METHOD", "FULL_MEMBER");
        String instrMode = getInstrumentationMode().getOrElse("FULL");
        if (!validInstrModes.contains(instrMode.toUpperCase())) {
            logger.warn("[test-order] Invalid instrumentationMode '{}'. Valid values: {}.", instrMode, validInstrModes);
        }

        // Validate change mode
        java.util.Set<String> validChangeModes = java.util.Set.of("auto", "since-last-run", "since-last-commit", "uncommitted", "explicit");
        String changeMode = getChangeMode().getOrElse("uncommitted");
        if (!validChangeModes.contains(changeMode)) {
            logger.warn("[test-order] Invalid changeMode '{}'. Valid values: {}.", changeMode, validChangeModes);
        }

        // Validate scoring weights are non-negative
        validateWeightNonNegative(logger, "scoreNewTest", getScoreNewTest());
        validateWeightNonNegative(logger, "scoreChangedTest", getScoreChangedTest());
        validateWeightNonNegative(logger, "scoreMaxFailure", getScoreMaxFailure());
        validateWeightNonNegative(logger, "scoreSpeed", getScoreSpeed());
        validateWeightNonNegative(logger, "scoreSpeedPenalty", getScoreSpeedPenalty());
        validateWeightNonNegative(logger, "scoreDepOverlap", getScoreDepOverlap());
        validateWeightNonNegative(logger, "scoreChangeComplexity", getScoreChangeComplexity());

        // Validate thresholds are non-negative
        int autoLearnThreshold = getAutoLearnRunThreshold().getOrElse(10);
        if (autoLearnThreshold < 0) {
            logger.warn("[test-order] autoLearnRunThreshold cannot be negative ({}). Use 0 to disable.", autoLearnThreshold);
        }
        int autoLearnDiff = getAutoLearnDiffThreshold().getOrElse(0);
        if (autoLearnDiff < 0) {
            logger.warn("[test-order] autoLearnDiffThreshold cannot be negative ({}). Use 0 to disable.", autoLearnDiff);
        }

        // Validate tiered fraction
        double tier2Fraction = getTieredTier2Fraction().getOrElse(0.5);
        if (tier2Fraction < 0 || tier2Fraction > 1) {
            throw new org.gradle.api.GradleException(
                    "[test-order] tieredTier2Fraction must be in [0, 1], got " + tier2Fraction
                    + ". This must be a fraction representing the proportion of remaining tests for tier 2.");
        }

        // Validate auto-optimize and auto-compact thresholds
        int optimizeEvery = getAutoOptimizeEvery().getOrElse(10);
        if (optimizeEvery < 0) {
            logger.warn("[test-order] autoOptimizeEvery cannot be negative ({}). Use 0 to disable.", optimizeEvery);
        }
        int compactEvery = getAutoCompactEvery().getOrElse(50);
        if (compactEvery < 0) {
            logger.warn("[test-order] autoCompactEvery cannot be negative ({}). Use 0 to disable.", compactEvery);
        }
    }

    private void validateWeightNonNegative(org.gradle.api.logging.Logger logger, String name, Property<Integer> prop) {
        if (prop.isPresent() && prop.get() < 0) {
            logger.warn("[test-order] Negative scoring weight {} = {} — this inverts scoring for that component.",
                    name, prop.get());
        }
    }
}
