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
	 * forks.
	 */
	private static final Set<String> LOGGED_STATE_ERRORS = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
		String indexPath = config.getConfig(TestOrderConfig.INDEX_PATH);
		if (indexPath == null || indexPath.isEmpty()) {
			return null;
		}
		Path idx = Path.of(indexPath);
		if (!Files.exists(idx)) {
			return null;
		}

		DependencyMap depMap;
		try {
			depMap = DependencyMap.load(idx);
		} catch (IOException e) {
			TestOrderLogger.error("Failed to load dependency index: {}", e.getMessage());
			return null;
		}

		String statePath = config.getConfig(TestOrderConfig.STATE_PATH);
		TestOrderState state;
		try {
			state = (statePath != null && !statePath.isEmpty() && Files.exists(Path.of(statePath)))
					? TestOrderState.load(Path.of(statePath))
					: new TestOrderState();
		} catch (IOException e) {
			if (LOGGED_STATE_ERRORS.add(e.getMessage())) {
				TestOrderLogger.error("Failed to load state: {} — falling back to defaults.", e.getMessage());
			}
			state = new TestOrderState();
		}

		Set<String> changedClasses = config.resolveChangedClasses();
		Set<String> changedTestClasses = config.resolveChangedTestClasses();
		boolean debug = config.getConfigBool(TestOrderConfig.DEBUG, false);

		TestOrderState.ScoringWeights effectiveWeights = config.resolveEffectiveWeights(config.loadBaseWeights(state));

		// Structural change analysis
		ChangedMembers changedMembers = null;
		List<StructuralDiff.FileDiff> structuralDiffs = null;
		String projectRootStr = config.getConfig(TestOrderConfig.PROJECT_ROOT);
		boolean structuralEnabled = config.getConfigBool(TestOrderConfig.STRUCTURAL_DIFF_ENABLED, true);
		if (structuralEnabled && projectRootStr != null && !projectRootStr.isEmpty()) {
			try {
				Path projectRoot = Path.of(projectRootStr);
				String changeMode = config.getConfig(TestOrderConfig.CHANGE_MODE);
				StructuralChangeAnalyzer.AnalysisResult analysis;
				if ("since-last-commit".equals(changeMode) || "SINCE_LAST_COMMIT".equals(changeMode)) {
					analysis = StructuralChangeAnalyzer.analyzeSinceLastCommitFull(projectRoot);
				} else {
					analysis = StructuralChangeAnalyzer.analyzeUncommittedFull(projectRoot);
				}
				changedMembers = analysis.changedMembers();
				structuralDiffs = analysis.diffs();
				if (debug) {
					TestOrderLogger.debug("[structural] {} classes with structural changes, {} changed members",
							changedMembers.changedClasses().size(), changedMembers.changedMemberKeys().size());
				}
			} catch (IOException e) {
				TestOrderLogger.debug("[structural] Failed to compute structural analysis: {}", e.getMessage());
			}
		}

		// Change complexity
		Map<String, Double> changeComplexityMap = Map.of();
		if (!changedClasses.isEmpty() && projectRootStr != null && !projectRootStr.isEmpty()) {
			Path projectRoot = Path.of(projectRootStr);
			String srcRoot = config.getConfig(TestOrderConfig.SOURCE_ROOT);
			List<Path> sourceRoots = new ArrayList<>();
			if (srcRoot != null && !srcRoot.isBlank()) {
				sourceRoots.add(Path.of(srcRoot));
			} else {
				sourceRoots.add(projectRoot.resolve("src/main/java"));
				sourceRoots.add(projectRoot.resolve("src/main/kotlin"));
			}
			changeComplexityMap = ChangeComplexity.compute(changedClasses, sourceRoots, changedMembers,
					structuralDiffs);
		}
		if (changeComplexityMap.isEmpty()) {
			changeComplexityMap = ChangeComplexity.deserialise(config.getConfig(TestOrderConfig.CHANGE_COMPLEXITY));
		}

		return new SetupResult(depMap, state, effectiveWeights, changedClasses, changedTestClasses, changedMembers,
				structuralDiffs, changeComplexityMap, debug, statePath);
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
							result.hasStaticFieldOverlap()));
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
		TreeMap<Integer, List<String>> groups = new TreeMap<>(Comparator.reverseOrder());
		for (String className : classNames) {
			groups.computeIfAbsent(scores.getOrDefault(className, 0), k -> new ArrayList<>()).add(className);
		}

		List<String> result = new ArrayList<>(classNames.size());
		Set<String> coveredDeps = new HashSet<>();

		for (var entry : groups.entrySet()) {
			List<String> group = new ArrayList<>(entry.getValue());
			while (!group.isEmpty()) {
				int bestIdx = -1;
				double bestDistance = -1;
				long bestDuration = Long.MAX_VALUE;
				String bestName = null;

				for (int i = 0; i < group.size(); i++) {
					String name = group.get(i);
					Set<String> deps = depMap.get(name);
					double distance = TestSelector.jaccardDistance(deps, coveredDeps);
					long dur = state.getDuration(name, Long.MAX_VALUE);

					if (distance > bestDistance || (distance == bestDistance && dur < bestDuration)
							|| (distance == bestDistance && dur == bestDuration && bestName != null
									&& name.compareTo(bestName) < 0)) {
						bestIdx = i;
						bestDistance = distance;
						bestDuration = dur;
						bestName = name;
					}
				}

				String best = group.get(bestIdx);
				int last = group.size() - 1;
				if (bestIdx != last) {
					group.set(bestIdx, group.get(last));
				}
				group.remove(last);
				result.add(best);
				coveredDeps.addAll(depMap.get(best));
			}
		}
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
			depsCache.put(item, depMap.get(nameFunc.apply(item)));
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
					String name = nameFunc.apply(item);
					long dur = state.getDuration(name, Long.MAX_VALUE);
					boolean matchesSpringContext = springContextFunc != null && activeSpringContext != null
							&& Objects.equals(activeSpringContext, springContextFunc.apply(item));

					if (distance > bestDistance
							|| (distance == bestDistance && matchesSpringContext && !bestMatchesSpringContext)
							|| (distance == bestDistance && matchesSpringContext == bestMatchesSpringContext
									&& dur < bestDuration)
							|| (distance == bestDistance && matchesSpringContext == bestMatchesSpringContext
									&& dur == bestDuration && bestName != null && name.compareTo(bestName) < 0)) {
						bestIdx = i;
						bestDistance = distance;
						bestMatchesSpringContext = matchesSpringContext;
						bestDuration = dur;
						bestName = name;
					}
				}

				T best = group.get(bestIdx);
				int last = group.size() - 1;
				if (bestIdx != last) {
					group.set(bestIdx, group.get(last));
				}
				group.remove(last);
				result.add(best);
				coveredDeps.addAll(depsCache.get(best));
				if (springContextFunc != null) {
					activeSpringContext = springContextFunc.apply(best);
				}
			}
		}

		items.clear();
		items.addAll(result);
	}
}
