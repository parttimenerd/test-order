package me.bechberger.testorder.junit;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.TestOrderConfig;
import me.bechberger.testorder.TestOrderState;

/**
 * Unit tests for {@link TddEnforcementExtension}.
 */
@Timeout(5)
class TddEnforcementExtensionTest {

	@TempDir
	Path tempDir;

	private String origTdd;
	private String origStatePath;

	@BeforeEach
	void setUp() {
		TddEnforcementExtension.resetForTesting();
		origTdd = System.getProperty(TestOrderConfig.TDD);
		origStatePath = System.getProperty(TestOrderConfig.STATE_PATH);
	}

	@AfterEach
	void tearDown() {
		restoreProp(TestOrderConfig.TDD, origTdd);
		restoreProp(TestOrderConfig.STATE_PATH, origStatePath);
		TddEnforcementExtension.resetForTesting();
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

	@Test
	void skipsWhenTddDisabled() throws Exception {
		System.setProperty(TestOrderConfig.TDD, "false");
		TddEnforcementExtension ext = new TddEnforcementExtension();
		ext.afterTestExecution(fakeContext(KnownTestA.class, "testOne", Optional.empty()));
	}

	@Test
	void skipsWhenNoStateFile() throws Exception {
		System.setProperty(TestOrderConfig.TDD, "true");
		System.setProperty(TestOrderConfig.STATE_PATH, tempDir.resolve("nonexistent.lz4").toString());
		TddEnforcementExtension ext = new TddEnforcementExtension();
		ext.afterTestExecution(fakeContext(KnownTestA.class, "testOne", Optional.empty()));
	}

	@Test
	void skipsWhenNoStatePathConfigured() throws Exception {
		System.setProperty(TestOrderConfig.TDD, "true");
		System.clearProperty(TestOrderConfig.STATE_PATH);
		TddEnforcementExtension ext = new TddEnforcementExtension();
		ext.afterTestExecution(fakeContext(KnownTestA.class, "testOne", Optional.empty()));
	}

	@Test
	void skipsWhenTestAlreadyFailed() throws Exception {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		setupState(state);

		TddEnforcementExtension ext = new TddEnforcementExtension();
		ext.afterTestExecution(
				fakeContext(NewTestClass.class, "testSomething", Optional.of(new RuntimeException("genuine failure"))));
	}

	@Test
	void throwsForNewClass() throws Exception {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		setupState(state);

		TddEnforcementExtension ext = new TddEnforcementExtension();
		AssertionError error = assertThrows(AssertionError.class,
				() -> ext.afterTestExecution(fakeContext(NewTestClass.class, "testSomething", Optional.empty())));
		assertTrue(error.getMessage().contains("TDD VIOLATION"), error.getMessage());
		assertTrue(error.getMessage().contains("New test CLASS"), error.getMessage());
		assertTrue(error.getMessage().contains(NewTestClass.class.getName()), error.getMessage());
	}

	@Test
	void throwsForNewMethodInKnownClass() throws Exception {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		state.recordMethodDuration(KnownTestA.class.getName(), "testOne", 50);
		setupState(state);

		TddEnforcementExtension ext = new TddEnforcementExtension();
		AssertionError error = assertThrows(AssertionError.class,
				() -> ext.afterTestExecution(fakeContext(KnownTestA.class, "testTwo", Optional.empty())));
		assertTrue(error.getMessage().contains("TDD VIOLATION"), error.getMessage());
		assertTrue(error.getMessage().contains("New test METHOD"), error.getMessage());
		assertTrue(error.getMessage().contains("testTwo"), error.getMessage());
	}

	@Test
	void passesForKnownClassAndMethod() throws Exception {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		state.recordMethodDuration(KnownTestA.class.getName(), "testOne", 50);
		setupState(state);

		TddEnforcementExtension ext = new TddEnforcementExtension();
		ext.afterTestExecution(fakeContext(KnownTestA.class, "testOne", Optional.empty()));
	}

	@Test
	void passesForKnownClassWithoutMethodData() throws Exception {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		setupState(state);

		TddEnforcementExtension ext = new TddEnforcementExtension();
		ext.afterTestExecution(fakeContext(KnownTestA.class, "anyMethod", Optional.empty()));
	}

	@Test
	void multipleCallsShareCachedState() throws Exception {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		state.recordMethodDuration(KnownTestA.class.getName(), "testOne", 50);
		Path stateFile = setupState(state);

		TddEnforcementExtension ext = new TddEnforcementExtension();
		// First call: loads state
		ext.afterTestExecution(fakeContext(KnownTestA.class, "testOne", Optional.empty()));

		// Delete the state file — subsequent calls should still work from cache
		java.nio.file.Files.delete(stateFile);

		TddEnforcementExtension ext2 = new TddEnforcementExtension();
		// Second call: uses cached state even though file is gone
		ext2.afterTestExecution(fakeContext(KnownTestA.class, "testOne", Optional.empty()));
	}

	@Test
	void errorMessageContainsBoxFormatting() throws Exception {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		setupState(state);

		TddEnforcementExtension ext = new TddEnforcementExtension();
		AssertionError error = assertThrows(AssertionError.class,
				() -> ext.afterTestExecution(fakeContext(NewTestClass.class, "testSomething", Optional.empty())));
		String msg = error.getMessage();
		// Verify box formatting
		assertTrue(msg.contains("═══"), "should contain box border");
		assertTrue(msg.contains("In TDD, write the test first"), "should contain guidance");
		assertTrue(msg.contains("see it FAIL"), "should contain TDD step");
	}

	@Test
	void skipsWhenStateFileCorrupt() throws Exception {
		System.setProperty(TestOrderConfig.TDD, "true");
		Path corrupt = tempDir.resolve("state.lz4");
		java.nio.file.Files.write(corrupt, new byte[]{0, 1, 2, 3, 4});
		System.setProperty(TestOrderConfig.STATE_PATH, corrupt.toString());

		TddEnforcementExtension ext = new TddEnforcementExtension();
		// Should not throw — gracefully skips enforcement on unreadable state
		ext.afterTestExecution(fakeContext(NewTestClass.class, "testSomething", Optional.empty()));
	}

	@Test
	void knownMethodInClassWithMethodData_doesNotThrow() throws Exception {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		state.recordMethodDuration(KnownTestA.class.getName(), "testOne", 50);
		state.recordMethodDuration(KnownTestA.class.getName(), "testTwo", 75);
		setupState(state);

		TddEnforcementExtension ext = new TddEnforcementExtension();
		// Both known methods should pass
		ext.afterTestExecution(fakeContext(KnownTestA.class, "testOne", Optional.empty()));
		ext.afterTestExecution(fakeContext(KnownTestA.class, "testTwo", Optional.empty()));
	}

	@Test
	void newMethodInClassWithMultipleKnownMethods_throws() throws Exception {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		state.recordDuration(KnownTestA.class.getName(), 100);
		state.recordMethodDuration(KnownTestA.class.getName(), "testOne", 50);
		state.recordMethodDuration(KnownTestA.class.getName(), "testTwo", 75);
		setupState(state);

		TddEnforcementExtension ext = new TddEnforcementExtension();
		// anyMethod is NOT in state — should throw
		AssertionError error = assertThrows(AssertionError.class,
				() -> ext.afterTestExecution(fakeContext(KnownTestA.class, "anyMethod", Optional.empty())));
		assertTrue(error.getMessage().contains("New test METHOD"), error.getMessage());
	}

	private ExtensionContext fakeContext(Class<?> testClass, String methodName, Optional<Throwable> executionException)
			throws NoSuchMethodException {
		return proxyContext(testClass, testClass.getDeclaredMethod(methodName), executionException);
	}

	private ExtensionContext proxyContext(Class<?> testClass, Method testMethod,
			Optional<Throwable> executionException) {
		InvocationHandler handler = (proxy, method, args) -> {
			switch (method.getName()) {
				case "getExecutionException" :
					return executionException;
				case "getRequiredTestClass" :
					return testClass;
				case "getRequiredTestMethod" :
					return testMethod;
				case "getTestClass" :
					return Optional.of(testClass);
				case "getTestMethod" :
					return Optional.of(testMethod);
				case "getParent" :
					return Optional.empty();
				case "getDisplayName" :
					return testClass.getName() + "#" + testMethod.getName();
				case "getUniqueId" :
					return "fake-context";
				case "getTags" :
					return java.util.Set.of();
				default :
					throw new UnsupportedOperationException("Unexpected method: " + method.getName());
			}
		};
		return (ExtensionContext) Proxy.newProxyInstance(ExtensionContext.class.getClassLoader(),
				new Class<?>[]{ExtensionContext.class}, handler);
	}

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
}
