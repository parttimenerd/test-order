package me.bechberger.testorder.testng;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import org.testng.IMethodInstance;
import org.testng.IMethodInterceptor;
import org.testng.ITestContext;

import me.bechberger.testorder.*;
import me.bechberger.testorder.changes.ChangeComplexity;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;
import me.bechberger.testorder.changes.StructuralDiff;

/**
 * TestNG method interceptor that reorders test methods across all classes based
 * on the test-order scoring engine.
 * <p>
 * Unlike JUnit 5, which has separate {@code ClassOrderer} and
 * {@code MethodOrderer} extension points, TestNG's {@code IMethodInterceptor}
 * receives <b>all</b> methods across <b>all</b> classes in a single list. This
 * interceptor handles both class-level and method-level ordering in one pass:
 * <ol>
 * <li>Groups methods by their declaring test class</li>
 * <li>Scores each class using {@link TestScorer} (same engine as JUnit's
 * PriorityClassOrderer)</li>
 * <li>Sorts class groups by score descending, with Jaccard diversity
 * tie-breaking</li>
 * <li>Within each class group, reorders methods using {@link MethodScorer}</li>
 * </ol>
 * <p>
 * Configuration via system properties (same keys as JUnit):
 * <ul>
 * <li>{@code testorder.index.path} — path to the dependency index</li>
 * <li>{@code testorder.state.path} — path to the state file</li>
 * <li>{@code testorder.changed.classes} — comma-separated changed class
 * FQCNs</li>
 * <li>{@code testorder.score.*} — individual weight overrides</li>
 * </ul>
 * Also reads {@code testorder-config.properties} from the classpath.
 * <p>
 * Auto-discovered via {@code META-INF/services/org.testng.ITestNGListener}.
 */
public class TestNGPriorityInterceptor implements IMethodInterceptor {

	private static final String CONFIG_RESOURCE = "testorder-config.properties";

	private static volatile boolean stateLoadErrorLogged;
	private Properties configProps;

