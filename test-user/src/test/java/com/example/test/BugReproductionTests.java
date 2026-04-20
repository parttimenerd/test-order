package com.example.test;

import com.example.Calculator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG REPRODUCTION TEST SUITE FOR test-order PLUGIN
 * 
 * This test file contains Java reproduction cases for bugs found during
 * user acceptance testing of the test-order plugin.
 * 
 * Each test documents the exact steps to reproduce a bug.
 */
public class BugReproductionTests {

    /**
     * BUG #1: Negative scores for tests unrelated to code changes
     * 
     * SEVERITY: HIGH - Affects core functionality of prioritization
     * IMPACT: Tests get negative scores when other tests are slow,
     *         breaking the scoring guarantee that relevant tests run first
     * 
     * STEPS TO REPRODUCE:
     * 
     * 1. Create a Maven project with test-order plugin
     * 2. Add these test classes:
     *    - FastTest (all tests run in <10ms each)
     *    - SlowTest (all tests run in >2500ms each)
     * 3. Run: mvn clean test -Dtestorder.mode=learn
     * 4. Run: mvn test-order:show-order
     *    Output shows all scores = 0 (correct)
     * 5. Modify Calculator.java (add a comment or whitespace)
     * 6. Run: mvn test-order:show-order
     * 
     * EXPECTED:
     * DatabaseHelperTest: 0 (not affected by Calculator change)
     * CalculatorTest: ~9 (changed test bonus)
     * SlowTest: 0 or slightly negative from speed penalty only
     * 
     * ACTUAL BUG:
     * DatabaseHelperTest:    -1  (Wrong! Not related to changes)
     * FastTest:               0   (Correct, no relation to changes)
     * StringProcessorTest:   -1  (Wrong! Not related to changes)
     * IntegrationTest:       -1  (Wrong! No bonus from Calculator change)
     * CalculatorTest:        -1  (Wrong! Should have +9 changed bonus)
     * SlowTest:              -1  (Wrong! Should be -0 or -1 for speed penalty)
     * 
     * ROOT CAUSE ANALYSIS:
     * The scoring appears to:
     * - Apply speed penalty to ALL tests when some tests are fast/slow
     * - Not apply changed test bonus correctly
     * - Show dependency overlap incorrectly (Deps column)
     * 
     * WORKAROUND: None found
     */
    @Test
    public void bug_001_negative_scores_when_unrelated() {
        // Navigate to: /Users/i560383_1/code/experiments/test-order/test-user
        // Follow steps 1-6 above to reproduce
    }

    /**
     * BUG #2: Change detection stops working after show-order commands
     * 
     * SEVERITY: MEDIUM - Affects change detection accuracy
     * IMPACT: Users think their files changed are no longer detected after
     *         running diagnostic commands like show-order
     * 
     * STEPS TO REPRODUCE:
     * 
     * 1. From test-user directory:
     *    mvn clean test -Dtestorder.mode=learn
     * 
     * 2. Modify src/main/java/com/example/Calculator.java
     *    (Add a comment like: // Modified by tester)
     * 
     * 3. Run: mvn test-order:show-order
     *    Output shows: "Changed classes: [com.example.Calculator]" ✓ CORRECT
     * 
     * 4. Run: mvn test-order:show-order (same command again)
     * 
     * EXPECTED:
     * Output shows: "Changed classes: [com.example.Calculator]"
     * CalculatorTest score includes changed bonus
     * 
     * ACTUAL BUG:
     * No "Changed classes:" line appears in output
     * CalculatorTest and related tests show negative or zero scores
     * The hash snapshots (.test-order-hashes.lz4) appear to be updated
     * 
     * ROOT CAUSE ANALYSIS:
     * The show-order command (or compute operations) appears to update
     * the hash snapshots, causing subsequent runs to not detect the files
     * as changed. This should either:
     * - Not modify hash snapshots, or
     * - Only update them in learn mode
     * 
     * WORKAROUND:
     * Recompile the files after running show-order:
     *   mvn clean compile test-compile
     * Then run mvn test-order:show-order again
     */
    @Test
    public void bug_002_change_detection_stops_after_show_order() {
        // Follow steps 1-4 to reproduce in test-user directory
    }

