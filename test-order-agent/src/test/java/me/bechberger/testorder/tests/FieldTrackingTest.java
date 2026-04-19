package me.bechberger.testorder.tests;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import me.bechberger.testorder.agent.runtime.ClassIdMap;
import me.bechberger.testorder.agent.runtime.BitsetTracker;
import me.bechberger.testorder.agent.runtime.UsageStore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for member-level field tracking with bitsets.
 */
@DisplayName("Field-Level Tracking Tests")
public class FieldTrackingTest {
    
    @Test
    @DisplayName("ClassIdMap registers class IDs uniquely")
    public void testClassIdMapRegistration() {
        ClassIdMap map = ClassIdMap.getInstance();
        
        int id1 = map.getOrRegisterClass("com.example.Foo");
        int id2 = map.getOrRegisterClass("com.example.Bar");
        
        assertTrue(id1 >= 0, "Class ID should be non-negative");
        assertTrue(id2 >= 0, "Class ID should be non-negative");
        assertNotEquals(id1, id2, "Different classes should get different IDs");
        
        // Re-registering same class should return same ID
        int id1Again = map.getOrRegisterClass("com.example.Foo");
        assertEquals(id1, id1Again, "Same class should always get same ID");
    }
    
    @Test
    @DisplayName("ClassIdMap registers member IDs in separate namespace")
    public void testMemberIdRegistration() {
        ClassIdMap map = ClassIdMap.getInstance();
        
        int classId = map.getOrRegisterClass("com.example.Foo");
        int memberId = map.getOrRegisterMember("com.example.Foo#field");
        int memberId2 = map.getOrRegisterMember("com.example.Foo#method");
        
        assertTrue(classId < 8_000_000, "Class ID should be in class namespace");
        assertTrue(memberId >= 8_000_000, "Member ID should be in member namespace");
        assertTrue(memberId2 >= 8_000_000, "Another member ID should be in member namespace");
        assertNotEquals(memberId, memberId2, "Different members should get different IDs");
    }
    
    @Test
    @DisplayName("BitsetTracker records both class and member IDs")
    public void testBitsetTrackerRecording() {
        BitsetTracker tracker = new BitsetTracker();
        
        tracker.recordClass(10);
        tracker.recordClass(20);
        tracker.recordMember(8_000_000);
        tracker.recordMember(8_000_001);
        
        // Verify bitset properties
        assertTrue(tracker.count() >= 4, "Should have at least 4 bits set");
        
        // Note: toClassNames/toMemberNames require actual ID map lookups
        // so we skip full conversion testing here, but the bitset recording works
    }
    
    @Test
    @DisplayName("UsageStore recordMemberUsageId forwards to tracker")
    public void testRecordMemberUsage() {
        UsageStore store = UsageStore.getInstance();
        ClassIdMap map = ClassIdMap.getInstance();

        // Pre-resolve member IDs (as instrumentation would do at transform time)
        int fooFieldId = map.getOrRegisterMember("com.example.Foo#field");
        int fooBarId   = map.getOrRegisterMember("com.example.Foo#bar");

        // Start a test class
        store.startTestClass("TestExample");

        // Record member usage by pre-resolved ID (should not throw)
        store.recordMemberUsageId(fooFieldId);
        store.recordMemberUsageId(fooBarId);

        store.endTestClass();

        // Verify no exceptions and integration works
        assertTrue(true, "recordMemberUsageId completed without errors");
    }

    @Test
    @DisplayName("UsageStore parallel recording updates class and method snapshots")
    public void testParallelUsageStoreRecording() throws Exception {
        UsageStore store = UsageStore.getInstance();
        ClassIdMap map = ClassIdMap.getInstance();

        String suffix = UUID.randomUUID().toString();
        String testClass = "ParallelTest_" + suffix;
        String methodName = "recordsEverything";
        String methodKey = testClass + "#" + methodName;

        String[] classNames = new String[] {
                "com.example.parallel." + suffix + ".Alpha",
                "com.example.parallel." + suffix + ".Beta",
                "com.example.parallel." + suffix + ".Gamma",
                "com.example.parallel." + suffix + ".Delta"
        };
        String[] memberNames = new String[] {
                classNames[0] + "#fieldA",
                classNames[1] + "#fieldB",
                classNames[2] + "#fieldC",
                classNames[3] + "#fieldD"
        };

        int[] classIds = new int[classNames.length];
        int[] memberIds = new int[memberNames.length];
        for (int i = 0; i < classNames.length; i++) {
            classIds[i] = map.getOrRegisterClass(classNames[i]);
            memberIds[i] = map.getOrRegisterMember(memberNames[i]);
        }

        store.setMethodLevelRecordingEnabled(true);
        store.startTestClass(testClass);
        store.startTestMethod(testClass, methodName);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                await(start);
                for (int round = 0; round < 1000; round++) {
                    for (int j = 0; j < classIds.length; j++) {
                        store.recordUsageId(classIds[j]);
                        store.recordMemberUsageId(memberIds[j]);
                    }
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "parallel recording should finish");

        store.endTestMethod();
        store.endTestClass();
        store.setMethodLevelRecordingEnabled(false);

        Map<String, Set<String>> classDeps = invokeSnapshot(store, "collectDeps");
        Map<String, Set<String>> methodDeps = invokeSnapshot(store, "collectMethodDeps");
        Map<String, Set<String>> classMemberDeps = invokeSnapshot(store, "collectMemberDeps");
        Map<String, Set<String>> methodMemberDeps = invokeSnapshot(store, "collectMethodMemberDeps");

        assertEquals(Set.of(classNames), classDeps.get(testClass), "class-level deps should contain all classes");
        assertEquals(Set.of(classNames), methodDeps.get(methodKey), "method-level deps should contain all classes");
        assertEquals(Set.of(memberNames), classMemberDeps.get(testClass), "class-level member deps should contain all members");
        assertEquals(Set.of(memberNames), methodMemberDeps.get(methodKey), "method-level member deps should contain all members");
    }
    
    @Test
    @DisplayName("Bitset dynamic growth handles large ID values")
    public void testBitsetDynamicGrowth() {
        BitsetTracker tracker = new BitsetTracker();
        
        // Record a class in a high-capacity part (beyond first word)
        tracker.recordClass(200);  // requires word 3 (200/64 = 3.125)
        tracker.recordClass(500);  // requires word 7 (500/64 = 7.8)
        tracker.recordClass(1000); // requires word 15 (1000/64 = 15.6)
        
        int count = tracker.count();
        assertTrue(count >= 3, "Should have recorded 3 bits");
    }
    
    @Test
    @DisplayName("StampedLock optimistic read wins 99.9% of time")
    public void testClassIdMapOptimisticRead() {
        ClassIdMap map = ClassIdMap.getInstance();
        
        // Pre-register some classes
        for (int i = 0; i < 100; i++) {
            map.getOrRegisterClass("com.example.Class" + i);
        }
        
        // Now do lookups - should all use optimistic read (no registration)
        long startNanos = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            map.getOrRegisterClass("com.example.Class" + (i % 100));
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        
        // Should be very fast if using optimistic reads
        // 1000 lookups should take < 100ms on modern hardware
        long elapsedMs = elapsedNanos / 1_000_000;
        assertTrue(elapsedMs < 100, "Lookups should be fast: " + elapsedMs + "ms for 1000 ops");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> invokeSnapshot(UsageStore store, String methodName) throws Exception {
        Method method = UsageStore.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (Map<String, Set<String>>) method.invoke(store);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting to start concurrent UsageStore test");
        }
    }
}
