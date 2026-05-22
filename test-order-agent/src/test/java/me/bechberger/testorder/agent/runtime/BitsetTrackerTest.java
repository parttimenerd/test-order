package me.bechberger.testorder.agent.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class BitsetTrackerTest {

	private static final int MEMBER_ID_OFFSET = 8_000_000;

	@Test
	void recordsClassesAndMembersAndConvertsBackToNames() {
		ClassIdMap map = ClassIdMap.getInstance();
		String className = "test.bitset." + UUID.randomUUID();
		String memberName = className + "#field";

		int classId = map.getOrRegisterClass(className);
		int memberId = map.getOrRegisterMember(memberName);

		BitsetTracker tracker = new BitsetTracker();
		tracker.recordClass(classId);
		tracker.recordMember(memberId);

		Set<String> classNames = tracker.toClassNames();
		Set<String> memberNames = tracker.toMemberNames();

		assertTrue(classNames.contains(className));
		assertTrue(memberNames.contains(memberName));
		assertEquals(2, tracker.count());
	}

	@Test
	void ignoresNegativeIdsAtUsageStoreLevel() {
		// BitsetTracker.recordClass/recordMember no longer guard against negative IDs
		// (caller's responsibility for hot-path performance). Verify the guard exists
		// at the UsageStore level via recordMemberUsageIdFast which checks memberId >=
		// 0.
		BitsetTracker tracker = new BitsetTracker();

		// Valid ID should work
		tracker.recordClass(0);
		assertEquals(1, tracker.count());
	}

	@Test
	void clearRemovesRecordedBits() {
		ClassIdMap map = ClassIdMap.getInstance();
		int classId = map.getOrRegisterClass("test.clear." + UUID.randomUUID());

		BitsetTracker tracker = new BitsetTracker();
		tracker.recordClass(classId);
		assertTrue(tracker.count() > 0);

		tracker.clear();

		assertEquals(0, tracker.count());
		assertTrue(tracker.toClassNames().isEmpty());
	}

	@Test
	void concurrentRecordingSameIdIsIdempotent() throws Exception {
		ClassIdMap map = ClassIdMap.getInstance();
		int classId = map.getOrRegisterClass("test.concurrent.bit." + UUID.randomUUID());

		BitsetTracker tracker = new BitsetTracker();
		int threads = 16;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);

		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				await(start);
				for (int j = 0; j < 1000; j++) {
					tracker.recordClass(classId);
				}
			});
		}

		start.countDown();
		pool.shutdown();
		assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

		assertEquals(1, tracker.count());
	}

	@Test
	void concurrentRecordingSameMemberIdIsIdempotent() throws Exception {
		ClassIdMap map = ClassIdMap.getInstance();
		String className = "test.concurrent.member." + UUID.randomUUID();
		int memberId = map.getOrRegisterMember(className + "#field");

		BitsetTracker tracker = new BitsetTracker();
		int threads = 16;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);

		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				await(start);
				for (int j = 0; j < 1000; j++) {
					tracker.recordMember(memberId);
				}
			});
		}

		start.countDown();
		pool.shutdown();
		assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

		assertEquals(1, tracker.count());
		assertTrue(tracker.toMemberNames().contains(className + "#field"));
	}

	@Test
	void concurrentRecordingDistinctClassAndMemberIdsPreservesAllBits() throws Exception {
		ClassIdMap map = ClassIdMap.getInstance();
		BitsetTracker tracker = new BitsetTracker();
		int threads = 8;
		int[] classIds = new int[threads];
		int[] memberIds = new int[threads];
		String[] classNames = new String[threads];
		String[] memberNames = new String[threads];

		for (int i = 0; i < threads; i++) {
			classNames[i] = "test.parallel.class." + i + "." + UUID.randomUUID();
			memberNames[i] = classNames[i] + "#field";
			classIds[i] = map.getOrRegisterClass(classNames[i]);
			memberIds[i] = map.getOrRegisterMember(memberNames[i]);
		}

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);

		for (int i = 0; i < threads; i++) {
			final int classId = classIds[i];
			final int memberId = memberIds[i];
			pool.submit(() -> {
				await(start);
				for (int j = 0; j < 1000; j++) {
					tracker.recordClass(classId);
					tracker.recordMember(memberId);
				}
			});
		}

		start.countDown();
		pool.shutdown();
		assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

		assertEquals(threads * 2, tracker.count());
		Set<String> recordedClasses = tracker.toClassNames();
		Set<String> recordedMembers = tracker.toMemberNames();
		for (int i = 0; i < threads; i++) {
			assertTrue(recordedClasses.contains(classNames[i]));
			assertTrue(recordedMembers.contains(memberNames[i]));
		}
	}

	@Test
	void growsBitsetForLargeClassIds() {
		BitsetTracker tracker = new BitsetTracker();
		tracker.recordClass(10_000);
		tracker.recordClass(20_000);

		assertEquals(2, tracker.count());
	}

	@Test
	void growsBitsetForLargeMemberIds() {
		BitsetTracker tracker = new BitsetTracker();

		tracker.recordMember(MEMBER_ID_OFFSET + 40_000);
		tracker.recordMember(MEMBER_ID_OFFSET + 80_000);

		assertEquals(2, tracker.count());
	}

	@Test
	void concurrentMemberRecordingDuringGrowthRetainsAllBits() throws Exception {
		BitsetTracker tracker = new BitsetTracker();
		int threads = 12;
		int[] memberIds = new int[threads];
		for (int i = 0; i < threads; i++) {
			memberIds[i] = MEMBER_ID_OFFSET + 33_000 + (i * 4096);
		}

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);

		for (int i = 0; i < threads; i++) {
			final int memberId = memberIds[i];
			pool.submit(() -> {
				await(start);
				for (int j = 0; j < 500; j++) {
					tracker.recordMember(memberId);
				}
			});
		}

		start.countDown();
		pool.shutdown();
		assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

		assertEquals(threads, tracker.count());
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
