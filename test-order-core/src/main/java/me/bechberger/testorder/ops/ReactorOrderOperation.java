package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestScorer;

/**
 * Computes per-module urgency scores for multi-module reactor builds, enabling
 * optimal module execution ordering (most-affected-first).
 *
 * <p>
 * In a standard Maven/Gradle multi-module build, modules execute in dependency
 * graph order. Independent modules (same DAG depth) default to declaration
 * order. This operation computes a priority score for each module based on:
 * <ul>
 * <li>Sum of test scores (affected, failed, new, etc.) within that module</li>
 * <li>Maximum individual test score (urgency signal)</li>
 * <li>Count of affected tests</li>
 * </ul>
 *
 * <p>
 * Consumers (Maven extension or Gradle task) can then reorder independent
 * modules so that the module with the highest urgency runs its tests first.
 */
public final class ReactorOrderOperation {

	private ReactorOrderOperation() {
	}

	/** Per-module urgency result. */
	public record ModuleScore(String moduleId, int maxTestScore, long sumTestScores, int affectedTestCount,
			int totalTestCount, List<String> topTests) implements Comparable<ModuleScore> {

		/**
		 * Primary sort: by affected test count (descending), then total score sum
		 * (descending), then max test score (descending), then module ID ascending.
		 * Modules with more affected tests are prioritized first because they represent
		 * broader change impact; the score sum tiebreaks at equal affected counts and
		 * reflects cumulative urgency across all affected tests in the module. The
		 * final alphabetical moduleId tiebreak makes the ordering deterministic when
		 * all three numeric keys are equal — matching the stable tie-break used by
		 * {@code ShowWorkflow.ModuleAggregate.compareByPriority} (BUG-184).
		 */
		@Override
		public int compareTo(ModuleScore other) {
			int cmp = Integer.compare(other.affectedTestCount, this.affectedTestCount);
			if (cmp != 0)
				return cmp;
			cmp = Long.compare(other.sumTestScores, this.sumTestScores);
			if (cmp != 0)
				return cmp;
			cmp = Integer.compare(other.maxTestScore, this.maxTestScore);
			if (cmp != 0)
				return cmp;
			return this.moduleId.compareTo(other.moduleId);
		}
	}

	/** Result of the reactor ordering computation. */
	public record ReactorOrderResult(List<ModuleScore> moduleScores, Set<String> changedClasses,
			Set<String> changedTests) {

		/** Returns module IDs in priority order (most urgent first). */
		public List<String> priorityOrder() {
			return moduleScores.stream().sorted().map(ModuleScore::moduleId).toList();
		}

		/** Returns only modules that have at least one affected test. */
		public List<ModuleScore> affectedModules() {
			return moduleScores.stream().filter(m -> m.affectedTestCount > 0).sorted().toList();
		}
	}

	/**
	 * Input for reactor order computation.
	 *
	 * @param indexFile
	 *            path to the shared dependency index
	 * @param stateFile
	 *            path to the shared state file
	 * @param changedClasses
	 *            set of changed production class FQCNs
	 * @param changedTests
	 *            set of changed test class FQCNs
	 * @param moduleTestDirs
	 *            map of moduleId → test-classes directory
	 * @param weights
	 *            scoring weights (null = use defaults from state)
	 * @param topN
	 *            number of top tests to include per module in result
	 * @param log
	 *            plugin logger
	 */
	public record ReactorOrderInput(Path indexFile, Path stateFile, Set<String> changedClasses,
			Set<String> changedTests, Map<String, Path> moduleTestDirs, TestOrderState.ScoringWeights weights, int topN,
			PluginLog log) {
	}

