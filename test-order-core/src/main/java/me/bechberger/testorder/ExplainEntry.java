package me.bechberger.testorder;

import java.util.*;
import java.util.Locale;

/**
 * Detailed score explanation for a single test class.
 * <p>
 * Contains the full breakdown of every scoring component, the list of
 * dependencies (with overlap markers), timing information relative to median,
 * and the final total score. Designed for human-readable output by show-order
 * explain mode.
 */
public record ExplainEntry(
		/** Fully-qualified test class name. */
		String testClass,
		/** Rank (1-based position after sorting). */
		int rank,

		// ── Total ──────────────────────────────────────────────────────
		/** Final composite score (sum of all components). */
		int totalScore,

		// ── Changed test ───────────────────────────────────────────────
		/** Whether the test source itself was modified. */
		boolean isChanged,
		/** Points awarded for changed test source. */
		int changedTestPoints,

		// ── Dependency overlap ─────────────────────────────────────────
		/** All production-class dependencies of this test. */
		Set<String> dependencies,
		/** Subset of dependencies that overlap with changed classes. */
		Set<String> overlappingDeps,
		/** Points awarded from dependency overlap. */
		int depOverlapPoints,

		// ── Change complexity ──────────────────────────────────────────
		/** Sum of normalised complexity values for overlapping deps. */
		double complexityOverlap,
		/** Points awarded from complexity-weighted overlap. */
		int complexityPoints,

		// ── Static field overlap ───────────────────────────────────────
		/** Whether a changed static field overlaps with test member deps. */
		boolean hasStaticFieldOverlap,
		/** Points awarded from static field overlap. */
		int staticFieldPoints,

		// ── Failure history ────────────────────────────────────────────
		/** Raw failure recency score (0.0–1.0). */
		double failScore,
		/** Points awarded from failure history. */
		int failurePoints,

		// ── New test ───────────────────────────────────────────────────
		/** Whether this test is new (not yet in the dependency index). */
		boolean isNew,
		/** Points awarded for being a new test. */
		int newTestPoints,

		// ── Speed ──────────────────────────────────────────────────────
		/** Test duration in ms (-1 if unknown). */
		long durationMs,
		/** Median duration across all tests in ms. */
		long medianDurationMs,
		/** Speed ratio in [-1, 1]: negative = faster, positive = slower. */
		double speedRatio,
		/** Points awarded (positive) or deducted (negative) from speed. */
		int speedPoints,

		// ── Set-cover ──────────────────────────────────────────────────
		/** Points from greedy set-cover bonus (0 when disabled). */
		int setCoverPoints,

		// ── Weights used ───────────────────────────────────────────────
		/** Scoring weights active for this run. */
		TestOrderState.ScoringWeights weights) {

	/**
	 * Formats a human-readable multi-line explanation block.
	 */
	public String format() {
		StringBuilder sb = new StringBuilder();
		sb.append(String.format("  %d. %s  (total score: %d)%n", rank, testClass, totalScore));
		sb.append(String.format("      %-24s %+d%s%n", "Changed test source:", changedTestPoints,
				isChanged ? "  (yes)" : ""));
		sb.append(String.format("      %-24s %+d  (%d/%d deps overlap)%n", "Dependency overlap:", depOverlapPoints,
				overlappingDeps.size(), dependencies.size()));
		if (!overlappingDeps.isEmpty()) {
			sb.append(String.format("      Changed dependencies (%d):%n", overlappingDeps.size()));
			for (String dep : sorted(overlappingDeps)) {
				sb.append(String.format("        - %s%n", dep));
			}
		}
		sb.append(String.format(Locale.US, "      %-24s %+d  (complexity: %.2f)%n", "Change complexity:",
				complexityPoints, complexityOverlap));
		sb.append(String.format("      %-24s %+d%s%n", "Static field overlap:", staticFieldPoints,
				hasStaticFieldOverlap ? "  (yes)" : ""));
		sb.append(String.format(Locale.US, "      %-24s %+d  (raw: %.2f, cap: %d)%n", "Failure history:", failurePoints,
				failScore, weights.maxFailure()));
		sb.append(String.format("      %-24s %+d%s%n", "New test bonus:", newTestPoints, isNew ? "  (yes)" : ""));

		String durationStr = durationMs >= 0
				? String.format(Locale.US, "%dms (median: %dms, ratio: %+.2f)", durationMs, medianDurationMs,
						speedRatio)
				: "unknown";
		sb.append(String.format("      %-24s %+d  (%s)%n", "Speed:", speedPoints, durationStr));
		if (setCoverPoints != 0) {
			sb.append(String.format("      %-24s %+d%n", "Set-cover bonus:", setCoverPoints));
		}

		// dependency list (non-overlapping)
		Set<String> nonOverlapping = new TreeSet<>(dependencies);
		nonOverlapping.removeAll(overlappingDeps);
		if (!nonOverlapping.isEmpty()) {
			sb.append(String.format("      Other dependencies (%d):%n", nonOverlapping.size()));
			for (String dep : nonOverlapping) {
				sb.append(String.format("        - %s%n", dep));
			}
		}

		return sb.toString();
	}

	private static List<String> sorted(Set<String> set) {
		List<String> list = new ArrayList<>(set);
		Collections.sort(list);
		return list;
	}
}
