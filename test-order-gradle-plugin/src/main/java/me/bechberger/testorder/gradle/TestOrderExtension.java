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

    /** Operational mode: auto, learn, or order. */
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

    /** Change detection mode: auto, since-last-run, since-last-commit, uncommitted, explicit. */
    public abstract Property<String> getChangeMode();

    /** Explicit changed class FQCNs (comma-separated). Only used when changeMode=explicit. */
    public abstract Property<String> getChangedClasses();

    // ---- Instrumentation ----

    /** Comma-separated package prefixes to instrument. Auto-detected from src/main/java if empty. */
    public abstract Property<String> getIncludePackages();

    /** Whether to auto-detect packages from project groupId when source scanning finds nothing. */
    public abstract Property<Boolean> getFilterByGroupId();

    /** Path for verbose agent logging (empty = disabled). */
    public abstract Property<String> getVerboseFile();

    // ---- Ordering ----

    /** Whether method-level ordering is enabled. */
    public abstract Property<Boolean> getMethodOrderingEnabled();

    /** Optional weights override file path. */
    public abstract Property<String> getWeightsFile();

    /** Number of top-scored tests to always select. */
    public abstract Property<Integer> getSelectTopN();

    /** Number of diverse fast tests to additionally select. */
    public abstract Property<Integer> getSelectRandomM();

    /** Optional random seed for deterministic selection. */
    public abstract Property<Long> getSelectSeed();

    /** File containing the selected test classes. */
    public abstract RegularFileProperty getSelectedFile();

    /** File containing deferred test classes for run-remaining. */
    public abstract RegularFileProperty getRemainingFile();

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

    /** In auto mode, force full learn after this many non-learn runs (0 = disabled). */
    public abstract Property<Integer> getAutoLearnRunThreshold();

    /** In auto mode, force learn when changed-class count reaches this threshold (0 = disabled). */
    public abstract Property<Integer> getAutoLearnDiffThreshold();

    /** In auto mode, run weight optimisation every N order-mode runs (0 = disabled). */
    public abstract Property<Integer> getAutoOptimizeEvery();

    // ---- Auto-mode behavior ----

    /** Whether to enable Spring context grouping for test scoring. */
    public abstract Property<Boolean> getSpringContextGrouping();

    /** When true, skip all test-order processing. */
    public abstract Property<Boolean> getSkip();

    /** In auto mode, whether to run deferred (remaining) tests after selected tests. */
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

        // Multi-project support: the shared dependency index lives at the root
        // project so that testOrderAggregateAll can merge all subproject deps.
        // Each subproject keeps its OWN state, hash, and deps files to avoid
        // path collisions in parallel builds.
        boolean isSubproject = project.getRootProject() != project;
        var localDir = project.getLayout().getProjectDirectory().dir(".test-order");
        var rootDir = project.getRootProject().getLayout().getProjectDirectory().dir(".test-order");
        String moduleId = isSubproject
                ? project.getGroup() + "-" + project.getName()
                : null;

        // Index is shared at the root so aggregation works across subprojects.
        getIndexFile().convention(rootDir.file("test-dependencies.lz4"));
        // State, hash files, and deps are per-module to prevent collisions.
        getStateFile().convention(localDir.file("state.lz4"));
        getDepsDir().convention(project.getLayout().getBuildDirectory().dir("test-order-deps"));
        if (isSubproject) {
            getHashFile().convention(rootDir.dir("hashes").file(moduleId + "-hashes.lz4"));
            getTestHashFile().convention(rootDir.dir("hashes").file(moduleId + "-test-hashes.lz4"));
            getMethodHashFile().convention(rootDir.dir("hashes").file(moduleId + "-method-hashes.lz4"));
        } else {
            getHashFile().convention(localDir.file("hashes.lz4"));
            getTestHashFile().convention(localDir.file("test-hashes.lz4"));
            getMethodHashFile().convention(localDir.file("method-hashes.lz4"));
        }
        getChangeMode().convention("uncommitted");
        getChangedClasses().convention("");
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
        getAutoLearnRunThreshold().convention(10);
        getAutoLearnDiffThreshold().convention(0);
        getAutoOptimizeEvery().convention(10);
        getSpringContextGrouping().convention(false);
        getSkip().convention(false);
        getAutoRunRemaining().convention(true);
        getDumpOutputFile().convention("");
        getCoverageThreshold().convention(2);
        getCoverageOutputDir().convention(project.getLayout().getBuildDirectory().dir("coverage-reports"));
        // Score weight conventions intentionally omitted — when not explicitly configured,
        // PriorityClassOrderer uses weights from the state file (possibly optimizer-tuned)
        // or its own defaults.  Setting conventions here would always override those.
    }
}
