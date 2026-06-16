package me.bechberger.testorder.ops.coverage;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.ops.PluginLog;
import me.bechberger.util.json.PrettyPrinter;

/**
 * Shared coverage-analysis logic that works from a {@link DependencyMap}. Both
 * Maven and Gradle plugins delegate here.
 *
 * <p>
 * The analysis inverts the dependency map (test→production) to compute
 * per-production-class metrics: how many tests exercise each class, and
 * optionally which members are exercised.
 */
public final class CoverageOperation {

	private CoverageOperation() { // utility
	}

	/**
	 * Returns true if {@code className} is a test class or an inner class of a test
	 * class.
	 */
	private static boolean isTestClass(String className, Set<String> testClassNames) {
		if (testClassNames.contains(className))
			return true;
		int dollar = className.indexOf('$');
		return dollar > 0 && testClassNames.contains(className.substring(0, dollar));
	}

	// ── Analysis ──────────────────────────────────────────────────────

	/**
	 * Build a {@link CoverageAnalysis} from a dependency map.
	 *
	 * @param depMap
	 *            the learned dependency map
	 * @param log
	 *            logger for progress info
	 * @return analysis result, sorted least-tested-first
	 */
	public static CoverageAnalysis analyze(DependencyMap depMap, PluginLog log) {
		log.info("Analyzing coverage from dependency map (" + depMap.size() + " test classes)");
		boolean hasMemberData = depMap.hasClassMemberDeps();

		// 1. Build inverse map: production class → set of exercising test classes
		// Exclude test classes (and their inner classes) — they are not production
		// code.
		Set<String> testClassNames = new TreeSet<>(depMap.testClasses());
		Map<String, Set<String>> prodToTests = new TreeMap<>();
		for (String testClass : depMap.testClasses()) {
			for (String prodClass : depMap.get(testClass)) {
				if (!isTestClass(prodClass, testClassNames)) {
					prodToTests.computeIfAbsent(prodClass, k -> new TreeSet<>()).add(testClass);
				}
			}
		}

		// 2. If member data available, build inverse member map:
		// production class → member → set of exercising test classes
		Map<String, Map<String, Set<String>>> prodMemberToTests = new TreeMap<>();
		Set<String> allKnownMembers = new TreeSet<>(); // "class#member" entries
		if (hasMemberData) {
			for (String testClass : depMap.testClasses()) {
				for (String entry : depMap.getMemberDeps(testClass)) {
					int hash = entry.indexOf('#');
					if (hash < 0)
						continue;
					String prodClass = entry.substring(0, hash);
					if (isTestClass(prodClass, testClassNames))
						continue;
					String member = entry.substring(hash + 1);
					allKnownMembers.add(entry);
					prodMemberToTests.computeIfAbsent(prodClass, k -> new TreeMap<>())
							.computeIfAbsent(member, k -> new TreeSet<>()).add(testClass);
				}
			}
		}

		// 3. Build ClassCoverage entries
		List<ClassCoverage> entries = new ArrayList<>();
		for (var e : prodToTests.entrySet()) {
			String prodClass = e.getKey();
			List<String> tests = new ArrayList<>(e.getValue());

			if (hasMemberData && prodMemberToTests.containsKey(prodClass)) {
				Map<String, Set<String>> memberMap = prodMemberToTests.get(prodClass);
				Set<String> exercised = memberMap.keySet();
				Map<String, List<String>> memberTestMap = new TreeMap<>();
				for (var me : memberMap.entrySet()) {
					memberTestMap.put(me.getKey(), new ArrayList<>(me.getValue()));
				}
				// allMembers is unknown from instrumentation data alone (we only know
				// which members were exercised, not the total member count). Pass Set.of()
				// so memberCoveragePercent() returns -1 rather than a misleading 100%.
				entries.add(new ClassCoverage(prodClass, tests, Set.of(), exercised, memberTestMap));
			} else {
				entries.add(new ClassCoverage(prodClass, tests));
			}
		}

		Collections.sort(entries);
		log.info("Coverage analysis: " + entries.size() + " production classes, " + depMap.size() + " test classes"
				+ (hasMemberData ? ", member-level data available" : ""));
		return new CoverageAnalysis(entries, depMap.size());
	}

	// ── Markdown report ───────────────────────────────────────────────

