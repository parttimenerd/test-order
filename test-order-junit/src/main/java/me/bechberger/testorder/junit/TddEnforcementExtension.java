package me.bechberger.testorder.junit;

import java.util.Set;
import java.util.logging.Logger;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import me.bechberger.testorder.TddEnforcementCore;
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
 * <p>
 * All enforcement logic lives in {@link TddEnforcementCore} and is shared with
 * the TestNG integration.
 */
public class TddEnforcementExtension implements AfterTestExecutionCallback {

	private static final Logger LOG = Logger.getLogger(TddEnforcementExtension.class.getName());

	@Override
	public void afterTestExecution(ExtensionContext context) throws Exception {
		// If the test already failed, TDD discipline is satisfied
		if (context.getExecutionException().isPresent()) {
			return;
		}

		String className = context.getRequiredTestClass().getName();
		String methodName = context.getRequiredTestMethod().getName();
		ClassLoader classLoader = context.getRequiredTestClass().getClassLoader();

		String violation = TddEnforcementCore.checkAfterTestPassed(className, methodName, classLoader);
		if (violation != null) {
			throw new AssertionError(violation);
		}
	}

	/**
	 * Delegates to {@link TddEnforcementCore#detectRenames} — kept here for
	 * backward compatibility with tests in this module.
	 */
	static Set<String> detectRenames(MethodHashStore current, MethodHashStore baseline) {
		return TddEnforcementCore.detectRenames(current, baseline);
	}

	/** Reset cached state for testing. */
	public static void resetForTesting() {
		TddEnforcementCore.resetForTesting();
	}
}
