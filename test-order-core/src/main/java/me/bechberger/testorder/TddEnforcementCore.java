package me.bechberger.testorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import me.bechberger.testorder.changes.MethodHashStore;

/**
 * Shared TDD enforcement logic used by both the JUnit 5 and TestNG
 * integrations.
 * <p>
 * Callers invoke {@link #checkAfterTestPassed(String, String, ClassLoader)}
 * after each test method completes successfully. The method returns a non-null
 * violation message when TDD discipline is violated — callers are responsible
 * for turning that into a test failure (e.g. throwing {@code AssertionError} or
 * failing the TestNG result).
 * <p>
 * All state (loaded {@link TestOrderState}, rename detection, enabled flag) is
 * cached statically and survives Surefire reruns within the same JVM — the
 * state file does not change mid-run, so reusing the snapshot is correct and
 * avoids repeated I/O.
 */
public final class TddEnforcementCore {

	private static final Logger LOG = Logger.getLogger(TddEnforcementCore.class.getName());

	private record CachedState(TestOrderState state) {
	}

	private static volatile CachedState cachedState;
	private static volatile Boolean tddEnabled;
	private static volatile Set<String> renamedMethods;

	/**
	 * Tracks {@code className#methodName} keys that have already fired a TDD
	 * violation this JVM run. Prevents duplicate violations for parameterized tests
	 * where each invocation shares the same method name.
	 */
	private static final Set<String> alreadyViolated = java.util.Collections
			.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

	private static final Object LOCK = new Object();

	private TddEnforcementCore() {
	}

	/**
	 * Checks whether a test that just passed violates TDD discipline.
	 *
	 * @param className
	 *            fully qualified test class name
	 * @param methodName
	 *            test method name
	 * @param classLoader
	 *            class loader used to resolve config and state
	 * @return a violation message string, or {@code null} if no violation
	 */
	public static String checkAfterTestPassed(String className, String methodName, ClassLoader classLoader) {
		if (!isTddEnabled(classLoader)) {
			return null;
		}

		TestOrderState state = loadStateLazy(classLoader);
		if (state == null) {
			return null;
		}

		String topLevel = TestOrderConfigResolver.toTopLevelClassName(className);
		Map<String, Long> classDurations = state.getClassDurations();

		if (!classDurations.containsKey(className) && !classDurations.containsKey(topLevel)) {
			if (!isLikelyRenamedClass(className, state)) {
				return formatViolation("New test CLASS passed without failing first", className, methodName);
			}
			// Rename detected — fall through to method-level check
		}

		// Nested class not directly in state (only top-level is) → new inner class
		if (!className.equals(topLevel) && !classDurations.containsKey(className)) {
			return formatViolation("New test CLASS passed without failing first", className, methodName);
		}

		Map<String, Double> methodsForClass = state.getMethodDurations().get(className);
		if (methodsForClass != null && !methodsForClass.containsKey(methodName)) {
			String methodKey = className + "#" + methodName;
			if (loadRenamedMethodsLazy(classLoader).contains(methodKey)) {
				LOG.fine("[test-order] TDD: " + methodKey + " is a renamed method, skipping enforcement");
				return null;
			}
			if (!alreadyViolated.add(methodKey)) {
				return null; // already fired for this parameterized method
			}
			return formatViolation("New test METHOD passed without failing first", className, methodName);
		}
		return null;
	}

	public static boolean isTddEnabled(ClassLoader classLoader) {
		Boolean cached = tddEnabled;
		if (cached != null) {
			return cached;
		}
		synchronized (LOCK) {
			cached = tddEnabled;
			if (cached != null) {
				return cached;
			}
			TestOrderConfigResolver resolver = new TestOrderConfigResolver(classLoader);
			boolean enabled = resolver.getConfigBool(TestOrderConfig.TDD, false);
			tddEnabled = enabled;
			return enabled;
		}
	}

	public static TestOrderState loadStateLazy(ClassLoader classLoader) {
		CachedState s = cachedState;
		if (s != null) {
			return s.state();
		}
		synchronized (LOCK) {
			s = cachedState;
			if (s != null) {
				return s.state();
			}

			TestOrderConfigResolver resolver = new TestOrderConfigResolver(classLoader);
			String statePath = resolver.getConfig(TestOrderConfig.STATE_PATH);
			if (statePath == null || statePath.isBlank()) {
				LOG.fine("[test-order] TDD mode: no state path configured, skipping enforcement");
				cachedState = new CachedState(null);
				return null;
			}

			Path path = Path.of(statePath);
			if (!Files.exists(path)) {
				LOG.info("[test-order] TDD mode: state file not found (" + path + "), skipping (first run?)");
				cachedState = new CachedState(null);
				return null;
			}

			try {
				TestOrderState loaded = TestOrderState.load(path);
				cachedState = new CachedState(loaded);
				LOG.info("[test-order] TDD mode: loaded state with " + loaded.getClassDurations().size()
						+ " known test classes");
				return loaded;
			} catch (IOException e) {
				LOG.log(Level.WARNING, "[test-order] TDD mode: failed to load state file, skipping enforcement", e);
				cachedState = new CachedState(null);
				return null;
			}
		}
	}

