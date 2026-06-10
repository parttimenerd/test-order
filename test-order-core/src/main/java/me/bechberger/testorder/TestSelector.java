package me.bechberger.testorder;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Selects a subset of test classes for a fast CI run:
 * <ol>
 * <li>all <em>new</em> test classes (not in the dependency index)</li>
 * <li>the top-<em>n</em> test classes by score (changed, failures, deps,
 * …)</li>
 * <li><em>m</em> random <em>fast</em> test classes chosen greedily by coverage
 * diversity (Jaccard distance against already-covered dependencies)</li>
 * </ol>
 * The remaining test classes are written to a file so a subsequent CI step can
 * run them ("run-remaining").
 */
public class TestSelector {

	/** Algorithmic parameters for the selection. */
	public record Config(int topN, int randomM, Long seed) {
		public static final Config DEFAULT = new Config(10, 5, null);
	}

	/** Result of the selection algorithm. */
	public record Selection(List<String> selected, List<String> remaining, int randomFastCount) {
	}

	/** A test class with its computed score and metadata. */
	record ScoredTest(String name, int score, long duration, boolean isNew, boolean isFast) {
	}

	private final DependencyMap depMap;
	private final TestOrderState state;
	private final Set<String> changedClasses;
	private final Set<String> changedTestClasses;
	private final TestOrderState.ScoringWeights weights;
	private final Config config;
	private final Set<String> alwaysRunClasses;
	private final Map<String, Double> changeComplexity;

	public TestSelector(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
			Set<String> changedTestClasses, TestOrderState.ScoringWeights weights, Config config) {
		this(depMap, state, changedClasses, changedTestClasses, weights, config, Set.of(), Map.of());
	}

	public TestSelector(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
			Set<String> changedTestClasses, TestOrderState.ScoringWeights weights, Config config,
			Set<String> alwaysRunClasses) {
		this(depMap, state, changedClasses, changedTestClasses, weights, config, alwaysRunClasses, Map.of());
	}

	public TestSelector(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
			Set<String> changedTestClasses, TestOrderState.ScoringWeights weights, Config config,
			Set<String> alwaysRunClasses, Map<String, Double> changeComplexity) {
		this.depMap = depMap;
		this.state = state;
		this.changedClasses = changedClasses;
		this.changedTestClasses = changedTestClasses;
		this.weights = weights;
		this.config = config;
		this.alwaysRunClasses = alwaysRunClasses != null ? alwaysRunClasses : Set.of();
		this.changeComplexity = changeComplexity != null ? changeComplexity : Map.of();
	}

	/**
	 * Runs the full selection algorithm: score → sort → pick new → pick top-N →
	 * pick diverse fast.
	 */
	public Selection select() {
		List<ScoredTest> scored = scoreAndSort();

		Set<String> selected = new LinkedHashSet<>();
		selectAlwaysRun(scored, selected);
		selectNewTests(scored, selected);
		selectTopN(scored, selected);
		int beforeRandom = selected.size();
		selectDiverseFast(scored, selected);
		int randomActual = selected.size() - beforeRandom;

		List<String> remaining = new ArrayList<>();
		for (ScoredTest s : scored) {
			if (!selected.contains(s.name()))
				remaining.add(s.name());
		}
		return new Selection(new ArrayList<>(selected), remaining, randomActual);
	}

	// ── Scoring ───────────────────────────────────────────────────────

	private List<ScoredTest> scoreAndSort() {
		Set<String> allTests = new LinkedHashSet<>(depMap.testClasses());
		allTests.addAll(changedTestClasses);
		// Ensure @AlwaysRun classes are included even if not yet in the index
		allTests.addAll(alwaysRunClasses);
		TestScorer scorer = new TestScorer.Builder(weights, depMap, state, changedClasses, changedTestClasses)
				.testClassNames(depMap.testClasses()).changeComplexity(changeComplexity).build();

		List<ScoredTest> scored = new ArrayList<>();
		for (String tc : allTests) {
			TestScorer.ScoreResult result = scorer.score(tc);
			long dur = state.getDuration(tc, -1);
			scored.add(new ScoredTest(tc, result.score(), dur >= 0 ? dur : Long.MAX_VALUE, result.isNew(),
					result.isFast()));
		}

		scored.sort(Comparator.comparing(ScoredTest::score).reversed().thenComparingLong(ScoredTest::duration)
				.thenComparing(ScoredTest::name));
		return scored;
	}

	// ── Selection phases ──────────────────────────────────────────────

	/** Phase 0: always include @AlwaysRun classes (before everything else). */
	private void selectAlwaysRun(List<ScoredTest> scored, Set<String> selected) {
		for (ScoredTest s : scored) {
			if (alwaysRunClasses.contains(s.name()))
				selected.add(s.name());
		}
	}

	/** Phase 1: always include all new tests. */
	private void selectNewTests(List<ScoredTest> scored, Set<String> selected) {
		for (ScoredTest s : scored) {
			if (s.isNew())
				selected.add(s.name());
		}
	}

