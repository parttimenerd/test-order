package me.bechberger.testorder.changes;

import java.util.*;

import me.bechberger.testorder.DependencyMap;

/**
 * Derives missing test → production-class edges from compiled test bytecode and
 * returns them as a delta to be merged into the runtime {@link DependencyMap}
 * via {@link DependencyMap#withAugmentation(Map)}.
 *
 * <p>
 * Motivation: the agent's recorded dependency map can be stale — a test's
 * compiled bytecode may reference a production class the agent didn't observe
 * at learn time (test was skipped, code path not exercised, fork crashed before
 * write, etc.). This augmenter cross-checks the recorded edges against what the
 * bytecode actually references and supplies the missing edges so affected-test
 * selection doesn't miss them.
 *
 * <p>
 * <b>Augment-only</b>: this class only ever returns <em>new</em> edges. It
 * never reports edges to remove — reflection, dynamic class loading and
 * service-loader patterns can produce dependencies that bytecode analysis
 * cannot see, and dropping them would silently lose tests.
 */
public final class BytecodeDependencyAugmenter {

	private BytecodeDependencyAugmenter() {
	}

	/**
	 * Cache of the inverted call graph (callerClass → set of calleeClasses) keyed
	 * by {@link StaticCallGraphAnalyzer.ScanResult} identity. Avoids rebuilding the
	 * inversion on every {@link #computeAugmentation} call when the same scan
	 * result is reused across multiple dependency maps.
	 */
	private static final IdentityHashMap<StaticCallGraphAnalyzer.ScanResult, Map<String, Set<String>>> INVERTED_CACHE = new IdentityHashMap<>();

	/**
	 * Invalidates the cached inverted graph for the given scan result. Call this
	 * when a scan result is updated or discarded to free memory.
	 */
	public static void invalidateCache(StaticCallGraphAnalyzer.ScanResult testScan) {
		INVERTED_CACHE.remove(testScan);
	}

	/**
	 * Computes the augmenting test → prod-class edges by inverting
	 * {@code testScan.reverseCallGraph()} into per-test outgoing edges and then
	 * subtracting the edges already recorded in {@code depMap}.
	 *
	 * <p>
	 * Only test classes already present in {@code depMap.testClasses()} get
	 * augmenting edges — we never invent new test entries. The returned map maps
	 * test FQCN → set of prod-class FQCNs missing from {@code depMap.get(test)}.
	 * Tests with no missing edges are omitted entirely.
	 */
	public static Map<String, Set<String>> computeAugmentation(StaticCallGraphAnalyzer.ScanResult testScan,
			DependencyMap depMap) {
		if (testScan == null || depMap == null) {
			return Map.of();
		}
		Set<String> testClasses = depMap.testClasses();
		if (testClasses.isEmpty()) {
			return Map.of();
		}
		if (testScan.reverseCallGraph().isEmpty()) {
			return Map.of();
		}

		// Invert: callerClass → set of calleeClasses (strip member, keep FQCN only).
		// The inversion is cached by ScanResult identity to avoid rebuilding on every
		// call when the same scan result is reused across multiple dependency maps.
		// Note: the cache stores edges for all caller classes; test-class filtering
		// happens below so the cached map can be shared across different depMaps.
		Map<String, Set<String>> invertedGraph = INVERTED_CACHE.computeIfAbsent(testScan, scan -> {
			Map<String, Set<String>> inverted = new HashMap<>();
			for (var entry : scan.reverseCallGraph().entrySet()) {
				String calleeKey = entry.getKey();
				String calleeClass = classOf(calleeKey);
				if (calleeClass == null || ClassNameFilter.isLibraryType(calleeClass)) {
					continue;
				}
				for (String callerKey : entry.getValue()) {
					String callerClass = classOf(callerKey);
					if (callerClass == null || callerClass.equals(calleeClass)) {
						continue; // skip self-references
					}
					inverted.computeIfAbsent(callerClass, k -> new HashSet<>()).add(calleeClass);
				}
			}
			return inverted;
		});

		Map<String, Set<String>> augmentation = new HashMap<>();
		for (var entry : invertedGraph.entrySet()) {
			String testClass = entry.getKey();
			if (!testClasses.contains(testClass)) {
				continue; // only augment edges for tests known to depMap
			}
			Set<String> bytecodeRefs = entry.getValue();
			Set<String> existing = depMap.get(testClass);
			Set<String> missing = null;
			for (String ref : bytecodeRefs) {
				if (testClasses.contains(ref)) {
					continue; // test → test edges are not production deps
				}
				if (existing.contains(ref)) {
					continue;
				}
				// Match the nested-class fallback used by DependencyMap#changedClassesContains:
				// a recorded dep on the top-level class covers nested-class references.
				int dollar = ref.indexOf('$');
				if (dollar > 0 && existing.contains(ref.substring(0, dollar))) {
					continue;
				}
				if (missing == null) {
					missing = new HashSet<>();
				}
				missing.add(ref);
			}
			if (missing != null && !missing.isEmpty()) {
				augmentation.put(testClass, missing);
			}
		}
		return augmentation;
	}

	/**
	 * Extracts the FQCN portion from a {@code Foo#bar} key. Returns the input
	 * unchanged if no {@code #} is present (treated as a bare class name).
	 */
	private static String classOf(String key) {
		if (key == null || key.isEmpty()) {
			return null;
		}
		int hash = key.indexOf('#');
		return hash < 0 ? key : key.substring(0, hash);
	}
}
