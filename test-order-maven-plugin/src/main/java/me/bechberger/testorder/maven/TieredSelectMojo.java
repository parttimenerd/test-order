package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TieredTestSelector;
import me.bechberger.testorder.ops.CiSummaryWriter;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.TieredSelectOperation;
import me.bechberger.testorder.ops.workflows.ChangeAnalysis;

/**
 * Selects tests into three tiers for progressive CI execution:
 * <ol>
 * <li><b>Tier 1</b>: change-affected tests (dep overlap, new, changed,
 * {@code @AlwaysRun})</li>
 * <li><b>Tier 2</b>: top-scored remaining tests (configurable fraction by
 * expected duration)</li>
 * <li><b>Tier 3</b>: all remaining tests</li>
 * </ol>
 *
 * <p>
 * Configures Surefire to run only tier 1 tests. Subsequent tiers can be
 * executed via {@code test-order:run-tier -Dtestorder.tiered.currentTier=2} and
 * {@code test-order:run-tier -Dtestorder.tiered.currentTier=3}.
 *
 * <p>
 * CI pipeline usage:
 *
 * <pre>
 * # Step 1: Run change-affected tests
 * mvn test-order:tiered-select test
 *
 * # Step 2: Run top-scored remaining (only if step 1 passed)
 * mvn test-order:run-tier test -Dtestorder.tiered.currentTier=2
 *
 * # Step 3: Run the rest (only if step 2 passed)
 * mvn test-order:run-tier test -Dtestorder.tiered.currentTier=3
 * </pre>
 */
