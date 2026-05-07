package me.bechberger.testorder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Splits test classes into three tiers for progressive CI execution:
 * <ol>
 * <li><b>Tier 1 (change-affected)</b>: tests with dependency overlap on changed
 * code, changed test classes, new tests, and {@code @AlwaysRun} tests.</li>
 * <li><b>Tier 2 (top-scored remaining)</b>: from the remaining tests, ordered
 * by score, selected until cumulative expected duration reaches
 * {@code tier2Fraction} of total remaining duration (or by count if
 * duration-weighting is disabled).</li>
 * <li><b>Tier 3 (the rest)</b>: all remaining tests.</li>
 * </ol>
 *
 * <p>
 * Execution contract: tier 2 only runs if tier 1 passes; tier 3 only runs if
 * tiers 1 and 2 pass.
 */
public class TieredTestSelector {

	/** Configuration for tiered selection. */
	public record Config(double tier2Fraction, boolean weightByDuration) {
		public static final Config DEFAULT = new Config(0.5, true);

		public Config {
			if (tier2Fraction < 0 || tier2Fraction > 1) {
				throw new IllegalArgumentException("tier2Fraction must be in [0, 1]: " + tier2Fraction);
			}
		}
	}

	/** Result of tiered selection — three ordered lists. */
	public record TieredSelection(List<String> tier1, List<String> tier2, List<String> tier3) {

		/** Returns all tests in tier order. */
		public List<String> allInOrder() {
			List<String> all = new ArrayList<>(tier1.size() + tier2.size() + tier3.size());
			all.addAll(tier1);
			all.addAll(tier2);
			all.addAll(tier3);
			return all;
		}
	}

	/** A test class with its computed score and metadata. */
	record ScoredTest(String name, int score, long duration, boolean isNew, boolean isChanged, boolean isFast,
			int depOverlap) {
	}

	private final DependencyMap depMap;
	private final TestOrderState state;
	private final Set<String> changedClasses;
	private final Set<String> changedTestClasses;
	private final TestOrderState.ScoringWeights weights;
	private final Config config;
	private final Set<String> alwaysRunClasses;

	public TieredTestSelector(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
			Set<String> changedTestClasses, TestOrderState.ScoringWeights weights, Config config,
			Set<String> alwaysRunClasses) {
		this.depMap = depMap;
		this.state = state;
		this.changedClasses = changedClasses;
		this.changedTestClasses = changedTestClasses;
		this.weights = weights;
		this.config = config;
		this.alwaysRunClasses = alwaysRunClasses != null ? alwaysRunClasses : Set.of();
	}

	/**
	 * Runs the full tiered selection algorithm.
	 */
	public TieredSelection select() {
		List<ScoredTest> scored = scoreAndSort();

		// ── Tier 1: change-affected ─────────────────────────────────
		Set<String> tier1Set = new LinkedHashSet<>();
		for (ScoredTest s : scored) {
			if (alwaysRunClasses.contains(s.name())) {
				tier1Set.add(s.name());
			}
		}
		for (ScoredTest s : scored) {
			if (s.isNew() || s.isChanged() || s.depOverlap() > 0) {
				tier1Set.add(s.name());
			}
		}

		// ── Remaining tests (not in tier 1), still sorted by score ──
		List<ScoredTest> remaining = new ArrayList<>();
		for (ScoredTest s : scored) {
			if (!tier1Set.contains(s.name())) {
				remaining.add(s);
			}
		}

		// ── Tier 2: top-scored fraction of remaining ────────────────
		List<String> tier2 = selectTier2(remaining);
		Set<String> tier2Set = new LinkedHashSet<>(tier2);

		// ── Tier 3: everything else ─────────────────────────────────
		List<String> tier3 = new ArrayList<>();
		for (ScoredTest s : remaining) {
			if (!tier2Set.contains(s.name())) {
				tier3.add(s.name());
			}
		}

		return new TieredSelection(new ArrayList<>(tier1Set), tier2, tier3);
	}

	// ── Scoring ───────────────────────────────────────────────────────

