package me.bechberger.testorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
	/**
	 * Optional: directory containing uncertain-classes*.txt files from
	 * selective-learn runs.
	 */
	private final Path depsDir;

	public DashboardGenerator(String projectName, String stateFilePath, String indexFilePath, String pluginVersion) {
		this(projectName, stateFilePath, indexFilePath, pluginVersion, null);
	}

	public DashboardGenerator(String projectName, String stateFilePath, String indexFilePath, String pluginVersion,
			Path depsDir) {
		this.projectName = projectName;
		this.stateFilePath = stateFilePath;
		this.indexFilePath = indexFilePath;
		this.pluginVersion = pluginVersion;
		this.depsDir = depsDir;
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
		weightsMap.put("killRateBonus", sw.killRateBonus());
		weightsMap.put("packageProximityBonus", sw.packageProximityBonus());
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
		Map<String, Double> killRates = state.getKillRates();
		// derive class→module for cross-module dep analysis (empty for single-module
		// projects)
		Map<String, String> classToModule = deriveClassToModule(depMap);
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
			if (r.killRate() >= 0)
				t.put("killRate", r.killRate());
			if (r.weightedDepOverlap() != r.depOverlap())
				t.put("weightedDepOverlap", r.weightedDepOverlap());
			t.put("isNew", r.isNew());
			t.put("isChanged", r.isChanged());
			t.put("isFast", r.isFast());
			t.put("isSlow", r.isSlow());
			t.put("hasStaticFieldOverlap", r.hasStaticFieldOverlap());
			t.put("duration", st.duration());
			t.put("durationVariance", st.durationVariance());
			Set<String> deps = depMap.get(st.name());
			t.put("deps", deps != null ? new ArrayList<>(deps) : new ArrayList<>());
			// module info (null for single-module projects)
			String ownModule = depMap.getModule(st.name());
			t.put("module", ownModule);
			if (ownModule != null && !classToModule.isEmpty() && deps != null && !deps.isEmpty()) {
				int foreignCount = 0;
				Map<String, Integer> foreignModuleCounts = new HashMap<>();
				for (String dep : deps) {
					String depModule = classToModule.get(dep);
					if (depModule != null && !depModule.equals(ownModule)) {
						foreignCount++;
						foreignModuleCounts.merge(depModule, 1, Integer::sum);
					}
				}
				t.put("crossModuleDepCount", foreignCount);
				String dominant = foreignModuleCounts.entrySet().stream().max(Map.Entry.comparingByValue())
						.map(Map.Entry::getKey).orElse(null);
				t.put("dominantDepModule", dominant);
				// Suspect: >70% of deps are from a single foreign module AND ≥5 deps
				// (small dep sets are too noisy for cross-module analysis).
				boolean suspect = dominant != null && deps.size() >= 5 && foreignCount * 10 >= deps.size() * 7; // foreignCount/total
																												// > 70%
				t.put("suspectHomeModule", suspect);
			} else {
				t.put("crossModuleDepCount", 0);
				t.put("dominantDepModule", null);
				t.put("suspectHomeModule", false);
			}
			if (depMap.hasMemberDeps()) {
				Set<String> memberDeps = depMap.getMemberDeps(st.name());
				t.put("memberDeps", memberDeps.isEmpty() ? null : new ArrayList<>(memberDeps));
			} else {
				t.put("memberDeps", null);
			}
			// per-test-method dependencies (METHOD+ mode)
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
						if (depMap.hasMemberDeps()) {
							Set<String> mMemberDeps = depMap.getMethodMemberDeps(key);
							m.put("memberDeps", mMemberDeps.isEmpty() ? null : new ArrayList<>(mMemberDeps));
						} else {
							m.put("memberDeps", null);
						}
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
			// Mutation kill rate (if available)
			if (!killRates.isEmpty()) {
				Double kr = killRates.get(st.name());
				t.put("killRate", kr != null ? Math.round(kr * 10000.0) / 10000.0 : null);
			}
			tests.add(t);
		}
		// Compress memberDeps: replace repeated strings with integer indices into a
		// shared dictionary. This reduces the 355MB jackson-databind dashboard to
		// ~12MB.
		compressMemberDeps(tests, root);
		root.put("tests", tests);
		// class→module lookup for DepGraph cross-module edge coloring (omitted for
		// single-module projects to avoid bloat)
		if (!classToModule.isEmpty()) {
			root.put("classToModule", classToModule);
		}

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
				oc.put("score", o.totalScore());
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
		root.put("coverage", depMap != null ? buildCoverageData(depMap) : null);

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

		// Mutation testing section (from state kill rates)
		if (!killRates.isEmpty()) {
			Map<String, Object> mutation = new LinkedHashMap<>();
			mutation.put("enabled", true);

			// Overall mutation score from persisted totals
			int totalMutants = state.getMutationTotalMutants();
			int totalKilled = state.getMutationTotalKilled();
			mutation.put("totalMutants", totalMutants);
			mutation.put("totalKilled", totalKilled);
			mutation.put("overallKillRate",
					totalMutants > 0 ? Math.round((double) totalKilled / totalMutants * 10000.0) / 10000.0 : 0.0);

			// Summary: count tests by kill-rate tier
			int high = 0, medium = 0, low = 0, none = 0;
			for (double rate : killRates.values()) {
				if (rate >= 0.15)
					high++;
				else if (rate >= 0.05)
					medium++;
				else if (rate > 0)
					low++;
				else
					none++;
			}
			Map<String, Object> summary = new LinkedHashMap<>();
			summary.put("high", high);
			summary.put("medium", medium);
			summary.put("low", low);
			summary.put("none", none);
			mutation.put("summary", summary);

			// Per-test entries sorted descending by kill rate
			List<Map<String, Object>> mutTests = killRates.entrySet().stream()
					.sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).map(e -> {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("testClass", e.getKey());
						m.put("killRate", Math.round(e.getValue() * 10000.0) / 10000.0);
						return m;
					}).collect(java.util.stream.Collectors.toList());
			mutation.put("tests", mutTests);

			root.put("mutation", mutation);
		}

		// Static analysis: uncertain classes from selective-learn runs
		root.put("staticAnalysis", buildStaticAnalysisData(depMap));

		return root;
	}

	/**
	 * Builds the staticAnalysis section: reads all uncertain-classes*.txt files
	 * from the depsDir. Returns null when no files exist or depsDir is not set.
	 *
	 * @param depMap
	 *            used to cross-join {@code source class → tests} so each entry can
	 *            list which tests would be re-run if its enclosing class is
	 *            considered uncertain.
	 */
	private Map<String, Object> buildStaticAnalysisData(DependencyMap depMap) {
		if (depsDir == null || !Files.isDirectory(depsDir)) {
			return null;
		}
		// Build source-class → tests lookup once.
		Map<String, List<String>> sourceToTests = new LinkedHashMap<>();
		if (depMap != null && depMap.size() > 0) {
			Map<String, Set<String>> tmp = new LinkedHashMap<>();
			for (String testClass : depMap.testClasses()) {
				for (String dep : depMap.get(testClass)) {
					tmp.computeIfAbsent(dep, k -> new TreeSet<>()).add(testClass);
				}
			}
			for (var e : tmp.entrySet()) {
				sourceToTests.put(e.getKey(), new ArrayList<>(e.getValue()));
			}
		}

		// Prefix used when writing per-module uncertain-classes files
		final String MODULE_PREFIX = "uncertain-classes-";
		final String SUFFIX = ".txt";
		List<Map<String, Object>> modules = new ArrayList<>();
		try (var stream = Files.newDirectoryStream(depsDir, "uncertain-classes*.txt")) {
			for (Path f : stream) {
				String fname = f.getFileName().toString();
				// Derive module name from filename:
				// "uncertain-classes.txt" → "(default)"
				// "uncertain-classes-groupId_art.txt" → "groupId_art"
				// anything else (shouldn't exist but be safe) → use full filename
				String moduleName;
				if (fname.equals("uncertain-classes.txt")) {
					moduleName = "(default)";
				} else if (fname.startsWith(MODULE_PREFIX) && fname.endsWith(SUFFIX)
						&& fname.length() > MODULE_PREFIX.length() + SUFFIX.length()) {
					moduleName = fname.substring(MODULE_PREFIX.length(), fname.length() - SUFFIX.length());
				} else {
					// Unexpected name pattern — use as-is so it at least shows up
					moduleName = fname;
				}
				List<String> classNames = new ArrayList<>();
				try {
					for (String line : Files.readAllLines(f)) {
						String t = line.trim();
						if (!t.isEmpty()) {
							classNames.add(t);
						}
					}
				} catch (IOException e) {
					System.err.println("[test-order] Could not read " + f.getFileName() + ": " + e.getMessage());
				}
				classNames.sort(String::compareTo);

				// Try sidecar for depth/parent and richer change data
				Map<String, Object> saJson = null;
				try {
					saJson = me.bechberger.testorder.changes.StaticAnalysisDataStore
							.load(me.bechberger.testorder.changes.StaticAnalysisDataStore.sidecarPath(f));
				} catch (IOException ignored) {
				}

				@SuppressWarnings("unchecked")
				Map<String, Object> depths = saJson != null ? (Map<String, Object>) saJson.get("classDepths") : null;
				@SuppressWarnings("unchecked")
				Map<String, Object> parents = saJson != null ? (Map<String, Object>) saJson.get("classParents") : null;
				@SuppressWarnings("unchecked")
				Map<String, Object> membersByClass = saJson != null
						? (Map<String, Object>) saJson.get("membersByClass")
						: null;
				@SuppressWarnings("unchecked")
				Map<String, Object> kinds = saJson != null
						? (Map<String, Object>) saJson.get("memberChangeKinds")
						: null;
				@SuppressWarnings("unchecked")
				List<Object> typeChangedList = saJson != null
						? (List<Object>) saJson.get("classesWithTypeChanges")
						: null;
				Set<String> typeChangedSet = typeChangedList != null
						? typeChangedList.stream().map(Object::toString)
								.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
						: Set.of();
				@SuppressWarnings("unchecked")
				List<Object> staticFieldKeys = saJson != null
						? (List<Object>) saJson.get("changedStaticFieldKeys")
						: null;
				Set<String> staticFieldSet = staticFieldKeys != null
						? staticFieldKeys.stream().map(Object::toString)
								.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
						: Set.of();

				List<Object> classEntries = new ArrayList<>();
				for (String cls : classNames) {
					Map<String, Object> ce = new LinkedHashMap<>();
					ce.put("name", cls);
					Object dv = depths != null ? depths.get(cls) : null;
					ce.put("depth", dv != null ? ((Number) dv).intValue() : null);
					ce.put("parent", parents != null ? parents.get(cls) : null);
					ce.put("hasTypeChange", typeChangedSet.contains(cls));

					// per-class member changes (only seeds carry these)
					List<Object> memberEntries = new ArrayList<>();
					Object membersForCls = membersByClass != null ? membersByClass.get(cls) : null;
					if (membersForCls instanceof List<?> memberList) {
						for (Object mn : memberList) {
							String memberName = String.valueOf(mn);
							Map<String, Object> me = new LinkedHashMap<>();
							me.put("name", memberName);
							String key = cls + "#" + memberName;
							Object kindVal = kinds != null ? kinds.get(key) : null;
							me.put("kind", kindVal != null ? kindVal.toString() : "BODY");
							me.put("isStaticField", staticFieldSet.contains(key));
							memberEntries.add(me);
						}
					}
					ce.put("members", memberEntries);

					// tests covering this class (cross-join with depMap)
					List<String> tests = sourceToTests.getOrDefault(cls, List.of());
					ce.put("tests", new ArrayList<>(tests));

					classEntries.add(ce);
				}

				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("module", moduleName);
				entry.put("count", classNames.size());
				entry.put("classes", classEntries);
				if (saJson != null) {
					entry.put("degraded", saJson.get("degraded"));
					entry.put("seedSize",
							saJson.get("seedSize") != null ? ((Number) saJson.get("seedSize")).intValue() : null);
					entry.put("expandedSize",
							saJson.get("expandedSize") != null
									? ((Number) saJson.get("expandedSize")).intValue()
									: null);

					// Per-file summaries (passthrough)
					@SuppressWarnings("unchecked")
					List<Object> fileSummaries = (List<Object>) saJson.get("fileSummaries");
					if (fileSummaries != null) {
						List<Object> normalized = new ArrayList<>();
						int filesChanged = 0, addedTotal = 0, removedTotal = 0, sigTotal = 0, bodyTotal = 0,
								linesTotal = 0;
						for (Object item : fileSummaries) {
							if (!(item instanceof Map<?, ?> raw))
								continue;
							Map<String, Object> fs = new LinkedHashMap<>();
							fs.put("path", raw.get("path") instanceof String s ? s : null);
							int a = raw.get("added") instanceof Number n ? n.intValue() : 0;
							int r = raw.get("removed") instanceof Number n ? n.intValue() : 0;
							int s = raw.get("signature") instanceof Number n ? n.intValue() : 0;
							int b = raw.get("body") instanceof Number n ? n.intValue() : 0;
							int tl = raw.get("totalLines") instanceof Number n ? n.intValue() : 0;
							fs.put("added", a);
							fs.put("removed", r);
							fs.put("signature", s);
							fs.put("body", b);
							fs.put("totalLines", tl);
							normalized.add(fs);
							filesChanged++;
							addedTotal += a;
							removedTotal += r;
							sigTotal += s;
							bodyTotal += b;
							linesTotal += tl;
						}
						entry.put("fileSummaries", normalized);

						// Module-level rollup summary
						int classesChanged = 0;
						int membersChanged = 0;
						if (membersByClass != null) {
							classesChanged = membersByClass.size();
							for (Object v : membersByClass.values()) {
								if (v instanceof List<?> l)
									membersChanged += l.size();
							}
						}
						Map<String, Object> summary = new LinkedHashMap<>();
						summary.put("filesChanged", filesChanged);
						summary.put("classesChanged", classesChanged);
						summary.put("membersChanged", membersChanged);
						summary.put("added", addedTotal);
						summary.put("removed", removedTotal);
						summary.put("signature", sigTotal);
						summary.put("body", bodyTotal);
						summary.put("staticFieldChanges", staticFieldSet.size());
						summary.put("totalChangedLines", linesTotal);
						entry.put("summary", summary);
					}
				}
				modules.add(entry);
			}
		} catch (IOException ignored) {
		}
		if (modules.isEmpty()) {
			return null;
		}
		modules.sort(Comparator.comparing(m -> (String) m.get("module")));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("enabled", true);
		result.put("modules", modules);
		int total = modules.stream().mapToInt(m -> ((Number) m.get("count")).intValue()).sum();
		result.put("totalUncertainClasses", total);
		return result;
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
		// Use overflow-safe formula: a + (b - a) / 2 instead of (a + b) / 2.
		return durations.length % 2 == 0
				? durations[mid - 1] + (durations[mid] - durations[mid - 1]) / 2
				: durations[mid];
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
		Map<String, Integer> totalMembersPerClass = depMap.hasMemberDeps() ? depMap.trackedMembersPerClass() : Map.of();
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
			// total vs. covered member counts (enables method-coverage % in dashboard)
			int coveredCount = memberMap != null ? memberMap.size() : 0;
			int totalCount = totalMembersPerClass.getOrDefault(fqcn, 0);
			cls.put("coveredMembers", coveredCount);
			cls.put("totalMembers", totalCount);
			classes.add(cls);
		}

		Map<String, Object> cov = new LinkedHashMap<>();
		cov.put("totalSourceClasses", totalSourceClasses);
		cov.put("classes", classes);
		return cov;
	}

	/**
	 * Derives a {@code sourceClass → moduleId} map from the test-to-module mapping
	 * already in the dependency map.
	 *
	 * <p>
	 * For every test in {@code testToModule}, we walk its dependency set and tally
	 * how many tests from each module reference each source class. The module whose
	 * tests most often reference a class wins. This is purely inferential — classes
	 * that are evenly cross-cutting get assigned to whichever module won the tally.
	 * The result is used only for display/analysis in the dashboard.
	 */
	static Map<String, String> deriveClassToModule(DependencyMap depMap) {
		if (depMap.modules().size() <= 1) {
			return Map.of();
		}
		// tally: class → (module → count)
		Map<String, Map<String, Integer>> tally = new HashMap<>();
		for (String testClass : depMap.testClasses()) {
			String module = depMap.getModule(testClass);
			if (module == null)
				continue;
			Set<String> deps = depMap.get(testClass);
			if (deps == null)
				continue;
			for (String dep : deps) {
				tally.computeIfAbsent(dep, k -> new HashMap<>()).merge(module, 1, Integer::sum);
			}
		}
		Map<String, String> result = new HashMap<>(tally.size());
		for (Map.Entry<String, Map<String, Integer>> e : tally.entrySet()) {
			String winner = e.getValue().entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
					.orElse(null);
			if (winner != null)
				result.put(e.getKey(), winner);
		}
		return result;
	}

	// ── Value types ──────────────────────────────────────────────────────────

	/**
	 * Compress memberDeps and deps string arrays into integer index arrays using a
	 * shared dictionary. Mutates test entries in-place and adds "memberDict" and
	 * "depDict" to root. Reduces dashboard size from ~355MB to ~12MB for large
	 * projects (jackson-databind: 735 tests × 8 methods × 567 memberDeps each).
	 */
	@SuppressWarnings("unchecked")
	private static void compressMemberDeps(List<Object> tests, Map<String, Object> root) {
		// Check if any test has memberDeps
		boolean hasMemberDeps = tests.stream().anyMatch(t -> {
			Map<String, Object> tm = (Map<String, Object>) t;
			return tm.get("memberDeps") != null || (tm.get("methods") != null && ((List<Object>) tm.get("methods"))
					.stream().anyMatch(m -> ((Map<String, Object>) m).get("memberDeps") != null));
		});

		// Build separate dictionaries for member refs and class names
		Map<String, Integer> memberDict = new LinkedHashMap<>();
		Map<String, Integer> depDict = new LinkedHashMap<>();

		for (Object test : tests) {
			Map<String, Object> t = (Map<String, Object>) test;
			if (hasMemberDeps)
				collectMemberRefs(t.get("memberDeps"), memberDict);
			collectMemberRefs(t.get("deps"), depDict);
			List<Object> methods = (List<Object>) t.get("methods");
			if (methods != null) {
				for (Object method : methods) {
					Map<String, Object> m = (Map<String, Object>) method;
					if (hasMemberDeps)
						collectMemberRefs(m.get("memberDeps"), memberDict);
					collectMemberRefs(m.get("deps"), depDict);
				}
			}
		}

		// Replace string arrays with integer index arrays
		for (Object test : tests) {
			Map<String, Object> t = (Map<String, Object>) test;
			if (hasMemberDeps)
				t.put("memberDeps", toIndexArray(t.get("memberDeps"), memberDict));
			t.put("deps", toIndexArray(t.get("deps"), depDict));
			List<Object> methods = (List<Object>) t.get("methods");
			if (methods != null) {
				for (Object method : methods) {
					Map<String, Object> m = (Map<String, Object>) method;
					if (hasMemberDeps)
						m.put("memberDeps", toIndexArray(m.get("memberDeps"), memberDict));
					m.put("deps", toIndexArray(m.get("deps"), depDict));
				}
			}
		}

		if (hasMemberDeps)
			root.put("memberDict", new ArrayList<>(memberDict.keySet()));
		if (!depDict.isEmpty())
			root.put("depDict", new ArrayList<>(depDict.keySet()));
	}

	@SuppressWarnings("unchecked")
	private static void collectMemberRefs(Object memberDeps, Map<String, Integer> dict) {
		if (!(memberDeps instanceof List))
			return;
		for (Object ref : (List<Object>) memberDeps) {
			if (ref instanceof String s) {
				dict.computeIfAbsent(s, k -> dict.size());
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Integer> toIndexArray(Object memberDeps, Map<String, Integer> dict) {
		if (!(memberDeps instanceof List))
			return null;
		List<Integer> indices = new ArrayList<>();
		for (Object ref : (List<Object>) memberDeps) {
			if (ref instanceof String s) {
				Integer idx = dict.get(s);
				if (idx != null)
					indices.add(idx);
			}
		}
		return indices.isEmpty() ? null : indices;
	}

	/**
	 * Holds a test class name together with its score result and timing metrics.
	 * Used as the element type of the {@code scored} list passed to
	 * {@link #buildData}.
	 */
	public record ScoredTest(String name, TestScorer.ScoreResult result, long duration, double durationVariance) {
	}
}
