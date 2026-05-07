# Detect-Dependencies Algorithm: Concrete Design

**Based on**: PRADET (Gambi/Bell/Zeller 2018), DTDetector (Zhang et al. 2014), PolDet (Gyori et al. 2015),
ElectricTest (Bell et al. 2015), iDFlakies (Lam et al. 2019), Tuscan (Li et al. 2023),
DependTest (Shi et al. 2020), FlaKat (Lin 2023), Flakinator (Malik 2025),
ddmin (Zeller & Hildebrandt 2002), PFAST (Lam et al. 2020), iFixFlakies (Shi et al. 2019)

**Input Data**: DependencyMap (`.test-order/test-dependencies.lz4`), TestOrderState (`.test-order/state.lz4`), Run history

**Algorithms**: 8 strategies (iterative-refinement, random-reordering, reverse-order,
dependence-aware-bounded, tuscan-systematic, history-mining, pfast-exclusion, **combined** [default])

**Phases**: A (conflict graph) → B (iterative refinement with ddmin) → C (orchestrator) → D (cleaner search + constraint feedback)

---

## Available Data Inventory

### From DependencyMap (built by test-order-agent instrumentation)

| Level | Key | Value | Available When |
|-------|-----|-------|----------------|
| Class deps | `testClass` (FQCN) | `Set<appClass>` (FQCNs this test touches) | Always (FULL mode default) |
| Method deps | `testClass#method` | `Set<appClass>` | FULL_METHOD or FULL_MEMBER mode |
| Member deps | `testClass` | `Set<"appClass#field">` (member-level) | FULL_MEMBER mode |
| Method-member deps | `testClass#method` | `Set<"appClass#field">` | FULL_MEMBER mode |

**Critical limitation**: No read/write distinction. We know "test X accesses field Y" but not whether it reads or writes. (PRADET has this via online instrumentation; we trade precision for zero-overhead reuse.)

### From TestOrderState (`.test-order/state.lz4`)

| Data | Description |
|------|-------------|
| `RunRecord[]` | Historical runs: test execution order, pass/fail per test, timestamps, APFD |
| `failureScores` | Decaying failure history per test class |
| `durations` | EMA of execution time per test class/method |
| `ScoringWeights` | Current optimized weights (including `staticFieldBonus`) |

### From Agent Instrumentation Modes

| Mode | Tracks | Overhead | OD-Detection Value |
|------|--------|----------|-------------------|
| `FULL` (default) | Class deps + **static field access** (class-level only) | ~66% | Medium — knows which classes share static fields |
| `FULL_METHOD` | Above + per-test-method class deps | ~68% | Medium — knows which specific test method touches which classes |
| `FULL_MEMBER` | Above + **`class#fieldName`** for all fields (static + instance) | ~121% | **High** — knows exactly which fields each test touches |

### Key Insight: What We Can and Cannot Infer

**CAN infer** (from member deps):
- Two tests T1, T2 both access `com.example.Service#cache` → shared state candidate
- Static field access in FULL mode → class-level conflict signal
- In FULL_MEMBER: exact field names → precise conflict pairs

**CANNOT infer** (without PRADET-style online R/W tracking):
- Whether T1 writes and T2 reads, or both read (read-read is benign)
- Whether the access is in setUp/tearDown vs the test body
- Whether the field is effectively immutable (final, initialized once)

