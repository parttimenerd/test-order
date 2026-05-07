package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.*;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestSelector;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.SelectOperation;
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

	/**
	 * Number of top-scored test classes to always include.
	 * Use -1 (default) to include all change-affected tests.
	 * Use 0 to rely only on randomM for selection.
	 */
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

	/**
	 * If true, print deferred-test handoff guidance and publish remaining-file
	 * properties for follow-up steps.
	 */
	@Parameter(property = MavenPluginConfigKeys.AUTO_RUN_REMAINING, defaultValue = "false")
	private boolean runRemaining;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		if (skipIfNotExplicitlySelectedReactorProject("select"))
			return;

		// Validate select parameters early (issue: topN=0 + randomM=0 → no tests selected)
		new me.bechberger.testorder.ops.ParameterValidator(MavenPluginLog.wrap(getLog()))
				.validateSelectParameters(topN, randomM);

		// Warn if 'test' phase is likely not going to run
		if (session != null && session.getGoals() != null
				&& session.getGoals().stream().noneMatch(g -> g.equals("test") || g.equals("verify")
						|| g.equals("install") || g.equals("package") || g.equals("deploy"))) {
			getLog().warn("[test-order] The 'select' goal configures Surefire but does not execute tests."
					+ " Include the test phase: mvn test-order:select test");
		}
		SurefireHelper.validateNoClassLevelParallel(project, getLog());

		Path idxPath = resolveIndexPath();

		// auto-aggregate if needed
		if (!Files.exists(idxPath)) {
			autoAggregateOrFail(idxPath);
		}
		ensureReadableIndex(idxPath, "select", false);

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
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to select tests", e);
		}
		TestSelector.Selection selection = result.selection();

		// Only print remaining file message if there actually are remaining tests
		if (!selection.remaining().isEmpty()) {
			getLog().info("[test-order] Remaining tests → " + remainingFile);
		}

		// configure Surefire to run only selected tests
		if (!selection.selected().isEmpty()) {
			SurefireHelper.configureIncludes(project, selection.selected(), true);
		} else {
			getLog().info("[test-order] No tests selected — skipping test execution.");
			project.getProperties().setProperty("skipTests", "true");
		}

		// Write PriorityClassOrderer config for ordering within the subset
		// Use dedicated change analysis to avoid double computation (R7-13)
		me.bechberger.testorder.ops.workflows.ChangeAnalysis.Result analysis;
		try {
			analysis = me.bechberger.testorder.ops.workflows.ChangeAnalysis.analyze(
					pctx, me.bechberger.testorder.ops.workflows.ChangeAnalysis.Options.CHANGES_ONLY);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to detect changes for ordering", e);
		}
		java.util.Map<String, String> configMap = me.bechberger.testorder.ops.OrdererConfigOperation.buildConfig(
				new me.bechberger.testorder.ops.OrdererConfigOperation.OrdererInput(
						pctx.indexFile().toAbsolutePath().toString(),
						pctx.stateFile().toAbsolutePath().toString(),
						pctx.weightsFile() != null ? pctx.weightsFile().toAbsolutePath().toString() : null,
						analysis.changedClasses(), analysis.changedTests(), analysis.changedMethods(),
						pctx.scoreOverrides(), pctx.methodOrderingEnabled(), pctx.springContextGrouping(),
						pctx.projectRoot().toAbsolutePath().toString(),
						pctx.sourceRoot() != null ? pctx.sourceRoot().toAbsolutePath().toString() : null,
						pctx.changeMode()));
		writeOrdererConfigFromMap(configMap);

		if (runRemaining && !selection.remaining().isEmpty()) {
			String remainingPath = Path.of(remainingFile).toAbsolutePath().toString();
			project.getProperties().setProperty(MavenPluginConfigKeys.SELECT_REMAINING_FILE, remainingPath);
			project.getProperties().setProperty("testorder.remaining.file", remainingPath);
			getLog().info("[test-order] Remaining tests written to " + remainingFile + ". Run deferred tests with:"
					+ " mvn test-order:run-remaining test");
		} else if (!runRemaining && !selection.remaining().isEmpty()) {
			getLog().warn("[test-order] " + selection.remaining().size() + " tests were NOT selected and will NOT run.");
			getLog().warn("[test-order] To run them: mvn test-order:run-remaining test"
					+ " -Dtestorder.select.remainingFile=" + remainingFile);
			getLog().warn("[test-order] To always run remaining automatically, add"
					+ " -Dtestorder.auto.runRemaining=true or set <runRemaining>true</runRemaining> in plugin config.");
		}

		// Prevent a POM-bound auto goal from overriding the test selection
		project.getProperties().setProperty("testorder.auto.active", "true");
	}

}
