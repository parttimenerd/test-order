package me.bechberger.testorder.testng;

import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestResult;

import me.bechberger.testorder.TddEnforcementCore;

/**
 * TestNG listener that enforces TDD discipline: new test classes and methods
 * that pass without having failed first are artificially failed with a
 * descriptive error message.
 * <p>
 * Activated when system property {@code testorder.tdd} is {@code "true"}.
 * Skipped entirely when no state file exists (first run).
 * <p>
 * Auto-discovered via {@code META-INF/services/org.testng.ITestNGListener}.
 * <p>
 * All enforcement logic lives in {@link TddEnforcementCore} and is shared with
 * the JUnit 5 integration.
 */
public class TestNGTddEnforcementListener implements IInvokedMethodListener {

	@Override
	public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {
		// nothing to do before
	}

	@Override
	public void afterInvocation(IInvokedMethod method, ITestResult testResult) {
		// Only enforce on test methods (not configuration methods like @BeforeMethod)
		if (!method.isTestMethod()) {
			return;
		}
		// Only check tests that passed — failures satisfy TDD discipline
		if (testResult.getStatus() != ITestResult.SUCCESS) {
			return;
		}

		String className = testResult.getTestClass().getRealClass().getName();
		String methodName = testResult.getMethod().getMethodName();
		ClassLoader classLoader = testResult.getTestClass().getRealClass().getClassLoader();

		String violation = TddEnforcementCore.checkAfterTestPassed(className, methodName, classLoader);
		if (violation != null) {
			testResult.setStatus(ITestResult.FAILURE);
			testResult.setThrowable(new AssertionError(violation));
		}
	}
}
