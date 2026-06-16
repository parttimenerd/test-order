package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

import me.bechberger.testorder.ops.MutationAnalysisOperation;

/**
 * Runs PIT mutation testing scoped to the indexed test classes, computes
 * per-test mutation kill rates, updates the state file, and writes a
 * {@code test-mutation-results.json} report.
 * <p>
 * Designed for nightly / weekly CI runs. Kill rates stored in the state file
 * are automatically picked up by test scoring on subsequent {@code select} /
 * {@code order} runs (when {@code killRateBonus > 0}).
 * <p>
 * In multi-module builds this goal runs once at the reactor root and collects
 * test classpaths, compiled class directories, and source directories from all
 * submodules automatically.
 * <p>
 * Usage: {@code mvn test-order:analyze-mutations}
 */
@Mojo(name = "analyze-mutations", aggregator = true, requiresDependencyResolution = ResolutionScope.TEST)
public class MutationAnalyzeMojo extends AbstractTestOrderMojo {

	/**
	 * Output path for {@code test-mutation-results.json}. Defaults to
	 * {@code target/test-mutation-results.json} in the project root.
	 */
	@Parameter(property = MavenPluginConfigKeys.MUTATIONS_OUTPUT_FILE)
	private String outputFile;

	/**
	 * Maximum seconds to spend on mutation testing (0 = no limit).
	 */
	@Parameter(property = MavenPluginConfigKeys.MUTATIONS_TIME_BUDGET, defaultValue = "0")
	private int timeBudget;

	/**
	 * Comma-separated glob of production class names to mutate. When unset, the
	 * target classes are derived from the dependency index (all production classes
	 * that at least one test covers).
	 */
	@Parameter(property = MavenPluginConfigKeys.MUTATIONS_TARGET_CLASSES)
	private String targetClasses;

	/**
	 * Path to the compiled production classes directory. Defaults to
	 * {@code target/classes} relative to the project root. In multi-module builds,
	 * all modules' classes directories are collected automatically; set this only
	 * to override the root module's directory.
	 */
	@Parameter(property = MavenPluginConfigKeys.MUTATIONS_CLASSES_DIR)
	private String classesDir;

	/**
	 * Path to the compiled test classes directory. Defaults to
	 * {@code target/test-classes} relative to the project root. In multi-module
	 * builds, all modules' test-classes directories are collected automatically;
	 * set this only to override the root module's directory.
	 */
	@Parameter(property = MavenPluginConfigKeys.MUTATIONS_TEST_CLASSES_DIR)
	private String testClassesDir;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;

		Path idxPath = resolveIndexPath();
		if (!Files.exists(idxPath)) {
			throw new MojoExecutionException("Dependency index not found at " + idxPath
					+ ". Run learn mode first: mvn test -Dtestorder.mode=learn"
					+ "\n  For more details: mvn test-order:diagnose");
		}

		Path projectRoot = project.getBasedir().toPath().toAbsolutePath();
		Path statePath = ctx.resolveStateFile(stateFile);
		Path output = outputFile != null && !outputFile.isBlank()
				? Path.of(outputFile)
				: projectRoot.resolve("target/test-mutation-results.json");

		// Collect test classpath from ALL reactor modules to support multi-module
		// builds.
		// For single-module builds, this is equivalent to
		// project.getTestClasspathElements().
		List<String> testClasspath = collectReactorTestClasspath();

		// Resolve optional overrides for class directories
		Path resolvedClassesDir = classesDir != null && !classesDir.isBlank() ? Path.of(classesDir) : null;
		Path resolvedTestClassesDir = testClassesDir != null && !testClassesDir.isBlank()
				? Path.of(testClassesDir)
				: null;

		try {
			MutationAnalysisOperation
					.run(new MutationAnalysisOperation.Config(idxPath, statePath, output, projectRoot, targetClasses,
							timeBudget, pluginLog(), testClasspath, resolvedClassesDir, resolvedTestClassesDir, null));
		} catch (IOException e) {
			throw new MojoExecutionException("Mutation analysis failed: " + e.getMessage(), e);
		}
	}

	/**
	 * Collects the union of test classpath elements from all reactor modules. Falls
	 * back to the root project's classpath if session information is unavailable.
	 * Duplicate entries are removed while preserving order.
	 */
	private List<String> collectReactorTestClasspath() throws MojoExecutionException {
		List<MavenProject> allProjects = session != null && session.getAllProjects() != null
				? session.getAllProjects()
				: List.of(project);

		LinkedHashSet<String> cp = new LinkedHashSet<>();
		for (MavenProject p : allProjects) {
			try {
				// Add the module's own test-classes directory first so it takes precedence
				String testClassesPath = p.getBuild().getTestOutputDirectory();
				if (testClassesPath != null) {
					cp.add(testClassesPath);
				}
				String classesPath = p.getBuild().getOutputDirectory();
				if (classesPath != null) {
					cp.add(classesPath);
				}
				cp.addAll(p.getTestClasspathElements());
			} catch (DependencyResolutionRequiredException e) {
				getLog().warn("[test-order] Could not resolve test classpath for module " + p.getArtifactId() + ": "
						+ e.getMessage());
			}
		}

		// Ensure root project is always included even if not in allProjects
		if (cp.isEmpty()) {
			try {
				cp.addAll(project.getTestClasspathElements());
			} catch (DependencyResolutionRequiredException e) {
				throw new MojoExecutionException("Failed to resolve test classpath: " + e.getMessage(), e);
			}
		}

		return new ArrayList<>(cp);
	}
}