	static boolean isLikelyRenamedClass(String newClassName, TestOrderState state) {
		Class<?> newClass;
		try {
			newClass = Class.forName(newClassName);
		} catch (ClassNotFoundException | Error e) {
			return false;
		}
		Set<String> newMethodNames = new HashSet<>();
		for (java.lang.reflect.Method m : newClass.getDeclaredMethods()) {
			if (!m.isSynthetic()) {
				newMethodNames.add(m.getName());
			}
		}
		if (newMethodNames.isEmpty()) {
			return false;
		}

		for (Map.Entry<String, Map<String, Double>> entry : state.getMethodDurations().entrySet()) {
			String knownClass = entry.getKey();
			if (knownClass.equals(newClassName)) {
				continue;
			}
			Set<String> knownMethodNames = entry.getValue().keySet();
			if (!knownMethodNames.isEmpty() && knownMethodNames.equals(newMethodNames)) {
				LOG.info("[test-order] TDD mode: class '" + newClassName + "' appears to be a rename of '" + knownClass
						+ "' — applying TDD enforcement");
				return true;
			}
		}
		return false;
	}

	static Set<String> loadRenamedMethodsLazy(ClassLoader classLoader) {
		Set<String> cached = renamedMethods;
		if (cached != null) {
			return cached;
		}
		synchronized (LOCK) {
			cached = renamedMethods;
			if (cached != null) {
				return cached;
			}
			renamedMethods = computeRenamedMethods(classLoader);
			return renamedMethods;
		}
	}

	private static Set<String> computeRenamedMethods(ClassLoader classLoader) {
		TestOrderConfigResolver resolver = new TestOrderConfigResolver(classLoader);
		String statePath = resolver.getConfig(TestOrderConfig.STATE_PATH);
		if (statePath == null || statePath.isBlank()) {
			return Set.of();
		}
		Path stateDir = Path.of(statePath).getParent();
		if (stateDir == null) {
			return Set.of();
		}
		Path currentFile = stateDir.resolve("method-hashes.lz4");
		Path baselineFile = stateDir.resolve("method-hashes.lz4.baseline");
		if (!Files.exists(currentFile) || !Files.exists(baselineFile)) {
			return Set.of();
		}
		try {
			MethodHashStore current = MethodHashStore.load(currentFile);
			MethodHashStore baseline = MethodHashStore.load(baselineFile);
			return detectRenames(current, baseline);
		} catch (IOException e) {
			LOG.log(Level.FINE, "[test-order] TDD: rename detection unavailable (load failed)", e);
			return Set.of();
		}
	}

	/**
	 * Returns the {@code className#methodName} keys in {@code current} that are
	 * renames of methods that disappeared from {@code baseline}: same class, same
	 * body hash, name no longer present.
	 */
	public static Set<String> detectRenames(MethodHashStore current, MethodHashStore baseline) {
		Map<String, String> currentHashes = current.getHashes();
		Map<String, String> baselineHashes = baseline.getHashes();
		Map<String, Set<String>> deletedByClassHash = new HashMap<>();
		for (var entry : baselineHashes.entrySet()) {
			String key = entry.getKey();
			if (currentHashes.containsKey(key)) {
				continue;
			}
			int hash = key.indexOf('#');
			if (hash <= 0) {
				continue;
			}
			String className = key.substring(0, hash);
			String classHashKey = className + "\0" + entry.getValue();
			deletedByClassHash.computeIfAbsent(classHashKey, k -> new HashSet<>()).add(key);
		}
		if (deletedByClassHash.isEmpty()) {
			return Set.of();
		}
		Set<String> renames = new HashSet<>();
		for (var entry : currentHashes.entrySet()) {
			String key = entry.getKey();
			if (baselineHashes.containsKey(key)) {
				continue;
			}
			int hash = key.indexOf('#');
			if (hash <= 0) {
				continue;
			}
			String className = key.substring(0, hash);
			String classHashKey = className + "\0" + entry.getValue();
			if (deletedByClassHash.containsKey(classHashKey)) {
				renames.add(key);
			}
		}
		return renames;
	}

	public static String formatViolation(String reason, String className, String methodName) {
		String testId = className + "#" + methodName;
		return "\n" + "═══════════════════════════════════════════════════════════════\n" + "  TDD VIOLATION: " + reason
				+ "\n" + "  Test: " + testId + "\n" + "\n" + "  In TDD, write the test first, see it FAIL,\n"
				+ "  then implement the code to make it pass.\n"
				+ "═══════════════════════════════════════════════════════════════";
	}

	/** Reset all cached state — for testing only. */
	public static void resetForTesting() {
		cachedState = null;
		tddEnabled = null;
		renamedMethods = null;
		alreadyViolated.clear();
	}
}
