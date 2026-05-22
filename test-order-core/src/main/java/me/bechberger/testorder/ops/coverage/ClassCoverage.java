package me.bechberger.testorder.ops.coverage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Dependency-based coverage metrics for a single production class. Records how
 * many test classes exercise this class and which ones, plus member-level
 * coverage when available (MEMBER instrumentation mode).
 */
public final class ClassCoverage implements Comparable<ClassCoverage> {

	private final String fullyQualifiedName;
	private final String packageName;
	private final String className;
	private final List<String> exercisingTests;

	/** All known member names (methods/fields) of this class. */
	private final Set<String> allMembers;

	/** Members that are exercised by at least one test. */
	private final Set<String> exercisedMembers;

	/** Per-member: member name → test classes that exercise it. */
	private final Map<String, List<String>> memberTestMap;

	public ClassCoverage(String fullyQualifiedName, List<String> exercisingTests, Set<String> allMembers,
			Set<String> exercisedMembers, Map<String, List<String>> memberTestMap) {
		this.fullyQualifiedName = fullyQualifiedName;
		int lastDot = fullyQualifiedName.lastIndexOf('.');
		this.packageName = lastDot > 0 ? fullyQualifiedName.substring(0, lastDot) : "";
		this.className = lastDot > 0 ? fullyQualifiedName.substring(lastDot + 1) : fullyQualifiedName;
		this.exercisingTests = List.copyOf(exercisingTests);
		this.allMembers = Set.copyOf(allMembers);
		this.exercisedMembers = Set.copyOf(exercisedMembers);
		this.memberTestMap = Map.copyOf(memberTestMap);
	}

	/**
	 * Convenience constructor when member-level data is not available.
	 */
	public ClassCoverage(String fullyQualifiedName, List<String> exercisingTests) {
		this(fullyQualifiedName, exercisingTests, Set.of(), Set.of(), Map.of());
	}

	public String fullyQualifiedName() {
		return fullyQualifiedName;
	}

	public String packageName() {
		return packageName;
	}

	public String className() {
		return className;
	}

	public int testCount() {
		return exercisingTests.size();
	}

	public List<String> exercisingTests() {
		return exercisingTests;
	}

	// ── Member-level coverage ─────────────────────────────────────────

	/** Whether member-level coverage data is available. */
	public boolean hasMemberCoverage() {
		return !allMembers.isEmpty();
	}

	/** All known members of this class. */
	public Set<String> allMembers() {
		return allMembers;
	}

	/** Members exercised by at least one test. */
	public Set<String> exercisedMembers() {
		return exercisedMembers;
	}

	/** Members with no test coverage. */
	public Set<String> uncoveredMembers() {
		if (allMembers.isEmpty())
			return Set.of();
		var uncovered = new java.util.TreeSet<>(allMembers);
		uncovered.removeAll(exercisedMembers);
		return Set.copyOf(uncovered);
	}

	/** Number of members exercised / total (0-100), or -1 if no member data. */
	public int memberCoveragePercent() {
		if (allMembers.isEmpty())
			return -1;
		return (int) (100.0 * exercisedMembers.size() / allMembers.size());
	}

	/** Per-member map: member name → test classes that exercise it. */
	public Map<String, List<String>> memberTestMap() {
		return memberTestMap;
	}

	/** Sorts by test count ascending (least tested first), then by FQCN. */
	@Override
	public int compareTo(ClassCoverage other) {
		int cmp = Integer.compare(this.testCount(), other.testCount());
		return cmp != 0 ? cmp : this.fullyQualifiedName.compareTo(other.fullyQualifiedName);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		return fullyQualifiedName.equals(((ClassCoverage) o).fullyQualifiedName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(fullyQualifiedName);
	}

	@Override
	public String toString() {
		String memberInfo = hasMemberCoverage()
				? ", members: " + exercisedMembers.size() + "/" + allMembers.size()
				: "";
		return fullyQualifiedName + " (" + testCount() + " tests" + memberInfo + ")";
	}
}
