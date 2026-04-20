package me.bechberger.testorder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import me.bechberger.util.json.Util;

/**
 * Unified test-order state file: scoring weights, durations, failure history,
 * and capped run history.  Replaces the former FailedTestStore, TestDurationStore,
 * and RunHistoryStore with a single {@code .test-order-state} file.
 * <p>
 * Also serves as a static coordinator: {@link PriorityClassOrderer} writes
 * per-test score breakdowns during ordering; {@link TelemetryListener} reads
 * them at the end of the run and appends a complete run record to the state file.
 */
public class TestOrderState {

    static final Logger LOG = Logger.getLogger(TestOrderState.class.getName());
    private static final int CURRENT_SCHEMA_VERSION = 1;

    // ── Scoring weights ───────────────────────────────────────────────

    /** Definition of a single weight: name, default, optimizer range. */
    public record WeightDef(String name, int defaultValue, int min, int max) {
        public WeightDef {
            if (min > max) {
                throw new IllegalArgumentException("Invalid weight range for " + name + ": min > max");
            }
        }
    }

    /** Canonical weight order — defines the sequence for arrays and iteration. */
    static final List<String> WEIGHT_ORDER = List.of(
            "newTest", "changedTest", "maxFailure", "speed", "speedPenalty", "depOverlap",
            "changeComplexity", "staticFieldBonus", "coverageBonus");

    /** Ordered weight definitions loaded from the {@code default-scoring-weights.toml} resource. */
    public static final List<WeightDef> WEIGHT_DEFS;

    /** Resource-loaded default for class-level failure decay per run. */
    static final double DEFAULT_FAILURE_DECAY;

    /** Resource-loaded default for method-level failure decay per run. */
    static final double DEFAULT_METHOD_FAILURE_DECAY;

    /** Resource-loaded default for class-level duration EMA alpha. */
    static final double DEFAULT_DURATION_ALPHA;

    /** Resource-loaded default for method-level duration EMA alpha. */
    static final double DEFAULT_METHOD_DURATION_ALPHA;

    /** Resource-loaded default for pruning threshold of negligible failure scores. */
    static final double DEFAULT_FAILURE_PRUNE_THRESHOLD;

    /** Resource-loaded default for EMA variance threshold in adaptive alpha calculation. */
    static final double DEFAULT_EMA_VARIANCE_THRESHOLD;

    /** Resource-loaded default for maximum run history size. */
    static final int DEFAULT_HISTORY_MAX_RUNS;

    /** Resource-loaded default for minimum adaptive alpha factor. */
    static final double DEFAULT_MIN_ADAPTIVE_ALPHA_FACTOR;

    static {
        CommentedConfig resourceConfig = loadResourceConfig();
        WEIGHT_DEFS = Collections.unmodifiableList(buildWeightDefs(resourceConfig));
        DEFAULT_FAILURE_DECAY = readConfigDouble(resourceConfig, "failureDecay");
        DEFAULT_METHOD_FAILURE_DECAY = readConfigDouble(resourceConfig, "methodFailureDecay");
        DEFAULT_DURATION_ALPHA = readConfigDouble(resourceConfig, "durationAlpha");
        DEFAULT_METHOD_DURATION_ALPHA = readConfigDouble(resourceConfig, "methodDurationAlpha");
        DEFAULT_FAILURE_PRUNE_THRESHOLD = readConfigDouble(resourceConfig, "failurePruneThreshold");
        DEFAULT_EMA_VARIANCE_THRESHOLD = readConfigDouble(resourceConfig, "emaVarianceThreshold");
        DEFAULT_HISTORY_MAX_RUNS = readConfigInt(resourceConfig, "historyMaxRuns");
        DEFAULT_MIN_ADAPTIVE_ALPHA_FACTOR = readConfigDouble(resourceConfig, "minAdaptiveAlphaFactor");
    }

    private static CommentedConfig loadResourceConfig() {
        try (InputStream is = TestOrderState.class.getResourceAsStream("/default-scoring-weights.toml")) {
            return new TomlParser().parse(is);
        } catch (IOException e) {
            throw new IllegalStateException("default-scoring-weights.toml missing from resources", e);
        }
    }

    private static List<WeightDef> buildWeightDefs(CommentedConfig config) {
        Map<String, WeightDef> parsed = new LinkedHashMap<>();
        for (WeightDef d : parseWeightDefs(config)) {
            parsed.put(d.name(), d);
        }
        List<WeightDef> defs = new ArrayList<>();
        for (String name : WEIGHT_ORDER) {
            defs.add(Objects.requireNonNull(parsed.get(name),
                    "weight '" + name + "' missing from default-scoring-weights.toml"));
        }
        return defs;
    }

    private static int readConfigInt(CommentedConfig root, String key) {
        CommentedConfig cfg = root.get("config");
        return ((Number) cfg.get(key)).intValue();
    }

    private static int readConfigInt(CommentedConfig root, String key, int fallback) {
        if (root.contains("config")) {
            CommentedConfig cfg = root.get("config");
            if (cfg.contains(key)) return ((Number) cfg.get(key)).intValue();
        }
        return fallback;
    }

    private static double readConfigDouble(CommentedConfig root, String key) {
        CommentedConfig cfg = root.get("config");
        return ((Number) cfg.get(key)).doubleValue();
    }

    private static double readConfigDouble(CommentedConfig root, String key, double fallback) {
        if (root.contains("config")) {
            CommentedConfig cfg = root.get("config");
            if (cfg.contains(key)) return ((Number) cfg.get(key)).doubleValue();
        }
        return fallback;
    }

