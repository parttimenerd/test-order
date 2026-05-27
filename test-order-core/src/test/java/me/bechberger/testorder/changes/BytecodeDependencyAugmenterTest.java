package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import me.bechberger.testorder.DependencyMap;

class BytecodeDependencyAugmenterTest {

	@Test
	void emptyInputsReturnEmptyAugmentation() {
		assertTrue(BytecodeDependencyAugmenter.computeAugmentation(null, new DependencyMap()).isEmpty());
		assertTrue(BytecodeDependencyAugmenter.computeAugmentation(new StaticCallGraphAnalyzer.ScanResult(), null)
				.isEmpty());
		assertTrue(BytecodeDependencyAugmenter
				.computeAugmentation(new StaticCallGraphAnalyzer.ScanResult(), new DependencyMap()).isEmpty());
	}

	@Test
	void addsMissingEdgeForKnownTest() {
		DependencyMap dep = new DependencyMap();
		dep.put("com.example.TestA", Set.of("com.example.Prod1"));

		StaticCallGraphAnalyzer.ScanResult scan = new StaticCallGraphAnalyzer.ScanResult();
		// TestA references Prod1 (already known) and Prod2 (missing).
		scan.reverseCallGraph.computeIfAbsent("com.example.Prod1#x", k -> new HashSet<>())
				.add("com.example.TestA#test");
		scan.reverseCallGraph.computeIfAbsent("com.example.Prod2#y", k -> new HashSet<>())
				.add("com.example.TestA#test");

		Map<String, Set<String>> aug = BytecodeDependencyAugmenter.computeAugmentation(scan, dep);

		assertEquals(Set.of("com.example.TestA"), aug.keySet());
		assertEquals(Set.of("com.example.Prod2"), aug.get("com.example.TestA"));
	}

	@Test
	void neverInventsTestEntries() {
		DependencyMap dep = new DependencyMap();
		dep.put("com.example.TestA", Set.of("com.example.Prod1"));

		StaticCallGraphAnalyzer.ScanResult scan = new StaticCallGraphAnalyzer.ScanResult();
		// UnknownTest is not in depMap.testClasses() — must be ignored.
		scan.reverseCallGraph.computeIfAbsent("com.example.Prod2#y", k -> new HashSet<>())
				.add("com.example.UnknownTest#test");

		Map<String, Set<String>> aug = BytecodeDependencyAugmenter.computeAugmentation(scan, dep);
		assertTrue(aug.isEmpty(), "augmenter must not invent new test entries: " + aug);
	}

	@Test
	void skipsLibraryTypes() {
		DependencyMap dep = new DependencyMap();
		dep.put("com.example.TestA", Set.of());

		StaticCallGraphAnalyzer.ScanResult scan = new StaticCallGraphAnalyzer.ScanResult();
		scan.reverseCallGraph.computeIfAbsent("java.util.HashMap#put", k -> new HashSet<>())
				.add("com.example.TestA#test");

		Map<String, Set<String>> aug = BytecodeDependencyAugmenter.computeAugmentation(scan, dep);
		assertTrue(aug.isEmpty(), "library types must not be augmented: " + aug);
	}

	@Test
	void skipsSelfReferences() {
		DependencyMap dep = new DependencyMap();
		dep.put("com.example.TestA", Set.of());

		StaticCallGraphAnalyzer.ScanResult scan = new StaticCallGraphAnalyzer.ScanResult();
		// Test calls a method on itself — should not produce an edge.
		scan.reverseCallGraph.computeIfAbsent("com.example.TestA#helper", k -> new HashSet<>())
				.add("com.example.TestA#test");

		Map<String, Set<String>> aug = BytecodeDependencyAugmenter.computeAugmentation(scan, dep);
		assertTrue(aug.isEmpty(), "self-references must not be augmented: " + aug);
	}

	@Test
	void nestedClassDependencyIsCoveredByTopLevelEntry() {
		// depMap records only the top-level class; bytecode references the nested
		// class.
		// Augmenter's nested-class fallback should see the dep is already covered.
		DependencyMap dep = new DependencyMap();
		dep.put("com.example.TestA", Set.of("com.example.Outer"));

		StaticCallGraphAnalyzer.ScanResult scan = new StaticCallGraphAnalyzer.ScanResult();
		scan.reverseCallGraph.computeIfAbsent("com.example.Outer$Nested#x", k -> new HashSet<>())
				.add("com.example.TestA#test");

		Map<String, Set<String>> aug = BytecodeDependencyAugmenter.computeAugmentation(scan, dep);
		assertTrue(aug.isEmpty(), "nested-class ref should be covered by top-level dep: " + aug);
	}

	@Test
	void crossTestReferencesNeverPolluteDeps() {
		// TestA calls a helper on TestB. Both are known tests in depMap. Bytecode
		// scan picks up the test→test edge, but the augmenter must drop it — tests
		// are not production deps. Regression guard for the
		// !testClasses.contains(callerClass) check.
		DependencyMap dep = new DependencyMap();
		dep.put("com.example.TestA", Set.of());
		dep.put("com.example.TestB", Set.of());

		StaticCallGraphAnalyzer.ScanResult scan = new StaticCallGraphAnalyzer.ScanResult();
		scan.reverseCallGraph.computeIfAbsent("com.example.TestB#helper", k -> new HashSet<>())
				.add("com.example.TestA#test");

		Map<String, Set<String>> aug = BytecodeDependencyAugmenter.computeAugmentation(scan, dep);

		assertTrue(aug.isEmpty(), "test→test references must not be augmented as deps: " + aug);
	}

	@Test
	void descriptorlessKeysAreToleratedSafely() {
		// Bare class-name keys (no '#') should not crash the augmenter. They
		// behave as both class and method key; library/self filters still apply.
		DependencyMap dep = new DependencyMap();
		dep.put("com.example.TestA", Set.of());

		StaticCallGraphAnalyzer.ScanResult scan = new StaticCallGraphAnalyzer.ScanResult();
		// Callee key has no '#'. classOf returns the input — must not NPE.
		scan.reverseCallGraph.computeIfAbsent("com.example.Prod1", k -> new HashSet<>()).add("com.example.TestA");

		Map<String, Set<String>> aug = BytecodeDependencyAugmenter.computeAugmentation(scan, dep);

		assertEquals(Set.of("com.example.Prod1"), aug.get("com.example.TestA"));
	}

	@Test
	void augmentationIsAdditiveOnly() {
		// Map already has Prod1 and Prod2 for TestA; bytecode shows only Prod3.
		// Augmenter must add Prod3, never report removal of Prod1/Prod2.
		DependencyMap dep = new DependencyMap();
		dep.put("com.example.TestA", Set.of("com.example.Prod1", "com.example.Prod2"));

		StaticCallGraphAnalyzer.ScanResult scan = new StaticCallGraphAnalyzer.ScanResult();
		scan.reverseCallGraph.computeIfAbsent("com.example.Prod3#z", k -> new HashSet<>())
				.add("com.example.TestA#test");

		Map<String, Set<String>> aug = BytecodeDependencyAugmenter.computeAugmentation(scan, dep);
		assertEquals(Set.of("com.example.Prod3"), aug.get("com.example.TestA"));

		DependencyMap merged = dep.withAugmentation(aug);
		assertTrue(merged.get("com.example.TestA").contains("com.example.Prod1"));
		assertTrue(merged.get("com.example.TestA").contains("com.example.Prod2"));
		assertTrue(merged.get("com.example.TestA").contains("com.example.Prod3"));
	}
}
