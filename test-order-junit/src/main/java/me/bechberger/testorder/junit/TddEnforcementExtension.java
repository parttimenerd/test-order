package me.bechberger.testorder.junit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import me.bechberger.testorder.TestOrderConfig;
import me.bechberger.testorder.TestOrderConfigResolver;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.changes.MethodHashStore;

/**
 * JUnit 5 extension that enforces TDD discipline: new test classes and methods
 * that pass without having failed first are artificially failed with a
 * descriptive error message.
 * <p>
 * Activated when system property {@code testorder.tdd} is {@code "true"}.
 * Skipped entirely when no state file exists (first run).
 * <p>
 * Auto-discovered via
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension} when
 * JUnit auto-detection is enabled.
 */
public class TddEnforcementExtension implements AfterTestExecutionCallback {

	private static final Logger LOG = Logger.getLogger(TddEnforcementExtension.class.getName());

	/** Lazy-loaded cached state; {@code null} means "not loaded yet". */
	private static volatile Object cachedState; // TestOrderState or Boolean.FALSE (= no state)
	private static volatile Boolean tddEnabled;
	/**
	 * Lazy-loaded set of {@code className#methodName} keys whose hash matches a
	 * deleted entry in the previous-run baseline — i.e. a renamed method. Empty set
	 * means "loaded, no renames detected"; {@code null} means "not loaded yet".
	 */
	private static volatile Set<String> renamedMethods;

	/**
	 * Tracks className#methodName keys that have already fired a TDD violation this
	 * JVM run. Prevents duplicate violations for @ParameterizedTest where each
	 * invocation shares the same method name.
	 */
	private static final java.util.Set<String> alreadyViolated = java.util.Collections
			.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

	private static final Object LOCK = new Object();

	@Override
	public void afterTestExecution(ExtensionContext context) throws Exception {
		if (!isTddEnabled()) {
			return;
		}

		// If the test already failed, TDD discipline is satisfied
		if (context.getExecutionException().isPresent()) {
			return;
		}

		TestOrderState state = loadStateLazy();
		if (state == null) {
			// No state file → first run, skip enforcement
			return;
		}

		String className = context.getRequiredTestClass().getName();
		String methodName = context.getRequiredTestMethod().getName();
		String topLevel = TestOrderConfigResolver.toTopLevelClassName(className);

		Map<String, Long> classDurations = state.getClassDurations();

		if (!classDurations.containsKey(className) && !classDurations.containsKey(topLevel)) {
			throw new AssertionError(
					formatViolation("New test CLASS passed without failing first", className, methodName));
		}

		// If the class is nested and not directly known to state (only topLevel is),
		// the nested class itself is new — fire a class-level violation rather than
		// falling back to the outer class's method map (which would mask a new inner
		// class whose methods happen to share names with the outer class).
		if (!className.equals(topLevel) && !classDurations.containsKey(className)) {
			throw new AssertionError(
					formatViolation("New test CLASS passed without failing first", className, methodName));
		}

		// Only enforce method-level if the state actually tracks method durations
		// for this class. Older state files (or agent-only runs) may have
		// class-level data but no per-method data — flagging every method in that
		// case would be a false positive.
		// Note: we do NOT fall back to topLevel's method map here, as a nested class
		// that IS in state should be tracked independently from its outer class.
		Map<String, Double> methodsForClass = state.getMethodDurations().get(className);
		if (methodsForClass != null && !methodsForClass.containsKey(methodName)) {
			String methodKey = className + "#" + methodName;
			// Method body unchanged but name differs from baseline → it's a rename, not
			// a brand-new test. Suppress the violation.
			if (loadRenamedMethodsLazy().contains(methodKey)) {
				LOG.fine("[test-order] TDD: " + methodKey + " is a renamed method, skipping enforcement");
				return;
			}
			// For @ParameterizedTest: only fire once per method per run to avoid N
			// identical violations
			if (!alreadyViolated.add(methodKey)) {
				return;
			}
			throw new AssertionError(
					formatViolation("New test METHOD passed without failing first", className, methodName));
		}
	}

