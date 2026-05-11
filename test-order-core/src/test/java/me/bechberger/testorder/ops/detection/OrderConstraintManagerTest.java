package me.bechberger.testorder.ops.detection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

class OrderConstraintManagerTest {

    @Test
    void mustPrecedeConstraintProducesValidOrder() {
        OrderConstraintManager mgr = new OrderConstraintManager();
        mgr.addMustPrecede("Setter", "Brittle", "Brittle needs Setter");

        List<String> order = mgr.buildConstrainedOrder(
                List.of("Brittle", "Setter", "Other"));

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
                new ODResult("Victim", ODType.VICTIM, List.of("Polluter", "Victim"),
                        "polluter found", 0.95),
                new ODResult("Brittle", ODType.BRITTLE, List.of("Setter", "Brittle"),
                        "setter found", 0.95));

        mgr.applyResults(results);

        assertEquals(2, mgr.constraints().size());

        // VICTIM → MUST_NOT_PRECEDE
        assertTrue(mgr.constraints().stream()
                .anyMatch(c -> c.type() == OrderConstraintManager.ConstraintType.MUST_NOT_PRECEDE
                        && c.testA().equals("Polluter") && c.testB().equals("Victim")));

        // BRITTLE → MUST_PRECEDE
        assertTrue(mgr.constraints().stream()
                .anyMatch(c -> c.type() == OrderConstraintManager.ConstraintType.MUST_PRECEDE
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
        List<String> order = mgr.buildConstrainedOrder(
                List.of("Polluter", "Victim", "Other"));

        // Victim should no longer directly follow Polluter
        int polluterIdx = order.indexOf("Polluter");
        int victimIdx = order.indexOf("Victim");
        assertTrue(victimIdx != polluterIdx + 1 || order.size() <= 2,
                "Victim should not immediately follow Polluter");
    }
}
