package me.bechberger.testorder.ops.detection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

class DeltaDebuggingTest {

    /**
     * A configurable fake TestRunner that fails the victim when specific polluters precede it.
     */
    static class FakeRunner implements TestRunner {
        private final Set<String> polluters;
        private final String victim;
        private int runCount = 0;

        FakeRunner(String victim, String... polluters) {
            this.victim = victim;
            this.polluters = Set.of(polluters);
        }

        @Override
        public TestRunResult run(List<String> testOrder) {
            runCount++;
            Set<String> passed = new HashSet<>();
            Set<String> failed = new HashSet<>();

            // Victim fails if any polluter appears before it in the order
            boolean polluted = false;
            for (String test : testOrder) {
                if (test.equals(victim)) {
                    if (polluted) {
                        failed.add(test);
                    } else {
                        passed.add(test);
                    }
                } else {
                    if (polluters.contains(test)) {
                        polluted = true;
                    }
                    passed.add(test);
                }
            }
            return new TestRunResult(testOrder, passed, failed);
        }

        int runCount() { return runCount; }
    }

    @Test
    void minimizesToSinglePolluter() {
        FakeRunner runner = new FakeRunner("Victim", "Polluter");
        List<String> candidates = List.of("A", "B", "Polluter", "C", "D");

        List<String> result = DeltaDebugging.minimize(candidates, "Victim", runner, 20);

        assertEquals(List.of("Polluter"), result);
    }

    @Test
    void returnsEmptyWhenNoPollution() {
        FakeRunner runner = new FakeRunner("Victim", "NotPresent");
        List<String> candidates = List.of("A", "B", "C");

        List<String> result = DeltaDebugging.minimize(candidates, "Victim", runner, 20);

        assertTrue(result.isEmpty());
    }

    @Test
    void handlesSingleCandidate() {
        FakeRunner runner = new FakeRunner("Victim", "Polluter");
        List<String> candidates = List.of("Polluter");

        List<String> result = DeltaDebugging.minimize(candidates, "Victim", runner, 20);

        assertEquals(List.of("Polluter"), result);
    }

    @Test
    void handlesEmptyCandidates() {
        FakeRunner runner = new FakeRunner("Victim", "Polluter");

        List<String> result = DeltaDebugging.minimize(List.of(), "Victim", runner, 20);

        assertTrue(result.isEmpty());
    }

    @Test
    void respectsRunBudget() {
        FakeRunner runner = new FakeRunner("Victim", "Polluter");
        List<String> candidates = List.of("A", "B", "C", "D", "E", "F", "G", "Polluter");

        // Very tight budget — should still return something, just not minimal
        List<String> result = DeltaDebugging.minimize(candidates, "Victim", runner, 3);

        // Should find something (may not be minimal with tight budget)
        assertFalse(result.isEmpty());
        assertTrue(result.contains("Polluter"));
    }

    @Test
    void findsPolluterAmongManyTests() {
        FakeRunner runner = new FakeRunner("V", "P");
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            candidates.add("T" + i);
        }
        candidates.set(37, "P"); // Hide polluter at position 37

        List<String> result = DeltaDebugging.minimize(candidates, "V", runner, 50);

        assertEquals(List.of("P"), result);
        // ddmin should find it in O(log n) runs
        assertTrue(runner.runCount() < 20, "Expected fewer runs, got " + runner.runCount());
    }
}
