package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

/** Tests for {@link TestSplitAdvisor}. */
class TestSplitAdvisorTest {

	// --- jaccardSimilarity unit tests ---

	@Test
	void jaccardSimilarityIdentical() {
		assertEquals(1.0, TestSplitAdvisor.jaccardSimilarity(Set.of("A", "B"), Set.of("A", "B")), 1e-9);
	}

	@Test
	void jaccardSimilarityDisjoint() {
		assertEquals(0.0, TestSplitAdvisor.jaccardSimilarity(Set.of("A"), Set.of("B")), 1e-9);
	}

	@Test
	void jaccardSimilarityEmpty() {
		assertEquals(0.0, TestSplitAdvisor.jaccardSimilarity(Set.of(), Set.of("A")), 1e-9);
		assertEquals(0.0, TestSplitAdvisor.jaccardSimilarity(Set.of("A"), Set.of()), 1e-9);
	}

	@Test
	void jaccardSimilarityPartialOverlap() {
		// {A,B,C} ∩ {B,C,D} = {B,C}; ∪ = {A,B,C,D} → sim = 2/4 = 0.5
		assertEquals(0.5, TestSplitAdvisor.jaccardSimilarity(Set.of("A", "B", "C"), Set.of("B", "C", "D")), 1e-9);
	}

	// --- avgPairwiseSimilarity ---

	@Test
	void avgSimTwoPerfectlyOverlapping() {
		List<Map.Entry<String, Set<String>>> methods = List.of(Map.entry("m1", Set.of("A", "B")),
				Map.entry("m2", Set.of("A", "B")));
		assertEquals(1.0, TestSplitAdvisor.computeAvgPairwiseSimilarity(methods), 1e-9);
	}

	@Test
	void avgSimTwoFullyDisjoint() {
		List<Map.Entry<String, Set<String>>> methods = List.of(Map.entry("m1", Set.of("A")),
				Map.entry("m2", Set.of("B")));
		assertEquals(0.0, TestSplitAdvisor.computeAvgPairwiseSimilarity(methods), 1e-9);
	}

	@Test
	void avgSimThreeMethods() {
		// m1 vs m2: sim=1, m1 vs m3: sim=0, m2 vs m3: sim=0 → avg = 1/3 ≈ 0.333
		List<Map.Entry<String, Set<String>>> methods = List.of(Map.entry("m1", Set.of("A", "B")),
				Map.entry("m2", Set.of("A", "B")), Map.entry("m3", Set.of("C", "D")));
		assertEquals(1.0 / 3.0, TestSplitAdvisor.computeAvgPairwiseSimilarity(methods), 1e-9);
	}

	// --- analyze (integration) ---

	@Test
	void noMethodDepsReturnsEmpty() {
		DependencyMap dep = new DependencyMap();
		dep.put("com.example.FooTest", Set.of("com.example.Foo"));
		assertTrue(TestSplitAdvisor.analyze(dep).isEmpty());
	}

	@Test
	void singleMethodNotFlagged() {
		DependencyMap dep = new DependencyMap();
		dep.putMethodDeps("com.example.FooTest#testA", Set.of("A"));
		assertTrue(TestSplitAdvisor.analyze(dep).isEmpty());
	}

	@Test
	void identicalMethodsNotFlagged() {
		DependencyMap dep = new DependencyMap();
		dep.putMethodDeps("com.example.FooTest#testA", Set.of("A", "B"));
		dep.putMethodDeps("com.example.FooTest#testB", Set.of("A", "B"));
		// sim = 1.0 ≥ 0.3 → not flagged
		assertTrue(TestSplitAdvisor.analyze(dep).isEmpty());
	}

	@Test
	void disjointMethodsFlagged() {
		DependencyMap dep = new DependencyMap();
		dep.putMethodDeps("com.example.FooTest#testA", Set.of("A", "B"));
		dep.putMethodDeps("com.example.FooTest#testB", Set.of("C", "D"));
		// sim = 0.0 < 0.3 → flagged
		List<TestSplitAdvice> advice = TestSplitAdvisor.analyze(dep);
		assertEquals(1, advice.size());
		TestSplitAdvice a = advice.get(0);
		assertEquals("com.example.FooTest", a.className());
		assertEquals(2, a.methodsWithDeps());
		assertEquals(0.0, a.avgPairwiseSimilarity(), 1e-9);
		assertFalse(a.reason().isEmpty());
	}

