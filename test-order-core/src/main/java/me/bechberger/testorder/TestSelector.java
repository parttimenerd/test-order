package me.bechberger.testorder;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Selects a subset of test classes for a fast CI run:
 * <ol>
 *   <li>all <em>new</em> test classes (not in the dependency index)</li>
 *   <li>the top-<em>n</em> test classes by score (changed, failures, deps, …)</li>
 *   <li><em>m</em> random <em>fast</em> test classes chosen greedily by coverage diversity
 *       (Jaccard distance against already-covered dependencies)</li>
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
    public record Selection(List<String> selected, List<String> remaining) {}

    /** A test class with its computed score and metadata. */
    record ScoredTest(String name, int score, long duration, boolean isNew, boolean isFast) {}

    private final DependencyMap depMap;
    private final TestOrderState state;
    private final Set<String> changedClasses;
    private final Set<String> changedTestClasses;
    private final TestOrderState.ScoringWeights weights;
    private final Config config;

    public TestSelector(DependencyMap depMap,
                        TestOrderState state,
                        Set<String> changedClasses,
                        Set<String> changedTestClasses,
                        TestOrderState.ScoringWeights weights,
                        Config config) {
        this.depMap = depMap;
        this.state = state;
        this.changedClasses = changedClasses;
        this.changedTestClasses = changedTestClasses;
        this.weights = weights;
        this.config = config;
    }

    /**
     * Runs the full selection algorithm: score → sort → pick new → pick top-N → pick diverse fast.
     */
    public Selection select() {
        List<ScoredTest> scored = scoreAndSort();

        Set<String> selected = new LinkedHashSet<>();
        selectNewTests(scored, selected);
        selectTopN(scored, selected);
        selectDiverseFast(scored, selected);

        List<String> remaining = new ArrayList<>();
        for (ScoredTest s : scored) {
            if (!selected.contains(s.name())) remaining.add(s.name());
        }
        return new Selection(new ArrayList<>(selected), remaining);
    }

    // ── Scoring ───────────────────────────────────────────────────────

    private List<ScoredTest> scoreAndSort() {
        Set<String> allTests = new LinkedHashSet<>(depMap.testClasses());
        allTests.addAll(changedTestClasses);

        TestScorer scorer = new TestScorer.Builder(weights, depMap, state,
                changedClasses, changedTestClasses)
                .testClassNames(depMap.testClasses())
                .build();

        List<ScoredTest> scored = new ArrayList<>();
        for (String tc : allTests) {
            TestScorer.ScoreResult result = scorer.score(tc);
            long dur = state.getDuration(tc, -1);
            scored.add(new ScoredTest(tc, result.score(), dur >= 0 ? dur : Long.MAX_VALUE,
                    result.isNew(), result.isFast()));
        }

        scored.sort(Comparator
                .comparing(ScoredTest::score).reversed()
                .thenComparingLong(ScoredTest::duration)
                .thenComparing(ScoredTest::name));
        return scored;
    }

    // ── Selection phases ──────────────────────────────────────────────

    /** Phase 1: always include all new tests. */
    private void selectNewTests(List<ScoredTest> scored, Set<String> selected) {
        for (ScoredTest s : scored) {
            if (s.isNew()) selected.add(s.name());
        }
    }

    /** Phase 2: include the top-N highest-scored tests. */
    private void selectTopN(List<ScoredTest> scored, Set<String> selected) {
        int added = 0;
        for (ScoredTest s : scored) {
            if (added >= config.topN()) break;
            if (selected.add(s.name())) added++;
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

        Random rng = config.seed() != null ? new Random(config.seed()) : new Random();
        // shuffle once up front for random tie-breaking (iteration order)
        Collections.shuffle(fastCandidates, rng);

        Set<String> coveredDeps = new HashSet<>();
        for (String tc : selected) {
            coveredDeps.addAll(depMap.get(tc));
        }

        for (int i = 0; i < config.randomM() && !fastCandidates.isEmpty(); i++) {
            int bestIdx = -1;
            double bestDist = -1;
            for (int j = 0; j < fastCandidates.size(); j++) {
                ScoredTest c = fastCandidates.get(j);
                double dist = jaccardDistance(depMap.get(c.name()), coveredDeps);
                if (dist > bestDist) {
                    bestDist = dist;
                    bestIdx = j;
                }
            }
            if (bestIdx >= 0) {
                ScoredTest best = fastCandidates.get(bestIdx);
                selected.add(best.name());
                coveredDeps.addAll(depMap.get(best.name()));
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

    /** Writes one test class name per line. */
    public static void writeTestList(List<String> tests, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file))) {
            for (String tc : tests) {
                pw.println(tc);
            }
        }
    }

    /** Reads one test class name per line (blank lines and # comments skipped). */
    public static List<String> readTestList(Path file) throws IOException {
        List<String> result = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) result.add(line);
        }
        return result;
    }

    /** Converts a list of FQCNs to a Surefire-compatible includes pattern. */
    public static String toSurefireIncludes(List<String> testClasses) {
        return String.join(",", testClasses);
    }

    /** Jaccard distance: 1 − |A∩B| / |A∪B|.  Returns 1.0 when either set is empty. */
    static double jaccardDistance(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 1.0;
        // iterate the smaller set for O(min(|A|,|B|))
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger  = smaller == a ? b : a;
        int intersection = 0;
        for (String s : smaller) {
            if (larger.contains(s)) intersection++;
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 1.0 : 1.0 - (double) intersection / union;
    }
}
