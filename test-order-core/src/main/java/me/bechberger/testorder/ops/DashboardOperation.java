package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import me.bechberger.testorder.DashboardGenerator;
import me.bechberger.testorder.DashboardGenerator.ScoredTest;
import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestScorer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;
import me.bechberger.testorder.changes.StructuralDiff;

/**
 * Assembles and writes the self-contained HTML dashboard.
 * <p>
 * This encapsulates the shared workflow used by both the Maven
 * {@code dashboard} mojo and the Gradle {@code testOrderDashboard} task: load
 * data → score tests → build JSON model → inject into HTML template → write
 * output.
 */
public final class DashboardOperation {

	private DashboardOperation() {
	}

	/**
	 * Scores all test classes and sorts them by descending score.
	 *
	 * @param allTests
	 *            fully-qualified test class names
	 * @param scorer
	 *            configured scorer
	 * @param state
	 *            test-order state (for durations)
	 * @return sorted list of scored tests
	 */
	public static List<ScoredTest> scoreAndSort(Collection<String> allTests, TestScorer scorer, TestOrderState state) {
		List<ScoredTest> scored = new ArrayList<>();
		for (String testClass : allTests) {
			TestScorer.ScoreResult r = scorer.score(testClass);
			long dur = state.getDuration(testClass, -1);
			double var = state.getDurationVariance(testClass, -1.0);
			scored.add(new ScoredTest(testClass, r, dur, var));
		}
		scored.sort(Comparator.<ScoredTest, Integer>comparing(s -> s.result().score()).reversed()
				.thenComparingLong(s -> s.duration() >= 0 ? s.duration() : Long.MAX_VALUE)
				.thenComparing(ScoredTest::name));
		return scored;
	}

	/**
	 * Computes the median duration from scored tests.
	 */
	public static long computeMedianDuration(List<ScoredTest> scored) {
		return DashboardGenerator.computeMedian(
				scored.stream().filter(s -> s.duration() >= 0).mapToLong(ScoredTest::duration).toArray());
	}
}
