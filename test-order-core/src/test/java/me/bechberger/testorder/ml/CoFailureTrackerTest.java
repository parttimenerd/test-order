package me.bechberger.testorder.ml;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

class CoFailureTrackerTest {

	@Test
	void sharedFailureProximity_nestedClassFallsBackToTopLevel() {
		CoFailureTracker tracker = new CoFailureTracker();
		// Record a run where three classes failed together.
		// This gives each class a partner set containing the other two.
		tracker.recordRun(Set.of("com.example.FooTest", "com.example.BarTest", "com.example.BazTest"));

		// Query with a nested class name as a changed class.
		// The tracker has no entry for "com.example.FooTest$Inner", but should
		// fall back to "com.example.FooTest" and find its co-failure partners.
		// BarTest partners = {FooTest, BazTest}
		// FooTest partners (via fallback) = {BarTest, BazTest}
		// Jaccard({FooTest,BazTest}, {BarTest,BazTest}) = 1/3 > 0
		double proximity = tracker.sharedFailureProximity("com.example.BarTest", Set.of("com.example.FooTest$Inner"));

		assertTrue(proximity > 0.0, "should find co-failure proximity via top-level fallback for nested changed class");
	}

	@Test
	void sharedFailureProximity_exactNestedMatchPreferred() {
		CoFailureTracker tracker = new CoFailureTracker();
		// Record runs with explicit nested class entries (3 classes for non-zero
		// Jaccard)
		tracker.recordRun(Set.of("com.example.Outer$Inner", "com.example.BarTest", "com.example.BazTest"));

		// BarTest partners = {Outer$Inner, BazTest}
		// Outer$Inner partners = {BarTest, BazTest}
		// Jaccard({Outer$Inner, BazTest}, {BarTest, BazTest}) = 1/3 > 0
		double proximity = tracker.sharedFailureProximity("com.example.BarTest", Set.of("com.example.Outer$Inner"));

		assertTrue(proximity > 0.0, "exact match for nested class should work without needing fallback");
	}

	@Test
	void sharedFailureProximity_noFallbackWhenTopLevelAlsoMissing() {
		CoFailureTracker tracker = new CoFailureTracker();
		// Record a run with unrelated classes
		tracker.recordRun(Set.of("com.example.FooTest", "com.example.BarTest"));

		// Query with nested class whose top-level parent is also not in the tracker
		double proximity = tracker.sharedFailureProximity("com.example.FooTest",
				Set.of("com.example.UnknownTest$Inner"));

		assertEquals(0.0, proximity, "should return 0.0 when neither nested nor top-level has partners");
	}

	@Test
	void recordRun_nestedClassesFormCoFailurePairs() {
		CoFailureTracker tracker = new CoFailureTracker();
		tracker.recordRun(Set.of("com.example.Outer$A", "com.example.Outer$B", "com.example.Other"));

		Set<String> partnersOfA = tracker.getPartners("com.example.Outer$A");
		assertTrue(partnersOfA.contains("com.example.Outer$B"));
		assertTrue(partnersOfA.contains("com.example.Other"));

		Set<String> partnersOfB = tracker.getPartners("com.example.Outer$B");
		assertTrue(partnersOfB.contains("com.example.Outer$A"));
	}
}
