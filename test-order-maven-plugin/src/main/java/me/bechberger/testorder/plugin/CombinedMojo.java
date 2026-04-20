package me.bechberger.testorder.plugin;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestSelector;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;

/**
 * Combined local development mode:
 * <ol>
 *   <li>Learn mode if no dependency index exists yet</li>
 *   <li>Select fast subset (new + top-n + m random diverse fast tests)</li>
 *   <li>Write remaining tests to a file for a separate {@code run-remaining} invocation</li>
 *   <li>Periodically trigger weight optimisation</li>
 * </ol>
 * <p>
 * Configures Surefire so that:
 * <ul>
 *   <li>The first {@code test} execution runs only selected tests (fail-fast)</li>
 *   <li>The remaining tests are written to {@code testorder.remaining.file} so a
 *       second {@code mvn test-order:run-remaining test} invocation can run them</li>
 * </ul>
 * <p>
 * Usage: {@code mvn test-order:combined test}
 */
@Mojo(name = "combined", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class CombinedMojo extends AbstractTestOrderMojo {

    /** Comma-separated additional package prefixes to instrument (merged with auto-detected source packages) */
    @Parameter(property = MavenPluginConfigKeys.INCLUDE_PACKAGES)
    private String includePackages;

    /** When true (default) and no source packages are detected, fall back to the project groupId. */
    @Parameter(property = MavenPluginConfigKeys.FILTER_BY_GROUP_ID, defaultValue = "true")
    private boolean filterByGroupId;

    @Parameter(property = MavenPluginConfigKeys.LEGACY_INSTRUMENTATION_MODE, defaultValue = "FULL")
    private String instrumentationMode;

    /** Number of top-scored test classes to always include. */
    @Parameter(property = MavenPluginConfigKeys.SELECT_TOP_N, defaultValue = "20")
    private int topN;

    /** Number of random fast tests to include for coverage diversity. */
        @Parameter(property = MavenPluginConfigKeys.SELECT_RANDOM_M, defaultValue = "10")
    private int randomM;

        @Parameter(property = MavenPluginConfigKeys.SELECT_SEED)
    private Long seed;

        @Parameter(property = MavenPluginConfigKeys.SELECT_REMAINING_FILE,
            defaultValue = "${project.build.directory}/test-order-remaining.txt")
    private String remainingFile;

        @Parameter(property = MavenPluginConfigKeys.SELECTED_FILE,
            defaultValue = "${project.build.directory}/test-order-selected.txt")
    private String selectedFile;

    /**
     * Whether to emit a reminder for running deferred tests via
     * {@code mvn test-order:run-remaining test} when any were deferred.
     */
    @Parameter(property = MavenPluginConfigKeys.COMBINED_RUN_REMAINING, defaultValue = "true")
    private boolean runRemaining;

    /** Optimise weights every N successful runs (0 = never). */
    @Parameter(property = MavenPluginConfigKeys.COMBINED_OPTIMIZE_EVERY, defaultValue = "10")
    private int optimizeEvery;

    @Override
    public void execute() throws MojoExecutionException {
        initContext();
        String canonicalInstrumentationMode = session != null && session.getUserProperties() != null
                ? session.getUserProperties().getProperty(MavenPluginConfigKeys.INSTRUMENTATION_MODE)
                : null;
        if (canonicalInstrumentationMode != null && !canonicalInstrumentationMode.isBlank()) {
            instrumentationMode = canonicalInstrumentationMode;
        }
        SurefireHelper.validateNoClassLevelParallel(project, getLog());

        Path idxPath = resolveIndexPath();

        // if no index exists, try auto-aggregation from .deps files
        if (!Files.exists(idxPath)) {
            Path depsDirPath = ctx.resolveDepsDir(depsDir);
            if (Files.isDirectory(depsDirPath) && hasDepsFiles(depsDirPath)) {
                autoAggregate(depsDirPath, idxPath);
            }
        }
        if (!Files.exists(idxPath)) {
            getLog().info("[test-order] No dependency index found — running in learn mode (all tests).");
            String effectiveInclude = resolveIncludePackages(includePackages, filterByGroupId, project, getLog());
            configureLearnMode(instrumentationMode, effectiveInclude, false);
            return;
        }

        // select mode
        DependencyMap depMap;
        try {
            depMap = DependencyMap.load(idxPath);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to load dependency index", e);
        }
        depMap = currentModuleDependencyMap(depMap);
        warnIfNoDeps(depMap);

        TestOrderState state = loadState();
        Set<String> changed = detectChangedClasses();
        Set<String> changedTests = detectChangedTestClasses();

        TestOrderState.ScoringWeights sw = resolveWeights(state);

        TestSelector.Selection selection = new TestSelector(
                depMap, state, changed, changedTests, sw,
                new TestSelector.Config(topN, randomM, seed)).select();

        getLog().info("[test-order] Selected " + selection.selected().size()
                + " tests (fail-fast), deferred " + selection.remaining().size());

        // write lists
        try {
            TestSelector.writeTestList(selection.selected(), Path.of(selectedFile));
            TestSelector.writeTestList(selection.remaining(), Path.of(remainingFile));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write test lists", e);
        }

        // configure Surefire for the selected subset
        if (!selection.selected().isEmpty()) {
            SurefireHelper.configureIncludes(project, selection.selected(), true);
        } else {
            getLog().info("[test-order] No tests selected — skipping test execution.");
            project.getProperties().setProperty("skipTests", "true");
        }
        writeOrdererConfig(changed, changedTests);

        // set project properties so a second execution can find the remaining tests
        String remainingPath = Path.of(remainingFile).toAbsolutePath().toString();
        project.getProperties().setProperty(MavenPluginConfigKeys.SELECT_REMAINING_FILE, remainingPath);
        // Preserve legacy project property name for compatibility with existing tooling.
        project.getProperties().setProperty("testorder.remaining.file", remainingPath);
        project.getProperties().setProperty("testorder.combined.active", "true");
        if (runRemaining && !selection.remaining().isEmpty()) {
            getLog().info("[test-order] Remaining tests written to " + remainingFile
                + ". Run deferred tests with: mvn test-order:run-remaining test");
        }

        // check if we should trigger optimization
        if (optimizeEvery > 0) {
            if (state.runsSinceLearn() > 0 && state.runsSinceLearn() % optimizeEvery == 0) {
                getLog().info("[test-order] Triggering periodic weight optimisation (every "
                        + optimizeEvery + " runs)…");
                TestOrderState.OptimizeResult optimized = state.optimize();
                if (optimized != null) {
                    state.setWeights(optimized.weights());
                    state.resetRunsSinceLearn();
                    try {
                        state.save(ctx.resolveStateFile(stateFile));
                        getLog().info("[test-order] Optimised weights saved: " + optimized.weights().format());
                    } catch (IOException e) {
                        getLog().warn("[test-order] Failed to save optimised weights: " + e.getMessage());
                    }
                }
            }
        }

        // snapshot hashes for next run
        snapshotHashes();
    }
}
