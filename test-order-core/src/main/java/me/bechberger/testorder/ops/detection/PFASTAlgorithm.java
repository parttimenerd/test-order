package me.bechberger.testorder.ops.detection;

import me.bechberger.testorder.ops.detection.TestRunner.TestRunResult;

import java.util.*;

/**
 * Algorithm 7: PFAST Single-Exclusion.
 * Detects BRITTLE tests by systematically removing one test at a time.
 * If removing test X causes test Y to fail, Y is BRITTLE and needs X as a setter.
 */
public class PFASTAlgorithm implements DetectionAlgorithm {

    @Override
    public String name() {
        return "pfast-exclusion";
    }

    @Override
    public Set<Prerequisite> prerequisites() {
        return Set.of(Prerequisite.PASSING_REFERENCE);
    }

    @Override
    public int estimatedRuns(int testCount, int conflictEdges) {
        return testCount;
    }

    @Override
    public List<ODResult> detect(DetectionContext ctx) {
        List<ODResult> findings = new ArrayList<>();
        Set<String> confirmed = new HashSet<>();

        for (String excluded : ctx.referenceOrder()) {
            if (ctx.timeBudgetExhausted()) break;

            // Run without this test
            List<String> order = new ArrayList<>(ctx.referenceOrder());
            order.remove(excluded);

            TestRunResult result = ctx.runner().run(order);

            for (String failed : result.failedTests()) {
                if (!ctx.passingTests().contains(failed)) continue;
                if (confirmed.contains(failed)) continue;

                // Test Y failed without X → verify: does [X, Y] make Y pass?
                TestRunResult verify = ctx.runner().run(List.of(excluded, failed));
                if (verify.passed(failed)) {
                    confirmed.add(failed);
                    findings.add(new ODResult(failed, ODType.BRITTLE,
                            List.of(excluded, failed),
                            "Brittle: " + failed + " needs " + excluded + " as state-setter",
                            0.95));
                }
            }
        }

        return findings;
    }
}
