package me.bechberger.testorder;

import me.bechberger.util.json.PrettyPrinter;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Builds the JSON data model for the test-order HTML dashboard.
 *
 * <p>This class is shared between the Maven plugin ({@code DashboardMojo}) and the
 * Gradle plugin ({@code testOrderDashboard} task).  It converts the in-memory scoring
 * results and run history into the JSON object tree expected by the Vue-based
 * dashboard front-end.
 *
 * <p>Resources (HTML template, JS assets) are intentionally <em>not</em> bundled
 * here; each plugin loads them from its own classpath so that this module stays
 * lean (no ~650 KB of web assets on the test-runner classpath).
 */
public class DashboardGenerator {

    /** Placeholder token that must appear inside the HTML template (data JSON). */
    public static final String DATA_PLACEHOLDER = "/*DASHBOARD_DATA_PLACEHOLDER*/";

    /** Placeholder tokens for the three bundled JS libraries (inlined for file:// compatibility). */
    public static final String VUE_PLACEHOLDER   = "/*VUE_JS_PLACEHOLDER*/";
    public static final String CHART_PLACEHOLDER = "/*CHART_JS_PLACEHOLDER*/";
    public static final String D3_PLACEHOLDER    = "/*D3_JS_PLACEHOLDER*/";

    private final String projectName;
    private final String stateFilePath;
    private final String indexFilePath;
    private final String pluginVersion;

