package me.bechberger.testorder;

import java.util.List;
import java.util.Locale;

/**
 * Advice that a test class should be considered for splitting into smaller,
 * more cohesive classes.
 *
 * <p>
 * When test methods within a class cover largely disjoint sets of production
 * code, test-order cannot schedule the most-relevant methods independently — it
 * must run the whole class as a unit. Splitting the class into cohesive
 * sub-classes unlocks class-level reordering.
 *
 * <p>
 * A {@code TestSplitAdvice} is produced by {@link TestSplitAdvisor} based on
 * the pairwise Jaccard similarity of each method's dependency set. Low average
 * similarity means the methods are essentially independent and are good
 * candidates for separation.
 *
 * <p>
 * Example output:
 *
 * <pre>
 * class: com.example.BigServiceTest
 *   methods with dependency data: 8
 *   avg pairwise similarity: 0.06 (threshold 0.30)
 *   suggested groups:
 *     group 1: [testCreate, testUpdate, testDelete]
 *     group 2: [testReport, testExport]
 *     group 3: [testHealthCheck, testMetrics, testStatus]
 *   reason: 8 methods have avg pairwise Jaccard similarity of 0.06 (< threshold 0.30);
 *           consider splitting into 3 focused test classes
 * </pre>
 *
 * @param className
 *            fully-qualified name of the test class
 * @param methodsWithDeps
 *            number of test methods that have dependency data (≥2 by contract)
 * @param avgPairwiseSimilarity
 *            average pairwise Jaccard similarity across all method pairs (0 =
 *            fully independent, 1 = all methods cover exactly the same
 *            production classes)
 * @param suggestedGroups
 *            greedy clustering of method names into cohesive groups; each inner
 *            list is a proposed new test class; order within each group is
 *            alphabetical
 * @param reason
 *            human-readable explanation suitable for printing in a report
 */
public record TestSplitAdvice(String className, int methodsWithDeps, double avgPairwiseSimilarity,
		List<List<String>> suggestedGroups, String reason) {

	/** Returns a one-line summary for quick display. */
	public String summary() {
		return String.format(Locale.US, "%s  sim=%.2f  methods=%d  groups=%d", className, avgPairwiseSimilarity,
				methodsWithDeps, suggestedGroups.size());
	}
}
