package me.bechberger.testorder.ops.workflows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import me.bechberger.testorder.MethodOrderingEngine.ClassMethodOrder;
import me.bechberger.testorder.MethodOrderingEngine.OrderedMethod;
import me.bechberger.testorder.OrderReportPrinter;
import me.bechberger.testorder.TestScorer;
import me.bechberger.testorder.ml.TestHealthReport;
import me.bechberger.util.json.PrettyPrinter;

/**
 * Produces JSON output for the unified {@code show} command. Combines class
 * ordering, method ordering, and ML health data into a single JSON object.
 */
public final class ShowJsonFormatter {

	private ShowJsonFormatter() {
	}

	/**
	 * Formats the show result as a JSON string.
	 *
	 * @param result
	 *            combined show result
	 * @param filter
	 *            name filter (null = no filter)
	 * @return JSON string
	 */
	public static String format(ShowWorkflow.ShowResult result, String filterGlob) {
		Predicate<String> filter = ShowWorkflow.compileFilter(filterGlob);
		Map<String, Object> root = new LinkedHashMap<>();
		addMetadata(root, result, filterGlob);

		// ── Class order ──────────────────────────────────────────────
		if (result.classOrder() != null) {
			List<OrderReportPrinter.RankedTest> ranked = result.classOrder().ranked();
			if (filter != null) {
				ranked = ranked.stream().filter(r -> filter.test(r.name())).toList();
			}
			List<Object> classOrderJson = new ArrayList<>();
			for (int i = 0; i < ranked.size(); i++) {
				OrderReportPrinter.RankedTest r = ranked.get(i);
				TestScorer.ScoreResult s = r.score();
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("rank", i + 1);
				entry.put("name", r.name());
				entry.put("score", s.score());
				entry.put("depOverlap", s.depOverlap());
				entry.put("depTotal", s.depTotal());
				entry.put("failScore", s.failScore());
				entry.put("speedRatio", roundTo4(s.speedRatio()));
				entry.put("isNew", s.isNew());
				entry.put("isChanged", s.isChanged());
				entry.put("duration", r.durationMs());
				if (result.mlPredictions() != null) {
					Double pFail = result.mlPredictions().get(r.name());
					entry.put("mlPFail", pFail != null ? roundTo4(pFail) : null);
				}
				classOrderJson.add(entry);
			}
			root.put("classOrder", classOrderJson);

			// Changed classes
			root.put("changedClasses", new ArrayList<>(result.classOrder().changedClasses()));
			root.put("changedTestClasses", new ArrayList<>(result.classOrder().changedTests()));
		}

		// ── Method order ─────────────────────────────────────────────
		if (result.methodOrder() != null) {
			List<ClassMethodOrder> classOrders = result.methodOrder().classOrders();
			if (filter != null) {
				classOrders = classOrders.stream().filter(co -> filter.test(co.className())).toList();
			}
			List<Object> methodOrderJson = new ArrayList<>();
			for (ClassMethodOrder co : classOrders) {
				Map<String, Object> classEntry = new LinkedHashMap<>();
				classEntry.put("class", co.className());
				List<Object> methods = new ArrayList<>();
				for (int i = 0; i < co.methods().size(); i++) {
					OrderedMethod m = co.methods().get(i);
					Map<String, Object> mEntry = new LinkedHashMap<>();
					mEntry.put("rank", i + 1);
					mEntry.put("method", m.methodName());
					mEntry.put("score", roundTo4(m.score()));
					methods.add(mEntry);
				}
				classEntry.put("methods", methods);
				methodOrderJson.add(classEntry);
			}
			root.put("methodOrder", methodOrderJson);
		}

		// ── ML health ────────────────────────────────────────────────
		if (result.healthReport() != null) {
			Map<String, Object> ml = new LinkedHashMap<>();
			ml.put("enabled", true);
			ml.put("runsAnalyzed", result.healthReport().runsAnalyzed());

			// Summary counts
			Map<String, Object> summary = new LinkedHashMap<>();
			summary.put("healthy", result.healthReport().byStatus(TestHealthReport.HealthStatus.HEALTHY).size());
			summary.put("degrading", result.healthReport().byStatus(TestHealthReport.HealthStatus.DEGRADING).size());
			summary.put("flaky", result.healthReport().byStatus(TestHealthReport.HealthStatus.FLAKY).size());
			summary.put("failing", result.healthReport().byStatus(TestHealthReport.HealthStatus.FAILING).size());
			ml.put("summary", summary);

			// Per-test health
			List<Object> health = new ArrayList<>();
			for (var entry : result.healthReport().tests().values()) {
				if (filter != null && !filter.test(entry.testClass())) {
					continue;
				}
				Map<String, Object> h = new LinkedHashMap<>();
				h.put("testClass", entry.testClass());
				h.put("status", entry.status().name());
				h.put("flakinessScore", roundTo4(entry.flakinessScore()));
				h.put("degradationTrend", roundTo4(entry.degradationTrend()));
				h.put("recentFailureRate", roundTo4(entry.recentFailureRate()));
				h.put("volatility", roundTo4(entry.volatility()));
				h.put("totalRuns", entry.totalRuns());
				h.put("totalFailures", entry.totalFailures());
				health.add(h);
			}
			ml.put("health", health);

			// Predictions
			if (result.mlPredictions() != null && !result.mlPredictions().isEmpty()) {
				Map<String, Object> predictions = new LinkedHashMap<>();
				for (var e : result.mlPredictions().entrySet()) {
					if (filter == null || filter.test(e.getKey())) {
						predictions.put(e.getKey(), roundTo4(e.getValue()));
					}
				}
				ml.put("predictions", predictions);
			}

			root.put("ml", ml);
		}

		return PrettyPrinter.prettyPrint(root);
	}

	private static void addMetadata(Map<String, Object> root, ShowWorkflow.ShowResult result, String filterGlob) {
		Map<String, Object> meta = new LinkedHashMap<>();
		List<String> sectionsShown = new ArrayList<>();
		List<Map<String, Object>> sectionsSkipped = new ArrayList<>();
		List<String> guidanceHints = new ArrayList<>();

		if (result.classOrder() != null) {
			sectionsShown.add("classOrder");
		} else {
			sectionsSkipped.add(skipped("classOrder", "no class-order data"));
			guidanceHints.add("Run: mvn test -Dtestorder.mode=learn");
		}

		if (result.methodOrder() != null) {
			sectionsShown.add("methodOrder");
		} else {
			sectionsSkipped.add(skipped("methodOrder", "no method telemetry data"));
			guidanceHints.add("Run: mvn test -Dtestorder.method.ordering=true -Dtestorder.mode=learn");
		}

		if (result.healthReport() != null) {
			sectionsShown.add("ml");
		} else {
			sectionsSkipped.add(skipped("ml", "no ML history found"));
			guidanceHints.add("Run: mvn test -Dtestorder.ml.enabled=true -Dtestorder.mode=learn");
		}

		meta.put("filter", filterGlob == null || filterGlob.isBlank() ? null : filterGlob);
		meta.put("sectionsShown", sectionsShown);
		meta.put("sectionsSkipped", sectionsSkipped);
		meta.put("guidanceHints", guidanceHints);
		root.put("meta", meta);
	}

	private static Map<String, Object> skipped(String section, String reason) {
		Map<String, Object> skipped = new LinkedHashMap<>();
		skipped.put("section", section);
		skipped.put("reason", reason);
		return skipped;
	}

	private static double roundTo4(double v) {
		return Math.round(v * 10000.0) / 10000.0;
	}
}
