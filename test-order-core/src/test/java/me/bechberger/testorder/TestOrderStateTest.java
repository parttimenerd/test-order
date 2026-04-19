package me.bechberger.testorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;

import net.jpountz.lz4.LZ4BlockInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TestOrderStateTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        TestOrderState.resetPending();
    }

    // --- Save / Load round-trip ---

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        TestOrderState state = new TestOrderState();
        state.setWeights(new TestOrderState.ScoringWeights(10, 8, 4, 2, 1, 3, 0));
        state.recordDuration("com.A", 100);
        state.recordDuration("com.B", 200);
        state.recordFailure("com.A");
        state.recordFailure("com.B");

        Path file = tempDir.resolve("state");
        state.save(file);
        TestOrderState loaded = TestOrderState.load(file);

        assertEquals(10, loaded.weights().newTest());
        assertEquals(8, loaded.weights().changedTest());
        assertEquals(4, loaded.weights().maxFailure());
        assertEquals(2, loaded.weights().speed());
        assertEquals(1, loaded.weights().speedPenalty());
        assertEquals(3, loaded.weights().depOverlap());
        assertEquals(100, loaded.getDuration("com.A", -1));
        assertEquals(200, loaded.getDuration("com.B", -1));
        // pending failures saved at full weight (no historical to decay)
        assertEquals(1.0, loaded.failureScore("com.A"), 0.001);
        assertEquals(1.0, loaded.failureScore("com.B"), 0.001);
    }

    @Test
    void loadNonExistentReturnsDefaults() throws IOException {
        TestOrderState state = TestOrderState.load(tempDir.resolve("missing"));
        assertEquals(TestOrderState.ScoringWeights.DEFAULT, state.weights());
        assertEquals(-1, state.getDuration("any", -1));
    }

    // --- Durations (EMA) ---

    @Test
    void durationEmaSmoothing() {
        TestOrderState state = new TestOrderState();
        state.recordDuration("com.A", 100);
        assertEquals(100, state.getDuration("com.A", -1));

        state.recordDuration("com.A", 200);
        // EMA: 0.85*200 + 0.15*100 = 185
        assertEquals(185, state.getDuration("com.A", -1));
    }

    @Test
    void durationDefaultValue() {
        TestOrderState state = new TestOrderState();
        assertEquals(-1, state.getDuration("unknown", -1));
        assertEquals(Long.MAX_VALUE, state.getDuration("unknown", Long.MAX_VALUE));
    }

    // --- Failures (decayed moving average) ---

    @Test
    void failureScoreAccumulatesAndDecays() throws IOException {
        TestOrderState state = new TestOrderState();
        state.recordFailure("com.A");
        state.recordFailure("com.A"); // two failures in same run
        assertEquals(2.0, state.failureScore("com.A"), 0.001);

        state.recordFailure("com.B"); // one failure
        assertEquals(1.0, state.failureScore("com.B"), 0.001);

        // After save/load, pending failures are saved at full weight (no historical to decay)
        Path file = tempDir.resolve("state");
        state.save(file);
        TestOrderState loaded = TestOrderState.load(file);
        assertEquals(2.0, loaded.failureScore("com.A"), 0.001);
        assertEquals(1.0, loaded.failureScore("com.B"), 0.001);

        // Second save with pending data triggers decay on historical
        loaded.recordFailure("com.C");
        loaded.save(file);
        TestOrderState loaded2 = TestOrderState.load(file);
        double retain = 1.0 - TestOrderState.DEFAULT_FAILURE_DECAY;
        assertEquals(2.0 * retain, loaded2.failureScore("com.A"), 0.001);
        assertEquals(1.0 * retain, loaded2.failureScore("com.B"), 0.001);
        assertEquals(1.0, loaded2.failureScore("com.C"), 0.001);

        // Scores returned by getFailureScores should match
        Map<String, Double> scores = loaded2.getFailureScores();
        assertTrue(scores.get("com.A") > scores.get("com.B"),
                "Class with more failures should score higher");
    }

    @Test
    void negligibleFailureScoresPruned() throws IOException {
        TestOrderState state = new TestOrderState();
        state.setFailureDecay(0.99); // aggressive decay
        state.recordFailure("com.A"); // pending = 1.0

        Path file = tempDir.resolve("state");
        state.save(file);
        TestOrderState loaded = TestOrderState.load(file);
        // Pending saved at full weight: 0 * 0.01 + 1.0 = 1.0
        assertEquals(1.0, loaded.failureScore("com.A"), 0.001);

        // Second save with pending triggers decay: 1.0 * 0.01 = 0.01, at threshold
        loaded.recordFailure("com.DUMMY");
        loaded.save(file);
        TestOrderState loaded2 = TestOrderState.load(file);
        assertEquals(0.01, loaded2.failureScore("com.A"), 0.001);

        // Third save with pending: 0.01 * 0.01 = 0.0001, below threshold → pruned
        loaded2.recordFailure("com.DUMMY2");
        loaded2.save(file);
        TestOrderState loaded3 = TestOrderState.load(file);
        assertEquals(0.0, loaded3.failureScore("com.A"), 0.001);
    }

    // --- Run history ---

    @Test
    void runHistoryCapping() {
        TestOrderState state = new TestOrderState();
        for (int i = 0; i < 60; i++) {
            state.addRunRecord(new TestOrderState.RunRecord(
                    i, 10, 0, -1, 1.0, List.of()));
        }
        assertEquals(TestOrderState.MAX_HISTORY_RUNS, state.runs().size());
        // most recent should be kept
        assertEquals(59, state.runs().get(state.runs().size() - 1).timestamp());
    }

    @Test
    void zeroFailureRunOutcomesOmittedOnSave() throws IOException {
        TestOrderState state = new TestOrderState();
        // Run with failures — outcomes should survive round-trip
        List<TestOrderState.TestOutcome> withFail = List.of(
                new TestOrderState.TestOutcome("com.A", 5, true, false, 1, 0, 0.5, true, false, true, 0.0));
        state.addRunRecord(new TestOrderState.RunRecord(1L, 1, 1, 0, 0.75, withFail));
        // Run without failures — outcomes should be dropped on save
        List<TestOrderState.TestOutcome> noFail = List.of(
                new TestOrderState.TestOutcome("com.B", 3, false, true, 0, 0, 0.0, false, false, false, 0.0));
        state.addRunRecord(new TestOrderState.RunRecord(2L, 1, 0, -1, 1.0, noFail));

        Path file = tempDir.resolve("state");
        state.save(file);
        TestOrderState loaded = TestOrderState.load(file);

        assertEquals(2, loaded.runs().size());
        // run with failures keeps outcomes (scoring fields only, testClass/totalScore dropped)
        assertEquals(1, loaded.runs().get(0).outcomes().size());
        // run without failures has empty outcomes
        assertTrue(loaded.runs().get(1).outcomes().isEmpty());
    }

    @Test
    void runHistoryRoundTrip() throws IOException {
        TestOrderState state = new TestOrderState();
        List<TestOrderState.TestOutcome> outcomes = List.of(
                new TestOrderState.TestOutcome("com.A", 5, true, false, 1, 0, 0.5, true, false, true, 0.0),
                new TestOrderState.TestOutcome("com.B", 3, false, true, 0, 0, 0.0, false, false, false, 0.0)
        );
        state.addRunRecord(new TestOrderState.RunRecord(
                123456789L, 2, 1, 0, 0.75, outcomes));

        Path file = tempDir.resolve("state");
        state.save(file);
        TestOrderState loaded = TestOrderState.load(file);

        assertEquals(1, loaded.runs().size());
        TestOrderState.RunRecord r = loaded.runs().get(0);
        assertEquals(123456789L, r.timestamp());
        assertEquals(2, r.totalTests());
        assertEquals(1, r.totalFailures());
        assertEquals(0, r.firstFailurePosition());
        assertEquals(0.75, r.apfd(), 0.001);
        assertEquals(2, r.outcomes().size());

        TestOrderState.TestOutcome o = r.outcomes().get(0);
        // compact format preserves testClass (needed by APFDc optimizer)
        // but drops totalScore (recomputed by reorderComparator)
        assertEquals("com.A", o.testClass());
        assertTrue(o.isNew());
        assertFalse(o.isChanged());
        assertEquals(1, o.depOverlap());
        assertEquals(0.5, o.failScore(), 0.001);
        assertTrue(o.isFast());
        assertTrue(o.failed());
    }

    // --- Compact outcome format ---

    @Test
    void compactOutcomeRoundTrip() {
        // All fields zero except flags → still full 7-element list
        var outcome = new TestOrderState.TestOutcome("X", 99, true, false, 0, 0, 0.0, true, false, true, 0.0);
        List<Object> compact = TestOrderState.outcomeToCompact(outcome);
        assertEquals(7, compact.size());
        assertEquals("X", compact.get(0));
        // flags: isNew=1, isFast=4, failed=16 → 21
        assertEquals(21, compact.get(1));
        assertEquals(0, compact.get(2));   // depOverlap
        assertEquals(0, compact.get(3));   // depTotal
        assertEquals(0.0, compact.get(4)); // failScore
        assertEquals(0.0, compact.get(5)); // complexityOverlap
        assertEquals(0.0, compact.get(6)); // speedRatio

        TestOrderState.TestOutcome restored = TestOrderState.compactToOutcome(compact);
        assertEquals("X", restored.testClass());
        assertTrue(restored.isNew());
        assertFalse(restored.isChanged());
        assertTrue(restored.isFast());
        assertFalse(restored.isSlow());
        assertTrue(restored.failed());
        assertEquals(0, restored.depOverlap());
        assertEquals(0.0, restored.failScore());
    }

    @Test
    void compactOutcomeFullFields() {
        // All fields populated
        var outcome = new TestOrderState.TestOutcome("Y", 7, false, true, 3, 5, 1.5, false, true, false, 0.0);
        List<Object> compact = TestOrderState.outcomeToCompact(outcome);
        assertEquals(7, compact.size());
        assertEquals("Y", compact.get(0));

        TestOrderState.TestOutcome restored = TestOrderState.compactToOutcome(compact);
        assertEquals("Y", restored.testClass());
        assertFalse(restored.isNew());
        assertTrue(restored.isChanged());
        assertEquals(3, restored.depOverlap());
        assertEquals(5, restored.depTotal());
        assertEquals(1.5, restored.failScore(), 0.001);
        assertFalse(restored.isFast());
        assertTrue(restored.isSlow());
        assertFalse(restored.failed());
    }

    @Test
    void compactOutcomeSkipsLegacyFormats() {
        // Plain integer (completely wrong type) → null, graceful skip so stale state files
        // do not crash test discovery
        assertNull(TestOrderState.compactToOutcome(21));

        // Map (old key-value format) → null, graceful skip
        Map<String, Object> legacyMap = Map.of("class", "com.Old", "score", 5,
                "isNew", true, "isChanged", false, "depOverlap", 2,
                "depTotal", 4, "failScore", 0.8, "isFast", false,
                "isSlow", false, "failed", true);
        assertNull(TestOrderState.compactToOutcome(legacyMap));

        // All-numeric list (old numeric-only compact format, e.g. [4.0, 0.0, 2.0, 0.0])
        // → null, graceful skip — this was the crash case in petclinic with a stale state file
        assertNull(TestOrderState.compactToOutcome(List.of(4.0, 0.0, 2.0, 0.0)));
    }

    // --- APFD ---

    @Test
    void apfdAllPassedIsOne() {
        List<TestOrderState.TestOutcome> outcomes = List.of(
                new TestOrderState.TestOutcome("A", 0, false, false, 0, 0, 0.0, false, false, false, 0.0),
                new TestOrderState.TestOutcome("B", 0, false, false, 0, 0, 0.0, false, false, false, 0.0)
        );
        assertEquals(1.0, TestOrderState.computeAPFD(outcomes));
    }

    @Test
    void apfdFirstTestFails() {
        List<TestOrderState.TestOutcome> outcomes = List.of(
                new TestOrderState.TestOutcome("A", 0, false, false, 0, 0, 0.0, false, false, true, 0.0),
                new TestOrderState.TestOutcome("B", 0, false, false, 0, 0, 0.0, false, false, false, 0.0)
        );
        // APFD = 1 - 1/(2*1) + 1/(2*2) = 1 - 0.5 + 0.25 = 0.75
        assertEquals(0.75, TestOrderState.computeAPFD(outcomes), 0.001);
    }

    @Test
    void apfdLastTestFails() {
        List<TestOrderState.TestOutcome> outcomes = List.of(
                new TestOrderState.TestOutcome("A", 0, false, false, 0, 0, 0.0, false, false, false, 0.0),
                new TestOrderState.TestOutcome("B", 0, false, false, 0, 0, 0.0, false, false, true, 0.0)
        );
        // APFD = 1 - 2/(2*1) + 1/(2*2) = 1 - 1 + 0.25 = 0.25
        assertEquals(0.25, TestOrderState.computeAPFD(outcomes), 0.001);
    }

    // --- Static coordination ---

    @Test
    void pendingBreakdownsCoordination() {
        TestOrderState.resetPending();
        assertFalse(TestOrderState.hasPendingData());

        TestOrderState.setStatePath("/tmp/state");
        TestOrderState.recordBreakdown("com.A",
                new TestOrderState.ScoreBreakdown(5, true, false, 1, 0, 0.5, true, false, 0.0));

        assertTrue(TestOrderState.hasPendingData());
        assertEquals(1, TestOrderState.getPendingBreakdowns().size());

        TestOrderState.resetPending();
        assertFalse(TestOrderState.hasPendingData());
    }

    @Test
    void buildRunRecord() {
        TestOrderState.resetPending();
        TestOrderState.setStatePath("/tmp/state");
        TestOrderState.recordBreakdown("com.A",
                new TestOrderState.ScoreBreakdown(5, true, false, 1, 0, 0.5, true, false, 0.0));
        TestOrderState.recordBreakdown("com.B",
                new TestOrderState.ScoreBreakdown(3, false, true, 0, 0, 0.0, false, false, 0.0));

        List<String> order = List.of("com.A", "com.B");
        Set<String> failed = Set.of("com.B");

        TestOrderState.RunRecord record = TestOrderState.buildRunRecord(order, failed);
        assertEquals(2, record.totalTests());
        assertEquals(1, record.totalFailures());
        assertEquals(1, record.firstFailurePosition());
        assertEquals(2, record.outcomes().size());
        assertTrue(record.outcomes().get(1).failed());
        assertFalse(record.outcomes().get(0).failed());
    }

    // --- Weights from state file ---

    @Test
    void weightsLoadedFromStateFile() throws IOException {
        TestOrderState state = new TestOrderState();
        state.setWeights(new TestOrderState.ScoringWeights(20, 12, 7, 3, 2, 4, 0));
        Path file = tempDir.resolve("state");
        state.save(file);

        TestOrderState loaded = TestOrderState.load(file);
        assertEquals(20, loaded.weights().newTest());
        assertEquals(12, loaded.weights().changedTest());
        assertEquals(7, loaded.weights().maxFailure());
        assertEquals(3, loaded.weights().speed());
        assertEquals(2, loaded.weights().speedPenalty());
        assertEquals(4, loaded.weights().depOverlap());
    }

    // --- Optimizer ---

    @Test
    void optimizeReturnsNullWithInsufficientData() {
        TestOrderState state = new TestOrderState();
        // no runs at all
        assertNull(state.optimize());

        // add runs without failures
        for (int i = 0; i < 5; i++) {
            state.addRunRecord(new TestOrderState.RunRecord(i, 10, 0, -1, 1.0, List.of()));
        }
        assertNull(state.optimize());
    }

    @Test
    void optimizeReturnsWeightsWithSufficientData() {
        TestOrderState state = new TestOrderState();
        // create runs with failures and outcomes
        for (int i = 0; i < 5; i++) {
            List<TestOrderState.TestOutcome> outcomes = List.of(
                    new TestOrderState.TestOutcome("A", 5, true, false, 1, 0, 0.5, true, false, true, 0.0),
                    new TestOrderState.TestOutcome("B", 3, false, true, 0, 0, 0.0, false, false, false, 0.0),
                    new TestOrderState.TestOutcome("C", 1, false, false, 0, 0, 0.0, false, false, false, 0.0)
            );
            state.addRunRecord(new TestOrderState.RunRecord(i, 3, 1, 0, 0.833, outcomes));
        }
        TestOrderState.OptimizeResult opt = state.optimize();
        assertNotNull(opt);
        assertFalse(opt.overfit());
        assertTrue(opt.weights().newTest() >= 0);
        assertTrue(opt.weights().changedTest() >= 0);
    }

    // --- Failure decay on save ---

    @Test
    void failureScoresDecayAcrossMultipleSaves() throws IOException {
        TestOrderState state = new TestOrderState();
        state.recordFailure("com.A"); // pending = 1.0

        Path file = tempDir.resolve("state");
        double retain = 1.0 - TestOrderState.DEFAULT_FAILURE_DECAY;

        // First save: pending at full weight (no historical to decay)
        state.save(file);
        TestOrderState l1 = TestOrderState.load(file);
        assertEquals(1.0, l1.failureScore("com.A"), 0.001);

        // Second save with pending triggers decay
        l1.recordFailure("com.B");
        l1.save(file);
        TestOrderState l2 = TestOrderState.load(file);
        assertEquals(1.0 * retain, l2.failureScore("com.A"), 0.001);

        // Third save with pending decays again
        l2.recordFailure("com.C");
        l2.save(file);
        TestOrderState l3 = TestOrderState.load(file);
        assertEquals(1.0 * retain * retain, l3.failureScore("com.A"), 0.001);
    }

    @Test
    void failureScoresDecayOnAllPassRun() throws IOException {
        TestOrderState state = new TestOrderState();
        state.recordFailure("com.A"); // initial failure

        Path file = tempDir.resolve("state");
        state.save(file);
        TestOrderState l1 = TestOrderState.load(file);
        assertEquals(1.0, l1.failureScore("com.A"), 0.001);

        // Simulate an all-pass run: addRunRecord but no recordFailure
        l1.addRunRecord(TestOrderState.buildRunRecord(List.of("com.A"), Set.of()));
        l1.save(file);
        TestOrderState l2 = TestOrderState.load(file);

        double retain = 1.0 - TestOrderState.DEFAULT_FAILURE_DECAY;
        assertEquals(1.0 * retain, l2.failureScore("com.A"), 0.001,
                "failure score should decay even when all tests pass");
    }

    // --- WeightDef parsing ---

    @Test
    void weightDefsLoadedFromResource() {
        assertFalse(TestOrderState.WEIGHT_DEFS.isEmpty());
        assertEquals("newTest", TestOrderState.WEIGHT_DEFS.get(0).name());
        // verify range is parsed from TOML tables
        assertEquals(0, TestOrderState.WEIGHT_DEFS.get(0).min());
        assertEquals(50, TestOrderState.WEIGHT_DEFS.get(0).max());
    }

    @Test
    void defaultWeightsMatchResource() {
        TestOrderState.ScoringWeights def = TestOrderState.ScoringWeights.DEFAULT;
        for (TestOrderState.WeightDef wd : TestOrderState.WEIGHT_DEFS) {
            assertTrue(def.toMap().containsKey(wd.name()),
                    "DEFAULT should have key: " + wd.name());
            assertEquals(wd.defaultValue(), def.toMap().get(wd.name()),
                    "DEFAULT value for " + wd.name());
        }
    }

    // --- Weights file I/O ---

    @Test
    void weightsFileSaveAndLoad() throws IOException {
        TestOrderState.ScoringWeights original = new TestOrderState.ScoringWeights(20, 12, 7, 3, 2, 4, 0);
        Path file = tempDir.resolve("weights.toml");
        original.saveToFile(file);

        String content = Files.readString(file);
        assertTrue(content.contains("[newTest]"), "should contain TOML table");
        assertTrue(content.contains("value = 20"), "should contain value");
        assertTrue(content.contains("range"), "should contain range");

        TestOrderState.LoadedWeights loaded = TestOrderState.ScoringWeights.loadFromFile(file);
        assertEquals(20, loaded.weights().newTest());
        assertEquals(12, loaded.weights().changedTest());
        assertEquals(7, loaded.weights().maxFailure());
        assertEquals(3, loaded.weights().speed());
        assertEquals(2, loaded.weights().speedPenalty());
        assertEquals(4, loaded.weights().depOverlap());
    }

    @Test
    void loadSimpleTomlFormat() throws IOException {
        // user can write simple key = value format
        String simple = """
                newTest = 20
                changedTest = 12
                """;
        Path file = tempDir.resolve("simple.toml");
        Files.writeString(file, simple);

        TestOrderState.LoadedWeights loaded = TestOrderState.ScoringWeights.loadFromFile(file);
        assertEquals(20, loaded.weights().newTest());
        assertEquals(12, loaded.weights().changedTest());
        // unspecified keys use defaults
        assertEquals(TestOrderState.ScoringWeights.DEFAULT.maxFailure(), loaded.weights().maxFailure());
    }

    @Test
    void loadTableFormatWithRangeOverride() throws IOException {
        // user can override optimizer ranges
        String toml = """
                [newTest]
                value = 25
                range = [5, 30]
                """;
        Path file = tempDir.resolve("custom-range.toml");
        Files.writeString(file, toml);

        TestOrderState.LoadedWeights loaded = TestOrderState.ScoringWeights.loadFromFile(file);
        assertEquals(25, loaded.weights().newTest());
        // verify merged defs have the overridden range
        TestOrderState.WeightDef newTestDef = loaded.defs().stream()
                .filter(d -> d.name().equals("newTest")).findFirst().orElseThrow();
        assertEquals(5, newTestDef.min());
        assertEquals(30, newTestDef.max());
        // other defs keep default ranges
        TestOrderState.WeightDef speedDef = loaded.defs().stream()
                .filter(d -> d.name().equals("speed")).findFirst().orElseThrow();
        assertEquals(0, speedDef.min());
        assertEquals(10, speedDef.max());
    }

    @Test
    void weightsFromMapMissingKeysUseDefaults() {
        TestOrderState.ScoringWeights w = TestOrderState.ScoringWeights.fromMap(Map.of("newTest", 99));
        assertEquals(99, w.newTest());
        assertEquals(TestOrderState.ScoringWeights.DEFAULT.changedTest(), w.changedTest());
    }

    @Test
    void weightsToArrayAndBack() {
        TestOrderState.ScoringWeights original = new TestOrderState.ScoringWeights(10, 8, 4, 2, 1, 3, 0);
        int[] arr = original.toArray();
        TestOrderState.ScoringWeights roundTripped = TestOrderState.ScoringWeights.fromArray(arr);
        assertEquals(original, roundTripped);
    }

    // --- JSON format ---

    @Test
    void saveProducesLz4CompressedJson() throws IOException {
        TestOrderState state = new TestOrderState();
        state.setWeights(new TestOrderState.ScoringWeights(10, 8, 4, 2, 1, 3, 0));
        state.recordDuration("com.A", 100);

        Path file = tempDir.resolve("state.json");
        state.save(file);

        // File should NOT start with '{' — it's LZ4 compressed
        byte[] raw = Files.readAllBytes(file);
        assertNotEquals((byte) '{', raw[0], "saved file should be LZ4 compressed, not plain JSON");

        // Decompressed content should be valid JSON
        String json;
        try (var in = new LZ4BlockInputStream(new ByteArrayInputStream(raw))) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        assertTrue(json.startsWith("{"), "decompressed content should be JSON");
        assertTrue(json.contains("\"weights\""), "should contain weights key");
        assertTrue(json.contains("\"durations\""), "should contain durations key");
    }

    @Test
    void loadPlainJsonBackwardCompat() throws IOException {
        // Simulate an old-format plain JSON state file (uncompressed)
        String plainJson = """
                {
                  "weights": {"newTest": 15, "changedTest": 8, "maxFailure": 4, "speed": 2, "speedPenalty": 1, "depOverlap": 3},
                  "durations": {"com.A": 100},
                  "failureScores": {},
                  "runs": []
                }
                """;
        Path file = tempDir.resolve("legacy-state");
        Files.writeString(file, plainJson);

        TestOrderState loaded = TestOrderState.load(file);
        assertEquals(15, loaded.weights().newTest());
        assertEquals(100, loaded.getDuration("com.A", -1));
    }

    @Test
    void loadOldEmaAlphaKeyIsIgnored() throws IOException {
        // Old emaAlpha key is no longer migrated — defaults should be used
        String plainJson = """
                {
                  "config": {"emaAlpha": 0.5},
                  "weights": {"newTest": 15, "changedTest": 9, "maxFailure": 5, "speed": 1, "speedPenalty": 1, "depOverlap": 5},
                  "durations": {},
                  "failureScores": {},
                  "runs": []
                }
                """;
        Path file = tempDir.resolve("old-ema-state");
        Files.writeString(file, plainJson);

        TestOrderState loaded = TestOrderState.load(file);
        // emaAlpha is ignored; defaults should be used
        assertEquals(TestOrderState.DEFAULT_DURATION_ALPHA, loaded.durationAlpha(), 0.001);
        assertEquals(TestOrderState.DEFAULT_METHOD_DURATION_ALPHA, loaded.methodDurationAlpha(), 0.001);
    }

    @Test
    void parseWeightDefsFromSimpleToml() {
        CommentedConfig config = new TomlParser().parse("newTest = 15\nspeed = 3\n");
        List<TestOrderState.WeightDef> defs = TestOrderState.parseWeightDefs(config);
        assertEquals(2, defs.size());
        var byName = new java.util.HashMap<String, TestOrderState.WeightDef>();
        defs.forEach(d -> byName.put(d.name(), d));
        assertEquals(15, byName.get("newTest").defaultValue());
        assertEquals(-1, byName.get("newTest").min()); // unset in simple format
        assertEquals(-1, byName.get("newTest").max());
        assertEquals(3, byName.get("speed").defaultValue());
    }

    @Test
    void parseWeightDefsFromTableToml() {
        String toml = """
                [newTest]
                value = 15
                range = [0, 50]

                [speed]
                value = 3
                """;
        CommentedConfig config = new TomlParser().parse(toml);
        List<TestOrderState.WeightDef> defs = TestOrderState.parseWeightDefs(config);
        assertEquals(2, defs.size());
        var byName = new java.util.HashMap<String, TestOrderState.WeightDef>();
        defs.forEach(d -> byName.put(d.name(), d));
        assertEquals(15, byName.get("newTest").defaultValue());
        assertEquals(0, byName.get("newTest").min());
        assertEquals(50, byName.get("newTest").max());
        assertEquals(3, byName.get("speed").defaultValue());
        assertEquals(-1, byName.get("speed").min()); // unset when range absent
        assertEquals(-1, byName.get("speed").max());
    }
}
