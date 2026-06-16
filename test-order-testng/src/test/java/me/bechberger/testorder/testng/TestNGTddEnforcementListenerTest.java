package me.bechberger.testorder.testng;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testng.IInvokedMethod;
import org.testng.ITestClass;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;

import me.bechberger.testorder.TddEnforcementCore;
import me.bechberger.testorder.TestOrderConfig;
import me.bechberger.testorder.TestOrderState;

/**
 * Unit tests for {@link TestNGTddEnforcementListener}.
 */
@Timeout(5)
class TestNGTddEnforcementListenerTest {

	@TempDir
	Path tempDir;

	private String origTdd;
	private String origStatePath;

	@BeforeEach
	void setUp() {
		TddEnforcementCore.resetForTesting();
		origTdd = System.getProperty(TestOrderConfig.TDD);
		origStatePath = System.getProperty(TestOrderConfig.STATE_PATH);
	}

	@AfterEach
	void tearDown() {
		restoreProp(TestOrderConfig.TDD, origTdd);
		restoreProp(TestOrderConfig.STATE_PATH, origStatePath);
		TddEnforcementCore.resetForTesting();
	}

	private void restoreProp(String key, String value) {
		if (value == null)
			System.clearProperty(key);
		else
			System.setProperty(key, value);
	}

	private Path setupState(TestOrderState state) throws IOException {
		Path stateFile = tempDir.resolve("state.lz4");
		state.save(stateFile);
		System.setProperty(TestOrderConfig.STATE_PATH, stateFile.toString());
		return stateFile;
	}

	// ── Builder for faking ITestResult ─────────────────────────────────────────

	private ITestResult fakeResult(Class<?> testClass, String methodName, int status) {
		ITestClass iTestClass = mock(ITestClass.class);
		when(iTestClass.getRealClass()).thenReturn((Class) testClass);

		ITestNGMethod method = mock(ITestNGMethod.class);
		when(method.getMethodName()).thenReturn(methodName);

		ITestResult result = mock(ITestResult.class);
		when(result.getTestClass()).thenReturn(iTestClass);
		when(result.getMethod()).thenReturn(method);
		when(result.getStatus()).thenReturn(status);
		return result;
	}

	private IInvokedMethod fakeInvokedMethod(boolean isTestMethod) {
		IInvokedMethod m = mock(IInvokedMethod.class);
		when(m.isTestMethod()).thenReturn(isTestMethod);
		return m;
	}

	// ── Tests ──────────────────────────────────────────────────────────────────

	@Test
	void skipsWhenTddDisabled() {
		System.setProperty(TestOrderConfig.TDD, "false");
		TestNGTddEnforcementListener listener = new TestNGTddEnforcementListener();
		ITestResult result = fakeResult(KnownTestA.class, "testOne", ITestResult.SUCCESS);
		listener.afterInvocation(fakeInvokedMethod(true), result);
		verify(result, never()).setStatus(ITestResult.FAILURE);
	}

	@Test
	void skipsWhenNoStateFile() {
		System.setProperty(TestOrderConfig.TDD, "true");
		System.setProperty(TestOrderConfig.STATE_PATH, tempDir.resolve("nonexistent.lz4").toString());
		TestNGTddEnforcementListener listener = new TestNGTddEnforcementListener();
		ITestResult result = fakeResult(KnownTestA.class, "testOne", ITestResult.SUCCESS);
		listener.afterInvocation(fakeInvokedMethod(true), result);
		verify(result, never()).setStatus(ITestResult.FAILURE);
	}

	@Test
	void skipsWhenNoStatePathConfigured() {
		System.setProperty(TestOrderConfig.TDD, "true");
		System.setProperty(TestOrderConfig.STATE_PATH, "");
		TestNGTddEnforcementListener listener = new TestNGTddEnforcementListener();
		ITestResult result = fakeResult(KnownTestA.class, "testOne", ITestResult.SUCCESS);
		listener.afterInvocation(fakeInvokedMethod(true), result);
		verify(result, never()).setStatus(ITestResult.FAILURE);
	}

	@Test
	void skipsWhenTestAlreadyFailed() throws IOException {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		setupState(state);

		TestNGTddEnforcementListener listener = new TestNGTddEnforcementListener();
		// Status is FAILURE, not SUCCESS — should not be re-examined
		ITestResult result = fakeResult(NewTestClass.class, "testSomething", ITestResult.FAILURE);
		listener.afterInvocation(fakeInvokedMethod(true), result);
		verify(result, never()).setStatus(ITestResult.FAILURE);
	}

	@Test
	void skipsConfigurationMethods() throws IOException {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		setupState(state);

		TestNGTddEnforcementListener listener = new TestNGTddEnforcementListener();
		ITestResult result = fakeResult(NewTestClass.class, "testSomething", ITestResult.SUCCESS);
		// isTestMethod=false → @BeforeMethod/@AfterMethod/etc — must not be enforced
		listener.afterInvocation(fakeInvokedMethod(false), result);
		verify(result, never()).setStatus(ITestResult.FAILURE);
	}