	/**
	 * Phase 2: include change-affected tests, capped at topN when topN >= 0.
	 * <p>
	 * Semantics:
	 * <ul>
	 * <li>If a change signal exists (changed source classes or changed test
	 * classes), only tests whose deps overlap the changed classes — or whose own
	 * test class changed — are eligible. topN caps the count (topN=-1 = no cap).
	 * <li>If no change is detected, topN=-1 selects all tests so the run is
	 * non-empty; topN>=0 picks the top N by score.
	 * </ul>
	 * Tests already selected by earlier phases count towards the topN budget — only
	 * {@code @AlwaysRun} tests are truly additive.
	 */
	private void selectTopN(List<ScoredTest> scored, Set<String> selected) {
		boolean hasChangeSignal = !changedClasses.isEmpty() || !changedTestClasses.isEmpty();
		if (hasChangeSignal) {
			Set<String> affectedByDeps = depMap.getAffectedTests(changedClasses);
			int cap = config.topN() < 0 ? Integer.MAX_VALUE : config.topN();
			int counted = 0;
			for (ScoredTest s : scored) {
				if (counted >= cap)
					break;
				boolean isAffected = affectedByDeps.contains(s.name()) || changedTestClasses.contains(s.name());
				if (!isAffected)
					continue;
				if (alwaysRunClasses.contains(s.name()))
					continue; // additive, doesn't count
				selected.add(s.name());
				counted++;
			}
			return;
		}
		// No change detected.
		if (config.topN() < 0) {
			for (ScoredTest s : scored) {
				selected.add(s.name());
			}
			return;
		}
		int counted = 0;
		for (ScoredTest s : scored) {
			if (counted >= config.topN())
				break;
			if (alwaysRunClasses.contains(s.name()))
				continue;
			selected.add(s.name());
			counted++;
		}
	}

	/** Phase 3: greedily pick M fast tests maximizing Jaccard diversity. */
	private void selectDiverseFast(List<ScoredTest> scored, Set<String> selected) {
		List<ScoredTest> fastCandidates = new ArrayList<>();
		for (ScoredTest s : scored) {
			if (!selected.contains(s.name()) && s.isFast()) {
				fastCandidates.add(s);
			}
		}

		Random rng;
		if (config.seed() != null) {
			rng = new Random(config.seed());
		} else {
			rng = new Random();
		}
		// shuffle once up front for random tie-breaking (iteration order)
		Collections.shuffle(fastCandidates, rng);

		// Pre-cache dependency sets to avoid repeated map lookups
		Map<String, Set<String>> depsCache = new HashMap<>(fastCandidates.size());
		for (ScoredTest c : fastCandidates) {
			depsCache.put(c.name(), depMap.get(c.name()));
		}

		Set<String> coveredDeps = new HashSet<>();
		for (String tc : selected) {
			coveredDeps.addAll(depMap.get(tc));
		}

		for (int i = 0; i < config.randomM() && !fastCandidates.isEmpty(); i++) {
			int bestIdx = -1;
			double bestDist = -1;
			for (int j = 0; j < fastCandidates.size(); j++) {
				ScoredTest c = fastCandidates.get(j);
				double dist = jaccardDistance(depsCache.get(c.name()), coveredDeps);
				if (dist > bestDist) {
					bestDist = dist;
					bestIdx = j;
				}
				// maximum possible distance — no need to check remaining candidates
				if (dist == 1.0)
					break;
			}
			if (bestIdx >= 0) {
				ScoredTest best = fastCandidates.get(bestIdx);
				selected.add(best.name());
				coveredDeps.addAll(depsCache.get(best.name()));
				// O(1) swap-to-end removal
				int last = fastCandidates.size() - 1;
				if (bestIdx != last) {
					fastCandidates.set(bestIdx, fastCandidates.get(last));
				}
				fastCandidates.remove(last);
			}
		}
	}

	// ── File I/O utilities ────────────────────────────────────────────

	/** Writes one test class name per line, atomically (temp file + rename). */
	public static void writeTestList(List<String> tests, Path file) throws IOException {
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path temp = PersistenceSupport.temporarySibling(file);
		try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(temp))) {
			for (String tc : tests) {
				pw.println(tc);
			}
		}
		PersistenceSupport.moveIntoPlace(temp, file);
	}

	/** Reads one test class name per line (blank lines and # comments skipped). */
	public static List<String> readTestList(Path file) throws IOException {
		List<String> result = new ArrayList<>();
		for (String line : Files.readAllLines(file)) {
			line = line.trim();
			if (!line.isEmpty() && !line.startsWith("#"))
				result.add(line);
		}
		return result;
	}

	/** Converts a list of FQCNs to a Surefire-compatible includes pattern. */
	public static String toSurefireIncludes(List<String> testClasses) {
		return String.join(",", testClasses);
	}

	/**
	 * Jaccard distance: 1 − |A∩B| / |A∪B|. Returns 0.5 (neutral) when the test's
	 * dependency set (a) is empty (unindexed test), to avoid over-selecting unknown
	 * tests in the diversity phase. Returns 1.0 when the covered set (b) is empty.
	 */
	public static double jaccardDistance(Set<String> a, Set<String> b) {
		if (a.isEmpty())
			return 0.5; // neutral distance for unindexed tests (R15-10)
		if (b.isEmpty())
			return 1.0;
		// iterate the smaller set for O(min(|A|,|B|))
		Set<String> smaller = a.size() <= b.size() ? a : b;
		Set<String> larger = smaller == a ? b : a;
		int intersection = 0;
		for (String s : smaller) {
			if (larger.contains(s))
				intersection++;
		}
		int union = a.size() + b.size() - intersection;
		return 1.0 - (double) intersection / union;
	}
}
