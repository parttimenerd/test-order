package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.TestSelector;

/**
 * Runs a specific tier of tests from a previous
 * {@code test-order:tiered-select} invocation.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * # Run tier 2 (top-scored remaining — only if tier 1 passed)
 * mvn test-order:run-tier test -Dtestorder.tiered.currentTier=2
 *
 * # Run tier 3 (the rest — only if tiers 1+2 passed)
 * mvn test-order:run-tier test -Dtestorder.tiered.currentTier=3
 * </pre>
 *
 * <p>
 * CI pipelines should only invoke each successive tier if the previous one
 * passed. This goal does <em>not</em> enforce that — the CI workflow (GitHub
 * Actions, Jenkins, etc.) controls the fail-fast logic.
 */
@Mojo(name = "run-tier", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class RunTierMojo extends AbstractTestOrderMojo {

	/** Which tier to run: 2 or 3. */
	@Parameter(property = MavenPluginConfigKeys.TIERED_CURRENT_TIER, required = true)
	private int currentTier;

	/** File containing the tier 2 test classes (written by tiered-select). */
	@Parameter(property = MavenPluginConfigKeys.TIERED_TIER2_FILE, defaultValue = "${project.build.directory}/test-order-tier2.txt")
	private String tier2File;

	/** File containing the tier 3 test classes (written by tiered-select). */
	@Parameter(property = MavenPluginConfigKeys.TIERED_TIER3_FILE, defaultValue = "${project.build.directory}/test-order-tier3.txt")
	private String tier3File;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		if ("pom".equals(project.getPackaging())) {
			getLog().debug("[test-order] Skipping run-tier — POM module.");
			return;
		}
		if (skipIfNotExplicitlySelectedReactorProject("run-tier")) {
			return;
		}
		// Warn if 'test' phase is likely not going to run
		if (session != null && session.getGoals() != null && session.getGoals().stream().noneMatch(g -> g.equals("test")
				|| g.equals("verify") || g.equals("install") || g.equals("package") || g.equals("deploy"))) {
			getLog().warn("[test-order] The 'run-tier' goal configures Surefire but does not execute tests."
					+ " Include the test phase: mvn test-order:run-tier test");
		}
		// Prevent a POM-bound auto goal from overriding the test selection
		project.getProperties().setProperty("testorder.auto.active", "true");

		if (currentTier != 2 && currentTier != 3) {
			throw new MojoExecutionException(
					"[test-order] testorder.tiered.currentTier must be 2 or 3, got: " + currentTier);
		}

		Path tierFile = currentTier == 2 ? Path.of(tier2File) : Path.of(tier3File);

		if (!Files.exists(tierFile)) {
			getLog().info("[test-order] No tier-" + currentTier + " file found at " + tierFile
					+ " — skipping (no tests assigned to this tier)."
					+ " If unexpected, run 'mvn test-order:tiered-select test' first to create tier files.");
			project.getProperties().setProperty("skipTests", "true");
			return;
		}

		List<String> tests;
		try {
			tests = TestSelector.readTestList(tierFile);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to read tier-" + currentTier + " tests file", e);
		}

		if (tests.isEmpty()) {
			getLog().info("[test-order] Tier-" + currentTier + " test list is empty — skipping.");
			project.getProperties().setProperty("skipTests", "true");
			return;
		}

		// Validate that at least some listed classes still exist in target/test-classes
		Path testClassesDir = Path.of(project.getBuild().getTestOutputDirectory());
		if (java.nio.file.Files.isDirectory(testClassesDir)) {
			List<String> unresolvable = tests.stream()
					.filter(t -> !java.nio.file.Files.exists(testClassesDir.resolve(t.replace('.', '/') + ".class")))
					.toList();
			if (!unresolvable.isEmpty()) {
				if (unresolvable.size() == tests.size()) {
					getLog().warn("[test-order] None of the tier-" + currentTier
							+ " test classes exist in target/test-classes. "
							+ "The tier file may be stale (from a previous tiered-select run). "
							+ "Re-run: mvn test-order:tiered-select test");
					project.getProperties().setProperty("skipTests", "true");
					return;
				}
				getLog().warn("[test-order] " + unresolvable.size() + " of " + tests.size() + " tier-" + currentTier
						+ " test classes not found in target/test-classes "
						+ "(classes may have been renamed/deleted since tiered-select).");
				tests = tests.stream().filter(t -> !unresolvable.contains(t)).toList();
			}
		}

		getLog().info("[test-order] Running " + tests.size() + " tier-" + currentTier + " test classes");

		// Write orderer config so tests are still prioritized within the tier
		if (indexFile != null && !indexFile.isBlank() && Files.exists(ctx.resolveIndexFile(indexFile))) {
			// Detect changed classes for within-tier ordering (R7-5: don't pass empty sets)
			Set<String> changed = detectChangedClasses();
			Set<String> changedTests = detectChangedTestClasses();
			writeOrdererConfig(changed, changedTests);
		} else {
			Path runtimeDir = runtimeConfigDir();
			injectTestClasspath(resolveOrdererClasspath());
			injectTestClasspath(runtimeDir);
			ensureListenerServiceFile(runtimeDir);
			if (isTestNGOnTestClasspath()) {
				ensureTestNGListenerServiceFile(runtimeDir);
			}
		}

		SurefireHelper.warnSelectModeFilters(project, getLog());
		SurefireHelper.configureIncludes(project, tests, true);
	}
}