    /**
     * Parses weight definitions from a TOML config.
     * Supports table format with {@code min}/{@code max}: {@code [name] value=15 min=0 max=50},
     * legacy {@code range} array: {@code [name] value=15 range=[0, 50]},
     * and simple format: {@code name = 15} (range unset, inherits from defaults).
     */
    @SuppressWarnings("unchecked")
    static List<WeightDef> parseWeightDefs(CommentedConfig config) {
        List<WeightDef> defs = new ArrayList<>();
        for (var entry : config.entrySet()) {
            String name = entry.getKey();
            if ("config".equals(name)) continue;
            Object val = entry.getValue();
            if (val instanceof CommentedConfig table) {
                // skip nested group tables (e.g. [method-scores]) that don't have a direct "value" key
                if (!table.contains("value")) continue;
                int value = ((Number) table.get("value")).intValue();
                int min = -1, max = -1;
                if (table.contains("min")) min = ((Number) table.get("min")).intValue();
                if (table.contains("max")) max = ((Number) table.get("max")).intValue();
                if (min == -1 && max == -1 && table.contains("range")) {
                    List<Number> range = table.get("range");
                    min = range.get(0).intValue();
                    max = range.get(1).intValue();
                }
                defs.add(new WeightDef(name, value, min, max));
            } else if (val instanceof Number n) {
                defs.add(new WeightDef(name, n.intValue(), -1, -1));
            }
        }
        return defs;
    }