	@Override
	public List<IMethodInstance> intercept(List<IMethodInstance> methods, ITestContext context) {
		String indexPath = getConfig(TestOrderConfig.INDEX_PATH);
		if (indexPath == null || indexPath.isEmpty()) {
			return methods;
		}
		Path idx = Path.of(indexPath);
		if (!Files.exists(idx)) {
			return methods;
		}

		DependencyMap depMap;
		try {
			depMap = DependencyMap.load(idx);
		} catch (IOException e) {
			TestOrderLogger.error("Failed to load dependency index: {}", e.getMessage());
			return methods;
		}

		// Load state
		String statePath = getConfig(TestOrderConfig.STATE_PATH);
		TestOrderState state;
		try {
			state = (statePath != null && !statePath.isEmpty() && Files.exists(Path.of(statePath)))
					? TestOrderState.load(Path.of(statePath))
					: new TestOrderState();
		} catch (IOException e) {
			if (!stateLoadErrorLogged) {
				stateLoadErrorLogged = true;
				TestOrderLogger.error("Failed to load state: {}", e.getMessage());
			}
			state = new TestOrderState();
		}

		Set<String> changedClasses = resolveChangedClasses();
		Set<String> changedTestClasses = resolveChangedTestClasses();
		boolean debug = getConfigBool(TestOrderConfig.DEBUG, false);

		// Resolve scoring weights
		TestOrderState.ScoringWeights sw = state.weights();
		String weightsFilePath = getConfig(TestOrderConfig.WEIGHTS_FILE);
		if (weightsFilePath != null && !weightsFilePath.isEmpty()) {
			Path wf = Path.of(weightsFilePath);
			if (Files.exists(wf)) {
				try {
					sw = TestOrderState.ScoringWeights.loadFromFile(wf).weights();
				} catch (IOException e) {
					TestOrderLogger.error("Failed to load weights file: {}", e.getMessage());
				}
			}
		}
		TestOrderState.ScoringWeights effectiveWeights = new TestOrderState.ScoringWeights(
				getConfigInt("testorder.score.newTest", sw.newTest()),
				getConfigInt("testorder.score.changedTest", sw.changedTest()),
				getConfigInt("testorder.score.maxFailure", sw.maxFailure()),
				getConfigInt("testorder.score.speed", sw.speed()),
				getConfigInt("testorder.score.speedPenalty", sw.speedPenalty()),
				getConfigInt("testorder.score.depOverlap", sw.depOverlap()),
				getConfigInt("testorder.score.changeComplexity", sw.changeComplexity()),
				getConfigInt("testorder.score.staticFieldBonus", sw.staticFieldBonus()),
				getConfigInt("testorder.score.coverageBonus", sw.coverageBonus()));

		// Group methods by declaring class
		Map<String, List<IMethodInstance>> byClass = new LinkedHashMap<>();
		for (IMethodInstance mi : methods) {
			String className = mi.getMethod().getRealClass().getName();
			byClass.computeIfAbsent(className, k -> new ArrayList<>()).add(mi);
		}

		List<String> allClassNames = new ArrayList<>(byClass.keySet());

		// Structural change analysis
		ChangedMembers changedMembers = null;
		List<StructuralDiff.FileDiff> structuralDiffs = null;
		String projectRootStr = getConfig(TestOrderConfig.PROJECT_ROOT);
		boolean structuralEnabled = getConfigBool(TestOrderConfig.STRUCTURAL_DIFF_ENABLED, true);
		if (structuralEnabled && projectRootStr != null && !projectRootStr.isEmpty()) {
			try {
				Path projectRoot = Path.of(projectRootStr);
				String changeMode = getConfig(TestOrderConfig.CHANGE_MODE);
				StructuralChangeAnalyzer.AnalysisResult analysis;
				if ("since-last-commit".equals(changeMode) || "SINCE_LAST_COMMIT".equals(changeMode)) {
					analysis = StructuralChangeAnalyzer.analyzeSinceLastCommitFull(projectRoot);
				} else {
					analysis = StructuralChangeAnalyzer.analyzeUncommittedFull(projectRoot);
				}
				changedMembers = analysis.changedMembers();
				structuralDiffs = analysis.diffs();
			} catch (IOException e) {
				TestOrderLogger.debug("[structural] Failed: {}", e.getMessage());
			}
		}

		// Change complexity
		Map<String, Double> changeComplexityMap = Map.of();
		if (!changedClasses.isEmpty() && projectRootStr != null && !projectRootStr.isEmpty()) {
			String srcRoot = getConfig(TestOrderConfig.SOURCE_ROOT);
			List<Path> sourceRoots = new ArrayList<>();
			if (srcRoot != null && !srcRoot.isBlank()) {
				sourceRoots.add(Path.of(srcRoot));
			} else {
				Path projectRoot = Path.of(projectRootStr);
				sourceRoots.add(projectRoot.resolve("src/main/java"));
				sourceRoots.add(projectRoot.resolve("src/main/kotlin"));
			}
			changeComplexityMap = ChangeComplexity.compute(changedClasses, sourceRoots, changedMembers,
					structuralDiffs);
		}
		if (changeComplexityMap.isEmpty()) {
			changeComplexityMap = ChangeComplexity.deserialise(getConfig(TestOrderConfig.CHANGE_COMPLEXITY));
		}

		// Score each class
		TestScorer scorer = new TestScorer.Builder(effectiveWeights, depMap, state, changedClasses, changedTestClasses)
				.testClassNames(allClassNames).changedMembers(changedMembers).changeComplexity(changeComplexityMap)
				.build();

		Map<String, Integer> classScores = new HashMap<>();
		for (String className : allClassNames) {
			TestScorer.ScoreResult result = scorer.score(className);
			classScores.put(className, result.score());

			// Record breakdown for run history
			if (statePath != null && !statePath.isEmpty()) {
				TestOrderState.recordBreakdown(className,
						new TestOrderState.ScoreBreakdown(result.score(), result.isNew(), result.isChanged(),
								result.depOverlap(), result.depTotal(), result.failScore(), result.isFast(),
								result.isSlow(), result.complexityOverlap(), result.speedRatio(),
								result.hasStaticFieldOverlap()));
			}

			if (debug) {
				TestOrderLogger.debug("[testng] {} score={} deps={} fail={} new={} changed={}", className,
						result.score(), result.depOverlap(), result.failScore(), result.isNew(), result.isChanged());
			}
		}

		// Order class groups by score descending, with Jaccard diversity tie-breaking
		List<String> orderedClasses = orderByScoreAndDiversity(allClassNames, classScores, depMap, state);

		// State path for run-quality tracking
		if (statePath != null && !statePath.isEmpty()) {
			TestOrderState.setStatePath(statePath);
		}

		// Method-level reordering within each class group
		boolean methodOrderingEnabled = getConfigBool(TestOrderConfig.METHOD_ORDER_ENABLED, false);
		Set<String> changedMethods = methodOrderingEnabled ? resolveChangedMethods() : Set.of();

		// Build result list
		List<IMethodInstance> result = new ArrayList<>(methods.size());
		for (String className : orderedClasses) {
			List<IMethodInstance> classMethods = byClass.get(className);
			if (classMethods == null)
				continue;

			if (methodOrderingEnabled) {
				classMethods = reorderMethods(classMethods, className, state, depMap, changedClasses, changedMethods);
			}
			result.addAll(classMethods);
		}

		if (debug) {
			TestOrderLogger.debug("[testng] Final order:");
			for (int i = 0; i < result.size(); i++) {
				IMethodInstance mi = result.get(i);
				TestOrderLogger.debug("  {}. {}#{}", i + 1, mi.getMethod().getRealClass().getSimpleName(),
						mi.getMethod().getMethodName());
			}
		}

		return result;
	}

