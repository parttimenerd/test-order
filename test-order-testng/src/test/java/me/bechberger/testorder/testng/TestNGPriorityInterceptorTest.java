package me.bechberger.testorder.testng;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testng.IMethodInstance;
import org.testng.ITestContext;
import org.testng.ITestNGMethod;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;

/**
 * Unit tests for {@link TestNGPriorityInterceptor}.
 */
class TestNGPriorityInterceptorTest {

	// Stub test classes used as ITestNGMethod.getRealClass() return values.
	// Their fully qualified names serve as dependency map keys.
	static class StubTestA {
	}
	static class StubTestB {
	}

	private static final String A = StubTestA.class.getName();
	private static final String B = StubTestB.class.getName();

	@TempDir
	Path tempDir;

	private Path indexFile;
	private Path stateFile;

	@BeforeEach
	void setUp() {
		indexFile = tempDir.resolve("test-dependencies.lz4");
		stateFile = tempDir.resolve("state.lz4");
	}

	@AfterEach
	void tearDown() {
		System.clearProperty("testorder.index.path");
		System.clearProperty("testorder.state.path");
		System.clearProperty("testorder.changed.classes");
		System.clearProperty("testorder.debug");
		System.clearProperty("testorder.methodOrder.enabled");
	}

	@Test
	void returnsUnmodifiedWhenNoIndexPath() {
		TestNGPriorityInterceptor interceptor = new TestNGPriorityInterceptor();
		List<IMethodInstance> methods = List.of(mockMethod(StubTestA.class, "test1"));
		List<IMethodInstance> result = interceptor.intercept(new ArrayList<>(methods), mock(ITestContext.class));
		assertEquals(1, result.size());
	}

	@Test
	void returnsUnmodifiedWhenIndexFileDoesNotExist() {
		System.setProperty("testorder.index.path", tempDir.resolve("nonexistent.lz4").toString());
		TestNGPriorityInterceptor interceptor = new TestNGPriorityInterceptor();
		List<IMethodInstance> methods = List.of(mockMethod(StubTestA.class, "test1"));
		List<IMethodInstance> result = interceptor.intercept(new ArrayList<>(methods), mock(ITestContext.class));
		assertEquals(1, result.size());
	}

	@Test
	void ordersClassesByDependencyOverlap() throws IOException {
		DependencyMap depMap = new DependencyMap();
		depMap.put(A, Set.of("com.example.ChangedClass", "com.example.Util"));
		depMap.put(B, Set.of("com.example.OtherClass"));
		depMap.save(indexFile);

		System.setProperty("testorder.index.path", indexFile.toString());
		System.setProperty("testorder.changed.classes", "com.example.ChangedClass");

		TestNGPriorityInterceptor interceptor = new TestNGPriorityInterceptor();

		List<IMethodInstance> methods = new ArrayList<>(
				List.of(mockMethod(StubTestB.class, "testB1"), mockMethod(StubTestA.class, "testA1")));

		List<IMethodInstance> result = interceptor.intercept(methods, mock(ITestContext.class));

		assertEquals(2, result.size());
		// A should come first (has dependency overlap with changed class)
		assertEquals(A, className(result.get(0)));
		assertEquals(B, className(result.get(1)));
	}

	@Test
	void maintainsMethodGroupingByClass() throws IOException {
		DependencyMap depMap = new DependencyMap();
		depMap.put(A, Set.of("com.example.Dep1"));
		depMap.put(B, Set.of("com.example.Dep2"));
		depMap.save(indexFile);

		System.setProperty("testorder.index.path", indexFile.toString());

		TestNGPriorityInterceptor interceptor = new TestNGPriorityInterceptor();

		List<IMethodInstance> methods = new ArrayList<>(
				List.of(mockMethod(StubTestA.class, "test1"), mockMethod(StubTestB.class, "test2"),
						mockMethod(StubTestA.class, "test3"), mockMethod(StubTestB.class, "test4")));

		List<IMethodInstance> result = interceptor.intercept(methods, mock(ITestContext.class));

		// Methods from same class should be grouped together
		String first = className(result.get(0));
		String second = className(result.get(1));
		assertEquals(first, second, "First two methods should be from the same class");

		String third = className(result.get(2));
		String fourth = className(result.get(3));
		assertEquals(third, fourth, "Last two methods should be from the same class");

		assertNotEquals(first, third, "The two groups should be different classes");
	}

	@Test
	void prioritizesFailedClasses() throws IOException {
		DependencyMap depMap = new DependencyMap();
		depMap.put(A, Set.of("com.example.Dep1"));
		depMap.put(B, Set.of("com.example.Dep2"));
		depMap.save(indexFile);

		TestOrderState state = new TestOrderState();
		state.recordFailure(B);
		state.save(stateFile);

		System.setProperty("testorder.index.path", indexFile.toString());
		System.setProperty("testorder.state.path", stateFile.toString());

		TestNGPriorityInterceptor interceptor = new TestNGPriorityInterceptor();

		List<IMethodInstance> methods = new ArrayList<>(
				List.of(mockMethod(StubTestA.class, "test1"), mockMethod(StubTestB.class, "test2")));

		List<IMethodInstance> result = interceptor.intercept(methods, mock(ITestContext.class));
		assertEquals(B, className(result.get(0)), "B should be first (has failure history)");
	}

	@Test
	void getConfigFallsBackToClasspathProperties() {
		TestNGPriorityInterceptor interceptor = new TestNGPriorityInterceptor();
		assertNull(interceptor.getConfig("testorder.nonexistent.key"));
	}

	@Test
	void handlesEmptyMethodList() throws IOException {
		DependencyMap depMap = new DependencyMap();
		depMap.save(indexFile);

		System.setProperty("testorder.index.path", indexFile.toString());

		TestNGPriorityInterceptor interceptor = new TestNGPriorityInterceptor();
		List<IMethodInstance> result = interceptor.intercept(new ArrayList<>(), mock(ITestContext.class));
		assertTrue(result.isEmpty());
	}

	@Test
	void handlesSingleClassMultipleMethods() throws IOException {
		DependencyMap depMap = new DependencyMap();
		depMap.put(A, Set.of("com.example.Dep1"));
		depMap.save(indexFile);

		System.setProperty("testorder.index.path", indexFile.toString());

		TestNGPriorityInterceptor interceptor = new TestNGPriorityInterceptor();

		List<IMethodInstance> methods = new ArrayList<>(List.of(mockMethod(StubTestA.class, "test1"),
				mockMethod(StubTestA.class, "test2"), mockMethod(StubTestA.class, "test3")));

		List<IMethodInstance> result = interceptor.intercept(methods, mock(ITestContext.class));
		assertEquals(3, result.size());
		for (IMethodInstance mi : result) {
			assertEquals(A, mi.getMethod().getRealClass().getName());
		}
	}

	// ── Helpers ────────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private IMethodInstance mockMethod(Class<?> realClass, String methodName) {
		ITestNGMethod testNGMethod = mock(ITestNGMethod.class);
		when(testNGMethod.getRealClass()).thenReturn((Class) realClass);
		when(testNGMethod.getMethodName()).thenReturn(methodName);

		IMethodInstance instance = mock(IMethodInstance.class);
		when(instance.getMethod()).thenReturn(testNGMethod);

		return instance;
	}

	private static String className(IMethodInstance mi) {
		return mi.getMethod().getRealClass().getName();
	}
}
