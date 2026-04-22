package me.bechberger.testorder.plugin;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.TestSelector;

/**
 * Configures Surefire to run the remaining test classes that were deferred by a
 * previous {@code test-order:select} or {@code test-order:combined} goal.
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
		if (skip) {
			getLog().info("[test-order] Skipping — testorder.skip=true");
			return;
		}
		// Prevent a POM-bound combined goal from overriding the test selection
		project.getProperties().setProperty("testorder.combined.active", "true");

		Path remaining = Path.of(remainingFile);
		if (!Files.exists(remaining)) {
			getLog().info("[test-order] No remaining-tests file found at " + remaining + " — nothing to run.");
			project.getProperties().setProperty("skipTests", "true");
			return;
		}

		List<String> tests;
		try {
			tests = TestSelector.readTestList(remaining);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to read remaining tests file", e);
		}

		if (tests.isEmpty()) {
			getLog().info("[test-order] Remaining tests file is empty — skipping tests.");
			project.getProperties().setProperty("skipTests", "true");
			return;
		}

		getLog().info("[test-order] Running " + tests.size() + " remaining test classes");

		// Inject test-order classpath + service files so orderer/telemetry works
		injectTestClasspath(resolveOrdererClasspath());
		ensureListenerServiceFile();
		if (isTestNGOnTestClasspath()) {
			ensureTestNGListenerServiceFile();
		}

		SurefireHelper.configureIncludes(project, tests, true);
	}
}