    /**
     * BUG #3: Failure bonus not reflected in scores or test ordering
     * 
     * SEVERITY: HIGH - Core feature not working
     * IMPACT: Tests known to fail are not prioritized for re-running first
     * 
     * STEPS TO REPRODUCE:
     * 
     * 1. From test-user directory:
     *    mvn clean test -Dtestorder.mode=learn
     * 
     * 2. Create FailingTest.java with a test that fails:
     *    @Test public void test1() { assertThat(1).isEqualTo(2); }
     * 
     * 3. Run: mvn test
     *    Results: FailingTest fails, others pass
     * 
     * 4. Run: mvn test-order:show-order
     * 
     * EXPECTED:
     * FailingTest should show:
     *   - Non-zero "Fail" column value
     *   - Score increased by failure bonus (1-5 points)
     *   - First position in output
     * Example: "FailingTest    12  1    yes   1    4ms"
     *          (score 12 = failure bonus 5 + recency 1 + something else)
     * 
     * ACTUAL BUG:
     * FailingTest shows:
     *   - Empty "Fail" column (shows as spaces)
     *   - Score same as other new tests (no failure bonus applied)
     *   - Not prioritized in ordering
     * Example: "FailingTest    14                          33ms"
     *          (score 14 should be higher if failure was detected)
     * 
     * ROOT CAUSE ANALYSIS:
     * The failure information is not being persisted to the state file,
     * or not being loaded/calculated correctly during show-order execution
     * 
     * WORKAROUND: None found
     */
    @Test
    public void bug_003_failure_bonus_not_applied() {
        // Follow steps above to reproduce in test-user directory
    }

    /**
     * BUG #4: New test bonus applied too aggressively
     * 
     * SEVERITY: MEDIUM - Affects test prioritization
     * IMPACT: Every time a user adds a new test file, ALL tests run first
     *         before showing their actual relevance
     * 
     * STEPS TO REPRODUCE:
     * 
     * 1. From test-user directory:
     *    mvn test-order:show-order
     * 
     * 2. Add NewTest.java file
     * 
     * 3. Run: mvn test-order:show-order
     * 
     * EXPECTED:
     * NewTest: Score ~15 (new test bonus only)
     * Others: Scores unchanged (not new tests)
     * 
     * ACTUAL BUG:
     * NewTest: Score 14 (correct)
     * But EACH previously existing test that hasn't been in dependency index
     * also gets treated as "new" and gets score 14, even if it's been run
     * in previous learn sessions
     * 
     * This might be correct behavior if they're truly new, but seems
     * overly aggressive in assigning same score to all new tests
     * 
     * OBSERVATION:
     * In our test project after adding TestOrderBugTests.java,
     * both TestOrderBugTests and FailingTest showed score 14
     */
    @Test
    public void bug_004_new_test_bonus_boundaries() {
        // Needs more investigation to confirm
    }

    /**
     * BUG #5: Speed bonus/penalty calculation incorrect or display wrong
     * 
     * SEVERITY: MEDIUM - Affects test order optimization
     * IMPACT: Tests are not ordered by speed correctly, potentially causing
     *         slow tests to run early and delay failure detection
     * 
     * STEPS TO REPRODUCE:
     * 
     * 1. Create project with:
     *    - FastTest: 3 tests, each ~2ms
     *    - SlowTest: 3 tests, each ~2700ms
     * 
     * 2. Run: mvn clean test -Dtestorder.mode=learn
     * 
     * 3. Make a simple code change to Calculator.java
     * 
     * 4. Run: mvn test-order:show-order -Dtestorder.weights.file=custom-weights.txt
     *    (with custom-weights.txt containing: speed=3, speedPenalty=2)
     * 
     * EXPECTED:
     * FastTest:      0 (no change bonus, no speed penalty)
     * SlowTest:     -2 (speed penalty 2 for slow tests)
     * 
     * ACTUAL BUG:
     * FastTest:      0
     * SlowTest:     -1
     * All others:   -2
     * 
     * The penalty seems to be applied inconsistently, and the thresholds
     * for "fast" (25% of median) and "slow" (75% of median) might be wrong
     * 
     * Median duration = ~1355ms (average of 2ms and 2700ms)
     * Fast threshold = 25% * 1355 = 338ms  (2ms is below, correct)
     * Slow threshold = 75% * 1355 = 1016ms (2700ms is above, correct)
     * 
     * But speeds are not being applied to all tests correctly
     */
    @Test
    public void bug_005_speed_bonus_penalty_calculation() {
        // Follow the reproduce steps above
    }

    /**
     * BUG #6: Dependency overlap scoring seems incorrect
     * 
     * SEVERITY: MEDIUM - Affects test prioritization accuracy
     * IMPACT: Tests that don't overlap with changed classes still get
     *         lower scores than expected
     * 
     * OUTPUT OBSERVATION:
     * When Calculator changed and IntegrationTest was shown:
     * - IntegrationTest shows Deps=1 and Score=2
     * - But IntegrationTest depends on 3 classes: Calculator, StringProcessor, DatabaseHelper
     * - Only 1 changed (Calculator), so overlap = 1/3 = 0.33
     * - With depOverlap weight=5: score = ceil(5 * 0.33) = ceil(1.67) = 2 ✓
     * 
     * HOWEVER: The "Deps" column shows 1, but that should be showing
     * the 3 classes that IntegrationTest depends on, not the overlap count
     * 
     * EXPECTED: Show total dependencies exercised by the test
     * ACTUAL: Shows overlap count (confusing)
     * 
     * This is more of a display issue, but it makes the output confusing
     */
    @Test
    public void bug_006_dependency_display_confusing() {
        // This is a display/documentation issue
    }
}
