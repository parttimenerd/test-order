package me.bechberger.testorder;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

import me.bechberger.testorder.ml.TestHealthReport;
import me.bechberger.util.json.PrettyPrinter;

/**
 * Builds the JSON data model for the test-order HTML dashboard.
 *
 * <p>
 * This class is shared between the Maven plugin ({@code DashboardMojo}) and the
 * Gradle plugin ({@code testOrderDashboard} task). It converts the in-memory
 * scoring results and run history into the JSON object tree expected by the
 * Vue-based dashboard front-end.
 *
 * <p>
 * Resources (HTML template, JS assets) are intentionally <em>not</em> bundled
 * here; each plugin loads them from its own classpath so that this module stays
 * lean (no ~650 KB of web assets on the test-runner classpath).
 */
public class DashboardGenerator {

	/** Placeholder token that must appear inside the HTML template (data JSON). */
	public static final String DATA_PLACEHOLDER = "/*DASHBOARD_DATA_PLACEHOLDER*/";

	/**
	 * @deprecated JS libraries are now bundled by Vite. Kept for backward
	 *             compatibility.
	 */
	@Deprecated
	public static final String VUE_PLACEHOLDER = "/*VUE_JS_PLACEHOLDER*/";
	/**
	 * @deprecated JS libraries are now bundled by Vite. Kept for backward
	 *             compatibility.
	 */
	@Deprecated
	public static final String CHART_PLACEHOLDER = "/*CHART_JS_PLACEHOLDER*/";
	/**
	 * @deprecated JS libraries are now bundled by Vite. Kept for backward
	 *             compatibility.
	 */
	@Deprecated
	public static final String D3_PLACEHOLDER = "/*D3_JS_PLACEHOLDER*/";

	private final String projectName;
	private final String stateFilePath;
	private final String indexFilePath;
	private final String pluginVersion;

	public DashboardGenerator(String projectName, String stateFilePath, String indexFilePath, String pluginVersion) {
		this.projectName = projectName;
		this.stateFilePath = stateFilePath;
		this.indexFilePath = indexFilePath;
		this.pluginVersion = pluginVersion;
	}

	// ── Public API ────────────────────────────────────────────────────────────

	/**
	 * Builds the complete dashboard JSON model as a {@link Map}.
	 *
	 * @param scored
	 *            tests in descending score order
	 * @param changed
	 *            source classes detected as changed
	 * @param changedTests
	 *            test classes detected as changed
	 * @param state
	 *            current test-order state (durations, failures, runs)
	 * @param sw
	 *            resolved scoring weights
	 * @param depMap
	 *            dependency map (test → source class dependencies)
	 * @param medianDuration
	 *            median test duration in ms
	 * @return root map suitable for {@link PrettyPrinter#compactPrint(Object)}
	 */
	public Map<String, Object> buildData(List<ScoredTest> scored, Set<String> changed, Set<String> changedTests,
			TestOrderState state, TestOrderState.ScoringWeights sw, DependencyMap depMap, long medianDuration) {
		return buildData(scored, changed, changedTests, state, sw, depMap, medianDuration, TestOrderState.WEIGHT_DEFS);
	}

	/**
	 * Overload that accepts explicit weight definitions (e.g. from a user-supplied
	 * weights file), so that slider min/max/defaultValue in the dashboard reflect
	 * the loaded configuration rather than the built-in TOML defaults.
	 */
	public Map<String, Object> buildData(List<ScoredTest> scored, Set<String> changed, Set<String> changedTests,
			TestOrderState state, TestOrderState.ScoringWeights sw, DependencyMap depMap, long medianDuration,
			List<TestOrderState.WeightDef> weightDefs) {
		return buildData(scored, changed, changedTests, state, sw, depMap, medianDuration, weightDefs, null, null);
	}

