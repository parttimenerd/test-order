package me.bechberger.testorder;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PriorityClassOrderer using system properties and mock ClassOrdererContext.
 */
class PriorityClassOrdererTest {

    @TempDir
    Path tempDir;

    private String origIndexPath;
    private String origChangedClasses;
    private String origChangedClassesFile;
    private String origStatePath;
    private String origChangedTestClasses;
    private String origScoreNewTest;
    private String origScoreChangedTest;
    private String origScoreMaxFailure;
    private String origScoreSpeed;
    private String origScoreSpeedPenalty;
    private String origScoreDepOverlap;

    @BeforeEach
    void saveProperties() {
        origIndexPath = System.getProperty("testorder.index.path");
        origChangedClasses = System.getProperty("testorder.changed.classes");
        origChangedClassesFile = System.getProperty("testorder.changed.classes.file");
        origStatePath = System.getProperty("testorder.state.path");
        origChangedTestClasses = System.getProperty("testorder.changed.test.classes");
        origScoreNewTest = System.getProperty("testorder.score.newTest");
        origScoreChangedTest = System.getProperty("testorder.score.changedTest");
        origScoreMaxFailure = System.getProperty("testorder.score.maxFailure");
        origScoreSpeed = System.getProperty("testorder.score.speed");
        origScoreSpeedPenalty = System.getProperty("testorder.score.speedPenalty");
        origScoreDepOverlap = System.getProperty("testorder.score.depOverlap");
    }

    @AfterEach
    void restoreProperties() {
        restoreProp("testorder.index.path", origIndexPath);
        restoreProp("testorder.changed.classes", origChangedClasses);
        restoreProp("testorder.changed.classes.file", origChangedClassesFile);
        restoreProp("testorder.state.path", origStatePath);
        restoreProp("testorder.changed.test.classes", origChangedTestClasses);
        restoreProp("testorder.score.newTest", origScoreNewTest);
        restoreProp("testorder.score.changedTest", origScoreChangedTest);
        restoreProp("testorder.score.maxFailure", origScoreMaxFailure);
        restoreProp("testorder.score.speed", origScoreSpeed);
        restoreProp("testorder.score.speedPenalty", origScoreSpeedPenalty);
        restoreProp("testorder.score.depOverlap", origScoreDepOverlap);
        TestOrderState.resetPending();
    }