    public record ScoringWeights(int newTest, int changedTest, int maxFailure,
                                 int speed, int speedPenalty, int depOverlap,
                                 int changeComplexity, int staticFieldBonus,
                                 int coverageBonus) {

        public ScoringWeights(int newTest, int changedTest, int maxFailure,
                              int speed, int speedPenalty, int depOverlap,
                              int changeComplexity, int staticFieldBonus) {
            this(newTest, changedTest, maxFailure, speed, speedPenalty, depOverlap,
                    changeComplexity, staticFieldBonus, 0);
        }

        public ScoringWeights(int newTest, int changedTest, int maxFailure,
                              int speed, int speedPenalty, int depOverlap,
                              int changeComplexity) {
            this(newTest, changedTest, maxFailure, speed, speedPenalty, depOverlap,
                    changeComplexity, 0, 0);
        }

        public static final ScoringWeights DEFAULT;

        static {
            Map<String, Integer> defaults = new LinkedHashMap<>();
            for (WeightDef d : WEIGHT_DEFS) defaults.put(d.name(), d.defaultValue());
            DEFAULT = new ScoringWeights(
                    defaults.get("newTest"), defaults.get("changedTest"),
                    defaults.get("maxFailure"), defaults.get("speed"),
                    defaults.get("speedPenalty"), defaults.get("depOverlap"),
                    defaults.get("changeComplexity"), defaults.get("staticFieldBonus"),
                    defaults.get("coverageBonus"));
        }

        /** Build weights from a name→value map; missing keys use resource defaults. */
        public static ScoringWeights fromMap(Map<String, Integer> map) {
            Map<String, Integer> merged = new LinkedHashMap<>(DEFAULT.toMap());
            merged.putAll(map);
            return new ScoringWeights(
                    merged.get("newTest"), merged.get("changedTest"),
                    merged.get("maxFailure"), merged.get("speed"),
                    merged.get("speedPenalty"), merged.get("depOverlap"),
                    merged.get("changeComplexity"), merged.get("staticFieldBonus"),
                    merged.get("coverageBonus"));
        }

        /** Convert to an ordered name→value map (same order as WEIGHT_DEFS). */
        public Map<String, Integer> toMap() {
            Map<String, Integer> map = new LinkedHashMap<>();
            map.put("newTest", newTest);
            map.put("changedTest", changedTest);
            map.put("maxFailure", maxFailure);
            map.put("speed", speed);
            map.put("speedPenalty", speedPenalty);
            map.put("depOverlap", depOverlap);
            map.put("changeComplexity", changeComplexity);
            map.put("staticFieldBonus", staticFieldBonus);
            map.put("coverageBonus", coverageBonus);
            return map;
        }

        /** Convert to array in WEIGHT_DEFS order. */
        public int[] toArray() {
            return new int[]{newTest, changedTest, maxFailure, speed, speedPenalty,
                    depOverlap, changeComplexity, staticFieldBonus, coverageBonus};
        }

        /** Human-readable key=value format. */
        public String format() {
            StringBuilder sb = new StringBuilder();
            for (var e : toMap().entrySet()) {
                if (!sb.isEmpty()) sb.append("  ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }

        /** Build from array in WEIGHT_DEFS order. */
        public static ScoringWeights fromArray(int[] a) {
            return new ScoringWeights(a[0], a[1], a[2], a[3], a[4], a[5],
                    a.length > 6 ? a[6] : DEFAULT.changeComplexity(),
                    a.length > 7 ? a[7] : DEFAULT.staticFieldBonus(),
                    a.length > 8 ? a[8] : DEFAULT.coverageBonus());
        }

        /**
         * Load from a user-provided TOML weights file and merge with defaults.
         * Supports both simple ({@code newTest = 20}) and table format
         * ({@code [newTest] value = 20 min = 5 max = 30}).
         * Also updates WEIGHT_DEFS ranges if the user overrides min/max.
         */
        public static LoadedWeights loadFromFile(Path file) throws IOException {
            try (var reader = Files.newBufferedReader(file)) {
                CommentedConfig config = new TomlParser().parse(reader);
                return mergeWithDefaults(config);
            }
        }

        /** Merge a user config on top of defaults. */
        static LoadedWeights mergeWithDefaults(CommentedConfig userConfig) {
            Map<String, WeightDef> userDefs = new LinkedHashMap<>();
            for (WeightDef d : parseWeightDefs(userConfig)) {
                userDefs.put(d.name(), d);
            }

            Map<String, Integer> values = new LinkedHashMap<>(DEFAULT.toMap());
            userDefs.forEach((name, d) -> values.put(name, d.defaultValue()));

            // merge: user range overrides default range where provided
            List<WeightDef> mergedDefs = new ArrayList<>();
            for (WeightDef def : WEIGHT_DEFS) {
                WeightDef userDef = userDefs.get(def.name());
                int v = values.getOrDefault(def.name(), def.defaultValue());
                int min = (userDef != null && userDef.min() >= 0) ? userDef.min() : def.min();
                int max = (userDef != null && userDef.max() >= 0) ? userDef.max() : def.max();
                mergedDefs.add(new WeightDef(def.name(), v, min, max));
            }

            return new LoadedWeights(fromMap(values), Collections.unmodifiableList(mergedDefs),
                    readConfigDouble(userConfig, "failureDecay", DEFAULT_FAILURE_DECAY),
                    readConfigDouble(userConfig, "methodFailureDecay", DEFAULT_METHOD_FAILURE_DECAY),
                    readConfigDouble(userConfig, "durationAlpha",
                            readConfigDouble(userConfig, "emaAlpha", DEFAULT_DURATION_ALPHA)),
                    readConfigDouble(userConfig, "methodDurationAlpha",
                            readConfigDouble(userConfig, "emaAlpha", DEFAULT_METHOD_DURATION_ALPHA)),
                    readConfigDouble(userConfig, "failurePruneThreshold", DEFAULT_FAILURE_PRUNE_THRESHOLD));
        }

        /** Save to a standalone TOML weights file. */
        public void saveToFile(Path file) throws IOException {
            saveToFile(file, WEIGHT_DEFS);
        }

        /** Save to a standalone TOML weights file with the given weight definitions (for ranges). */
        public void saveToFile(Path file, List<WeightDef> defs) throws IOException {
            Files.createDirectories(file.getParent());
            CommentedConfig config = CommentedConfig.inMemory();
            Map<String, Integer> values = toMap();
            for (WeightDef wd : defs) {
                List<String> path = List.of(wd.name());
                CommentedConfig table = CommentedConfig.inMemory();
                table.set("value", values.getOrDefault(wd.name(), wd.defaultValue()));
                table.set("range", List.of(wd.min(), wd.max()));
                config.set(path, table);
            }
            try (var writer = Files.newBufferedWriter(file)) {
                new TomlWriter().write(config, writer);
            }
        }
    }

    /** Result of loading a user weights file: weights, merged defs, and config overrides. */
    public record LoadedWeights(ScoringWeights weights, List<WeightDef> defs,
                                double failureDecay, double methodFailureDecay,
                                double durationAlpha, double methodDurationAlpha,
                                double failurePruneThreshold) {}

    /**
     * Method-level scoring weights.
     * Separate from class-level weights to allow different prioritization logic.
     */
    public record MethodScoringWeights(double failureRecency, double fast, double slow,
                                        double depOverlap, double newMethod, double changedMethod,
                                        double coverageBonus) {

        /** Backward-compat constructor (coverageBonus defaults to 0). */
        public MethodScoringWeights(double failureRecency, double fast, double slow,
                                    double depOverlap, double newMethod, double changedMethod) {
            this(failureRecency, fast, slow, depOverlap, newMethod, changedMethod, 0.0);
        }

        public static final MethodScoringWeights DEFAULT = new MethodScoringWeights(3.0, 1.0, 1.0, 2.0, 5.0, 3.0, 0.0);

        public static MethodScoringWeights fromMap(Map<String, ? extends Number> map) {
            double fr = 3.0, f = 1.0, s = 1.0, d = 2.0, nm = 5.0, cm = 3.0, cb = 0.0;
            if (map.containsKey("failureRecency")) {
                fr = map.get("failureRecency").doubleValue();
            }
            if (map.containsKey("fast")) {
                f = map.get("fast").doubleValue();
            }
            if (map.containsKey("slow")) {
                s = map.get("slow").doubleValue();
            }
            if (map.containsKey("depOverlap")) {
                d = map.get("depOverlap").doubleValue();
            }
            if (map.containsKey("newMethod")) {
                nm = map.get("newMethod").doubleValue();
            }
            if (map.containsKey("changedMethod")) {
                cm = map.get("changedMethod").doubleValue();
            }
            if (map.containsKey("coverageBonus")) {
                cb = map.get("coverageBonus").doubleValue();
            }
            return new MethodScoringWeights(fr, f, s, d, nm, cm, cb);
        }

        public Map<String, Double> toMap() {
            Map<String, Double> map = new LinkedHashMap<>();
            map.put("failureRecency", failureRecency);
            map.put("fast", fast);
            map.put("slow", slow);
            map.put("depOverlap", depOverlap);
            map.put("newMethod", newMethod);
            map.put("changedMethod", changedMethod);
            map.put("coverageBonus", coverageBonus);
            return map;
        }
    }

    // ── Data records ──────────────────────────────────────────────────

    public record ScoreBreakdown(int totalScore, boolean isNew, boolean isChanged,
                                 int depOverlap, int depTotal, double failScore, boolean isFast,
                                 boolean isSlow, double complexityOverlap,
                                 double speedRatio, boolean hasStaticFieldOverlap) {
        /** Backward-compat constructor (speedRatio=0, hasStaticFieldOverlap=false). */
        public ScoreBreakdown(int totalScore, boolean isNew, boolean isChanged,
                              int depOverlap, int depTotal, double failScore, boolean isFast,
                              boolean isSlow, double complexityOverlap) {
            this(totalScore, isNew, isChanged, depOverlap, depTotal, failScore,
                    isFast, isSlow, complexityOverlap, 0.0, false);
        }
    }

    public record TestOutcome(String testClass, int totalScore, boolean isNew,
                              boolean isChanged, int depOverlap, int depTotal, double failScore,
                              boolean isFast, boolean isSlow, boolean failed,
                              double complexityOverlap, double speedRatio,
                              boolean hasStaticFieldOverlap) {
        /** Backward-compat constructor (speedRatio=0, hasStaticFieldOverlap=false). */
        public TestOutcome(String testClass, int totalScore, boolean isNew,
                           boolean isChanged, int depOverlap, int depTotal, double failScore,
                           boolean isFast, boolean isSlow, boolean failed,
                           double complexityOverlap) {
            this(testClass, totalScore, isNew, isChanged, depOverlap, depTotal, failScore,
                    isFast, isSlow, failed, complexityOverlap, 0.0, false);
        }
        TestOutcome(String testClass, ScoreBreakdown b, boolean failed) {
            this(testClass, b.totalScore(), b.isNew(), b.isChanged(),
                    b.depOverlap(), b.depTotal(), b.failScore(), b.isFast(), b.isSlow(), failed,
                    b.complexityOverlap(), b.speedRatio(), b.hasStaticFieldOverlap());
        }
    }

    public record RunRecord(long timestamp, int totalTests, int totalFailures,
                            int firstFailurePosition, double apfd,
                            List<TestOutcome> outcomes) {}

    // ── Instance state ────────────────────────────────────────────────

    /** Maximum number of run records to keep. */
    public static final int MAX_HISTORY_RUNS = DEFAULT_HISTORY_MAX_RUNS;
    private static final double MIN_ADAPTIVE_ALPHA_FACTOR = TestOrderState.DEFAULT_MIN_ADAPTIVE_ALPHA_FACTOR;

    private final StateConfiguration config;
    private ScoringWeights weights;
    private MethodScoringWeights methodScoringWeights;
    private final DurationTracker durationTracker;
    private final FailureHistoryTracker failureHistory;
    private final RunHistoryManager runHistory;
    /** True when addRunRecord was called since the last save — enables decay even on all-pass runs. */
    private boolean pendingRunCompleted;

    public TestOrderState() {
        this.config = new StateConfiguration();
        this.weights = ScoringWeights.DEFAULT;
        this.methodScoringWeights = MethodScoringWeights.DEFAULT;
        this.durationTracker = new DurationTracker();
        this.failureHistory = new FailureHistoryTracker();
        this.runHistory = new RunHistoryManager();
    }

    // ── Weights ───────────────────────────────────────────────────────

    public ScoringWeights weights() { return weights; }
    public void setWeights(ScoringWeights w) { this.weights = w; }

    // ── Configuration ─────────────────────────────────────────────────

    public double failureDecay() { return config.failureDecay(); }
    public void setFailureDecay(double d) {
        config.setFailureDecay(d);
    }

    public double methodFailureDecay() { return config.methodFailureDecay(); }
    public void setMethodFailureDecay(double d) {
        config.setMethodFailureDecay(d);
    }

    public double durationAlpha() { return config.durationAlpha(); }
    public void setDurationAlpha(double a) {
        config.setDurationAlpha(a);
    }

    public double methodDurationAlpha() { return config.methodDurationAlpha(); }
    public void setMethodDurationAlpha(double a) {
        config.setMethodDurationAlpha(a);
    }

    public double failurePruneThreshold() { return config.failurePruneThreshold(); }
    public void setFailurePruneThreshold(double t) {
        config.setFailurePruneThreshold(t);
    }

    public double emaVarianceThreshold() { return config.emaVarianceThreshold(); }
    public void setEmaVarianceThreshold(double threshold) {
        config.setEmaVarianceThreshold(threshold);
    }

    public int historyMaxRuns() { return config.historyMaxRuns(); }
    public void setHistoryMaxRuns(int maxRuns) {
        config.setHistoryMaxRuns(maxRuns);
        runHistory.trimToMax(config.historyMaxRuns());
    }

    /** Get the number of order-mode runs since the last learn mode. */
    public int runsSinceLearn() { return config.runsSinceLearn(); }
    /** Reset the counter (called when switching to learn mode). */
    public void resetRunsSinceLearn() { config.resetRunsSinceLearn(); }
    /** Increment the counter (called after each order-mode run). */
    public void incrementRunsSinceLearn() { config.incrementRunsSinceLearn(); }

    /**
     * Removes duration and failure entries for test classes that do not appear
     * in any current run record. This prevents the state file from accumulating
     * stale entries for renamed or deleted test classes that inflate the median
     * duration and waste disk space.
     */
    public void pruneStaleEntries() {
        if (runHistory.runs().isEmpty()) return;
        Set<String> activeClasses = new java.util.HashSet<>();
        for (RunRecord run : runHistory.runs()) {
            if (run.outcomes() != null) {
                for (TestOutcome outcome : run.outcomes()) {
                    activeClasses.add(outcome.testClass());
                }
            }
        }
        if (activeClasses.isEmpty()) return;
        durationTracker.pruneToActiveClasses(activeClasses);
        failureHistory.pruneToActiveClasses(activeClasses);
    }

    // ── Durations ─────────────────────────────────────────────────────

    public long getDuration(String testClass, long defaultValue) {
        return durationTracker.getClassDuration(testClass, defaultValue);
    }

    /** Returns the EMA variance of the class duration, or {@code defaultValue} if unknown. */
    public double getDurationVariance(String testClass, double defaultValue) {
        Double v = durationTracker.classDurationVariances().get(testClass);
        return v != null ? v : defaultValue;
    }

    /** Returns all known class durations (EMA-smoothed, in ms). */
    public Map<String, Long> getClassDurations() {
        return durationTracker.classDurations();
    }

    public void recordDuration(String testClass, long measuredMs) {
        durationTracker.recordClassDuration(
                testClass,
                measuredMs,
                config.durationAlpha(),
                config.emaVarianceThreshold(),
                MIN_ADAPTIVE_ALPHA_FACTOR);
    }

    // ── Failures (decayed moving average) ──────────────────────────────

    public void recordFailure(String testClass) {
        failureHistory.recordFailure(testClass);
    }

    /** Returns the current failure score (historical + pending from this run). */
    public double failureScore(String testClass) {
        return failureHistory.failureScore(testClass);
    }

    /**
     * Returns pre-computed decayed failure scores.
     */
    public Map<String, Double> getFailureScores() {
        return failureHistory.failureScores();
    }

    // ── Method-level durations ────────────────────────────────────────

    public double getDurationMethod(String className, String methodName, double defaultValue) {
        return durationTracker.getMethodDuration(className, methodName, defaultValue);
    }

    public void recordMethodDuration(String className, String methodName, long measuredMs) {
        durationTracker.recordMethodDuration(
            className,
            methodName,
            measuredMs,
            config.methodDurationAlpha(),
            config.emaVarianceThreshold(),
            MIN_ADAPTIVE_ALPHA_FACTOR);
    }

    public Map<String, Map<String, Double>> getMethodDurations() {
        return durationTracker.methodDurations();
    }

    // ── Method-level failures (decayed moving average) ─────────────────

    public void recordMethodFailure(String className, String methodName) {
        failureHistory.recordMethodFailure(className, methodName);
    }

    /** Returns the current method-level failure score (historical + pending). */
    public double methodFailureScore(String className, String methodName) {
        return failureHistory.methodFailureScore(className, methodName);
    }

    /**
     * Returns pre-computed decayed method failure scores.
     * Key format: "className#methodName".
     * The {@code windowDays} parameter is accepted for API compatibility but ignored.
     */
    public Map<String, Double> getMethodRecencyWeightedFailureScores(int windowDays) {
        return failureHistory.methodFailureScores();
    }

    /** Returns an unmodifiable view of the raw method failure scores map. */
    public Map<String, Double> getMethodFailureScores() {
        return failureHistory.methodFailureScores();
    }

    // ── Method-level weights ──────────────────────────────────────────

    public MethodScoringWeights methodScoringWeights() { return methodScoringWeights; }
    public void setMethodScoringWeights(MethodScoringWeights w) { this.methodScoringWeights = w; }

    // ── Run history ───────────────────────────────────────────────────

    public List<RunRecord> runs() { return runHistory.runs(); }

    public synchronized void addRunRecord(RunRecord record) {
        runHistory.add(record, config.historyMaxRuns());
        pendingRunCompleted = true;
    }

    // ── Static coordination (PriorityClassOrderer → TelemetryListener) ──

    public static void recordBreakdown(String testClass, ScoreBreakdown breakdown) {
        PendingRunCoordinator.recordBreakdown(testClass, breakdown);
    }

    public static void setStatePath(String path) {
        PendingRunCoordinator.setStatePath(path);
    }

    public static boolean hasPendingData() {
        return PendingRunCoordinator.hasPendingData();
    }

    public static Map<String, ScoreBreakdown> getPendingBreakdowns() {
        return PendingRunCoordinator.getPendingBreakdowns();
    }

    public static String getPendingStatePath() {
        return PendingRunCoordinator.getPendingStatePath();
    }

    public static void resetPending() {
        PendingRunCoordinator.resetPending();
    }

    /** Build a RunRecord from pending breakdowns and actual outcomes. */
    public static RunRecord buildRunRecord(List<String> executionOrder, Set<String> failedClasses) {
        Map<String, ScoreBreakdown> pendingBreakdowns = getPendingBreakdowns();
        List<TestOutcome> outcomes = new ArrayList<>();
        int firstFailPos = -1;
        int failureCount = 0;
        for (int i = 0; i < executionOrder.size(); i++) {
            String tc = executionOrder.get(i);
            boolean failed = failedClasses.contains(tc);
            ScoreBreakdown bd = pendingBreakdowns.getOrDefault(tc,
                    new ScoreBreakdown(0, false, false, 0, 0, 0.0, false, false, 0.0));
            outcomes.add(new TestOutcome(tc, bd, failed));
            if (failed) {
                failureCount++;
                if (firstFailPos < 0) firstFailPos = i;
            }
        }
        return new RunRecord(System.currentTimeMillis(), executionOrder.size(),
            failureCount, firstFailPos, APFDCalculator.computeAPFD(outcomes), outcomes);
    }

    // ── Persistence ──────────────────────────────────────────────────

    public void save(Path file) throws IOException {
        StateSerializer.save(file, this);
    }

    Map<String, Object> toPersistedRoot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION);

        // config (only persist non-default values)
        Map<String, Object> configMap = new LinkedHashMap<>();
        if (config.failureDecay() != DEFAULT_FAILURE_DECAY)
            configMap.put("failureDecay", config.failureDecay());
        if (config.methodFailureDecay() != DEFAULT_METHOD_FAILURE_DECAY)
            configMap.put("methodFailureDecay", config.methodFailureDecay());
        if (config.durationAlpha() != DEFAULT_DURATION_ALPHA)
            configMap.put("durationAlpha", config.durationAlpha());
        if (config.methodDurationAlpha() != DEFAULT_METHOD_DURATION_ALPHA)
            configMap.put("methodDurationAlpha", config.methodDurationAlpha());
        if (config.failurePruneThreshold() != DEFAULT_FAILURE_PRUNE_THRESHOLD)
            configMap.put("failurePruneThreshold", config.failurePruneThreshold());
        if (config.emaVarianceThreshold() != DEFAULT_EMA_VARIANCE_THRESHOLD)
            configMap.put("emaVarianceThreshold", config.emaVarianceThreshold());
        if (config.historyMaxRuns() != MAX_HISTORY_RUNS)
            configMap.put("historyMaxRuns", config.historyMaxRuns());
        if (config.runsSinceLearn() != 0)
            configMap.put("runsSinceLearn", config.runsSinceLearn());
        if (!configMap.isEmpty())
            root.put("config", configMap);

        root.put("weights", new LinkedHashMap<>(weights.toMap()));
        root.put("durations", new LinkedHashMap<>(durationTracker.classDurations()));
        if (!durationTracker.classDurationVariances().isEmpty()) {
            root.put("durationVariances", new LinkedHashMap<>(durationTracker.classDurationVariances()));
        }

        // failure scores: decay historical when a test run completed (detected via addRunRecord)
        // or when new failures were recorded (covers interrupted-run edge case),
        // then add pending failures (current run) at full weight, prune.
        // When save() is called without a run (e.g. optimizer saving weights only),
        // scores are preserved without decay — decay represents "one run passed".
        boolean hasRunData = pendingRunCompleted || failureHistory.hasPendingData();
        FailureHistoryTracker.PersistedScores mergedFailureState = failureHistory.mergeForSave(
                hasRunData,
                config.failureDecay(),
                config.methodFailureDecay(),
                config.failurePruneThreshold(),
                LOG);
        Map<String, Object> mergedFailures = mergedFailureState.failureScores();
        root.put("failureScores", mergedFailures);

        // run history (capped at MAX_HISTORY_RUNS, most recent entries)
        List<RunRecord> persistedRuns = RunHistoryManager.thinRunHistory(runHistory.runs(), config.historyMaxRuns());
        // Prune stale duration and failure entries for test classes no longer in runs
        pruneStaleEntries();
        List<Object> runsList = new ArrayList<>(persistedRuns.size());
        for (RunRecord run : persistedRuns) {
            runsList.add(runRecordToMap(run));
        }
        root.put("runs", runsList);

        // method durations (class → method → EMA duration)
        if (!durationTracker.methodDurations().isEmpty()) {
            Map<String, Object> mdMap = new LinkedHashMap<>();
            for (var classEntry : durationTracker.methodDurations().entrySet()) {
                Map<String, Object> methods = new LinkedHashMap<>();
                for (var methodEntry : classEntry.getValue().entrySet()) {
                    methods.put(methodEntry.getKey(), methodEntry.getValue());
                }
                mdMap.put(classEntry.getKey(), methods);
            }
            root.put("methodDurations", mdMap);
        }
        if (!durationTracker.methodDurationVariances().isEmpty()) {
            Map<String, Object> mdvMap = new LinkedHashMap<>();
            for (var classEntry : durationTracker.methodDurationVariances().entrySet()) {
                Map<String, Object> methods = new LinkedHashMap<>();
                for (var methodEntry : classEntry.getValue().entrySet()) {
                    methods.put(methodEntry.getKey(), methodEntry.getValue());
                }
                mdvMap.put(classEntry.getKey(), methods);
            }
            root.put("methodDurationVariances", mdvMap);
        }

        // method failure scores: decay historical only when new run data exists, add pending, prune
        Map<String, Object> mergedMethodFailures = mergedFailureState.methodFailureScores();
        if (!mergedMethodFailures.isEmpty()) {
            root.put("methodFailureScores", mergedMethodFailures);
        }

        // method scoring weights (only persist non-default)
        if (!methodScoringWeights.equals(MethodScoringWeights.DEFAULT)) {
            root.put("methodWeights", new LinkedHashMap<>(methodScoringWeights.toMap()));
        }

        persistedFailureScores = mergedFailures;
        persistedMethodFailureScores = mergedMethodFailures;
        persistedRunsAfterSave = persistedRuns;
        return root;
    }

    private transient Map<String, Object> persistedFailureScores = Map.of();
    private transient Map<String, Object> persistedMethodFailureScores = Map.of();
    private transient List<RunRecord> persistedRunsAfterSave = List.of();

    void afterSave() {
        failureHistory.applyPersisted(new FailureHistoryTracker.PersistedScores(
                persistedFailureScores, persistedMethodFailureScores));
        runHistory.replace(persistedRunsAfterSave);
        pendingRunCompleted = false;
    }

    private static Map<String, Object> runRecordToMap(RunRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", r.timestamp());
        m.put("totalTests", r.totalTests());
        m.put("totalFailures", r.totalFailures());
        m.put("firstFailurePosition", r.firstFailurePosition());
        m.put("apfd", r.apfd());
        // omit outcomes for runs without failures — they are unused by the optimizer
        if (r.totalFailures() > 0) {
            m.put("outcomes", r.outcomes().stream().map(StateRecordCodec::outcomeToCompact).toList());
        }
        return m;
    }

    public static TestOrderState load(Path file) throws IOException {
        return StateSerializer.load(file);
    }

    @SuppressWarnings("unchecked")
    static TestOrderState fromPersistedRoot(Map<String, Object> root) throws IOException {
        TestOrderState state = new TestOrderState();
        if (root.isEmpty()) {
            LOG.warning("Ignoring malformed state file root; starting fresh");
            return state;
        }
        if (!root.containsKey("schemaVersion")) {
            LOG.warning("State file schemaVersion missing; discarding state");
            return state;
        }
        int schemaVersion = safeInt(root.get("schemaVersion"), 0, "schemaVersion");
        if (schemaVersion < CURRENT_SCHEMA_VERSION) {
            LOG.warning("State file schemaVersion " + schemaVersion + " is too old; discarding state");
            return state;
        }
        if (schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported state schemaVersion " + schemaVersion
                    + " (current " + CURRENT_SCHEMA_VERSION + ")");
        }

        // config — new decay-based params, with backward compat for old keys
        if (root.containsKey("config")) {
            Map<String, Object> cm = safeMap(root.get("config"), "config");
            if (cm.containsKey("failureDecay"))
                state.setFailureDecay(safeDouble(cm.get("failureDecay"), state.failureDecay(), "config.failureDecay"));
            if (cm.containsKey("methodFailureDecay"))
                state.setMethodFailureDecay(safeDouble(cm.get("methodFailureDecay"), state.methodFailureDecay(), "config.methodFailureDecay"));
            if (cm.containsKey("durationAlpha"))
                state.setDurationAlpha(safeDouble(cm.get("durationAlpha"), state.durationAlpha(), "config.durationAlpha"));
            if (cm.containsKey("methodDurationAlpha"))
                state.setMethodDurationAlpha(safeDouble(cm.get("methodDurationAlpha"), state.methodDurationAlpha(), "config.methodDurationAlpha"));
            if (cm.containsKey("failurePruneThreshold"))
                state.setFailurePruneThreshold(safeDouble(cm.get("failurePruneThreshold"), state.failurePruneThreshold(), "config.failurePruneThreshold"));
            if (cm.containsKey("emaVarianceThreshold"))
                state.setEmaVarianceThreshold(safeDouble(cm.get("emaVarianceThreshold"), state.emaVarianceThreshold(), "config.emaVarianceThreshold"));
            if (cm.containsKey("historyMaxRuns"))
                state.setHistoryMaxRuns(safeInt(cm.get("historyMaxRuns"), state.historyMaxRuns(), "config.historyMaxRuns"));
            if (cm.containsKey("runsSinceLearn"))
                state.config.setRunsSinceLearn(safeInt(cm.get("runsSinceLearn"), 0, "config.runsSinceLearn"));
        }

        // weights
        if (root.containsKey("weights")) {
            Map<String, Object> wm = safeMap(root.get("weights"), "weights");
            Map<String, Integer> weightMap = new LinkedHashMap<>(ScoringWeights.DEFAULT.toMap());
            for (var e : wm.entrySet()) {
                weightMap.put(e.getKey(), safeInt(e.getValue(), weightMap.getOrDefault(e.getKey(), 0), "weights." + e.getKey()));
            }
            state.weights = ScoringWeights.fromMap(weightMap);
        }

        // durations
        if (root.containsKey("durations")) {
            Map<String, Object> dm = safeMap(root.get("durations"), "durations");
            for (var e : dm.entrySet()) {
                state.durationTracker.putClassDuration(e.getKey(), safeLong(e.getValue(), 0L, "durations." + e.getKey()));
            }
        }
        if (root.containsKey("durationVariances")) {
            Map<String, Object> dvm = safeMap(root.get("durationVariances"), "durationVariances");
            for (var e : dvm.entrySet()) {
                state.durationTracker.putClassDurationVariance(e.getKey(), safeDouble(e.getValue(), 0.0,
                        "durationVariances." + e.getKey()));
            }
        }

        // failure scores: map of class→score
        if (root.containsKey("failureScores")) {
            Map<String, Object> fsm = safeMap(root.get("failureScores"), "failureScores");
            for (var e : fsm.entrySet()) {
                state.failureHistory.loadFailureScore(e.getKey(),
                        safeDouble(e.getValue(), 0.0, "failureScores." + e.getKey()));
            }
        }

        // runs
        if (root.containsKey("runs")) {
            for (Object item : safeList(root.get("runs"), "runs")) {
                Map<String, Object> runMap = safeMap(item, "runs[]");
                if (!runMap.isEmpty()) {
                    state.runHistory.addRaw(mapToRunRecord(runMap));
                }
            }
        }

        // method durations
        if (root.containsKey("methodDurations")) {
            Map<String, Object> mdMap = safeMap(root.get("methodDurations"), "methodDurations");
            for (var classEntry : mdMap.entrySet()) {
                Map<String, Object> methods = safeMap(classEntry.getValue(), "methodDurations." + classEntry.getKey());
                for (var methodEntry : methods.entrySet()) {
                    state.durationTracker.putMethodDuration(classEntry.getKey(), methodEntry.getKey(),
                            safeDouble(methodEntry.getValue(), 0.0,
                                    "methodDurations." + classEntry.getKey() + "." + methodEntry.getKey()));
                }
            }
        }
        if (root.containsKey("methodDurationVariances")) {
            Map<String, Object> mdvMap = safeMap(root.get("methodDurationVariances"), "methodDurationVariances");
            for (var classEntry : mdvMap.entrySet()) {
                Map<String, Object> methods = safeMap(classEntry.getValue(),
                        "methodDurationVariances." + classEntry.getKey());
                for (var methodEntry : methods.entrySet()) {
                    state.durationTracker.putMethodDurationVariance(classEntry.getKey(), methodEntry.getKey(),
                        safeDouble(methodEntry.getValue(), 0.0,
                            "methodDurationVariances." + classEntry.getKey() + "." + methodEntry.getKey()));
                }
            }
        }

        // method scoring weights
        if (root.containsKey("methodWeights")) {
            Map<String, Object> mwm = safeMap(root.get("methodWeights"), "methodWeights");
            Map<String, Double> doubleMap = new LinkedHashMap<>();
            for (var e : mwm.entrySet()) {
                doubleMap.put(e.getKey(), safeDouble(e.getValue(), 0.0, "methodWeights." + e.getKey()));
            }
            state.methodScoringWeights = MethodScoringWeights.fromMap(doubleMap);
        }

        // method failure scores: map of "class#method"→score
        if (root.containsKey("methodFailureScores")) {
            Map<String, Object> mfs = safeMap(root.get("methodFailureScores"), "methodFailureScores");
            for (var e : mfs.entrySet()) {
                state.failureHistory.loadMethodFailureScore(e.getKey(), safeDouble(e.getValue(), 0.0,
                        "methodFailureScores." + e.getKey()));
            }
        }

        state.runHistory.trimToMax(state.config.historyMaxRuns());

        return state;
    }

