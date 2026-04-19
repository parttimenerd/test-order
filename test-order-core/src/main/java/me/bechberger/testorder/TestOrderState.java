package me.bechberger.testorder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import java.util.concurrent.ConcurrentHashMap;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import io.jenetics.*;
import io.jenetics.engine.Engine;
import io.jenetics.engine.EvolutionResult;
import io.jenetics.engine.Limits;
import io.jenetics.util.Factory;
import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.PrettyPrinter;
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

    private static final Logger LOG = Logger.getLogger(TestOrderState.class.getName());

    // ── Scoring weights ───────────────────────────────────────────────

    /** Definition of a single weight: name, default, optimizer range. */
    public record WeightDef(String name, int defaultValue, int min, int max) {}

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

    static {
        CommentedConfig resourceConfig = loadResourceConfig();
        WEIGHT_DEFS = Collections.unmodifiableList(buildWeightDefs(resourceConfig));
        DEFAULT_FAILURE_DECAY = readConfigDouble(resourceConfig, "failureDecay");
        DEFAULT_METHOD_FAILURE_DECAY = readConfigDouble(resourceConfig, "methodFailureDecay");
        DEFAULT_DURATION_ALPHA = readConfigDouble(resourceConfig, "durationAlpha");
        DEFAULT_METHOD_DURATION_ALPHA = readConfigDouble(resourceConfig, "methodDurationAlpha");
        DEFAULT_FAILURE_PRUNE_THRESHOLD = readConfigDouble(resourceConfig, "failurePruneThreshold");
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

    /** @deprecated No longer used; failure scoring uses decay-based model now. */
    @Deprecated
    public record FailureRecord(String className, long epochMillis) {}

    /** @deprecated No longer used; failure scoring uses decay-based model now. */
    @Deprecated
    public record MethodFailureRecord(String className, String methodName, long epochMillis) {}

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
    public static final int MAX_HISTORY_RUNS = 50;

    private ScoringWeights weights;
    private MethodScoringWeights methodScoringWeights;
    private double failureDecay = DEFAULT_FAILURE_DECAY;
    private double methodFailureDecay = DEFAULT_METHOD_FAILURE_DECAY;
    private double durationAlpha = DEFAULT_DURATION_ALPHA;
    private double methodDurationAlpha = DEFAULT_METHOD_DURATION_ALPHA;
    private double failurePruneThreshold = DEFAULT_FAILURE_PRUNE_THRESHOLD;
    /** Tracks how many order-mode runs have occurred since the last learn mode. */
    private int runsSinceLearn = 0;
    private final Map<String, Long> durations;
    /** Historical failure scores loaded from state file (decay applied). */
    private final Map<String, Double> failureScores;
    /** Failures recorded during the current run (not yet decayed). */
    private final Map<String, Double> pendingFailureScores;
    private final Map<String, Map<String, Double>> methodDurations;
    /** Historical method failure scores loaded from state file. */
    private final Map<String, Double> methodFailureScores;
    /** Method failures recorded during the current run. */
    private final Map<String, Double> pendingMethodFailureScores;
    private final List<RunRecord> runs;
    /** True when addRunRecord was called since the last save — enables decay even on all-pass runs. */
    private boolean pendingRunCompleted;

    public TestOrderState() {
        this.weights = ScoringWeights.DEFAULT;
        this.methodScoringWeights = MethodScoringWeights.DEFAULT;
        this.durations = new LinkedHashMap<>();
        this.failureScores = new LinkedHashMap<>();
        this.pendingFailureScores = new LinkedHashMap<>();
        this.methodDurations = new LinkedHashMap<>();
        this.methodFailureScores = new LinkedHashMap<>();
        this.pendingMethodFailureScores = new LinkedHashMap<>();
        this.runs = new ArrayList<>();
    }

    // ── Weights ───────────────────────────────────────────────────────

    public ScoringWeights weights() { return weights; }
    public void setWeights(ScoringWeights w) { this.weights = w; }

    // ── Configuration ─────────────────────────────────────────────────

    public double failureDecay() { return failureDecay; }
    public void setFailureDecay(double d) {
        if (d < 0 || d > 1) throw new IllegalArgumentException("failureDecay must be in [0, 1]: " + d);
        this.failureDecay = d;
    }

    public double methodFailureDecay() { return methodFailureDecay; }
    public void setMethodFailureDecay(double d) {
        if (d < 0 || d > 1) throw new IllegalArgumentException("methodFailureDecay must be in [0, 1]: " + d);
        this.methodFailureDecay = d;
    }

    public double durationAlpha() { return durationAlpha; }
    public void setDurationAlpha(double a) {
        if (a < 0 || a > 1) throw new IllegalArgumentException("durationAlpha must be in [0, 1]: " + a);
        this.durationAlpha = a;
    }

    public double methodDurationAlpha() { return methodDurationAlpha; }
    public void setMethodDurationAlpha(double a) {
        if (a < 0 || a > 1) throw new IllegalArgumentException("methodDurationAlpha must be in [0, 1]: " + a);
        this.methodDurationAlpha = a;
    }

    public double failurePruneThreshold() { return failurePruneThreshold; }
    public void setFailurePruneThreshold(double t) { this.failurePruneThreshold = t; }

    /** Get the number of order-mode runs since the last learn mode. */
    public int runsSinceLearn() { return runsSinceLearn; }
    /** Reset the counter (called when switching to learn mode). */
    public void resetRunsSinceLearn() { this.runsSinceLearn = 0; }
    /** Increment the counter (called after each order-mode run). */
    public void incrementRunsSinceLearn() { this.runsSinceLearn++; }

    // ── Durations ─────────────────────────────────────────────────────

    public long getDuration(String testClass, long defaultValue) {
        return durations.getOrDefault(testClass, defaultValue);
    }

    public void recordDuration(String testClass, long measuredMs) {
        durations.merge(testClass, measuredMs, (a, b) -> Math.round(durationAlpha * b + (1.0 - durationAlpha) * a));
    }

    // ── Failures (decayed moving average) ──────────────────────────────

    public void recordFailure(String testClass) {
        pendingFailureScores.merge(testClass, 1.0, Double::sum);
    }

    /** @deprecated timestamp is ignored; use {@link #recordFailure(String)} instead */
    @Deprecated
    public void recordFailure(String testClass, long epochMillis) {
        recordFailure(testClass);
    }

    /** Returns the current failure score (historical + pending from this run). */
    public double failureScore(String testClass) {
        return failureScores.getOrDefault(testClass, 0.0)
                + pendingFailureScores.getOrDefault(testClass, 0.0);
    }

    /**
     * Returns pre-computed decayed failure scores.
     */
    public Map<String, Double> getFailureScores() {
        return Collections.unmodifiableMap(failureScores);
    }

    // ── Method-level durations ────────────────────────────────────────

    public double getDurationMethod(String className, String methodName, double defaultValue) {
        return methodDurations.getOrDefault(className, Map.of())
                .getOrDefault(methodName, defaultValue);
    }

    public void recordMethodDuration(String className, String methodName, long measuredMs) {
        methodDurations.computeIfAbsent(className, k -> new LinkedHashMap<>())
                .merge(methodName, (double) measuredMs,
                        (a, b) -> (double) Math.round(methodDurationAlpha * b + (1.0 - methodDurationAlpha) * a));
    }

    public Map<String, Map<String, Double>> getMethodDurations() {
        return Collections.unmodifiableMap(methodDurations);
    }

    // ── Method-level failures (decayed moving average) ─────────────────

    public void recordMethodFailure(String className, String methodName) {
        String key = className + "#" + methodName;
        pendingMethodFailureScores.merge(key, 1.0, Double::sum);
    }

    /** @deprecated timestamp is ignored; use {@link #recordMethodFailure(String, String)} instead */
    @Deprecated
    public void recordMethodFailure(String className, String methodName, long epochMillis) {
        recordMethodFailure(className, methodName);
    }

    /** Returns the current method-level failure score (historical + pending). */
    public double methodFailureScore(String className, String methodName) {
        String key = className + "#" + methodName;
        return methodFailureScores.getOrDefault(key, 0.0)
                + pendingMethodFailureScores.getOrDefault(key, 0.0);
    }

    /**
     * Returns pre-computed decayed method failure scores.
     * Key format: "className#methodName".
     * The {@code windowDays} parameter is accepted for API compatibility but ignored.
     */
    public Map<String, Double> getMethodRecencyWeightedFailureScores(int windowDays) {
        return Collections.unmodifiableMap(methodFailureScores);
    }

    /** Returns an unmodifiable view of the raw method failure scores map. */
    public Map<String, Double> getMethodFailureScores() {
        return Collections.unmodifiableMap(methodFailureScores);
    }

    // ── Method-level weights ──────────────────────────────────────────

    public MethodScoringWeights methodScoringWeights() { return methodScoringWeights; }
    public void setMethodScoringWeights(MethodScoringWeights w) { this.methodScoringWeights = w; }

    // ── Run history ───────────────────────────────────────────────────

    public List<RunRecord> runs() { return Collections.unmodifiableList(runs); }

    public void addRunRecord(RunRecord record) {
        runs.add(record);
        pendingRunCompleted = true;
        while (runs.size() > MAX_HISTORY_RUNS) {
            runs.remove(0);
        }
    }

    // ── Static coordination (PriorityClassOrderer → TelemetryListener) ──

    private static final Map<String, ScoreBreakdown> pendingBreakdowns = new ConcurrentHashMap<>();
    private static volatile String pendingStatePath;
    private static final Object pendingLock = new Object();

    public static void recordBreakdown(String testClass, ScoreBreakdown breakdown) {
        pendingBreakdowns.put(testClass, breakdown);
    }

    public static void setStatePath(String path) {
        synchronized (pendingLock) { pendingStatePath = path; }
    }

    public static boolean hasPendingData() {
        synchronized (pendingLock) {
            return !pendingBreakdowns.isEmpty() && pendingStatePath != null;
        }
    }

    public static Map<String, ScoreBreakdown> getPendingBreakdowns() {
        return Collections.unmodifiableMap(pendingBreakdowns);
    }

    public static String getPendingStatePath() {
        synchronized (pendingLock) { return pendingStatePath; }
    }

    public static void resetPending() {
        synchronized (pendingLock) {
            pendingBreakdowns.clear();
            pendingStatePath = null;
        }
    }

    // ── APFD ──────────────────────────────────────────────────────────

    public static double computeAPFD(List<TestOutcome> orderedOutcomes) {
        int n = orderedOutcomes.size();
        int m = 0;
        double positionSum = 0;
        for (int i = 0; i < n; i++) {
            if (orderedOutcomes.get(i).failed()) {
                m++;
                positionSum += (i + 1);
            }
        }
        if (m == 0 || n == 0) return 1.0;
        return 1.0 - positionSum / ((double) n * m) + 1.0 / (2.0 * n);
    }

    /**
     * Cost-cognizant APFD (APFDc) using test execution time as cost.
     * <p>
     * APFDc measures the fraction of total fault-detection cost saved by running
     * tests in the given order, weighted by the per-test execution time.
     * Unlike standard APFD (which treats all tests as equal cost), APFDc rewards
     * orderings that surface failures before expensive tests run.
     * <p>
     * Formula (Elbaum et al., all fault severities equal to 1):
     * <pre>
     *   APFDc = 1 - Σ_j (cost_up_to_first_detector_j - 0.5 * cost_of_detector_j)
     *               / (total_cost × number_of_faults)
     * </pre>
     * Falls back to standard APFD when no duration data is available.
     *
     * @param orderedOutcomes outcomes in their prioritised execution order
     * @param durations       EMA-smoothed class → duration map (from state)
     * @return APFDc score in [0, 1]; higher is better
     *
     * @see <a href="https://doi.org/10.1145/566171.566187">Elbaum et al. (2002):
     *      Test Case Prioritization: A Family of Empirical Studies</a>
     */
    public static double computeAPFDc(List<TestOutcome> orderedOutcomes,
                                       Map<String, Long> durations) {
        int n = orderedOutcomes.size();
        if (n == 0) return 1.0;

        // Assign costs: use EMA duration when available, else 1 (equal cost)
        double[] costs = new double[n];
        boolean hasCosts = false;
        for (int i = 0; i < n; i++) {
            Long dur = durations.get(orderedOutcomes.get(i).testClass());
            if (dur != null && dur > 0) {
                costs[i] = dur;
                hasCosts = true;
            } else {
                costs[i] = 1.0;
            }
        }
        if (!hasCosts) {
            // No duration data: fall back to standard APFD
            return computeAPFD(orderedOutcomes);
        }

        double totalCost = 0;
        for (double c : costs) totalCost += c;

        // For each fault, find the cumulative cost up to the first test that reveals it.
        // Since many tests can fail independently, each failed test represents one fault.
        int m = 0;
        double weightedSum = 0;
        double cumulativeCost = 0;
        for (int i = 0; i < n; i++) {
            cumulativeCost += costs[i];
            if (orderedOutcomes.get(i).failed()) {
                m++;
                // cost to detect this fault = cumulative cost so far - half the detecting test's cost
                weightedSum += cumulativeCost - 0.5 * costs[i];
            }
        }
        if (m == 0 || totalCost <= 0) return 1.0;
        return 1.0 - weightedSum / (totalCost * m);
    }

    /**
     * Recomputes APFD for outcomes re-ordered by the given weights.
     * <p>
     * Uses stored per-outcome data to faithfully reconstruct what each weight
     * combination would produce:
     * <ul>
     *   <li><b>Speed:</b> uses {@code speedRatio} (weight-independent log-bucket position
     *       in [-1, 1]) when available, falling back to legacy {@code isFast}/{@code isSlow}
     *       booleans for old records.</li>
     *   <li><b>Static field bonus:</b> uses {@code hasStaticFieldOverlap}.</li>
     *   <li><b>coverageBonus:</b> when {@code > 0}, skips both depOverlap and
     *       complexityScore (matching real scorer behaviour).  A faithful set-cover
     *       re-run is not possible from stored outcomes, so depOverlap is used as a
     *       proxy even in coverageBonus mode.</li>
     * </ul>
     */
    public static double computeAPFDWithWeights(List<TestOutcome> outcomes, ScoringWeights w) {
        List<TestOutcome> reordered = new ArrayList<>(outcomes);
        reordered.sort(reorderComparator(w));
        return computeAPFD(reordered);
    }

    /**
     * Like {@link #computeAPFDWithWeights} but returns APFDc (cost-cognizant)
     * using EMA durations as test execution cost.
     * Falls back to standard APFD when duration data is unavailable.
     */
    public static double computeAPFDcWithWeights(List<TestOutcome> outcomes,
                                                  ScoringWeights w,
                                                  Map<String, Long> durations) {
        List<TestOutcome> reordered = new ArrayList<>(outcomes);
        reordered.sort(reorderComparator(w));
        return computeAPFDc(reordered, durations);
    }

    /** Shared comparator: scores outcomes by the given weights (descending). */
    private static Comparator<TestOutcome> reorderComparator(ScoringWeights w) {
        return Comparator.comparingDouble((TestOutcome o) -> {
            double score = 0;
            if (o.isNew()) score += w.newTest();
            if (o.isChanged()) score += w.changedTest();
            if (o.failScore() > 0) score += Math.min(Math.ceil(o.failScore()), w.maxFailure());

            if (o.speedRatio() != 0.0) {
                double sr = o.speedRatio();
                if (sr <= 0) {
                    score += (-sr) * w.speed();
                } else {
                    score -= sr * w.speedPenalty();
                }
            } else {
                if (o.isFast()) score += w.speed();
                if (o.isSlow()) score -= w.speedPenalty();
            }

            if (w.coverageBonus() > 0) {
                score += TestScorer.depOverlapScore(o.depOverlap(), o.depTotal(), w.coverageBonus());
            } else {
                score += TestScorer.depOverlapScore(o.depOverlap(), o.depTotal(), w.depOverlap());
                score += TestScorer.complexityScore(o.complexityOverlap(), o.depTotal(), w.changeComplexity());
            }

            if (o.hasStaticFieldOverlap()) score += w.staticFieldBonus();

            return score;
        }).reversed();
    }

    /** Build a RunRecord from pending breakdowns and actual outcomes. */
    public static RunRecord buildRunRecord(List<String> executionOrder, Set<String> failedClasses) {
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
                failureCount, firstFailPos, computeAPFD(outcomes), outcomes);
    }

    // ── Persistence ──────────────────────────────────────────────────

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Map<String, Object> root = new LinkedHashMap<>();

        // config (only persist non-default values)
        Map<String, Object> configMap = new LinkedHashMap<>();
        if (failureDecay != DEFAULT_FAILURE_DECAY)
            configMap.put("failureDecay", failureDecay);
        if (methodFailureDecay != DEFAULT_METHOD_FAILURE_DECAY)
            configMap.put("methodFailureDecay", methodFailureDecay);
        if (durationAlpha != DEFAULT_DURATION_ALPHA)
            configMap.put("durationAlpha", durationAlpha);
        if (methodDurationAlpha != DEFAULT_METHOD_DURATION_ALPHA)
            configMap.put("methodDurationAlpha", methodDurationAlpha);
        if (failurePruneThreshold != DEFAULT_FAILURE_PRUNE_THRESHOLD)
            configMap.put("failurePruneThreshold", failurePruneThreshold);
        if (runsSinceLearn != 0)
            configMap.put("runsSinceLearn", runsSinceLearn);
        if (!configMap.isEmpty())
            root.put("config", configMap);

        root.put("weights", new LinkedHashMap<>(weights.toMap()));
        root.put("durations", new LinkedHashMap<>(durations));

        // failure scores: decay historical when a test run completed (detected via addRunRecord)
        // or when new failures were recorded (covers interrupted-run edge case),
        // then add pending failures (current run) at full weight, prune.
        // When save() is called without a run (e.g. optimizer saving weights only),
        // scores are preserved without decay — decay represents "one run passed".
        boolean hasRunData = pendingRunCompleted || !pendingFailureScores.isEmpty()
                || !pendingMethodFailureScores.isEmpty();
        double retain = hasRunData ? (1.0 - failureDecay) : 1.0;
        Map<String, Object> mergedFailures = new LinkedHashMap<>();
        // Iterate historical scores first, applying decay
        for (var entry : failureScores.entrySet()) {
            double historical = entry.getValue() * retain;
            double pending = pendingFailureScores.getOrDefault(entry.getKey(), 0.0);
            double total = historical + pending;
            if (total >= failurePruneThreshold) {
                mergedFailures.put(entry.getKey(), total);
            }
        }
        // Then add any pending-only entries (new failures not in historical)
        for (var entry : pendingFailureScores.entrySet()) {
            if (!failureScores.containsKey(entry.getKey())) {
                if (entry.getValue() >= failurePruneThreshold) {
                    mergedFailures.put(entry.getKey(), entry.getValue());
                }
            }
        }
        root.put("failureScores", mergedFailures);

        // run history (capped at MAX_HISTORY_RUNS, most recent entries)
        int start = Math.max(0, runs.size() - MAX_HISTORY_RUNS);
        List<Object> runsList = new ArrayList<>(runs.size() - start);
        for (int i = start; i < runs.size(); i++) {
            runsList.add(runRecordToMap(runs.get(i)));
        }
        root.put("runs", runsList);

        // method durations (class → method → EMA duration)
        if (!methodDurations.isEmpty()) {
            Map<String, Object> mdMap = new LinkedHashMap<>();
            for (var classEntry : methodDurations.entrySet()) {
                Map<String, Object> methods = new LinkedHashMap<>();
                for (var methodEntry : classEntry.getValue().entrySet()) {
                    methods.put(methodEntry.getKey(), methodEntry.getValue());
                }
                mdMap.put(classEntry.getKey(), methods);
            }
            root.put("methodDurations", mdMap);
        }

        // method failure scores: decay historical only when new run data exists, add pending, prune
        double methodRetain = hasRunData ? (1.0 - methodFailureDecay) : 1.0;
        Map<String, Object> mergedMethodFailures = new LinkedHashMap<>();
        for (var entry : methodFailureScores.entrySet()) {
            double historical = entry.getValue() * methodRetain;
            double pending = pendingMethodFailureScores.getOrDefault(entry.getKey(), 0.0);
            double total = historical + pending;
            if (total >= failurePruneThreshold) {
                mergedMethodFailures.put(entry.getKey(), total);
            }
        }
        for (var entry : pendingMethodFailureScores.entrySet()) {
            if (!methodFailureScores.containsKey(entry.getKey())) {
                if (entry.getValue() >= failurePruneThreshold) {
                    mergedMethodFailures.put(entry.getKey(), entry.getValue());
                }
            }
        }
        if (!mergedMethodFailures.isEmpty()) {
            root.put("methodFailureScores", mergedMethodFailures);
        }

        // method scoring weights (only persist non-default)
        if (!methodScoringWeights.equals(MethodScoringWeights.DEFAULT)) {
            root.put("methodWeights", new LinkedHashMap<>(methodScoringWeights.toMap()));
        }

        byte[] jsonBytes = PrettyPrinter.compactPrint(root).getBytes(StandardCharsets.UTF_8);
        LZ4Factory factory = LZ4Factory.fastestInstance();
        LZ4Compressor compressor = factory.highCompressor(17); // maximum compression level
        try (var out = new LZ4BlockOutputStream(
                Files.newOutputStream(file), 1 << 16, compressor)) {
            out.write(jsonBytes);
        }

        // Sync in-memory state with what was persisted:
        // failureScores should mirror decayed+merged values that were written to disk
        failureScores.clear();
        for (var e : mergedFailures.entrySet()) {
            failureScores.put(e.getKey(), ((Number) e.getValue()).doubleValue());
        }
        pendingFailureScores.clear();
        methodFailureScores.clear();
        for (var e : mergedMethodFailures.entrySet()) {
            methodFailureScores.put(e.getKey(), ((Number) e.getValue()).doubleValue());
        }
        pendingMethodFailureScores.clear();
        pendingRunCompleted = false;
    }

    /**
     * Compact outcome serialization: packs booleans into a flags integer.
     * <p>Flags: bit0=isNew, bit1=isChanged, bit2=isFast, bit3=isSlow, bit4=failed,
     * bit5=hasStaticFieldOverlap.
     * <p>Format: always {@code [testClass, flags, depOverlap, depTotal, failScore,
     * complexityOverlap, speedRatio]} — a fixed 7-element list for simplicity and
     * forward/backward compatibility.
     */
    static List<Object> outcomeToCompact(TestOutcome o) {
        int flags = (o.isNew() ? 1 : 0)
                  | (o.isChanged() ? 2 : 0)
                  | (o.isFast() ? 4 : 0)
                  | (o.isSlow() ? 8 : 0)
                  | (o.failed() ? 16 : 0)
                  | (o.hasStaticFieldOverlap() ? 32 : 0);
        return List.of(o.testClass(), flags, o.depOverlap(), o.depTotal(), o.failScore(),
                o.complexityOverlap(), o.speedRatio());
    }

    /**
     * Deserializes a compact outcome.
     * <p>Accepts the canonical format: {@code [testClass, flags, depOverlap, depTotal,
     * failScore, complexityOverlap, speedRatio]}.
     * <p>Returns {@code null} for any unrecognized or legacy format (e.g. all-numeric
     * lists from old state files, plain integers, maps). Callers must filter nulls.
     * This prevents stale state files from crashing test discovery.
     */
    static TestOutcome compactToOutcome(Object obj) {
        if (!(obj instanceof List<?>)) {
            return null; // legacy non-list format — skip silently
        }
        List<Object> arr = Util.asList(obj);
        if (arr.isEmpty() || !(arr.get(0) instanceof String)) {
            return null; // legacy all-numeric compact format — skip silently
        }
        String tc = (String) arr.get(0);
        int flags = toInt(arr.get(1));
        return new TestOutcome(tc, 0,
                (flags & 1) != 0, (flags & 2) != 0,
                arr.size() > 2 ? toInt(arr.get(2)) : 0,
                arr.size() > 3 ? toInt(arr.get(3)) : 0,
                arr.size() > 4 ? toDouble(arr.get(4)) : 0.0,
                (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0,
                arr.size() > 5 ? toDouble(arr.get(5)) : 0.0,
                arr.size() > 6 ? toDouble(arr.get(6)) : 0.0,
                (flags & 32) != 0);
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
            m.put("outcomes", r.outcomes().stream().map(TestOrderState::outcomeToCompact).toList());
        }
        return m;
    }

    public static TestOrderState load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new TestOrderState();
        }
        byte[] raw = Files.readAllBytes(file);
        if (raw.length == 0) {
            return new TestOrderState();
        }
        String json;
        // Detect format: plain JSON starts with '{' or whitespace; otherwise LZ4 compressed
        if (raw[0] == '{' || raw[0] == ' ' || raw[0] == '\n' || raw[0] == '\r' || raw[0] == '\t') {
            json = new String(raw, StandardCharsets.UTF_8).strip();
        } else {
            try (var in = new LZ4BlockInputStream(new ByteArrayInputStream(raw))) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
        }
        if (json.isEmpty()) {
            return new TestOrderState();
        }
        return loadJson(json);
    }

    @SuppressWarnings("unchecked")
    private static TestOrderState loadJson(String json) throws IOException {
        TestOrderState state = new TestOrderState();
        Map<String, Object> root = Util.asMap(JSONParser.parse(json));

        // config — new decay-based params, with backward compat for old keys
        if (root.containsKey("config")) {
            Map<String, Object> cm = Util.asMap(root.get("config"));
            if (cm.containsKey("failureDecay"))
                state.failureDecay = toDouble(cm.get("failureDecay"));
            if (cm.containsKey("methodFailureDecay"))
                state.methodFailureDecay = toDouble(cm.get("methodFailureDecay"));
            if (cm.containsKey("durationAlpha"))
                state.durationAlpha = toDouble(cm.get("durationAlpha"));
            if (cm.containsKey("methodDurationAlpha"))
                state.methodDurationAlpha = toDouble(cm.get("methodDurationAlpha"));
            if (cm.containsKey("failurePruneThreshold"))
                state.failurePruneThreshold = toDouble(cm.get("failurePruneThreshold"));
            if (cm.containsKey("runsSinceLearn"))
                state.runsSinceLearn = toInt(cm.get("runsSinceLearn"));
        }

        // weights
        if (root.containsKey("weights")) {
            Map<String, Object> wm = Util.asMap(root.get("weights"));
            Map<String, Integer> weightMap = new LinkedHashMap<>(ScoringWeights.DEFAULT.toMap());
            for (var e : wm.entrySet()) {
                weightMap.put(e.getKey(), toInt(e.getValue()));
            }
            state.weights = ScoringWeights.fromMap(weightMap);
        }

        // durations
        if (root.containsKey("durations")) {
            Map<String, Object> dm = Util.asMap(root.get("durations"));
            for (var e : dm.entrySet()) {
                state.durations.put(e.getKey(), toLong(e.getValue()));
            }
        }

        // failure scores: map of class→score
        if (root.containsKey("failureScores")) {
            Map<String, Object> fsm = Util.asMap(root.get("failureScores"));
            for (var e : fsm.entrySet()) {
                state.failureScores.put(e.getKey(), toDouble(e.getValue()));
            }
        }

        // runs
        if (root.containsKey("runs")) {
            for (Object item : Util.asList(root.get("runs"))) {
                state.runs.add(mapToRunRecord(Util.asMap(item)));
            }
        }

        // method durations
        if (root.containsKey("methodDurations")) {
            Map<String, Object> mdMap = Util.asMap(root.get("methodDurations"));
            for (var classEntry : mdMap.entrySet()) {
                Map<String, Object> methods = Util.asMap(classEntry.getValue());
                for (var methodEntry : methods.entrySet()) {
                    state.methodDurations
                            .computeIfAbsent(classEntry.getKey(), k -> new LinkedHashMap<>())
                            .put(methodEntry.getKey(), toDouble(methodEntry.getValue()));
                }
            }
        }

        // method scoring weights
        if (root.containsKey("methodWeights")) {
            Map<String, Object> mwm = Util.asMap(root.get("methodWeights"));
            Map<String, Double> doubleMap = new LinkedHashMap<>();
            for (var e : mwm.entrySet()) {
                doubleMap.put(e.getKey(), toDouble(e.getValue()));
            }
            state.methodScoringWeights = MethodScoringWeights.fromMap(doubleMap);
        }

        // method failure scores: map of "class#method"→score
        if (root.containsKey("methodFailureScores")) {
            Map<String, Object> mfs = Util.asMap(root.get("methodFailureScores"));
            for (var e : mfs.entrySet()) {
                state.methodFailureScores.put(e.getKey(), toDouble(e.getValue()));
            }
        }

        return state;
    }

    private static RunRecord mapToRunRecord(Map<String, Object> rm) {
        List<TestOutcome> outcomes = rm.containsKey("outcomes")
                ? Util.asList(rm.get("outcomes")).stream()
                    .map(TestOrderState::compactToOutcome)
                    .filter(java.util.Objects::nonNull)
                    .toList()
                : List.of();
        // firstFailurePosition: -1 means "no failures", 0 means "first test failed".
        // Old state files may omit this field; default to -1 (no failures) rather than
        // 0 which would incorrectly imply the first test failed.
        Object ffp = rm.get("firstFailurePosition");
        int firstFailPos = ffp == null ? -1 : toInt(ffp);
        return new RunRecord(
                toLong(rm.get("timestamp")),
                toInt(rm.get("totalTests")),
                toInt(rm.get("totalFailures")),
                firstFailPos,
                toDouble(rm.get("apfd")),
                outcomes);
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

    private static boolean toBool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        return "true".equals(String.valueOf(o)) || "1".equals(String.valueOf(o));
    }

    // ── Optimizer (jenetics-based genetic algorithm) ─────────────────

    /** Minimum runs with failures needed for meaningful optimisation. */
    public static final int MIN_RUNS_FOR_OPTIMISATION = 3;

    /** Maximum generations for the genetic algorithm. */
    private static final int MAX_GENERATIONS = 2000;

    /** Number of generations without improvement before stopping early. */
    private static final int STEADY_FITNESS_LIMIT = 200;

    /** Population size for the genetic algorithm. */
    private static final int POPULATION_SIZE = 150;

    /**
     * L2 regularization strength: penalises weights that deviate from defaults.
     * <p>
     * Scaled so that a single weight deviating by its full range (~50 units)
     * costs approximately 0.05 APFDc points: {@code 0.00002 × 50² = 0.05}.
     * This is enough to discourage gratuitous parameter shifts while allowing
     * the optimizer to move weights when there's clear evidence.
     *
     * @see <a href="https://doi.org/10.1007/978-0-387-84858-7">Hastie, Tibshirani, Friedman (2009):
     *      The Elements of Statistical Learning, §3.4 — Shrinkage Methods</a>
     */
    private static final double L2_LAMBDA = 0.00002;

    /**
     * Recency decay factor for expanding-window folds.
     * Each fold's score is weighted by {@code (1 − decay)^(numFolds − foldIndex)},
     * so recent folds contribute more. 0.15 means the oldest fold in a 10-fold
     * window retains about 23% of the weight of the newest fold.
     *
     * @see <a href="https://doi.org/10.1109/TSE.2020.2979736">Elsner et al. (2021):
     *      Empirically Evaluating Readily Available Information for Regression Test Optimization
     *      in Continuous Integration — §IV.C on recency-weighted historical models</a>
     */
    private static final double RECENCY_DECAY = 0.15;

    /**
     * Minimum training window as a fraction of total runs.
     * Expanding-window validation starts training on this fraction and grows
     * by one run per fold until it reaches the last run.
     */
    private static final double MIN_TRAIN_FRACTION = 0.5;

    /**
     * Overfitting threshold: if validation APFDc falls below this fraction of
     * the training APFDc, the optimized weights are considered overfit and
     * discarded in favor of the defaults.
     */
    private static final double OVERFIT_THRESHOLD = 0.85;

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
        long runsWithFailures = runs.stream().filter(r -> r.totalFailures() > 0).count();
        List<RunRecord> withFailures = runs.stream()
                .filter(r -> r.totalFailures() > 0 && !r.outcomes().isEmpty())
                .toList();
        long skippedOldFormat = runsWithFailures - withFailures.size();
        if (skippedOldFormat > 0) {
            LOG.warning("Optimizer skipped " + skippedOldFormat
                    + " run(s) with failures but no outcome data (pre-compact-format). "
                    + "These runs cannot contribute to weight optimisation.");
        }
        if (withFailures.size() < MIN_RUNS_FOR_OPTIMISATION) {
            return null;
        }

        // Snapshot of EMA durations for APFDc cost computation
        Map<String, Long> durSnapshot = Collections.unmodifiableMap(new LinkedHashMap<>(durations));

        // Default weights for L2 penalty baseline
        int[] defaults = ScoringWeights.DEFAULT.toArray();

        boolean useExpandingWindow = withFailures.size() >= 5;
        int minTrainSize = Math.max(2, (int) (withFailures.size() * MIN_TRAIN_FRACTION));
        int numFolds = useExpandingWindow ? withFailures.size() - minTrainSize : 0;

        // Build genotype factory: one IntegerChromosome per weight
        List<IntegerChromosome> chromosomes = defs.stream()
                .map(d -> IntegerChromosome.of(d.min(), d.max()))
                .toList();
        Factory<Genotype<IntegerGene>> gtf = Genotype.of(chromosomes);

        Engine<IntegerGene, Double> engine = Engine.builder(
                        gt -> {
                            int[] w = new int[defs.size()];
                            for (int i = 0; i < w.length; i++) {
                                w[i] = gt.get(i).gene().allele();
                            }
                            double l2Penalty = l2Penalty(w, defaults);
                            return useExpandingWindow
                                    ? evaluateExpandingWindow(w, withFailures, durSnapshot,
                                            minTrainSize, numFolds) - l2Penalty
                                    : evaluateWeights(w, withFailures, durSnapshot) - l2Penalty;
                        }, gtf)
                .optimize(Optimize.MAXIMUM)
                .populationSize(POPULATION_SIZE)
                .build();

        Genotype<IntegerGene> best = engine.stream()
                .limit(Limits.bySteadyFitness(STEADY_FITNESS_LIMIT))
                .limit(MAX_GENERATIONS)
                .collect(EvolutionResult.toBestGenotype());

        int[] result = new int[defs.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = best.get(i).gene().allele();
        }
        ScoringWeights optimized = ScoringWeights.fromArray(result);

        // Overfit check: compare training vs validation performance
        double trainScore, validationScore;
        boolean overfit = false;
        if (useExpandingWindow) {
            // Training: first minTrainSize runs; Validation: remaining runs
            List<RunRecord> trainRuns = withFailures.subList(0, minTrainSize);
            List<RunRecord> valRuns = withFailures.subList(minTrainSize, withFailures.size());
            trainScore = evaluateWeights(result, trainRuns, durSnapshot);
            validationScore = evaluateWeights(result, valRuns, durSnapshot);
            if (validationScore < trainScore * OVERFIT_THRESHOLD) {
                overfit = true;
            }
        } else {
            trainScore = evaluateWeights(result, withFailures, durSnapshot);
            validationScore = trainScore; // no separate validation possible
        }

        if (overfit) {
            LOG.warning("Optimizer detected overfitting: train=" + String.format("%.3f", trainScore)
                    + " validation=" + String.format("%.3f", validationScore)
                    + ". Falling back to default weights.");
            return new OptimizeResult(ScoringWeights.DEFAULT, trainScore, validationScore,
                    true, numFolds);
        }

        return new OptimizeResult(optimized, trainScore, validationScore, false, numFolds);
    }

    /** L2 penalty: {@code λ × Σ(wᵢ − defaultᵢ)²}. */
    static double l2Penalty(int[] w, int[] defaults) {
        double penalty = 0;
        int len = Math.min(w.length, defaults.length);
        for (int i = 0; i < len; i++) {
            double diff = w[i] - defaults[i];
            penalty += diff * diff;
        }
        return L2_LAMBDA * penalty;
    }

    /** Fitness: average APFDc across all runs. */
    static double evaluateWeights(int[] w, List<RunRecord> runs, Map<String, Long> durations) {
        ScoringWeights sw = ScoringWeights.fromArray(w);
        double sum = 0;
        for (RunRecord r : runs) {
            sum += computeAPFDcWithWeights(r.outcomes(), sw, durations);
        }
        return sum / runs.size();
    }

    /**
     * Expanding-window cross-validation with recency-weighted APFDc.
     * <p>
     * For each fold i (0-based), trains on runs {@code [0, minTrainSize + i)}
     * and validates on run {@code minTrainSize + i}.  Training score is the
     * average APFDc on the training window; the fold score is the APFDc on
     * the single validation run.  The final fitness is the recency-weighted
     * average of fold scores.
     * <p>
     * References:
     * <ul>
     *   <li>Hyndman &amp; Athanasopoulos (2021), <i>Forecasting: Principles and Practice</i>,
     *       §5.4 — Time series cross-validation (expanding window).</li>
     *   <li>Luo et al. (2019), <i>How Do Static and Dynamic Test Case Prioritization
     *       Techniques Perform on Modern Software Systems?</i>, TSE —
     *       demonstrates need for temporal validation in TCP.</li>
     * </ul>
     */
    static double evaluateExpandingWindow(int[] w, List<RunRecord> runs,
                                           Map<String, Long> durations,
                                           int minTrainSize, int numFolds) {
        ScoringWeights sw = ScoringWeights.fromArray(w);
        double weightedSum = 0;
        double weightTotal = 0;
        double retain = 1.0 - RECENCY_DECAY;

        for (int fold = 0; fold < numFolds; fold++) {
            int valIndex = minTrainSize + fold;
            if (valIndex >= runs.size()) break;
            RunRecord valRun = runs.get(valIndex);
            double foldScore = computeAPFDcWithWeights(valRun.outcomes(), sw, durations);

            // Recency weight: most recent fold gets weight 1.0
            double recencyWeight = Math.pow(retain, numFolds - 1 - fold);
            weightedSum += foldScore * recencyWeight;
            weightTotal += recencyWeight;
        }

        return weightTotal > 0 ? weightedSum / weightTotal : 0.0;
    }
}