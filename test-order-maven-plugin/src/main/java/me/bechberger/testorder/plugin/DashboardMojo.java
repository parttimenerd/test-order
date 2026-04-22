package me.bechberger.testorder.plugin;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.DashboardGenerator;
import me.bechberger.testorder.DashboardGenerator.ScoredTest;
import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestScorer;
import me.bechberger.testorder.changes.ChangeComplexity;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;
import me.bechberger.testorder.changes.StructuralDiff;
import me.bechberger.testorder.dashboard.DashboardResources;

/**
 * Generates a self-contained HTML dashboard visualising the test-order scoring,
 * dependency graph, run history, and coverage data.
 *
 * <p>
 * Usage: {@code mvn test-order:dashboard}
 *
 * <p>
 * Output: {@code target/test-order-dashboard/index.html}
 */
@Mojo(name = "dashboard", defaultPhase = LifecyclePhase.VALIDATE)
public class DashboardMojo extends ShowOrderMojo {

	/**
	 * Names of the bundled JS libraries — delegated to {@link DashboardResources}.
	 */
	public static final List<String> WEB_ASSETS = DashboardResources.WEB_ASSETS;

	/** Output HTML file path. */
	@Parameter(property = MavenPluginConfigKeys.DASHBOARD_OUTPUT, defaultValue = "${project.build.directory}/test-order-dashboard/index.html")
	protected String dashboardOutput;

	/** Optional JaCoCo coverage report directory. */
	@Parameter(property = MavenPluginConfigKeys.DASHBOARD_COVERAGE_DIR, defaultValue = "${project.build.directory}/site/jacoco")
	private String coverageDir;

	/** If true, attempt to open the generated dashboard in the default browser. */
	@Parameter(property = MavenPluginConfigKeys.DASHBOARD_OPEN, defaultValue = "false")
	private boolean openBrowser;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;

		Path idxPath = resolveIndexPath();
		if (!Files.exists(idxPath)) {
			autoAggregateOrFail(idxPath);
		}

		DependencyMap depMap;
		try {
			depMap = DependencyMap.load(idxPath);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to load dependency index", e);
		}

		Set<String> changed = detectChangedClasses();
		Set<String> changedTests = detectChangedTestClasses();
		TestOrderState state = loadState();
		TestOrderState.ScoringWeights sw = resolveWeights(state);

		ChangedMembers changedMembers = null;
		List<StructuralDiff.FileDiff> structuralDiffs = null;
		if (!changed.isEmpty()) {
			try {
				Path projectRoot = project.getBasedir().toPath().toAbsolutePath();
				String structMode = resolveStructuralDiffMode();
				if (structMode != null) {
					StructuralChangeAnalyzer.AnalysisResult analysis;
					if ("since-last-commit".equals(structMode)) {
						analysis = StructuralChangeAnalyzer.analyzeSinceLastCommitFull(projectRoot);
					} else {
						analysis = StructuralChangeAnalyzer.analyzeUncommittedFull(projectRoot);
					}
					changedMembers = analysis.changedMembers();
					structuralDiffs = analysis.diffs();
				}
			} catch (IOException e) {
				getLog().debug("[test-order] dashboard: structural diff failed: " + e.getMessage());
			}
		}

		Map<String, Double> changeComplexityMap = Map.of();
		if (!changed.isEmpty()) {
			List<Path> sourceRoots = resolveSourceRootsPublic();
			if (!sourceRoots.isEmpty()) {
				changeComplexityMap = ChangeComplexity.compute(changed, sourceRoots, changedMembers, structuralDiffs);
			}
		}

		Set<String> allTests = new LinkedHashSet<>(depMap.testClasses());
		allTests.addAll(changedTests);
		Path testClassesDir = Path.of(project.getBuild().getTestOutputDirectory());
		if (Files.isDirectory(testClassesDir)) {
			try (var walk = Files.walk(testClassesDir)) {
				walk.filter(p -> p.toString().endsWith(".class") && !p.toString().contains("$")).forEach(p -> {
					String rel = testClassesDir.relativize(p).toString();
					allTests.add(rel.replace('/', '.').replace('\\', '.').replaceAll("\\.class$", ""));
				});
			} catch (IOException e) {
				getLog().debug("[test-order] dashboard: could not scan test-classes: " + e.getMessage());
			}
		}

