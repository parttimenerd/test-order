package me.bechberger.testorder;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyses a {@link DependencyMap} and returns advice on which test classes
 * should be considered for splitting.
 *
 * <p>
 * A class is flagged when the average pairwise Jaccard similarity of its test
 * methods' dependency sets is below a configurable threshold. Low similarity
 * means the methods cover largely disjoint production code and would benefit
 * from being in separate, cohesive test classes — which in turn lets test-order
 * schedule them independently at the class level.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * DependencyMap depMap = DependencyMap.load(indexPath);
 * List&lt;TestSplitAdvice&gt; advice = TestSplitAdvisor.analyze(depMap);
 * advice.forEach(a -&gt; System.out.println(a.summary()));
 * </pre>
 */
public class TestSplitAdvisor {

	/**
	 * Default similarity threshold: classes whose methods have an average pairwise
	 * Jaccard similarity below this value are flagged as split candidates.
	 */
	public static final double DEFAULT_THRESHOLD = 0.3;

	/**
	 * Minimum inter-group avg similarity required to merge two groups during
	 * clustering. Groups whose methods share at least this fraction of their
	 * dependencies are kept together.
	 */
	private static final double MERGE_THRESHOLD = 0.4;

	/**
	 * Minimum number of methods with dependency data required to analyse a class.
	 */
	private static final int MIN_METHODS = 2;

	private TestSplitAdvisor() {
	}

	/**
	 * Analyse all classes in {@code depMap} using {@link #DEFAULT_THRESHOLD} and
	 * return advice for those below the threshold, sorted by ascending
	 * {@code avgPairwiseSimilarity} (most split-worthy first).
	 */
	public static List<TestSplitAdvice> analyze(DependencyMap depMap) {
		return analyze(depMap, DEFAULT_THRESHOLD);
	}

	/**
	 * Analyse all classes in {@code depMap} and return advice for those whose
	 * average pairwise Jaccard similarity is below {@code threshold}.
	 *
	 * @param depMap
	 *            dependency map (may or may not contain per-method data)
	 * @param threshold
	 *            similarity threshold in [0, 1]; classes below this value are
	 *            flagged
	 * @return list of advice sorted by {@code avgPairwiseSimilarity} ascending
	 */
	public static List<TestSplitAdvice> analyze(DependencyMap depMap, double threshold) {
		if (!depMap.hasMethodDeps()) {
			return Collections.emptyList();
		}

		// Group method keys by class name
		Map<String, List<String>> byClass = new LinkedHashMap<>();
		for (String key : depMap.methodKeys()) {
			int hash = key.lastIndexOf('#');
			if (hash < 0)
				continue;
			String cls = key.substring(0, hash);
			byClass.computeIfAbsent(cls, k -> new ArrayList<>()).add(key);
		}

		List<TestSplitAdvice> results = new ArrayList<>();

		for (Map.Entry<String, List<String>> entry : byClass.entrySet()) {
			String className = entry.getKey();
			List<String> methodKeys = entry.getValue();

			// Collect non-empty dep sets
			List<Map.Entry<String, Set<String>>> methods = new ArrayList<>();
			for (String mk : methodKeys) {
				Set<String> deps = depMap.getMethodDeps(mk);
				if (!deps.isEmpty()) {
					String methodName = mk.substring(mk.lastIndexOf('#') + 1);
					methods.add(Map.entry(methodName, deps));
				}
			}

			if (methods.size() < MIN_METHODS)
				continue;

			double avgSim = computeAvgPairwiseSimilarity(methods);
			if (avgSim >= threshold)
				continue;

			List<List<String>> groups = cluster(methods);
			String reason = buildReason(className, methods.size(), avgSim, threshold, groups.size());
			results.add(new TestSplitAdvice(className, methods.size(), avgSim, groups, reason));
		}

		results.sort(Comparator.comparingDouble(TestSplitAdvice::avgPairwiseSimilarity));
		return Collections.unmodifiableList(results);
	}

	// --- internals ---

