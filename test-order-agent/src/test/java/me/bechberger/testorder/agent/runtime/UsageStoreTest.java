package me.bechberger.testorder.agent.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UsageStoreTest {

    @Test
    void recordsTestClassesPerThread() throws Exception {
        UsageStore store = newStore();
        int alphaId = ClassIdMap.getInstance().getOrRegisterClass("com.example.AlphaDependency");
        int betaId = ClassIdMap.getInstance().getOrRegisterClass("com.example.BetaDependency");

        var executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> alpha = executor.submit(() -> runClassRecording(store, "com.example.AlphaTest", alphaId, ready, start));
            Future<?> beta = executor.submit(() -> runClassRecording(store, "com.example.BetaTest", betaId, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            alpha.get(5, TimeUnit.SECONDS);
            beta.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(Set.of("com.example.AlphaDependency"), collectDeps(store).get("com.example.AlphaTest"));
        assertEquals(Set.of("com.example.BetaDependency"), collectDeps(store).get("com.example.BetaTest"));
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

        for (Future<Void> f : futures) f.get(10, TimeUnit.SECONDS);
        exec.shutdown();

        Map<String, Set<String>> deps = collectDeps(store);
        // Every test class must be present and have exactly its own dep (not contaminated)
        assertEquals(threads, deps.size(), "Each thread's test class should be recorded");
        for (int i = 0; i < threads; i++) {
            String testClass = "com.example.Thread" + i + "Test";
            assertTrue(deps.containsKey(testClass), "Missing key: " + testClass);
        }
    }

    private static void runClassRecording(UsageStore store, String testClass, int depId,
                                          CountDownLatch ready, CountDownLatch start) {
        try {
            store.startTestClass(testClass);
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            store.recordUsageId(depId);
            store.endTestClass(testClass);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Set<String>> collectDeps(UsageStore store) throws Exception {
        var collectDeps = UsageStore.class.getDeclaredMethod("collectDeps");
        collectDeps.setAccessible(true);
        return (java.util.Map<String, Set<String>>) collectDeps.invoke(store);
    }

    private static UsageStore newStore() throws Exception {
        Constructor<UsageStore> constructor = UsageStore.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}
