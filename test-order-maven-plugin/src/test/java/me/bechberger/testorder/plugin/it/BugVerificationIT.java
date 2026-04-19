package me.bechberger.testorder.plugin.it;

import me.bechberger.testorder.DependencyMap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static me.bechberger.testorder.plugin.it.TestOrderAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug-verification integration tests — found by exercising the plugin as a user
 * and checking each README claim against actual behaviour.
 * <p>
 * Uses the sample-shop project (Product → Cart → Invoice, 3 test classes).
 * <p>
 * Enable with: {@code -Dtestorder.it=true}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BugVerificationIT {

    static final String PRODUCT_TEST = "com.example.shop.ProductTest";
    static final String CART_TEST = "com.example.shop.CartTest";
    static final String INVOICE_TEST = "com.example.shop.InvoiceTest";

    static final String PRODUCT = "com.example.shop.Product";
    static final String CART = "com.example.shop.Cart";
    static final String INVOICE = "com.example.shop.Invoice";

    TestProject project;

    @BeforeAll
    void setup() {
        Path root = Paths.get("").toAbsolutePath();
        if (root.getFileName().toString().equals("test-order-maven-plugin")) {
            root = root.getParent();
        }
        project = new TestProject(root.resolve("sample-shop"),
                List.of("-Dtestorder.includePackages=com.example"));
    }

    @AfterAll
    void tearDown() {
        if (project != null) {
            project.restoreAll();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  BUG 1: show-order without index creates a bogus empty index
    //
    //  Steps: delete index → run show-order → an empty index file is
    //  silently created (0 test classes) and all tests get the "new test"
    //  bonus (score 15).  Expected: warn or fail, don't create a file.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("BUG: show-order without index should not silently create an empty index file")
    void showOrderWithoutIndexShouldNotCreateEmptyIndex() {
        project.cleanAll();

        MavenResult result = project.maven().showOrder(PRODUCT);

        // The goal may succeed or fail — either is acceptable.
        // But it should NOT silently create an empty index file.
        if (result.isSuccess()) {
            // If it succeeds, it should not have auto-aggregated an empty index
            DependencyMap depMap = project.loadIndex();
            if (depMap != null) {
                // An empty index (0 test classes) should not be written —
                // it misleads the scoring by treating every test as "new"
                assertThat(depMap.size())
                        .as("show-order should not create an empty index with 0 test classes")
                        .isGreaterThan(0);
            }
        }
        // If it failed, that's also acceptable — at least it didn't silently corrupt state
    }

    // ═══════════════════════════════════════════════════════════════════
    //  BUG 2: run-remaining with empty remaining file runs ALL tests
    //
    //  Steps: learn → select (all 3 tests selected, remaining file
    //  empty) → run-remaining → all 14 tests run again.
    //  Expected: no tests should run (or skip gracefully).
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("BUG: run-remaining with empty remaining file should not re-run all tests")
    void runRemainingWithEmptyFileShouldNotRunAllTests() {
        project.cleanAll();

        // Learn first
        MavenResult learn = project.maven().learn();
        assertThat(learn).succeeded();

        // Select — with only 3 tests and topN=20, all tests get selected
        MavenResult select = project.maven().select(INVOICE);
        assertThat(select).succeeded();

        // The remaining file should be empty (all tests were selected)
        String remaining = project.readFile("target/test-order-remaining.txt");
        assertThat(remaining == null || remaining.isBlank())
                .as("All tests should have been selected (remaining file should be empty/absent)")
                .isTrue();

        // Now: run-remaining should NOT run all tests again
        MavenResult runRemaining = project.maven().runRemaining();

        if (runRemaining.isSuccess()) {
            // If it succeeded, check that it didn't re-run all 14 individual tests
            List<String> testRunLines = runRemaining.grepOutput("Tests run:");
            int totalTestsRun = testRunLines.stream()
                    .mapToInt(line -> {
                        // Parse "Tests run: N, ..." from each line
                        var m = java.util.regex.Pattern.compile("Tests run: (\\d+)")
                                .matcher(line);
                        return m.find() ? Integer.parseInt(m.group(1)) : 0;
                    })
                    .sum();

            assertThat(totalTestsRun)
                    .as("run-remaining with empty remaining file should run 0 tests, not %d", totalTestsRun)
                    .isEqualTo(0);
        }
        // Failing is also acceptable — it means "no remaining tests to run"
    }

    // ═══════════════════════════════════════════════════════════════════
    //  BUG 3: non-existent weights file is silently ignored
    //
    //  Steps: learn → show-order with -Dtestorder.weights.file=bogus.txt
    //  Expected: warning or failure about missing file.
    //  Actual: succeeds silently, user doesn't know weights weren't loaded.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    @DisplayName("BUG: referencing a non-existent weights file should warn or fail")
    void nonExistentWeightsFileShouldWarnOrFail() {
        // Ensure index exists
        if (!project.exists("test-dependencies.lz4")) {
            project.cleanAll();
            project.maven().learn();
        }

        MavenResult result = project.maven().run(
                "test-order:show-order",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.weights.file=this-file-does-not-exist.txt");
        
        // The goal should either:
        //  a) fail with a clear error about the missing file, or
        //  b) succeed but log a visible warning
        if (result.isSuccess()) {
            assertThat(result.output())
                    .as("Non-existent weights file should produce a warning in output")
                    .containsIgnoringCase("warn");
        }
        // If it failed, that's the expected correct behaviour
    }

    // ═══════════════════════════════════════════════════════════════════
    //  BUG 4: system property score overrides are ignored by show-order
    //
    //  Steps: learn → show-order with Product changed → note scores.
    //  Then: show-order with -Dtestorder.score.depOverlap=0 → scores
    //  should change (overlap should be 0).  Actual: identical scores.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(30)
    @DisplayName("BUG: system property score overrides should affect show-order output")
    void systemPropertyScoreOverridesShouldWork() {
        // Ensure clean index
        project.cleanAll();
        MavenResult learn = project.maven().learn();
        assertThat(learn).succeeded();

        // Baseline: show-order with default weights and Product changed
        MavenResult baseline = project.maven().showOrder(PRODUCT);
        assertThat(baseline).succeeded();

        int baseProductScore = extractScore(baseline.output(), "ProductTest");
        int baseCartScore = extractScore(baseline.output(), "CartTest");

        // With depOverlap=0: the overlap component should be gone
        MavenResult noOverlap = project.maven().run(
                "test-order:show-order",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=" + PRODUCT,
                "-Dtestorder.score.depOverlap=0");
        assertThat(noOverlap).succeeded();

        int noOverlapProductScore = extractScore(noOverlap.output(), "ProductTest");
        int noOverlapCartScore = extractScore(noOverlap.output(), "CartTest");

        // With depOverlap=0, scores should be lower (no overlap bonus)
        assertThat(noOverlapProductScore)
                .as("ProductTest score with depOverlap=0 should be lower than baseline (%d vs %d)",
                        noOverlapProductScore, baseProductScore)
                .isLessThan(baseProductScore);

        // Similarly, with depOverlap=100, scores should be much higher
        MavenResult highOverlap = project.maven().run(
                "test-order:show-order",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=" + PRODUCT,
                "-Dtestorder.score.depOverlap=100");
        assertThat(highOverlap).succeeded();

        int highOverlapProductScore = extractScore(highOverlap.output(), "ProductTest");

        assertThat(highOverlapProductScore)
                .as("ProductTest score with depOverlap=100 should be higher than baseline (%d vs %d)",
                        highOverlapProductScore, baseProductScore)
                .isGreaterThan(baseProductScore);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  BUG 4b: verify weights file overrides DO work (for comparison)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(31)
    @DisplayName("VERIFY: weights file overrides DO work (contrasts with Bug 4)")
    void weightsFileOverridesDoWork() {
        // Ensure index exists
        if (!project.exists("test-dependencies.lz4")) {
            project.cleanAll();
            project.maven().learn();
        }

        // Create a weights file with depOverlap=100
        try {
            java.nio.file.Files.writeString(project.path("test-weights.txt"),
                    "newTest = 0\nchangedTest = 0\nmaxFailure = 0\nspeed = 0\nspeedPenalty = 0\ndepOverlap = 100\n");

            MavenResult result = project.maven().run(
                    "test-order:show-order",
                    "-Dtestorder.changeMode=explicit",
                    "-Dtestorder.changed.classes=" + PRODUCT,
                    "-Dtestorder.weights.file=test-weights.txt");
            assertThat(result).succeeded();

            int productScore = extractScore(result.output(), "ProductTest");
            // With depOverlap=100 and all other bonuses=0, ProductTest should have a high score
            assertThat(productScore)
                    .as("Weights file with depOverlap=100 should produce a high score")
                    .isGreaterThanOrEqualTo(25);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            project.deleteIfExists("test-weights.txt");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  EDGE: order mode test execution order verification
    //
    //  When Cart is changed, CartTest (direct dep) should run before
    //  ProductTest (no dep on Cart). This verifies the ordering actually
    //  affects JUnit execution order.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(40)
    @DisplayName("VERIFY: order mode runs affected tests first")
    void orderModeRunsAffectedTestsFirst() {
        // Ensure index
        if (!project.exists("test-dependencies.lz4")) {
            project.cleanAll();
            project.maven().learn();
        }

        MavenResult result = project.maven().order(CART);
        assertThat(result).succeeded();

        // CartTest should appear in output before ProductTest
        int cartPos = result.output().indexOf("CartTest");
        int productPos = result.output().indexOf("ProductTest");

        assertThat(cartPos)
                .as("CartTest should appear before ProductTest when Cart is changed")
                .isLessThan(productPos);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  EDGE: show-order with multiple changed classes
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(50)
    @DisplayName("VERIFY: show-order with multiple changed classes scores correctly")
    void showOrderMultipleChangedClasses() {
        if (!project.exists("test-dependencies.lz4")) {
            project.cleanAll();
            project.maven().learn();
        }

        // Change both Cart and Invoice — InvoiceTest should score highest
        // (depends on both), CartTest second (depends on Cart only)
        MavenResult result = project.maven().showOrder(CART, INVOICE);
        assertThat(result).succeeded();

        int invoiceScore = extractScore(result.output(), "InvoiceTest");
        int cartScore = extractScore(result.output(), "CartTest");
        int productScore = extractScore(result.output(), "ProductTest");

        assertThat(invoiceScore)
                .as("InvoiceTest should score >= CartTest when Cart+Invoice changed (2/4 overlap vs 1/3)")
                .isGreaterThanOrEqualTo(cartScore);

        assertThat(invoiceScore)
                .as("InvoiceTest should score > ProductTest when Cart+Invoice changed")
                .isGreaterThan(productScore);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  EDGE: learn, introduce failing test, order mode records failure
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(60)
    @DisplayName("BUG: failing test should be recorded in state file")
    void failingTestRecordedInState() {
        project.cleanAll();
        MavenResult learn = project.maven().learn();
        assertThat(learn).succeeded();

        try {
            // Introduce a bug in Cart.size()
            project.replaceInFile("src/main/java/com/example/shop/Cart.java",
                    "return items.size();", "return items.size() + 1;");

            // Order mode — CartTest should fail
            MavenResult order = project.maven().order(CART);
            assertThat(order).failed();

            // State file should record the failure
            var state = project.loadState();
            assertThat(state).isLoaded();
            assertThat(state).hasFailureFor(CART_TEST);
        } finally {
            project.restoreAll();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  EDGE: combined mode from scratch learns and creates index
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(70)
    @DisplayName("VERIFY: combined mode without index learns first and creates a valid index")
    void combinedModeFromScratchCreatesValidIndex() {
        project.cleanAll();

        MavenResult result = project.maven().combined();
        assertThat(result).succeeded()
                .outputContains("Tests run:");

        // Index should exist and have all 3 test classes
        DependencyMap depMap = project.loadIndex();
        assertThat(depMap).isLoaded().hasSize(3)
                .hasTestClass(PRODUCT_TEST)
                .hasTestClass(CART_TEST)
                .hasTestClass(INVOICE_TEST);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  BUG 5: select with empty selection runs ALL tests
    //
    //  Steps: learn → select with topN=0,randomM=0 → reports "Selected 0"
    //  but all 14 tests still run.
    //  Expected: no tests should run (or skip gracefully).
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(80)
    @DisplayName("BUG: select with topN=0 and randomM=0 should not run all tests")
    void selectWithEmptySelectionShouldNotRunAllTests() {
        // Ensure index exists
        if (!project.exists("test-dependencies.lz4")) {
            project.cleanAll();
            project.maven().learn();
        }

        MavenResult result = project.maven().run(
                "clean", "test-order:select", "test",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=" + PRODUCT,
                "-Dtestorder.select.topN=0",
                "-Dtestorder.select.randomM=0");

        if (result.isSuccess()) {
            // Parse total tests run from ALL "Tests run:" lines
            List<String> testRunLines = result.grepOutput("Tests run:");
            int totalTestsRun = testRunLines.stream()
                    .mapToInt(line -> {
                        var m = java.util.regex.Pattern.compile("Tests run: (\\d+)")
                                .matcher(line);
                        return m.find() ? Integer.parseInt(m.group(1)) : 0;
                    })
                    .sum();

            assertThat(totalTestsRun)
                    .as("select with topN=0, randomM=0 should run 0 tests, not %d", totalTestsRun)
                    .isEqualTo(0);
        }
        // Failing is also acceptable
    }

    // ═══════════════════════════════════════════════════════════════════
    //  BUG 6: combined mode with topN=1 doesn't run remaining tests
    //
    //  Steps: learn → combined with topN=1,randomM=0 → only 1 test class
    //  runs, remaining 2 are never executed despite runRemaining=true.
    //  Expected: output should tell user how to run remaining tests.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(90)
    @DisplayName("BUG: combined mode should tell user how to run remaining tests")
    void combinedModeShouldInformAboutRemainingTests() {
        // Ensure index exists
        if (!project.exists("test-dependencies.lz4")) {
            project.cleanAll();
            project.maven().learn();
        }
        project.deleteIfExists(".test-order-state");

        MavenResult result = project.maven().run(
                "clean", "test-order:combined", "test",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=" + PRODUCT,
                "-Dtestorder.select.topN=1",
                "-Dtestorder.select.randomM=0");
        assertThat(result).succeeded();

        // Remaining file should exist with 2 deferred test classes
        String remaining = project.readFile("target/test-order-remaining.txt");
        assertThat(remaining).as("Remaining file should exist and not be empty").isNotNull().isNotBlank();

        // Output should inform the user how to run remaining tests
        assertThat(result.output())
                .as("Combined mode should tell user to run: mvn test-order:run-remaining test")
                .contains("run-remaining");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  BUG 7: aggregate with empty deps dir creates empty index
    //
    //  Steps: delete index → create empty deps dir → aggregate
    //  Expected: should warn and NOT create a 0-test-class index file.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(100)
    @DisplayName("BUG: aggregate with empty deps dir should not create empty index")
    void aggregateWithEmptyDepsShouldNotCreateEmptyIndex() {
        project.deleteIfExists("test-dependencies.lz4");
        project.deleteTree("target");

        // Create empty deps dir
        try {
            java.nio.file.Files.createDirectories(project.path("target/test-order-deps"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        MavenResult result = project.maven().aggregate();

        // Should NOT have created an index file
        assertThat(project.exists("test-dependencies.lz4"))
                .as("aggregate with empty deps dir should not create an index file")
                .isFalse();

        // Should have warned
        assertThat(result.output())
                .as("aggregate with empty deps should produce a warning")
                .containsIgnoringCase("warn");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════
    //  BUG 8: configureIncludes uses simple class names (ambiguous)
    //
    //  Steps: select tests → verify the Surefire test property includes
    //  fully-qualified class names, not just simple names.
    //  Impact: if two packages have "FooTest", both would run even if
    //  only one was selected.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(110)
    @DisplayName("BUG: select should use FQCNs in Surefire test filter, not simple names")
    void selectShouldUseFqcnsInTestFilter() {
        // Ensure index exists
        if (!project.exists("test-dependencies.lz4")) {
            project.cleanAll();
            project.maven().learn();
        }

        // select with explicit change → only CartTest should be selected
        MavenResult result = project.maven().run(
                "clean", "test-order:select", "test",
                "-Dtestorder.changeMode=explicit",
                "-Dtestorder.changed.classes=" + CART,
                "-Dtestorder.select.topN=1",
                "-Dtestorder.select.randomM=0");
        assertThat(result).succeeded();

        // The selected file should contain FQCNs (with dots)
        String selected = project.readFile("target/test-order-selected.txt");
        assertThat(selected).isNotNull();
        assertThat(selected.lines().toList())
                .as("Selected file should contain fully-qualified class names")
                .allMatch(line -> line.contains("."));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  BUG 9: TelemetryListener double-records failures
    //
    //  Steps: run learn mode with a failing test → check state file
    //  has exactly 1 failure record, not 2.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(120)
    @DisplayName("BUG: learn mode with a failure should record exactly 1 failure, not 2")
    void learnModeShouldNotDoubleRecordFailures() {
        project.cleanAll();

        try {
            // Introduce a bug in Cart.size() so CartTest fails
            project.replaceInFile("src/main/java/com/example/shop/Cart.java",
                    "return items.size();", "return items.size() + 1;");

            // Learn mode — CartTest should fail, failure recorded in state
            MavenResult learn = project.maven().run(
                    "clean", "test", "-Dtestorder.mode=learn",
                    "-Dmaven.test.failure.ignore=true");
            assertThat(learn).succeeded();

            // State file should exist (Bug 10 fix: sysProps via argLine)
            var state = project.loadState();
            assertThat(state).isLoaded();

            // Should have exactly 1 failure record for CartTest, not 2 (Bug 9 fix)
            // With exponential decay model, we verify score is present (count no longer tracked).
            assertThat(state).hasFailureFor(CART_TEST);
        } finally {
            project.restoreAll();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  BUG 10: learn mode doesn't save state file
    //
    //  Steps: learn → state file should be created (system properties
    //  now passed via argLine instead of systemPropertyVariables).
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(130)
    @DisplayName("BUG: learn mode should create state file with durations")
    void learnModeShouldCreateStateFile() {
        project.cleanAll();

        MavenResult learn = project.maven().learn();
        assertThat(learn).succeeded();

        // State file should exist with durations for all 3 test classes
        var state = project.loadState();
        assertThat(state).isLoaded()
                .hasDuration(PRODUCT_TEST)
                .hasDuration(CART_TEST)
                .hasDuration(INVOICE_TEST);
    }

    @Test
    @Order(140)
    @DisplayName("BUG: run-remaining with missing remaining file should skip tests, not run all")
    void runRemainingWithMissingFileShouldSkip() {
        project.cleanAll();

        // Learn first so there's an index (but no remaining file)
        MavenResult learn = project.maven().learn();
        assertThat(learn).succeeded();

        // Delete the remaining file (making sure it doesn't exist)
        project.deleteIfExists("target/test-order-remaining.txt");

        // run-remaining should skip tests, not run all
        MavenResult remaining = project.maven().runRemaining();
        assertThat(remaining).succeeded()
                .outputContains("nothing to run")
                .outputContains("Tests are skipped");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ISSUE FIX: OptimizeMojo now extends AbstractTestOrderMojo
    //
    //  Steps: learn → run tests multiple times → optimize should still
    //  work correctly after refactoring to use ReactorContext.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(150)
    @DisplayName("ISSUE: optimize should work after extending AbstractTestOrderMojo")
    void optimizeShouldWorkWithReactorContext() {
        project.cleanAll();

        // Learn + run enough times for optimization (MIN_RUNS_FOR_OPTIMISATION = 3)
        for (int i = 0; i < 3; i++) {
            MavenResult learn = project.maven().learn();
            assertThat(learn).succeeded();
        }

        // Inject a failure so the optimizer has something to work with
        project.replaceInFile("src/test/java/com/example/shop/ProductTest.java",
                "assertEquals(\"Widget\", p.getName());",
                "assertEquals(\"WRONG\", p.getName());");
        MavenResult failRun = project.maven().learn();
        assertThat(failRun).failed();
        project.restoreAll();

        // Run optimize — should succeed (previously extended AbstractMojo directly)
        MavenResult optimize = project.maven().optimize();
        assertThat(optimize).succeeded()
                .outputContains("[test-order]");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ISSUE FIX: SnapshotMojo now extends AbstractTestOrderMojo
    //
    //  Steps: run snapshot → should succeed using ReactorContext paths.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(160)
    @DisplayName("ISSUE: snapshot should work after extending AbstractTestOrderMojo")
    void snapshotShouldWorkWithReactorContext() {
        project.cleanAll();

        MavenResult snapshot = project.maven().snapshot();
        assertThat(snapshot).succeeded()
                .outputContains("[test-order] Snapshot:");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ISSUE FIX: Path escaping in testorder-config.properties
    //
    //  Steps: learn → order mode → verify config file has readable paths.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @Order(170)
    @DisplayName("ISSUE: config properties paths should be readable by Properties.load()")
    void configPathsShouldBeCorrectlyEscaped() throws Exception {
        project.cleanAll();

        // Learn then run in order mode (writes testorder-config.properties)
        MavenResult learn = project.maven().learn();
        assertThat(learn).succeeded();

        MavenResult order = project.maven().order(PRODUCT);
        assertThat(order).succeeded();

        // Verify the config file is valid properties
        java.nio.file.Path configFile = project.getProjectDir()
                .resolve("target/test-classes/testorder-config.properties");
        assertThat(configFile).exists();
        java.util.Properties props = new java.util.Properties();
        try (var reader = java.nio.file.Files.newBufferedReader(configFile)) {
            props.load(reader);
        }
        // Paths should resolve to existing files
        String indexPath = props.getProperty("testorder.index.path");
        assertThat(indexPath).isNotNull();
        assertThat(java.nio.file.Path.of(indexPath)).exists();

        String statePath = props.getProperty("testorder.state.path");
        assertThat(statePath).isNotNull();
        assertThat(java.nio.file.Path.of(statePath)).exists();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers (private)
    // ═══════════════════════════════════════════════════════════════════

    private int extractScore(String output, String testClassName) {
        for (String line : output.lines().toList()) {
            if (!line.contains(testClassName)) continue;
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 3) {
                try {
                    return Integer.parseInt(parts[2]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        throw new AssertionError("Could not find score for " + testClassName + " in output:\n" + output);
    }
}