	/**
	 * Generate a markdown coverage report.
	 *
	 * @param analysis
	 *            the analysis result
	 * @param threshold
	 *            minimum test count to be considered "well-tested"
	 */
	public static String generateReport(CoverageAnalysis analysis, int threshold) {
		StringBuilder sb = new StringBuilder();
		CoverageAnalysis.Stats stats = analysis.stats();
		sb.append("# Dependency-Based Coverage Report\n\n");

		// Summary
		sb.append("## Summary\n\n");
		sb.append("| Metric | Value |\n");
		sb.append("|--------|-------|\n");
		sb.append("| Production classes | ").append(stats.totalClasses()).append(" |\n");
		sb.append("| Test classes | ").append(stats.totalTestClasses()).append(" |\n");
		sb.append("| Untested classes | ").append(stats.untestedClasses()).append(" |\n");
		sb.append("| Avg tests/class | ").append(String.format(java.util.Locale.US, "%.1f", stats.avgTestsPerClass()))
				.append(" |\n");
		sb.append("| Max tests/class | ").append(stats.maxTestsPerClass()).append(" |\n");
		sb.append("| Below threshold (<").append(threshold).append(" tests) | ")
				.append(analysis.belowThreshold(threshold).size()).append(" |\n");
		if (stats.hasMemberData()) {
			sb.append("| Total members exercised | ").append(stats.exercisedMembers()).append(" |\n");
			int pct = stats.memberCoveragePercent();
			if (pct >= 0) {
				sb.append("| Total members tracked | ").append(stats.totalMembers()).append(" |\n");
				sb.append("| Member coverage | ").append(pct).append("% |\n");
			}
		}
		sb.append('\n');

		// Least-tested classes
		List<ClassCoverage> belowThreshold = analysis.belowThreshold(threshold);
		if (!belowThreshold.isEmpty()) {
			sb.append("## Least-Tested Classes (below ").append(threshold).append(" tests)\n\n");
			if (stats.hasMemberData()) {
				sb.append("| Class | Tests | Member Coverage | Uncovered Members |\n");
				sb.append("|-------|------:|----------------:|-------------------|\n");
				for (ClassCoverage c : belowThreshold) {
					int pct = c.memberCoveragePercent();
					String memberCov = c.hasMemberCoverage()
							? (pct >= 0
									? pct + "% (" + c.exercisedMembers().size() + "/" + c.allMembers().size() + ")"
									: c.exercisedMembers().size() + " exercised")
							: "n/a";
					String uncovered = (c.hasMemberCoverage() && pct >= 0)
							? String.join(", ", c.uncoveredMembers())
							: "";
					sb.append("| `").append(c.fullyQualifiedName()).append("` | ").append(c.testCount()).append(" | ")
							.append(memberCov).append(" | ").append(uncovered).append(" |\n");
				}
			} else {
				sb.append("| Class | Tests |\n");
				sb.append("|-------|------:|\n");
				for (ClassCoverage c : belowThreshold) {
					sb.append("| `").append(c.fullyQualifiedName()).append("` | ").append(c.testCount()).append(" |\n");
				}
			}
			sb.append('\n');
		}

		// By-package breakdown
		sb.append("## Coverage by Package\n\n");
		if (stats.hasMemberData()) {
			sb.append("| Package | Classes | Avg Tests | Members Exercised |\n");
			sb.append("|---------|--------:|----------:|------------------:|\n");
		} else {
			sb.append("| Package | Classes | Avg Tests |\n");
			sb.append("|---------|--------:|----------:|\n");
		}
		for (var pe : analysis.byPackage().entrySet()) {
			List<ClassCoverage> pkgClasses = pe.getValue();
			double avg = pkgClasses.stream().mapToInt(ClassCoverage::testCount).average().orElse(0);
			if (stats.hasMemberData()) {
				int exercisedM = pkgClasses.stream().mapToInt(c -> c.exercisedMembers().size()).sum();
				sb.append("| `").append(pe.getKey()).append("` | ").append(pkgClasses.size()).append(" | ")
						.append(String.format(java.util.Locale.US, "%.1f", avg)).append(" | ").append(exercisedM)
						.append(" |\n");
			} else {
				sb.append("| `").append(pe.getKey()).append("` | ").append(pkgClasses.size()).append(" | ")
						.append(String.format(java.util.Locale.US, "%.1f", avg)).append(" |\n");
			}
		}
		sb.append('\n');

		return sb.toString();
	}

