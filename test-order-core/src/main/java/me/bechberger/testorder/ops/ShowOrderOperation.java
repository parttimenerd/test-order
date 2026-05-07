package me.bechberger.testorder.ops;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.OrderReportPrinter;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestScorer;
import me.bechberger.testorder.changes.ChangeComplexity;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;
import me.bechberger.testorder.changes.StructuralDiff;

/**
 * Computes the predicted test execution order and prints a report.
 * <p>
 * This encapsulates the shared workflow: load dependency map → build scorer
 * with structural analysis and change complexity → rank tests → print.
 */
public final class ShowOrderOperation {

	private ShowOrderOperation() {
	}

	/**
	 * Checks whether a compiled {@code .class} file looks like a JUnit test class
	 * by scanning the constant pool for JUnit test annotation descriptors.
	 */
	public static boolean looksLikeTestClass(Path classFile) {
		try {
			byte[] bytes = Files.readAllBytes(classFile);
			String content = new String(bytes, StandardCharsets.ISO_8859_1);
			return content.contains("Lorg/junit/jupiter/api/Test;")
					|| content.contains("Lorg/junit/jupiter/api/TestFactory;")
					|| content.contains("Lorg/junit/jupiter/api/RepeatedTest;")
					|| content.contains("Lorg/junit/jupiter/params/ParameterizedTest;")
					|| content.contains("Lorg/junit/Test;");
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Performs structural analysis (git diff) to obtain changed members and
	 * structural diffs. Returns {@code null} when the mode is unsupported or
	 * analysis fails.
	 */
	public static StructuralChangeAnalyzer.AnalysisResult analyzeStructuralChanges(Path projectRoot,
			String structuralDiffMode) {
		if (structuralDiffMode == null) {
			return null;
		}
		try {
			if ("since-last-commit".equalsIgnoreCase(structuralDiffMode)) {
				return StructuralChangeAnalyzer.analyzeSinceLastCommitFull(projectRoot);
			} else if ("uncommitted".equalsIgnoreCase(structuralDiffMode)) {
				return StructuralChangeAnalyzer.analyzeUncommittedFull(projectRoot);
			}
		} catch (IOException e) {
			// best effort — return null
		}
		return null;
	}

	/**
	 * Computes change-complexity scores for the given changed classes.
	 *
	 * @param changed
	 *            set of changed class names
	 * @param sourceRoots
	 *            source directories to search for changed files
	 * @param changedMembers
	 *            structural members (may be {@code null})
	 * @param structuralDiffs
	 *            structural diffs (may be {@code null})
	 * @return map of class → complexity score; empty when inputs are empty
	 */
	public static Map<String, Double> computeChangeComplexity(Set<String> changed, List<Path> sourceRoots,
			ChangedMembers changedMembers, List<StructuralDiff.FileDiff> structuralDiffs) {
		if (changed.isEmpty() || sourceRoots.isEmpty()) {
			return Map.of();
		}
		return ChangeComplexity.compute(changed, sourceRoots, changedMembers, structuralDiffs);
	}

	/**
	 * Builds a {@link TestScorer} from the given parameters.
	 */
	public static TestScorer buildScorer(TestOrderState.ScoringWeights weights, DependencyMap depMap,
			TestOrderState state, Set<String> changed, Set<String> changedTests, ChangedMembers changedMembers,
			Map<String, Double> changeComplexityMap) {
		return new TestScorer.Builder(weights, depMap, state, changed, changedTests)
				.testClassNames(depMap.testClasses()).changedMembers(changedMembers)
				.changeComplexity(changeComplexityMap).build();
	}

	/**
	 * Collects all known test classes from the dependency map, changed test set,
	 * and (optionally) a compiled test-classes directory.
	 *
	 * @param depMap
	 *            dependency map
	 * @param changedTests
	 *            additional changed test class names
	 * @param testClassesDir
	 *            compiled test output directory (may be {@code null})
	 * @return mutable set of all test class FQCNs
	 */
	public static Set<String> collectAllTests(DependencyMap depMap, Set<String> changedTests, Path testClassesDir) {
		Set<String> allTests = new LinkedHashSet<>(depMap.testClasses());
		allTests.addAll(changedTests);
		if (testClassesDir != null && Files.isDirectory(testClassesDir)) {
			try (var walk = Files.walk(testClassesDir)) {
				walk.filter(p -> p.toString().endsWith(".class") && !p.toString().contains("$"))
						.filter(ShowOrderOperation::looksLikeTestClass).forEach(p -> {
							String relative = testClassesDir.relativize(p).toString();
							String fqcn = relative.replace('/', '.').replace('\\', '.').replaceAll("\\.class$", "");
							allTests.add(fqcn);
						});
			} catch (IOException e) {
				// best effort
			}
		}
		return allTests;
	}

	/**
	 * Prints the show-order report (compact table or explain).
	 */
	public static void printReport(PrintStream out, List<OrderReportPrinter.RankedTest> scored, TestScorer scorer,
			Set<String> changed, Set<String> changedTests, TestOrderState.ScoringWeights weights, boolean explain,
			boolean includeTags, boolean showDepTotals, boolean fullNames) {
		if (explain) {
			OrderReportPrinter.printExplainReport(out, scored, scorer, changed, changedTests, weights);
		} else {
			OrderReportPrinter.printShowOrderTable(out, scored, changed, changedTests, includeTags, showDepTotals, fullNames);
		}
	}
}
