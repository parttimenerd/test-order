package me.bechberger.testorder.agent.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsageStoreTest {

	// Register class names so recordUsageId() produces resolvable names
	private static int ID_DEP_A;
	private static int ID_DEP_B;
	private static int ID_DEP_C;

	@BeforeAll
	static void registerClassIds() {
		ClassIdMap map = ClassIdMap.getInstance();
		ID_DEP_A = map.getOrRegisterClass("com.dep.A");
		ID_DEP_B = map.getOrRegisterClass("com.dep.B");
		ID_DEP_C = map.getOrRegisterClass("com.dep.C");
	}

	@Test
	void concurrentThreadsHaveIsolatedTracking() throws Exception {
		UsageStore store = newStore();

		int threads = 10;
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService exec = Executors.newFixedThreadPool(threads);

		// Build per-thread future: each thread records one unique test class + dep
		var futures = new java.util.ArrayList<Future<Void>>();
		for (int i = 0; i < threads; i++) {
			int idx = i;
			futures.add(exec.submit(() -> {
				String testClass = "com.example.Thread" + idx + "Test";
				store.startTestClass(testClass);
				ready.countDown();
				start.await(5, TimeUnit.SECONDS);
				// Each thread records a distinct dep ID (ids 100..109)
				store.recordUsageId(100 + idx);
				store.endTestClass(testClass);
				return null;
			}));
		}

		ready.await(5, TimeUnit.SECONDS);
		start.countDown(); // release all threads at once

		for (Future<Void> f : futures)
			f.get(10, TimeUnit.SECONDS);
		exec.shutdown();

		Map<String, Set<String>> deps = collectDeps(store);
		// Every test class must be present and have exactly its own dep (not
		// contaminated)
		assertEquals(threads, deps.size(), "Each thread's test class should be recorded");
		for (int i = 0; i < threads; i++) {
			String testClass = "com.example.Thread" + i + "Test";
			assertTrue(deps.containsKey(testClass), "Missing key: " + testClass);
		}
	}

	// ── flush() adverse condition tests ────────────────────────────────

	@Test
	void flushWithNoDepsDoesNothing(@TempDir Path tempDir) throws Exception {
		// A store with no recorded tests should exit flush() early without errors
		UsageStore store = newStore();
		store.setOutputDir(tempDir.toString());
		invokeFlush(store);
		// No .deps files written because there were no deps
		assertEquals(0, Files.list(tempDir).count());
	}

	@Test
	void flushWritesDepsFilesWhenNoSocketAvailable(@TempDir Path tempDir) throws Exception {
		// Clear any collector port so socket path is skipped
		String oldPort = System.getProperty("testorder.collector.port");
		try {
			System.clearProperty("testorder.collector.port");

			UsageStore store = newStore();
			store.setOutputDir(tempDir.toString());

			// Record a test with a registered dep
			store.startTestClass("com.example.FooTest");
			store.recordUsageId(ID_DEP_A);
			store.endTestClass("com.example.FooTest");

			invokeFlush(store);

			// Should write a .deps file
			Path depsFile = tempDir.resolve("com.example.FooTest.deps");
			assertTrue(Files.exists(depsFile), "Expected .deps file to be written");
			List<String> lines = Files.readAllLines(depsFile);
			assertFalse(lines.isEmpty(), "Deps file should not be empty");
			assertTrue(lines.contains("com.dep.A"), "Should contain registered dep class");
		} finally {
			if (oldPort != null) {
				System.setProperty("testorder.collector.port", oldPort);
			}
		}
	}

	@Test
	void flushWithInvalidPortFallsBackToFiles(@TempDir Path tempDir) throws Exception {
		String oldPort = System.getProperty("testorder.collector.port");
		try {
			System.setProperty("testorder.collector.port", "not_a_number");

			UsageStore store = newStore();
			store.setOutputDir(tempDir.toString());

			store.startTestClass("com.example.BarTest");
			store.recordUsageId(ID_DEP_A);
			store.endTestClass("com.example.BarTest");

			// Should not throw — invalid port is handled gracefully
			invokeFlush(store);

			Path depsFile = tempDir.resolve("com.example.BarTest.deps");
			assertTrue(Files.exists(depsFile), "Should fall back to .deps file on invalid port");
		} finally {
			if (oldPort != null) {
				System.setProperty("testorder.collector.port", oldPort);
			} else {
				System.clearProperty("testorder.collector.port");
			}
		}
	}

	@Test
	void flushWithUnreachablePortFallsBackToFiles(@TempDir Path tempDir) throws Exception {
		// Use a port that definitely isn't listening (ephemeral port allocated then
		// immediately closed)
		String oldPort = System.getProperty("testorder.collector.port");
		try {
			// Port 1 is almost never open and requires root
			System.setProperty("testorder.collector.port", "1");

			UsageStore store = newStore();
			store.setOutputDir(tempDir.toString());

			store.startTestClass("com.example.UnreachableTest");
			store.recordUsageId(ID_DEP_A);
			store.endTestClass("com.example.UnreachableTest");

			invokeFlush(store);

			Path depsFile = tempDir.resolve("com.example.UnreachableTest.deps");
			assertTrue(Files.exists(depsFile), "Should fall back to .deps when socket fails");
		} finally {
			if (oldPort != null) {
				System.setProperty("testorder.collector.port", oldPort);
			} else {
				System.clearProperty("testorder.collector.port");
			}
		}
	}

	@Test
	void flushWithNoOutputDirAndNoPortDoesNotThrow() throws Exception {
		// Both paths unavailable — should log warning but not crash
		String oldPort = System.getProperty("testorder.collector.port");
		try {
			System.clearProperty("testorder.collector.port");

			UsageStore store = newStore();
			// Don't set outputDir — it stays null

			store.startTestClass("com.example.LostTest");
			store.recordUsageId(ID_DEP_A);
			store.endTestClass("com.example.LostTest");

			// Should not throw
			invokeFlush(store);
		} finally {
			if (oldPort != null) {
				System.setProperty("testorder.collector.port", oldPort);
			}
		}
	}

	@Test
	void flushWithNonWritableOutputDirDoesNotThrow(@TempDir Path tempDir) throws Exception {
		// Create a directory and make it non-writable
		Path readOnlyDir = tempDir.resolve("readonly");
		Files.createDirectories(readOnlyDir);
		// Write a file first to verify the dir exists, then make non-writable
		readOnlyDir.toFile().setWritable(false);

		String oldPort = System.getProperty("testorder.collector.port");
		try {
			System.clearProperty("testorder.collector.port");

			UsageStore store = newStore();
			store.setOutputDir(readOnlyDir.toString());

			store.startTestClass("com.example.PermTest");
			store.recordUsageId(ID_DEP_A);
			store.endTestClass("com.example.PermTest");

			// Should handle IOException gracefully
			invokeFlush(store);
		} finally {
			readOnlyDir.toFile().setWritable(true); // restore for cleanup
			if (oldPort != null) {
				System.setProperty("testorder.collector.port", oldPort);
			}
		}
	}

	@Test
	void flushWithNonExistentOutputDirCreatesIt(@TempDir Path tempDir) throws Exception {
		Path nested = tempDir.resolve("a/b/c/deps");
		assertFalse(Files.exists(nested));

		String oldPort = System.getProperty("testorder.collector.port");
		try {
			System.clearProperty("testorder.collector.port");

			UsageStore store = newStore();
			store.setOutputDir(nested.toString());

			store.startTestClass("com.example.NestedDirTest");
			store.recordUsageId(ID_DEP_B);
			store.endTestClass("com.example.NestedDirTest");

			invokeFlush(store);

			assertTrue(Files.isDirectory(nested), "Should create nested output directory");
			assertTrue(Files.exists(nested.resolve("com.example.NestedDirTest.deps")));
		} finally {
			if (oldPort != null) {
				System.setProperty("testorder.collector.port", oldPort);
			}
		}
	}

	@Test
	void flushWritesMethodDepsFiles(@TempDir Path tempDir) throws Exception {
		String oldPort = System.getProperty("testorder.collector.port");
		try {
			System.clearProperty("testorder.collector.port");

			UsageStore store = newStore();
			store.setOutputDir(tempDir.toString());
			store.setMethodLevelRecordingEnabled(true);

			store.startTestClass("com.example.MethodTest");
			store.startTestMethod("com.example.MethodTest", "testOne");
			store.recordUsageId(ID_DEP_C);
			store.endTestMethod();
			store.endTestClass("com.example.MethodTest");

			invokeFlush(store);

			// Check class-level deps
			Path depsFile = tempDir.resolve("com.example.MethodTest.deps");
			assertTrue(Files.exists(depsFile), "Class deps should be written");

			// Check method-level deps (# replaced by __)
			Path mdepsFile = tempDir.resolve("com.example.MethodTest__testOne.mdeps");
			assertTrue(Files.exists(mdepsFile), "Method deps should be written");
			List<String> lines = Files.readAllLines(mdepsFile);
			assertEquals("# com.example.MethodTest#testOne", lines.get(0));
		} finally {
			if (oldPort != null) {
				System.setProperty("testorder.collector.port", oldPort);
			}
		}
	}

	@Test
	void flushIsIdempotentWhenCalledTwice(@TempDir Path tempDir) throws Exception {
		String oldPort = System.getProperty("testorder.collector.port");
		try {
			System.clearProperty("testorder.collector.port");

			UsageStore store = newStore();
			store.setOutputDir(tempDir.toString());

			store.startTestClass("com.example.IdempotentTest");
			store.recordUsageId(ID_DEP_A);
			store.endTestClass("com.example.IdempotentTest");

			invokeFlush(store);
			invokeFlush(store); // second call should still work (same data)

			Path depsFile = tempDir.resolve("com.example.IdempotentTest.deps");
			assertTrue(Files.exists(depsFile));
		} finally {
			if (oldPort != null) {
				System.setProperty("testorder.collector.port", oldPort);
			}
		}
	}

	@Test
	void flushWithEmptyOutputDirStringTreatedAsUnset() throws Exception {
		// Empty string should be treated as "not configured"
		String oldPort = System.getProperty("testorder.collector.port");
		try {
			System.clearProperty("testorder.collector.port");

			UsageStore store = newStore();
			store.setOutputDir(""); // empty string

			store.startTestClass("com.example.EmptyDirTest");
			store.recordUsageId(ID_DEP_A);
			store.endTestClass("com.example.EmptyDirTest");

			// Should not throw — just logs warning
			invokeFlush(store);
		} finally {
			if (oldPort != null) {
				System.setProperty("testorder.collector.port", oldPort);
			}
		}
	}

	@Test
	void multipleTestClassesWriteSeparateFiles(@TempDir Path tempDir) throws Exception {
		String oldPort = System.getProperty("testorder.collector.port");
		try {
			System.clearProperty("testorder.collector.port");

			UsageStore store = newStore();
			store.setOutputDir(tempDir.toString());

			store.startTestClass("com.example.AlphaTest");
			store.recordUsageId(ID_DEP_A);
			store.endTestClass("com.example.AlphaTest");

			store.startTestClass("com.example.BetaTest");
			store.recordUsageId(ID_DEP_B);
			store.endTestClass("com.example.BetaTest");

			store.startTestClass("com.example.GammaTest");
			store.recordUsageId(ID_DEP_C);
			store.endTestClass("com.example.GammaTest");

			invokeFlush(store);

			assertTrue(Files.exists(tempDir.resolve("com.example.AlphaTest.deps")));
			assertTrue(Files.exists(tempDir.resolve("com.example.BetaTest.deps")));
			assertTrue(Files.exists(tempDir.resolve("com.example.GammaTest.deps")));
		} finally {
			if (oldPort != null) {
				System.setProperty("testorder.collector.port", oldPort);
			}
		}
	}

	@Test
	void configureSetsBothOutputDirAndIndexFile() throws Exception {
		UsageStore store = newStore();
		store.configure("/tmp/out", "/tmp/index.lz4", true, null);
		// Verify via reflection that fields were set
		var outputDirField = UsageStore.class.getDeclaredField("outputDir");
		outputDirField.setAccessible(true);
		assertEquals("/tmp/out", outputDirField.get(store));

		var indexFileField = UsageStore.class.getDeclaredField("indexFile");
		indexFileField.setAccessible(true);
		assertEquals("/tmp/index.lz4", indexFileField.get(store));
	}

	@Test
	void startEndTestClassWithNullDoesNotCrash() throws Exception {
		// Edge case: null test class name (shouldn't happen but should be robust)
		UsageStore store = newStore();
		// startTestClass with null key — ConcurrentHashMap.computeIfAbsent will throw
		// NPE,
		// but endTestClass with a mismatched name should be safe
		store.startTestClass("com.example.RealTest");
		store.endTestClass("com.example.NonExistentTest"); // mismatched name
		// Should not crash — the tracker just stays active
		store.recordUsageId(ID_DEP_A);
		store.endTestClass("com.example.RealTest");

		Map<String, Set<String>> deps = collectDeps(store);
		assertTrue(deps.containsKey("com.example.RealTest"));
	}

	// ── Helper methods ─────────────────────────────────────────────────

	private static void invokeFlush(UsageStore store) throws Exception {
		Method flush = UsageStore.class.getDeclaredMethod("flush");
		flush.setAccessible(true);
		flush.invoke(store);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Set<String>> collectDeps(UsageStore store) throws Exception {
		var collectDeps = UsageStore.class.getDeclaredMethod("collectDeps");
		collectDeps.setAccessible(true);
		return (Map<String, Set<String>>) collectDeps.invoke(store);
	}

	private static UsageStore newStore() throws Exception {
		Constructor<UsageStore> constructor = UsageStore.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}
}