	@Test
	void belowCustomThresholdFlagged() {
		DependencyMap dep = new DependencyMap();
		dep.putMethodDeps("com.example.FooTest#testA", Set.of("A", "B", "C"));
		dep.putMethodDeps("com.example.FooTest#testB", Set.of("B", "C", "D"));
		// sim = 2/4 = 0.5 → above default 0.3 → not flagged by default
		assertTrue(TestSplitAdvisor.analyze(dep).isEmpty());
		// but flagged with threshold=0.6
		List<TestSplitAdvice> advice = TestSplitAdvisor.analyze(dep, 0.6);
		assertEquals(1, advice.size());
		assertEquals(0.5, advice.get(0).avgPairwiseSimilarity(), 1e-9);
	}

	@Test
	void sortedBySimAscending() {
		DependencyMap dep = new DependencyMap();
		// ClassA: methods fully disjoint → sim=0
		dep.putMethodDeps("com.example.ClassATest#m1", Set.of("P"));
		dep.putMethodDeps("com.example.ClassATest#m2", Set.of("Q"));
		// ClassB: methods overlap a bit → sim=0.2 (1 shared / 5 union)
		dep.putMethodDeps("com.example.ClassBTest#m1", Set.of("P", "Q", "R"));
		dep.putMethodDeps("com.example.ClassBTest#m2", Set.of("R", "S", "T"));
		List<TestSplitAdvice> advice = TestSplitAdvisor.analyze(dep);
		assertEquals(2, advice.size());
		// ClassA should come first (sim=0 < sim of ClassB)
		assertEquals("com.example.ClassATest", advice.get(0).className());
		assertEquals("com.example.ClassBTest", advice.get(1).className());
	}

	@Test
	void suggestedGroupsNotEmpty() {
		DependencyMap dep = new DependencyMap();
		dep.putMethodDeps("com.example.FooTest#testA", Set.of("A", "B"));
		dep.putMethodDeps("com.example.FooTest#testB", Set.of("C", "D"));
		dep.putMethodDeps("com.example.FooTest#testC", Set.of("A", "B")); // same as testA → merge
		// avg sim: A vs B = 0, A vs C = 1, B vs C = 0 → avg = 1/3 ≈ 0.33
		// Use threshold=0.4 so this borderline case is flagged
		List<TestSplitAdvice> advice = TestSplitAdvisor.analyze(dep, 0.4);
		assertEquals(1, advice.size());
		List<List<String>> groups = advice.get(0).suggestedGroups();
		// testA and testC share same deps → merged into one group; testB alone
		assertFalse(groups.isEmpty());
		// Total methods across all groups must equal methodsWithDeps
		int total = groups.stream().mapToInt(List::size).sum();
		assertEquals(advice.get(0).methodsWithDeps(), total);
	}

	@Test
	void emptyDepsSkippedForMethod() {
		DependencyMap dep = new DependencyMap();
		dep.putMethodDeps("com.example.FooTest#testA", Set.of("A"));
		dep.putMethodDeps("com.example.FooTest#testB", Set.of()); // empty → skipped
		// Only 1 method with data → not analysed
		assertTrue(TestSplitAdvisor.analyze(dep).isEmpty());
	}

	@Test
	void clusterMergesOverlappingPairs() {
		// m1 and m2 both depend on {A,B}; m3 depends on {C,D}
		List<Map.Entry<String, Set<String>>> methods = List.of(Map.entry("m1", Set.of("A", "B")),
				Map.entry("m2", Set.of("A", "B")), Map.entry("m3", Set.of("C", "D")));
		List<List<String>> groups = TestSplitAdvisor.cluster(methods);
		assertEquals(2, groups.size());
		// One group should contain both m1 and m2
		boolean foundPair = groups.stream().anyMatch(g -> g.containsAll(List.of("m1", "m2")));
		assertTrue(foundPair, "Expected m1 and m2 to be merged into the same group");
	}
}
