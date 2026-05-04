package me.bechberger.testorder.plugin;

import java.io.IOException;
import java.nio.file.*;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestSelector;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.SelectOperation;
import me.bechberger.testorder.ops.workflows.OrderWorkflow;
import me.bechberger.testorder.ops.workflows.SelectWorkflow;

/**
 * Selects a fast subset of tests for CI: all new tests, the top-n by score, and
 * m random fast tests chosen for maximum code coverage diversity. The remaining
 * tests are written to a file for a later "run-remaining" step.
 * <p>
 * Configures Surefire to run only the selected subset.
 * <p>
 * Usage: {@code mvn test-order:select test}
 */
@Mojo(name = "select", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class SelectMojo extends AbstractTestOrderMojo {

	/** Number of top-scored test classes to always include (-1 = all affected). */
	@Parameter(property = MavenPluginConfigKeys.SELECT_TOP_N, defaultValue = "-1")
	private int topN;

	/** Number of random fast tests to include for coverage diversity. */
	@Parameter(property = MavenPluginConfigKeys.SELECT_RANDOM_M, defaultValue = "10")
	private int randomM;

	/** Random seed for reproducible selection (optional). */
	@Parameter(property = MavenPluginConfigKeys.SELECT_SEED)
	private Long seed;

	/** File to write the remaining (not selected) test classes to. */
	@Parameter(property = MavenPluginConfigKeys.SELECT_REMAINING_FILE, defaultValue = "${project.build.directory}/test-order-remaining.txt")
	private String remainingFile;

	/** File to write the selected test classes to (for reference / debugging). */
	@Parameter(property = MavenPluginConfigKeys.SELECTED_FILE, defaultValue = "${project.build.directory}/test-order-selected.txt")
	private String selectedFile;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		SurefireHelper.validateNoClassLevelParallel(project, getLog());

		Path idxPath = resolveIndexPath();

		// auto-aggregate if needed
		if (!Files.exists(idxPath)) {
			autoAggregateOrFail(idxPath);
		}

		if ("explicit".equalsIgnoreCase(changeMode)) {
			try {
				warnUnknownChangedClasses(detectChangedClasses(), DependencyMap.load(idxPath));
			} catch (IOException e) {
				throw new MojoExecutionException("Failed to validate explicitly changed classes", e);
			}
		}

		PluginContext pctx = buildPluginContextBuilder().topN(topN).randomM(randomM).seed(seed)
				.selectedFile(Path.of(selectedFile)).remainingFile(Path.of(remainingFile)).build();

		SelectOperation.SelectResult result;
		try {
			result = SelectWorkflow.select(pctx);
			getLog().info("[test-order] Remaining tests → " + remainingFile);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to select tests", e);
		}
		TestSelector.Selection selection = result.selection();

		// configure Surefire to run only selected tests
		if (!selection.selected().isEmpty()) {
			SurefireHelper.configureIncludes(project, selection.selected(), true);
		} else {
			getLog().info("[test-order] No tests selected — skipping test execution.");
			project.getProperties().setProperty("skipTests", "true");
		}

		// also write the PriorityClassOrderer config so ordering still works within the
		// subset — use OrderWorkflow's change detection results for consistency
		OrderWorkflow.OrderSetupResult orderResult;
		try {
			orderResult = OrderWorkflow.setup(pctx, loadState());
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to set up test ordering", e);
		}
		writeOrdererConfig(orderResult.changedClasses(), orderResult.changedTests(), orderResult.changedMethods(),
				buildScoreOverrides());

		// Prevent a POM-bound auto goal from overriding the test selection
		project.getProperties().setProperty("testorder.auto.active", "true");
	}

}
