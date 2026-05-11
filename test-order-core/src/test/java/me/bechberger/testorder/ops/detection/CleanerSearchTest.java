package me.bechberger.testorder.ops.detection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

class CleanerSearchTest {

    @Test
    void findsCleanerThatNeutralizesPolluterEffect() {
        // Polluter sets bad state; Cleaner resets it; Victim needs clean state
        TestRunner runner = order -> {
            Set<String> passed = new HashSet<>();
            Set<String> failed = new HashSet<>();
            boolean polluted = false;

            for (String test : order) {
                switch (test) {
                    case "Polluter" -> { polluted = true; passed.add(test); }
                    case "Cleaner" -> { polluted = false; passed.add(test); }
                    case "Victim" -> {
                        if (polluted) failed.add(test); else passed.add(test);
                    }
                    default -> passed.add(test);
                }
            }
            return new TestRunner.TestRunResult(order, passed, failed);
        };

        Optional<String> cleaner = CleanerSearch.find(
                "Polluter", "Victim",
                List.of("A", "Cleaner", "B"), runner, 10);

        assertTrue(cleaner.isPresent());
        assertEquals("Cleaner", cleaner.get());
    }

    @Test
    void returnsEmptyWhenNoCleanerExists() {
        TestRunner runner = order -> {
            Set<String> passed = new HashSet<>();
            Set<String> failed = new HashSet<>();
            boolean polluted = false;

            for (String test : order) {
                if (test.equals("Polluter")) { polluted = true; passed.add(test); }
                else if (test.equals("Victim")) {
                    if (polluted) failed.add(test); else passed.add(test);
                } else { passed.add(test); } // No test neutralizes
            }
            return new TestRunner.TestRunResult(order, passed, failed);
        };

        Optional<String> cleaner = CleanerSearch.find(
                "Polluter", "Victim",
                List.of("A", "B", "C"), runner, 10);

        assertTrue(cleaner.isEmpty());
    }

    @Test
    void returnsEmptyWhenBaselineDoesNotFail() {
        // If [Polluter, Victim] doesn't fail, nothing to clean
        TestRunner runner = order -> new TestRunner.TestRunResult(
                order, new HashSet<>(order), Set.of());

        Optional<String> cleaner = CleanerSearch.find(
                "Polluter", "Victim",
                List.of("A", "B"), runner, 10);

        assertTrue(cleaner.isEmpty());
    }

    @Test
    void findAllReturnsMultipleCleaners() {
        TestRunner runner = order -> {
            Set<String> passed = new HashSet<>();
            Set<String> failed = new HashSet<>();
            boolean polluted = false;

            for (String test : order) {
                switch (test) {
                    case "Polluter" -> { polluted = true; passed.add(test); }
                    case "Clean1", "Clean2" -> { polluted = false; passed.add(test); }
                    case "Victim" -> {
                        if (polluted) failed.add(test); else passed.add(test);
                    }
                    default -> passed.add(test);
                }
            }
            return new TestRunner.TestRunResult(order, passed, failed);
        };

        List<String> cleaners = CleanerSearch.findAll(
                "Polluter", "Victim",
                List.of("A", "Clean1", "B", "Clean2"), runner, 10);

        assertEquals(2, cleaners.size());
        assertTrue(cleaners.contains("Clean1"));
        assertTrue(cleaners.contains("Clean2"));
    }

    @Test
    void respectsRunBudget() {
        int[] counter = {0};
        TestRunner runner = order -> {
            counter[0]++;
            Set<String> passed = new HashSet<>();
            Set<String> failed = new HashSet<>();
            for (String t : order) {
                if (t.equals("Victim") && order.contains("Polluter")
                        && order.indexOf("Polluter") < order.indexOf("Victim")
                        && !order.contains("Cleaner")) {
                    failed.add(t);
                } else {
                    passed.add(t);
                }
            }
            return new TestRunner.TestRunResult(order, passed, failed);
        };

        // Budget of 2: 1 for baseline + 1 for first candidate
        CleanerSearch.find("Polluter", "Victim",
                List.of("A", "B", "C", "D", "E"), runner, 2);

        assertEquals(2, counter[0]);
    }
}