	/**
	 * Computes the average pairwise Jaccard similarity over all distinct pairs of
	 * methods.
	 *
	 * <p>
	 * Jaccard similarity = |A ∩ B| / |A ∪ B|. Returns 0.0 when either set is empty.
	 */
	static double computeAvgPairwiseSimilarity(List<Map.Entry<String, Set<String>>> methods) {
		int n = methods.size();
		double sum = 0.0;
		int pairs = 0;
		for (int i = 0; i < n; i++) {
			for (int j = i + 1; j < n; j++) {
				sum += jaccardSimilarity(methods.get(i).getValue(), methods.get(j).getValue());
				pairs++;
			}
		}
		return pairs == 0 ? 0.0 : sum / pairs;
	}

	/**
	 * Greedy agglomerative clustering: start with one group per method; repeatedly
	 * merge the pair of groups with the highest inter-group average similarity as
	 * long as it is above {@link #MERGE_THRESHOLD}. Returns groups sorted by size
	 * descending then name ascending.
	 */
	static List<List<String>> cluster(List<Map.Entry<String, Set<String>>> methods) {
		// Each cluster is a list of (methodName, depSet) entries
		List<List<Map.Entry<String, Set<String>>>> clusters = new ArrayList<>();
		for (Map.Entry<String, Set<String>> m : methods) {
			List<Map.Entry<String, Set<String>>> c = new ArrayList<>();
			c.add(m);
			clusters.add(c);
		}

		boolean merged = true;
		while (clusters.size() > 1 && merged) {
			merged = false;
			double bestSim = MERGE_THRESHOLD;
			int bestI = -1, bestJ = -1;

			// Limit iterations to prevent O(n^4) worst case: stop after inspecting N^2/4
			// pairs
			int maxChecks = Math.max(100, (clusters.size() * clusters.size()) / 4);
			int checksPerformed = 0;
			outerLoop : for (int i = 0; i < clusters.size() && checksPerformed < maxChecks; i++) {
				for (int j = i + 1; j < clusters.size() && checksPerformed < maxChecks; j++) {
					double sim = interGroupSimilarity(clusters.get(i), clusters.get(j));
					if (sim > bestSim) {
						bestSim = sim;
						bestI = i;
						bestJ = j;
					}
					checksPerformed++;
					if (bestI >= 0 && checksPerformed > 50) {
						break outerLoop;
					}
				}
			}

			if (bestI >= 0) {
				clusters.get(bestI).addAll(clusters.get(bestJ));
				clusters.remove(bestJ);
				merged = true;
			}
		}

		// Convert to sorted List<List<String>>
		return clusters.stream().map(c -> c.stream().map(Map.Entry::getKey).sorted().collect(Collectors.toList()))
				.sorted(Comparator.<List<String>, Integer>comparing(List::size).reversed()
						.thenComparing(g -> g.isEmpty() ? "" : g.get(0)))
				.collect(Collectors.toList());
	}

	/** Average Jaccard similarity between all pairs across two clusters. */
	private static double interGroupSimilarity(List<Map.Entry<String, Set<String>>> a,
			List<Map.Entry<String, Set<String>>> b) {
		double sum = 0.0;
		for (Map.Entry<String, Set<String>> ma : a) {
			for (Map.Entry<String, Set<String>> mb : b) {
				sum += jaccardSimilarity(ma.getValue(), mb.getValue());
			}
		}
		return sum / (a.size() * b.size());
	}

	/**
	 * Jaccard similarity = |A ∩ B| / |A ∪ B|. Returns 0.0 when either set is empty.
	 */
	static double jaccardSimilarity(Set<String> a, Set<String> b) {
		if (a.isEmpty() || b.isEmpty())
			return 0.0;
		int intersection = 0;
		Set<String> smaller = a.size() <= b.size() ? a : b;
		Set<String> larger = a.size() <= b.size() ? b : a;
		for (String s : smaller) {
			if (larger.contains(s))
				intersection++;
		}
		int union = a.size() + b.size() - intersection;
		return union == 0 ? 0.0 : (double) intersection / union;
	}

	private static String buildReason(String className, int methodCount, double avgSim, double threshold,
			int groupCount) {
		return String.format(
				"%d methods have avg pairwise Jaccard similarity of %.2f (< threshold %.2f); "
						+ "consider splitting '%s' into %d focused test class%s",
				methodCount, avgSim, threshold, simpleClassName(className), groupCount, groupCount == 1 ? "" : "es");
	}

	private static String simpleClassName(String fqcn) {
		int dot = fqcn.lastIndexOf('.');
		return dot < 0 ? fqcn : fqcn.substring(dot + 1);
	}
}
