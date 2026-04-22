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

    /** Apply defaults. Called once during plugin application. */
    void applyDefaults(Project project) {
        getMode().convention("auto");
        getInstrumentationMode().convention("FULL");
        getIndexFile().convention(project.getLayout().getProjectDirectory().file(".test-order/test-dependencies.lz4"));
        getStateFile().convention(project.getLayout().getProjectDirectory().file(".test-order/state.lz4"));
        getDepsDir().convention(project.getLayout().getBuildDirectory().dir("test-order-deps"));
        getHashFile().convention(project.getLayout().getProjectDirectory().file(".test-order/hashes.lz4"));
        getTestHashFile().convention(project.getLayout().getProjectDirectory().file(".test-order/test-hashes.lz4"));
        getMethodHashFile().convention(project.getLayout().getProjectDirectory().file(".test-order/method-hashes.lz4"));
        getChangeMode().convention("auto");
        getChangedClasses().convention("");
        getIncludePackages().convention("");
        getFilterByGroupId().convention(true);
        getVerboseFile().convention("");
        getMethodOrderingEnabled().convention(false);
        getWeightsFile().convention("");
        getSelectTopN().convention(20);
        getSelectRandomM().convention(10);
        getSelectSeed().convention((Long) null);
        getSelectedFile().convention(project.getLayout().getBuildDirectory().file("test-order-selected.txt"));
        getRemainingFile().convention(project.getLayout().getBuildDirectory().file("test-order-remaining.txt"));
        // Score weight conventions intentionally omitted — when not explicitly configured,
        // PriorityClassOrderer uses weights from the state file (possibly optimizer-tuned)
        // or its own defaults.  Setting conventions here would always override those.
    }
}
