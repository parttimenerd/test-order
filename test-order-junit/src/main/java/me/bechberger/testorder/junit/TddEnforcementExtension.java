package me.bechberger.testorder.junit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import me.bechberger.testorder.TestOrderConfig;
import me.bechberger.testorder.TestOrderConfigResolver;
import me.bechberger.testorder.TestOrderState;

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
			// For @ParameterizedTest: only fire once per method per run to avoid N
			// identical violations
			String methodKey = className + "#" + methodName;
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

	/** Reset cached state for testing. */
	public static void resetForTesting() {
		cachedState = null;
		tddEnabled = null;
		alreadyViolated.clear();
	}
}
