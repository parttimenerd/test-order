package me.bechberger.testorder.ops.coverage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Immutable result of dependency-based coverage analysis. Contains
 * {@link ClassCoverage} entries for all known production classes and provides
 * query methods for filtering/aggregating.
 */
public final class CoverageAnalysis {

	private final List<ClassCoverage> entries;
	private final int totalTestClasses;
	private final boolean hasMemberData;

	public CoverageAnalysis(List<ClassCoverage> entries, int totalTestClasses) {
		this.entries = List.copyOf(entries);
		this.totalTestClasses = totalTestClasses;
		this.hasMemberData = entries.stream().anyMatch(ClassCoverage::hasMemberCoverage);
	}

	/** All entries, sorted least-tested first. */
	public List<ClassCoverage> entries() {
		return entries;
	}

	/** Total number of distinct test classes in the dependency map. */
	public int totalTestClasses() {
		return totalTestClasses;
	}

	/** Whether any entry has member-level coverage data. */
	public boolean hasMemberData() {
		return hasMemberData;
	}

	/** Production classes with zero exercising tests. */
	public List<ClassCoverage> untested() {
		return entries.stream().filter(c -> c.testCount() == 0).collect(Collectors.toList());
	}

	/** Production classes exercised by fewer than {@code minTests} test classes. */
	public List<ClassCoverage> belowThreshold(int minTests) {
		return entries.stream().filter(c -> c.testCount() < minTests).collect(Collectors.toList());
	}

	/** Production classes whose member coverage is below the given percent. */
	public List<ClassCoverage> memberCoverageBelow(int percent) {
		return entries.stream().filter(ClassCoverage::hasMemberCoverage)
				.filter(c -> c.memberCoveragePercent() < percent).collect(Collectors.toList());
	}

	/** Entries grouped by package name, sorted by package name. */
	public Map<String, List<ClassCoverage>> byPackage() {
		return entries.stream()
				.collect(Collectors.groupingBy(ClassCoverage::packageName, TreeMap::new, Collectors.toList()));
	}

	/** High-level statistics. */
	public Stats stats() {
		return new Stats(this);
	}

	/** Summary statistics for the analysis. */
	public static final class Stats {

		private final int totalClasses;
		private final int untestedClasses;
		private final int totalTestClasses;
		private final double avgTestsPerClass;
		private final int maxTestsPerClass;
		// member-level stats (only meaningful when hasMemberData)
		private final boolean hasMemberData;
		private final int totalMembers;
		private final int exercisedMembers;

		Stats(CoverageAnalysis analysis) {
			List<ClassCoverage> all = analysis.entries();
			this.totalClasses = all.size();
			this.totalTestClasses = analysis.totalTestClasses();
			this.untestedClasses = (int) all.stream().filter(c -> c.testCount() == 0).count();
			this.avgTestsPerClass = all.isEmpty()
					? 0
					: all.stream().mapToInt(ClassCoverage::testCount).average().orElse(0);
			this.maxTestsPerClass = all.stream().mapToInt(ClassCoverage::testCount).max().orElse(0);
			this.hasMemberData = analysis.hasMemberData();
			this.totalMembers = all.stream().mapToInt(c -> c.allMembers().size()).sum();
			this.exercisedMembers = all.stream().mapToInt(c -> c.exercisedMembers().size()).sum();
		}

		public int totalClasses() {
			return totalClasses;
		}

		public int untestedClasses() {
			return untestedClasses;
		}

		public int totalTestClasses() {
			return totalTestClasses;
		}

		public double avgTestsPerClass() {
			return avgTestsPerClass;
		}

		public int maxTestsPerClass() {
			return maxTestsPerClass;
		}

		public boolean hasMemberData() {
			return hasMemberData;
		}

		public int totalMembers() {
			return totalMembers;
		}

		public int exercisedMembers() {
			return exercisedMembers;
		}

		/** Member coverage percentage (0-100) or -1 if no member data. */
		public int memberCoveragePercent() {
			if (!hasMemberData || totalMembers == 0)
				return -1;
			return (int) (100.0 * exercisedMembers / totalMembers);
		}
	}
}
