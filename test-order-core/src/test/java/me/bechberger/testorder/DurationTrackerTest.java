package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

class DurationTrackerTest {

	@Test
	void recordsClassDurationWithEmaSmoothing() {
		DurationTracker tracker = new DurationTracker();

		tracker.recordClassDuration("com.test.A", 100, 0.5, 0.35, 0.35);
		tracker.recordClassDuration("com.test.A", 200, 0.5, 0.35, 0.35);

		assertEquals(150, tracker.getClassDuration("com.test.A", 0));
	}

	@Test
	void recordsMethodDurationWithEmaSmoothing() {
		DurationTracker tracker = new DurationTracker();

		tracker.recordMethodDuration("com.test.A", "testOne", 40, 0.5, 0.35, 0.35);
		tracker.recordMethodDuration("com.test.A", "testOne", 80, 0.5, 0.35, 0.35);

		assertEquals(60.0, tracker.getMethodDuration("com.test.A", "testOne", 0.0));
	}

	@Test
	void pruneToActiveClassesRemovesStaleClassAndMethodData() {
		DurationTracker tracker = new DurationTracker();

		tracker.putClassDuration("active.Test", 100L);
		tracker.putClassDuration("stale.Test", 200L);
		tracker.putClassDurationVariance("active.Test", 0.2);
		tracker.putClassDurationVariance("stale.Test", 0.3);
		tracker.putMethodDuration("active.Test", "testA", 10.0);
		tracker.putMethodDuration("stale.Test", "testB", 20.0);
		tracker.putMethodDurationVariance("active.Test", "testA", 0.1);
		tracker.putMethodDurationVariance("stale.Test", "testB", 0.2);

		tracker.pruneToActiveClasses(Set.of("active.Test"));

		assertEquals(Set.of("active.Test"), tracker.classDurations().keySet());
		assertEquals(Set.of("active.Test"), tracker.classDurationVariances().keySet());
		assertEquals(Set.of("active.Test"), tracker.methodDurations().keySet());
		assertEquals(Set.of("active.Test"), tracker.methodDurationVariances().keySet());
	}
}