**Pragmatic solution**: Treat all shared-field pairs as *candidate* edges (over-approximation), then use targeted rerun (PRADET's "iterative refinement") to confirm which are manifest.

---

## Detection Granularity: Class-Level and Method-Level

The detect-dependencies system operates at **two granularity levels**, mirroring the existing ordering infrastructure (`PriorityClassOrderer` + `PriorityMethodOrderer`):

### Test Identifier Format

```java
enum TestGranularity { CLASS, METHOD }

/**
 * A test identifier at either class or method level.
 * Class-level: "com.example.FooTest"
 * Method-level: "com.example.FooTest#testSomething"
 */
record TestId(String className, @Nullable String methodName) {
    TestGranularity granularity() {
        return methodName == null ? TestGranularity.CLASS : TestGranularity.METHOD;
    }
    
    String key() {
        return methodName == null ? className : className + "#" + methodName;
    }
    
    TestId classLevel() {
        return new TestId(className, null);
    }
}
```

### When Each Granularity Applies

| Granularity | Data Required | Ordering Hook | Detection Use Case |
|-------------|--------------|---------------|-------------------|
| **Class-level** | Any mode (FULL/FULL_METHOD/FULL_MEMBER) | `PriorityClassOrderer` | Inter-class OD: test class A pollutes test class B |
| **Method-level** | FULL_METHOD or FULL_MEMBER mode | `PriorityMethodOrderer` | Intra-class OD: method `testX` in class C pollutes `testY` in same class C; also cross-class method-level deps |

### Intra-Class vs Inter-Class Dependencies

Most OD detection tools work at class level only (DTDetector, iDFlakies, PRADET). However, **intra-class method-level OD is common** — tests within the same class share `@BeforeAll` state, static fields, and setup/teardown side effects. Our system detects both:

1. **Inter-class (class-level)**: `FooTest` pollutes `BarTest` — detected by reordering classes
2. **Intra-class (method-level)**: `FooTest#testA` pollutes `FooTest#testB` — detected by reordering methods within a class

### How Granularity Affects Each Algorithm

All 7 algorithms operate on a `List<TestId>` which can be either class-level or method-level. The orchestrator runs detection in two passes:

```java
/**
 * Two-pass detection: first between classes, then within classes.
 */
List<ODResult> detectBothLevels(DetectionContext ctx) {
    List<ODResult> results = new ArrayList<>();
    
    // Pass 1: Class-level detection (inter-class OD)
    // Unit = test class; reorder classes, observe cross-class failures
    DetectionContext classCtx = ctx.withGranularity(TestGranularity.CLASS);
    results.addAll(runAlgorithms(classCtx));
    
    // Pass 2: Method-level detection (intra-class OD)
    // For each class with method-level data, reorder methods within it
    if (ctx.depMap().hasMethodDeps()) {
        for (String testClass : ctx.depMap().testClasses()) {
            List<String> methods = ctx.state().methodsOf(testClass);
            if (methods.size() < 2) continue;  // need 2+ methods to have intra-class OD
            
            DetectionContext methodCtx = ctx.scopedToClass(testClass, methods);
            results.addAll(runAlgorithms(methodCtx));
        }
    }
    
    return results;
}
```

### DependencyMap Lookups Per Granularity

| Operation | Class-Level | Method-Level |
|-----------|------------|--------------|
| Get class deps | `depMap.get(testClass)` | `depMap.getMethodDeps("class#method")` |
| Get member deps | `depMap.getMemberDeps(testClass)` | `depMap.getMethodMemberDeps("class#method")` |
| Shared-field pairs | Between different test classes | Between methods (same or different class) |
| Conflict edge | `(FooTest, BarTest, sharedFields)` | `(FooTest#testA, FooTest#testB, sharedFields)` |

### TestRunner Abstraction

The `TestRunner` interface handles both levels:

```java
interface TestRunner {
    /** Run a list of test classes in the given order (class-level detection) */
    TestRunResult runClasses(List<String> classOrder);
    
    /** Run methods within a single class in the given order (method-level detection) */
    TestRunResult runMethodsInClass(String testClass, List<String> methodOrder);
    
    /** Run a specific subset: [(class, method), ...] in exact order */
    TestRunResult runExact(List<TestId> order);
}
```

For class-level: uses Surefire fork with `FileBasedClassOrderer` (existing mechanism).  
For method-level: uses Surefire fork with a custom method order file consumed by `PriorityMethodOrderer`.

### Constraint Output Per Granularity

```java
record OrderConstraint(TestId before, TestId after, ConstraintType type, double confidence) {
    /** True if this is a method-level constraint within a single class */
    boolean isIntraClass() {
        return before.className().equals(after.className()) 
            && before.methodName() != null;
    }
}
```

- Class-level constraints → feed into `PriorityClassOrderer`
- Method-level constraints → feed into `PriorityMethodOrderer`

---

## Algorithm Architecture: Multi-Strategy Design

The system supports **five detection algorithms**, each with different trade-offs. The user selects one (or the orchestrator auto-selects based on available data and time budget):

```
┌─────────────────────────────────────────────────────────┐
│                  DetectDependenciesOperation             │
│  (orchestrator: time budget, algorithm selection, I/O)  │
└────────────────────────┬────────────────────────────────┘
                         │ delegates to
         ┌───────────────┼───────────────────┐
         ▼               ▼                   ▼
┌─────────────┐ ┌─────────────────┐ ┌────────────────┐
│  Algorithm  │ │    Algorithm    │ │   Algorithm    │
│  interface  │ │    interface    │ │   interface    │
└─────────────┘ └─────────────────┘ └────────────────┘
         │               │                   │
    ┌────┴────┐    ┌─────┴─────┐    ┌───────┴───────┐
    │PRADET   │    │Random     │    │Tuscan         │
    │Iterative│    │Reordering │    │Systematic     │
    │Refine   │    │(iDFlakies)│    │Coverage       │
    └─────────┘    └───────────┘    └───────────────┘
    ┌────┴────┐    ┌─────┴─────┐
    │Reverse  │    │History    │
    │Order    │    │Mining     │
    └─────────┘    └───────────┘
```

### Algorithm Interface

```java
interface DetectionAlgorithm {
    
    String name();
    
    /** What data this algorithm requires to function */
    Set<Prerequisite> prerequisites();
    
    /** Estimated runs needed for given test count / edge count */
    int estimatedRuns(int testCount, int conflictEdges);
    
    /** Execute detection within the given budget, return found OD bugs */
    List<ODResult> detect(DetectionContext ctx);
}

record DetectionContext(
    ConflictGraph graph,           // from Phase A (may be empty for some algorithms)
    DependencyMap depMap,          // raw dependency data
    TestOrderState state,          // run history + failure scores
    TestGranularity granularity,   // CLASS or METHOD
    @Nullable String scopeClass,   // non-null for method-level (intra-class) detection
    List<String> referenceOrder,   // last known passing order (class or method IDs)
    Set<String> passingTests,      // tests that pass in reference order
    TestRunner runner,             // executes test orders at either granularity
    long deadlineMillis,           // hard stop time
    long randomSeed                // reproducibility
) {
    /** Create a method-level context scoped to one class */
    DetectionContext scopedToClass(String testClass, List<String> methodOrder) {
        return new DetectionContext(
            buildMethodConflictGraph(testClass), depMap, state, 
            TestGranularity.METHOD, testClass, methodOrder,
            Set.copyOf(methodOrder), runner, deadlineMillis, randomSeed);
    }
    
    DetectionContext withGranularity(TestGranularity g) {
        return new DetectionContext(graph, depMap, state, g, null,
            referenceOrder, passingTests, runner, deadlineMillis, randomSeed);
    }
    
    /** Convenience: are we detecting at method level? */
    boolean isMethodLevel() { return granularity == TestGranularity.METHOD; }
}

enum Prerequisite {
    DEPENDENCY_MAP,          // test-dependencies.lz4 exists
    MEMBER_DEPS,             // FULL_MEMBER mode data available
    RUN_HISTORY,             // at least 1 historical run in state
    MULTIPLE_RUNS,           // 3+ historical runs (for anomaly detection)
    PASSING_REFERENCE        // at least one known all-passing order
}
```

---

## Algorithm 1: PRADET-Style Iterative Refinement

**Name**: `iterative-refinement`  
**Prerequisites**: `DEPENDENCY_MAP` + `PASSING_REFERENCE` (best with `MEMBER_DEPS`)  
**Inspired by**: PRADET (Gambi/Bell/Zeller 2018)  
**Strength**: Most precise — directly targets high-probability conflict edges  
**Best for**: Projects with rich dependency data (FULL_MEMBER mode); medium-sized test suites (20-200 tests)

### Overview

```
Phase A: Build Conflict Graph (static, from DependencyMap)
         → Over-approximate set of candidate dependency edges
         
Phase B: Iterative Refinement (dynamic, via targeted reruns)
         → Confirm which edges are manifest dependencies
```

This mirrors PRADET exactly, but replaces PRADET's expensive Phase A (online heap instrumentation during a full reference run) with our pre-built DependencyMap — **zero additional instrumentation cost** since the agent already builds this during normal test-order learn runs.

---

### Phase A: Conflict Graph Construction

#### Input
```java
DependencyMap depMap = DependencyMap.load(path);  // .test-order/test-dependencies.lz4
```

#### Algorithm A.1: Extract Shared-Member Pairs

The conflict graph construction is parameterized by granularity. The same algorithm applies at both class and method level — only the lookup function changes:

```java
/**
 * Build shared-member map at the appropriate granularity.
 * @param testIds - class names (class-level) or "class#method" keys (method-level)
 */
Map<String, Set<String>> extractSharedMembers(List<String> testIds, 
                                               DependencyMap depMap,
                                               TestGranularity granularity) {
    Map<String, Set<String>> memberToTests = new HashMap<>();
    
    for (String testId : testIds) {
        // Look up member deps at the appropriate level
        Set<String> members = (granularity == TestGranularity.CLASS)
            ? depMap.getMemberDeps(testId)           // class → Set<"appClass#field">
            : depMap.getMethodMemberDeps(testId);    // class#method → Set<"appClass#field">
        
        if (members == null) continue;
        for (String member : members) {
            memberToTests.computeIfAbsent(member, k -> new LinkedHashSet<>()).add(testId);
        }
    }
    
    // Filter: only members accessed by 2+ test units are conflict candidates
    return memberToTests.entrySet().stream()
        .filter(e -> e.getValue().size() >= 2)
        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
}
```

**Class-level example** (inter-class OD):
```java
// testIds = ["com.example.FooTest", "com.example.BarTest", ...]
// Finds: both FooTest and BarTest access "com.example.Cache#instance"
```

**Method-level example** (intra-class OD):
```java
// testIds = ["com.example.FooTest#testA", "com.example.FooTest#testB", ...]
// Finds: both testA and testB within FooTest access "com.example.Config#initialized"
```

For `FULL` mode (no member data): fall back to class-level overlap:
```java
Map<String, Set<String>> classToTests = new HashMap<>();
for (String testId : testIds) {
    Set<String> deps = (granularity == TestGranularity.CLASS)
        ? depMap.get(testId)
        : depMap.getMethodDeps(testId);
    if (deps == null) continue;
    for (String dep : deps) {
        classToTests.computeIfAbsent(dep, k -> new LinkedHashSet<>()).add(testId);
    }
}
// Shared class = weaker signal, but still useful
```

#### Algorithm A.2: Build Weighted Conflict Graph

```java
record ConflictEdge(String testA, String testB, Set<String> sharedMembers, double weight) {}

List<ConflictEdge> edges = new ArrayList<>();
Set<Set<String>> seenPairs = new HashSet<>();

for (var entry : sharedMembers.entrySet()) {
    String member = entry.getKey();
    List<String> tests = new ArrayList<>(entry.getValue());
    
    for (int i = 0; i < tests.size(); i++) {
        for (int j = i + 1; j < tests.size(); j++) {
            Set<String> pair = Set.of(tests.get(i), tests.get(j));
            if (seenPairs.add(pair)) {
                edges.add(new ConflictEdge(tests.get(i), tests.get(j), 
                    computeSharedMembers(tests.get(i), tests.get(j), depMap), 0.0));
            }
        }
    }
}

// Assign weights
for (ConflictEdge edge : edges) {
    edge.weight = computeWeight(edge, depMap, state);
}

// Sort by weight descending — highest-priority edges first
edges.sort(Comparator.comparingDouble(ConflictEdge::weight).reversed());
```

#### Algorithm A.3: Edge Weighting Function

Weight formula combining multiple signals (inspired by Flakinator's Bayesian multi-signal approach):

```java
double computeWeight(ConflictEdge edge, DependencyMap depMap, TestOrderState state) {
    double w = 0.0;
    
    // Signal 1: Number of shared members (more shared fields = more likely OD)
    // Normalized to [0,1] with diminishing returns
    int shared = edge.sharedMembers.size();
    w += 0.3 * (1.0 - 1.0 / (1.0 + shared));  // asymptotic to 0.3
    
    // Signal 2: Static field indicator
    // Members containing known mutable-state patterns get bonus
    long staticFieldCount = edge.sharedMembers.stream()
        .filter(m -> isLikelyMutableStaticField(m, depMap))
        .count();
    w += 0.3 * Math.min(1.0, staticFieldCount / 3.0);
    
    // Signal 3: Same test class (intra-class OD more common per Tuscan research)
    if (sameTestClass(edge.testA, edge.testB)) {
        w += 0.15;
    }
    
    // Signal 4: Historical failure signal
    // If either test has failed before, conflict is more suspicious
    double failA = state.getFailureScore(edge.testA);
    double failB = state.getFailureScore(edge.testB);
    w += 0.15 * Math.min(1.0, failA + failB);
    
    // Signal 5: Test duration ratio (very fast tests less likely to do state mutation)
    long durA = state.getDuration(edge.testA, 1000);
    long durB = state.getDuration(edge.testB, 1000);
    double minDur = Math.min(durA, durB);
    w += 0.1 * Math.min(1.0, minDur / 5000.0);  // bonus if either test > 5s
    
    return Math.min(1.0, w);
}
```

**Heuristic for mutable static field detection** (without R/W info):
```java
boolean isLikelyMutableStaticField(String memberKey, DependencyMap depMap) {
    // memberKey = "com.example.Service#instanceCache"
    String fieldName = memberKey.substring(memberKey.indexOf('#') + 1);
    
    // Heuristic 1: common mutable-state naming patterns
    if (fieldName.matches("(?i).*(cache|registry|instance|pool|map|list|set|queue|counter|state|config|context).*")) {
        return true;
    }
    
    // Heuristic 2: <clinit> (class initializer) — always suspicious
    if (fieldName.equals("<clinit>")) return true;
    
    // Heuristic 3: ALL_CAPS suggests constant (less suspicious)
    if (fieldName.matches("[A-Z_0-9]+")) return false;
    
    // Default: assume mutable (over-approximate, like PRADET)
    return true;
}
```

#### Output of Phase A

```java
record ConflictGraph(
    List<String> testClasses,              // all test classes
    List<ConflictEdge> edges,              // sorted by weight desc
    Map<String, Set<String>> memberIndex   // member → tests (for lookup)
) {}
```

Typical sizes:
- 20-test project: ~190 potential pairs, ~10-30 after shared-member filter
- 100-test project: ~4950 pairs, ~100-500 conflict edges
- 500-test project: ~125K pairs, ~1000-5000 edges (but only top-N tested)

---

### Phase B: Iterative Refinement (Targeted Reruns)

This is the PRADET-inspired core: for each candidate edge, we generate a test order that **violates** that edge (runs the pair in reverse relative order) and check if the outcome changes.

#### Algorithm B.1: Edge Selection Strategy

```java
// Budget: N refinement runs allowed (derived from time budget)
// Each run costs ~avgTestDuration × testsPerRun
int budget = (int) (timeBudgetSeconds / avgRunDuration);

// Strategy: prioritized iteration over edges
List<ConflictEdge> toCheck = conflictGraph.edges;  // already sorted by weight desc

// Optimization: skip edges where both tests always pass in all historical runs
toCheck = toCheck.stream()
    .filter(e -> !alwaysPassedTogether(e, state))
    .collect(toList());
```

#### Algorithm B.2: Order Generation Per Edge

For a given edge (testA, testB) with weight w, we want to check if running them in reverse triggers a failure. But unlike PRADET (which runs only the pair), we need to run within Surefire/JUnit — so we manipulate the **class order** of the full suite.

```java
List<String> generateViolatingOrder(ConflictEdge edge, List<String> referenceOrder) {
    // Reference order: [... testA at pos i ... testB at pos j ...] where i < j
    // Violating order: swap their relative positions
    
    List<String> order = new ArrayList<>(referenceOrder);
    int posA = order.indexOf(edge.testA);
    int posB = order.indexOf(edge.testB);
    
    if (posA < posB) {
        // Normal: A before B. Violate by putting B before A.
        order.remove(posB);
        order.add(posA, edge.testB);  // B now at A's old position
    } else {
        // Already B before A — try A before B
        order.remove(posA);
        order.add(posB, edge.testA);
    }
    
    return order;
}
```

**Enhanced: Cluster Violation** (test multiple edges per run):

Since each run is expensive, we can violate multiple independent edges simultaneously:

```java
List<String> generateClusterViolation(List<ConflictEdge> edgeBatch, List<String> referenceOrder) {
    // Select edges that don't share tests (independent)
    // Reverse all their relative orderings in one run
    // If failure → binary-search which edge caused it
    
    List<String> order = new ArrayList<>(referenceOrder);
    for (ConflictEdge edge : edgeBatch) {
        swapRelativeOrder(order, edge.testA, edge.testB);
    }
    return order;
}
```

Cluster size: `sqrt(remainingEdges)` edges per batch (balances coverage vs pinpointing cost).

#### Algorithm B.3: Run and Interpret Results

```java
record RefinementResult(ConflictEdge edge, boolean manifestDependency, 
                        String failingTest, String failureMessage) {}

RefinementResult checkEdge(ConflictEdge edge, List<String> referenceOrder, 
                           Set<String> referenceResults) {
    List<String> violatingOrder = generateViolatingOrder(edge, referenceOrder);
    
    TestRunResult result = runTests(violatingOrder);  // execute via Surefire fork
    
    // Compare outcomes: any test that was passing now fails?
    for (TestOutcome outcome : result.outcomes()) {
        if (outcome.failed() && referenceResults.contains(outcome.testClass())) {
            // This test passed in reference but fails now → manifest dependency!
            return new RefinementResult(edge, true, outcome.testClass(), outcome.message());
        }
    }
    
    // No outcome change → edge is benign, remove from consideration
    return new RefinementResult(edge, false, null, null);
}
```

#### Algorithm B.4: Delta Debugging Isolation (ddmin)

When a cluster-violation run fails, we need to find the minimal set of violated edges that cause the failure. Simple binary search assumes a **single culprit**, but real OD bugs can involve **co-polluters** — multiple tests that must ALL run before the victim for the failure to manifest. The ddmin algorithm (Zeller & Hildebrandt 2002) handles both cases optimally.

**Key insight**: ddmin finds a **1-minimal** failure-inducing subset — removing ANY single element makes the failure disappear. This handles:
- Single polluter: resolves in O(log₂ n) runs (same as binary search)
- Co-polluters (k tests needed): resolves in O(k × log₂(n/k)) runs
- Worst case: O(n²) runs (rare; requires nearly all tests to be co-polluters)

```java
/**
 * Full ddmin algorithm for isolating the minimal failing subset.
 * Input: a set of edges whose collective violation causes a failure.
 * Output: 1-minimal subset — removing any single edge removes the failure.
 */
List<ConflictEdge> ddmin(List<ConflictEdge> failingSet, List<String> referenceOrder,
                         Set<String> referenceResults) {
    return ddminRecurse(failingSet, 2, referenceOrder, referenceResults);
}

List<ConflictEdge> ddminRecurse(List<ConflictEdge> cF, int n,
                                List<String> referenceOrder, Set<String> referenceResults) {
    // Base: if only 1 element, it's minimal
    if (cF.size() == 1) return cF;
    
    // Partition into n subsets
    List<List<ConflictEdge>> partitions = partition(cF, n);
    
    // Step 1: Reduce to subset — try each partition alone
    for (List<ConflictEdge> delta_i : partitions) {
        List<String> order = generateClusterViolation(delta_i, referenceOrder);
        TestRunResult result = runTests(order);
        
        if (hasNewFailure(result, referenceResults)) {
            // Failure in subset alone — recurse with n=2
            return ddminRecurse(delta_i, 2, referenceOrder, referenceResults);
        }
    }
    
    // Step 2: Reduce to complement — try removing each partition
    for (List<ConflictEdge> delta_i : partitions) {
        List<ConflictEdge> complement = new ArrayList<>(cF);
        complement.removeAll(delta_i);
        
        List<String> order = generateClusterViolation(complement, referenceOrder);
        TestRunResult result = runTests(order);
        
        if (hasNewFailure(result, referenceResults)) {
            // Complement still fails — recurse with adjusted granularity
            return ddminRecurse(complement, Math.max(n - 1, 2), 
                              referenceOrder, referenceResults);
        }
    }
    
    // Step 3: Neither subset nor complement alone fails 
    // → co-polluters span partition boundaries. Increase granularity.
    if (n < cF.size()) {
        return ddminRecurse(cF, Math.min(2 * n, cF.size()), 
                           referenceOrder, referenceResults);
    }
    
    // Termination: n == |cF| and nothing reduces → this IS the 1-minimal set
    return cF;
}

List<List<ConflictEdge>> partition(List<ConflictEdge> items, int n) {
    int size = items.size();
    int chunkSize = (size + n - 1) / n;
    List<List<ConflictEdge>> result = new ArrayList<>();
    for (int i = 0; i < size; i += chunkSize) {
        result.add(items.subList(i, Math.min(i + chunkSize, size)));
    }
    return result;
}
```

**Complexity comparison** with naive binary search:

| Scenario | Binary Search | ddmin |
|----------|--------------|-------|
| Single polluter (typical) | O(log₂ n) | O(log₂ n) — identical |
| 2 co-polluters | **Fails** (returns wrong answer) | O(2 × log₂(n/2)) |
| k co-polluters | **Fails** | O(k × log₂(n/k)) |
| Worst case (all needed) | N/A | O(n²) |

For test-order-dependency detection, co-polluters are uncommon (~18% per DTDetector) but real. The ddmin upgrade costs nothing in the common case and correctly handles the uncommon case.

#### Algorithm B.5: Victim/Polluter Classification

Once a manifest dependency is confirmed between (testA, testB):

```java
record ODClassification(String polluter, String victim, ODType type, 
                        Set<String> sharedState) {}

enum ODType { VICTIM, BRITTLE, UNCLEAR }

ODClassification classify(ConflictEdge edge, String failingTest) {
    // Run failing test ALONE (isolated)
    TestRunResult isolated = runSingleTest(failingTest);
    
    if (isolated.passed()) {
        // Passes alone, fails after the other → VICTIM pattern
        // The other test is the POLLUTER
        String polluter = edge.testA.equals(failingTest) ? edge.testB : edge.testA;
        return new ODClassification(polluter, failingTest, ODType.VICTIM, edge.sharedMembers);
    } else {
        // Fails alone → might be BRITTLE (needs the other to set up state)
        // Or just a genuinely broken test
        String stateSetter = edge.testA.equals(failingTest) ? edge.testB : edge.testA;
        
        // Verify: does it pass when stateSetter runs first?
        TestRunResult withSetter = runTests(List.of(stateSetter, failingTest));
        if (withSetter.passed(failingTest)) {
            return new ODClassification(stateSetter, failingTest, ODType.BRITTLE, edge.sharedMembers);
        }
        return new ODClassification(null, failingTest, ODType.UNCLEAR, edge.sharedMembers);
    }
}
```

---

## Algorithm 2: Random Reordering (iDFlakies / DTDetector)

**Name**: `random-reordering`  
**Prerequisites**: `PASSING_REFERENCE`  
**Inspired by**: iDFlakies (Lam et al. 2019), DTDetector Randomized (Zhang et al. 2014)  
**Strength**: No dependency data needed; surprisingly effective (DTDetector found it detected the most tests in practice)  
**Best for**: First-pass exploration; projects without FULL_MEMBER instrumentation; large test suites where PRADET is too costly

### Overview

The simplest sound approach: shuffle tests randomly, rerun, observe failures. DTDetector's experiments showed the randomized algorithm (1000 trials) detected more dependent tests than even the exhaustive bounded algorithm in some cases. iDFlakies refined this with hierarchical shuffling (classes first, then methods within classes) which better matches real JUnit/Surefire execution semantics.

### Key Insight from iDFlakies

iDFlakies defines 5 configurations. The most effective is **random-class-method (RandomC+M)**: randomize class order, then randomize method order within each class, but never interleave methods from different classes. This matches how Maven Surefire actually executes tests.

### Algorithm

```java
class RandomReorderingAlgorithm implements DetectionAlgorithm {

    String name() { return "random-reordering"; }
    
    Set<Prerequisite> prerequisites() { return Set.of(Prerequisite.PASSING_REFERENCE); }
    
    int estimatedRuns(int testCount, int conflictEdges) {
        // DTDetector recommends 1000 runs for thorough detection
        // iDFlakies uses 20 rounds as default (time-bounded)
        // We use min(100, timeBudget / avgRunTime)
        return 100;
    }
    
    List<ODResult> detect(DetectionContext ctx) {
        List<ODResult> found = new ArrayList<>();
        Random rng = new Random(ctx.randomSeed());
        int round = 0;
        
        while (System.currentTimeMillis() < ctx.deadlineMillis()) {
            round++;
            
            // RandomC+M: shuffle classes, then shuffle methods within each class
            List<String> order = shuffleClassMethod(ctx.referenceOrder(), rng);
            
            TestRunResult result = ctx.runner().run(order);
            
            // Any test that passed in reference but fails now is a candidate
            for (TestOutcome outcome : result.outcomes()) {
                if (outcome.failed() && ctx.passingTests().contains(outcome.testId())) {
                    // Classification step (iDFlakies §III-B):
                    // Rerun truncated failing order to confirm OD vs NOD
                    ODResult od = classifyFailure(outcome, order, ctx);
                    if (od != null) found.add(od);
                }
            }
        }
        
        return found;
    }
    
    /**
     * iDFlakies classification: rerun the truncated failing order
     * (all tests up to and including the failing test).
     * If it fails again → OD. If it passes → NOD (flaky for other reasons).
     */
    ODResult classifyFailure(TestOutcome failure, List<String> failingOrder, 
                             DetectionContext ctx) {
        // Truncate: keep only tests up to the failing test
        int failIdx = failingOrder.indexOf(failure.testId());
        List<String> truncated = failingOrder.subList(0, failIdx + 1);
        
        // Rerun truncated failing order
        TestRunResult rerun = ctx.runner().run(truncated);
        if (!rerun.failed(failure.testId())) {
            return null;  // NOD — not order-dependent
        }
        
        // Confirm: passes in truncated original order?
        int origIdx = ctx.referenceOrder().indexOf(failure.testId());
        List<String> truncatedOrig = ctx.referenceOrder().subList(0, origIdx + 1);
        TestRunResult origRerun = ctx.runner().run(truncatedOrig);
        
        if (origRerun.passed(failure.testId())) {
            // Confirmed OD: fails in reordered, passes in original
            return new ODResult(failure.testId(), ODType.VICTIM, truncated, 
                              failure.message());
        }
        return null;  // Fails in both orders — broken test, not OD
    }
    
    List<String> shuffleClassMethod(List<String> tests, Random rng) {
        // Group by class, shuffle class order, shuffle within each class
        Map<String, List<String>> byClass = tests.stream()
            .collect(groupingBy(t -> t.contains("#") ? t.substring(0, t.indexOf('#')) : t,
                               LinkedHashMap::new, toList()));
        
        List<String> classOrder = new ArrayList<>(byClass.keySet());
        Collections.shuffle(classOrder, rng);
        
        List<String> result = new ArrayList<>();
        for (String cls : classOrder) {
            List<String> methods = new ArrayList<>(byClass.get(cls));
            Collections.shuffle(methods, rng);
            result.addAll(methods);
        }
        return result;
    }
}
```

### Complexity

| Metric | Value |
|--------|-------|
| Runs needed | N (user-specified, default 100) |
| Per-run cost | Full test suite execution |
| Total cost | N × suite duration |
| Detection rate (DTDetector empirical) | ~80-95% of all OD tests with 1000 runs |
| Detection rate (iDFlakies, 20 rounds) | 50.5% of detected flaky tests were OD |

### When to Use

- **Always viable** — only needs one passing run as baseline
- Best as first pass before more targeted algorithms
- Good for projects with many tests where conflict graph would be too large
- Recommended: run 20-100 rounds with RandomC+M configuration

---

## Algorithm 3: Reverse Order (DTDetector / iDFlakies)

**Name**: `reverse-order`  
**Prerequisites**: `PASSING_REFERENCE`  
**Inspired by**: DTDetector Reversal (Zhang et al. 2014), iDFlakies ReverseC+M  
**Strength**: Cheapest possible check — exactly 1 additional run  
**Best for**: Quick sanity check; CI integration; smoke test for OD presence

### Overview

Run all tests in reverse order. If any test that passed in the original order now fails, it's an OD candidate. This is the absolute minimum-cost detection: 1 run. DTDetector showed it finds a subset of all OD tests — specifically those dependent on tests that run later in the original order.

iDFlakies offers two variants:
- **ReverseC**: reverse class order, keep method order within classes
- **ReverseC+M**: reverse both class order and method order within classes

### Algorithm

```java
class ReverseOrderAlgorithm implements DetectionAlgorithm {

    String name() { return "reverse-order"; }
    
    Set<Prerequisite> prerequisites() { return Set.of(Prerequisite.PASSING_REFERENCE); }
    
    int estimatedRuns(int testCount, int conflictEdges) { return 1; }
    
    List<ODResult> detect(DetectionContext ctx) {
        List<ODResult> found = new ArrayList<>();
        
        // Reverse class-method order (iDFlakies ReverseC+M)
        List<String> reversed = reverseClassMethod(ctx.referenceOrder());
        
        TestRunResult result = ctx.runner().run(reversed);
        
        for (TestOutcome outcome : result.outcomes()) {
            if (outcome.failed() && ctx.passingTests().contains(outcome.testId())) {
                // Use same classification as Algorithm 2
                ODResult od = classifyFailure(outcome, reversed, ctx);
                if (od != null) found.add(od);
            }
        }
        
        return found;
    }
    
    List<String> reverseClassMethod(List<String> tests) {
        // Group by class, reverse class order, reverse within each class
        Map<String, List<String>> byClass = tests.stream()
            .collect(groupingBy(t -> t.contains("#") ? t.substring(0, t.indexOf('#')) : t,
                               LinkedHashMap::new, toList()));
        
        List<String> classOrder = new ArrayList<>(byClass.keySet());
        Collections.reverse(classOrder);
        
        List<String> result = new ArrayList<>();
        for (String cls : classOrder) {
            List<String> methods = new ArrayList<>(byClass.get(cls));
            Collections.reverse(methods);
            result.addAll(methods);
        }
        return result;
    }
}
```

### Complexity

| Metric | Value |
|--------|-------|
| Runs needed | 1 (+ 1-2 classification runs per failure) |
| Total cost | ~1× suite duration |
| Detection rate | Low — finds only tests dependent on later-running tests |
| False negatives | High — misses dependencies between adjacent or same-half tests |

### When to Use

- **Always run first** — virtually free and immediate signal
- Perfect for CI pipelines (add as a post-test check)
- If it finds nothing, proceed to Random or PRADET
- If it finds something, you know OD bugs exist → invest in thorough detection

---

## Algorithm 4: Dependence-Aware Bounded (DTDetector)

**Name**: `dependence-aware-bounded`  
**Prerequisites**: `DEPENDENCY_MAP` + `PASSING_REFERENCE`  
**Inspired by**: DTDetector §4.4 (Zhang et al. 2014)  
**Strength**: Exhaustive within bound k — guarantees finding all OD bugs between any k tests that share state  
**Best for**: Small-medium test suites (≤50 tests); when completeness within k matters

### Overview

DTDetector's most sophisticated algorithm. The key insight: **if two tests don't share any mutable static fields, they cannot be order-dependent** (for heap-based dependencies). This prunes the O(n^k) search space dramatically.

Our advantage: DTDetector had to instrument tests at runtime to discover which fields each test accesses. We already have this data in **DependencyMap** — specifically the `memberDependencies` section lists exactly which `class#field` each test touches. This means we can apply DTDetector's dependence-aware pruning **without any runtime overhead**.

### Algorithm

```java
class DependenceAwareBoundedAlgorithm implements DetectionAlgorithm {

    private final int k;  // default 2

    String name() { return "dependence-aware-bounded-k" + k; }
    
    Set<Prerequisite> prerequisites() { 
        return Set.of(Prerequisite.DEPENDENCY_MAP, Prerequisite.PASSING_REFERENCE); 
    }
    
    int estimatedRuns(int testCount, int conflictEdges) {
        // Worst case: all pairs that share fields = conflictEdges
        // With k=2: conflictEdges permutations (both orderings)
        return conflictEdges * 2;
    }
    
    List<ODResult> detect(DetectionContext ctx) {
        List<ODResult> found = new ArrayList<>();
        
        // Step 1: Compute field sets per test (from DependencyMap)
        Map<String, Set<String>> testFields = new HashMap<>();
        for (String test : ctx.depMap().testClasses()) {
            Set<String> fields = ctx.depMap().hasMemberDeps() 
                ? ctx.depMap().getMemberDeps(test)
                : ctx.depMap().get(test);  // fallback to class-level
            testFields.put(test, fields);
        }
        
        // Step 2: Isolation baseline — run each test alone
        // (DTDetector §4.4 k=1: test in isolation vs default order)
        Map<String, Boolean> isolationResults = new HashMap<>();
        for (String test : ctx.referenceOrder()) {
            if (System.currentTimeMillis() >= ctx.deadlineMillis()) break;
            TestRunResult isolated = ctx.runner().run(List.of(test));
            isolationResults.put(test, isolated.passed(test));
        }
        
        // Tests that fail in isolation but pass in original → BRITTLE
        for (var entry : isolationResults.entrySet()) {
            if (!entry.getValue() && ctx.passingTests().contains(entry.getKey())) {
                found.add(new ODResult(entry.getKey(), ODType.BRITTLE, 
                    List.of(entry.getKey()), "Fails in isolation"));
            }
        }
        
        // Step 3: Generate k-permutations, pruning by shared fields
        // For k=2: only test pairs (t_i, t_j) where fields(t_i) ∩ fields(t_j) ≠ ∅
        List<String> tests = new ArrayList<>(ctx.referenceOrder());
        
        for (int i = 0; i < tests.size() && System.currentTimeMillis() < ctx.deadlineMillis(); i++) {
            for (int j = 0; j < tests.size(); j++) {
                if (i == j) continue;
                String t1 = tests.get(i);
                String t2 = tests.get(j);
                
                // PRUNING: skip if no shared fields (DTDetector's key optimization)
                Set<String> shared = new HashSet<>(testFields.getOrDefault(t1, Set.of()));
                shared.retainAll(testFields.getOrDefault(t2, Set.of()));
                if (shared.isEmpty()) continue;
                
                // Additional prune: skip if t2's reads aren't written by t1
                // (We can't distinguish R/W, so skip this — over-approximate)
                
                // Run the permutation [t1, t2]
                TestRunResult result = ctx.runner().run(List.of(t1, t2));
                
                // Compare with isolation results
                if (result.failed(t2) && isolationResults.getOrDefault(t2, true)) {
                    // t2 passes alone but fails after t1 → VICTIM
                    found.add(new ODResult(t2, ODType.VICTIM, 
                        List.of(t1, t2), "Polluter: " + t1 + ", shared: " + shared));
                }
                if (result.failed(t1) && isolationResults.getOrDefault(t1, true)) {
                    // t1 fails when run before t2 in 2-sequence (unusual but possible)
                    found.add(new ODResult(t1, ODType.UNCLEAR, 
                        List.of(t1, t2), "Shared: " + shared));
                }
            }
        }
        
        return found;
    }
}
```

### DTDetector's Pruning Power (from paper §6)

> "The dependence-aware k-bounded algorithm ran about an order of magnitude faster than the exhaustive bounded algorithm."

With our pre-built DependencyMap, the pruning is **free** (no runtime instrumentation needed). For a typical project:
- 50 tests → 2450 possible 2-permutations
- After shared-field pruning: ~50-200 permutations (95-98% reduction)
- Each permutation runs only 2 tests → very fast per run

### Complexity

| Metric | Value |
|--------|-------|
| Isolation runs | n (one per test) |
| Pair runs (worst case) | O(n²) |
| Pair runs (with pruning) | O(E) where E = conflict edges from DependencyMap |
| Witness length | Minimal (exactly k tests) — easiest to debug |
| Completeness (k=2) | Finds all OD bugs between any 2 tests sharing state |
| Misses | Dependencies requiring 3+ tests in specific order (~18% per DTDetector study) |

### When to Use

- Small-medium suites (≤50 test classes) where O(n²) pairs are tractable
- When you need **minimal witnesses** (polluter + victim pair) for debugging
- When you want exhaustive coverage within the k-bound
- Complement with Algorithm 2 (Random) to catch k>2 dependencies

---

## Algorithm 5: Tuscan Systematic Coverage (Li et al. 2023)

**Name**: `tuscan-systematic`  
**Prerequisites**: None (works on test list alone; `PASSING_REFERENCE` improves classification)  
**Inspired by**: Li et al., ISSTA 2023; Wei et al., TACAS 2021  
**Strength**: Guarantees every pair of tests is adjacent in at least one permutation — optimal coverage with minimal runs  
**Best for**: Thorough systematic detection; medium test suites (20-100 tests); when you need mathematical coverage guarantees

### Overview

A Tuscan square of order n is an n×n array where every ordered pair of symbols appears as adjacent entries in some row. Applying Tuscan squares to test ordering guarantees that for every pair of tests (A, B), there exists at least one permutation where A runs immediately before B. This means **any pairwise OD dependency is guaranteed to manifest** — unlike random which only has probabilistic coverage.

Key empirical result from Li et al.: **only ~3.3 minimal orders are needed on average to detect all OD tests**, and the Tuscan approach achieves 97.2% detection rate with ~104.7 test orders for intra-class detection.

### Algorithm

```java
class TuscanSystematicAlgorithm implements DetectionAlgorithm {

    String name() { return "tuscan-systematic"; }
    
    Set<Prerequisite> prerequisites() { return Set.of(); }  // Works on any test list
    
    int estimatedRuns(int testCount, int conflictEdges) {
        // Tuscan square of order n requires n permutations
        // (each row is a permutation; n rows guarantee all adjacent pairs)
        return testCount;
    }
    
    List<ODResult> detect(DetectionContext ctx) {
        List<ODResult> found = new ArrayList<>();
        List<String> tests = ctx.referenceOrder();
        int n = tests.size();
        
        // Generate Tuscan square permutations
        // For prime n: use the construction T[i][j] = (i*j + j) mod n
        // For non-prime n: use the smallest prime p ≥ n, generate p×p, truncate
        List<List<String>> permutations = generateTuscanPermutations(tests);
        
        // Execute each permutation
        for (List<String> perm : permutations) {
            if (System.currentTimeMillis() >= ctx.deadlineMillis()) break;
            
            TestRunResult result = ctx.runner().run(perm);
            
            for (TestOutcome outcome : result.outcomes()) {
                if (outcome.failed() && ctx.passingTests().contains(outcome.testId())) {
                    // Found a failure — the immediately preceding test is the polluter candidate
                    int failIdx = perm.indexOf(outcome.testId());
                    String suspectedPolluter = failIdx > 0 ? perm.get(failIdx - 1) : null;
                    
                    found.add(new ODResult(outcome.testId(), ODType.VICTIM,
                        perm.subList(Math.max(0, failIdx - 1), failIdx + 1),
                        "Suspected polluter: " + suspectedPolluter));
                }
            }
        }
        
        return found;
    }
    
    /**
     * Tuscan square construction (Wei et al. 2021):
     * For prime p: row i = [i*0+0, i*1+1, i*2+2, ..., i*(p-1)+(p-1)] mod p
     * This gives p permutations where every ordered pair (a,b) is adjacent in some row.
     */
    List<List<String>> generateTuscanPermutations(List<String> tests) {
        int n = tests.size();
        int p = nextPrime(n);  // smallest prime ≥ n
        
        List<List<String>> perms = new ArrayList<>();
        for (int i = 0; i < p; i++) {
            List<String> row = new ArrayList<>();
            for (int j = 0; j < p; j++) {
                int idx = (i * j + j) % p;  // Tuscan construction
                if (idx < n) {
                    row.add(tests.get(idx));
                }
            }
            // Pad with remaining tests if row is short (due to non-prime truncation)
            if (row.size() == n) {
                perms.add(row);
            }
        }
        
        // Fallback: if construction doesn't yield enough, use shift-based
        if (perms.size() < n) {
            perms = generateShiftPermutations(tests);
        }
        
        return perms;
    }
    
    /** Simpler fallback: cyclic shift — guarantees all pairs appear in n permutations */
    List<List<String>> generateShiftPermutations(List<String> tests) {
        int n = tests.size();
        List<List<String>> perms = new ArrayList<>();
        for (int shift = 0; shift < n; shift++) {
            List<String> perm = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                perm.add(tests.get((j + shift) % n));
            }
            perms.add(perm);
        }
        return perms;
    }
}
```

### Complexity

| Metric | Value |
|--------|-------|
| Permutations needed | n (for n tests) — optimal by combinatorial bound |
| Total cost | n × suite duration |
| Coverage guarantee | Every ordered pair (A,B) adjacent in ≥1 permutation |
| Detection rate (Li et al.) | 97.2% of all OD tests |
| Comparison to random | Same detection with ~10× fewer runs for medium suites |

### When to Use

- Medium suites (20-100 tests) where n runs are affordable
- When you need **guaranteed pair coverage** (not probabilistic like Random)
- When you want to be thorough but can't afford full O(n²) bounded approach
- NOT recommended for large suites (>200 tests) — n runs × n tests each becomes expensive

---

## Algorithm 6: History Mining (Zero-Cost Pre-Pass)

**Name**: `history-mining`  
**Prerequisites**: `MULTIPLE_RUNS` (3+ historical runs in TestOrderState)  
**Inspired by**: Flakinator Bayesian approach (Malik 2025), our unique contribution  
**Strength**: Zero additional test runs — mines existing RunRecord data for OD signals  
**Best for**: Projects with rich test history; free pre-pass before any other algorithm; continuous monitoring

### Overview

The test-order agent already records `RunRecord` entries with per-test pass/fail outcomes, execution orders, and timestamps. If the same test intermittently passes and fails across different runs (with different orders), that's a strong OD signal — no additional runs needed.

This is unique to our system: no other tool in the literature leverages historical run data for OD detection. The closest is Flakinator's Bayesian approach (which uses failure rate as a prior), but we go further by correlating failures with order differences.

### Algorithm

```java
class HistoryMiningAlgorithm implements DetectionAlgorithm {

    String name() { return "history-mining"; }
    
    Set<Prerequisite> prerequisites() { return Set.of(Prerequisite.MULTIPLE_RUNS); }
    
    int estimatedRuns(int testCount, int conflictEdges) { return 0; }  // Zero!
    
    List<ODResult> detect(DetectionContext ctx) {
        List<ODResult> found = new ArrayList<>();
        List<RunRecord> runs = ctx.state().runs();
        
        if (runs.size() < 3) return found;  // Need multiple runs for correlation
        
        // Step 1: Find intermittent tests (pass in some runs, fail in others)
        Map<String, List<RunOutcome>> testHistory = new HashMap<>();
        for (RunRecord run : runs) {
            for (TestOutcome outcome : run.outcomes()) {
                testHistory.computeIfAbsent(outcome.testId(), k -> new ArrayList<>())
                    .add(new RunOutcome(run.timestamp(), outcome.passed(), 
                                       run.testOrder()));
            }
        }
        
        List<String> intermittentTests = testHistory.entrySet().stream()
            .filter(e -> {
                List<RunOutcome> outcomes = e.getValue();
                boolean hasPassed = outcomes.stream().anyMatch(RunOutcome::passed);
                boolean hasFailed = outcomes.stream().anyMatch(o -> !o.passed());
                return hasPassed && hasFailed;
            })
            .map(Map.Entry::getKey)
            .toList();
        
        // Step 2: For each intermittent test, correlate failures with order
        for (String test : intermittentTests) {
            List<RunOutcome> outcomes = testHistory.get(test);
            
            // Find tests that appear BEFORE this test in failing runs
            // but NOT before it (or in different position) in passing runs
            Set<String> alwaysBeforeOnFail = null;
            Set<String> neverBeforeOnPass = new HashSet<>();
            
            for (RunOutcome outcome : outcomes) {
                List<String> order = outcome.testOrder();
                int testIdx = order.indexOf(test);
                if (testIdx < 0) continue;
                
                Set<String> predecessors = new HashSet<>(order.subList(0, testIdx));
                
                if (!outcome.passed()) {
                    // Failing run: accumulate predecessors
                    if (alwaysBeforeOnFail == null) {
                        alwaysBeforeOnFail = new HashSet<>(predecessors);
                    } else {
                        alwaysBeforeOnFail.retainAll(predecessors);
                    }
                } else {
                    // Passing run: these tests are NOT polluters (present before and still passes)
                    neverBeforeOnPass.addAll(predecessors);
                }
            }
            
            if (alwaysBeforeOnFail == null) continue;
            
            // Suspected polluters: always before on failure, never before on pass
            // (or: before on fail but NOT before on pass)
            Set<String> suspectedPolluters = new HashSet<>(alwaysBeforeOnFail);
            suspectedPolluters.removeAll(neverBeforeOnPass);
            
            // Step 3: Score candidates using RankFO differential scoring + DependencyMap
            if (!suspectedPolluters.isEmpty()) {
                // RankFO scoring (inspired by Lam et al. "Root Causing Flaky Tests"):
                // For each candidate, compute:
                //   score = count_before_on_fail - count_before_on_pass
                // Higher score → more likely polluter. Ties broken by avg distance.
                String bestPolluter = suspectedPolluters.stream()
                    .max(Comparator.<String>comparingDouble(p -> rankFOScore(p, test, outcomes))
                         .thenComparingDouble(p -> -avgDistance(p, test, outcomes))
                         .thenComparingInt(p -> sharedFieldCount(p, test, ctx.depMap())))
                    .orElse(suspectedPolluters.iterator().next());
                
                found.add(new ODResult(test, ODType.VICTIM,
                    List.of(bestPolluter, test),
                    "History-mined (RankFO): " + bestPolluter + " score=" + 
                    rankFOScore(bestPolluter, test, outcomes)));
            }
        }
        
        return found;
    }
    
    /** RankFO differential: how often p appears before victim on fail vs pass */
    double rankFOScore(String p, String victim, List<RunOutcome> outcomes) {
        int beforeOnFail = 0, beforeOnPass = 0;
        int failRuns = 0, passRuns = 0;
        for (RunOutcome o : outcomes) {
            int victimIdx = o.testOrder().indexOf(victim);
            int pIdx = o.testOrder().indexOf(p);
            if (victimIdx < 0 || pIdx < 0) continue;
            if (!o.passed()) {
                failRuns++;
                if (pIdx < victimIdx) beforeOnFail++;
            } else {
                passRuns++;
                if (pIdx < victimIdx) beforeOnPass++;
            }
        }
        // Normalize by run count to avoid bias from unequal pass/fail ratios
        double failRate = failRuns > 0 ? (double) beforeOnFail / failRuns : 0;
        double passRate = passRuns > 0 ? (double) beforeOnPass / passRuns : 0;
        return failRate - passRate;  // Range [-1, 1]; higher = more suspicious
    }
    
    /** Average distance: closer polluters are more suspicious (state may decay) */
    double avgDistance(String p, String victim, List<RunOutcome> outcomes) {
        int sum = 0, count = 0;
        for (RunOutcome o : outcomes) {
            int victimIdx = o.testOrder().indexOf(victim);
            int pIdx = o.testOrder().indexOf(p);
            if (victimIdx >= 0 && pIdx >= 0 && pIdx < victimIdx) {
                sum += (victimIdx - pIdx);
                count++;
            }
        }
        return count > 0 ? (double) sum / count : Double.MAX_VALUE;
    }
    
    int sharedFieldCount(String testA, String testB, DependencyMap depMap) {
        if (depMap == null || !depMap.hasMemberDeps()) return 0;
        Set<String> fieldsA = depMap.getMemberDeps(testA);
        Set<String> fieldsB = depMap.getMemberDeps(testB);
        if (fieldsA == null || fieldsB == null) return 0;
        Set<String> shared = new HashSet<>(fieldsA);
        shared.retainAll(fieldsB);
        return shared.size();
    }
    
    record RunOutcome(long timestamp, boolean passed, List<String> testOrder) {}
}
```

### Complexity

| Metric | Value |
|--------|-------|
| Additional test runs | **0** |
| Computational cost | O(R × T) where R = historical runs, T = tests |
| Confidence | Lower than dynamic approaches — correlation ≠ causation |
| False positive rate | Medium — some intermittent failures are NOD (concurrency, timing) |
| Unique advantage | Can be run continuously as a background monitor |

### When to Use

- **Always run first** — it's free
- Produces candidates for confirmation by other algorithms (PRADET, Bounded)
- Best when project has 10+ historical runs with varying orders
- Especially valuable when test-order has been running in `learn` mode for a while
- Results should be treated as "suspected" until confirmed by a dynamic algorithm

---

## Algorithm 7: PFAST Single-Exclusion (Brittle Detection)

**Name**: `pfast-exclusion`  
**Prerequisites**: `NONE` (needs only a passing test order)  
**Inspired by**: Lam et al. "PFAST" (Practical Fast Approaches to Sequencing of Test dependencies), Shi et al. "MEM-FAST"  
**Strength**: Trivially parallelizable; detects BRITTLE tests (those that need a specific predecessor to pass)  
**Best for**: Finding state-setters; parallelizable environments; complementing victim-detection algorithms

### Overview

PFAST inverts the usual "add polluter" approach: instead of randomizing to find what breaks a test, it starts from a **known-passing order** and systematically removes one test at a time. If removing test X causes test Y to fail, then Y depends on X (BRITTLE pattern: Y needs X to set up state).

This is complementary to Random/Reverse which primarily find VICTIM patterns (test polluted by new predecessor). PFAST finds tests that **need** their predecessors.

### Algorithm

```java
class PFASTAlgorithm implements DetectionAlgorithm {

    String name() { return "pfast-exclusion"; }
    
    Set<Prerequisite> prerequisites() { return Set.of(); }
    
    int estimatedRuns(int testCount, int conflictEdges) { 
        return testCount;  // One run per excluded test
    }
    
    List<ODResult> detect(DetectionContext ctx) {
        List<ODResult> found = new ArrayList<>();
        List<String> passingOrder = ctx.lastPassingOrder();
        
        if (passingOrder == null || passingOrder.isEmpty()) {
            // Need at least one known-passing order to exclude from
            passingOrder = ctx.state().lastRunOrder();
            if (passingOrder == null) return found;
        }
        
        // Baseline: run full passing order to confirm it still passes
        TestRunResult baseline = runTests(passingOrder);
        if (baseline.hasFailures()) {
            // Passing order no longer passes — cannot use PFAST
            return found;
        }
        
        // For each test, run the suite WITHOUT it and check for new failures
        // This is trivially parallelizable — each exclusion is independent
        for (int i = 0; i < passingOrder.size(); i++) {
            if (ctx.timeBudgetExhausted()) break;
            
            String excluded = passingOrder.get(i);
            List<String> withoutExcluded = new ArrayList<>(passingOrder);
            withoutExcluded.remove(i);
            
            TestRunResult result = runTests(withoutExcluded);
            
            // Check for new failures caused by removing this test
            for (String failedTest : result.newFailures(baseline)) {
                // failedTest passed when excluded was present, fails without it
                // → failedTest is BRITTLE, excluded is its state-setter
                
                // Optional: verify by running only [excluded, failedTest]
                TestRunResult pairCheck = runTests(List.of(excluded, failedTest));
                if (pairCheck.passed(failedTest)) {
                    found.add(new ODResult(failedTest, ODType.BRITTLE,
                        List.of(excluded, failedTest),
                        "PFAST: removing " + excluded + " causes " + failedTest + " to fail"));
                }
            }
        }
        
        return found;
    }
}
```

### MEM-FAST Variant (Bottom-Up Incremental)

An alternative approach inspired by Shi et al.: instead of excluding tests from a passing sequence, **build up** a sequence incrementally:

```java
// MEM-FAST: start with empty, add tests one by one
// When adding test T causes a later test to fail, T is a polluter
List<ODResult> memFastDetect(DetectionContext ctx) {
    List<String> passingOrder = ctx.lastPassingOrder();
    List<ODResult> found = new ArrayList<>();
    
    for (int i = 1; i < passingOrder.size(); i++) {
        // Run tests [0..i] — the first i+1 tests in order
        List<String> prefix = passingOrder.subList(0, i + 1);
        TestRunResult result = runTests(prefix);
        
        // If test at position i fails, one of [0..i-1] is polluting it
        // Use ddmin to isolate (already have that algorithm)
        if (result.failed(passingOrder.get(i))) {
            // Binary search / ddmin among [0..i-1] for the polluter
            // ...
        }
    }
    return found;
}
```

### Complexity

| Metric | Value |
|--------|-------|
| Additional test runs | T (one per excluded test) + verification pairs |
| Parallelizable | **Yes** — all exclusion runs are independent |
| Computational cost | O(T) runs where T = test count |
| Detection type | BRITTLE (state-setter dependencies) |
| False positive rate | Very low — direct causal evidence |
| Unique advantage | Finds dependencies that randomization-based approaches often miss |

### When to Use

- When you need to find **state-setters** (tests that other tests depend on for setup)
- When parallelization is available (CI with multiple executors)
- As a complement to Random/Reverse which primarily find polluters (VICTIM pattern)
- For suites where tests have complex setUp dependencies not visible at the class level

---

## Algorithm 8: Combined Adaptive (Default)

**Name**: `combined` (the default when `algorithm=auto`)  
**Prerequisites**: `PASSING_REFERENCE` (gracefully degrades without other data)  
**Strength**: Single unified algorithm that adaptively combines all techniques; maximizes bugs-per-run  
**Best for**: All projects — replaces the sequential multi-algorithm orchestrator as the default

### Motivation

The sequential orchestrator (Phase C) runs algorithms independently with fixed time splits. This has three inefficiencies:

1. **No information sharing** — reverse-order may find a victim, but iterative-refinement starts fresh without using that knowledge
2. **Fixed time allocation** — if history-mining yields 10 high-confidence suspects, iterative-refinement should prioritize confirming those, not randomly exploring
3. **Redundant work** — multiple algorithms may re-test the same pairs

The Combined Adaptive algorithm is a **single unified loop** that dynamically selects the next action based on what's been learned so far, maximizing information gain per test run.

### Architecture: Priority Queue of Actions

```java
class CombinedAdaptiveAlgorithm implements DetectionAlgorithm {

    String name() { return "combined"; }
    Set<Prerequisite> prerequisites() { return Set.of(Prerequisite.PASSING_REFERENCE); }
    
    int estimatedRuns(int testCount, int conflictEdges) {
        // Adaptive — depends on what we find. Upper bound:
        return Math.min(testCount, 2 + conflictEdges / 5 + 3);
    }

    /**
     * Central data structure: a priority queue of "next actions" scored by 
     * expected information gain. Each action costs 1 test run.
     * 
     * Implemented as a TreeMap<Action> (not PriorityQueue) because we need
     * to iterate and remove/re-insert elements for priority adjustment.
     */
    record Action(double priority, ActionType type, Object payload, String dedupKey) 
        implements Comparable<Action> {
        public int compareTo(Action o) { 
            int c = Double.compare(o.priority, this.priority); // max-first
            return c != 0 ? c : dedupKey.compareTo(o.dedupKey); // stable tie-break
        }
    }
    
    enum ActionType {
        REVERSE_FULL,           // Run full suite in reverse (one-shot)
        CONFIRM_SUSPECT,        // Run [suspect_polluter, victim] and [victim alone]
        CLUSTER_VIOLATE,        // Violate a batch of conflict edges
        RANDOM_SHUFFLE,         // Full random reorder
        EXCLUDE_TEST,           // PFAST: run without one specific test
        ISOLATE_VICTIM,         // Run victim alone to distinguish OD from NOD
        ISOLATE_PAIR,           // Run [polluter, victim] — expect FAILURE to confirm VICTIM
        CONFIRM_BRITTLE,        // Run [setter, brittle] — expect PASS to confirm BRITTLE
        DDMIN_STEP,             // One step of delta debugging to narrow polluter set
        METHOD_REVERSE,         // Reverse method order within one class
        METHOD_EXCLUDE          // Exclude one method within a class
    }
    
    /**
     * Tracks what we know about each test during the run.
     * Mutable state holder (not a true record — Sets are mutated in place).
     * Using a class with accessor-style methods would be more idiomatic,
     * but we use record syntax here for brevity in the design doc.
     */
    static class TestKnowledge {
        final Set<String> confirmedPolluters = new HashSet<>();   // tests proven to pollute this one
        final Set<String> confirmedSetters = new HashSet<>();     // tests that this one NEEDS (BRITTLE)
        final Set<String> eliminatedPolluters = new HashSet<>();  // tests proven NOT to pollute
        Boolean passesAlone = null;       // null = untested, true = OD candidate, false = broken/NOD
        int failureCount = 0;             // how many times this test has failed in this session
        
        TestKnowledge withFailureCount(int n) { this.failureCount = n; return this; }
        TestKnowledge withPassesAlone(Boolean v) { this.passesAlone = v; return this; }
    }
}
```

### Core Loop: Adaptive Action Selection

```java
List<ODResult> detect(DetectionContext ctx) {
    List<ODResult> confirmed = new ArrayList<>();
    TreeSet<Action> queue = new TreeSet<>();
    Set<String> completedDedupKeys = new HashSet<>();
    Map<String, TestKnowledge> knowledge = new HashMap<>();
    
    // ══════════════════════════════════════════════════════════
    // PHASE 0: Zero-cost initialization (no test runs)
    // ══════════════════════════════════════════════════════════
    
    // 0a. History mining — extract suspects from existing run data
    List<Suspect> historyMinedSuspects = mineHistory(ctx);
    for (Suspect s : historyMinedSuspects) {
        // First action for a suspect: isolate victim alone (to confirm it's OD, not NOD)
        queue.add(new Action(
            0.85 + s.rankFOScore() * 0.15,  // priority: 0.85–1.0 based on RankFO
            ActionType.ISOLATE_VICTIM, 
            s.victim(),
            "isolate:" + s.victim()));
    }
    
    // 0b. Build conflict graph (if dep data available)
    ConflictGraph graph = null;
    if (ctx.depMap() != null) {
        graph = buildConflictGraph(ctx);
        
        // Seed queue with top-weighted edges as cluster-violate actions
        List<List<ConflictEdge>> clusters = partitionIntoClusters(graph.edges, 
            clusterSize(graph.edges.size()));
        for (int i = 0; i < clusters.size(); i++) {
            queue.add(new Action(
                0.6 - i * 0.01,  // decreasing priority by rank
                ActionType.CLUSTER_VIOLATE, clusters.get(i),
                "cluster:" + i));
        }
    }
    
    // 0c. Always seed: reverse order (cheap, high detection rate per DTDetector)
    queue.add(new Action(0.9, ActionType.REVERSE_FULL, null, "reverse"));
    
    // 0d. Seed random shuffles as low-priority fallback
    for (int i = 0; i < 5; i++) {
        queue.add(new Action(0.3 - i * 0.02, ActionType.RANDOM_SHUFFLE, 
            ctx.randomSeed() + i, "random:" + i));
    }
    
    // 0e. Seed PFAST exclusions upfront (not contingent on reverse failing)
    // Prioritize excluding tests with most shared state (most likely to be state-setters)
    if (ctx.depMap() != null) {
        List<String> bySharedState = ctx.referenceOrder().stream()
            .sorted(Comparator.comparingInt(t -> 
                -sharedFieldCountTotal(t, ctx.depMap())))
            .limit(config.maxPfastExclusions)
            .toList();
        for (int i = 0; i < bySharedState.size(); i++) {
            queue.add(new Action(0.45 - i * 0.005, ActionType.EXCLUDE_TEST, 
                bySharedState.get(i), "exclude:" + bySharedState.get(i)));
        }
    }
    
    // 0f. If method-level data available, seed intra-class reverse actions
    // Priority weighted by: method count × historical failure rate
    if (ctx.depMap() != null && ctx.depMap().hasMethodDeps()) {
        for (String testClass : classesWithMultipleMethods(ctx)) {
            int methodCount = ctx.state().methodsOf(testClass).size();
            double classFail = ctx.state().getFailureScore(testClass);
            double priority = 0.7 + Math.min(0.15, classFail * 0.1 + methodCount * 0.01);
            queue.add(new Action(priority, ActionType.METHOD_REVERSE, testClass,
                "method-rev:" + testClass));
        }
    }
    
    // ══════════════════════════════════════════════════════════
    // MAIN LOOP: Execute highest-priority action until budget exhausted
    // ══════════════════════════════════════════════════════════
    
    int runCount = 0;
    int runsWithoutNewFind = 0;    // for exploration/exploitation tuning
    int confirmedAtLastCheck = 0;
    
    while (!queue.isEmpty() && !ctx.timeBudgetExhausted()) {
        Action action = queue.pollFirst();
        
        // Skip already-executed dedup keys
        if (completedDedupKeys.contains(action.dedupKey)) continue;
        
        // Skip actions targeting tests already fully characterized
        if (isRedundant(action, knowledge, confirmed)) continue;
        
        // Execute action → produces a test run result
        ActionOutcome outcome = executeAction(action, ctx);
        runCount++;
        completedDedupKeys.add(action.dedupKey);
        
        // Track yield for exploration/exploitation balance
        int newFinds = 0;
        
        // ── React to outcome: generate follow-up actions ──
        
        if (outcome.hasNewFailures()) {
            for (FailureInfo fi : outcome.newFailures()) {
                String victim = fi.failedTest();
                
                // ─── NOD filter: skip tests that fail too often (likely flaky) ───
                TestKnowledge vk = knowledge.computeIfAbsent(victim, 
                    k -> new TestKnowledge());
                vk.withFailureCount(vk.failureCount + 1);
                
                if (vk.failureCount > 3 && vk.passesAlone == null) {
                    // Failed 3+ times but never tested alone → likely NOD/flaky
                    // Demote: test alone at medium priority to confirm
                    queue.add(new Action(0.6, ActionType.ISOLATE_VICTIM, victim,
                        "isolate:" + victim));
                    continue;
                }
                if (Boolean.FALSE.equals(vk.passesAlone)) {
                    // Already known to fail in isolation → NOT OD, skip
                    continue;
                }
                
                // ─── Route based on action type ───
                
                if (action.type == ActionType.CLUSTER_VIOLATE) {
                    List<ConflictEdge> cluster = (List<ConflictEdge>) action.payload;
                    if (cluster.size() == 1) {
                        // Single edge isolated — classify it
                        newFinds++;
                        confirmed.add(classifyWithKnowledge(cluster.get(0), fi, ctx, knowledge));
                    } else {
                        // Multiple edges violated — use ddmin to isolate
                        queue.add(new Action(0.98, ActionType.DDMIN_STEP, 
                            new DdminState(cluster, fi, 2),
                            "ddmin:" + victim + ":" + runCount));
                    }
                    
                } else if (action.type == ActionType.REVERSE_FULL || 
                           action.type == ActionType.RANDOM_SHUFFLE) {
                    // Found failure — need to identify polluter among predecessors
                    List<String> predecessors = outcome.predecessorsOf(victim);
                    
                    // Step 1: First confirm this test passes alone (if unknown)
                    if (vk.passesAlone() == null) {
                        queue.add(new Action(0.96, ActionType.ISOLATE_VICTIM, victim,
                            "isolate:" + victim));
                    }
                    
                    // Step 2: Rank predecessors by shared state + proximity
                    List<String> ranked = rankPolluters(predecessors, victim, ctx, knowledge);
                    
                    // Step 3: Use ddmin to find minimal polluter set among top candidates
                    // (not just top-3 — use conflict graph to pick the right candidates)
                    int candidateCount = Math.min(ranked.size(), 10);
                    List<ConflictEdge> candidateEdges = ranked.subList(0, candidateCount).stream()
                        .map(p -> new ConflictEdge(p, victim, 
                            sharedMembers(p, victim, ctx.depMap()), 0.0))
                        .toList();
                    
                    if (candidateEdges.size() == 1) {
                        queue.add(new Action(0.95, ActionType.ISOLATE_PAIR,
                            new TestPair(ranked.get(0), victim),
                            "pair:" + ranked.get(0) + "→" + victim));
                    } else {
                        queue.add(new Action(0.95, ActionType.DDMIN_STEP,
                            new DdminState(candidateEdges, fi, 2),
                            "ddmin:" + victim + ":" + runCount));
                    }
                    
                } else if (action.type == ActionType.ISOLATE_PAIR) {
                    TestPair pair = (TestPair) action.payload;
                    // [polluter, victim] failed — but does victim fail alone too?
                    if (Boolean.TRUE.equals(knowledge.get(victim).passesAlone)) {
                        // Passes alone, fails after polluter → CONFIRMED VICTIM
                        newFinds++;
                        confirmed.add(new ODResult(victim, ODType.VICTIM,
                            List.of(pair.polluter(), victim),
                            "Isolated pair confirmed: " + pair.polluter() + " → " + victim));
                        knowledge.get(victim).confirmedPolluters.add(pair.polluter());
                        
                        // Boost: look for other victims of same polluter
                        boostRelatedEdges(pair.polluter(), graph, queue, knowledge);
                    } else {
                        // Don't know yet if victim passes alone → schedule isolation
                        queue.add(new Action(0.97, ActionType.ISOLATE_VICTIM, victim,
                            "isolate:" + victim));
                    }
                    
                } else if (action.type == ActionType.ISOLATE_VICTIM) {
                    // This action RAN victim alone and it FAILED
                    // → victim is broken/NOD, not OD. Record and suppress future actions.
                    knowledge.put(victim, vk.withPassesAlone(false));
                    // Remove queued actions targeting this victim
                    queue.removeIf(a -> targetTest(a).equals(victim));
                    
                } else if (action.type == ActionType.CONFIRM_SUSPECT) {
                    Suspect s = (Suspect) action.payload;
                    newFinds++;
                    confirmed.add(new ODResult(s.victim(), s.type(), 
                        s.dependencyChain(), "Confirmed history-mined suspect"));
                        
                } else if (action.type == ActionType.EXCLUDE_TEST) {
                    // PFAST: removing test X caused test Y to fail → Y is BRITTLE candidate
                    // Confirm: does [X, Y] make Y pass again? If yes → X is setter for Y
                    String excluded = (String) action.payload;
                    for (FailureInfo bf : outcome.newFailures()) {
                        queue.add(new Action(0.93, ActionType.CONFIRM_BRITTLE,
                            new TestPair(excluded, bf.failedTest()),
                            "brittle:" + excluded + "→" + bf.failedTest()));
                    }
                    
                } else if (action.type == ActionType.METHOD_REVERSE ||
                           action.type == ActionType.METHOD_EXCLUDE) {
                    String testClass = methodActionClass(action);
                    newFinds++;
                    confirmed.add(classifyMethodLevel(action, fi, testClass, ctx));
                    
                    // Spawn method-exclude actions for this class to isolate which method
                    if (action.type == ActionType.METHOD_REVERSE) {
                        for (String method : ctx.state().methodsOf(testClass)) {
                            queue.add(new Action(0.85, ActionType.METHOD_EXCLUDE,
                                new MethodExclude(testClass, method),
                                "method-excl:" + testClass + "#" + method));
                        }
                    }
                    
                } else if (action.type == ActionType.DDMIN_STEP) {
                    DdminState dds = (DdminState) action.payload;
                    // ddmin found failure in a subset — result is in outcome.ddminResult
                    if (outcome.ddminResult().isMinimal()) {
                        newFinds++;
                        confirmed.add(classifyFromDdmin(outcome.ddminResult(), ctx, knowledge));
                    } else {
                        // Need more ddmin iterations — re-enqueue
                        queue.add(new Action(0.98, ActionType.DDMIN_STEP,
                            outcome.ddminResult().nextState(),
                            "ddmin:" + victim + ":" + runCount));
                    }
                }
            }
        } else {
            // ── No failure — learn from negative result ──
            
            if (action.type == ActionType.CLUSTER_VIOLATE) {
                // Entire cluster is benign → mark edges as eliminated
                List<ConflictEdge> cluster = (List<ConflictEdge>) action.payload;
                for (ConflictEdge e : cluster) {
                    TestKnowledge ka = knowledge.computeIfAbsent(e.testA(), 
                        k -> new TestKnowledge());
                    ka.eliminatedPolluters.add(e.testB());
                    TestKnowledge kb = knowledge.computeIfAbsent(e.testB(), 
                        k -> new TestKnowledge());
                    kb.eliminatedPolluters.add(e.testA());
                }
            }
            
            if (action.type == ActionType.ISOLATE_VICTIM) {
                // Victim passes alone → OD confirmed (it needs some predecessor to fail)
                String victim = (String) action.payload;
                TestKnowledge vk = knowledge.computeIfAbsent(victim, 
                    k -> new TestKnowledge());
                vk.withPassesAlone(true);
                
                // Now promote any pending CONFIRM_SUSPECT / ISOLATE_PAIR for this victim
                // (The test is genuinely OD — confirming the polluter is valuable)
                Suspect suspect = findSuspect(victim, historyMinedSuspects);
                if (suspect != null) {
                    queue.add(new Action(0.94, ActionType.CONFIRM_SUSPECT, suspect,
                        "confirm:" + victim));
                }
            }
            
            if (action.type == ActionType.ISOLATE_PAIR) {
                // [polluter, victim] passed → this polluter does NOT pollute this victim
                TestPair pair = (TestPair) action.payload;
                TestKnowledge vk = knowledge.computeIfAbsent(pair.victim(), 
                    k -> new TestKnowledge());
                vk.eliminatedPolluters.add(pair.polluter());
            }
            
            if (action.type == ActionType.CONFIRM_BRITTLE) {
                // [setter, brittle] passed → CONFIRMED BRITTLE dependency!
                // The brittle test needs the setter to pass.
                TestPair pair = (TestPair) action.payload;
                TestKnowledge vk = knowledge.computeIfAbsent(pair.victim(), 
                    k -> new TestKnowledge());
                vk.confirmedSetters.add(pair.polluter());
                confirmed.add(new ODResult(pair.victim(), ODType.BRITTLE,
                    List.of(pair.polluter(), pair.victim()),
                    "Brittle confirmed: " + pair.victim() + " needs " + pair.polluter()));
            }
            
            if (action.type == ActionType.REVERSE_FULL && outcome.allPassed()) {
                // No OD detectable by full reverse — this is a strong negative signal.
                // Boost PFAST exclusions (may find BRITTLE patterns instead)
                boostType(queue, ActionType.EXCLUDE_TEST, +0.15);
            }
        }
        
        // ── Exploration/exploitation tuning ──
        if (newFinds > 0) {
            runsWithoutNewFind = 0;
        } else {
            runsWithoutNewFind++;
        }
        
        if (runCount % 5 == 0) {
            adjustStrategy(queue, confirmed.size(), runCount, runsWithoutNewFind);
        }
        
        // Early transition: if we've found enough, shift to Cleaner search
        if (confirmed.size() >= 10 && runCount > 15) {
            break;  // Phase D (Cleaner search) is more valuable now
        }
    }
    
    return confirmed;
}
```

### Polluter Ranking (Improved)

A critical step: when a failure is observed, rank the predecessors to find the likely polluter:

```java
/**
 * Rank candidate polluters for a victim using multiple signals.
 * Combines: shared state overlap, proximity, historical correlation, elimination history.
 */
List<String> rankPolluters(List<String> predecessors, String victim, 
                           DetectionContext ctx, Map<String, TestKnowledge> knowledge) {
    TestKnowledge vk = knowledge.get(victim);
    
    return predecessors.stream()
        // Filter out already-eliminated candidates
        .filter(p -> vk == null || !vk.eliminatedPolluters().contains(p))
        // Filter out tests already confirmed as polluters of other victims (less likely to be dual-polluter)
        .sorted(Comparator.<String>comparingDouble(p -> {
            double score = 0.0;
            
            // Signal 1: Shared member count (0–0.35)
            int shared = sharedFieldCount(p, victim, ctx.depMap());
            score += 0.35 * (1.0 - 1.0 / (1.0 + shared));
            
            // Signal 2: Proximity — closer predecessor more likely (state decays)
            int distance = predecessors.indexOf(p);  // 0 = immediately before
            score += 0.25 * (1.0 / (1.0 + distance));
            
            // Signal 3: Historical correlation (RankFO if available)
            Suspect hist = findHistorySuspect(p, victim);
            if (hist != null) score += 0.25 * hist.rankFOScore();
            
            // Signal 4: Mutable static field presence
            if (accessesMutableStaticField(p, ctx.depMap())) score += 0.15;
            
            return score;
        }).reversed())
        .toList();
}
```

### Action Execution Details

```java
ActionOutcome executeAction(Action action, DetectionContext ctx) {
    switch (action.type) {
        case REVERSE_FULL -> {
            List<String> reversed = new ArrayList<>(ctx.referenceOrder());
            Collections.reverse(reversed);
            return runAndCompare(reversed, ctx);
        }
        case CONFIRM_SUSPECT -> {
            // Confirmation = run polluter immediately before victim (rest of suite in reference order)
            // If victim fails → dependency confirmed
            Suspect s = (Suspect) action.payload;
            List<String> order = buildConfirmationOrder(s.polluter(), s.victim(), ctx);
            return runAndCompare(order, ctx);
        }
        case CLUSTER_VIOLATE -> {
            List<ConflictEdge> edges = (List<ConflictEdge>) action.payload;
            List<String> order = generateClusterViolation(edges, ctx.referenceOrder());
            return runAndCompare(order, ctx);
        }
        case RANDOM_SHUFFLE -> {
            long seed = (Long) action.payload;
            List<String> order = new ArrayList<>(ctx.referenceOrder());
            Collections.shuffle(order, new Random(seed));
            return runAndCompare(order, ctx);
        }
        case EXCLUDE_TEST -> {
            String excluded = (String) action.payload;
            List<String> order = new ArrayList<>(ctx.referenceOrder());
            order.remove(excluded);
            return runAndCompare(order, ctx);
        }
        case ISOLATE_VICTIM -> {
            // Run victim alone — if it passes, it's genuinely OD
            // If it fails, it's broken/NOD
            String victim = (String) action.payload;
            return runAndCompare(List.of(victim), ctx);
        }
        case ISOLATE_PAIR -> {
            // Run [polluter, victim] — if victim fails, dependency confirmed
            // (Only valid AFTER we know victim passes alone)
            TestPair pair = (TestPair) action.payload;
            return runAndCompare(List.of(pair.polluter(), pair.victim()), ctx);
        }
        case DDMIN_STEP -> {
            DdminState state = (DdminState) action.payload;
            return executeDdminStep(state, ctx);
        }
        case METHOD_REVERSE -> {
            String testClass = (String) action.payload;
            List<String> methods = ctx.state().methodsOf(testClass);
            List<String> reversed = new ArrayList<>(methods);
            Collections.reverse(reversed);
            return runMethodsAndCompare(testClass, reversed, ctx);
        }
        case METHOD_EXCLUDE -> {
            var payload = (MethodExclude) action.payload;
            List<String> methods = new ArrayList<>(ctx.state().methodsOf(payload.testClass()));
            methods.remove(payload.method());
            return runMethodsAndCompare(payload.testClass(), methods, ctx);
        }
    }
}

/**
 * Build order that places polluter immediately before victim while keeping
 * the rest of the suite in reference order (to avoid introducing other OD effects).
 */
List<String> buildConfirmationOrder(String polluter, String victim, DetectionContext ctx) {
    List<String> order = new ArrayList<>(ctx.referenceOrder());
    order.remove(polluter);
    order.remove(victim);
    // Place polluter→victim at the end (minimal interference from other tests)
    order.add(polluter);
    order.add(victim);
    return order;
}
```

### NOD (Non-Order-Dependent) Flaky Test Handling

A major practical concern: truly flaky tests (concurrency bugs, timing-dependent, resource leaks) produce false positives. The Combined algorithm handles this via the **isolate-first protocol**:

```
Discovery: victim fails in reordered run
     │
     ▼
ISOLATE_VICTIM: run victim alone
     │
     ├─ Fails alone → NOT OD. Mark as NOD. Suppress all future actions for this test.
     │
     └─ Passes alone → Confirmed OD candidate. Proceed to polluter isolation.
          │
          ▼
     ISOLATE_PAIR: run [suspect_polluter, victim]
          │
          ├─ Victim fails → CONFIRMED: polluter → victim dependency.
          │
          └─ Victim passes → This suspect is innocent. Try next candidate.
```

**Repeated-failure heuristic**: If a test fails in 3+ different orderings without being tested alone, it's likely NOD. The algorithm automatically schedules an isolation run and suppresses further action until confirmed.

### Strategy Adaptation

```java
/**
 * Adjust the exploration/exploitation balance based on recent yield.
 * Uses a "multi-armed bandit" intuition: actions that have been productive
 * for their category get boosted; unproductive categories get demoted.
 */
void adjustStrategy(TreeSet<Action> queue, int totalFound, int runCount, 
                    int runsWithoutFind) {
    // Stagnation detection: if 8+ runs without finding anything, pivot hard
    if (runsWithoutFind >= 8) {
        // Current approach isn't working — boost unexplored categories
        boostType(queue, ActionType.RANDOM_SHUFFLE, +0.25);
        boostType(queue, ActionType.EXCLUDE_TEST, +0.2);
        boostType(queue, ActionType.METHOD_REVERSE, +0.15);
        demoteType(queue, ActionType.CLUSTER_VIOLATE, -0.15);
    }
    
    // High-yield mode: if finding 1+ bug per 3 runs, double down on targeted actions
    double yieldRate = runCount > 0 ? (double) totalFound / runCount : 0;
    if (yieldRate > 0.33) {
        boostType(queue, ActionType.CONFIRM_SUSPECT, +0.1);
        boostType(queue, ActionType.ISOLATE_PAIR, +0.1);
    }
    
    // Late-stage: if we have many confirmed bugs but lots of time, add more random seeds
    if (totalFound > 5 && queue.stream().noneMatch(a -> a.type == ActionType.RANDOM_SHUFFLE)) {
        for (int i = 0; i < 3; i++) {
            queue.add(new Action(0.35, ActionType.RANDOM_SHUFFLE, 
                System.nanoTime() + i, "random:late:" + i));
        }
    }
}

/**
 * Boost/demote by rebuilding actions of a given type with adjusted priority.
 * TreeSet requires remove + re-add (not in-place mutation).
 */
void boostType(TreeSet<Action> queue, ActionType type, double delta) {
    List<Action> toAdjust = queue.stream()
        .filter(a -> a.type == type)
        .toList();
    queue.removeAll(toAdjust);
    for (Action a : toAdjust) {
        queue.add(new Action(
            Math.max(0.01, Math.min(0.99, a.priority + delta)),
            a.type, a.payload, a.dedupKey));
    }
}
```

### Information Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Shared Knowledge Base                           │
│                                                                     │
│  TestKnowledge per test:                                            │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │ passesAlone: true/false/null                                  │  │
│  │ confirmedPolluters: {X, Y}                                    │  │
│  │ eliminatedPolluters: {A, B, C}  (proven NOT polluters)        │  │
│  │ failureCount: 2                                               │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  Global state:                                                      │
│  ┌─────────────────┐  ┌───────────────┐  ┌───────────────────┐    │
│  │ confirmed: []   │  │ completedKeys │  │ runsWithoutFind   │    │
│  └─────────────────┘  └───────────────┘  └───────────────────┘    │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
                  ┌───────────────┼────────────────┐
                  ▼                                ▼
       ┌────────────────────┐          ┌────────────────────────┐
       │  Priority Queue     │          │ Redundancy Filter:     │
       │  (TreeSet<Action>)  │          │ skip if target test is │
       │                     │          │ NOD, or pair is        │
       │  Sorted by expected │          │ already eliminated     │
       │  information gain   │          └────────────────────────┘
       └──────────┬──────────┘
                  │ poll highest
                  ▼
       ┌────────────────────┐     ┌───────────────────────────────┐
       │   TestRunner        │────▶│ Outcome + compare to baseline │
       └────────────────────┘     └──────────────┬────────────────┘
                                                 │
                            ┌────────────────────┼───────────────────┐
                            ▼                    ▼                   ▼
                     New failure          No failure           ddmin result
                            │                    │                   │
                            ▼                    ▼                   ▼
                     Spawn follow-up      Update knowledge     Recurse or
                     (isolate/ddmin)      (eliminate edges)    confirm
```

### Key Design Decisions and Their Rationale

| Decision | Rationale |
|----------|-----------|
| **Isolate-first protocol** | Prevents cascading false positives from NOD flaky tests. Costs 1 extra run per victim but saves many wasted runs chasing ghosts. |
| **TreeSet instead of PriorityQueue** | Supports removal and priority adjustment. Java `PriorityQueue` doesn't support efficient `removeIf` or re-prioritization. |
| **`dedupKey` on Actions** | Generic deduplication — works for pairs, singles, clusters, methods. Avoids separate dedup logic per action type. |
| **TestKnowledge accumulation** | Eliminates redundant work: once we know test X passes alone, we never re-test that. Once polluter Y is eliminated for victim Z, we skip that pair. |
| **Proximity as a polluter signal** | State pollution is typically local — if test at position i pollutes, the effect is most likely seen at i+1, not i+100 (state may be cleaned by intervening tests). |
| **Early termination (10 bugs)** | Diminishing returns: finding the 11th bug adds less value than fixing the first 10. Shift to Phase D (Cleaners). |
| **PFAST seeded upfront (not contingent)** | BRITTLE bugs are invisible to reverse-order. Waiting for reverse to fail before trying PFAST misses an entire bug category. |
| **ddmin for predecessor narrowing** | When a test fails after a long sequence, there may be 50+ predecessors. Testing them one-by-one costs 50 runs. ddmin finds the minimal set in ~log₂(50) ≈ 6 runs. |

### Complexity (Revised)

| Metric | Value |
|--------|-------|
| Best case (history + conflict graph, OD exists) | 3–5 runs: reverse finds victim → isolate confirms → pair confirms |
| Typical (50 tests, 30 edges, 2 OD bugs) | 10–15 runs: reverse + 2 isolations + 2 pair confirms + clusters |
| Typical (200 tests, 200 edges) | 15–25 runs (includes ddmin chains) |
| Worst case (200 tests, no dep data, no OD) | 30+ random/PFAST runs until timeout |
| Overhead per action selection | O(log n) — negligible |
| Space | O(T²) worst case for knowledge map, typically O(T × avg_candidates) |

**Note**: Run counts are higher than previously claimed because each confirmed bug requires a minimum of 2 verification runs (isolate victim + isolate pair). This is the cost of avoiding false positives — a worthwhile trade-off for production reliability.

### Default Configuration

```java
record CombinedConfig(
    int timeBudgetSeconds,       // 1800 (30 min)
    int maxRuns,                 // unlimited (time-bounded)
    int initialRandomSeeds,      // 5 random shuffles seeded
    int maxPfastExclusions,      // 20 (top tests by shared-state count)
    int clusterSize,             // sqrt(edges), min 3
    boolean includeMethodLevel,  // true
    int parallelExecutors,       // 1 (set >1 for PFAST parallelization)
    int maxNodFailures,          // 3 — failures before treating as NOD
    int earlyTerminationBugs     // 10 — shift to Phase D after this many
) {
    static CombinedConfig DEFAULT = new CombinedConfig(
        1800, Integer.MAX_VALUE, 5, 20, -1, true, 1, 3, 10);
}
```

### Maven Invocation

```bash
# Default — Combined Adaptive runs automatically:
mvn test-order:detect-dependencies

# Equivalent explicit invocation:
mvn test-order:detect-dependencies -Dtestorder.od.algorithm=combined

# Tuning:
mvn test-order:detect-dependencies \
    -Dtestorder.od.timeBudget=60 \
    -Dtestorder.od.combined.maxPfast=50 \
    -Dtestorder.od.combined.parallel=4 \
    -Dtestorder.od.combined.earlyTermination=20

# For extremely flaky suites (high NOD rate):
mvn test-order:detect-dependencies \
    -Dtestorder.od.combined.nodThreshold=2 \    # stricter NOD filtering
    -Dtestorder.od.combined.requireIsolation=true  # always verify victim alone before confirming
```

### Graceful Degradation

| Available Data | Behavior |
|---------------|----------|
| FULL_MEMBER + history (ideal) | History suspects + conflict graph clusters + reverse + PFAST + method-level. Fastest convergence. |
| FULL + history | Class-level conflict graph + history suspects + reverse + PFAST. |
| FULL only (no history) | Conflict graph + reverse + random + PFAST + method-level. Slightly slower (no priors). |
| No dep map, has history | History suspects + reverse + PFAST + random. No graph-guided exploration. |
| Nothing (fresh project) | Reverse + random shuffles + PFAST. Still effective per DTDetector (~66% detection in one reverse run). |

---

## Algorithm Relationships: PolDet and ElectricTest

Two important tools from the literature are **not directly implementable** as detection algorithms in our system, but their insights inform our approach:

### PolDet (Gyori et al. 2015) — Proactive Pollution Detection

**Approach**: Capture full heap state before and after each test (via XStream serialization or JVMTI heap-walking). Compare using graph isomorphism. Report any test that modifies shared state as a "polluter."

**Why we can't directly use it**: Requires JVMTI heap snapshots or deep serialization — too expensive for production use (4× overhead per test). Our agent is lighter.

**What we borrow**: Our DependencyMap's `memberDependencies` (field-level tracking) is a **lightweight approximation** of PolDet's approach. Where PolDet captures the full heap graph, we capture which fields are accessed. Combined with Algorithm 1's edge weighting (static field naming heuristics), we approximate PolDet's pollution detection without the heap-walk cost.

### ElectricTest (Bell et al. 2015) — Single-Pass Dependency Detection

**Approach**: Instrument all classes. During ONE test run, use JVMTI heap tagging + garbage collection to track which objects were written by which test. When a later test reads a tagged object → dependency detected. Reports all data dependencies (not just manifest ones) in a single execution.

**Why we can't directly use it**: Requires JVMTI heap tagging and field access callbacks — specialized JVM integration beyond our agent's scope. 20× overhead.

**What we borrow**: Our agent's `FieldAccessCollector` is conceptually similar but coarser: it records field accesses per test class rather than per-object ownership. ElectricTest's key insight — "a single instrumented run can find all potential dependencies" — is exactly what our DependencyMap provides at the field-name level.

**Key difference**: ElectricTest distinguishes reads vs writes (detects true data dependencies). Our DependencyMap only records "accesses" (no R/W distinction). This is why we need Phase B (iterative refinement) to confirm which shared accesses are actually manifest dependencies.

---

### Phase C: Orchestrator (Multi-Algorithm Time-Budgeted Execution)

```java
class DetectDependenciesOperation {

    record Config(
        int timeBudgetSeconds,       // default 1800 (30 min)
        String algorithm,            // "auto" | algorithm name | comma-separated list
        boolean stopOnFirst,         // default false
        int clusterSize,             // for iterative-refinement: default sqrt(edges)
        int randomRounds,            // for random-reordering: default 100
        int boundedK,                // for dependence-aware-bounded: default 2
        long randomSeed,             // default 42
        boolean classifyResults      // default true
    ) {}

    // All registered algorithms
    static final List<DetectionAlgorithm> ALGORITHMS = List.of(
        new HistoryMiningAlgorithm(),           // Algorithm 6 (free)
        new ReverseOrderAlgorithm(),            // Algorithm 3 (1 run)
        new IterativeRefinementAlgorithm(),     // Algorithm 1 (targeted)
        new DependenceAwareBoundedAlgorithm(2), // Algorithm 4 (exhaustive k=2)
        new RandomReorderingAlgorithm(),        // Algorithm 2 (probabilistic)
        new TuscanSystematicAlgorithm()         // Algorithm 5 (systematic)
    );

    List<ODResult> run(Path testOrderDir, Config config) {
        // 1. Load data
        DependencyMap depMap = DependencyMap.load(testOrderDir.resolve("test-dependencies.lz4"));
        TestOrderState state = TestOrderState.load(testOrderDir.resolve("state.lz4"));
        
        // 2. Determine available prerequisites
        Set<Prerequisite> available = determinePrerequisites(depMap, state);
        log("Available prerequisites: %s", available);
        
        // 3. Select algorithms
        List<DetectionAlgorithm> selected = selectAlgorithms(config.algorithm, available);
        log("Selected algorithms: %s", selected.stream().map(DetectionAlgorithm::name).toList());
        
        // 4. Establish reference order (shared across algorithms)
        List<String> referenceOrder = state.lastPassingOrder()
            .orElse(depMap.testClasses().stream().sorted().toList());
        TestRunResult reference = runTests(referenceOrder);
        Set<String> passingTests = reference.passingTests();
        
        if (passingTests.isEmpty()) {
            log("No tests passing in reference run — cannot detect OD bugs.");
            return List.of();
        }
        
        // 5. Build shared context
        ConflictGraph graph = (available.contains(Prerequisite.DEPENDENCY_MAP))
            ? buildConflictGraph(depMap, state)
            : ConflictGraph.empty(referenceOrder);
        
        DetectionContext ctx = new DetectionContext(
            graph, depMap, state, referenceOrder, passingTests,
            new SurefireTestRunner(), 
            System.currentTimeMillis() + config.timeBudgetSeconds * 1000L,
            config.randomSeed
        );
        
        // 6. Execute algorithms in sequence, sharing time budget
        List<ODResult> allFound = new ArrayList<>();
        long totalDeadline = ctx.deadlineMillis();
        
        for (int i = 0; i < selected.size(); i++) {
            DetectionAlgorithm algo = selected.get(i);
            
            // Allocate time: proportional to estimated runs
            long remainingTime = totalDeadline - System.currentTimeMillis();
            if (remainingTime <= 0) break;
            
            int remainingAlgos = selected.size() - i;
            long algoDeadline = System.currentTimeMillis() + (remainingTime / remainingAlgos);
            
            DetectionContext algoCtx = ctx.withDeadline(algoDeadline);
            
            log("--- Running %s (budget: %d sec) ---", algo.name(), 
                (algoDeadline - System.currentTimeMillis()) / 1000);
            
            List<ODResult> results = algo.detect(algoCtx);
            allFound.addAll(results);
            
            log("%s found %d OD bugs", algo.name(), results.size());
            
            if (config.stopOnFirst && !allFound.isEmpty()) break;
        }
        
        // 7. Deduplicate and report
        List<ODResult> deduplicated = deduplicateResults(allFound);
        log("Done: %d unique OD bugs found by %d algorithms", 
            deduplicated.size(), selected.size());
        
        return deduplicated;
    }
    
    /**
     * Algorithm selection strategy:
     * - "auto": choose based on available data + time budget
     * - specific name: run only that algorithm
     * - comma-separated: run those in order
     */
    List<DetectionAlgorithm> selectAlgorithms(String spec, Set<Prerequisite> available) {
        if (spec.equals("auto")) {
            return autoSelect(available);
        }
        
        List<String> names = Arrays.asList(spec.split(","));
        return ALGORITHMS.stream()
            .filter(a -> names.contains(a.name()))
            .filter(a -> available.containsAll(a.prerequisites()))
            .toList();
    }
    
    /**
     * Auto-selection logic (recommended default):
     * 
     * Always: history-mining (free) → reverse-order (1 run)
     * 
     * Then based on data:
     *   MEMBER_DEPS available → iterative-refinement (most precise)
     *   DEPENDENCY_MAP only  → dependence-aware-bounded (exhaustive k=2)
     *   Nothing available    → random-reordering (always works)
     * 
     * If time remains and < 100 tests: tuscan-systematic (guaranteed coverage)
     * If parallel executors available: pfast-exclusion (BRITTLE detection)
     */
    List<DetectionAlgorithm> autoSelect(Set<Prerequisite> available, 
                                         DetectionConfig config) {
        List<DetectionAlgorithm> plan = new ArrayList<>();
        
        // Phase 1: Free/cheap (always run)
        if (available.contains(Prerequisite.MULTIPLE_RUNS)) {
            plan.add(findAlgo("history-mining"));
        }
        if (available.contains(Prerequisite.PASSING_REFERENCE)) {
            plan.add(findAlgo("reverse-order"));
        }
        
        // Phase 2: Primary algorithm (pick the best available)
        if (available.contains(Prerequisite.MEMBER_DEPS)) {
            plan.add(findAlgo("iterative-refinement"));
        } else if (available.contains(Prerequisite.DEPENDENCY_MAP)) {
            plan.add(findAlgo("dependence-aware-bounded-k2"));
        } else {
            plan.add(findAlgo("random-reordering"));
        }
        
        // Phase 3: BRITTLE detection (if parallel executors available)
        if (config.parallelExecutors() > 1 && 
            available.contains(Prerequisite.PASSING_REFERENCE)) {
            plan.add(findAlgo("pfast-exclusion"));
        }
        
        // Phase 4: Fill remaining time
        if (available.contains(Prerequisite.PASSING_REFERENCE)) {
            plan.add(findAlgo("random-reordering"));  // probabilistic backup
        }
        
        return plan;
    }
    
    Set<Prerequisite> determinePrerequisites(DependencyMap depMap, TestOrderState state) {
        Set<Prerequisite> avail = EnumSet.noneOf(Prerequisite.class);
        if (depMap != null) {
            avail.add(Prerequisite.DEPENDENCY_MAP);
            if (depMap.hasMemberDeps()) avail.add(Prerequisite.MEMBER_DEPS);
        }
        if (state != null) {
            if (!state.runs().isEmpty()) {
                avail.add(Prerequisite.RUN_HISTORY);
                avail.add(Prerequisite.PASSING_REFERENCE);
            }
            if (state.runs().size() >= 3) avail.add(Prerequisite.MULTIPLE_RUNS);
        }
        return avail;
    }
}
```

### Auto-Selection Decision Tree

```
                    ┌─ Has MULTIPLE_RUNS? ──→ history-mining (free pre-pass)
                    │
Start ──→ Always ───┼─ Has PASSING_REFERENCE? ──→ reverse-order (1 run)
                    │
                    └─ Pick Primary:
                         │
                         ├─ Has MEMBER_DEPS? ──→ iterative-refinement
                         │                       (best precision, uses conflict graph)
                         │
                         ├─ Has DEPENDENCY_MAP? ──→ dependence-aware-bounded
                         │                         (exhaustive k=2, pruned by shared fields)
                         │
                         └─ Nothing? ──→ random-reordering
                                         (always works, probabilistic)
                    
                    Then if time remains:
                         ├─ < 100 tests? ──→ tuscan-systematic (guaranteed pair coverage)
                         └─ Otherwise ──→ more random rounds
```

---

## Phase D: Post-Detection Actions

After Phase C identifies Polluter→Victim and Setter→Brittle pairs, Phase D provides actionable remediation: finding Cleaner tests, synthesizing repairs, and feeding constraints back into the test ordering system.

### D.1: Cleaner Search

A **Cleaner** is a test that, when placed between a Polluter and its Victim, neutralizes the pollution and allows the Victim to pass. Cleaners already exist in the suite — they reset the shared state as a side effect of their own logic.

```java
record CleanerResult(String polluter, String victim, String cleaner,
                     Set<String> sharedState) {}

List<CleanerResult> findCleaners(ODResult odResult, DetectionContext ctx) {
    List<CleanerResult> cleaners = new ArrayList<>();
    
    String polluter = odResult.polluter();
    String victim = odResult.victim();
    
    // Confirm: [polluter, victim] fails
    TestRunResult failRun = runTests(List.of(polluter, victim));
    if (!failRun.failed(victim)) return cleaners;  // sanity check
    
    // Candidate cleaners: tests that access the same shared state
    Set<String> sharedFields = sharedFields(polluter, victim, ctx.depMap());
    List<String> candidates = ctx.allTests().stream()
        .filter(t -> !t.equals(polluter) && !t.equals(victim))
        .filter(t -> accessesAny(t, sharedFields, ctx.depMap()))
        .sorted(Comparator.comparingInt(t -> 
            -sharedFieldCount(t, sharedFields, ctx.depMap())))  // most overlap first
        .toList();
    
    // Try each candidate: [polluter, candidate, victim] — if victim passes, it's a cleaner
    for (String candidate : candidates) {
        if (ctx.timeBudgetExhausted()) break;
        
        TestRunResult result = runTests(List.of(polluter, candidate, victim));
        if (result.passed(victim)) {
            cleaners.add(new CleanerResult(polluter, victim, candidate, sharedFields));
        }
    }
    
    return cleaners;
}
```

### D.2: Repair Suggestions

When a Cleaner is found, analyze what it does that fixes the state:

```java
record RepairSuggestion(String target, RepairType type, String description,
                        String codeHint) {}

enum RepairType { ADD_BEFORE_TO_VICTIM, ADD_AFTER_TO_POLLUTER, ADD_BEFORE_CLASS }

List<RepairSuggestion> suggestRepairs(CleanerResult cleaner, DetectionContext ctx) {
    List<RepairSuggestion> suggestions = new ArrayList<>();
    
    // Analyze cleaner's @Before/@After methods for state reset patterns
    Set<String> cleanerWrites = ctx.depMap().getWrittenFields(cleaner.cleaner());
    Set<String> overlap = new HashSet<>(cleanerWrites);
    overlap.retainAll(cleaner.sharedState());
    
    if (!overlap.isEmpty()) {
        suggestions.add(new RepairSuggestion(
            cleaner.victim(), RepairType.ADD_BEFORE_TO_VICTIM,
            "Add @BeforeEach that resets: " + overlap,
            "// Reset shared state (extracted from " + cleaner.cleaner() + ")\n" +
            overlap.stream()
                .map(f -> f.split("#")[0] + "." + f.split("#")[1] + " = <initial>;")
                .collect(joining("\n"))
        ));
        
        suggestions.add(new RepairSuggestion(
            cleaner.polluter(), RepairType.ADD_AFTER_TO_POLLUTER,
            "Add @AfterEach to " + cleaner.polluter() + " that cleans: " + overlap,
            "// Prevent pollution of shared state\n@AfterEach void cleanUp() { ... }"
        ));
    }
    
    return suggestions;
}
```

### D.3: enhanceOrder — Feed Dependencies Back into PriorityClassOrderer / PriorityMethodOrderer

The most immediate value of detected dependencies: **automatically constrain future test ordering** so OD tests don't cause failures again. This feeds into the existing `PriorityClassOrderer` (inter-class) and `PriorityMethodOrderer` (intra-class) without requiring code fixes.

```java
record OrderConstraint(TestId before, TestId after, ConstraintType type,
                       double confidence) {
    /** True if both sides are method-level within the same class */
    boolean isIntraClass() {
        return before.className().equals(after.className()) 
            && before.methodName() != null && after.methodName() != null;
    }
    
    /** Which orderer should enforce this constraint */
    TestGranularity targetOrderer() {
        return isIntraClass() ? TestGranularity.METHOD : TestGranularity.CLASS;
    }
}

enum ConstraintType {
    MUST_PRECEDE,      // 'before' must always run before 'after' (BRITTLE)
    MUST_NOT_PRECEDE   // 'before' must NOT run before 'after' (VICTIM/POLLUTER)
}

/**
 * Convert detection results into ordering constraints.
 * Stored in TestOrderState for use by PriorityClassOrderer / PriorityMethodOrderer.
 */
List<OrderConstraint> toConstraints(List<ODResult> detectionResults) {
    List<OrderConstraint> constraints = new ArrayList<>();
    
    for (ODResult od : detectionResults) {
        switch (od.type()) {
            case VICTIM -> {
                // Polluter must NOT precede victim (or insert cleaner between)
                constraints.add(new OrderConstraint(
                    od.polluter(), od.victim(), ConstraintType.MUST_NOT_PRECEDE,
                    od.confidence()));
            }
            case BRITTLE -> {
                // State-setter MUST precede the brittle test
                constraints.add(new OrderConstraint(
                    od.stateSetter(), od.victim(), ConstraintType.MUST_PRECEDE,
                    od.confidence()));
            }
        }
    }
    return constraints;
}

/**
 * Integration point in PriorityClassOrderer / PriorityMethodOrderer:
 * After computing priority scores, apply hard constraints.
 * Class-level constraints go to ClassOrderer; method-level to MethodOrderer.
 */
List<String> applyConstraints(List<String> priorityOrder, 
                              List<OrderConstraint> constraints,
                              TestGranularity granularity) {
    // Topological sort with constraint satisfaction
    // MUST_PRECEDE → add directed edge (before → after)
    // MUST_NOT_PRECEDE → add directed edge (after → before) 
    //                     [i.e., victim runs first, or they're separated by cleaner]
    
    DirectedGraph<String> constraintGraph = new DirectedGraph<>();
    for (OrderConstraint c : constraints) {
        if (c.confidence() < 0.7) continue;  // Only apply high-confidence constraints
        
        switch (c.type()) {
            case MUST_PRECEDE -> constraintGraph.addEdge(c.before(), c.after());
            case MUST_NOT_PRECEDE -> constraintGraph.addEdge(c.after(), c.before());
        }
    }
    
    // Check for cycles (conflicting constraints) 
    if (constraintGraph.hasCycle()) {
        // Log warning, remove lowest-confidence edges until acyclic
        constraintGraph.removeWeakestUntilAcyclic(constraints);
    }
    
    // Merge: respect constraints while preserving priority order as much as possible
    return topologicalSort(priorityOrder, constraintGraph);
}
```

### D.4: Output Report

Phase D produces a structured report persisted alongside TestOrderState:

```java
record DetectionReport(
    LocalDateTime timestamp,
    Duration totalTime,
    int totalRuns,
    List<ODResult> confirmedDependencies,
    List<CleanerResult> cleanersFound,
    List<RepairSuggestion> repairs,
    List<OrderConstraint> constraintsGenerated,
    Map<String, String> algorithmStats  // per-algorithm timing and yield
) {
    void persist(Path stateDir) {
        // Written to .test-order/detection-report.json
        // Also updates TestOrderState with new constraints
    }
}
```

---

## Optimization: Edge Weight Boosting from Run History

The History Mining algorithm (Algorithm 6) operates standalone, but its outputs also **boost edge weights** for Algorithm 1 (Iterative Refinement). Tests identified as intermittent get a +0.3 weight bonus on their conflict edges:

```java
// Tests that have failed intermittently in different runs are OD candidates
List<String> intermittentTests = state.runs().stream()
    .flatMap(run -> run.outcomes().stream())
    .collect(groupingBy(TestOutcome::testClass, 
                        mapping(TestOutcome::failed, toList())))
    .entrySet().stream()
    .filter(e -> e.getValue().contains(true) && e.getValue().contains(false))
    .map(Map.Entry::getKey)
    .toList();

// Boost edges involving intermittent tests (Flakinator's Bayesian prior concept)
for (ConflictEdge edge : conflictGraph.edges) {
    if (intermittentTests.contains(edge.testA) || intermittentTests.contains(edge.testB)) {
        edge.weight = Math.min(1.0, edge.weight + 0.3);
    }
}
```

---

## Fallback: No Member Data (FULL mode only)

When only class-level deps are available (FULL mode, no `memberDependencies`):

1. **Weaker signal**: Two tests touching the same *class* is much less specific than same *field*
2. **Higher false positive rate**: many tests touch common utility classes without conflict
3. **Mitigation**: Apply stronger filters:
   - Skip edges where the shared class has no mutable static fields (via bytecode analysis at build time)
   - Require ≥3 shared classes for an edge to qualify
   - Boost edges where shared class is in the same package as the tests (locality heuristic)
   - Use historical intermittency signal more aggressively as a pre-filter

Recommendation: For OD detection, **recommend FULL_MEMBER mode** in the Maven/Gradle plugin configuration. The 121% overhead (vs 66% for FULL) is acceptable for a one-time `learn` phase since it gives 10-50× better edge precision for detect-dependencies.

---

## Complexity Analysis (Per Algorithm)

| Algorithm | Runs (50 tests, 30 edges) | Runs (200 tests, 200 edges) | Wall-clock (200 tests) |
|-----------|--------------------------|----------------------------|----------------------|
| 1. Iterative Refinement (cluster=5) | 1 + 30/5 = **7** | 1 + 200/14 = **15** | ~30 min |
| 2. Random (100 rounds) | **100** | **100** | ~3.3 hours |
| 3. Reverse Order | **1** | **1** | ~2 min |
| 4. Dep-Aware Bounded (k=2) | 50 + ~60 pairs = **~110** | 200 + ~400 pairs = **~600** | hours (only for small suites) |
| 5. Tuscan Systematic | **50** | **200** | ~6.7 hours |
| 6. History Mining | **0** | **0** | instant |
| 7. PFAST Single-Exclusion | **50** | **200** | ~6.7 hours (parallelizable to ~1.7h @ 4x) |
| **8. Combined Adaptive (default)** | **10–15** | **15–25** | **~30–50 min** |
| ~~Auto (sequential)~~ | 0+1+7 = ~8 | 0+1+15 = ~16 | ~32 min |

The Combined Adaptive algorithm (8) is the **new default**. Run counts are slightly higher than the sequential strategy because of the **isolate-first verification protocol** (2 runs per confirmed bug to eliminate NOD false positives), but detection confidence is significantly higher. The sequential orchestrator remains available for explicit algorithm chaining.

---

## Integration with Existing test-order Infrastructure

### What to Reuse (no new code needed)

| Component | Purpose | Location |
|-----------|---------|----------|
| `DependencyMap.load()` | Read pre-built dependency index (class + method level) | test-order-core |
| `TestOrderState.load()` | Read run history + failure scores (class + method level) | test-order-core |
| `PriorityClassOrderer` | Apply custom class order via JUnit ClassOrderer SPI | test-order-junit |
| `PriorityMethodOrderer` | Apply custom method order via JUnit MethodOrderer SPI | test-order-junit |
| Surefire fork mechanism | Execute tests in subprocess | test-order-maven-plugin |
| `FileBasedClassOrderer` | Write order file → JUnit reads it | test-order-core |

### What to Build (new)

| Component | Location | Lines (est.) |
|-----------|----------|-------------|
| `DetectionAlgorithm` interface | test-order-core | ~30 |
| `ConflictGraphBuilder` | test-order-core | ~150 |
| `EdgeWeightCalculator` | test-order-core | ~80 |
| `IterativeRefinementAlgorithm` | test-order-core | ~200 |
| `RandomReorderingAlgorithm` | test-order-core | ~80 |
| `ReverseOrderAlgorithm` | test-order-core | ~40 |
| `DependenceAwareBoundedAlgorithm` | test-order-core | ~120 |
| `TuscanSystematicAlgorithm` | test-order-core | ~100 |
| `HistoryMiningAlgorithm` (with RankFO) | test-order-core | ~150 |
| `PFASTAlgorithm` | test-order-core | ~100 |
| `CombinedAdaptiveAlgorithm` (default) | test-order-core | ~350 |
| `DeltaDebugging` (ddmin utility) | test-order-core | ~80 |
| `CleanerSearch` | test-order-core | ~120 |
| `OrderConstraintManager` | test-order-core | ~100 |
| `DetectDependenciesOperation` (orchestrator) | test-order-core/ops | ~250 |
| `DetectDependenciesMojo` | test-order-maven-plugin | ~100 |
| Report generator (JSON + MD) | test-order-core | ~120 |
| **Total** | | **~2,170 lines** |

### Maven Plugin Invocation

```bash
# Standard usage — Combined Adaptive runs by default:
mvn test-order:learn -Dtestorder.instrumentation.mode=FULL_MEMBER
mvn test-order:detect-dependencies

# Options:
mvn test-order:detect-dependencies \
    -Dtestorder.od.timeBudget=30 \
    -Dtestorder.od.algorithm=combined \
    -Dtestorder.od.stopOnFirst=false

# Specific algorithm:
mvn test-order:detect-dependencies -Dtestorder.od.algorithm=random-reordering
mvn test-order:detect-dependencies -Dtestorder.od.algorithm=reverse-order
mvn test-order:detect-dependencies -Dtestorder.od.algorithm=pfast-exclusion

# Chain specific algorithms:
mvn test-order:detect-dependencies \
    -Dtestorder.od.algorithm=history-mining,reverse-order,iterative-refinement

# DTDetector-style exhaustive (small suites only):
mvn test-order:detect-dependencies \
    -Dtestorder.od.algorithm=dependence-aware-bounded \
    -Dtestorder.od.boundedK=2

# Run detection + Cleaner search + auto-constrain ordering:
mvn test-order:detect-dependencies \
    -Dtestorder.od.findCleaners=true \
    -Dtestorder.od.applyConstraints=true

# Parallel PFAST (specify executor count):
mvn test-order:detect-dependencies \
    -Dtestorder.od.algorithm=pfast-exclusion \
    -Dtestorder.od.parallel=4

# Method-level detection (intra-class OD):
mvn test-order:detect-dependencies \
    -Dtestorder.od.granularity=both          # default: both class + method
mvn test-order:detect-dependencies \
    -Dtestorder.od.granularity=class         # class-level only (faster)
mvn test-order:detect-dependencies \
    -Dtestorder.od.granularity=method        # method-level only
```

---

## Comparison with Prior Tools

| Aspect | PRADET | DTDetector | PolDet | ElectricTest | Our System |
|--------|--------|-----------|--------|-------------|------------|
| Year | 2018 | 2014 | 2015 | 2015 | 2026 |
| Detection type | Manifest (targeted) | Manifest (reordering) | Pollution (proactive) | Data deps (all) | Manifest (multi-algo) |
| Phase A cost | 1 instrumented run | 0 (no static analysis) | 1 run with heap snapshots | 1 instrumented run | **0** (reuses DependencyMap) |
| R/W distinction | Yes (Phosphor) | Coarse (static fields only) | Yes (heap diffing) | Yes (JVMTI tagging) | No — all accesses as candidates |
| Granularity | Java object (heap) | Static field (class-level) | Heap graph (full) | Heap object (tagged) | Field name (`class#field`) |
| Algorithms | 1 (iterative refine) | 4 (reverse, random, exhaustive, aware) | 1 (state diff) | 1 (single-pass tracking) | **6** (all of the above + history) |
| Runs needed (50 tests) | ~31 | 1-1000+ (varies by algo) | 1 (but 4× overhead) | 1 (but 20× overhead) | **~7-20** (algorithm-dependent) |
| Produces witnesses | Yes (edge pair) | Yes (k-permutation) | Yes (access path) | Yes (stack trace) | Yes (pair + shared fields) |
| Infrastructure | Phosphor JVM agent | Fresh JVM per permutation | XStream + JVMTI | JVMTI heap-walking | Standard Maven/Surefire |
| Scalability | Medium (~200 tests) | Small (≤50 for bounded) | Medium | Large (5000+ tests) | Large (via algorithm selection) |
| False positives | Low | Zero (sound) | Medium (not all pollution manifests) | High (data deps ≠ manifest) | Low-medium (varies by algo) |

### Algorithm Comparison Matrix

| Algorithm | Runs Needed | Prerequisites | Completeness | Precision | Best For |
|-----------|-------------|---------------|--------------|-----------|----------|
| 1. Iterative Refinement | ~E/C + log₂C | DEPENDENCY_MAP + PASSING_REF | High (for shared-field pairs) | High | Medium suites with rich dep data |
| 2. Random Reordering | N (default 100) | PASSING_REF only | Probabilistic (~95% at 1000 runs) | High (manifest only) | First pass, large suites |
| 3. Reverse Order | 1 | PASSING_REF only | Low | High (manifest only) | CI smoke test, quick check |
| 4. Dep-Aware Bounded | O(E) pairs | DEPENDENCY_MAP + PASSING_REF | Complete for k=2 pairs | High | Small suites, minimal witnesses |
| 5. Tuscan Systematic | n permutations | None | Guaranteed all adjacent pairs | High | Thorough, medium suites |
| 6. History Mining | **0** | MULTIPLE_RUNS | Low (correlational) | Medium | Free pre-pass, monitoring |

**Net**: By combining algorithms, we achieve better coverage than any single prior tool. History Mining + Reverse Order costs only 1 run. Adding Iterative Refinement brings precision comparable to PRADET at zero Phase A cost. The auto-selector adapts to available data, from bare-minimum (random only) to full-precision (PRADET-style with member deps).

---

## Key Research Insights Applied

1. **PRADET's two-phase architecture** → Algorithm 1 (static graph + iterative refinement)
2. **PRADET's "96% exist at test-write time"** → trigger detect-deps only when new tests added
3. **DTDetector's 4 algorithms** → Algorithms 2, 3, 4 directly derived from Zhang et al.
4. **DTDetector's "randomized detects most"** → Random Reordering as default fallback
5. **DTDetector's dependence-aware pruning** → Algorithm 4 uses DependencyMap as the shared-field filter (free, no runtime instrumentation)
6. **iDFlakies' RandomC+M** → hierarchical shuffling (classes first, then methods) in Algorithm 2
7. **iDFlakies' classification protocol** → truncated-order rerun to distinguish OD vs NOD
8. **PolDet's heap diffing** → our agent's field tracking approximates this at lower cost
9. **ElectricTest's single-pass insight** → our DependencyMap is the pre-built equivalent (field-level rather than object-level)
10. **Tuscan's "3.3 minimal orders"** → Algorithm 5 for guaranteed pair coverage
11. **Tuscan's 97.2% detection rate** → mathematical coverage beats probabilistic for medium suites
12. **DependTest's "static fields cover 80-85%"** → focus on `memberDependencies` containing fields
13. **FlaKat's ML pre-screening (F1=0.90)** → edge weighting function acts as static pre-filter
14. **Flakinator's Bayesian approach** → historical failure data as prior (Algorithm 6 + edge weight boosting)
15. **Flakinator's quarantine** → output includes quarantine recommendation per found OD test
16. **Zeller's ddmin** → B.4 uses full delta debugging for 1-minimal isolation (handles co-polluters)
17. **PFAST single-exclusion** → Algorithm 7 finds BRITTLE tests by systematic removal
18. **RankFO differential scoring** → Algorithm 6 ranks polluter candidates by (fail_rate - pass_rate) correlation
19. **Cleaner search** → Phase D finds neutralizing tests and synthesizes repair suggestions
20. **Constraint feedback loop** → Phase D feeds MUST_PRECEDE/MUST_NOT_PRECEDE back to PriorityClassOrderer and PriorityMethodOrderer
21. **Dual-granularity detection** → class-level (inter-class) and method-level (intra-class) OD in a single two-pass run, using existing DependencyMap method-level data
22. **Combined Adaptive (default)** → priority-queue–driven single loop that dynamically selects the highest-value action per run, shares state across techniques, and adapts exploration/exploitation based on yield rate

---

## Bibliography

| # | Paper | Year | Key Contribution Used |
|---|-------|------|----------------------|
| [1] | Li et al., "Systematically Detecting OD Flaky Tests" (ISSTA) | 2023 | Tuscan square construction, 3.3 minimal orders |
| [2] | Lam et al., "iDFlakies" (ICST) | 2019 | RandomC+M config, OD/NOD classification protocol |
| [3] | Shi et al., "DependTest" (ISSTA) | 2020 | Static field coverage statistic (80-85%) |
| [4] | Biagiola et al., "Web Test Dependencies" (ESEC/FSE) | 2019 | PRAW patterns, cross-test filtering |
| [5] | Gruber et al., "JS-TOD" (ISSTA) | 2021 | Event-driven OD patterns |
| [6] | Wei et al., "Tuscan class-level" (TACAS) | 2021 | Tuscan square mathematical construction |
| [7] | Lin, "FlaKat" (Waterloo) | 2023 | ML pre-screening with code features |
| [8] | Gambi/Bell/Zeller, "PRADET" (ASE) | 2018 | Two-phase architecture, cluster batching |
| [9] | Palanisamy, "AI framework" (IJCA) | 2025 | Multi-signal integration concept |
| [10] | Malik, "Flakinator" (Atlassian Blog) | 2025 | Bayesian priors from history, quarantine |
| [11] | Zhang et al., "DTDetector" (ISSTA) | 2014 | 4 detection algorithms, dependence-aware pruning, NP-completeness proof |
| [12] | Gyori et al., "PolDet" (ISSTA) | 2015 | Heap-state diffing, access-path reporting, proactive detection |
| [13] | Bell et al., "ElectricTest" (ESEC/FSE) | 2015 | Single-pass data dependency detection via JVMTI, 20× overhead |
| [14] | Zeller & Hildebrandt, "Simplifying and Isolating Failure-Inducing Input" (TSE) | 2002 | ddmin algorithm for 1-minimal isolation |
| [15] | Lam et al., "PFAST" (ICSE) | 2020 | Single-exclusion dependency detection, parallelizable |
| [16] | Shi et al., "iFixFlakies" (ESEC/FSE) | 2019 | Cleaner search, automated repair of OD tests |
