package me.bechberger.testorder.agent.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ClassIdMapTest {

    @Test
    void atomicAndVarHandleModesBehaveConsistently() {
        ClassIdMap atomicMap = ClassIdMap.createForBenchmark(ClassIdMap.CounterMode.ATOMIC);
        ClassIdMap varHandleMap = ClassIdMap.createForBenchmark(ClassIdMap.CounterMode.VAR_HANDLE);

        String classA = "test.mode.ClassA";
        String classB = "test.mode.ClassB";
        String memberA = classA + "#field";

        int atomicA = atomicMap.getOrRegisterClass(classA);
        int atomicB = atomicMap.getOrRegisterClass(classB);
        int atomicMember = atomicMap.getOrRegisterMember(memberA);

        int varHandleA = varHandleMap.getOrRegisterClass(classA);
        int varHandleB = varHandleMap.getOrRegisterClass(classB);
        int varHandleMember = varHandleMap.getOrRegisterMember(memberA);

        assertEquals(0, atomicA);
        assertEquals(1, atomicB);
        assertTrue(atomicMember >= 8_000_000);

        assertEquals(0, varHandleA);
        assertEquals(1, varHandleB);
        assertTrue(varHandleMember >= 8_000_000);
    }

    @Test
    void sameClassNameReturnsStableId() {
        ClassIdMap map = ClassIdMap.getInstance();
        String className = "test.classidmap." + UUID.randomUUID().toString().replace('-', '_');

        int id1 = map.getOrRegisterClass(className);
        int id2 = map.getOrRegisterClass(className);

        assertEquals(id1, id2);
        assertTrue(id1 >= 0);
    }

    @Test
    void memberIdsUseOffsetNamespace() {
        ClassIdMap map = ClassIdMap.getInstance();
        String memberA = "test.member." + UUID.randomUUID() + "#fieldA";
        String memberB = "test.member." + UUID.randomUUID() + "#fieldB";

        int idA = map.getOrRegisterMember(memberA);
        int idB = map.getOrRegisterMember(memberB);

        assertTrue(idA >= 8_000_000);
        assertTrue(idB >= 8_000_000);
        assertNotEquals(idA, idB);
    }

    @Test
    void reverseLookupsReturnRegisteredNames() {
        ClassIdMap map = ClassIdMap.getInstance();
        String className = "test.reverse." + UUID.randomUUID();
        String memberName = className + "#myField";

        int classId = map.getOrRegisterClass(className);
        int memberId = map.getOrRegisterMember(memberName);

        assertEquals(className, map.getClassNameForId(classId));
        assertEquals(memberName, map.getMemberNameForId(memberId));
        assertNull(map.getClassNameForId(memberId));
        assertNull(map.getMemberNameForId(classId));
    }

    @Test
    void concurrentRegistrationSameClassProducesSingleId() throws Exception {
        ClassIdMap map = ClassIdMap.getInstance();
        String className = "test.concurrent.same." + UUID.randomUUID();

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Integer> ids = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                await(start);
                ids.add(map.getOrRegisterClass(className));
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, ids.size());
    }

    @Test
    void concurrentRegistrationDifferentClassesProducesUniqueIds() throws Exception {
        ClassIdMap map = ClassIdMap.getInstance();
        int n = 120;
        List<String> names = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            names.add("test.concurrent.unique." + i + "." + UUID.randomUUID());
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        Set<Integer> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);

        for (String name : names) {
            pool.submit(() -> {
                await(start);
                ids.add(map.getOrRegisterClass(name));
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(n, ids.size());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting to start concurrent test");
        }
    }
}
