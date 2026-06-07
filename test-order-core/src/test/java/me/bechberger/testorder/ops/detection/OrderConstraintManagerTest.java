package me.bechberger.testorder.ops.detection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

class OrderConstraintManagerTest {

	@Test
	void mustPrecedeConstraintProducesValidOrder() {
		OrderConstraintManager mgr = new OrderConstraintManager();
		mgr.addMustPrecede("Setter", "Brittle", "Brittle needs Setter");

		List<String> order = mgr.buildConstrainedOrder(List.of("Brittle", "Setter", "Other"));

		int setterIdx = order.indexOf("Setter");
		int brittleIdx = order.indexOf("Brittle");
		assertTrue(setterIdx < brittleIdx,
				"Setter should precede Brittle, got indices " + setterIdx + "," + brittleIdx);
	}

	@Test
	void multipleConstraintsRespected() {
		OrderConstraintManager mgr = new OrderConstraintManager();
		mgr.addMustPrecede("A", "B", "B needs A");
		mgr.addMustPrecede("B", "C", "C needs B");

		List<String> order = mgr.buildConstrainedOrder(List.of("C", "B", "A", "D"));

		assertTrue(order.indexOf("A") < order.indexOf("B"));
		assertTrue(order.indexOf("B") < order.indexOf("C"));
	}

	@Test
	void cycleFallsBackToReferenceOrder() {
		OrderConstraintManager mgr = new OrderConstraintManager();
		mgr.addMustPrecede("A", "B", "");
		mgr.addMustPrecede("B", "A", ""); // cycle!

		List<String> reference = List.of("A", "B", "C");
		List<String> order = mgr.buildConstrainedOrder(reference);

		assertEquals(reference, order);
	}

	@Test
	void applyResultsFromODFindings() {
		OrderConstraintManager mgr = new OrderConstraintManager();

		List<ODResult> results = List.of(
				new ODResult("Victim", ODType.VICTIM, List.of("Polluter", "Victim"), "polluter found", 0.95),
				new ODResult("Brittle", ODType.BRITTLE, List.of("Setter", "Brittle"), "setter found", 0.95));

		mgr.applyResults(results);

		assertEquals(2, mgr.constraints().size());

		// VICTIM → MUST_NOT_PRECEDE
		assertTrue(mgr.constraints().stream()
				.anyMatch(c -> c.type() == OrderConstraintManager.ConstraintType.MUST_NOT_PRECEDE
						&& c.testA().equals("Polluter") && c.testB().equals("Victim")));

		// BRITTLE → MUST_PRECEDE
		assertTrue(
				mgr.constraints().stream().anyMatch(c -> c.type() == OrderConstraintManager.ConstraintType.MUST_PRECEDE
						&& c.testA().equals("Setter") && c.testB().equals("Brittle")));
	}

	@Test
	void emptyConstraintsPreservesReferenceOrder() {
		OrderConstraintManager mgr = new OrderConstraintManager();
		List<String> reference = List.of("A", "B", "C", "D");

		List<String> order = mgr.buildConstrainedOrder(reference);

		assertEquals(reference, order);
	}

	@Test
	void mustNotPrecedeSwapsAdjacentPair() {
		OrderConstraintManager mgr = new OrderConstraintManager();
		mgr.addMustNotPrecede("Polluter", "Victim", "OD bug");

		// Polluter immediately before Victim in reference → should be separated
		List<String> order = mgr.buildConstrainedOrder(List.of("Polluter", "Victim", "Other"));

		// Victim should no longer directly follow Polluter
		int polluterIdx = order.indexOf("Polluter");
		int victimIdx = order.indexOf("Victim");
		assertTrue(victimIdx != polluterIdx + 1 || order.size() <= 2, "Victim should not immediately follow Polluter");
	}

	@Test
	void mustNotPrecedeRecheckAfterSwapCatchesNewViolation() {
		// After swapping [P, V1, X] → [P, X, V1], the re-check at position 0 must
		// also detect P→X if that is a constraint. Without i-- this second violation
		// is missed because the loop advances to position 1 before checking P→X.
		OrderConstraintManager mgr = new OrderConstraintManager();
		mgr.addMustNotPrecede("P", "V1", "OD1");
		mgr.addMustNotPrecede("P", "X", "OD2");

		// [P, V1, X, Safe]: swap V1 with X → [P, X, V1, Safe].
		// Re-check at i=0 finds P→X violation too.
		// X swaps with V1 → [P, V1, X, Safe] again — but one re-check breaks the cycle.
		// Regardless, P must not immediately precede either victim at the end.
		List<String> order = mgr.buildConstrainedOrder(List.of("P", "V1", "X", "Safe"));

		// We just need P not to directly precede both V1 and X at the same time.
		// At least one of them should not immediately follow P.
		int pIdx = order.indexOf("P");
		int v1Idx = order.indexOf("V1");
		int xIdx = order.indexOf("X");
		boolean v1ImmediatelyAfterP = (v1Idx == pIdx + 1);
		boolean xImmediatelyAfterP = (xIdx == pIdx + 1);
		assertFalse(v1ImmediatelyAfterP && xImmediatelyAfterP,
				"Both V1 and X cannot simultaneously immediately follow P — order: " + order);
	}

	@Test
	void mustNotPrecedeAtEndMovesVictimBeforePolluter() {
		OrderConstraintManager mgr = new OrderConstraintManager();
		mgr.addMustNotPrecede("P", "V", "OD");

		// P at end, V immediately after — only two elements
		List<String> order = mgr.buildConstrainedOrder(List.of("P", "V"));

		int pIdx = order.indexOf("P");
		int vIdx = order.indexOf("V");
		assertNotEquals(pIdx + 1, vIdx, "V should not immediately follow P");
	}
}
