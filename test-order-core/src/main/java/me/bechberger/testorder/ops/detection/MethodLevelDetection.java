package me.bechberger.testorder.ops.detection;

import me.bechberger.testorder.DependencyMap;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects order-dependent test methods within a single test class.
 * <p>
 * Uses method-level dependency data from the DependencyMap to identify methods
 * that share mutable state (via member-level deps), then probes with reversed
 * and shuffled method orders to find intra-class OD bugs.
 * <p>
 * Strategy:
 * <ol>
 *   <li>Run methods in reference (alphabetical) order → establish baseline</li>
 *   <li>Run methods in reverse order → detect victims</li>
 *   <li>For each failure, isolate via binary search (ddmin-style)</li>
 *   <li>If dependency data available, prioritize methods sharing members</li>
 * </ol>
 */
public class MethodLevelDetection {

    private MethodLevelDetection() {}

    /**
     * Result of method-level OD detection for a single class.
     */
    public record MethodODResult(
            String testClass,
            String victimMethod,
            ODType type,
            List<String> dependencyChain,
            String description,
            double confidence) {}

    /**
     * Detect intra-class method-level OD bugs for a single test class.
     *
     * @param testClass FQCN of the test class
     * @param methods list of test method names in this class
     * @param runner test runner that supports method ordering
     * @param depMap dependency map (nullable, used for prioritization)
     * @param deadline deadline in epoch millis
     * @param rng random number generator
     * @return list of method-level OD findings
     */
    public static List<MethodODResult> detect(String testClass, List<String> methods,
                                              TestRunner runner, DependencyMap depMap,
                                              long deadline, Random rng) {
        if (methods.size() < 2) {
            return List.of();
        }

        List<MethodODResult> findings = new ArrayList<>();

        // Phase 1: Establish baseline — run in reference order
        List<String> referenceOrder = new ArrayList<>(methods);
        Collections.sort(referenceOrder); // alphabetical = reference
        TestRunner.MethodRunResult baseline = runner.runMethods(testClass, referenceOrder);
        Set<String> passingMethods = baseline.passedMethods();

        if (passingMethods.isEmpty()) {
            return List.of(); // Nothing passes — can't detect OD
        }

        // Phase 2: Run in reverse order
        List<String> reversed = new ArrayList<>(referenceOrder);
        Collections.reverse(reversed);
        TestRunner.MethodRunResult reverseResult = runner.runMethods(testClass, reversed);

        // Find methods that pass in reference but fail in reverse → VICTIM candidates
        Set<String> victimCandidates = new HashSet<>();
        for (String method : reverseResult.failedMethods()) {
            if (passingMethods.contains(method)) {
                victimCandidates.add(method);
            }
        }

        // Phase 3: For each victim candidate, isolate the polluter
        for (String victim : victimCandidates) {
            if (System.currentTimeMillis() >= deadline) break;

            List<String> predecessors = reverseResult.predecessorsOf(victim);
            String polluter = isolatePolluter(testClass, victim, predecessors, passingMethods, runner, deadline);

            if (polluter != null) {
                findings.add(new MethodODResult(
                        testClass, victim, ODType.VICTIM,
                        List.of(polluter, victim),
                        "Method '" + victim + "' fails when '" + polluter + "' runs before it",
                        0.95));
            } else if (!predecessors.isEmpty()) {
                // Couldn't isolate but confirmed failure — report with lower confidence
                findings.add(new MethodODResult(
                        testClass, victim, ODType.VICTIM,
                        List.of(predecessors.get(predecessors.size() - 1), victim),
                        "Method '" + victim + "' is order-dependent (polluter in: "
                                + predecessors.stream().limit(3).collect(Collectors.joining(", ")) + "...)",
                        0.7));
            }
        }

        // Phase 4: Check for brittles — methods that fail when run alone
        if (System.currentTimeMillis() < deadline) {
            for (String method : passingMethods) {
                if (System.currentTimeMillis() >= deadline) break;
                if (victimCandidates.contains(method)) continue; // Already classified

                // Run this method alone
                TestRunner.MethodRunResult isolation = runner.runMethods(testClass, List.of(method));
                if (isolation.failed(method)) {
                    // It's brittle — needs a setter. Find which predecessor it needs.
                    String setter = isolateSetter(testClass, method, referenceOrder, runner, deadline);
                    if (setter != null) {
                        findings.add(new MethodODResult(
                                testClass, method, ODType.BRITTLE,
                                List.of(setter, method),
                                "Method '" + method + "' requires '" + setter + "' to run first",
                                0.95));
                    } else {
                        findings.add(new MethodODResult(
                                testClass, method, ODType.BRITTLE,
                                List.of(method),
                                "Method '" + method + "' fails in isolation (needs a state-setter)",
                                0.8));
                    }
                }
            }
        }

        // Phase 5: Dependency-guided probes (if method-level deps are available)
        if (depMap != null && depMap.hasMethodDeps() && System.currentTimeMillis() < deadline) {
            Set<String> alreadyFound = findings.stream()
                    .map(MethodODResult::victimMethod)
                    .collect(Collectors.toSet());
            List<MethodODResult> depGuided = dependencyGuidedProbes(
                    testClass, methods, passingMethods, alreadyFound, depMap, runner, deadline, rng);
            findings.addAll(depGuided);
        }

        return findings;
    }