		TestScorer scorer = new TestScorer.Builder(sw, depMap, state, changed, changedTests)
				.testClassNames(depMap.testClasses()).changedMembers(changedMembers)
				.changeComplexity(changeComplexityMap).build();

		List<ScoredTest> scored = new ArrayList<>();
		for (String testClass : allTests) {
			TestScorer.ScoreResult r = scorer.score(testClass);
			long dur = state.getDuration(testClass, -1);
			double var = state.getDurationVariance(testClass, -1.0);
			scored.add(new ScoredTest(testClass, r, dur, var));
		}
		scored.sort(Comparator.<ScoredTest, Integer>comparing(s -> s.result().score()).reversed()
				.thenComparingLong(s -> s.duration() >= 0 ? s.duration() : Long.MAX_VALUE)
				.thenComparing(ScoredTest::name));

		long medianDuration = DashboardGenerator.computeMedian(
				scored.stream().filter(s -> s.duration() >= 0).mapToLong(ScoredTest::duration).toArray());

		// build JSON data model via shared DashboardGenerator
		DashboardGenerator gen = new DashboardGenerator(project.getArtifactId(), stateFile, indexFile,
				getPluginVersion());
		Map<String, Object> data = gen.buildData(scored, changed, changedTests, state, sw, depMap, medianDuration);

		if (data.get("coverage") == null && depMap.size() > 0) {
			getLog().warn("[test-order] Coverage data is null despite " + depMap.size()
					+ " test class(es) in the dependency index. "
					+ "Check that your index contains non-empty dependency sets.");
		}

		// load template from shared dashboard module and inject data
		String template;
		try {
			template = DashboardResources.assembleTemplate();
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to load dashboard template", e);
		}
		String html = gen.injectIntoTemplate(template, data);

		// write output
		Path outPath = Path.of(dashboardOutput);
		try {
			Files.createDirectories(outPath.getParent());
			Files.writeString(outPath, html, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to write dashboard to " + outPath, e);
		}

		getLog().info("[test-order] Dashboard written to: " + outPath);
		getLog().info("[test-order] Open in browser: file://" + outPath.toAbsolutePath());

		if (openBrowser) {
			tryOpenBrowser(outPath.toAbsolutePath().toUri());
		}
	}

	// ── JSON model builders ───────────────────────────────────────────────────
	// Delegated to DashboardGenerator in test-order-core (shared with Gradle
	// plugin)

	// ── Helpers ───────────────────────────────────────────────────────────────

	/**
	 * @deprecated Assets are now inlined into the HTML. This method is kept for
	 *             subclasses that may still rely on it (e.g. tests checking the
	 *             assets directory), but the main generate flow no longer calls it.
	 */
	@Deprecated
	protected void copyWebAssets(Path outputDir) throws MojoExecutionException {
		Path assetsDir = outputDir.resolve("assets");
		try {
			Files.createDirectories(assetsDir);
			for (String asset : WEB_ASSETS) {
				String content = DashboardResources.loadWebAsset(asset);
				Files.writeString(assetsDir.resolve(asset), content, StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to copy dashboard assets", e);
		}
	}

	private String getPluginVersion() {
		try (InputStream is = getClass()
				.getResourceAsStream("/META-INF/maven/me.bechberger/test-order-maven-plugin/pom.properties")) {
			if (is != null) {
				Properties props = new Properties();
				props.load(is);
				return props.getProperty("version", "unknown");
			}
		} catch (IOException ignored) {
		}
		return "unknown";
	}

	protected void tryOpenBrowser(URI uri) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(uri);
			}
		} catch (Exception e) {
			getLog().debug("[test-order] dashboard: could not open browser: " + e.getMessage());
		}
	}

	/** Returns the resolved output path for the generated dashboard HTML. */
	protected Path resolveOutputPath() {
		return Path.of(dashboardOutput);
	}

	/** Exposed for subclasses — identical to ShowOrderMojo's private method. */
	private List<Path> resolveSourceRootsPublic() {
		LinkedHashSet<Path> roots = new LinkedHashSet<>();
		roots.add(ChangeDetectionHelper.resolveSourceRoot(project, sourceRoot));
		Path projectRoot = project.getBasedir().toPath().toAbsolutePath();
		Path kotlinRoot = projectRoot.resolve("src/main/kotlin");
		if (Files.isDirectory(kotlinRoot)) {
			roots.add(kotlinRoot);
		}
		return roots.stream().filter(Objects::nonNull).filter(Files::isDirectory).toList();
	}
}