	@Test
	void failsForNewClass() throws IOException {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		setupState(state);

		TestNGTddEnforcementListener listener = new TestNGTddEnforcementListener();
		ITestResult result = fakeResult(NewTestClass.class, "testSomething", ITestResult.SUCCESS);
		listener.afterInvocation(fakeInvokedMethod(true), result);

		verify(result).setStatus(ITestResult.FAILURE);
		verify(result).setThrowable(argThat(t -> t instanceof AssertionError
				&& t.getMessage().contains("New test CLASS") && t.getMessage().contains("TDD VIOLATION")));
	}

	@Test
	void failsForNewMethodInKnownClass() throws IOException {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		state.recordMethodDuration(KnownTestA.class.getName(), "testOne", 50);
		setupState(state);

		TestNGTddEnforcementListener listener = new TestNGTddEnforcementListener();
		ITestResult result = fakeResult(KnownTestA.class, "testTwo", ITestResult.SUCCESS);
		listener.afterInvocation(fakeInvokedMethod(true), result);

		verify(result).setStatus(ITestResult.FAILURE);
		verify(result).setThrowable(argThat(t -> t instanceof AssertionError
				&& t.getMessage().contains("New test METHOD") && t.getMessage().contains("testTwo")));
	}

	@Test
	void passesForKnownClassAndMethod() throws IOException {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		state.recordMethodDuration(KnownTestA.class.getName(), "testOne", 50);
		setupState(state);

		TestNGTddEnforcementListener listener = new TestNGTddEnforcementListener();
		ITestResult result = fakeResult(KnownTestA.class, "testOne", ITestResult.SUCCESS);
		listener.afterInvocation(fakeInvokedMethod(true), result);
		verify(result, never()).setStatus(ITestResult.FAILURE);
	}

	@Test
	void passesForKnownClassWithoutMethodData() throws IOException {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		setupState(state);

		TestNGTddEnforcementListener listener = new TestNGTddEnforcementListener();
		ITestResult result = fakeResult(KnownTestA.class, "anyMethod", ITestResult.SUCCESS);
		listener.afterInvocation(fakeInvokedMethod(true), result);
		verify(result, never()).setStatus(ITestResult.FAILURE);
	}

	@Test
	void violationMessageContainsBoxFormatting() throws IOException {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		setupState(state);

		TestNGTddEnforcementListener listener = new TestNGTddEnforcementListener();
		ITestResult result = fakeResult(NewTestClass.class, "testSomething", ITestResult.SUCCESS);
		listener.afterInvocation(fakeInvokedMethod(true), result);

		verify(result).setThrowable(argThat(t -> t instanceof AssertionError && t.getMessage().contains("═══")
				&& t.getMessage().contains("In TDD, write the test first")));
	}

	@Test
	void deduplicatesRepeatedInvocationsOfSameMethod() throws IOException {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		state.recordMethodDuration(KnownTestA.class.getName(), "testOne", 50);
		setupState(state);

		TestNGTddEnforcementListener listener = new TestNGTddEnforcementListener();

		// First invocation of the new method (testTwo) — fires a violation
		ITestResult first = fakeResult(KnownTestA.class, "testTwo", ITestResult.SUCCESS);
		listener.afterInvocation(fakeInvokedMethod(true), first);
		verify(first).setStatus(ITestResult.FAILURE);

		// Second invocation (DataProvider repeat) — already violated, must not fire
		// again
		ITestResult second = fakeResult(KnownTestA.class, "testTwo", ITestResult.SUCCESS);
		listener.afterInvocation(fakeInvokedMethod(true), second);
		verify(second, never()).setStatus(ITestResult.FAILURE);
	}

	@Test
	void renamedClass_withSameMethodSet_stillFiresViolation() throws IOException {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(OldName.class.getName(), 100);
		state.recordMethodDuration(OldName.class.getName(), "testSomething", 50);
		setupState(state);

		TestNGTddEnforcementListener listener = new TestNGTddEnforcementListener();
		ITestResult result = fakeResult(RenamedFromOld.class, "testSomething", ITestResult.SUCCESS);
		listener.afterInvocation(fakeInvokedMethod(true), result);

		verify(result).setStatus(ITestResult.FAILURE);
		verify(result)
				.setThrowable(argThat(t -> t instanceof AssertionError && t.getMessage().contains("TDD VIOLATION")));
	}

	@Test
	void multipleCallsShareCachedState() throws IOException {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		state.recordMethodDuration(KnownTestA.class.getName(), "testOne", 50);
		Path stateFile = setupState(state);

		TestNGTddEnforcementListener listener = new TestNGTddEnforcementListener();

		ITestResult first = fakeResult(KnownTestA.class, "testOne", ITestResult.SUCCESS);
		listener.afterInvocation(fakeInvokedMethod(true), first);
		verify(first, never()).setStatus(ITestResult.FAILURE);

		// Delete state file — second call should use cached state
		java.nio.file.Files.delete(stateFile);

		ITestResult second = fakeResult(KnownTestA.class, "testOne", ITestResult.SUCCESS);
		listener.afterInvocation(fakeInvokedMethod(true), second);
		verify(second, never()).setStatus(ITestResult.FAILURE);
	}

	// ── Stub test classes ─────────────────────────────────────────────────────

	static class KnownTestA {
		void testOne() {
		}
		void testTwo() {
		}
		void anyMethod() {
		}
	}

	static class NewTestClass {
		void testSomething() {
		}
	}

	static class OldName {
		void testSomething() {
		}
	}

	static class RenamedFromOld {
		void testSomething() {
		}
	}
}
