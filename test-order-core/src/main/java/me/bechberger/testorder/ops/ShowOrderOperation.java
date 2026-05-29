package me.bechberger.testorder.ops;

import java.io.IOException;
import java.io.InputStream;
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
	/**
	 * Read at most 16 KB — annotation descriptors live in the constant pool near
	 * the file start.
	 */
	private static final int CLASS_SCAN_LIMIT = 16 * 1024;

	public static boolean looksLikeTestClass(Path classFile) {
		String name = classFile.getFileName().toString();
		String simpleName = name.substring(0, name.length() - 6); // strip .class
		// Abstract*Test classes are base classes that Surefire never runs directly.
		// They appear as "NEW" tests (not in dep map) and would otherwise flood the
		// top of the selection list with an unearned new-test bonus.
		if (simpleName.startsWith("Abstract")) {
			return false;
		}
		try (InputStream in = Files.newInputStream(classFile)) {
			byte[] buf = in.readNBytes(CLASS_SCAN_LIMIT);
			String content = new String(buf, StandardCharsets.ISO_8859_1);
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
				walk.filter(p -> {
					String name = p.toString();
					return name.endsWith(".class") && !name.contains("$");
				}).filter(ShowOrderOperation::looksLikeTestClass).filter(p -> !isSurefireDefaultExcluded(p))
						.forEach(p -> {
							String relative = testClassesDir.relativize(p).toString();
							// We know it ends with ".class" (6 chars) from the filter above
							String fqcn = relative.substring(0, relative.length() - 6).replace('/', '.').replace('\\',
									'.');
							allTests.add(fqcn);
						});
			} catch (IOException e) {
				// best effort
			}
		}
		return allTests;
	}

	/**
	 * Returns true for class files that match Maven Surefire's default excludes:
	 * {@code **‌/*IT.class} and {@code **‌/*ITCase.class}. These are integration
	 * tests run by Failsafe, not Surefire, so they have no dependency data in the
	 * index and would otherwise appear as permanently-NEW tests with a large score
	 * bonus.
	 */
	public static boolean isSurefireDefaultExcluded(Path classFile) {
		String name = classFile.getFileName().toString();
		// strip .class suffix (guaranteed present by caller's filter)
		String simple = name.substring(0, name.length() - 6);
		return simple.endsWith("IT") || simple.endsWith("ITCase");
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
			OrderReportPrinter.printShowOrderTable(out, scored, changed, changedTests, includeTags, showDepTotals,
					fullNames);
		}
	}
}
