package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TieredTestSelector;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.TieredSelectOperation;
import me.bechberger.testorder.ops.workflows.ChangeAnalysis;

/**
 * Runs all three tiers in a single Maven invocation (single Surefire JVM):
 * <ol>
 * <li><b>Tier 1</b>: change-affected + {@code @AlwaysRun} + new tests</li>
 * <li><b>Tier 2</b>: top-scored remaining (configurable duration fraction)</li>
 * <li><b>Tier 3</b>: all remaining tests (optionally sharded across
 * runners)</li>
 * </ol>
 *
 * <p>
 * Failures in tier 1 or tier 2 abort the build before the next tier runs — same
 * fail-fast semantics as the multi-step approach, but with a single JVM startup
 * cost.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * mvn test-order:run-tiered test \
 *   -Dtestorder.changeMode=since-last-commit \
 *   -Dtestorder.tiered.tier2Fraction=0.5
 * </pre>
 *
 * <p>
 * Parallel sharding of tier 3 (across N runners):
 *
 * <pre>
 * # Runner 1 of 3
 * mvn test-order:run-tiered test -Dtestorder.tiered.shard=1/3
 * # Runner 2 of 3
 * mvn test-order:run-tiered test -Dtestorder.tiered.shard=2/3
 * # Runner 3 of 3
 * mvn test-order:run-tiered test -Dtestorder.tiered.shard=3/3
 * </pre>
 *
 * <p>
 * All runners execute tiers 1 and 2 fully. Only tier 3 is sharded.
 */
@Mojo(name = "run-tiered", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class RunTieredMojo extends AbstractTestOrderMojo {

	/**
	 * Fraction of remaining test duration budget allocated to tier 2. Value in [0,
	 * 1]. Default 0.5.
	 */
	@Parameter(property = MavenPluginConfigKeys.TIERED_TIER2_FRACTION, defaultValue = "0.5")
	private double tier2Fraction;

	/**
	 * If true, tier 2 selects tests by cumulative expected duration. If false,
	 * selects by count.
	 */
	@Parameter(property = MavenPluginConfigKeys.TIERED_WEIGHT_BY_DURATION, defaultValue = "true")
	private boolean weightByDuration;

	/** File to write the tier 1 test classes to (for inspection). */
	@Parameter(property = MavenPluginConfigKeys.TIERED_TIER1_FILE, defaultValue = "${project.build.directory}/test-order-tier1.txt")
	private String tier1File;

	/** File to write the tier 2 test classes to (for inspection). */
	@Parameter(property = MavenPluginConfigKeys.TIERED_TIER2_FILE, defaultValue = "${project.build.directory}/test-order-tier2.txt")
	private String tier2File;

	/** File to write the tier 3 test classes to (for inspection). */
	@Parameter(property = MavenPluginConfigKeys.TIERED_TIER3_FILE, defaultValue = "${project.build.directory}/test-order-tier3.txt")
	private String tier3File;

	/**
	 * Shard tier-3 tests across N parallel runners. Format: {@code k/N}, e.g.
	 * {@code 2/3} for the second of three runners. Tier 1 and tier 2 always run in
	 * full on every runner.
	 */
	@Parameter(property = MavenPluginConfigKeys.TIERED_SHARD)
	private String shard;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		if ("pom".equals(project.getPackaging())) {
			getLog().debug("[test-order] Skipping run-tiered — POM module.");
			return;
		}
		if (skipIfNotExplicitlySelectedReactorProject("run-tiered"))
			return;
		SurefireHelper.validateNoClassLevelParallel(project, getLog());

		if (tier2Fraction < 0 || tier2Fraction > 1) {
			throw new MojoExecutionException("[test-order] tiered.tier2Fraction must be in [0, 1]: " + tier2Fraction);
		}

		Path idxPath = resolveIndexPath();
		if (!Files.exists(idxPath)) {
			autoAggregateOrFail(idxPath);
		}
		ensureReadableIndex(idxPath, "run-tiered", false);

		if (session != null && session.getGoals() != null && session.getGoals().stream().noneMatch(g -> g.equals("test")
				|| g.equals("verify") || g.equals("install") || g.equals("package") || g.equals("deploy"))) {
			getLog().warn("[test-order] The 'run-tiered' goal configures Surefire but does not execute tests."
					+ " Include the test phase: mvn test-order:run-tiered test");
		}

		if ("explicit".equalsIgnoreCase(changeMode)) {
			try {
				warnUnknownChangedClasses(detectChangedClasses(), DependencyMap.load(idxPath));
			} catch (IOException e) {
				throw new MojoExecutionException("Failed to validate explicitly changed classes", e);
			}
		}

		PluginContext pctx = buildPluginContextBuilder().build();

		ChangeAnalysis.Result analysis;
		try {
			analysis = ChangeAnalysis.analyze(pctx, ChangeAnalysis.Options.FOR_SELECTION);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to analyze changes for run-tiered", e);
		}

		Set<String> alwaysRun = discoverAlwaysRunClasses();

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

		// Build the ordered run list: tier1 + tier2 + tier3 (sharded)
		List<String> tier3 = selection.tier3();
		if (shard != null && !shard.isBlank()) {
			try {
				tier3 = TieredTestSelector.applyShard(tier3, shard);
				getLog().info("[test-order] Shard " + shard + ": running " + tier3.size() + " of "
						+ selection.tier3().size() + " tier-3 tests");
			} catch (IllegalArgumentException e) {
				throw new MojoExecutionException("[test-order] Invalid shard spec: " + e.getMessage());
			}
		}

		java.util.List<String> allTests = new java.util.ArrayList<>(
				selection.tier1().size() + selection.tier2().size() + tier3.size());
		allTests.addAll(selection.tier1());
		allTests.addAll(selection.tier2());
		allTests.addAll(tier3);

		int total = allTests.size();
		if (total == 0) {
			getLog().info("[test-order] run-tiered: no tests to run.");
			project.getProperties().setProperty("skipTests", "true");
			return;
		}

		getLog().info("[test-order] run-tiered: " + selection.tier1().size() + " tier-1 + " + selection.tier2().size()
				+ " tier-2 + " + tier3.size() + " tier-3 = " + total + " tests");

		// Write orderer config so tests are ordered within each tier
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

		project.getProperties().setProperty("testorder.auto.active", "true");

		SurefireHelper.warnSelectModeFilters(project, getLog());
		SurefireHelper.configureIncludes(project, allTests, true);
	}
}