	// ── JSON report ───────────────────────────────────────────────────

	/**
	 * Generate a JSON coverage report using femtojson.
	 */
	public static String generateJson(CoverageAnalysis analysis, int threshold) {
		CoverageAnalysis.Stats stats = analysis.stats();
		Map<String, Object> root = new LinkedHashMap<>();

		// summary
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("totalProductionClasses", stats.totalClasses());
		summary.put("totalTestClasses", stats.totalTestClasses());
		summary.put("untestedClasses", stats.untestedClasses());
		summary.put("avgTestsPerClass",
				Double.parseDouble(String.format(java.util.Locale.US, "%.2f", stats.avgTestsPerClass())));
		summary.put("maxTestsPerClass", stats.maxTestsPerClass());
		summary.put("threshold", threshold);
		summary.put("belowThreshold", analysis.belowThreshold(threshold).size());
		if (stats.hasMemberData()) {
			summary.put("exercisedMembers", stats.exercisedMembers());
			int pct = stats.memberCoveragePercent();
			if (pct >= 0) {
				summary.put("totalMembers", stats.totalMembers());
				summary.put("memberCoveragePercent", pct);
			}
		}
		root.put("summary", summary);

		// classes
		List<Object> classes = new ArrayList<>();
		for (ClassCoverage c : analysis.entries()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("class", c.fullyQualifiedName());
			entry.put("package", c.packageName());
			entry.put("testCount", c.testCount());
			entry.put("tests", new ArrayList<>(c.exercisingTests()));
			if (c.hasMemberCoverage()) {
				entry.put("exercisedMembers", c.exercisedMembers().size());
				int pct = c.memberCoveragePercent();
				if (pct >= 0) {
					entry.put("memberCoveragePercent", pct);
					entry.put("totalMembers", c.allMembers().size());
					entry.put("uncoveredMembers", new ArrayList<>(c.uncoveredMembers()));
				}
			}
			classes.add(entry);
		}
		root.put("classes", classes);

		return PrettyPrinter.prettyPrint(root);
	}

	// ── File writing ──────────────────────────────────────────────────

	/**
	 * Write markdown and JSON reports to the output directory.
	 */
	public static void writeReports(CoverageAnalysis analysis, Path outputDir, int threshold, PluginLog log)
			throws IOException {
		Files.createDirectories(outputDir);
		Path mdFile = outputDir.resolve("COVERAGE_REPORT.md");
		Path mdTemp = me.bechberger.testorder.PersistenceSupport.temporarySibling(mdFile);
		Files.writeString(mdTemp, generateReport(analysis, threshold));
		me.bechberger.testorder.PersistenceSupport.moveIntoPlace(mdTemp, mdFile);
		log.info("Written: " + mdFile);

		Path jsonFile = outputDir.resolve("coverage-metrics.json");
		Path jsonTemp = me.bechberger.testorder.PersistenceSupport.temporarySibling(jsonFile);
		Files.writeString(jsonTemp, generateJson(analysis, threshold));
		me.bechberger.testorder.PersistenceSupport.moveIntoPlace(jsonTemp, jsonFile);
		log.info("Written: " + jsonFile);
	}

	/**
	 * Print a compact summary to the given stream.
	 */
	public static void printSummary(CoverageAnalysis analysis, int threshold, PrintStream out) {
		CoverageAnalysis.Stats stats = analysis.stats();
		out.println();
		out.println("=== Dependency-Based Coverage Summary ===");
		out.println("Production classes: " + stats.totalClasses());
		out.println("Test classes:       " + stats.totalTestClasses());
		out.println("Untested classes:   " + stats.untestedClasses());
		out.println("Avg tests/class:    " + String.format(java.util.Locale.US, "%.1f", stats.avgTestsPerClass()));
		out.println("Below threshold (<" + threshold + " tests): " + analysis.belowThreshold(threshold).size());
		if (stats.hasMemberData()) {
			int pct = stats.memberCoveragePercent();
			if (pct >= 0) {
				out.println("Member coverage:    " + stats.exercisedMembers() + "/" + stats.totalMembers() + " (" + pct
						+ "%)");
			} else {
				out.println("Member coverage:    " + stats.exercisedMembers() + " exercised (total unknown)");
			}
		}
		out.println();
	}

	// ── Helpers ───────────────────────────────────────────────────────
}
