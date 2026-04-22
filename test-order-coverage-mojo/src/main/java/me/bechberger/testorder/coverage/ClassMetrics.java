package me.bechberger.testorder.coverage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Coverage metrics for a single class. Immutable value object containing all
 * coverage-related information.
 */
public class ClassMetrics implements Comparable<ClassMetrics> {
	private final String fullyQualifiedName;
	private final String module;
	private final String packageName;
	private final String className;

	private final int lineCoverage; // 0-100%
	private final int methodCoverage; // 0-100%
	private final int branchCoverage; // 0-100%

	private final int statementsCovered;
	private final int statementsTotal;
	private final int methodsCovered;
	private final int methodsTotal;

	private final int testCount;
	private final List<String> testNames;

	private final boolean isAbstract;
	private final boolean isInterface;
	private final boolean isEnum;

	public ClassMetrics(String fullyQualifiedName, String module, String packageName, String className,
			int lineCoverage, int methodCoverage, int branchCoverage, int statementsCovered, int statementsTotal,
			int methodsCovered, int methodsTotal, int testCount, List<String> testNames, boolean isAbstract,
			boolean isInterface, boolean isEnum) {

		this.fullyQualifiedName = fullyQualifiedName;
		this.module = module;
		this.packageName = packageName;
		this.className = className;
		this.lineCoverage = Math.max(0, Math.min(100, lineCoverage));
		this.methodCoverage = Math.max(0, Math.min(100, methodCoverage));
		this.branchCoverage = Math.max(0, Math.min(100, branchCoverage));
		this.statementsCovered = statementsCovered;
		this.statementsTotal = statementsTotal;
		this.methodsCovered = methodsCovered;
		this.methodsTotal = methodsTotal;
		this.testCount = testCount;
		this.testNames = new ArrayList<>(testNames != null ? testNames : new ArrayList<>());
		this.isAbstract = isAbstract;
		this.isInterface = isInterface;
		this.isEnum = isEnum;
	}

	// Getters
	public String getFullyQualifiedName() {
		return fullyQualifiedName;
	}
	public String getModule() {
		return module;
	}
	public String getPackageName() {
		return packageName;
	}
	public String getClassName() {
		return className;
	}

	public int getLineCoverage() {
		return lineCoverage;
	}
	public int getMethodCoverage() {
		return methodCoverage;
	}
	public int getBranchCoverage() {
		return branchCoverage;
	}

	public double getAverageCoverage() {
		return (lineCoverage + methodCoverage + branchCoverage) / 3.0;
	}

	public int getStatementsCovered() {
		return statementsCovered;
	}
	public int getStatementsTotal() {
		return statementsTotal;
	}
	public int getMethodsCovered() {
		return methodsCovered;
	}
	public int getMethodsTotal() {
		return methodsTotal;
	}

	public int getTestCount() {
		return testCount;
	}
	public List<String> getTestNames() {
		return new ArrayList<>(testNames);
	}

	public boolean isAbstract() {
		return isAbstract;
	}
	public boolean isInterface() {
		return isInterface;
	}
	public boolean isEnum() {
		return isEnum;
	}

	/**
	 * Whether this class should be skipped from coverage analysis. Skips abstract,
	 * interface, and enum classes.
	 */
	public boolean shouldSkip() {
		return isAbstract || isInterface || isEnum;
	}

	@Override
	public int compareTo(ClassMetrics other) {
		// Sort by line coverage ascending (least tested first)
		int cmp = Integer.compare(this.lineCoverage, other.lineCoverage);
		if (cmp != 0)
			return cmp;

		// Then by class name for consistency
		return this.fullyQualifiedName.compareTo(other.fullyQualifiedName);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		ClassMetrics that = (ClassMetrics) o;
		return Objects.equals(fullyQualifiedName, that.fullyQualifiedName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(fullyQualifiedName);
	}

	@Override
	public String toString() {
		return String.format("%s (line:%d%%, method:%d%%, branch:%d%%) - %d tests", fullyQualifiedName, lineCoverage,
				methodCoverage, branchCoverage, testCount);
	}
}