    public DashboardGenerator(String projectName, String stateFilePath,
                               String indexFilePath, String pluginVersion) {
        this.projectName   = projectName;
        this.stateFilePath = stateFilePath;
        this.indexFilePath = indexFilePath;
        this.pluginVersion = pluginVersion;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Builds the complete dashboard JSON model as a {@link Map}.
     *
     * @param scored         tests in descending score order
     * @param changed        source classes detected as changed
     * @param changedTests   test classes detected as changed
     * @param state          current test-order state (durations, failures, runs)
     * @param sw             resolved scoring weights
     * @param depMap         dependency map (test → source class dependencies)
     * @param medianDuration median test duration in ms
     * @return root map suitable for {@link PrettyPrinter#compactPrint(Object)}
     */
    public Map<String, Object> buildData(
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
        proj.put("name", projectName);
        proj.put("generated", DateTimeFormatter.ISO_INSTANT.format(Instant.now().atZone(ZoneOffset.UTC)));
        proj.put("pluginVersion", pluginVersion);
        proj.put("stateFilePath", stateFilePath);
        proj.put("indexFilePath", indexFilePath);
        root.put("project", proj);

        // scoring weights
        Map<String, Object> weightsMap = new LinkedHashMap<>();
        weightsMap.put("newTest",          sw.newTest());
        weightsMap.put("changedTest",      sw.changedTest());
        weightsMap.put("maxFailure",       sw.maxFailure());
        weightsMap.put("speed",            sw.speed());
        weightsMap.put("speedPenalty",     sw.speedPenalty());
        weightsMap.put("depOverlap",       sw.depOverlap());
        weightsMap.put("changeComplexity", sw.changeComplexity());
        weightsMap.put("staticFieldBonus", sw.staticFieldBonus());
        weightsMap.put("coverageBonus",    sw.coverageBonus());
        root.put("weights", weightsMap);

        // weight definitions (min/max for sliders)
        List<Object> weightDefs = new ArrayList<>();
        for (TestOrderState.WeightDef def : TestOrderState.WEIGHT_DEFS) {
            Map<String, Object> wd = new LinkedHashMap<>();
            wd.put("name",         def.name());
            wd.put("defaultValue", def.defaultValue());
            wd.put("min",          def.min());
            wd.put("max",          def.max());
            weightDefs.add(wd);
        }
        root.put("weightDefs", weightDefs);

        // state config
        Map<String, Object> configMap = new LinkedHashMap<>();
        configMap.put("failureDecay",            state.failureDecay());
        configMap.put("durationAlpha",           state.durationAlpha());
        configMap.put("failurePruneThreshold",   state.failurePruneThreshold());
        configMap.put("emaVarianceThreshold",    state.emaVarianceThreshold());
        configMap.put("historyMaxRuns",          state.historyMaxRuns());
        root.put("config", configMap);

        root.put("medianDuration",   medianDuration);
        root.put("changedClasses",   new ArrayList<>(changed));
        root.put("changedTestClasses", new ArrayList<>(changedTests));

        // test entries
        List<Object> tests = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            ScoredTest st = scored.get(i);
            TestScorer.ScoreResult r = st.result();
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name",                st.name());
            t.put("rank",                i + 1);
            t.put("score",               r.score());
            t.put("depOverlap",          r.depOverlap());
            t.put("depTotal",            r.depTotal());
            t.put("failScore",           r.failScore());
            t.put("speedRatio",          r.speedRatio());
            t.put("complexityOverlap",   r.complexityOverlap());
            t.put("isNew",               r.isNew());
            t.put("isChanged",           r.isChanged());
            t.put("isFast",              r.isFast());
            t.put("isSlow",              r.isSlow());
            t.put("hasStaticFieldOverlap", r.hasStaticFieldOverlap());
            t.put("duration",            st.duration());
            t.put("durationVariance",    st.durationVariance());
            Set<String> deps = depMap.get(st.name());
            t.put("deps", deps != null ? new ArrayList<>(deps) : new ArrayList<>());
            if (depMap.hasMemberDeps()) {
                Set<String> memberDeps = depMap.getMemberDeps(st.name());
                t.put("memberDeps", memberDeps != null ? new ArrayList<>(memberDeps) : null);
            } else {
                t.put("memberDeps", null);
            }
            tests.add(t);
        }
        root.put("tests", tests);

        // run history (most-recent first)
        List<Object> runs = new ArrayList<>();
        List<TestOrderState.RunRecord> history = state.runs();
        for (int ri = history.size() - 1; ri >= 0; ri--) {
            TestOrderState.RunRecord rr = history.get(ri);
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("timestamp",          rr.timestamp());
            run.put("totalTests",         rr.totalTests());
            run.put("totalFailures",      rr.totalFailures());
            run.put("firstFailurePosition", rr.firstFailurePosition());
            run.put("apfd",               rr.apfd());
            List<Object> outcomes = new ArrayList<>();
            for (TestOrderState.TestOutcome o : rr.outcomes()) {
                Map<String, Object> oc = new LinkedHashMap<>();
                oc.put("testClass",           o.testClass());
                oc.put("depOverlap",          o.depOverlap());
                oc.put("depTotal",            o.depTotal());
                oc.put("failScore",           o.failScore());
                oc.put("speedRatio",          o.speedRatio());
                oc.put("complexityOverlap",   o.complexityOverlap());
                oc.put("isNew",               o.isNew());
                oc.put("isChanged",           o.isChanged());
                oc.put("isFast",              o.isFast());
                oc.put("isSlow",              o.isSlow());
                oc.put("failed",              o.failed());
                oc.put("hasStaticFieldOverlap", o.hasStaticFieldOverlap());
                outcomes.add(oc);
            }
            run.put("outcomes", outcomes);
            runs.add(run);
        }
        root.put("runs", runs);

        root.put("coverage", null);
        return root;
    }

    /**
     * Injects the data into the provided HTML template string.
     *
     * @param template HTML template containing {@link #DATA_PLACEHOLDER}
     * @param data     map produced by {@link #buildData}
     * @return complete HTML string ready to write to disk
     */
    public String injectIntoTemplate(String template, Map<String, Object> data) {
        return template.replace(DATA_PLACEHOLDER, PrettyPrinter.compactPrint(data));
    }

    /**
     * Inlines the three JS libraries into the template, replacing their placeholder tokens.
     * This produces a fully self-contained HTML file that works when opened via {@code file://}.
     *
     * @param html   template string (after {@link #injectIntoTemplate})
     * @param vue    content of {@code vue.global.prod.js}
     * @param chart  content of {@code chart.umd.min.js}
     * @param d3     content of {@code d3.min.js}
     * @return HTML with all three placeholders replaced by inline JS
     */
    public String injectAssets(String html, String vue, String chart, String d3) {
        return html
                .replace(VUE_PLACEHOLDER,   vue)
                .replace(CHART_PLACEHOLDER, chart)
                .replace(D3_PLACEHOLDER,    d3);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /** Computes the median of an array of durations (will sort the array in place). */
    public static long computeMedian(long[] durations) {
        if (durations.length == 0) return 0;
        Arrays.sort(durations);
        int mid = durations.length / 2;
        return durations.length % 2 == 0 ? (durations[mid - 1] + durations[mid]) / 2 : durations[mid];
    }

    // ── Value types ──────────────────────────────────────────────────────────

    /**
     * Holds a test class name together with its score result and timing metrics.
     * Used as the element type of the {@code scored} list passed to {@link #buildData}.
     */
    public record ScoredTest(
            String name,
            TestScorer.ScoreResult result,
            long duration,
            double durationVariance) {}
}