    /**
     * Binary-search isolation of the polluter method.
     */
    private static String isolatePolluter(String testClass, String victim,
                                          List<String> predecessors, Set<String> passingMethods,
                                          TestRunner runner, long deadline) {
        if (predecessors.isEmpty()) return null;
        if (predecessors.size() == 1) {
            // Verify: run [predecessor, victim] — if victim fails, predecessor is the polluter
            TestRunner.MethodRunResult verify = runner.runMethods(testClass,
                    List.of(predecessors.get(0), victim));
            return verify.failed(victim) ? predecessors.get(0) : null;
        }

        // Binary search: split predecessors, run [left half + victim] vs [right half + victim]
        int mid = predecessors.size() / 2;
        List<String> leftHalf = predecessors.subList(0, mid);
        List<String> rightHalf = predecessors.subList(mid, predecessors.size());

        if (System.currentTimeMillis() >= deadline) return predecessors.get(predecessors.size() - 1);

        // Try right half first (more likely to contain the immediate predecessor)
        List<String> rightRun = new ArrayList<>(rightHalf);
        rightRun.add(victim);
        TestRunner.MethodRunResult rightResult = runner.runMethods(testClass, rightRun);
        if (rightResult.failed(victim)) {
            return isolatePolluter(testClass, victim, rightHalf, passingMethods, runner, deadline);
        }

        // Try left half
        List<String> leftRun = new ArrayList<>(leftHalf);
        leftRun.add(victim);
        TestRunner.MethodRunResult leftResult = runner.runMethods(testClass, leftRun);
        if (leftResult.failed(victim)) {
            return isolatePolluter(testClass, victim, leftHalf, passingMethods, runner, deadline);
        }

        // Neither half alone causes failure — might need combination
        return predecessors.get(predecessors.size() - 1); // Best guess
    }

    /**
     * Find the setter method that a brittle method depends on.
     */
    private static String isolateSetter(String testClass, String brittle,
                                        List<String> referenceOrder, TestRunner runner,
                                        long deadline) {
        // Find where brittle is in the reference order
        int brittleIdx = referenceOrder.indexOf(brittle);
        if (brittleIdx <= 0) return null;

        List<String> predecessors = referenceOrder.subList(0, brittleIdx);

        // Binary search for the setter
        if (predecessors.size() == 1) {
            TestRunner.MethodRunResult verify = runner.runMethods(testClass,
                    List.of(predecessors.get(0), brittle));
            return verify.passed(brittle) ? predecessors.get(0) : null;
        }

        int mid = predecessors.size() / 2;
        List<String> rightHalf = new ArrayList<>(predecessors.subList(mid, predecessors.size()));

        if (System.currentTimeMillis() >= deadline) return null;

        // Try right half + brittle
        rightHalf.add(brittle);
        TestRunner.MethodRunResult rightResult = runner.runMethods(testClass, rightHalf);
        if (rightResult.passed(brittle)) {
            rightHalf.remove(rightHalf.size() - 1); // Remove brittle
            return isolateSetterInRange(testClass, brittle, rightHalf, runner, deadline);
        }

        // Try left half + brittle
        List<String> leftHalf = new ArrayList<>(predecessors.subList(0, mid));
        leftHalf.add(brittle);
        TestRunner.MethodRunResult leftResult = runner.runMethods(testClass, leftHalf);
        if (leftResult.passed(brittle)) {
            leftHalf.remove(leftHalf.size() - 1);
            return isolateSetterInRange(testClass, brittle, leftHalf, runner, deadline);
        }

        return null;
    }

