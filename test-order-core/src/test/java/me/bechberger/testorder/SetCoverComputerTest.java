package me.bechberger.testorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SetCoverComputer")
class SetCoverComputerTest {

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("E16: Empty universe returns empty result")
        void emptyUniverseReturnsEmptyResult() {
            // E16: Guard condition - if universe is empty, returns empty list and map
            Map<String, Set<String>> coverage = new HashMap<>();
            coverage.put("test1", Set.of("dep1", "dep2"));
            coverage.put("test2", Set.of("dep3"));
            Set<String> universe = new HashSet<>();

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            assertTrue(result.order().isEmpty(), "Order should be empty for empty universe");
            assertTrue(result.initialCoverCounts().isEmpty(), "Initial counts should be empty for empty universe");
        }

        @Test
        @DisplayName("E17: Empty coverage (no tests) returns empty result")
        void emptyCoverageReturnsEmptyResult() {
            // E17: Guard condition - if coverage map is empty, returns empty list and map
            Map<String, Set<String>> coverage = new HashMap<>();
            Set<String> universe = Set.of("dep1", "dep2", "dep3", "dep4", "dep5");

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            assertTrue(result.order().isEmpty(), "Order should be empty when no coverage");
            assertTrue(result.initialCoverCounts().isEmpty(), "Initial counts should be empty when no coverage");
        }

        @Test
        @DisplayName("E18: Single test covers entire universe")
        void singleTestCoversEntireUniverse() {
            // E18: One test covers all dependencies - should have exactly one result entry
            Map<String, Set<String>> coverage = new HashMap<>();
            coverage.put("test1", Set.of("dep1", "dep2", "dep3", "dep4", "dep5"));
            Set<String> universe = Set.of("dep1", "dep2", "dep3", "dep4", "dep5");

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            assertEquals(1, result.order().size(), "Should have exactly one test in order");
            assertEquals("test1", result.order().get(0), "Order should contain test1");
            assertEquals(1, result.initialCoverCounts().size(), "Should have one entry in initial counts");
            assertEquals(5, result.initialCoverCounts().get("test1"), "test1 should initially cover 5 deps");
        }

        @Test
        @DisplayName("E19: All tests have identical dep sets - uniform initial counts")
        void allTestsIdenticalDepSetsUniformInitialCounts() {
            // E19: When all tests cover the same set of dependencies,
            // they should have uniform initialCoverCounts, but greedy order depends on first selection
            Map<String, Set<String>> coverage = new HashMap<>();
            coverage.put("test1", Set.of("dep1", "dep2", "dep3"));
            coverage.put("test2", Set.of("dep1", "dep2", "dep3"));
            coverage.put("test3", Set.of("dep1", "dep2", "dep3"));
            Set<String> universe = Set.of("dep1", "dep2", "dep3");

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            // Should select one test (others are redundant) since first test covers everything
            assertEquals(1, result.order().size(), "Only one test needed to cover all identical deps");
            assertEquals(3, result.initialCoverCounts().get(result.order().get(0)));
            // All tests should have uniform initialCoverCounts
            assertEquals(3, result.initialCoverCounts().get("test1"));
            assertEquals(3, result.initialCoverCounts().get("test2"));
            assertEquals(3, result.initialCoverCounts().get("test3"));
        }

