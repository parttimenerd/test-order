package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.TestSelector;

/**
 * Configures Surefire to run the remaining test classes that were deferred by a
 * previous {@code test-order:select} or {@code test-order:auto} goal.
 * <p>
 * Usage: {@code mvn test-order:run-remaining test}
 */
@Mojo(name = "run-remaining", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class RunRemainingMojo extends AbstractTestOrderMojo {

	/** File containing the remaining test classes (one FQCN per line). */
	@Parameter(property = MavenPluginConfigKeys.SELECT_REMAINING_FILE, defaultValue = "${project.build.directory}/test-order-remaining.txt")
	private String remainingFile;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip) {
			getLog().info("[test-order] Skipping — testorder.skip=true");
			return;
		}
		if (skipIfNotExplicitlySelectedReactorProject("run-remaining")) {
			return;
		}
		// Warn if 'test' phase is likely not going to run
		if (session != null && session.getGoals() != null
				&& session.getGoals().stream().noneMatch(g -> g.equals("test") || g.equals("verify")
						|| g.equals("install") || g.equals("package") || g.equals("deploy"))) {
			getLog().warn("[test-order] The 'run-remaining' goal configures Surefire but does not execute tests."
				+ " Include the test phase: mvn test-order:run-remaining test");
		}
		// Prevent a POM-bound auto goal from overriding the test selection
		project.getProperties().setProperty("testorder.auto.active", "true");

		Path remaining = Path.of(remainingFile);
		if (!Files.exists(remaining)) {
			getLog().info("[test-order] No remaining-tests file found at " + remaining + " — nothing to run.");
			project.getProperties().setProperty("skipTests", "true");
			return;
		}

		List<String> tests;
		try {
			tests = TestSelector.readTestList(remaining);
			Files.deleteIfExists(remaining);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to read remaining tests file", e);
		}

		if (tests.isEmpty()) {
			getLog().info("[test-order] Remaining tests file is empty — skipping tests.");
			project.getProperties().setProperty("skipTests", "true");
			return;
		}

		getLog().info("[test-order] Running " + tests.size() + " remaining test classes");

		// Write orderer config so remaining tests are still prioritized by
		// failure history, duration, and dependency coverage (no change-based scoring).
		// If the index file is available, write the full config; otherwise just
		// inject the classpath and listener so telemetry still records outcomes.
		if (indexFile != null && !indexFile.isBlank() && Files.exists(ctx.resolveIndexFile(indexFile))) {
			writeOrdererConfig(Set.of(), Set.of());
		} else {
			Path runtimeDir = runtimeConfigDir();
			injectTestClasspath(resolveOrdererClasspath());
			injectTestClasspath(runtimeDir);
			ensureListenerServiceFile(runtimeDir);
			if (isTestNGOnTestClasspath()) {
				ensureTestNGListenerServiceFile(runtimeDir);
			}
		}

		SurefireHelper.configureIncludes(project, tests, true);
	}
}
