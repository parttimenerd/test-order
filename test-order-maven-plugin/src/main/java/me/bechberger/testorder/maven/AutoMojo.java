package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.*;

import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import me.bechberger.testorder.TestSelector;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.workflows.AutoWorkflow;

/**
 * Auto local development mode — thin Maven wrapper around {@link AutoWorkflow}.
 *
 * <p>
 * Usage: {@code mvn test-order:auto test}
 */
@Mojo(name = "auto", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class AutoMojo extends AbstractTestOrderMojo {

	@Parameter(property = MavenPluginConfigKeys.MODE, defaultValue = "auto")
	private String mode;

	@Parameter(property = MavenPluginConfigKeys.INCLUDE_PACKAGES)
	private String includePackages;

	@Parameter(property = MavenPluginConfigKeys.FILTER_BY_GROUP_ID, defaultValue = "true")
	private boolean filterByGroupId;

	@Parameter(property = MavenPluginConfigKeys.INSTRUMENTATION_MODE, defaultValue = "FULL")
	private String instrumentationMode;

	@Parameter(property = MavenPluginConfigKeys.SELECT_TOP_N, defaultValue = "-1")
	private int topN;

	@Parameter(property = MavenPluginConfigKeys.SELECT_RANDOM_M, defaultValue = "10")
	private int randomM;

	@Parameter(property = MavenPluginConfigKeys.SELECT_SEED)
	private Long seed;

	@Parameter(property = MavenPluginConfigKeys.SELECT_REMAINING_FILE, defaultValue = "${project.build.directory}/test-order-remaining.txt")
	private String remainingFile;

	@Parameter(property = MavenPluginConfigKeys.SELECTED_FILE, defaultValue = "${project.build.directory}/test-order-selected.txt")
	private String selectedFile;

	@Parameter(property = MavenPluginConfigKeys.AUTO_RUN_REMAINING, defaultValue = "true")
	private boolean runRemaining;

	@Parameter(property = MavenPluginConfigKeys.AUTO_LEARN_RUN_THRESHOLD, defaultValue = "10")
	private int autoLearnRunThreshold;

	@Parameter(property = MavenPluginConfigKeys.AUTO_LEARN_DIFF_THRESHOLD, defaultValue = "0")
	private int autoLearnDiffThreshold;

	@Parameter(property = MavenPluginConfigKeys.AUTO_OPTIMIZE_EVERY, defaultValue = "10")
	private int optimizeEvery;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		if (skipIfNotExplicitlySelectedReactorProject("auto"))
			return;

		if ("pom".equals(project.getPackaging())) {
			getLog().debug("[test-order] Skipping auto goal — POM module: " + project.getArtifactId());
			return;
		}

		// Guard against double execution
		if ("true".equals(project.getProperties().getProperty("testorder.auto.active"))) {
			getLog().debug("[test-order] Auto mojo already executed" + " in this session — skipping.");
			return;
		}

		// Warn if 'test' phase is likely not going to run (standalone CLI goal
		// invocation)
		if (session != null && session.getGoals() != null && session.getGoals().stream().noneMatch(g -> g.equals("test")
				|| g.equals("verify") || g.equals("install") || g.equals("package") || g.equals("deploy"))) {
			getLog().warn("[test-order] The 'auto' goal configures test selection but does not execute tests."
					+ " Include the test phase: mvn test-order:auto test");
		}

		if (isPrepareGoalBound()) {
			getLog().debug("[test-order] Skipping POM-bound 'prepare' (CLI goal takes precedence).");
		}

		validateAutoMojoParameters();
		SurefireHelper.validateNoClassLevelParallel(project, getLog());
		SurefireHelper.warnConflictingRunOrder(project, getLog());
		SurefireHelper.warnConflictingOrderers(project, getLog());
		SurefireHelper.forceClasspathModeIfNeeded(project, getLog());

		// Resolve effective mode: CLI property override wins
		String modeOverride = session != null && session.getUserProperties() != null
				? session.getUserProperties().getProperty(MavenPluginConfigKeys.MODE)
				: null;
		if (modeOverride != null && !modeOverride.isBlank()) {
			mode = modeOverride;
		}

		Path idxPath = resolveIndexPath();
		Path depsDirPath = ctx.resolveDepsDir(depsDir);
		ensureReadableIndex(idxPath, "auto", true);

		PluginContext pctx = buildPluginContextBuilder().instrumentationMode(instrumentationMode)
				.includePackages(includePackages).filterByGroupId(filterByGroupId)
				.autoLearnRunThreshold(autoLearnRunThreshold).autoLearnDiffThreshold(autoLearnDiffThreshold)
				.optimizeEvery(optimizeEvery).topN(topN).randomM(randomM).seed(seed).selectedFile(Path.of(selectedFile))
				.remainingFile(Path.of(remainingFile)).build();

		// ── Execute shared workflow ─────────────────────────────────
		AutoWorkflow.Result result;
		try {
			result = new AutoWorkflow(pctx, mode, () -> {
				if (me.bechberger.testorder.ci.CiConfigParser.configExistsIn(project.getBasedir().toPath())) {
					me.bechberger.testorder.ci.CiDepDownloadManager
							.downloadIfConfigured(project.getBasedir().toPath(), idxPath)
							.ifPresent(p -> getLog().info("[test-order] CI index downloaded to " + p));
				}
			}, Files.isDirectory(depsDirPath) && hasDepsFiles(depsDirPath) ? depsDirPath : null).execute();
		} catch (IllegalArgumentException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to execute auto workflow", e);
		}

		// ── Apply framework-specific result ─────────────────────────
		if (result instanceof AutoWorkflow.Result.Skip s) {
			getLog().info("[test-order] " + s.reason());
			project.getProperties().setProperty("testorder.auto.active", "true");

		} else if (result instanceof AutoWorkflow.Result.Learn l) {
			getLog().info("[test-order] " + l.reason());
			SurefireHelper.warnForkCountInLearnMode(project, getLog());
			SurefireHelper.warnReuseForksFalseInLearnMode(project, getLog());
			SurefireHelper.warnRerunFailingTestsInLearnMode(project, getLog());
			String effectiveInclude = resolveIncludePackages(includePackages, filterByGroupId, project, getLog());
			configureLearnMode(instrumentationMode, effectiveInclude, true);
			project.getProperties().setProperty("testorder.auto.active", "true");

			// ML predictions are orthogonal to dependency learning
			if (isMLEnabled()) {
				appendMLEnabledToConfig();
				generateMLPredictions(java.util.Set.of(), java.util.Set.of());
			}

		} else if (result instanceof AutoWorkflow.Result.OrderSelect os) {
			SurefireHelper.warnForkCountInOrderMode(project, getLog());
			TestSelector.Selection selection = os.selection();

			if (os.selectResult().allSelected()) {
				// SelectOperation already logged "Running full test suite"
			} else if (runRemaining && !selection.remaining().isEmpty()) {
				// R18-13: autoRunRemaining=true means run ALL tests (selected + remaining)
				// in priority order, rather than deferring remaining to a separate step.
				getLog().info("[test-order] autoRunRemaining=true — running all "
						+ (selection.selected().size() + selection.remaining().size()) + " tests in priority order.");
				// Don't filter Surefire — let all tests run, but in scored order via orderer
			} else if (!selection.selected().isEmpty()) {
				SurefireHelper.warnSelectModeFilters(project, getLog());
				SurefireHelper.configureIncludes(project, selection.selected(), true);
			} else {
				getLog().info("[test-order] No tests selected" + " — skipping test execution.");
				project.getProperties().setProperty("skipTests", "true");
			}

			writeOrdererConfig(os.changedClasses(), os.changedTests(), os.changedMethods(), buildScoreOverrides());

			// ML: record history and generate predictions
			if (isMLEnabled()) {
				appendMLEnabledToConfig();
				generateMLPredictions(os.changedClasses(), os.changedTests());
			}

			String remainingPath = Path.of(remainingFile).toAbsolutePath().toString();
			project.getProperties().setProperty(MavenPluginConfigKeys.SELECT_REMAINING_FILE, remainingPath);
			project.getProperties().setProperty("testorder.remaining.file", remainingPath);
			project.getProperties().setProperty("testorder.auto.active", "true");

			if (!runRemaining && !selection.remaining().isEmpty()) {
				getLog().info("[test-order] Remaining tests written to " + remainingFile + ". Run deferred tests with:"
						+ " mvn test-order:run-remaining test");
			}
		}
	}

	private void validateAutoMojoParameters() throws MojoExecutionException {
		ParameterValidator validator = new ParameterValidator(getLog());
		validator.validateInstrumentationMode(instrumentationMode);
		if (topN < -1)
			throw new MojoExecutionException("[test-order] selectTopN cannot be less than -1: " + topN);
		if (randomM < 0)
			throw new MojoExecutionException("[test-order] selectRandomM cannot be negative: " + randomM);
		if (topN == 0 && randomM == 0)
			throw new MojoExecutionException(
					"[test-order] Both selectTopN and selectRandomM are 0 — no tests would be selected. "
							+ "Set selectTopN to at least 1, or use selectTopN=-1 to include all change-affected tests.");
		if (optimizeEvery < 0)
			throw new MojoExecutionException("[test-order] optimizeEvery cannot be negative: " + optimizeEvery);
		if (autoLearnRunThreshold < 0)
			throw new MojoExecutionException(
					"[test-order] autoLearnRunThreshold cannot be negative: " + autoLearnRunThreshold);
		if (autoLearnDiffThreshold < 0)
			throw new MojoExecutionException(
					"[test-order] autoLearnDiffThreshold cannot be negative: " + autoLearnDiffThreshold);
	}

	private boolean isPrepareGoalBound() {
		for (Plugin plugin : project.getBuildPlugins()) {
			if ("test-order-maven-plugin".equals(plugin.getArtifactId())
					&& "me.bechberger".equals(plugin.getGroupId())) {
				for (var exec : plugin.getExecutions()) {
					if (exec.getGoals().contains("prepare")) {
						return true;
					}
				}
			}
		}
		return false;
	}
}
