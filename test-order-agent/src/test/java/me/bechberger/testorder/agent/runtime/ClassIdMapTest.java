package me.bechberger.testorder.agent.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ClassIdMapTest {

	@Test
	void benchmarkFactoryUsesVarHandleCounter() {
		ClassIdMap map = ClassIdMap.createForBenchmark();

		String classA = "test.mode.ClassA";
		String classB = "test.mode.ClassB";
		String memberA = classA + "#field";

		int idA = map.getOrRegisterClass(classA);
		int idB = map.getOrRegisterClass(classB);
		int memberId = map.getOrRegisterMember(memberA);

		assertEquals(0, idA);
		assertEquals(1, idB);
		assertTrue(memberId >= 8_000_000);
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

	@Test
	void getOrRegisterClassReturnsNegativeOneAtCapacity() throws Exception {
		ClassIdMap map = ClassIdMap.createForBenchmark();

		// Use reflection to set the class ID counter to MEMBER_ID_OFFSET - 1
		// so the very next registration exhausts the class ID namespace.
		int memberIdOffset = 8_000_000;
		java.lang.reflect.Field nextClassIdField = ClassIdMap.class.getDeclaredField("nextClassId");
		nextClassIdField.setAccessible(true);
		Object counter = nextClassIdField.get(map);
		java.lang.reflect.Field valueField = counter.getClass().getDeclaredField("value");
		valueField.setAccessible(true);
		valueField.setInt(counter, memberIdOffset - 1);

		// Last valid ID
		int lastId = map.getOrRegisterClass("com.Last");
		assertEquals(memberIdOffset - 1, lastId);

		// First overflow — must return -1, not throw
		int overflowId = map.getOrRegisterClass("com.Overflow");
		assertEquals(-1, overflowId, "getOrRegisterClass should return -1 when capacity exceeded");

		// Repeated calls for same overflow key also return -1
		int overflowId2 = map.getOrRegisterClass("com.Overflow");
		assertEquals(-1, overflowId2);
	}
}
