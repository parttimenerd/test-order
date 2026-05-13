package me.bechberger.testorder.testng;

import java.util.*;

import org.testng.IMethodInstance;
import org.testng.IMethodInterceptor;
import org.testng.ITestContext;

import me.bechberger.testorder.*;

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

	private TestOrderConfigResolver config;

	String getConfig(String key) {
		return getResolver().getConfig(key);
	}

	@Override
	public List<IMethodInstance> intercept(List<IMethodInstance> methods, ITestContext context) {
		TestOrderConfigResolver config = getResolver();

		ClassOrderingEngine.SetupResult s = ClassOrderingEngine.setup(config);
		if (s == null) {
			return methods;
		}

		// Group methods by declaring class
		Map<String, List<IMethodInstance>> byClass = new LinkedHashMap<>();
		for (IMethodInstance mi : methods) {
			String className = mi.getMethod().getRealClass().getName();
			byClass.computeIfAbsent(className, k -> new ArrayList<>()).add(mi);
		}

		List<String> allClassNames = new ArrayList<>(byClass.keySet());

		// Score each class
		TestScorer scorer = ClassOrderingEngine.buildScorer(s, allClassNames);

		Map<String, Integer> classScores = new HashMap<>();
		for (String className : allClassNames) {
			TestScorer.ScoreResult result = scorer.score(className);
			classScores.put(className, result.score());
			ClassOrderingEngine.recordBreakdown(s.statePath(), className, result);

			if (s.debug()) {
				TestOrderLogger.debug("[testng] {} score={} deps={} fail={} new={} changed={}", className,
						result.score(), result.depOverlap(), result.failScore(), result.isNew(), result.isChanged());
			}
		}

		// Separate @AlwaysRun classes — pin them first
		List<String> alwaysRunClasses = new ArrayList<>();
		List<String> normalClasses = new ArrayList<>(allClassNames);
		for (String className : allClassNames) {
			List<IMethodInstance> group = byClass.get(className);
			if (group != null && !group.isEmpty()) {
				Class<?> realClass = group.get(0).getMethod().getRealClass();
				if (realClass.isAnnotationPresent(me.bechberger.testorder.annotations.AlwaysRun.class)) {
					alwaysRunClasses.add(className);
				}
			}
		}
		normalClasses.removeAll(alwaysRunClasses);

		// Order remaining class groups by score descending, with Jaccard diversity
		// tie-breaking
		List<String> orderedClasses = ClassOrderingEngine.orderByScoreAndDiversity(normalClasses, classScores,
				s.depMap(), s.state());
		// Sort @AlwaysRun classes by score descending, then prepend
		alwaysRunClasses
				.sort((a, b) -> Integer.compare(classScores.getOrDefault(b, 0), classScores.getOrDefault(a, 0)));
		orderedClasses.addAll(0, alwaysRunClasses);

		// State path for run-quality tracking
		if (s.statePath() != null && !s.statePath().isEmpty()) {
			TestOrderState.setStatePath(s.statePath());
		}

		// Method-level reordering within each class group
		boolean methodOrderingEnabled = config.getConfigBool(TestOrderConfig.METHOD_ORDER_ENABLED, false);
		Set<String> changedMethods = methodOrderingEnabled ? config.resolveChangedMethods() : Set.of();

		// Build result list
		List<IMethodInstance> result = new ArrayList<>(methods.size());
		for (String className : orderedClasses) {
			List<IMethodInstance> classMethods = byClass.get(className);
			if (classMethods == null)
				continue;

			if (methodOrderingEnabled) {
				classMethods = reorderMethods(classMethods, className, s.state(), s.depMap(), s.changedClasses(),
						changedMethods);
			}
			result.addAll(classMethods);
		}

		if (s.debug()) {
			TestOrderLogger.debug("[testng] Final order:");
			for (int i = 0; i < result.size(); i++) {
				IMethodInstance mi = result.get(i);
				TestOrderLogger.debug("  {}. {}#{}", i + 1, mi.getMethod().getRealClass().getSimpleName(),
						mi.getMethod().getMethodName());
			}
		}

		return result;
	}

	private TestOrderConfigResolver getResolver() {
		if (config == null) {
			config = new TestOrderConfigResolver(getClass().getClassLoader());
		}
		return config;
	}

	/**
	 * Reorders methods within a single class group using
	 * {@link MethodOrderingEngine}.
	 * <p>
	 * Preserves {@code @Test(dependsOnMethods=...)} and
	 * {@code @Test(dependsOnGroups=...)} constraints: if the reordered list would
	 * place a method before one of its dependencies, the dependent method is moved
	 * after all of its dependencies.
	 */
	private List<IMethodInstance> reorderMethods(List<IMethodInstance> classMethods, String className,
			TestOrderState state, DependencyMap depMap, Set<String> changedClasses, Set<String> changedMethods) {
		TestOrderState.MethodScoringWeights weights = config.resolveMethodWeights(state);

		// Build metadata — group by unique method name to handle DataProvider
		// duplicates
		Map<String, List<IMethodInstance>> dataProviderGroups = new LinkedHashMap<>();
		List<String> uniqueMethodNames = new ArrayList<>();
		for (IMethodInstance mi : classMethods) {
			String methodName = mi.getMethod().getMethodName();
			String key = className + "#" + methodName;
			if (!dataProviderGroups.containsKey(key)) {
				uniqueMethodNames.add(methodName);
			}
			dataProviderGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(mi);
		}

		// Collect TestNG dependency constraints (dependsOnMethods/dependsOnGroups)
		// Map: methodName → set of method names it depends on
		Map<String, Set<String>> methodDeps = new HashMap<>();
		// Map: groupName → set of method names in that group
		Map<String, Set<String>> groupMembers = new HashMap<>();
		for (IMethodInstance mi : classMethods) {
			String methodName = mi.getMethod().getMethodName();
			String[] groups = mi.getMethod().getGroups();
			if (groups != null) {
				for (String g : groups) {
					groupMembers.computeIfAbsent(g, k -> new HashSet<>()).add(methodName);
				}
			}
		}
		for (IMethodInstance mi : classMethods) {
			String methodName = mi.getMethod().getMethodName();
			String[] depMethods = mi.getMethod().getMethodsDependedUpon();
			String[] depGroups = mi.getMethod().getGroupsDependedUpon();
			Set<String> deps = new HashSet<>();
			if (depMethods != null) {
				for (String dm : depMethods) {
					// TestNG returns FQN "pkg.Class.method" — extract simple method name
					int dot = dm.lastIndexOf('.');
					deps.add(dot >= 0 ? dm.substring(dot + 1) : dm);
				}
			}
			if (depGroups != null) {
				for (String dg : depGroups) {
					Set<String> members = groupMembers.get(dg);
					if (members != null) {
						deps.addAll(members);
					}
				}
			}
			deps.remove(methodName); // remove self
			if (!deps.isEmpty()) {
				methodDeps.put(methodName, deps);
			}
		}

		MethodOrderingEngine.ClassMethodOrder order = MethodOrderingEngine.orderMethods(className, uniqueMethodNames,
				state, depMap, changedClasses, changedMethods, weights);

		// Build scored method list, then enforce dependency constraints
		List<String> scoredNames = new ArrayList<>();
		for (MethodOrderingEngine.OrderedMethod om : order.methods()) {
			scoredNames.add(om.methodName());
		}
		if (!methodDeps.isEmpty()) {
			enforceDependencyOrder(scoredNames, methodDeps);
		}

		// Build reordered list, expanding DataProvider groups
		List<IMethodInstance> reordered = new ArrayList<>(classMethods.size());
		for (String methodName : scoredNames) {
			String key = className + "#" + methodName;
			List<IMethodInstance> group = dataProviderGroups.get(key);
			if (group != null) {
				reordered.addAll(group);
			}
		}
		return reordered;
	}

	/**
	 * Enforces dependency ordering: if method B depends on method A, ensure A
	 * appears before B in the list. Modifies {@code order} in-place.
	 */
	private static void enforceDependencyOrder(List<String> order, Map<String, Set<String>> methodDeps) {
		// Simple iterative fixup: scan left-to-right, if a method appears before
		// one of its dependencies, move it after the latest dependency.
		// Repeat until stable (max N passes for N methods).
		boolean changed = true;
		int maxPasses = order.size();
		while (changed && maxPasses-- > 0) {
			changed = false;
			Map<String, Integer> posMap = new HashMap<>();
			for (int i = 0; i < order.size(); i++) {
				posMap.put(order.get(i), i);
			}
			for (int i = 0; i < order.size(); i++) {
				String method = order.get(i);
				Set<String> deps = methodDeps.get(method);
				if (deps == null)
					continue;
				int latestDepPos = -1;
				for (String dep : deps) {
					Integer depPos = posMap.get(dep);
					if (depPos != null && depPos > i) {
						latestDepPos = Math.max(latestDepPos, depPos);
					}
				}
				if (latestDepPos > i) {
					// Move method to just after its latest dependency
					order.remove(i);
					order.add(latestDepPos, method);
					changed = true;
					break; // restart scan
				}
			}
		}
	}
}