	private List<ScoredTest> scoreAndSort() {
		Set<String> allTests = new LinkedHashSet<>(depMap.testClasses());
		allTests.addAll(changedTestClasses);

		TestScorer scorer = new TestScorer.Builder(weights, depMap, state, changedClasses, changedTestClasses)
				.testClassNames(depMap.testClasses()).build();

		List<ScoredTest> scored = new ArrayList<>();
		for (String tc : allTests) {
			TestScorer.ScoreResult result = scorer.score(tc);
			long dur = state.getDuration(tc, -1);
			scored.add(new ScoredTest(tc, result.score(), dur >= 0 ? dur : Long.MAX_VALUE, result.isNew(),
					result.isChanged(), result.isFast(), result.depOverlap()));
		}

		scored.sort(Comparator.comparing(ScoredTest::score).reversed().thenComparingLong(ScoredTest::duration)
				.thenComparing(ScoredTest::name));
		return scored;
	}

	// ── Tier 2 selection ──────────────────────────────────────────────

	/**
	 * Selects the tier 2 subset from remaining tests. If
	 * {@code weightByDuration=true}, selects from the top by score until the
	 * cumulative expected duration reaches {@code tier2Fraction} of the total
	 * remaining duration. Otherwise, selects the top {@code tier2Fraction} by
	 * count.
	 */
	private List<String> selectTier2(List<ScoredTest> remaining) {
		if (remaining.isEmpty() || config.tier2Fraction() == 0) {
			return List.of();
		}
		if (config.tier2Fraction() >= 1.0) {
			List<String> all = new ArrayList<>();
			for (ScoredTest s : remaining) {
				all.add(s.name());
			}
			return all;
		}

		if (config.weightByDuration()) {
			return selectByDurationFraction(remaining);
		} else {
			return selectByCountFraction(remaining);
		}
	}

	/**
	 * Select tests from the top of the score-sorted list until cumulative duration
	 * reaches the target fraction. This favors fast tests — you get more fast tests
	 * in the same duration budget.
	 */
	private List<String> selectByDurationFraction(List<ScoredTest> remaining) {
		long totalDuration = 0;
		boolean anyKnown = false;
		for (ScoredTest s : remaining) {
			if (s.duration() != Long.MAX_VALUE) {
				totalDuration += s.duration();
				anyKnown = true;
			}
		}

		if (!anyKnown) {
			// No duration data — fall back to count-based
			return selectByCountFraction(remaining);
		}

		long budget = (long) (totalDuration * config.tier2Fraction());
		long spent = 0;
		List<String> tier2 = new ArrayList<>();
		// Estimate unknown durations as the average of known ones (R12-5)
		long knownCount = remaining.stream().filter(s -> s.duration() != Long.MAX_VALUE).count();
		long avgDuration = knownCount > 0 ? totalDuration / knownCount : 0;
		for (ScoredTest s : remaining) {
			long dur = s.duration() != Long.MAX_VALUE ? s.duration() : avgDuration;
			if (spent + dur > budget && !tier2.isEmpty()) {
				break;
			}
			tier2.add(s.name());
			spent += dur;
		}
		return tier2;
	}

	/** Select the top fraction by count. */
	private List<String> selectByCountFraction(List<ScoredTest> remaining) {
		int count = Math.max(1, (int) Math.ceil(remaining.size() * config.tier2Fraction()));
		List<String> tier2 = new ArrayList<>();
		for (int i = 0; i < count && i < remaining.size(); i++) {
			tier2.add(remaining.get(i).name());
		}
		return tier2;
	}

	// ── File I/O utilities ────────────────────────────────────────────

	/** Writes tier files using the shared TestSelector I/O. */
	public static void writeTierFiles(TieredSelection selection, Path tier1File, Path tier2File, Path tier3File)
			throws IOException {
		if (tier1File != null) {
			TestSelector.writeTestList(selection.tier1(), tier1File);
		}
		if (tier2File != null) {
			TestSelector.writeTestList(selection.tier2(), tier2File);
		}
		if (tier3File != null) {
			TestSelector.writeTestList(selection.tier3(), tier3File);
		}
	}
}
