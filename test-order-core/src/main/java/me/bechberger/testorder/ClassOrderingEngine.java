package me.bechberger.testorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import me.bechberger.testorder.changes.ChangeComplexity;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;
import me.bechberger.testorder.changes.StructuralDiff;

/**
 * Shared class-ordering engine used by both the JUnit PriorityClassOrderer and
 * TestNG TestNGPriorityInterceptor. Encapsulates:
 * <ul>
 * <li>Loading dependencies, state, and weights</li>
 * <li>Structural change analysis and complexity computation</li>
 * <li>Scoring via {@link TestScorer}</li>
 * <li>Greedy Jaccard diversity sort</li>
 * </ul>
 */
public final class ClassOrderingEngine {

	/**
	 * Tracks already-logged state load failures to avoid 12x duplication across
	 * forks. Capped at 100 entries: once full, further unique messages are logged
	 * without deduplication (acceptable — failure messages are infrequent).
	 */
	private static final Set<String> LOGGED_STATE_ERRORS = java.util.concurrent.ConcurrentHashMap.newKeySet(100);

	/**
	 * Tracks index-load error messages already reported to avoid log spam when the
	 * same corrupt index is read once per test class. Capped at 100 entries.
	 */
	private static final Set<String> LOGGED_INDEX_ERRORS = java.util.concurrent.ConcurrentHashMap.newKeySet(100);

	/** Result of the setup phase — everything needed to score and sort classes. */
	public record SetupResult(DependencyMap depMap, TestOrderState state,
			TestOrderState.ScoringWeights effectiveWeights, Set<String> changedClasses, Set<String> changedTestClasses,
			ChangedMembers changedMembers, List<StructuralDiff.FileDiff> structuralDiffs,
			Map<String, Double> changeComplexityMap, boolean debug, String statePath) {
	}

	private ClassOrderingEngine() {
	}

	/**
	 * Performs the full setup: loads dependency map, state, resolves changes,
	 * performs structural analysis, and computes change complexity.
	 *
	 * @return setup result, or {@code null} if the index path is missing/invalid
	 */
	public static SetupResult setup(TestOrderConfigResolver config) {
		DependencyMap depMap = setupIO(config);
		if (depMap == null) {
			return null;
		}

		TestOrderState state = setupState(config);
		Set<String> changedClasses = config.resolveChangedClasses();
		Set<String> changedTestClasses = config.resolveChangedTestClasses();
		TestOrderState.ScoringWeights effectiveWeights = config.resolveEffectiveWeights(config.loadBaseWeights(state));
		boolean debug = config.getConfigBool(TestOrderConfig.DEBUG, false);
		String statePath = config.getConfig(TestOrderConfig.STATE_PATH);

		var analysis = setupAnalysis(config, debug);
		var changeComplexity = setupComplexity(config, changedClasses, analysis);

		return new SetupResult(depMap, state, effectiveWeights, changedClasses, changedTestClasses,
				analysis.changedMembers(), analysis.diffs(), changeComplexity, debug, statePath);
	}

	/**
	 * Loads the dependency index file. Returns null if index path is missing or
	 * file does not exist.
	 */
	private static DependencyMap setupIO(TestOrderConfigResolver config) {
		String indexPath = config.getConfig(TestOrderConfig.INDEX_PATH);
		if (indexPath == null || indexPath.isEmpty()) {
			return null;
		}
		Path idx = Path.of(indexPath);
		if (!Files.exists(idx)) {
			return null;
		}

		try {
			return DependencyMap.load(idx);
		} catch (IOException | RuntimeException e) {
			String msg = e.getClass().getName() + ": " + e.getMessage();
			if (LOGGED_INDEX_ERRORS.add(msg)) {
				TestOrderLogger.error("Failed to load dependency index: {}", e.getMessage());
			}
			return null;
		}
	}

	/**
	 * Loads or creates the test-order state file. Returns a fresh state if load
	 * fails, with deduplication of repeated error messages.
	 */
	private static TestOrderState setupState(TestOrderConfigResolver config) {
		String statePath = config.getConfig(TestOrderConfig.STATE_PATH);
		try {
			if (statePath != null && !statePath.isEmpty() && Files.exists(Path.of(statePath))) {
				return TestOrderState.load(Path.of(statePath));
			}
		} catch (IOException | RuntimeException e) {
			String msg = e.getClass().getName() + ": " + e.getMessage();
			if (LOGGED_STATE_ERRORS.add(msg)) {
				TestOrderLogger.error("Failed to load state: {} — falling back to defaults.", e.getMessage());
			}
		}
		return new TestOrderState();
	}