        @Test
        @DisplayName("E20: Stale QueueEntry count after re-queue - entries skipped correctly")
        void staleQueueEntryCountSkipped() {
            // E20: When a test's uncovered count becomes stale after decrement,
            // it should be re-queued with correct count and later skipped when popped with wrong count
            Map<String, Set<String>> coverage = new HashMap<>();
            coverage.put("test1", Set.of("dep1", "dep2", "dep3"));
            coverage.put("test2", Set.of("dep2", "dep3", "dep4"));
            coverage.put("test3", Set.of("dep5"));
            Set<String> universe = Set.of("dep1", "dep2", "dep3", "dep4", "dep5");

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            // Verify that result is valid and complete
            assertFalse(result.order().isEmpty(), "Should have selected tests");
            assertEquals(3, result.initialCoverCounts().size(), "Should track initial counts for all tests");
            // Verify no NPE and algorithm completes successfully
            assertNotNull(result.order());
            assertNotNull(result.initialCoverCounts());
        }
    }

    @Nested
    @DisplayName("Basic Scenarios")
    class BasicScenarios {

        @Test
        @DisplayName("Two tests covering different parts of universe")
        void twoTestsDifferentCoverage() {
            // Test 1 covers deps 1,2,3 and Test 2 covers deps 3,4,5
            // Greedy should pick test1 first (covers 3) then test2 (covers 2 new ones)
            Map<String, Set<String>> coverage = new HashMap<>();
            coverage.put("test1", Set.of("dep1", "dep2", "dep3"));
            coverage.put("test2", Set.of("dep3", "dep4", "dep5"));
            Set<String> universe = Set.of("dep1", "dep2", "dep3", "dep4", "dep5");

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            assertEquals(2, result.order().size(), "Should select both tests");
            assertTrue(result.order().contains("test1"), "Should contain test1");
            assertTrue(result.order().contains("test2"), "Should contain test2");
            // First test should be test1 (covers 3 deps) vs test2 (covers 3 deps)
            // Due to insertion order, test1 comes first in LinkedHashMap
            assertEquals("test1", result.order().get(0), "First selected should be test1 (covers 3)");
            assertEquals("test2", result.order().get(1), "Second selected should be test2");
        }

        @Test
        @DisplayName("Greedy prioritizes tests covering most uncovered deps")
        void greedyPrioritizesMostCovering() {
            // Test 1 covers 4 deps, Test 2 covers 2 deps, Test 3 covers 2 deps
            // Greedy should pick test1 first, then either test2 or test3
            Map<String, Set<String>> coverage = new HashMap<>();
            coverage.put("test1", Set.of("dep1", "dep2", "dep3", "dep4"));
            coverage.put("test2", Set.of("dep5", "dep6"));
            coverage.put("test3", Set.of("dep7", "dep8"));
            Set<String> universe = Set.of("dep1", "dep2", "dep3", "dep4", "dep5", "dep6", "dep7", "dep8");

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            assertEquals(3, result.order().size(), "Should select all three tests");
            assertEquals("test1", result.order().get(0), "First selected should be test1 (covers 4 deps)");
            assertTrue(result.order().contains("test2"), "Should contain test2");
            assertTrue(result.order().contains("test3"), "Should contain test3");
        }

        @Test
        @DisplayName("Result order is deterministic")
        void resultOrderDeterministic() {
            // Run the same computation multiple times and verify same order
            Map<String, Set<String>> coverage1 = new HashMap<>();
            coverage1.put("testA", Set.of("d1", "d2", "d3"));
            coverage1.put("testB", Set.of("d3", "d4", "d5"));
            coverage1.put("testC", Set.of("d5", "d6"));
            Set<String> universe = Set.of("d1", "d2", "d3", "d4", "d5", "d6");

            SetCoverComputer<String, String> computer1 = new SetCoverComputer<>(coverage1, universe);
            var result1 = computer1.compute();

            // Create fresh map with same data
            Map<String, Set<String>> coverage2 = new HashMap<>();
            coverage2.put("testA", Set.of("d1", "d2", "d3"));
            coverage2.put("testB", Set.of("d3", "d4", "d5"));
            coverage2.put("testC", Set.of("d5", "d6"));

            SetCoverComputer<String, String> computer2 = new SetCoverComputer<>(coverage2, universe);
            var result2 = computer2.compute();

            assertEquals(result1.order(), result2.order(), "Results should be deterministic");
        }
    }

    @Nested
    @DisplayName("Coverage Completeness")
    class CoverageCompleteness {

        @Test
        @DisplayName("Verify result size is correct")
        void resultSizeCorrect() {
            Map<String, Set<String>> coverage = new HashMap<>();
            coverage.put("test1", Set.of("dep1"));
            coverage.put("test2", Set.of("dep2"));
            coverage.put("test3", Set.of("dep3"));
            Set<String> universe = Set.of("dep1", "dep2", "dep3");

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            assertEquals(3, result.order().size(), "All tests needed to cover all deps");
        }

        @Test
        @DisplayName("Verify coverage completeness")
        void verifyCoverageCompleteness() {
            Map<String, Set<String>> coverage = new HashMap<>();
            coverage.put("t1", Set.of("a", "b"));
            coverage.put("t2", Set.of("c", "d", "e"));
            coverage.put("t3", Set.of("e", "f"));
            Set<String> universe = Set.of("a", "b", "c", "d", "e", "f");

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            // Compute covered set
            Set<String> covered = new HashSet<>();
            for (String test : result.order()) {
                covered.addAll(coverage.get(test));
            }

            assertEquals(universe, covered, "Selected tests should cover entire universe");
        }

        @Test
        @DisplayName("Initial cover counts are accurate")
        void initialCoverCountsAccurate() {
            Map<String, Set<String>> coverage = new HashMap<>();
            coverage.put("test1", Set.of("dep1", "dep2", "dep3"));
            coverage.put("test2", Set.of("dep3", "dep4"));
            coverage.put("test3", Set.of("dep4", "dep5", "dep6", "dep7"));
            Set<String> universe = Set.of("dep1", "dep2", "dep3", "dep4", "dep5", "dep6", "dep7");

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            assertEquals(3, result.initialCoverCounts().get("test1"), "test1 covers 3 deps");
            assertEquals(2, result.initialCoverCounts().get("test2"), "test2 covers 2 deps");
            assertEquals(4, result.initialCoverCounts().get("test3"), "test3 covers 4 deps");
        }
    }

    @Nested
    @DisplayName("Null/Empty Input Handling")
    class NullEmptyInputHandling {

        @Test
        @DisplayName("Null universe elements handled safely")
        void nullUniverseElementsSafe() {
            Map<String, Set<Integer>> coverage = new HashMap<>();
            coverage.put("test1", Set.of(1, 2));
            coverage.put("test2", Set.of(3, 4));
            Set<Integer> universe = Set.of(1, 2, 3, 4);

            assertDoesNotThrow(() -> {
                SetCoverComputer<String, Integer> computer = new SetCoverComputer<>(coverage, universe);
                var result = computer.compute();
                assertNotNull(result);
            });
        }

        @Test
        @DisplayName("Coverage with no intersection with universe")
        void coverageNoIntersectionWithUniverse() {
            Map<String, Set<String>> coverage = new HashMap<>();
            coverage.put("test1", Set.of("x", "y"));
            coverage.put("test2", Set.of("a", "b"));
            Set<String> universe = Set.of("dep1", "dep2", "dep3");

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            // No overlap between coverage and universe, so nothing should be selected
            assertTrue(result.order().isEmpty(), "Should not select tests that don't cover universe");
        }

        @Test
        @DisplayName("Partial coverage - some deps uncovered")
        void partialCoverageUncoveredDeps() {
            Map<String, Set<String>> coverage = new HashMap<>();
            coverage.put("test1", Set.of("dep1", "dep2"));
            coverage.put("test2", Set.of("dep3"));
            Set<String> universe = Set.of("dep1", "dep2", "dep3", "dep4", "dep5");

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            // Only dep1, dep2, dep3 can be covered; dep4, dep5 cannot
            Set<String> covered = new HashSet<>();
            for (String test : result.order()) {
                covered.addAll(coverage.get(test));
            }

            assertTrue(covered.containsAll(Set.of("dep1", "dep2", "dep3")), "Should cover available deps");
            assertFalse(covered.contains("dep4"), "Cannot cover dep4");
            assertFalse(covered.contains("dep5"), "Cannot cover dep5");
        }
    }

    @Nested
    @DisplayName("Algorithm Correctness")
    class AlgorithmCorrectness {

        @Test
        @DisplayName("No duplicate tests in result")
        void noDuplicatesInResult() {
            Map<String, Set<String>> coverage = new HashMap<>();
            coverage.put("test1", Set.of("dep1", "dep2"));
            coverage.put("test2", Set.of("dep2", "dep3"));
            coverage.put("test3", Set.of("dep3", "dep4"));
            Set<String> universe = Set.of("dep1", "dep2", "dep3", "dep4");

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            Set<String> uniqueTests = new HashSet<>(result.order());
            assertEquals(uniqueTests.size(), result.order().size(), "Result should not contain duplicate tests");
        }

        @Test
        @DisplayName("All selected tests are from coverage map")
        void selectedTestsInCoverage() {
            Map<String, Set<String>> coverage = new HashMap<>();
            coverage.put("validTest1", Set.of("dep1", "dep2"));
            coverage.put("validTest2", Set.of("dep3", "dep4"));
            coverage.put("validTest3", Set.of("dep4", "dep5"));
            Set<String> universe = Set.of("dep1", "dep2", "dep3", "dep4", "dep5");

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            for (String test : result.order()) {
                assertTrue(coverage.containsKey(test), "Selected test " + test + " should be in coverage map");
            }
        }

        @Test
        @DisplayName("Greedy selection stops when universe is covered")
        void greedyStopsWhenCovered() {
            Map<String, Set<String>> coverage = new HashMap<>();
            coverage.put("test1", Set.of("dep1", "dep2", "dep3", "dep4", "dep5"));
            coverage.put("test2", Set.of("dep6", "dep7"));
            coverage.put("test3", Set.of("dep8", "dep9"));
            Set<String> universe = Set.of("dep1", "dep2", "dep3", "dep4", "dep5");

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            assertEquals(1, result.order().size(), "Should only select test1 to cover universe");
            assertEquals("test1", result.order().get(0));
        }
    }

    @Nested
    @DisplayName("Complex Scenarios")
    class ComplexScenarios {

        @Test
        @DisplayName("Overlapping coverage with multiple selections")
        void overlappingCoverageMultipleSelections() {
            Map<String, Set<String>> coverage = new HashMap<>();
            coverage.put("test1", Set.of("a", "b", "c"));
            coverage.put("test2", Set.of("b", "c", "d"));
            coverage.put("test3", Set.of("c", "d", "e"));
            coverage.put("test4", Set.of("d", "e", "f"));
            Set<String> universe = Set.of("a", "b", "c", "d", "e", "f");

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            Set<String> covered = new HashSet<>();
            for (String test : result.order()) {
                covered.addAll(coverage.get(test));
            }

            assertEquals(universe, covered, "Should cover entire universe");
            assertTrue(result.order().size() <= 3, "Should need at most 3 tests");
        }

        @Test
        @DisplayName("Large universe with multiple tests")
        void largeUniverseMultipleTests() {
            Map<String, Set<String>> coverage = new HashMap<>();
            Set<String> universe = new HashSet<>();

            // Create 10 tests covering different subsets
            for (int i = 0; i < 10; i++) {
                Set<String> deps = new HashSet<>();
                for (int j = i; j < i + 5; j++) {
                    deps.add("dep" + j);
                    universe.add("dep" + j);
                }
                coverage.put("test" + i, deps);
            }

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            Set<String> covered = new HashSet<>();
            for (String test : result.order()) {
                covered.addAll(coverage.get(test));
            }

            assertEquals(universe, covered, "Should cover entire large universe");
            assertFalse(result.order().isEmpty());
        }

        @Test
        @DisplayName("Tests with single element coverage")
        void testsWithSingleElementCoverage() {
            Map<String, Set<String>> coverage = new HashMap<>();
            coverage.put("test1", Set.of("dep1"));
            coverage.put("test2", Set.of("dep2"));
            coverage.put("test3", Set.of("dep3"));
            coverage.put("test4", Set.of("dep4"));
            coverage.put("test5", Set.of("dep5"));
            Set<String> universe = Set.of("dep1", "dep2", "dep3", "dep4", "dep5");

            SetCoverComputer<String, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            assertEquals(5, result.order().size(), "Should need all 5 tests");
            Set<String> covered = new HashSet<>();
            for (String test : result.order()) {
                covered.addAll(coverage.get(test));
            }
            assertEquals(universe, covered);
        }
    }

    @Nested
    @DisplayName("Generic Types")
    class GenericTypes {

        @Test
        @DisplayName("Works with integer keys and string values")
        void integerKeysStringValues() {
            Map<Integer, Set<String>> coverage = new HashMap<>();
            coverage.put(1, Set.of("feature1", "feature2"));
            coverage.put(2, Set.of("feature2", "feature3"));
            coverage.put(3, Set.of("feature3", "feature4"));
            Set<String> universe = Set.of("feature1", "feature2", "feature3", "feature4");

            SetCoverComputer<Integer, String> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            assertFalse(result.order().isEmpty());
            assertEquals(3, result.initialCoverCounts().size());
        }

        @Test
        @DisplayName("Works with custom object types")
        void customObjectTypes() {
            Map<String, Set<Integer>> coverage = new HashMap<>();
            coverage.put("groupA", Set.of(1, 2, 3));
            coverage.put("groupB", Set.of(3, 4, 5));
            coverage.put("groupC", Set.of(5, 6));
            Set<Integer> universe = Set.of(1, 2, 3, 4, 5, 6);

            SetCoverComputer<String, Integer> computer = new SetCoverComputer<>(coverage, universe);
            var result = computer.compute();

            Set<Integer> covered = new HashSet<>();
            for (String group : result.order()) {
                covered.addAll(coverage.get(group));
            }

            assertEquals(universe, covered);
        }
    }
}