	/**
	 * Computes per-module urgency scores.
	 *
	 * @param input
	 *            the reactor order input
	 * @return ordered result with per-module scores
	 * @throws IOException
	 *             if index or state files cannot be read
	 */
	public static ReactorOrderResult compute(ReactorOrderInput input) throws IOException {
		if (!Files.exists(input.indexFile())) {
			throw new IOException(
					"Dependency index not found: " + input.indexFile() + "\nRun: mvn test -Dtestorder.mode=learn"
							+ "\nRun: mvn test-order:learn test" + "\nRun: mvn test-order:diagnose");
		}

		DependencyMap depMap = DependencyMap.load(input.indexFile());
		TestOrderState state = Files.exists(input.stateFile())
				? TestOrderState.load(input.stateFile())
				: new TestOrderState();

		TestOrderState.ScoringWeights weights = input.weights() != null ? input.weights() : state.weights();

		int topN = input.topN() > 0 ? input.topN() : 5;

		List<ModuleScore> moduleScores = new ArrayList<>();

		for (Map.Entry<String, Path> entry : input.moduleTestDirs().entrySet()) {
			String moduleId = entry.getKey();
			Path testClassesDir = entry.getValue();

			// Pre-scan: check if this module actually has compiled test classes.
			// filterToModule returns the FULL unfiltered depMap when scan finds no
			// .class files, which would incorrectly attribute all tests to this module.
			Set<String> onDiskTests = TestClassDiscovery.scanTestClasses(testClassesDir);
			if (onDiskTests.isEmpty()) {
				moduleScores.add(new ModuleScore(moduleId, 0, 0, 0, 0, List.of()));
				continue;
			}

			// Filter dependency map to this module's tests
			DependencyMap moduleDepMap = TestClassDiscovery.filterToModule(depMap, testClassesDir);
			Set<String> moduleTests = moduleDepMap.testClasses();

			if (moduleTests.isEmpty()) {
				// The dep map has no entries for this module yet (module not yet learned).
				// Use onDiskTests.size() as totalTestCount so consumers can distinguish
				// between "module truly empty" (onDiskTests.isEmpty(), caught above) and
				// "module has unlearned tests" — the operator can see the module has N
				// compiled test classes and take action (e.g. run a learn pass). (BUG-185)
				moduleScores.add(new ModuleScore(moduleId, 0, 0, 0, onDiskTests.size(), List.of()));
				continue;
			}

			// Score all tests in this module
			TestScorer scorer = new TestScorer(weights, moduleDepMap, state, input.changedClasses(),
					input.changedTests(), moduleTests);

			int maxScore = 0;
			long sumScores = 0;
			// Collapse Outer$Nested entries to their outer class before counting affected
			// tests. The dep map indexes @Nested inner test classes as separate Outer$Inner
			// FQCNs, but Surefire runs only the outer class as a single task (it discovers
			// nested tests automatically). Counting outer$inner entries separately inflates
			// affectedCount, making a module with one changed class but many @Nested tests
			// appear more urgent than one with several changed outer classes (BUG-186).
			Set<String> affectedOuterClasses = new java.util.LinkedHashSet<>();
			List<Map.Entry<String, Integer>> testScores = new ArrayList<>();

			for (String testClass : moduleTests) {
				TestScorer.ScoreResult result = scorer.score(testClass);
				int score = result.score();
				testScores.add(Map.entry(testClass, score));
				if (result.isChangeAffected()) {
					// "affected" means genuinely impacted by the change (dep/static/complexity
					// overlap, changed test, or new) — NOT merely a positive score, which a fast
					// or flaky test earns from change-independent speed/failure bonuses. Otherwise
					// a module of fast-but-unrelated tests would sort as "affected" and run first
					// for an unrelated change (BUG-173).
					// Collapse Outer$Inner → Outer so sibling @Nested classes count once (BUG-186).
					int dollar = testClass.indexOf('$');
					affectedOuterClasses.add(dollar > 0 ? testClass.substring(0, dollar) : testClass);
				}
				if (score > 0) {
					// Sum only positive scores. Negative scores (e.g. the SLOW penalty) would
					// otherwise drag the module sum below zero on modules dominated by slow but
					// unaffected tests, producing misleading "sum=-1" output and inverted
					// priority.
					sumScores += score;
				}
				if (score > maxScore) {
					maxScore = score;
				}
			}
			int affectedCount = affectedOuterClasses.size();

			// Top-N tests for display
			List<String> topTests = testScores.stream().filter(e -> e.getValue() > 0)
					.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue())).limit(topN)
					.map(e -> e.getKey() + " (score=" + e.getValue() + ")").toList();

			moduleScores
					.add(new ModuleScore(moduleId, maxScore, sumScores, affectedCount, onDiskTests.size(), topTests));
		}

		return new ReactorOrderResult(moduleScores, input.changedClasses(), input.changedTests());
	}
}
