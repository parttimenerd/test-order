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

	/**
	 * Configuration for skip-if-unchanged caching.
	 *
	 * @param enabled
	 *            when {@code true}, eligible tests are omitted from both
	 *            {@code selected} and {@code remaining} (i.e. not run at all)
	 * @param minPassStreak
	 *            minimum consecutive passing runs before a test becomes
	 *            cache-eligible (typical default: {@code 3})
	 * @param maxSkipFraction
	 *            safety cap: never skip more than this fraction of the suite
	 *            (typical default: {@code 0.9}). Set to {@code 1.0} to disable.
	 * @param quarantinedTests
	 *            tests currently in flaky-quarantine. These are never cache-skipped
	 *            even if their pass-streak qualifies them, because quarantine
	 *            downgrades failures to {@code ABORTED} which leaves the streak
	 *            artificially intact — caching such a test would mask it
	 *            permanently.
	 */
	public record CacheConfig(boolean enabled, int minPassStreak, double maxSkipFraction,
			Set<String> quarantinedTests) {
		public static final CacheConfig DISABLED = new CacheConfig(false, 3, 0.9, Set.of());

		public CacheConfig {
			if (minPassStreak < 1) {
				minPassStreak = 1;
			}
			if (Double.isNaN(maxSkipFraction) || maxSkipFraction < 0.0) {
				maxSkipFraction = 0.0;
			} else if (maxSkipFraction > 1.0) {
				maxSkipFraction = 1.0;
			}
			quarantinedTests = quarantinedTests == null ? Set.of() : Set.copyOf(quarantinedTests);
		}

		public CacheConfig(boolean enabled, int minPassStreak, double maxSkipFraction) {
			this(enabled, minPassStreak, maxSkipFraction, Set.of());
		}
	}

	/** Result of the selection algorithm. */
	public record Selection(List<String> selected, List<String> remaining, int randomFastCount, List<String> cached) {
		/** Backward-compat constructor: no cached entries. */
		public Selection(List<String> selected, List<String> remaining, int randomFastCount) {
			this(selected, remaining, randomFastCount, List.of());
		}
	}

	/** A test class with its computed score and metadata. */
	record ScoredTest(String name, int score, long duration, boolean isNew, boolean isFast, int depOverlap) {
	}

	private final DependencyMap depMap;
	private final TestOrderState state;
	private final Set<String> changedClasses;
	private final Set<String> changedTestClasses;
	private final TestOrderState.ScoringWeights weights;
	private final Config config;
	private final Set<String> alwaysRunClasses;
	private final Map<String, Double> changeComplexity;
	private final CacheConfig cacheConfig;

	public TestSelector(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
			Set<String> changedTestClasses, TestOrderState.ScoringWeights weights, Config config) {
		this(depMap, state, changedClasses, changedTestClasses, weights, config, Set.of(), Map.of(),
				CacheConfig.DISABLED);
	}

	public TestSelector(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
			Set<String> changedTestClasses, TestOrderState.ScoringWeights weights, Config config,
			Set<String> alwaysRunClasses) {
		this(depMap, state, changedClasses, changedTestClasses, weights, config, alwaysRunClasses, Map.of(),
				CacheConfig.DISABLED);
	}

	public TestSelector(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
			Set<String> changedTestClasses, TestOrderState.ScoringWeights weights, Config config,
			Set<String> alwaysRunClasses, Map<String, Double> changeComplexity) {
		this(depMap, state, changedClasses, changedTestClasses, weights, config, alwaysRunClasses, changeComplexity,
				CacheConfig.DISABLED);
	}

	public TestSelector(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
			Set<String> changedTestClasses, TestOrderState.ScoringWeights weights, Config config,
			Set<String> alwaysRunClasses, Map<String, Double> changeComplexity, CacheConfig cacheConfig) {
		this.depMap = depMap;
		this.state = state;
		this.changedClasses = changedClasses;
		this.changedTestClasses = changedTestClasses;
		this.weights = weights;
		this.config = config;
		this.alwaysRunClasses = alwaysRunClasses != null ? alwaysRunClasses : Set.of();
		this.changeComplexity = changeComplexity != null ? changeComplexity : Map.of();
		this.cacheConfig = cacheConfig != null ? cacheConfig : CacheConfig.DISABLED;
	}

	/**
	 * Runs the full selection algorithm: score → sort → pick new → pick top-N →
	 * pick diverse fast → (optionally) apply skip-if-unchanged cache.
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

		List<String> cached = applyCacheSkip(scored, selected, remaining);

		// BUG-170: @Nested inner test classes are indexed as separate Outer$Nested
		// FQCNs, but Surefire runs the OUTER class (nested tests execute as its
		// children) — SurefireHelper.configureIncludes collapses Outer$Nested -> Outer
		// before building -Dtest=. Collapse the emitted lists to outer-class form here
		// so counts reflect runnable classes and match what actually runs. Done AFTER
		// selectDiverseFast/applyCacheSkip so their per-nested depMap lookups stay
		// intact. An outer that is selected must not also appear in remaining.
		List<String> selectedOuter = collapseToOuter(selected);
		Set<String> selectedOuterSet = new LinkedHashSet<>(selectedOuter);
		List<String> remainingOuter = new ArrayList<>();
		Set<String> seenRemaining = new LinkedHashSet<>();
		for (String r : remaining) {
			String outer = TestOrderConfigResolver.toTopLevelClassName(r);
			if (!selectedOuterSet.contains(outer) && seenRemaining.add(outer)) {
				remainingOuter.add(outer);
			}
		}
		List<String> cachedOuter = collapseToOuter(cached);
		return new Selection(selectedOuter, remainingOuter, randomActual, cachedOuter);
	}

	/**
	 * Collapse a name collection to distinct outer-class form, preserving order.
	 */
	private static List<String> collapseToOuter(Collection<String> names) {
		Set<String> out = new LinkedHashSet<>();
		for (String n : names) {
			out.add(TestOrderConfigResolver.toTopLevelClassName(n));
		}
		return new ArrayList<>(out);
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
					result.isFast(), result.depOverlap()));
		}

		// Sort by score DESC, then break ties toward the more change-relevant test
		// (higher dep-overlap count) BEFORE preferring the faster one. Without the
		// dep-overlap tiebreak, a slow-but-uniquely-relevant test (e.g. the only test
		// covering a changed method) loses a score tie to a faster, less-relevant test
		// and can fall below the topN cutoff — the commons-codec Base64 MISS (BUG-161).
		scored.sort(Comparator.comparing(ScoredTest::score).reversed()
				.thenComparing(Comparator.comparingInt(ScoredTest::depOverlap).reversed())
				.thenComparingLong(ScoredTest::duration).thenComparing(ScoredTest::name));
		return scored;
	}

	// ── Selection phases ──────────────────────────────────────────────

	/** Phase 0: always include @AlwaysRun classes (before everything else). */
	private void selectAlwaysRun(List<ScoredTest> scored, Set<String> selected) {
		selected.addAll(alwaysRunClasses);
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
	 * <li>If a change signal exists (changed source classes or changed
	 * <em>known</em> test classes), only tests whose deps overlap the changed
	 * classes — or whose own test class changed — are eligible. topN caps the count
	 * (topN=-1 = no cap).
	 * <li>If no change is detected, topN=-1 selects all tests so the run is
	 * non-empty; topN>=0 picks the top N by score — tests already selected by Phase
	 * 1 (new tests) count toward the topN budget so topN is a hard cap on total
	 * selected.
	 * </ul>
	 * Unindexed new tests in {@code changedTestClasses} are excluded from the
	 * change signal — they are already picked up by Phase 1 and must not suppress
	 * the topN fallback. {@code @AlwaysRun} tests are additive (never cap-counted).
	 */
	private void selectTopN(List<ScoredTest> scored, Set<String> selected) {
		// Only truly-changed known tests trigger the change-signal path.
		// Unindexed new tests added to changedTestClasses for scoring are handled by
		// Phase 1 (selectNewTests); including them here would falsely suppress the
		// "no change detected" topN fallback when only new tests exist.
		Set<String> knownTests = depMap.testClasses();
		boolean hasChangeSignal = !changedClasses.isEmpty()
				|| changedTestClasses.stream().anyMatch(knownTests::contains);
		if (hasChangeSignal) {
			Set<String> affectedByDeps = depMap.getAffectedTests(changedClasses);
			int cap = config.topN() < 0 ? Integer.MAX_VALUE : config.topN();
			// BUG-170: budget by outer class — nested siblings that collapse to one
			// runnable outer class (see configureIncludes) must not each consume a slot.
			Set<String> selectedOuter = outerSet(selected);
			int counted = selectedOuter.size();
			for (ScoredTest s : scored) {
				if (counted >= cap)
					break;
				boolean isAffected = affectedByDeps.contains(s.name()) || changedTestClasses.contains(s.name());
				if (!isAffected)
					continue;
				if (alwaysRunClasses.contains(s.name()))
					continue; // additive, doesn't count
				// M19: only count toward the budget when the element is actually new
				// (selected.add returns false for duplicates added by earlier phases)
				boolean added = selected.add(s.name());
				if (added && selectedOuter.add(TestOrderConfigResolver.toTopLevelClassName(s.name()))) {
					counted++;
				}
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
		// Count tests already selected by Phase 1 (new tests) toward the topN budget
		// so that topN acts as a hard cap on total selected tests. BUG-170: count by
		// outer class so nested siblings collapse to one budget unit.
		Set<String> selectedOuter = outerSet(selected.stream().filter(t -> !alwaysRunClasses.contains(t))
				.collect(java.util.stream.Collectors.toSet()));
		int counted = selectedOuter.size();
		for (ScoredTest s : scored) {
			if (counted >= config.topN())
				break;
			if (alwaysRunClasses.contains(s.name()))
				continue;
			boolean added = selected.add(s.name());
			if (added && selectedOuter.add(TestOrderConfigResolver.toTopLevelClassName(s.name()))) {
				counted++;
			}
		}
	}

	/** Map a name collection to the set of distinct outer-class names. */
	private static Set<String> outerSet(Collection<String> names) {
		Set<String> out = new LinkedHashSet<>();
		for (String n : names) {
			out.add(TestOrderConfigResolver.toTopLevelClassName(n));
		}
		return out;
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

	/**
	 * Phase 4 (optional): skip-if-unchanged cache. A test is cache-skippable iff
	 * (1) it is not in {@code alwaysRunClasses}, (2) it is not affected by the
	 * change set via {@link DependencyMap#getAffectedTests(Set)} and was not itself
	 * changed, and (3) its consecutive pass streak in
	 * {@link TestOrderState#passStreak(String)} is at least
	 * {@link CacheConfig#minPassStreak()}. The total number of skipped tests is
	 * capped at {@code floor(maxSkipFraction * totalCandidates)}. When the cap
	 * binds, slower tests (by EMA duration) are preferred for skipping so that
	 * skipping saves the most wall-clock time.
	 * <p>
	 * Cached tests are removed from both {@code selected} and {@code remaining} (in
	 * place). The returned list is sorted by name for deterministic output.
	 */
	private List<String> applyCacheSkip(List<ScoredTest> scored, Set<String> selected, List<String> remaining) {
		if (!cacheConfig.enabled()) {
			return List.of();
		}
		Set<String> affectedByDeps = depMap.getAffectedTests(changedClasses);
		Set<String> quarantined = cacheConfig.quarantinedTests();
		List<ScoredTest> eligible = new ArrayList<>();
		for (ScoredTest s : scored) {
			if (alwaysRunClasses.contains(s.name()))
				continue;
			if (affectedByDeps.contains(s.name()) || changedTestClasses.contains(s.name()))
				continue;
			if (s.isNew())
				continue;
			if (quarantined.contains(s.name()))
				continue;
			if (state.passStreak(s.name()) < cacheConfig.minPassStreak())
				continue;
			eligible.add(s);
		}

		int totalCandidates = scored.size();
		int cap = (int) Math.floor(cacheConfig.maxSkipFraction() * totalCandidates);
		if (cap <= 0) {
			return List.of();
		}
		if (eligible.size() > cap) {
			// Prefer slower tests for skipping (greater wall-clock savings).
			// Unknown durations (Long.MAX_VALUE) sort to the top — also good to skip.
			eligible.sort(Comparator.comparingLong(ScoredTest::duration).reversed().thenComparing(ScoredTest::name));
			eligible = new ArrayList<>(eligible.subList(0, cap));
		}

		List<String> cached = new ArrayList<>(eligible.size());
		for (ScoredTest s : eligible) {
			cached.add(s.name());
		}
		Set<String> cachedSet = new HashSet<>(cached);
		selected.removeIf(cachedSet::contains);
		remaining.removeIf(cachedSet::contains);

		Collections.sort(cached);
		return cached;
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
