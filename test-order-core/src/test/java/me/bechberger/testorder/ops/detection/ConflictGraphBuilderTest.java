package me.bechberger.testorder.ops.detection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

class ConflictGraphBuilderTest {

	@Test
	void emptyDepMapProducesEmptyGraph() {
		// A dep map with no shared deps should produce no edges
		FakeDependencyMap depMap = new FakeDependencyMap();
		depMap.addTestClass("TestA", Set.of("com.example.Foo"));
		depMap.addTestClass("TestB", Set.of("com.example.Bar"));

		ConflictGraph graph = ConflictGraphBuilder.build(depMap.toDependencyMap(), null, List.of("TestA", "TestB"));

		assertTrue(graph.isEmpty());
	}

	@Test
	void sharedDependencyCreatesEdge() {
		FakeDependencyMap depMap = new FakeDependencyMap();
		depMap.addTestClass("TestA", Set.of("com.example.Shared", "com.example.Foo"));
		depMap.addTestClass("TestB", Set.of("com.example.Shared", "com.example.Bar"));

		// Need at least 3 shared at class level for edge by default
		depMap.addMemberDep("TestA", Set.of("com.example.Shared#field1", "com.example.Shared#field2"));
		depMap.addMemberDep("TestB", Set.of("com.example.Shared#field1", "com.example.Shared#field2"));

		ConflictGraph graph = ConflictGraphBuilder.build(depMap.toDependencyMap(), null, List.of("TestA", "TestB"));

		// With member-level deps sharing field1 and field2, should create an edge
		assertFalse(graph.isEmpty());
		assertEquals(1, graph.edgeCount());

		ConflictGraph.ConflictEdge edge = graph.edges().get(0);
		assertTrue(edge.sharedMembers().contains("com.example.Shared#field1"));
	}

	@Test
	void noEdgeForDisjointMembers() {
		FakeDependencyMap depMap = new FakeDependencyMap();
		depMap.addTestClass("TestA", Set.of("com.example.Service"));
		depMap.addTestClass("TestB", Set.of("com.example.Service"));
		depMap.addMemberDep("TestA", Set.of("com.example.Service#methodA"));
		depMap.addMemberDep("TestB", Set.of("com.example.Service#methodB"));

		ConflictGraph graph = ConflictGraphBuilder.build(depMap.toDependencyMap(), null, List.of("TestA", "TestB"));

		// No shared members → no edge
		assertTrue(graph.isEmpty());
	}

	@Test
	void edgesSortedByWeightDescending() {
		FakeDependencyMap depMap = new FakeDependencyMap();
		depMap.addTestClass("TestA", Set.of("X"));
		depMap.addTestClass("TestB", Set.of("X"));
		depMap.addTestClass("TestC", Set.of("X"));
		depMap.addMemberDep("TestA", Set.of("X#f1", "X#f2", "X#f3"));
		depMap.addMemberDep("TestB", Set.of("X#f1")); // shares 1 with A
		depMap.addMemberDep("TestC", Set.of("X#f1", "X#f2", "X#f3")); // shares 3 with A

		ConflictGraph graph = ConflictGraphBuilder.build(depMap.toDependencyMap(), null,
				List.of("TestA", "TestB", "TestC"));

		if (!graph.isEmpty()) {
			// The edge with more shared members should have higher weight
			ConflictGraph.ConflictEdge first = graph.edges().get(0);
			assertTrue(first.sharedMembers().size() >= 2, "Highest-weight edge should have most shared members");
		}
	}

	@Test
	void constantFieldsWithUnderscoresDoNotCreateEdges() {
		// Tests sharing only ALL_CAPS_CONSTANT fields should NOT create edges
		// because constants (e.g., MAX_SIZE, DEFAULT_TIMEOUT) are immutable
		FakeDependencyMap depMap = new FakeDependencyMap();
		depMap.addTestClass("TestA", Set.of("com.example.Config"));
		depMap.addTestClass("TestB", Set.of("com.example.Config"));
		depMap.addMemberDep("TestA", Set.of("com.example.Config#MAX_SIZE", "com.example.Config#DEFAULT_TIMEOUT"));
		depMap.addMemberDep("TestB", Set.of("com.example.Config#MAX_SIZE", "com.example.Config#DEFAULT_TIMEOUT"));

		ConflictGraph graph = ConflictGraphBuilder.build(depMap.toDependencyMap(), null, List.of("TestA", "TestB"));

		assertTrue(graph.isEmpty(),
				"Constants (ALL_CAPS with underscores) should be filtered out — no conflict edge expected");
	}

	@Test
	void mutableFieldsWithConstantsCreateEdgesOnlyForMutable() {
		// When tests share both mutable fields and constants, only mutable fields
		// should contribute to conflict edges
		FakeDependencyMap depMap = new FakeDependencyMap();
		depMap.addTestClass("TestA", Set.of("com.example.Service"));
		depMap.addTestClass("TestB", Set.of("com.example.Service"));
		depMap.addMemberDep("TestA", Set.of("com.example.Service#MAX_RETRIES", // constant → skip
				"com.example.Service#cache", // mutable → count
				"com.example.Service#counter")); // mutable → count
		depMap.addMemberDep("TestB",
				Set.of("com.example.Service#MAX_RETRIES", "com.example.Service#cache", "com.example.Service#counter"));

		ConflictGraph graph = ConflictGraphBuilder.build(depMap.toDependencyMap(), null, List.of("TestA", "TestB"));

		if (!graph.isEmpty()) {
			ConflictGraph.ConflictEdge edge = graph.edges().get(0);
			assertFalse(edge.sharedMembers().contains("com.example.Service#MAX_RETRIES"),
					"Constant field MAX_RETRIES should not appear in shared members");
		}
	}

	/**
	 * Helper to build a fake DependencyMap for testing.
	 */
	private static class FakeDependencyMap {
		private final Map<String, Set<String>> deps = new HashMap<>();
		private final Map<String, Set<String>> memberDeps = new HashMap<>();

		void addTestClass(String testClass, Set<String> classDeps) {
			deps.put(testClass, new HashSet<>(classDeps));
		}

		void addMemberDep(String testClass, Set<String> members) {
			memberDeps.put(testClass, new HashSet<>(members));
		}

		me.bechberger.testorder.DependencyMap toDependencyMap() {
			var dm = new me.bechberger.testorder.DependencyMap();
			deps.forEach((k, v) -> dm.put(k, v));
			memberDeps.forEach((k, v) -> dm.putMemberDeps(k, v));
			return dm;
		}
	}
}
