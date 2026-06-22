package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.*;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestSelector;
import me.bechberger.testorder.ops.AffectedOperation;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.workflows.AffectedWorkflow;

/**
 * Selects a fast subset of tests for CI: all new tests, the top-n by score, and
 * m random fast tests chosen for maximum code coverage diversity. The remaining
 * tests are written to a file for a later "run-remaining" step.
 * <p>
 * Configures Surefire to run only the selected subset.
 * <p>
 * Usage: {@code mvn test-order:affected test}
 */
@Mojo(name = "affected", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class AffectedMojo extends AbstractTestOrderMojo {

	/**
	 * Number of top-scored test classes to always include. Use -1 (default) to
	 * include all change-affected tests. Must be >= 1, or -1 to select all. A value
	 * of 0 is rejected as ambiguous (it selects no top-scored tests but still runs
	 * new and @AlwaysRun tests, which is confusing).
	 */
	@Parameter(property = MavenPluginConfigKeys.SELECT_TOP_N, defaultValue = "-1")
	private int topN;

	/**
	 * Number of random fast tests to include for coverage diversity. Default 0
	 * keeps {@code test-order:affected} change-focused: only tests with a real
	 * change signal run. Set higher to add fast-diverse padding.
	 */
	@Parameter(property = MavenPluginConfigKeys.SELECT_RANDOM_M, defaultValue = "0")
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
		if (skipIfNotExplicitlySelectedReactorProject("affected"))
			return;

		// R15-2: Skip POM-packaging modules — they have no tests and their selection
		// decisions don't propagate to sub-modules, confusing users.
		if ("pom".equals(project.getPackaging())) {
			getLog().debug("[test-order] Skipping select goal — POM module: " + project.getArtifactId());
			return;
		}

		// R16-4 (affected parity): When user filters to specific tests via -Dtest,
		// skip selection entirely — the user's explicit filter takes precedence and
		// test-order selection would only produce a misleading "N tests not selected"
		// warning for tests that are still going to run via -Dtest.
		String userTestFilter = session != null && session.getUserProperties() != null
				? session.getUserProperties().getProperty("test")
				: null;
		if (userTestFilter != null && !userTestFilter.isBlank()) {
			getLog().info("[test-order] Skipping selection — -Dtest=" + userTestFilter + " filter active. "
					+ "test-order will not override your explicit test selection.");
			project.getProperties().setProperty("testorder.auto.active", "true");
			return;
		}

		// Validate select parameters early (issue: topN=0 + randomM=0 → no tests
		// selected)
		try {
			new me.bechberger.testorder.ops.ParameterValidator(MavenPluginLog.wrap(getLog()))
					.validateSelectParameters(topN, randomM);
		} catch (IllegalArgumentException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}

		// Inform when topN=-1 (default) will run all affected tests in priority order
		if (topN == -1) {
			getLog().info(
					"[test-order] topN=-1 (default): runs ALL affected tests in priority order, defers unaffected ones."
							+ " To run only the top N affected tests, set -Dtestorder.affected.topN=N (e.g., topN=10).");
		}

		// Warn if 'test' phase is likely not going to run
		if (session != null && session.getGoals() != null && session.getGoals().stream().noneMatch(g -> g.equals("test")
				|| g.equals("verify") || g.equals("install") || g.equals("package") || g.equals("deploy"))) {
			getLog().warn("[test-order] The 'affected' goal configures Surefire but does not execute tests."
					+ "\nRun: mvn test-order:affected test");
		}
		SurefireHelper.validateNoClassLevelParallel(project, getLog());

		Path idxPath = resolveIndexPath();

		// auto-aggregate if needed
		if (!Files.exists(idxPath)) {
			autoAggregateOrFail(idxPath);
		}
		ensureReadableIndex(idxPath, "affected", false);

		if ("explicit".equalsIgnoreCase(changeMode)) {
			try {
				warnUnknownChangedClasses(detectChangedClasses(), DependencyMap.load(idxPath));
			} catch (IOException e) {
				throw new MojoExecutionException("Failed to validate explicitly changed classes", e);
			}
		}

		PluginContext pctx = buildPluginContextBuilder().topN(topN).randomM(randomM).seed(seed)
				.selectedFile(Path.of(selectedFile)).remainingFile(Path.of(remainingFile)).build();

		AffectedOperation.SelectResult result;
		me.bechberger.testorder.ops.workflows.ChangeAnalysis.Result analysis;
		try {
			AffectedWorkflow.SelectWithAnalysis combined = AffectedWorkflow.selectWithAnalysis(pctx);
			result = combined.result();
			analysis = combined.analysis();
		} catch (IOException e) {
			throw new MojoExecutionException("[test-order] Failed to select tests: " + e.getMessage()
					+ ". Run 'mvn test-order:diagnose' for setup details.", e);
		}
		TestSelector.Selection selection = result.selection();

		// Only print remaining file message if there actually are remaining tests
		if (!selection.remaining().isEmpty()) {
			getLog().info("[test-order] Remaining tests → " + remainingFile);
		}

		// configure Surefire to run only selected tests
		SurefireHelper.warnSelectModeFilters(project, getLog());
		if (!selection.selected().isEmpty()) {
			SurefireHelper.configureIncludes(project, selection.selected(), true);
			// PriorityClassOrderer can only reorder classes within a single TestPlan,
			// so multi-fork or fresh-JVM-per-class configs defeat ordering. Pin
			// forkCount=1 reuseForks=true (preserving any explicit user override).
			SurefireHelper.forceSingleForkForOrdering(project, getLog());
		} else {
			getLog().info("[test-order] No tests selected — skipping test execution.");
			project.getProperties().setProperty("skipTests", "true");
		}

		// Write PriorityClassOrderer config for ordering within the subset
		// R7-13: Reuse analysis from AffectedWorkflow instead of re-running change
		// detection
		java.util.Map<String, String> configMap = me.bechberger.testorder.ops.OrdererConfigOperation
				.buildConfig(new me.bechberger.testorder.ops.OrdererConfigOperation.OrdererInput(
						pctx.indexFile().toAbsolutePath().toString(), pctx.stateFile().toAbsolutePath().toString(),
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
			getLog().info("[test-order] Remaining tests written to " + remainingFile + ".");
			getLog().info("Run: mvn test-order:run-remaining test");
		} else if (!runRemaining && !selection.remaining().isEmpty()) {
			// R15-6: Suppress alarming warning when run-remaining is also in the goal list
			boolean runRemainingInGoals = session != null && session.getGoals() != null
					&& session.getGoals().stream().anyMatch(g -> g.contains("run-remaining"));
			if (runRemainingInGoals) {
				getLog().info(
						"[test-order] " + selection.remaining().size() + " tests deferred to run-remaining phase.");
			} else {
				getLog().warn(
						"[test-order] " + selection.remaining().size() + " tests were NOT selected and will NOT run.");
				getLog().warn(
						"Run: mvn test-order:run-remaining test -Dtestorder.affected.remainingFile=target/test-order-remaining.txt");
				getLog().warn("[test-order] To always run remaining automatically, add"
						+ " -Dtestorder.auto.runRemaining=true or set <runRemaining>true</runRemaining> in plugin config.");
			}
		}

		// Prevent a POM-bound auto goal from overriding the test selection
		project.getProperties().setProperty("testorder.auto.active", "true");
	}

}
