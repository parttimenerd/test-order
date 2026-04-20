package me.bechberger.testorder.plugin;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestScorer;
import me.bechberger.testorder.changes.ChangeComplexity;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;
import me.bechberger.testorder.changes.StructuralDiff;
import me.bechberger.util.json.PrettyPrinter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    private static final String DATA_PLACEHOLDER = "/*DASHBOARD_DATA_PLACEHOLDER*/";
    private static final String TEMPLATE_RESOURCE = "dashboard-template.html";

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

        long medianDuration = computeMedian(scored.stream()
                .filter(s -> s.duration() >= 0)
                .mapToLong(ScoredTest::duration)
                .toArray());

        // build JSON data model
        Map<String, Object> data = buildDashboardData(
                scored, changed, changedTests, state, sw, depMap, medianDuration);

        String json = PrettyPrinter.compactPrint(data);

        // load template, inject data
        String template = loadTemplate();
        String html = template.replace(DATA_PLACEHOLDER, json);

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

    private Map<String, Object> buildDashboardData(
            List<ScoredTest> scored,
            Set<String> changed,
            Set<String> changedTests,
            TestOrderState state,
            TestOrderState.ScoringWeights sw,
            DependencyMap depMap,
            long medianDuration) {

        Map<String, Object> root = new LinkedHashMap<>();

        // project info
        Map<String, Object> proj = new LinkedHashMap<>();
        proj.put("name", project.getArtifactId());
        proj.put("generated", DateTimeFormatter.ISO_INSTANT.format(Instant.now().atZone(ZoneOffset.UTC)));
        proj.put("pluginVersion", getPluginVersion());
        proj.put("stateFilePath", stateFile);
        proj.put("indexFilePath", indexFile);
        root.put("project", proj);

        // scoring weights
        Map<String, Object> weightsMap = new LinkedHashMap<>();
        weightsMap.put("newTest", sw.newTest());
        weightsMap.put("changedTest", sw.changedTest());
        weightsMap.put("maxFailure", sw.maxFailure());
        weightsMap.put("speed", sw.speed());
        weightsMap.put("speedPenalty", sw.speedPenalty());
        weightsMap.put("depOverlap", sw.depOverlap());
        weightsMap.put("changeComplexity", sw.changeComplexity());
        weightsMap.put("staticFieldBonus", sw.staticFieldBonus());
        weightsMap.put("coverageBonus", sw.coverageBonus());
        root.put("weights", weightsMap);

        // weight definitions (min/max for sliders)
        ArrayList<Object> weightDefs = new ArrayList<>();
        for (TestOrderState.WeightDef def : TestOrderState.WEIGHT_DEFS) {
            Map<String, Object> wd = new LinkedHashMap<>();
            wd.put("name", def.name());
            wd.put("defaultValue", def.defaultValue());
            wd.put("min", def.min());
            wd.put("max", def.max());
            weightDefs.add(wd);
        }
        root.put("weightDefs", weightDefs);

        // state config
        Map<String, Object> configMap = new LinkedHashMap<>();
        configMap.put("failureDecay", state.failureDecay());
        configMap.put("durationAlpha", state.durationAlpha());
        configMap.put("failurePruneThreshold", state.failurePruneThreshold());
        configMap.put("emaVarianceThreshold", state.emaVarianceThreshold());
        configMap.put("historyMaxRuns", state.historyMaxRuns());
        root.put("config", configMap);

        root.put("medianDuration", medianDuration);
        root.put("changedClasses", new ArrayList<>(changed));
        root.put("changedTestClasses", new ArrayList<>(changedTests));

        // test entries
        ArrayList<Object> tests = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            ScoredTest st = scored.get(i);
            TestScorer.ScoreResult r = st.result();
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", st.name());
            t.put("rank", i + 1);
            t.put("score", r.score());
            t.put("depOverlap", r.depOverlap());
            t.put("depTotal", r.depTotal());
            t.put("failScore", r.failScore());
            t.put("speedRatio", r.speedRatio());
            t.put("complexityOverlap", r.complexityOverlap());
            t.put("isNew", r.isNew());
            t.put("isChanged", r.isChanged());
            t.put("isFast", r.isFast());
            t.put("isSlow", r.isSlow());
            t.put("hasStaticFieldOverlap", r.hasStaticFieldOverlap());
            t.put("duration", st.duration());
            t.put("durationVariance", st.durationVariance());
            // class-level deps
            Set<String> deps = depMap.get(st.name());
            t.put("deps", deps != null ? new ArrayList<>(deps) : new ArrayList<>());
            // member-level deps (V4+)
            if (depMap.hasMemberDeps()) {
                Set<String> memberDeps = depMap.getMemberDeps(st.name());
                t.put("memberDeps", memberDeps != null ? new ArrayList<>(memberDeps) : null);
            } else {
                t.put("memberDeps", null);
            }
            tests.add(t);
        }
        root.put("tests", tests);

        // run history
        ArrayList<Object> runs = new ArrayList<>();
        List<TestOrderState.RunRecord> history = state.runs();
        for (int ri = history.size() - 1; ri >= 0; ri--) {  // most recent first
            TestOrderState.RunRecord rr = history.get(ri);
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("timestamp", rr.timestamp());
            run.put("totalTests", rr.totalTests());
            run.put("totalFailures", rr.totalFailures());
            run.put("firstFailurePosition", rr.firstFailurePosition());
            run.put("apfd", rr.apfd());
            ArrayList<Object> outcomes = new ArrayList<>();
            for (TestOrderState.TestOutcome o : rr.outcomes()) {
                Map<String, Object> oc = new LinkedHashMap<>();
                oc.put("testClass", o.testClass());
                oc.put("depOverlap", o.depOverlap());
                oc.put("depTotal", o.depTotal());
                oc.put("failScore", o.failScore());
                oc.put("speedRatio", o.speedRatio());
                oc.put("complexityOverlap", o.complexityOverlap());
                oc.put("isNew", o.isNew());
                oc.put("isChanged", o.isChanged());
                oc.put("isFast", o.isFast());
                oc.put("isSlow", o.isSlow());
                oc.put("failed", o.failed());
                oc.put("hasStaticFieldOverlap", o.hasStaticFieldOverlap());
                outcomes.add(oc);
            }
            run.put("outcomes", outcomes);
            runs.add(run);
        }
        root.put("runs", runs);

        // coverage (null for now; future: parse JaCoCo XML)
        root.put("coverage", null);

        return root;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private record ScoredTest(String name, TestScorer.ScoreResult result, long duration, double durationVariance) {}

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

    private static long computeMedian(long[] sorted) {
        if (sorted.length == 0) return 0;
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid];
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