	/**
	 * Orders class names by score descending, using Jaccard diversity as
	 * tie-breaker.
	 */
	private List<String> orderByScoreAndDiversity(List<String> classNames, Map<String, Integer> scores,
			DependencyMap depMap, TestOrderState state) {
		// Group by score
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
	 * Reorders methods within a single class group using {@link MethodScorer}.
	 */
	private List<IMethodInstance> reorderMethods(List<IMethodInstance> classMethods, String className,
			TestOrderState state, DependencyMap depMap, Set<String> changedClasses, Set<String> changedMethods) {
		TestOrderState.MethodScoringWeights mw = state.methodScoringWeights();
		double failureRecency = getConfigDouble("testorder.method.score.failureRecency", mw.failureRecency());
		double fast = getConfigDouble("testorder.method.score.fast", mw.fast());
		double slow = getConfigDouble("testorder.method.score.slow", mw.slow());
		double depOverlap = getConfigDouble("testorder.method.score.depOverlap", mw.depOverlap());
		double newMethod = getConfigDouble("testorder.method.score.newMethod", mw.newMethod());
		double changedMethod = getConfigDouble("testorder.method.score.changedMethod", mw.changedMethod());
		double coverageBonus = getConfigDouble("testorder.method.score.coverageBonus", mw.coverageBonus());
		TestOrderState.MethodScoringWeights weights = new TestOrderState.MethodScoringWeights(failureRecency, fast,
				slow, depOverlap, newMethod, changedMethod, coverageBonus);

		MethodScorer scorer = new MethodScorer(weights, state, depMap, changedClasses, changedMethods);

		// Build metadata — group by unique method name to handle DataProvider
		// duplicates
		Map<String, IMethodInstance> firstInstance = new LinkedHashMap<>();
		Map<String, List<IMethodInstance>> dataProviderGroups = new LinkedHashMap<>();
		for (IMethodInstance mi : classMethods) {
			String methodName = mi.getMethod().getMethodName();
			String key = className + "#" + methodName;
			if (!firstInstance.containsKey(key)) {
				firstInstance.put(key, mi);
			}
			dataProviderGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(mi);
		}

		List<MethodScorer.MethodMetadata> metadata = new ArrayList<>();
		for (var entry : firstInstance.entrySet()) {
			String[] parts = entry.getKey().split("#", 2);
			long duration = (long) state.getDurationMethod(parts[0], parts[1], -1.0);
			metadata.add(new MethodScorer.MethodMetadata(parts[0], parts[1], duration, null));
		}

		List<MethodScorer.MethodScoreResult> scored = scorer.score(metadata);

		// Sort by score descending
		scored.sort((a, b) -> Double.compare(b.score(), a.score()));

		// Build reordered list, expanding DataProvider groups
		List<IMethodInstance> reordered = new ArrayList<>(classMethods.size());
		for (MethodScorer.MethodScoreResult sr : scored) {
			String key = sr.className() + "#" + sr.methodName();
			List<IMethodInstance> group = dataProviderGroups.get(key);
			if (group != null) {
				reordered.addAll(group);
			}
		}
		return reordered;
	}

	// ── Config helpers ─────────────────────────────────────────────────

	private Set<String> resolveChangedClasses() {
		Set<String> result = new LinkedHashSet<>();
		String explicit = getConfig(TestOrderConfig.CHANGED_CLASSES);
		if (explicit != null && !explicit.isBlank()) {
			for (String cls : explicit.split(",")) {
				String trimmed = cls.trim();
				if (!trimmed.isEmpty())
					result.add(trimmed);
			}
		}
		String filePath = getConfig(TestOrderConfig.CHANGED_CLASSES_FILE);
		if (filePath != null && !filePath.isBlank()) {
			Path f = Path.of(filePath);
			if (Files.exists(f)) {
				try {
					Files.readAllLines(f).stream().map(String::trim).filter(s -> !s.isEmpty()).forEach(result::add);
				} catch (IOException e) {
					TestOrderLogger.error("Failed to read changed classes file: {}", e.getMessage());
				}
			}
		}
		return result;
	}

	private Set<String> resolveChangedTestClasses() {
		Set<String> result = new LinkedHashSet<>();
		String explicit = getConfig(TestOrderConfig.CHANGED_TEST_CLASSES);
		if (explicit != null && !explicit.isBlank()) {
			for (String cls : explicit.split(",")) {
				String trimmed = cls.trim();
				if (!trimmed.isEmpty())
					result.add(trimmed);
			}
		}
		return result;
	}

	private Set<String> resolveChangedMethods() {
		Set<String> result = new LinkedHashSet<>();
		String explicit = getConfig(TestOrderConfig.CHANGED_METHODS);
		if (explicit != null && !explicit.isBlank()) {
			for (String key : explicit.split(",")) {
				String trimmed = key.trim();
				if (!trimmed.isEmpty())
					result.add(trimmed);
			}
		}
		return result;
	}

	String getConfig(String key) {
		String val = System.getProperty(key);
		if (val != null)
			return val;
		if (configProps == null) {
			configProps = new Properties();
			try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_RESOURCE)) {
				if (is != null)
					configProps.load(is);
			} catch (IOException e) {
				TestOrderLogger.debug("Failed to load {}: {}", CONFIG_RESOURCE, e.getMessage());
			}
		}
		return configProps.getProperty(key);
	}

	private boolean getConfigBool(String key, boolean defaultValue) {
		String val = getConfig(key);
		if (val == null)
			return defaultValue;
		return "true".equalsIgnoreCase(val.trim());
	}

	private int getConfigInt(String key, int defaultValue) {
		String val = getConfig(key);
		if (val == null)
			return defaultValue;
		try {
			return Integer.parseInt(val.trim());
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	private double getConfigDouble(String key, double defaultValue) {
		String val = getConfig(key);
		if (val == null)
			return defaultValue;
		try {
			return Double.parseDouble(val.trim());
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}
}
