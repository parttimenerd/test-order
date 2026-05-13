package me.bechberger.testorder.ml;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks which test classes fail together (co-failure) across runs.
 * <p>
 * For each test class, maintains the set of other classes that have failed in
 * the same run at least once. The "shared failure proximity" between two
 * classes is the Jaccard similarity of their co-failure partner sets.
 * <p>
 * This captures an important signal for failure prediction: tests that
 * historically fail alongside other tests are likely coupled to the same
 * production code.
 */
public final class CoFailureTracker {

	// testClass → set of other classes that have failed in the same run
	private final Map<String, Set<String>> coFailurePartners = new HashMap<>();

	/**
	 * Records a run's failures: all pairs of failed classes become co-failure
	 * partners.
	 *
	 * @param failedClasses
	 *            set of test class names that failed in this run
	 */
	public void recordRun(Set<String> failedClasses) {
		if (failedClasses.size() < 2) {
			// A single failure has no co-failure partners to record,
			// but we still register the class so it appears in the tracker.
			for (String cls : failedClasses) {
				coFailurePartners.computeIfAbsent(cls, k -> new HashSet<>());
			}
			return;
		}
		for (String cls : failedClasses) {
			Set<String> partners = coFailurePartners.computeIfAbsent(cls, k -> new HashSet<>());
			for (String other : failedClasses) {
				if (!other.equals(cls)) {
					partners.add(other);
				}
			}
		}
	}

	/**
	 * Computes the shared failure proximity for a test class relative to the
	 * currently-changed classes. Returns the maximum Jaccard similarity between
	 * this test's co-failure partner set and any changed test's co-failure partner
	 * set.
	 *
	 * @param testClass
	 *            the test class to evaluate
	 * @param changedClasses
	 *            test classes that changed (the diff context)
	 * @return value in [0.0, 1.0]; 0.0 if no co-failure history
	 */
	public double sharedFailureProximity(String testClass, Set<String> changedClasses) {
		Set<String> myPartners = coFailurePartners.getOrDefault(testClass, Set.of());
		if (myPartners.isEmpty()) {
			return 0.0;
		}
		double maxSimilarity = 0.0;
		for (String changed : changedClasses) {
			Set<String> theirPartners = coFailurePartners.getOrDefault(changed, Set.of());
			if (theirPartners.isEmpty()) {
				continue;
			}
			double sim = jaccardSimilarity(myPartners, theirPartners);
			if (sim > maxSimilarity) {
				maxSimilarity = sim;
			}
		}
		return maxSimilarity;
	}

	/**
	 * Returns the co-failure partner set for a test class.
	 */
	public Set<String> getPartners(String testClass) {
		return coFailurePartners.getOrDefault(testClass, Set.of());
	}

	/**
	 * Returns all test classes known to this tracker.
	 */
	public Set<String> knownClasses() {
		return coFailurePartners.keySet();
	}

	private static double jaccardSimilarity(Set<String> a, Set<String> b) {
		if (a.isEmpty() && b.isEmpty()) {
			return 0.0;
		}
		int intersection = 0;
		// iterate over the smaller set for efficiency
		Set<String> smaller = a.size() <= b.size() ? a : b;
		Set<String> larger = a.size() <= b.size() ? b : a;
		for (String s : smaller) {
			if (larger.contains(s)) {
				intersection++;
			}
		}
		int union = a.size() + b.size() - intersection;
		return union == 0 ? 0.0 : (double) intersection / union;
	}
}
