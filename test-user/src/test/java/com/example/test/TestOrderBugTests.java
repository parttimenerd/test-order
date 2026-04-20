package com.example.test;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class TestOrderBugTests {
    /**
     * BUG #1: Speed penalty is applied even when test is not particularly slow
     * 
     * REPRODUCE:
     * 1. Create a project with FastTest (runs in ~2ms) and SlowTest (runs in ~2700ms)
     * 2. Run: mvn test-order:show-order
     * 3. EXPECTED: CalculatorTest (42ms median) should not get speed penalty
     * ACTUAL: CalculatorTest shows score -1 when run without explicit changes
     * 
     * ISSUE: Tests that are not in the "slow" category (>75% percentile) 
     * are incorrectly receiving negative scores or speed penalties
     */
    @Test
    public void bug_speed_penalty_incorrectly_applied() {
        // Reproduce with:
        // mvn test-order:show-order
        // Shows CalculatorTest with -1 score, but 42ms is not slow
    }

    /**
     * BUG #2: Changed detection stops working after show-order commands
     * 
     * REPRODUCE:
     * 1. Run: mvn test -Dtestorder.mode=learn
     * 2. Modify src/main/java/com/example/Calculator.java (add comment)
     * 3. Run: mvn test-order:show-order
     *    -> Output shows: Changed classes: [com.example.Calculator]
     * 4. Run again: mvn test-order:show-order
     *    -> EXPECTED: Shows changed classes again
     *    -> ACTUAL: No "Changed classes:" line shown, scores go negative
     * 
     * ISSUE: The hash snapshots appear to be updated during show-order,
     * causing subsequent change detection to fail
     */
    @Test
    public void bug_changed_detection_stops_after_show_order() {
        // Already observed in multi-user session
    }

    /**
     * BUG #3: Negative scores when no files are actually changed
     * 
     * REPRODUCE:
     * 1. Run learn mode
     * 2. Run: mvn test-order:show-order
     *    -> Output shows score 0 for all tests (correct)
     * 3. Modify single application file
     * 4. Run: mvn test-order:show-order
     *    -> Output shows ALL other tests with NEGATIVE scores (-1)
     *    -> EXPECTED: Tests unrelated to change should remain 0 or sorted by Jaccard distance
     *    -> ACTUAL: Unrelated tests get penalized with -1
     * 
     * ISSUE: The scoring formula appears to apply penalties to tests that 
     * don't use changed classes, instead of just de-prioritizing them
     */
    @Test
    public void bug_negative_scores_for_unrelated_tests() {
        // Observed with CalculatorTest score -1 when SlowTest has -1 penalty
    }
}