	/**
	 * Full overload that includes optional ML data for the dashboard.
	 *
	 * @param mlPredictions
	 *            ML failure probability predictions (test class → P(fail)), or null
	 *            if ML is disabled
	 * @param healthReport
	 *            ML health report with test classifications, or null if not
	 *            available
	 */
	public Map<String, Object> buildData(List<ScoredTest> scored, Set<String> changed, Set<String> changedTests,
			TestOrderState state, TestOrderState.ScoringWeights sw, DependencyMap depMap, long medianDuration,
			List<TestOrderState.WeightDef> weightDefs, Map<String, Double> mlPredictions,
			TestHealthReport healthReport) {

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
		List<Object> weightDefsJson = new ArrayList<>();
		for (TestOrderState.WeightDef def : weightDefs) {
			Map<String, Object> wd = new LinkedHashMap<>();
			wd.put("name", def.name());
			wd.put("defaultValue", def.defaultValue());
			wd.put("min", def.min());
			wd.put("max", def.max());
			weightDefsJson.add(wd);
		}
		root.put("weightDefs", weightDefsJson);

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
		List<Object> tests = new ArrayList<>();
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
			Set<String> deps = depMap.get(st.name());
			t.put("deps", deps != null ? new ArrayList<>(deps) : new ArrayList<>());
			if (depMap.hasMemberDeps()) {
				Set<String> memberDeps = depMap.getMemberDeps(st.name());
				t.put("memberDeps", memberDeps != null ? new ArrayList<>(memberDeps) : null);
			} else {
				t.put("memberDeps", null);
			}
			// per-test-method dependencies (FULL_METHOD+ mode)
			if (depMap.hasMethodDeps()) {
				List<Object> methods = new ArrayList<>();
				String prefix = st.name() + "#";
				for (String key : depMap.methodKeys()) {
					if (key.startsWith(prefix)) {
						String methodName = key.substring(prefix.length());
						Set<String> mDeps = depMap.getMethodDeps(key);
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("name", methodName);
						m.put("deps", new ArrayList<>(mDeps));
						m.put("depCount", mDeps.size());
						methods.add(m);
					}
				}
				t.put("methods", methods.isEmpty() ? null : methods);
			} else {
				t.put("methods", null);
			}
			// ML failure prediction (if available)
			if (mlPredictions != null) {
				Double pFail = mlPredictions.get(st.name());
				t.put("mlPFail", pFail != null ? Math.round(pFail * 10000.0) / 10000.0 : null);
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
			run.put("timestamp", rr.timestamp());
			run.put("totalTests", rr.totalTests());
			run.put("totalFailures", rr.totalFailures());
			run.put("firstFailurePosition", rr.firstFailurePosition());
			run.put("apfd", rr.apfd());
			List<Object> outcomes = new ArrayList<>();
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

		// coverage: invert dependency map (test→sources) to (source→tests)
		root.put("coverage", buildCoverageData(depMap));

		// ML section (health report + predictions summary)
		if (healthReport != null) {
			Map<String, Object> ml = new LinkedHashMap<>();
			ml.put("enabled", true);
			ml.put("runsAnalyzed", healthReport.runsAnalyzed());

			Map<String, Object> summary = new LinkedHashMap<>();
			summary.put("healthy", healthReport.byStatus(TestHealthReport.HealthStatus.HEALTHY).size());
			summary.put("degrading", healthReport.byStatus(TestHealthReport.HealthStatus.DEGRADING).size());
			summary.put("flaky", healthReport.byStatus(TestHealthReport.HealthStatus.FLAKY).size());
			summary.put("failing", healthReport.byStatus(TestHealthReport.HealthStatus.FAILING).size());
			ml.put("summary", summary);

			List<Object> health = new ArrayList<>();
			for (var entry : healthReport.tests().values()) {
				Map<String, Object> h = new LinkedHashMap<>();
				h.put("testClass", entry.testClass());
				h.put("status", entry.status().name());
				h.put("flakinessScore", Math.round(entry.flakinessScore() * 10000.0) / 10000.0);
				h.put("degradationTrend", Math.round(entry.degradationTrend() * 10000.0) / 10000.0);
				h.put("recentFailureRate", Math.round(entry.recentFailureRate() * 10000.0) / 10000.0);
				h.put("volatility", Math.round(entry.volatility() * 10000.0) / 10000.0);
				h.put("totalRuns", entry.totalRuns());
				h.put("totalFailures", entry.totalFailures());
				health.add(h);
			}
			ml.put("health", health);

			if (mlPredictions != null && !mlPredictions.isEmpty()) {
				ml.put("hasPredictions", true);
			}

			root.put("ml", ml);
		}

		return root;
	}

	/**
	 * Injects the data into the provided HTML template string.
	 *
	 * @param template
	 *            HTML template containing {@link #DATA_PLACEHOLDER}
	 * @param data
	 *            map produced by {@link #buildData}
	 * @return complete HTML string ready to write to disk
	 */
	public String injectIntoTemplate(String template, Map<String, Object> data) {
		// Escape </script> sequences to prevent XSS when JSON is inlined in HTML
		// <script> block
		String json = PrettyPrinter.compactPrint(data).replace("</", "<\\/");
		return template.replace(DATA_PLACEHOLDER, json);
	}

	/**
	 * Inlines the three JS libraries into the template.
	 *
	 * @deprecated JS libraries are now bundled by Vite into a single IIFE. The
	 *             assembled template from {@code DashboardResources} already
	 *             contains everything. This method is kept for backward
	 *             compatibility.
	 */
	@Deprecated
	public String injectAssets(String html, String vue, String chart, String d3) {
		return html.replace(VUE_PLACEHOLDER, vue).replace(CHART_PLACEHOLDER, chart).replace(D3_PLACEHOLDER, d3);
	}

	// ── Utilities ─────────────────────────────────────────────────────────────

	/**
	 * Computes the median of an array of durations (will sort the array in place).
	 */
	public static long computeMedian(long[] durations) {
		if (durations.length == 0)
			return 0;
		Arrays.sort(durations);
		int mid = durations.length / 2;
		return durations.length % 2 == 0 ? (durations[mid - 1] + durations[mid]) / 2 : durations[mid];
	}

	/**
	 * Inverts the dependency map (test→sources) to build a source→tests coverage
	 * model. Returns {@code null} if the dependency map is empty.
	 */
	private static Map<String, Object> buildCoverageData(DependencyMap depMap) {
		if (depMap.size() == 0)
			return null;

		// invert: source class → set of test classes that exercise it
		Map<String, Set<String>> sourceToTests = new LinkedHashMap<>();
		for (String testClass : depMap.testClasses()) {
			for (String dep : depMap.get(testClass)) {
				sourceToTests.computeIfAbsent(dep, k -> new TreeSet<>()).add(testClass);
			}
		}
		if (sourceToTests.isEmpty())
			return null;

		int totalSourceClasses = sourceToTests.size();

		// invert member deps: source member → set of test classes
		Map<String, Map<String, Set<String>>> sourceMemberToTests = new LinkedHashMap<>();
		if (depMap.hasMemberDeps()) {
			for (String testClass : depMap.testClasses()) {
				for (String memberRef : depMap.getMemberDeps(testClass)) {
					// memberRef = "com.example.Foo#bar"
					int hash = memberRef.indexOf('#');
					if (hash < 0)
						continue;
					String srcClass = memberRef.substring(0, hash);
					String member = memberRef.substring(hash + 1);
					sourceMemberToTests.computeIfAbsent(srcClass, k -> new LinkedHashMap<>())
							.computeIfAbsent(member, k -> new TreeSet<>()).add(testClass);
				}
			}
		}

		// build per-class entries
		List<Object> classes = new ArrayList<>();
		for (var entry : sourceToTests.entrySet()) {
			String fqcn = entry.getKey();
			Set<String> tests = entry.getValue();
			Map<String, Object> cls = new LinkedHashMap<>();
			cls.put("name", fqcn);
			cls.put("testCount", tests.size());
			cls.put("tests", new ArrayList<>(tests));
			// extract package
			int dot = fqcn.lastIndexOf('.');
			cls.put("package", dot > 0 ? fqcn.substring(0, dot) : "(default)");
			// member-level breakdown (when available)
			Map<String, Set<String>> memberMap = sourceMemberToTests.get(fqcn);
			if (memberMap != null && !memberMap.isEmpty()) {
				List<Object> members = new ArrayList<>();
				for (var me : memberMap.entrySet()) {
					Map<String, Object> mem = new LinkedHashMap<>();
					mem.put("name", me.getKey());
					mem.put("testCount", me.getValue().size());
					mem.put("tests", new ArrayList<>(me.getValue()));
					members.add(mem);
				}
				cls.put("members", members);
			} else {
				cls.put("members", null);
			}
			classes.add(cls);
		}

		Map<String, Object> cov = new LinkedHashMap<>();
		cov.put("totalSourceClasses", totalSourceClasses);
		cov.put("classes", classes);
		return cov;
	}

	// ── Value types ──────────────────────────────────────────────────────────

	/**
	 * Holds a test class name together with its score result and timing metrics.
	 * Used as the element type of the {@code scored} list passed to
	 * {@link #buildData}.
	 */
	public record ScoredTest(String name, TestScorer.ScoreResult result, long duration, double durationVariance) {
	}
}
