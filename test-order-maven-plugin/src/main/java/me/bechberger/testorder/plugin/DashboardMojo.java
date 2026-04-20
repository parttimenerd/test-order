package me.bechberger.testorder.plugin;

import me.bechberger.testorder.DashboardGenerator;
import me.bechberger.testorder.DashboardGenerator.ScoredTest;
import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestScorer;
import me.bechberger.testorder.changes.ChangeComplexity;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;
import me.bechberger.testorder.changes.StructuralDiff;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * Generates a self-contained HTML dashboard visualising the test-order scoring,
 * dependency graph, run history, and coverage data.
 *
 * <p>Usage: {@code mvn test-order:dashboard}
 *
 * <p>Output: {@code target/test-order-dashboard/index.html}
 */
@Mojo(name = "dashboard", defaultPhase = LifecyclePhase.VALIDATE)
public class DashboardMojo extends ShowOrderMojo {

    private static final String TEMPLATE_RESOURCE = "dashboard-template.html";
    public static final List<String> WEB_ASSETS =
            List.of("vue.global.prod.js", "chart.umd.min.js", "d3.min.js");

    /** Output HTML file path. */
    @Parameter(property = MavenPluginConfigKeys.DASHBOARD_OUTPUT,
               defaultValue = "${project.build.directory}/test-order-dashboard/index.html")
    protected String dashboardOutput;

    /** Optional JaCoCo coverage report directory. */
    @Parameter(property = MavenPluginConfigKeys.DASHBOARD_COVERAGE_DIR,
               defaultValue = "${project.build.directory}/site/jacoco")
    private String coverageDir;

    /** If true, attempt to open the generated dashboard in the default browser. */
    @Parameter(property = MavenPluginConfigKeys.DASHBOARD_OPEN, defaultValue = "false")
    private boolean openBrowser;

    @Override
    public void execute() throws MojoExecutionException {
        initContext();

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
                walk.filter(p -> p.toString().endsWith(".class") && !p.toString().contains("$"))
                        .forEach(p -> {
                            String rel = testClassesDir.relativize(p).toString();
                            allTests.add(rel.replace('/', '.').replace('\\', '.').replaceAll("\\.class$", ""));
                        });
            } catch (IOException e) {
                getLog().debug("[test-order] dashboard: could not scan test-classes: " + e.getMessage());
            }
        }

        TestScorer scorer = new TestScorer.Builder(sw, depMap, state, changed, changedTests)
                .testClassNames(depMap.testClasses())
                .changedMembers(changedMembers)
                .changeComplexity(changeComplexityMap)
                .build();

        List<ScoredTest> scored = new ArrayList<>();
        for (String testClass : allTests) {
            TestScorer.ScoreResult r = scorer.score(testClass);
            long dur = state.getDuration(testClass, -1);
            double var = state.getDurationVariance(testClass, -1.0);
            scored.add(new ScoredTest(testClass, r, dur, var));
        }
        scored.sort(Comparator
                .<ScoredTest, Integer>comparing(s -> s.result().score()).reversed()
                .thenComparingLong(s -> s.duration() >= 0 ? s.duration() : Long.MAX_VALUE)
                .thenComparing(ScoredTest::name));

        long medianDuration = DashboardGenerator.computeMedian(scored.stream()
                .filter(s -> s.duration() >= 0)
                .mapToLong(ScoredTest::duration)
                .toArray());

        // build JSON data model via shared DashboardGenerator
        DashboardGenerator gen = new DashboardGenerator(
                project.getArtifactId(), stateFile, indexFile, getPluginVersion());
        Map<String, Object> data = gen.buildData(
                scored, changed, changedTests, state, sw, depMap, medianDuration);

        // load template, inject data and inline JS assets
        String template = loadTemplate();
        String html = gen.injectIntoTemplate(template, data);
        html = gen.injectAssets(html,
                loadAsset("vue.global.prod.js"),
                loadAsset("chart.umd.min.js"),
                loadAsset("d3.min.js"));

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
    // Delegated to DashboardGenerator in test-order-core (shared with Gradle plugin)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String loadTemplate() throws MojoExecutionException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (is == null) {
                throw new MojoExecutionException(
                        "Dashboard template not found in classpath: " + TEMPLATE_RESOURCE +
                        " — rebuild the plugin or check packaging.");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to load dashboard template", e);
        }
    }

    private String loadAsset(String name) throws MojoExecutionException {
        try (InputStream in = getClass().getResourceAsStream("/web-assets/" + name)) {
            if (in == null) {
                throw new MojoExecutionException("Missing bundled asset: /web-assets/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to load asset: " + name, e);
        }
    }

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
                try (InputStream in = getClass().getResourceAsStream("/web-assets/" + asset)) {
                    if (in == null) {
                        throw new MojoExecutionException("Missing bundled asset: /web-assets/" + asset);
                    }
                    Files.copy(in, assetsDir.resolve(asset),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to copy dashboard assets", e);
        }
    }

    private String getPluginVersion() {
        try (InputStream is = getClass().getResourceAsStream(
                "/META-INF/maven/me.bechberger/test-order-maven-plugin/pom.properties")) {
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
        return roots.stream()
                .filter(Objects::nonNull)
                .filter(Files::isDirectory)
                .toList();
    }
}