	private static boolean isTddEnabled() {
		Boolean cached = tddEnabled;
		if (cached != null) {
			return cached;
		}
		synchronized (LOCK) {
			cached = tddEnabled;
			if (cached != null) {
				return cached;
			}
			TestOrderConfigResolver resolver = new TestOrderConfigResolver(
					TddEnforcementExtension.class.getClassLoader());
			boolean enabled = resolver.getConfigBool(TestOrderConfig.TDD, false);
			tddEnabled = enabled;
			return enabled;
		}
	}

	private static TestOrderState loadStateLazy() {
		Object s = cachedState;
		if (s != null) {
			return s instanceof TestOrderState ? (TestOrderState) s : null;
		}
		synchronized (LOCK) {
			s = cachedState;
			if (s != null) {
				return s instanceof TestOrderState ? (TestOrderState) s : null;
			}

			TestOrderConfigResolver resolver = new TestOrderConfigResolver(
					TddEnforcementExtension.class.getClassLoader());
			String statePath = resolver.getConfig(TestOrderConfig.STATE_PATH);
			if (statePath == null || statePath.isBlank()) {
				LOG.fine("[test-order] TDD mode: no state path configured, skipping enforcement");
				cachedState = Boolean.FALSE;
				return null;
			}

			Path path = Path.of(statePath);
			if (!Files.exists(path)) {
				LOG.info("[test-order] TDD mode: state file not found (" + path
						+ "), skipping enforcement (first run?)");
				cachedState = Boolean.FALSE;
				return null;
			}

			try {
				TestOrderState loaded = TestOrderState.load(path);
				cachedState = loaded;
				LOG.info("[test-order] TDD mode: loaded state with " + loaded.getClassDurations().size()
						+ " known test classes");
				return loaded;
			} catch (IOException e) {
				LOG.log(Level.WARNING, "[test-order] TDD mode: failed to load state file, skipping enforcement", e);
				cachedState = Boolean.FALSE;
				return null;
			}
		}
	}

	private static String formatViolation(String reason, String className, String methodName) {
		String testId = className + "#" + methodName;
		return "\n" + "═══════════════════════════════════════════════════════════════\n" + "  TDD VIOLATION: " + reason
				+ "\n" + "  Test: " + testId + "\n" + "\n" + "  In TDD, write the test first, see it FAIL,\n"
				+ "  then implement the code to make it pass.\n"
				+ "═══════════════════════════════════════════════════════════════";
	}

	/**
	 * Loads {@code method-hashes.lz4} and {@code method-hashes.lz4.baseline} (when
	 * present, sibling to {@code state.lz4}) and computes the set of method keys
	 * that look like renames: the body hash is unchanged but the name moved within
	 * the same class. Returns an empty set when the files are missing or the load
	 * fails — rename detection is best-effort, never blocking.
	 */
	private static Set<String> loadRenamedMethodsLazy() {
		Set<String> cached = renamedMethods;
		if (cached != null) {
			return cached;
		}
		synchronized (LOCK) {
			cached = renamedMethods;
			if (cached != null) {
				return cached;
			}
			renamedMethods = computeRenamedMethods();
			return renamedMethods;
		}
	}

	private static Set<String> computeRenamedMethods() {
		TestOrderConfigResolver resolver = new TestOrderConfigResolver(TddEnforcementExtension.class.getClassLoader());
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
	static Set<String> detectRenames(MethodHashStore current, MethodHashStore baseline) {
		Map<String, String> currentHashes = current.getHashes();
		Map<String, String> baselineHashes = baseline.getHashes();
		// Index baseline by (class, hash) → set<key>, but only for keys that
		// disappeared in current. Those are the rename source candidates.
		Map<String, Set<String>> deletedByClassHash = new HashMap<>();
		for (var entry : baselineHashes.entrySet()) {
			String key = entry.getKey();
			if (currentHashes.containsKey(key)) {
				continue; // method still exists under the same name
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
		// Now scan current for keys that are NEW (not in baseline) but whose
		// (class, hash) matches a deleted baseline entry. Those are renames.
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

	/** Reset cached state for testing. */
	public static void resetForTesting() {
		cachedState = null;
		tddEnabled = null;
		renamedMethods = null;
		alreadyViolated.clear();
	}
}
