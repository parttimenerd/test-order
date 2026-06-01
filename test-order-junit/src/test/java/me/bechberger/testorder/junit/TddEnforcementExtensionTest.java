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
	private String origBuildId;
	private String origPendingRunsDir;

	@BeforeEach
	void setUp() {
		TddEnforcementExtension.resetForTesting();
		origTdd = System.getProperty(TestOrderConfig.TDD);
		origStatePath = System.getProperty(TestOrderConfig.STATE_PATH);
		origBuildId = System.getProperty("testorder.build.id");
		origPendingRunsDir = System.getProperty("testorder.pending.runs.dir");
		// Isolate from testorder-config.properties (written by mvn test-order:auto)
		System.setProperty("testorder.build.id", "");
		System.setProperty("testorder.pending.runs.dir", "");
	}

	@AfterEach
	void tearDown() {
		restoreProp(TestOrderConfig.TDD, origTdd);
		restoreProp(TestOrderConfig.STATE_PATH, origStatePath);
		restoreProp("testorder.build.id", origBuildId);
		restoreProp("testorder.pending.runs.dir", origPendingRunsDir);
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
		// Set blank rather than clear — clearing falls back to
		// testorder-config.properties
		// which may have a state path set (e.g. when running under mvn
		// test-order:auto).
		System.setProperty(TestOrderConfig.STATE_PATH, "");
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

		// Suppress expected WARNING + stack trace from corrupt-file error path
		java.util.logging.Logger tddLog = java.util.logging.Logger.getLogger(TddEnforcementExtension.class.getName());
		java.util.logging.Level prev = tddLog.getLevel();
		tddLog.setLevel(java.util.logging.Level.SEVERE);
		try {
			TddEnforcementExtension ext = new TddEnforcementExtension();
			// Should not throw — gracefully skips enforcement on unreadable state
			ext.afterTestExecution(fakeContext(NewTestClass.class, "testSomething", Optional.empty()));
		} finally {
			tddLog.setLevel(prev);
		}
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

	// ── Nested-class TDD enforcement (Bug fix verification) ──────────────────

	/**
	 * topLevel (TddEnforcementExtensionTest) is in state with a method named
	 * testSomething. OuterKnown.InnerNew is NOT in state. Even though topLevel is
	 * known, running a test on InnerNew should fire a CLASS violation because
	 * InnerNew itself is new.
	 */
	@Test
	void newNestedClassInKnownOuter_throws_evenWhenOuterHasSameMethodName() throws Exception {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		// Register the topLevel class (toTopLevelClassName strips everything after
		// first $)
		// with the same method name that InnerNew also has
		String topLevelName = me.bechberger.testorder.TestOrderConfigResolver
				.toTopLevelClassName(OuterKnown.InnerNew.class.getName());
		state.recordDuration(topLevelName, 100);
		state.recordMethodDuration(topLevelName, "testSomething", 50);
		setupState(state);

		TddEnforcementExtension ext = new TddEnforcementExtension();
		// InnerNew is NOT in state — must fire CLASS violation despite topLevel having
		// testSomething
		AssertionError error = assertThrows(AssertionError.class, () -> ext
				.afterTestExecution(fakeContext(OuterKnown.InnerNew.class, "testSomething", Optional.empty())));
		assertTrue(error.getMessage().contains("TDD VIOLATION"), error.getMessage());
		assertTrue(error.getMessage().contains("New test CLASS"), error.getMessage());
		assertTrue(error.getMessage().contains(OuterKnown.InnerNew.class.getName()), error.getMessage());
	}

	/** Inner class that IS in state should still pass normally. */
	@Test
	void knownNestedClassInKnownOuter_passesForKnownMethod() throws Exception {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		String topLevelName = me.bechberger.testorder.TestOrderConfigResolver
				.toTopLevelClassName(OuterKnown.InnerNew.class.getName());
		state.recordDuration(topLevelName, 100);
		state.recordDuration(OuterKnown.InnerNew.class.getName(), 80);
		state.recordMethodDuration(OuterKnown.InnerNew.class.getName(), "testSomething", 40);
		setupState(state);

		TddEnforcementExtension ext = new TddEnforcementExtension();
		// InnerNew IS in state — no violation
		ext.afterTestExecution(fakeContext(OuterKnown.InnerNew.class, "testSomething", Optional.empty()));
	}

	/**
	 * Inner class in state but with a NEW method should still fire METHOD
	 * violation.
	 */
	@Test
	void knownNestedClassInKnownOuter_throwsForNewMethod() throws Exception {
		System.setProperty(TestOrderConfig.TDD, "true");
		TestOrderState state = new TestOrderState();
		String topLevelName = me.bechberger.testorder.TestOrderConfigResolver
				.toTopLevelClassName(OuterKnown.InnerNew.class.getName());
		state.recordDuration(topLevelName, 100);
		state.recordDuration(OuterKnown.InnerNew.class.getName(), 80);
		state.recordMethodDuration(OuterKnown.InnerNew.class.getName(), "testSomething", 40);
		setupState(state);

		TddEnforcementExtension ext = new TddEnforcementExtension();
		// testNewInner is NOT in state for InnerNew — should fire METHOD violation
		AssertionError error = assertThrows(AssertionError.class,
				() -> ext.afterTestExecution(fakeContext(OuterKnown.InnerNew.class, "testNewInner", Optional.empty())));
		assertTrue(error.getMessage().contains("New test METHOD"), error.getMessage());
		assertTrue(error.getMessage().contains("testNewInner"), error.getMessage());
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

	static class OuterKnown {
		void testSomething() {
		}

		static class InnerNew {
			void testSomething() {
			}
			void testNewInner() {
			}
		}
	}
}
