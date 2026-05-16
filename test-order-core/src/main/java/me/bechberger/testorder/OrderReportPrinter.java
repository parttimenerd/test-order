package me.bechberger.testorder;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * Shared formatting and ranking helpers for show-order style reports.
 */
public final class OrderReportPrinter {

	private OrderReportPrinter() {
	}

	/**
	 * A single ranked test row.
	 */
	public record RankedTest(String name, TestScorer.ScoreResult score, long durationMs) {
	}

	/**
	 * Ranks tests with the same tie-breaking rules used by PriorityClassOrderer.
	 */
	public static List<RankedTest> rankTests(Collection<String> testClasses, TestScorer scorer, TestOrderState state) {
		List<RankedTest> ranked = new ArrayList<>();
		for (String testClass : testClasses) {
			TestScorer.ScoreResult result = scorer.score(testClass);
			long dur = state.getDuration(testClass, -1);
			ranked.add(new RankedTest(testClass, result, dur));
		}
		ranked.sort(Comparator.<RankedTest, Integer>comparing(r -> r.score().score()).reversed()
				.thenComparingLong(r -> r.durationMs() >= 0 ? r.durationMs() : Long.MAX_VALUE)
				.thenComparing(RankedTest::name));
		return ranked;
	}

	/**
	 * Prints a compact table view of the ranked tests.
	 */
	public static void printShowOrderTable(PrintStream out, List<RankedTest> ranked, Set<String> changed,
			Set<String> changedTests, boolean includeTags, boolean showDepTotals, boolean fullNames) {
		out.println();
		if (!changed.isEmpty()) {
			out.println("Changed classes: " + String.join(", ", new TreeSet<>(changed)));
		}
		if (!changedTests.isEmpty()) {
			out.println("Changed test classes: " + String.join(", ", new TreeSet<>(changedTests)));
		}
		out.println();

		int maxName = "Test Class".length();
		for (RankedTest entry : ranked) {
			String displayName = fullNames ? entry.name() : shortenClassName(entry.name());
			if (displayName.length() > maxName) {
				maxName = displayName.length();
			}
		}

		String fmt;
		if (includeTags) {
			fmt = "  %-4s %-" + maxName + "s %6s %5s %5s %8s %8s %s%n";
			out.printf(fmt, "#", "Test Class", "Score", "Deps", "Fail", "Changed", "Duration", "");
			out.printf(fmt, "-", "-".repeat(maxName), "-".repeat(6), "-".repeat(5), "-".repeat(5), "-".repeat(8),
					"-".repeat(8), "");
		} else {
			fmt = "  %-4s %-" + maxName + "s %6s %5s %5s %8s %8s%n";
			out.printf(fmt, "#", "Test Class", "Score", "Deps", "Fail", "Changed", "Duration");
			out.printf(fmt, "-", "-".repeat(maxName), "-".repeat(6), "-".repeat(5), "-".repeat(5), "-".repeat(8),
					"-".repeat(8));
		}

		for (int i = 0; i < ranked.size(); i++) {
			RankedTest entry = ranked.get(i);
			TestScorer.ScoreResult score = entry.score();
			String displayName = fullNames ? entry.name() : shortenClassName(entry.name());
			String deps = formatDeps(score.depOverlap(), score.depTotal(), showDepTotals);
			if (includeTags) {
				var tags = new StringJoiner(" ");
				if (score.isNew()) {
					tags.add("[NEW]");
				}
				if (score.isFast()) {
					tags.add("[FAST]");
				}
				if (score.isSlow()) {
					tags.add("[SLOW]");
				}
				out.printf(fmt, (i + 1) + ".", displayName, score.score(), deps,
						score.failScore() > 0 ? String.format(java.util.Locale.US, "%.1f", score.failScore()) : "",
						score.isChanged() ? "yes" : "", entry.durationMs() >= 0 ? entry.durationMs() + "ms" : "",
						tags.toString());
			} else {
				out.printf(fmt, (i + 1) + ".", displayName, score.score(), deps,
						score.failScore() > 0 ? String.format(java.util.Locale.US, "%.1f", score.failScore()) : "",
						score.isChanged() ? "yes" : "", entry.durationMs() >= 0 ? entry.durationMs() + "ms" : "");
			}
		}

		// R12-4: Print summary line for quick overview on large projects
		if (ranked.size() > 5) {
			int minScore = ranked.stream().mapToInt(r -> r.score().score()).min().orElse(0);
			int maxScore = ranked.stream().mapToInt(r -> r.score().score()).max().orElse(0);
			long newCount = ranked.stream().filter(r -> r.score().isNew()).count();
			long slowCount = ranked.stream().filter(r -> r.score().isSlow()).count();
			long changedCount = ranked.stream().filter(r -> r.score().isChanged()).count();
			StringBuilder summary = new StringBuilder();
			summary.append("Total: ").append(ranked.size()).append(" tests | Score range: ").append(minScore)
					.append("–").append(maxScore);
			if (newCount > 0)
				summary.append(" | ").append(newCount).append(" NEW");
			if (slowCount > 0)
				summary.append(" | ").append(slowCount).append(" SLOW");
			if (changedCount > 0)
				summary.append(" | ").append(changedCount).append(" CHANGED");
			out.println(summary);
		}
		out.println();
		out.println("Tip: use -Dtestorder.showOrder.explain=true to see why each test got its score.");
		out.println();
	}

	/**
	 * Prints the verbose explain report in rank order.
	 */
	public static void printExplainReport(PrintStream out, List<RankedTest> ranked, TestScorer scorer,
			Set<String> changed, Set<String> changedTests, TestOrderState.ScoringWeights weights) {
		out.println();
		if (!changed.isEmpty()) {
			out.println("Changed classes: " + String.join(", ", new TreeSet<>(changed)));
		}
		if (!changedTests.isEmpty()) {
			out.println("Changed test classes: " + String.join(", ", new TreeSet<>(changedTests)));
		}
		out.printf("Median duration: %dms%n", scorer.medianDuration());
		out.printf(
				"Weights: newTest=%d changedTest=%d maxFailure=%d speed=%d speedPenalty=%d depOverlap=%d changeComplexity=%d staticFieldBonus=%d coverageBonus=%d%n",
				weights.newTest(), weights.changedTest(), weights.maxFailure(), weights.speed(), weights.speedPenalty(),
				weights.depOverlap(), weights.changeComplexity(), weights.staticFieldBonus(), weights.coverageBonus());
		out.println();

		for (int i = 0; i < ranked.size(); i++) {
			ExplainEntry entry = scorer.explain(ranked.get(i).name(), i + 1);
			out.print(entry.format());
			out.println();
		}
	}

	private static String formatDeps(int overlap, int total, boolean showDepTotals) {
		if (showDepTotals) {
			if (total <= 0) {
				return "";
			}
			return overlap > 0 ? overlap + "/" + total : String.valueOf(total);
		}
		return overlap > 0 ? String.valueOf(overlap) : "";
	}

	/**
	 * Abbreviates all package segments except the last one.
	 */
	public static String shortenClassName(String fqcn) {
		int lastDot = fqcn.lastIndexOf('.');
		if (lastDot < 0) {
			return fqcn;
		}
		String pkg = fqcn.substring(0, lastDot);
		String cls = fqcn.substring(lastDot + 1);
		String[] parts = pkg.split("\\.");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.length - 1; i++) {
			sb.append(parts[i].charAt(0)).append('.');
		}
		sb.append(parts[parts.length - 1]).append('.').append(cls);
		return sb.toString();
	}
}