@Mojo(name = "tiered-select", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class TieredSelectMojo extends AbstractTestOrderMojo {

	/**
	 * Fraction of remaining test duration budget allocated to tier 2. Value in [0,
	 * 1]. Default 0.5 means tier 2 gets about half the expected execution time of
	 * remaining tests.
	 */
	@Parameter(property = MavenPluginConfigKeys.TIERED_TIER2_FRACTION, defaultValue = "0.5")
	private double tier2Fraction;

	/**
	 * If true, tier 2 selects tests by cumulative expected duration (favors more
	 * fast tests). If false, selects by count (top N% by score).
	 */
	@Parameter(property = MavenPluginConfigKeys.TIERED_WEIGHT_BY_DURATION, defaultValue = "true")
	private boolean weightByDuration;

	/** File to write the tier 1 test classes to. */
	@Parameter(property = MavenPluginConfigKeys.TIERED_TIER1_FILE, defaultValue = "${project.build.directory}/test-order-tier1.txt")
	private String tier1File;

	/** File to write the tier 2 test classes to. */
	@Parameter(property = MavenPluginConfigKeys.TIERED_TIER2_FILE, defaultValue = "${project.build.directory}/test-order-tier2.txt")
	private String tier2File;

	/** File to write the tier 3 test classes to. */
	@Parameter(property = MavenPluginConfigKeys.TIERED_TIER3_FILE, defaultValue = "${project.build.directory}/test-order-tier3.txt")
	private String tier3File;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		if ("pom".equals(project.getPackaging())) {
			getLog().debug("[test-order] Skipping tiered-select — POM module.");
			return;
		}
		if (skipIfNotExplicitlySelectedReactorProject("tiered-select"))
			return;
		SurefireHelper.validateNoClassLevelParallel(project, getLog());

		if (tier2Fraction < 0 || tier2Fraction > 1) {
			throw new MojoExecutionException("[test-order] tiered.tier2Fraction must be in [0, 1]: " + tier2Fraction);
		}

		// R16-4 (tiered-select parity): When user filters to specific tests via
		// -Dtest, skip tiered selection entirely.
		String userTestFilter = session != null && session.getUserProperties() != null
				? session.getUserProperties().getProperty("test")
				: null;
		if (userTestFilter != null && !userTestFilter.isBlank()) {
			getLog().info("[test-order] Skipping tiered selection — -Dtest=" + userTestFilter
					+ " filter active. test-order will not override your explicit test selection.");
			project.getProperties().setProperty("testorder.auto.active", "true");
			return;
		}

		Path idxPath = resolveIndexPath();
		if (!Files.exists(idxPath)) {
			autoAggregateOrFail(idxPath);
		}
		ensureReadableIndex(idxPath, "tiered-select", false);

		// Warn about test phase only after we know the index is present — otherwise
		// this misleading message appears before the real "no index" error.
		if (session != null && session.getGoals() != null && session.getGoals().stream().noneMatch(g -> g.equals("test")
				|| g.equals("verify") || g.equals("install") || g.equals("package") || g.equals("deploy"))) {
			getLog().warn("[test-order] The 'tiered-select' goal configures Surefire but does not execute tests."
					+ " Include the test phase: mvn test-order:tiered-select test");
		}

		if ("explicit".equalsIgnoreCase(changeMode)) {
			try {
				warnUnknownChangedClasses(detectChangedClasses(), DependencyMap.load(idxPath));
			} catch (IOException e) {
				throw new MojoExecutionException("Failed to validate explicitly changed classes", e);
			}
		}

		PluginContext pctx = buildPluginContextBuilder().build();

		// Run change analysis
		ChangeAnalysis.Result analysis;
		try {
			analysis = ChangeAnalysis.analyze(pctx, ChangeAnalysis.Options.FOR_SELECTION);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to analyze changes for tiered selection", e);
		}

		Set<String> alwaysRun = discoverAlwaysRunClasses();

		// Run tiered selection
		TieredSelectOperation.TieredSelectResult result;
		try {
			result = TieredSelectOperation.select(new TieredSelectOperation.TieredSelectConfig(analysis.depMap(),
					analysis.state(), analysis.changedClasses(), analysis.changedTests(), analysis.weights(),
					tier2Fraction, weightByDuration, alwaysRun, Path.of(tier1File), Path.of(tier2File),
					Path.of(tier3File), pluginLog()));
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to run tiered test selection", e);
		}

		TieredTestSelector.TieredSelection selection = result.selection();

		// Configure Surefire to run only tier 1
		SurefireHelper.warnSelectModeFilters(project, getLog());
		boolean tier2RanInline = false;
		boolean tier3RanInline = false;
		if (!selection.tier1().isEmpty()) {
			SurefireHelper.configureIncludes(project, selection.tier1(), true);
			getLog().info("[test-order] Tier 1: running " + selection.tier1().size() + " change-affected tests");
		} else {
			getLog().info("[test-order] Tier 1: no change-affected tests — skipping to tier 2.");
			// If tier 1 is empty, run tier 2 immediately
			if (!selection.tier2().isEmpty()) {
				SurefireHelper.configureIncludes(project, selection.tier2(), true);
				getLog().info("[test-order] Running " + selection.tier2().size() + " tier-2 tests directly");
				// Clear the tier 2 file so downstream CI steps don't re-run these tests
				clearTierFile(Path.of(tier2File));
				tier2RanInline = true;
			} else if (!selection.tier3().isEmpty()) {
				SurefireHelper.configureIncludes(project, selection.tier3(), true);
				getLog().info("[test-order] Running " + selection.tier3().size() + " tier-3 tests directly");
				// Clear the tier 3 file so downstream CI steps don't re-run these tests
				clearTierFile(Path.of(tier3File));
				tier3RanInline = true;
			} else {
				getLog().info("[test-order] No tests to run.");
				project.getProperties().setProperty("skipTests", "true");
			}
		}

		// Write orderer config for test ordering within the selected tier
		// Use already-computed analysis results instead of re-running change detection
		// (R7-3)
		Map<String, String> configMap = me.bechberger.testorder.ops.OrdererConfigOperation
				.buildConfig(new me.bechberger.testorder.ops.OrdererConfigOperation.OrdererInput(
						pctx.indexFile().toAbsolutePath().toString(), pctx.stateFile().toAbsolutePath().toString(),
						pctx.weightsFile() != null ? pctx.weightsFile().toAbsolutePath().toString() : null,
						analysis.changedClasses(), analysis.changedTests(), analysis.changedMethods(),
						pctx.scoreOverrides(), pctx.methodOrderingEnabled(), pctx.springContextGrouping(),
						pctx.projectRoot().toAbsolutePath().toString(),
						pctx.sourceRoot() != null ? pctx.sourceRoot().toAbsolutePath().toString() : null,
						pctx.changeMode()));
		writeOrdererConfigFromMap(configMap);

		// Publish tier file paths as properties for run-tier to find
		project.getProperties().setProperty(MavenPluginConfigKeys.TIERED_TIER1_FILE,
				Path.of(tier1File).toAbsolutePath().toString());
		project.getProperties().setProperty(MavenPluginConfigKeys.TIERED_TIER2_FILE,
				Path.of(tier2File).toAbsolutePath().toString());
		project.getProperties().setProperty(MavenPluginConfigKeys.TIERED_TIER3_FILE,
				Path.of(tier3File).toAbsolutePath().toString());

		// Prevent a POM-bound auto goal from overriding the test selection
		project.getProperties().setProperty("testorder.auto.active", "true");

		getLog().info("[test-order] Tier files written:");
		getLog().info("[test-order]   Tier 1 (" + selection.tier1().size() + " tests): " + tier1File);
		getLog().info("[test-order]   Tier 2 (" + selection.tier2().size() + " tests"
				+ (tier2RanInline ? ", already run" : "") + "): " + tier2File);
		getLog().info("[test-order]   Tier 3 (" + selection.tier3().size() + " tests"
				+ (tier3RanInline ? ", already run" : "") + "): " + tier3File);
		// Only suggest tier 2 hint if tier 1 is not empty (meaning tier 2 hasn't
		// already run inline)
		if (!selection.tier1().isEmpty() && (!selection.tier2().isEmpty() || !selection.tier3().isEmpty())) {
			int skippedCount = selection.tier2().size() + selection.tier3().size();
			getLog().warn("[test-order] " + skippedCount + " tests in tier 2/3 were NOT run — "
					+ "this build does not represent full test coverage. Run additional tiers to complete:");
			if (!selection.tier2().isEmpty()) {
				getLog().warn("[test-order]   mvn test-order:run-tier test -Dtestorder.tiered.currentTier=2");
			}
			if (!selection.tier3().isEmpty()) {
				getLog().warn("[test-order]   mvn test-order:run-tier test -Dtestorder.tiered.currentTier=3");
			}
		} else if (selection.tier1().isEmpty() && tier2RanInline && !selection.tier3().isEmpty()) {
			// Tier 2 ran inline — remind user that tier 3 still needs a separate run
			getLog().warn("[test-order] " + selection.tier3().size() + " tests in tier 3 were NOT run — "
					+ "this build does not represent full test coverage. Run:");
			getLog().warn("[test-order]   mvn test-order:run-tier test -Dtestorder.tiered.currentTier=3");
		}

		CiSummaryWriter
				.writeSummary(
						new CiSummaryWriter.SummaryInput(analysis.depMap().testClasses().size(), selection.tier1(),
								java.util.stream.Stream.concat(selection.tier2().stream(), selection.tier3().stream())
										.toList(),
								analysis.changedClasses(), analysis.changedTests(), java.util.List.of(),
								"tiered-select", 1, Path.of(project.getBuild().getDirectory())),
						pluginLog());
	}

	/**
	 * Clear a tier file that was already executed inline, to prevent duplicate
	 * runs.
	 */
	private void clearTierFile(Path tierFile) {
		try {
			if (Files.exists(tierFile)) {
				Files.writeString(tierFile, "");
			}
		} catch (IOException e) {
			getLog().warn("[test-order] Could not clear tier file " + tierFile + ": " + e.getMessage());
		}
	}
}