	/** Analysis result holder for structural analysis. */
	private record AnalysisResult(ChangedMembers changedMembers, List<StructuralDiff.FileDiff> diffs) {
	}

	/**
	 * Performs structural change analysis if enabled. Returns empty result if
	 * disabled or analysis fails.
	 */
	private static AnalysisResult setupAnalysis(TestOrderConfigResolver config, boolean debug) {
		String projectRootStr = config.getConfig(TestOrderConfig.PROJECT_ROOT);
		boolean structuralEnabled = config.getConfigBool(TestOrderConfig.STRUCTURAL_DIFF_ENABLED, true);

		if (!structuralEnabled || projectRootStr == null || projectRootStr.isEmpty()) {
			return new AnalysisResult(null, null);
		}

		try {
			Path projectRoot = Path.of(projectRootStr);
			String changeMode = config.getConfig(TestOrderConfig.CHANGE_MODE);
			String normalizedMode = changeMode != null
					? changeMode.replace('_', '-').toLowerCase(java.util.Locale.ROOT)
					: "";
			StructuralChangeAnalyzer.AnalysisResult analysis = "since-last-commit".equals(normalizedMode)
					? StructuralChangeAnalyzer.analyzeSinceLastCommitFull(projectRoot)
					: StructuralChangeAnalyzer.analyzeUncommittedFull(projectRoot);
			if (debug) {
				TestOrderLogger.debug("[structural] {} classes with structural changes, {} changed members",
						analysis.changedMembers().changedClasses().size(),
						analysis.changedMembers().changedMemberKeys().size());
			}
			return new AnalysisResult(analysis.changedMembers(), analysis.diffs());
		} catch (IOException e) {
			TestOrderLogger.debug("[structural] Failed to compute structural analysis: {}", e.getMessage());
			return new AnalysisResult(null, null);
		}
	}

	/**
	 * Computes change complexity if changed classes are present. Returns empty map
	 * if no analysis data available or complexity computation fails.
	 */
	private static Map<String, Double> setupComplexity(TestOrderConfigResolver config, Set<String> changedClasses,
			AnalysisResult analysis) {
		if (changedClasses.isEmpty()) {
			return Map.of();
		}

		String projectRootStr = config.getConfig(TestOrderConfig.PROJECT_ROOT);
		if (projectRootStr == null || projectRootStr.isEmpty()) {
			Map<String, Double> deserialized = ChangeComplexity
					.deserialise(config.getConfig(TestOrderConfig.CHANGE_COMPLEXITY));
			return deserialized != null ? deserialized : Map.of();
		}

		Path projectRoot = Path.of(projectRootStr);
		String srcRoot = config.getConfig(TestOrderConfig.SOURCE_ROOT);
		List<Path> sourceRoots = new ArrayList<>();
		if (srcRoot != null && !srcRoot.isBlank()) {
			sourceRoots.add(Path.of(srcRoot));
		} else {
			sourceRoots.add(projectRoot.resolve("src/main/java"));
			sourceRoots.add(projectRoot.resolve("src/main/kotlin"));
		}

		Map<String, Double> complexityMap = ChangeComplexity.compute(changedClasses, sourceRoots,
				analysis.changedMembers(), analysis.diffs());
		if (!complexityMap.isEmpty()) {
			return complexityMap;
		}

		Map<String, Double> deserialized = ChangeComplexity
				.deserialise(config.getConfig(TestOrderConfig.CHANGE_COMPLEXITY));
		return deserialized != null ? deserialized : Map.of();
	}

	/**
	 * Creates a {@link TestScorer} from the setup result.
	 */
	public static TestScorer buildScorer(SetupResult s, List<String> testClassNames) {
		return new TestScorer.Builder(s.effectiveWeights(), s.depMap(), s.state(), s.changedClasses(),
				s.changedTestClasses()).testClassNames(testClassNames).changedMembers(s.changedMembers())
				.changeComplexity(s.changeComplexityMap()).build();
	}

	/**
	 * Records a score breakdown for run history.
	 */
	public static void recordBreakdown(String statePath, String testClassName, TestScorer.ScoreResult result) {
		if (statePath != null && !statePath.isEmpty()) {
			TestOrderState.recordBreakdown(testClassName,
					new TestOrderState.ScoreBreakdown(result.score(), result.isNew(), result.isChanged(),
							result.depOverlap(), result.depTotal(), result.failScore(), result.isFast(),
							result.isSlow(), result.complexityOverlap(), result.speedRatio(),
							result.hasStaticFieldOverlap(), result.weightedDepOverlap()));
		}
	}

	// ── Diversity sort ────────────────────────────────────────────────

