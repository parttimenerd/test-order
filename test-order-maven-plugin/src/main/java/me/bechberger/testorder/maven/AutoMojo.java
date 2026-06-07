package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.*;

import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import me.bechberger.testorder.TestSelector;
import me.bechberger.testorder.ops.CiSummaryWriter;
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

	@Parameter(property = MavenPluginConfigKeys.INSTRUMENTATION_MODE, defaultValue = "MEMBER")
	private String instrumentationMode;

	@Parameter(property = MavenPluginConfigKeys.INSTRUMENTATION, defaultValue = "offline")
	private String instrumentation;

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

		if (isPrepareGoalBound()) {
			getLog().info("[test-order] The 'prepare' goal is bound in your POM."
					+ " The CLI goal takes precedence; 'prepare' will detect this and skip.");
		}

		validateAutoMojoParameters();
		SurefireHelper.validateNoClassLevelParallel(project, getLog());
		SurefireHelper.warnConflictingRunOrder(project, getLog());
		SurefireHelper.forceClasspathModeIfNeeded(project, getLog());

		// R16-4 (auto parity): When user filters to specific tests via -Dtest,
		// delegate entirely to Surefire — don't override with auto-selection.
		String userTestFilter = session != null && session.getUserProperties() != null
				? session.getUserProperties().getProperty("test")
				: null;
		if (userTestFilter != null && !userTestFilter.isBlank()) {
			getLog().info("[test-order] Skipping auto selection — -Dtest=" + userTestFilter
					+ " filter active. test-order will not override your explicit test selection.");
			project.getProperties().setProperty("testorder.auto.active", "true");
			return;
		}

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
			// Use reactor root for CI config lookup — download-config.yml lives at the
			// reactor root, not per-module. Mirrors the behaviour of DownloadMojo.
			Path ciConfigRoot = session != null && session.getTopLevelProject() != null
					? session.getTopLevelProject().getBasedir().toPath()
					: project.getBasedir().toPath();
			result = new AutoWorkflow(pctx, mode, () -> {
				if (me.bechberger.testorder.ci.CiConfigParser.configExistsIn(ciConfigRoot)) {
					me.bechberger.testorder.ci.CiDepDownloadManager.downloadIfConfigured(ciConfigRoot, idxPath)
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
			if ("offline".equalsIgnoreCase(instrumentation)) {
				configureOfflineLearnMode(instrumentationMode, effectiveInclude);
			} else {
				configureLearnMode(instrumentationMode, effectiveInclude, true);
			}
			project.getProperties().setProperty("testorder.auto.active", "true");

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

			int totalInIndex = os.selection().selected().size() + os.selection().remaining().size();
			CiSummaryWriter.writeSummary(new CiSummaryWriter.SummaryInput(totalInIndex, os.selection().selected(),
					os.selection().remaining(), os.changedClasses(), os.changedTests(), java.util.List.of(), "auto", 0,
					Path.of(project.getBuild().getDirectory())), pluginLog());

			String remainingPath = Path.of(remainingFile).toAbsolutePath().toString();
			project.getProperties().setProperty(MavenPluginConfigKeys.SELECT_REMAINING_FILE, remainingPath);
			project.getProperties().setProperty("testorder.remaining.file", remainingPath);
			project.getProperties().setProperty("testorder.auto.active", "true");

			if (os.attachLearnAgent()) {
				// alwaysLearn: attach the learn agent on top of the ordered run so the
				// next run can incrementally merge fresh deps into the existing index.
				String effectiveInclude = resolveIncludePackages(includePackages, filterByGroupId, project, getLog());
				if ("offline".equalsIgnoreCase(instrumentation)) {
					configureOfflineLearnMode(instrumentationMode, effectiveInclude);
				} else {
					configureLearnMode(instrumentationMode, effectiveInclude, true);
				}
				getLog().info("[test-order] alwaysLearn=true — agent attached on top of ordered run");
			}

			if (!runRemaining && !selection.remaining().isEmpty()) {
				getLog().info("[test-order] Remaining tests written to " + remainingFile + ". Run deferred tests with:"
						+ " mvn test-order:run-remaining test");
			}
		}
	}

	@Override
	protected String resolveEffectiveIncludePackages() {
		return resolveIncludePackages(includePackages, filterByGroupId, project, getLog());
	}

	private void validateAutoMojoParameters() throws MojoExecutionException {
		ParameterValidator validator = new ParameterValidator(getLog());
		validator.validateInstrumentationMode(instrumentationMode);
		validator.validateSelectParameters(topN, randomM);
		if (optimizeEvery < 0)
			throw new MojoExecutionException("[test-order] optimizeEvery cannot be negative: " + optimizeEvery);
		if (autoLearnRunThreshold < 0)
			throw new MojoExecutionException(
					"[test-order] autoLearnRunThreshold cannot be negative: " + autoLearnRunThreshold);
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
