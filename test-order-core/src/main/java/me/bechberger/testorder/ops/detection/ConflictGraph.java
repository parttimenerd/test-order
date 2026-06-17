package me.bechberger.testorder.ops.detection;

import java.util.*;

/**
 * A conflict graph where edges represent pairs of tests that share mutable
 * state and whose relative ordering may trigger OD failures.
 */
public class ConflictGraph {

	private final List<ConflictEdge> edges;
	private final Map<String, List<ConflictEdge>> edgesByTest;

	public ConflictGraph(List<ConflictEdge> edges) {
		this.edges = new ArrayList<>(edges);
		this.edgesByTest = new HashMap<>();
		for (ConflictEdge e : edges) {
			edgesByTest.computeIfAbsent(e.testA(), k -> new ArrayList<>()).add(e);
			edgesByTest.computeIfAbsent(e.testB(), k -> new ArrayList<>()).add(e);
		}
	}

	public static ConflictGraph empty() {
		return new ConflictGraph(List.of());
	}

	public List<ConflictEdge> edges() {
		return Collections.unmodifiableList(edges);
	}

	public List<ConflictEdge> edgesFor(String test) {
		List<ConflictEdge> list = edgesByTest.get(test);
		return list != null ? Collections.unmodifiableList(list) : List.of();
	}

	public int edgeCount() {
		return edges.size();
	}

	public boolean isEmpty() {
		return edges.isEmpty();
	}

	/**
	 * An edge in the conflict graph representing two tests sharing state.
	 *
	 * @param testA
	 *            first test (FQCN)
	 * @param testB
	 *            second test (FQCN)
	 * @param sharedMembers
	 *            the shared fields (e.g., "com.example.Service#cache")
	 * @param weight
	 *            edge weight (0.0–1.0) representing likelihood of OD
	 */
	public record ConflictEdge(String testA, String testB, Set<String> sharedMembers,
			double weight) implements Comparable<ConflictEdge> {

		@Override
		public int compareTo(ConflictEdge o) {
			int c = Double.compare(o.weight, this.weight);
			if (c != 0)
				return c;
			c = testA.compareTo(o.testA);
			return c != 0 ? c : testB.compareTo(o.testB);
		}
	}
}
