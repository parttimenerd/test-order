package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TestScorer.ScoreResult#isChangeAffected()} — the predicate
 * that decides whether a test is genuinely affected by the change (BUG-173).
 *
 * <p>
 * A positive {@code score} alone is NOT sufficient: speed and failure-history
 * bonuses are change-INDEPENDENT (a fast or historically-flaky test scores
 * above zero regardless of what changed). Before BUG-173 both {@code show}'s
 * module header and the reactor reorderer counted any {@code score > 0} test as
 * "affected", so passing a non-existent/unrelated changed class made 162 fast
 * jackson-databind tests report as "affected=162" while their dependency
 * overlap was 0/238. A test is affected only when its application code probably
 * changed (dep overlap / static-field overlap / change complexity), it is a
 * changed test, or it is new.
 */
class ScoreResultAffectedTest {

	private static TestScorer.ScoreResult result(int score, int depOverlap, int depTotal, double failScore,
			boolean isNew, boolean isChanged, boolean isFast, boolean isSlow, double complexityOverlap,
			boolean hasStaticFieldOverlap) {
		return new TestScorer.ScoreResult(score, depOverlap, depTotal, failScore, isNew, isChanged, isFast, isSlow,
				complexityOverlap, 0.0, hasStaticFieldOverlap);
	}

	@Test
	void speedOnlyBonusIsNotAffected() {
		// The jackson-databind case: fast test, +1 purely from speed, zero change signal.
		var r = result(1, 0, 238, 0.0, false, false, true, false, 0.0, false);
		assertFalse(r.isChangeAffected(), "a test scoring only on speed must not count as change-affected");
	}

	@Test
	void failureHistoryOnlyIsNotAffected() {
		// Flaky test with failure-history score but no change relationship.
		var r = result(3, 0, 50, 4.0, false, false, false, false, 0.0, false);
		assertFalse(r.isChangeAffected(), "a test scoring only on failure history must not count as change-affected");
	}

	@Test
	void depOverlapIsAffected() {
		var r = result(6, 2, 40, 0.0, false, false, false, false, 0.0, false);
		assertTrue(r.isChangeAffected(), "dependency overlap with the changed set means affected");
	}

	@Test
	void changedTestIsAffected() {
		var r = result(9, 0, 40, 0.0, false, true, false, false, 0.0, false);
		assertTrue(r.isChangeAffected(), "a changed test itself is affected");
	}

	@Test
	void newTestIsAffected() {
		var r = result(15, 0, 0, 0.0, true, false, true, false, 0.0, false);
		assertTrue(r.isChangeAffected(), "a new test is affected (should run)");
	}

	@Test
	void staticFieldOverlapIsAffected() {
		var r = result(2, 0, 40, 0.0, false, false, false, false, 0.0, true);
		assertTrue(r.isChangeAffected(), "static-field overlap with a changed class means affected");
	}

	@Test
	void changeComplexityOverlapIsAffected() {
		var r = result(2, 0, 40, 0.0, false, false, false, false, 0.75, false);
		assertTrue(r.isChangeAffected(), "change-complexity overlap means affected");
	}

	@Test
	void zeroScoreUnaffectedIsNotAffected() {
		var r = result(0, 0, 40, 0.0, false, false, false, false, 0.0, false);
		assertFalse(r.isChangeAffected());
	}
}