    private static String isolateSetterInRange(String testClass, String brittle,
                                               List<String> candidates, TestRunner runner,
                                               long deadline) {
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) {
            TestRunner.MethodRunResult verify = runner.runMethods(testClass,
                    List.of(candidates.get(0), brittle));
            return verify.passed(brittle) ? candidates.get(0) : null;
        }
        if (System.currentTimeMillis() >= deadline) return candidates.get(candidates.size() - 1);

        int mid = candidates.size() / 2;
        List<String> rightHalf = new ArrayList<>(candidates.subList(mid, candidates.size()));
        rightHalf.add(brittle);
        TestRunner.MethodRunResult result = runner.runMethods(testClass, rightHalf);
        if (result.passed(brittle)) {
            rightHalf.remove(rightHalf.size() - 1);
            return isolateSetterInRange(testClass, brittle, rightHalf, runner, deadline);
        }

        List<String> leftHalf = new ArrayList<>(candidates.subList(0, mid));
        leftHalf.add(brittle);
        TestRunner.MethodRunResult leftResult = runner.runMethods(testClass, leftHalf);
        if (leftResult.passed(brittle)) {
            leftHalf.remove(leftHalf.size() - 1);
            return isolateSetterInRange(testClass, brittle, leftHalf, runner, deadline);
        }

        return null;
    }

    /**
     * Use method-level dependency data to find methods that share mutable members
     * and probe them in targeted orders.
     */
    private static List<MethodODResult> dependencyGuidedProbes(
            String testClass, List<String> methods, Set<String> passingMethods,
            Set<String> alreadyFound, DependencyMap depMap, TestRunner runner,
            long deadline, Random rng) {
        List<MethodODResult> findings = new ArrayList<>();

        // Find pairs of methods that share member-level dependencies (potential state conflicts)
        Map<String, Set<String>> methodMembers = new HashMap<>();
        for (String method : methods) {
            Set<String> memberDeps = depMap.getMethodMemberDeps(testClass, method);
            if (!memberDeps.isEmpty()) {
                methodMembers.put(method, memberDeps);
            }
        }

        // For each pair of methods sharing members, test both orders
        List<String> methodsWithDeps = new ArrayList<>(methodMembers.keySet());
        for (int i = 0; i < methodsWithDeps.size() && System.currentTimeMillis() < deadline; i++) {
            for (int j = i + 1; j < methodsWithDeps.size() && System.currentTimeMillis() < deadline; j++) {
                String a = methodsWithDeps.get(i);
                String b = methodsWithDeps.get(j);

                if (alreadyFound.contains(a) && alreadyFound.contains(b)) continue;

                // Check if they share any member deps
                Set<String> shared = new HashSet<>(methodMembers.get(a));
                shared.retainAll(methodMembers.get(b));
                if (shared.isEmpty()) continue;

                // Test [a, b] order
                if (!alreadyFound.contains(b) && passingMethods.contains(b)) {
                    TestRunner.MethodRunResult abResult = runner.runMethods(testClass, List.of(a, b));
                    if (abResult.failed(b)) {
                        findings.add(new MethodODResult(
                                testClass, b, ODType.VICTIM, List.of(a, b),
                                "Method '" + b + "' fails when '" + a + "' runs before it "
                                        + "(shared members: " + shared.stream().limit(3)
                                        .collect(Collectors.joining(", ")) + ")",
                                0.95));
                        alreadyFound.add(b);
                    }
                }

                // Test [b, a] order
                if (!alreadyFound.contains(a) && passingMethods.contains(a)) {
                    TestRunner.MethodRunResult baResult = runner.runMethods(testClass, List.of(b, a));
                    if (baResult.failed(a)) {
                        findings.add(new MethodODResult(
                                testClass, a, ODType.VICTIM, List.of(b, a),
                                "Method '" + a + "' fails when '" + b + "' runs before it "
                                        + "(shared members: " + shared.stream().limit(3)
                                        .collect(Collectors.joining(", ")) + ")",
                                0.95));
                        alreadyFound.add(a);
                    }
                }
            }
        }

        return findings;
    }
}
