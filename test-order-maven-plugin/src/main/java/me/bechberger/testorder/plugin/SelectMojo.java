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
 * Selects a fast subset of tests for CI: all new tests, the top-n by score,
 * and m random fast tests chosen for maximum code coverage diversity.
 * The remaining tests are written to a file for a later "run-remaining" step.
 * <p>
 * Configures Surefire to run only the selected subset.
 * <p>
 * Usage: {@code mvn test-order:select test}
 */
@Mojo(name = "select", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class SelectMojo extends AbstractTestOrderMojo {

    /** Number of top-scored test classes to always include. */
    @Parameter(property = MavenPluginConfigKeys.SELECT_TOP_N, defaultValue = "20")
    private int topN;

    /** Number of random fast tests to include for coverage diversity. */
    @Parameter(property = MavenPluginConfigKeys.SELECT_RANDOM_M, defaultValue = "10")
    private int randomM;

    /** Random seed for reproducible selection (optional). */
    @Parameter(property = MavenPluginConfigKeys.SELECT_SEED)
    private Long seed;

    /** File to write the remaining (not selected) test classes to. */
        @Parameter(property = MavenPluginConfigKeys.SELECT_REMAINING_FILE,
            defaultValue = "${project.build.directory}/test-order-remaining.txt")
    private String remainingFile;

    /** File to write the selected test classes to (for reference / debugging). */
        @Parameter(property = MavenPluginConfigKeys.SELECTED_FILE,
            defaultValue = "${project.build.directory}/test-order-selected.txt")
    private String selectedFile;

    @Override
    public void execute() throws MojoExecutionException {
        initContext();
        SurefireHelper.validateNoClassLevelParallel(project, getLog());

        Path idxPath = resolveIndexPath();

        // auto-aggregate if needed
        if (!Files.exists(idxPath)) {
            autoAggregateOrFail(idxPath);
        }

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
                + " tests, deferred " + selection.remaining().size());

        // write lists
        try {
            TestSelector.writeTestList(selection.selected(), Path.of(selectedFile));
            TestSelector.writeTestList(selection.remaining(), Path.of(remainingFile));
            getLog().info("[test-order] Remaining tests → " + remainingFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write test lists", e);
        }

        // configure Surefire to run only selected tests
        if (!selection.selected().isEmpty()) {
            SurefireHelper.configureIncludes(project, selection.selected(), true);
        } else {
            getLog().info("[test-order] No tests selected — skipping test execution.");
            project.getProperties().setProperty("skipTests", "true");
        }

        // also write the PriorityClassOrderer config so ordering still works within the subset
        writeOrdererConfig(changed, changedTests);
    }
}