    private void restoreProp(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    /** Helper: save state file and set the system property. */
    private void setupState(TestOrderState state) throws IOException {
        Path stateFile = tempDir.resolve(".test-order-state");
        state.save(stateFile);
        System.setProperty("testorder.state.path", stateFile.toString());
    }

    @Test
    void noIndexPathDoesNotReorder() {
        System.clearProperty("testorder.index.path");
        System.clearProperty("testorder.changed.classes");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        orderer.orderClasses(new StubClassOrdererContext(
                List.of(desc(String.class), desc(Integer.class))));
    }

    @Test
    void missingIndexFileDoesNotReorder() {
        System.setProperty("testorder.index.path", tempDir.resolve("nonexistent.idx").toString());
        System.clearProperty("testorder.changed.classes");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        orderer.orderClasses(new StubClassOrdererContext(
                List.of(desc(String.class), desc(Integer.class))));
    }

    @Test
    void noChangedClassesDoesNotReorder() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put("java.lang.String", Set.of("java.lang.StringBuilder"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        System.setProperty("testorder.index.path", idx.toString());
        System.clearProperty("testorder.changed.classes");
        System.clearProperty("testorder.changed.classes.file");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(String.class), desc(Integer.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));
    }

    @Test
    void ordersAffectedTestsFirst() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of("com.example.Foo", "com.example.Bar"));
        map.put(Integer.class.getName(), Set.of("com.example.Baz"));
        map.put(Long.class.getName(), Set.of("com.example.Foo", "com.example.Baz"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        System.setProperty("testorder.index.path", idx.toString());
        System.setProperty("testorder.changed.classes", "com.example.Foo");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(Integer.class), desc(Long.class), desc(String.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        assertEquals(Integer.class.getName(), descs.get(descs.size() - 1).getTestClass().getName());
    }

    @Test
    void multipleChangedClassesScoreHigher() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of("com.A", "com.B", "com.C"));
        map.put(Integer.class.getName(), Set.of("com.A", "com.D", "com.E"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        System.setProperty("testorder.index.path", idx.toString());
        System.setProperty("testorder.changed.classes", "com.A,com.B");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(Integer.class), desc(String.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        assertEquals(String.class.getName(), descs.get(0).getTestClass().getName());
        assertEquals(Integer.class.getName(), descs.get(1).getTestClass().getName());
    }

    @Test
    void changedClassesFromFile() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of("com.X"));
        map.put(Integer.class.getName(), Set.of("com.Y"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        Path changedFile = tempDir.resolve("changed.txt");
        Files.writeString(changedFile, "com.X\n");

        System.setProperty("testorder.index.path", idx.toString());
        System.clearProperty("testorder.changed.classes");
        System.setProperty("testorder.changed.classes.file", changedFile.toString());

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(Integer.class), desc(String.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        assertEquals(String.class.getName(), descs.get(0).getTestClass().getName());
    }

    @Test
    void unknownTestClassGetsNewTestBonus() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of("com.X"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        System.setProperty("testorder.index.path", idx.toString());
        System.setProperty("testorder.changed.classes", "com.X");
        System.clearProperty("testorder.state.path");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(Integer.class), desc(String.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        // String: dep=1, Integer: new=15 → Integer first
        assertEquals(Integer.class.getName(), descs.get(0).getTestClass().getName());
    }

    @Test
    void failedTestsGetFrequencyBasedBonus() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of("com.X"));
        map.put(Integer.class.getName(), Set.of("com.Y"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        long now = System.currentTimeMillis();
        long oneDay = 24 * 60 * 60 * 1000L;
        TestOrderState state = new TestOrderState();
        state.recordFailure(Integer.class.getName(), now - oneDay);
        state.recordFailure(Integer.class.getName(), now - oneDay * 2);
        state.recordFailure(Integer.class.getName(), now - oneDay * 3);
        setupState(state);

        System.setProperty("testorder.index.path", idx.toString());
        System.setProperty("testorder.changed.classes", "com.X");
        System.setProperty("testorder.score.depOverlap", "1");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(String.class), desc(Integer.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        // Integer: fail≈2 → 2;  String: dep=1 → 1
        assertEquals(Integer.class.getName(), descs.get(0).getTestClass().getName());
        assertEquals(String.class.getName(), descs.get(1).getTestClass().getName());
    }

    @Test
    void recentFailuresWeightedHigher() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of());
        map.put(Integer.class.getName(), Set.of());
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        // Simulate past run: Integer had one failure (saved at full weight)
        TestOrderState state = new TestOrderState();
        state.recordFailure(Integer.class.getName());
        Path stateFile = tempDir.resolve(".test-order-state");
        state.save(stateFile); // Integer stored: 1.0

        // Simulate current run: String has fresh failures (more than Integer's decayed score)
        TestOrderState state2 = TestOrderState.load(stateFile);
        state2.recordFailure(String.class.getName());
        state2.recordFailure(String.class.getName());
        state2.recordFailure(String.class.getName());
        setupState(state2); // saved: Integer=1.0*0.7=0.7(ceil→1), String=3.0(ceil→3)

        System.setProperty("testorder.index.path", idx.toString());
        System.clearProperty("testorder.changed.classes");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(Integer.class), desc(String.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        assertEquals(String.class.getName(), descs.get(0).getTestClass().getName());
        assertEquals(Integer.class.getName(), descs.get(1).getTestClass().getName());
    }

    @Test
    void newTestClassesGetBonus() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of("com.X"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        System.setProperty("testorder.index.path", idx.toString());
        System.clearProperty("testorder.changed.classes");
        System.clearProperty("testorder.state.path");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(String.class), desc(Integer.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        assertEquals(Integer.class.getName(), descs.get(0).getTestClass().getName());
        assertEquals(String.class.getName(), descs.get(1).getTestClass().getName());
    }

    @Test
    void combinedScoring() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of("com.X", "com.Y"));
        map.put(Integer.class.getName(), Set.of("com.X"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        long now = System.currentTimeMillis();
        long oneDay = 24 * 60 * 60 * 1000L;
        TestOrderState state = new TestOrderState();
        state.recordFailure(String.class.getName(), now - oneDay);
        state.recordFailure(String.class.getName(), now - oneDay * 2);
        state.recordFailure(String.class.getName(), now - oneDay * 3);
        setupState(state);

        System.setProperty("testorder.index.path", idx.toString());
        System.setProperty("testorder.changed.classes", "com.X,com.Y");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(Long.class), desc(Integer.class), desc(String.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        // Long: new=15;  String: dep=2+fail≈2=4;  Integer: dep=1
        assertEquals(Long.class.getName(), descs.get(0).getTestClass().getName());
        assertEquals(String.class.getName(), descs.get(1).getTestClass().getName());
        assertEquals(Integer.class.getName(), descs.get(2).getTestClass().getName());
    }

    @Test
    void changedTestClassesGetHighestBonus() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of("com.X"));
        map.put(Integer.class.getName(), Set.of("com.X"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        long now = System.currentTimeMillis();
        long oneDay = 24 * 60 * 60 * 1000L;
        TestOrderState state = new TestOrderState();
        state.recordFailure(Integer.class.getName(), now - oneDay);
        state.recordFailure(Integer.class.getName(), now - oneDay * 2);
        state.recordFailure(Integer.class.getName(), now - oneDay * 3);
        state.recordFailure(Integer.class.getName(), now - oneDay * 4);
        setupState(state);

        System.setProperty("testorder.index.path", idx.toString());
        System.setProperty("testorder.changed.classes", "com.X");
        System.setProperty("testorder.changed.test.classes", String.class.getName());

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(Integer.class), desc(String.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        // String: dep=1+changed=9=10;  Integer: dep=1+fail≈2=3
        assertEquals(String.class.getName(), descs.get(0).getTestClass().getName());
        assertEquals(Integer.class.getName(), descs.get(1).getTestClass().getName());
    }

    @Test
    void durationTiebreaker() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of("com.X"));
        map.put(Integer.class.getName(), Set.of("com.X"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        TestOrderState state = new TestOrderState();
        state.recordDuration(String.class.getName(), 100);
        state.recordDuration(Integer.class.getName(), 5000);
        setupState(state);

        System.setProperty("testorder.index.path", idx.toString());
        System.setProperty("testorder.changed.classes", "com.X");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(Integer.class), desc(String.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        // Both score 1+speed(String<median) → String=2, Integer=1
        // Or both score 1 with duration tiebreak → String first
        assertEquals(String.class.getName(), descs.get(0).getTestClass().getName());
        assertEquals(Integer.class.getName(), descs.get(1).getTestClass().getName());
    }

    @Test
    void failureCountIsCapped() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of("com.X"));
        map.put(Integer.class.getName(), Set.of("com.X"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        long now = System.currentTimeMillis();
        long oneDay = 24 * 60 * 60 * 1000L;
        TestOrderState state = new TestOrderState();
        for (int i = 1; i <= 8; i++) {
            state.recordFailure(Integer.class.getName(), now - oneDay * i);
        }
        setupState(state);

        System.setProperty("testorder.index.path", idx.toString());
        System.setProperty("testorder.changed.classes", "com.X");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(String.class), desc(Integer.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        // Integer: dep=1+fail(capped)=boosted;  String: dep=1
        assertEquals(Integer.class.getName(), descs.get(0).getTestClass().getName());
        assertEquals(String.class.getName(), descs.get(1).getTestClass().getName());
    }

    @Test
    void speedBonusForFastTests() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of());
        map.put(Integer.class.getName(), Set.of());
        map.put(Long.class.getName(), Set.of());
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        TestOrderState state = new TestOrderState();
        state.recordDuration(String.class.getName(), 100);
        state.recordDuration(Integer.class.getName(), 500);
        state.recordDuration(Long.class.getName(), 2000);
        setupState(state);

        System.setProperty("testorder.index.path", idx.toString());
        System.clearProperty("testorder.changed.classes");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(Long.class), desc(Integer.class), desc(String.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        // String: speed=1;  Integer+Long: 0. Tiebreak by duration.
        assertEquals(String.class.getName(), descs.get(0).getTestClass().getName());
        assertEquals(Integer.class.getName(), descs.get(1).getTestClass().getName());
        assertEquals(Long.class.getName(), descs.get(2).getTestClass().getName());
    }

    @Test
    void speedPenaltyForSlowTests() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of("com.X"));
        map.put(Integer.class.getName(), Set.of("com.X"));
        map.put(Long.class.getName(), Set.of("com.X"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        TestOrderState state = new TestOrderState();
        state.recordDuration(String.class.getName(), 100);
        state.recordDuration(Integer.class.getName(), 500);
        state.recordDuration(Long.class.getName(), 2000);
        setupState(state);

        System.setProperty("testorder.index.path", idx.toString());
        System.setProperty("testorder.changed.classes", "com.X");
        System.setProperty("testorder.score.speedPenalty", "3");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(Long.class), desc(Integer.class), desc(String.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        // String: dep=1+speed=1=2;  Integer: dep=1 (median);  Long: dep=1-penalty=3=-2
        assertEquals(String.class.getName(), descs.get(0).getTestClass().getName());
        assertEquals(Integer.class.getName(), descs.get(1).getTestClass().getName());
        assertEquals(Long.class.getName(), descs.get(2).getTestClass().getName());
    }

    // --- Tests for configurable scoring ---

    @Test
    void customNewTestBonus() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of("com.X"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        System.setProperty("testorder.index.path", idx.toString());
        System.clearProperty("testorder.state.path");
        System.setProperty("testorder.score.newTest", "2");
        System.setProperty("testorder.score.depOverlap", "1");
        System.setProperty("testorder.changed.classes", "com.X");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(Integer.class), desc(String.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        // String: dep=1;  Integer: newTest=2 → Integer first
        assertEquals(Integer.class.getName(), descs.get(0).getTestClass().getName());
        assertEquals(String.class.getName(), descs.get(1).getTestClass().getName());
    }

    @Test
    void customNewTestBonusZeroDisablesIt() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of("com.X"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        System.setProperty("testorder.index.path", idx.toString());
        System.setProperty("testorder.changed.classes", "com.X");
        System.clearProperty("testorder.state.path");
        System.setProperty("testorder.score.newTest", "0");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(Integer.class), desc(String.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        assertEquals(String.class.getName(), descs.get(0).getTestClass().getName());
    }

    @Test
    void customChangedTestBonus() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of("com.X"));
        map.put(Integer.class.getName(), Set.of("com.X"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        System.setProperty("testorder.index.path", idx.toString());
        System.setProperty("testorder.changed.classes", "com.X");
        System.clearProperty("testorder.state.path");
        System.setProperty("testorder.score.changedTest", "50");
        System.setProperty("testorder.changed.test.classes", Integer.class.getName());

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(String.class), desc(Integer.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        assertEquals(Integer.class.getName(), descs.get(0).getTestClass().getName());
    }

    @Test
    void customSpeedBonus() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of());
        map.put(Integer.class.getName(), Set.of());
        map.put(Long.class.getName(), Set.of());
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        TestOrderState state = new TestOrderState();
        state.recordDuration(String.class.getName(), 100);
        state.recordDuration(Integer.class.getName(), 500);
        state.recordDuration(Long.class.getName(), 2000);
        setupState(state);

        System.setProperty("testorder.index.path", idx.toString());
        System.clearProperty("testorder.changed.classes");
        System.setProperty("testorder.score.speed", "10");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(Long.class), desc(Integer.class), desc(String.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        assertEquals(String.class.getName(), descs.get(0).getTestClass().getName());
    }

    @Test
    void customMaxFailureBonus() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of());
        map.put(Integer.class.getName(), Set.of());
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        long now = System.currentTimeMillis();
        long oneDay = 24 * 60 * 60 * 1000L;
        TestOrderState state = new TestOrderState();
        for (int i = 0; i < 10; i++) {
            state.recordFailure(String.class.getName(), now - oneDay / 10 * (i + 1));
        }
        setupState(state);

        System.setProperty("testorder.index.path", idx.toString());
        System.clearProperty("testorder.changed.classes");
        System.setProperty("testorder.score.maxFailure", "2");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(Integer.class), desc(String.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        assertEquals(String.class.getName(), descs.get(0).getTestClass().getName());
    }

    // --- Tests that would have caught the isNew bug ---

    @Test
    void changedTestNotInIndexGetsNewTestBonus() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of("com.A"));
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        System.setProperty("testorder.index.path", idx.toString());
        System.setProperty("testorder.changed.classes", "com.A");
        System.clearProperty("testorder.state.path");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(String.class), desc(Integer.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        assertEquals(Integer.class.getName(), descs.get(0).getTestClass().getName(),
                "New test classes (not in index) must get NEW_TEST_BONUS");
        assertEquals(String.class.getName(), descs.get(1).getTestClass().getName());
    }

    @Test
    void invalidScoringPropertyFallsBackToDefault() throws IOException {
        DependencyMap map = new DependencyMap();
        map.put(String.class.getName(), Set.of());
        Path idx = tempDir.resolve("test.idx");
        map.save(idx);

        System.setProperty("testorder.index.path", idx.toString());
        System.clearProperty("testorder.changed.classes");
        System.clearProperty("testorder.state.path");
        System.setProperty("testorder.score.newTest", "not-a-number");

        PriorityClassOrderer orderer = new PriorityClassOrderer();
        List<StubClassDescriptor> descs = new ArrayList<>(List.of(
                desc(String.class), desc(Integer.class)));
        orderer.orderClasses(new StubClassOrdererContext(descs));

        assertEquals(Integer.class.getName(), descs.get(0).getTestClass().getName(),
                "Invalid scoring property should fall back to default");
    }

    // --- Jaccard distance tests ---

    @Test
    void jaccardDistanceBothEmpty() {
        assertEquals(1.0, TestSelector.jaccardDistance(Set.of(), Set.of()));
    }

    @Test
    void jaccardDistanceOneEmpty() {
        assertEquals(1.0, TestSelector.jaccardDistance(Set.of("A"), Set.of()));
    }

    @Test
    void jaccardDistanceIdentical() {
        assertEquals(0.0, TestSelector.jaccardDistance(Set.of("A", "B"), Set.of("A", "B")));
    }

    @Test
    void jaccardDistanceDisjoint() {
        assertEquals(1.0, TestSelector.jaccardDistance(Set.of("A"), Set.of("B")));
    }

    @Test
    void jaccardDistancePartialOverlap() {
        double d = TestSelector.jaccardDistance(
                Set.of("A", "B", "C"), Set.of("B", "C", "D"));
        assertEquals(0.5, d, 0.001);
    }

    // --- Stubs ---

    static StubClassDescriptor desc(Class<?> clazz) {
        return new StubClassDescriptor(clazz);
    }

    static class StubClassDescriptor implements ClassDescriptor {
        private final Class<?> testClass;

        StubClassDescriptor(Class<?> testClass) {
            this.testClass = testClass;
        }

        @Override
        public Class<?> getTestClass() {
            return testClass;
        }

        @Override
        public String getDisplayName() {
            return testClass.getSimpleName();
        }

        @Override
        public <A extends java.lang.annotation.Annotation> Optional<A> findAnnotation(Class<A> annotationType) {
            return Optional.empty();
        }

        @Override
        public <A extends java.lang.annotation.Annotation> java.util.List<A> findRepeatableAnnotations(Class<A> annotationType) {
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean isAnnotated(Class<? extends java.lang.annotation.Annotation> annotationType) {
            return false;
        }
    }

    static class StubClassOrdererContext implements ClassOrdererContext {
        private final List<? extends ClassDescriptor> descriptors;

        StubClassOrdererContext(List<? extends ClassDescriptor> descriptors) {
            this.descriptors = descriptors;
        }

        @Override
        public List<? extends ClassDescriptor> getClassDescriptors() {
            return descriptors;
        }

        @Override
        public Optional<String> getConfigurationParameter(String key) {
            return Optional.empty();
        }
    }
}
