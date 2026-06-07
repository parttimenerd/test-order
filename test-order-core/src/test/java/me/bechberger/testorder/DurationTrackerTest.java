package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

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

	// ── Adaptive alpha (BUG-91 regression) ────────────────────────────

	@Test
	void highVarianceDampensAlphaForMoreSmoothing() {
		// BUG-91: Javadoc on emaVarianceThreshold claimed high variance *increases*
		// alpha
		// (to track real changes), but the code correctly *reduces* alpha (to damp
		// noise).
		// This test pins the actual behaviour: high-variance test → lower effective
		// alpha.

		// Set up a test with a known mean and high variance.
		// mean = 100, variance such that relativeStdDev = sqrt(variance)/100 >>
		// threshold
		// Use threshold=0.10, baseAlpha=0.5, minAdaptiveAlphaFactor=0.1 so the
		// dampening
		// is large enough to observe.
		DurationTracker tracker = new DurationTracker();
		tracker.putClassDuration("com.test.Noisy", 100L);
		// relativeStdDev = sqrt(9000) / 100 ≈ 0.949, which >> threshold=0.10
		tracker.putClassDurationVariance("com.test.Noisy", 9000.0);

		// Without adaptive dampening (variance=0 path): EMA = 0.5*200 + 0.5*100 = 150
		// With adaptive dampening: effectiveAlpha = max(0.1*0.5, 0.5 * (0.10/0.949)) ≈
		// 0.0527
		// EMA = 0.0527*200 + (1-0.0527)*100 ≈ 110.54, rounded → 111

		// With threshold=0.35 (no dampening at this variance level), check the stable
		// path
		tracker.recordClassDuration("com.test.Noisy", 200, 0.5, 0.10, 0.1);
		long dampened = tracker.getClassDuration("com.test.Noisy", -1);
		assertTrue(dampened < 150,
				"High variance should dampen alpha → EMA should stay closer to old value (100), got " + dampened);
		assertTrue(dampened > 100, "EMA must still move toward 200, got " + dampened);
	}

	@Test
	void stableVarianceUsesBaseAlpha() {
		// When relativeStdDev <= varianceThreshold, base alpha is used unchanged.
		DurationTracker tracker = new DurationTracker();
		tracker.putClassDuration("com.test.Stable", 100L);
		// relativeStdDev = sqrt(1) / 100 = 0.01, well below threshold=0.35
		tracker.putClassDurationVariance("com.test.Stable", 1.0);

		tracker.recordClassDuration("com.test.Stable", 200, 0.5, 0.35, 0.35);
		// base alpha=0.5 → EMA = round(0.5*200 + 0.5*100) = 150
		assertEquals(150, tracker.getClassDuration("com.test.Stable", -1),
				"Stable variance should use base alpha → EMA = 150");
	}

	@Test
	void negativeOrZeroDurationIsIgnored() {
		DurationTracker tracker = new DurationTracker();
		tracker.recordClassDuration("com.test.A", 100, 0.5, 0.35, 0.35);
		tracker.recordClassDuration("com.test.A", -1, 0.5, 0.35, 0.35);
		assertEquals(100, tracker.getClassDuration("com.test.A", -1), "Negative duration should be ignored");
	}
}