    private static RunRecord mapToRunRecord(Map<String, Object> rm) {
        List<TestOutcome> outcomes = rm.containsKey("outcomes")
                ? safeList(rm.get("outcomes"), "runs[].outcomes").stream()
                    .map(obj -> StateRecordCodec.compactToOutcome(obj, LOG))
                    .filter(java.util.Objects::nonNull)
                    .toList()
                : List.of();
        // firstFailurePosition: -1 means "no failures", 0 means "first test failed".
        // Old state files may omit this field; default to -1 (no failures) rather than
        // 0 which would incorrectly imply the first test failed.
        Object ffp = rm.get("firstFailurePosition");
        int firstFailPos = ffp == null ? -1 : safeInt(ffp, -1, "runs[].firstFailurePosition");
        return new RunRecord(
                safeLong(rm.get("timestamp"), 0L, "runs[].timestamp"),
                safeInt(rm.get("totalTests"), 0, "runs[].totalTests"),
                safeInt(rm.get("totalFailures"), 0, "runs[].totalFailures"),
                firstFailPos,
                safeDouble(rm.get("apfd"), 0.0, "runs[].apfd"),
                outcomes);
    }

    static Map<String, Object> safeMap(Object value, String label) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?>)) {
            LOG.warning("Expected map for " + label + " but got " + value.getClass().getSimpleName());
            return Map.of();
        }
        return Util.asMap(value);
    }

    private static List<Object> safeList(Object value, String label) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?>)) {
            LOG.warning("Expected list for " + label + " but got " + value.getClass().getSimpleName());
            return List.of();
        }
        return Util.asList(value);
    }

    private static int safeInt(Object o, int defaultValue, String label) {
        try {
            return toInt(o);
        } catch (RuntimeException e) {
            LOG.warning("Invalid integer for " + label + ": " + o);
            return defaultValue;
        }
    }

    private static long safeLong(Object o, long defaultValue, String label) {
        try {
            return toLong(o);
        } catch (RuntimeException e) {
            LOG.warning("Invalid long for " + label + ": " + o);
            return defaultValue;
        }
    }

    private static double safeDouble(Object o, double defaultValue, String label) {
        try {
            return toDouble(o);
        } catch (RuntimeException e) {
            LOG.warning("Invalid double for " + label + ": " + o);
            return defaultValue;
        }
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(o.toString());
    }

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(o.toString());
    }

    // ── Optimizer (jenetics-based genetic algorithm) ─────────────────

    /** Result of the optimizer: weights plus diagnostic scores for reporting. */
    public record OptimizeResult(ScoringWeights weights, double trainScore,
                                 double validationScore, boolean overfit, int folds) {}

    /**
     * Genetic-algorithm optimizer using jenetics: evolves weight combinations
     * to maximise recency-weighted expanding-window APFDc across historical
     * runs with failures.
     * <p>
     * Uses default weight definitions for optimizer ranges.
     *
     * @return optimised result, or {@code null} if insufficient data
     */
    public OptimizeResult optimize() {
        return optimize(WEIGHT_DEFS);
    }

    /**
     * Genetic-algorithm optimizer using jenetics with custom weight definitions.
     * <p>
     * Validation strategy (to guard against overfitting with 9 parameters):
     * <ol>
     *   <li><b>Expanding-window cross-validation</b>: runs are sorted chronologically.
     *       Training starts at the oldest 50% of runs; each subsequent fold adds one
     *       run to the training window and validates on the next run.  This respects
     *       temporal ordering and avoids data leakage from future runs.
     *       <br><i>Reference:</i> Hyndman &amp; Athanasopoulos (2021), <i>Forecasting:
     *       Principles and Practice</i>, §5.4 — Time series cross-validation.</li>
     *   <li><b>APFDc fitness</b>: uses cost-cognizant APFD with EMA-smoothed test
     *       durations as execution cost.  This aligns the optimizer with the real
     *       goal: minimise wall-clock time to first failure.
     *       <br><i>Reference:</i> Elbaum, Malishevsky, Rothermel (2002), <i>Test Case
     *       Prioritization: A Family of Empirical Studies</i>, ICSE.</li>
     *   <li><b>L2 regularization</b>: the fitness function subtracts
     *       {@code λ × Σ(wᵢ − defaultᵢ)²}, penalising weights that drift from
     *       established defaults without strong evidence.
     *       <br><i>Reference:</i> Hastie, Tibshirani, Friedman (2009), <i>The Elements
     *       of Statistical Learning</i>, §3.4.</li>
     *   <li><b>Recency weighting</b>: each fold's APFDc is weighted by
     *       {@code (1 − α)^(k − i)} so recent folds dominate, matching the
     *       assumption that the most recent codebase trajectory is the best predictor.
     *       <br><i>Reference:</i> Elsner et al. (2021), <i>Empirically Evaluating Readily
     *       Available Information for Regression Test Optimization in CI</i>, TSE.</li>
     *   <li><b>Overfit guard</b>: after evolution, the best weights are evaluated
     *       on the training and validation portions.  If validation APFDc drops below
     *       85% of training APFDc, the optimizer returns the default weights instead.</li>
     * </ol>
     * With ≤ 4 qualifying runs, expanding-window produces too few folds, so we
     * fall back to plain average APFDc with L2 regularization only.
     *
     * @param defs weight definitions specifying optimizer ranges
     * @return optimised result, or {@code null} if insufficient data
     */
    public OptimizeResult optimize(List<WeightDef> defs) {
        return ScoringOptimizer.optimize(runHistory.runs(), durationTracker.classDurations(), defs, LOG);
    }

}