	/**
	 * Orders class names by score descending, using greedy Jaccard-distance
	 * diversity as tie-breaker within each score group.
	 *
	 * @param classNames
	 *            class names to order (not modified)
	 * @param scores
	 *            score for each class name
	 * @param depMap
	 *            dependency map for Jaccard distance
	 * @param state
	 *            state for duration tie-breaking
	 * @return ordered list of class names
	 */
	public static List<String> orderByScoreAndDiversity(List<String> classNames, Map<String, Integer> scores,
			DependencyMap depMap, TestOrderState state) {
		List<String> result = new ArrayList<>(classNames);
		orderByScoreAndDiversity(result, (String name) -> scores.getOrDefault(name, 0), name -> name, depMap, state,
				null);
		return result;
	}

	/**
	 * Extended diversity sort with Spring context grouping support. Used by the
	 * JUnit orderer which operates on generic items rather than plain class names.
	 *
	 * @param <T>
	 *            item type
	 * @param items
	 *            items to order (modified in-place)
	 * @param scoreFunc
	 *            extracts score from item
	 * @param nameFunc
	 *            extracts class name from item
	 * @param depMap
	 *            dependency map
	 * @param state
	 *            state for duration tie-breaking
	 * @param springContextFunc
	 *            extracts Spring context key (null to disable grouping)
	 */
	public static <T> void orderByScoreAndDiversity(List<T> items, java.util.function.ToIntFunction<T> scoreFunc,
			java.util.function.Function<T, String> nameFunc, DependencyMap depMap, TestOrderState state,
			java.util.function.Function<T, String> springContextFunc) {

		Map<T, Set<String>> depsCache = new HashMap<>(items.size());
		for (T item : items) {
			Set<String> d = depMap.get(nameFunc.apply(item));
			depsCache.put(item, d != null ? d : Set.of());
		}

		TreeMap<Integer, List<T>> groups = new TreeMap<>(Comparator.reverseOrder());
		for (T item : items) {
			groups.computeIfAbsent(scoreFunc.applyAsInt(item), k -> new ArrayList<>()).add(item);
		}

		List<T> result = new ArrayList<>(items.size());
		Set<String> coveredDeps = new HashSet<>();
		String activeSpringContext = null;

		for (var entry : groups.entrySet()) {
			List<T> group = new ArrayList<>(entry.getValue());
			// Singleton groups need no diversity sort
			if (group.size() == 1) {
				T single = group.get(0);
				result.add(single);
				Set<String> deps = depsCache.get(single);
				if (deps != null)
					coveredDeps.addAll(deps);
				if (springContextFunc != null) {
					activeSpringContext = springContextFunc.apply(single);
				}
				continue;
			}
			while (!group.isEmpty()) {
				int bestIdx = -1;
				double bestDistance = -1;
				boolean bestMatchesSpringContext = false;
				long bestDuration = Long.MAX_VALUE;
				String bestName = null;

				for (int i = 0; i < group.size(); i++) {
					T item = group.get(i);
					Set<String> deps = depsCache.get(item);
					double distance = TestSelector.jaccardDistance(deps, coveredDeps);

					if (distance > bestDistance) {
						bestIdx = i;
						bestDistance = distance;
						bestName = nameFunc.apply(item);
						bestDuration = state.getDuration(bestName, Long.MAX_VALUE);
						bestMatchesSpringContext = springContextFunc != null && activeSpringContext != null
								&& Objects.equals(activeSpringContext, springContextFunc.apply(item));
					} else if (distance == bestDistance) {
						String name = nameFunc.apply(item);
						long dur = state.getDuration(name, Long.MAX_VALUE);
						boolean matchesSpringContext = springContextFunc != null && activeSpringContext != null
								&& Objects.equals(activeSpringContext, springContextFunc.apply(item));
						if ((matchesSpringContext && !bestMatchesSpringContext)
								|| (matchesSpringContext == bestMatchesSpringContext && dur < bestDuration)
								|| (matchesSpringContext == bestMatchesSpringContext && dur == bestDuration
										&& bestName != null && name.compareTo(bestName) < 0)) {
							bestIdx = i;
							bestMatchesSpringContext = matchesSpringContext;
							bestDuration = dur;
							bestName = name;
						}
					}
				}

				T best = group.get(bestIdx);
				int last = group.size() - 1;
				if (bestIdx != last) {
					group.set(bestIdx, group.get(last));
				}
				group.remove(last);
				result.add(best);
				Set<String> bestDeps = depsCache.get(best);
				if (bestDeps != null)
					coveredDeps.addAll(bestDeps);
				if (springContextFunc != null) {
					activeSpringContext = springContextFunc.apply(best);
				}
			}
		}

		items.clear();
		items.addAll(result);
	}
}
