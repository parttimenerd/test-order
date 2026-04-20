# Plan: Structural Diff → Impact-Aware Test Scoring

## Core Idea

Currently, change detection answers: **"which classes changed?"**  
StructuralDiff can answer: **"what exactly changed inside those classes?"**

Combine this with the dependency index (which already tracks which test methods use which classes) to compute a **fine-grained impact score**: tests that exercise the specific methods/fields that changed rank higher than tests that merely touch the same class.

---

## Current State

### What we have

| Component | Granularity | Data |
|-----------|------------|------|
| `ChangeDetector` | class-level | `Set<String>` of changed FQCNs |
| `StructuralDiff` | member-level | `List<Change>` with kind/category/fqcn/name |
| `DependencyMap` (V2) | class→class | test class → set of dependency classes |
| `DependencyMap` (V3) | method→class | `testClass#testMethod` → set of dependency classes |
| `MethodHashStore` | method-level | `className#methodName` → hash (test source only) |

### What's missing

The agent records which **classes** each test uses, but not which **members** (methods/fields) within those classes. So the dependency map says "testFoo uses class Bar" but not "testFoo calls Bar.calculate()".

This means we can't do a perfect join of "Bar.calculate() changed" → "testFoo calls Bar.calculate()". But we can still do **much better than class-level** by combining structural diff information with what we already have.

---

## Integration Strategy: Three Tiers

### Tier 1: Structural Change Weighting (no agent changes, high value)

**Insight:** Not all class changes are equal. A method body change is more impactful than a comment change, and adding a new public method is more impactful than renaming a private field.

**How it works:**

1. When computing scores, run `StructuralDiff` on each changed class
2. Assign an **impact weight** to each change:

```
Change Impact Weights (configurable):
  METHOD body changed     → 1.0  (likely affects behavior)
  METHOD added            → 0.8  (new code path, may be called)
  METHOD removed          → 0.9  (callers will break)
  METHOD signature changed→ 1.0  (callers likely break)
  FIELD added/removed     → 0.6  (structural change)
  FIELD modified          → 0.7  (type/init change)
  TYPE added              → 0.5  (new, no existing callers)
  TYPE removed            → 1.0  (all users break)
  TYPE signature changed  → 0.9  (subclass/impl breaks)
  INITIALIZER changed     → 0.4  (startup behavior)
```

3. Compute a **per-class impact score** = max(change weights) or sum, capped at 1.0
4. Use this to **scale** the existing `depOverlap` score:

```java
// Current:
depOverlapScore = ceil(overlapCount / totalDeps * weight)

// Enhanced:
classImpact = structuralImpact(changedClass)  // 0.0–1.0
depOverlapScore = ceil(overlapCount / totalDeps * weight * classImpact)
```

**Where to plug in:**
- `TestScorer.score()` already iterates over `changedClasses`
- Add a `Map<String, Double> classImpactScores` parameter (precomputed)
- Weight each overlapping class by its impact score instead of counting all equally

**Cost:** Low. Only requires calling `StructuralDiff.diffAgainstGit()` for each changed `.java` file during the change detection phase, then passing the impact map to `TestScorer`.

---

### Tier 2: Method-Level Dependency Matching (uses V3 index, medium effort)

**Insight:** The V3 index stores `testClass#testMethod → Set<depClass>`. While this is still class-level on the dep side, combining it with structural diff tells us:

- "testFoo calls classes {A, B, C}"
- "Only class B had a method body change; A and C only had comment changes"
- → testFoo's score gets boosted proportional to B's impact, not A's or C's

**How it works:**

For each test **method** (not just class):

```java
Set<String> testMethodDeps = depMap.getMethodDeps(testClass, testMethod);
double methodImpact = 0;
for (String dep : testMethodDeps) {
    if (classImpactScores.containsKey(dep)) {
        methodImpact = Math.max(methodImpact, classImpactScores.get(dep));
    }
}
```

This lets `PriorityMethodOrderer` order test methods within a class: test methods that touch high-impact changed classes run first.

**Where to plug in:**
- `PriorityMethodOrderer` already supports `changedMethods` (from `MethodHashStore`)
- Add structural impact scores as an additional signal in `MethodScorer`

---

### Tier 3: Member-Level Dependency Tracking (agent changes, highest precision)

**Insight:** To go from "testFoo uses class Bar" to "testFoo calls Bar.calculate()", the agent needs to record member-level usage.

**How it works:**

Extend the agent's instrumentation to record not just the declaring class but the specific method/field accessed:

```java
// Current (CLASS-level): recordUsage("com.example.Bar")
// Enhanced (MEMBER-level): recordUsage("com.example.Bar#calculate")
```

This creates a **member-level dependency index**:
```
testClass#testMethod → {Bar#calculate, Bar#name, Baz#process}
```

Now the join with StructuralDiff is precise:
```
StructuralDiff says: Bar#calculate body changed
Index says:          testGetPrice uses Bar#calculate
→ testGetPrice gets maximum impact boost
```

**Agent changes needed:**

1. **METHOD_ENTRY instrumentation**: Already records declaring class. Change `recordUsage(className)` to `recordUsage(className + "#" + methodName)` in method entry instrumentation.

2. **Field access instrumentation** (FULL mode): Already instruments field accesses. Change to record `className + "#" + fieldName`.

3. **UsageStore**: Change `Set<String>` dependencies to distinguish class vs member keys. Or use a separator convention (`#` already used for method keys in V3).

4. **DependencyMap V4**: New format storing member-level deps. Backward compatible — V4 includes V3 data plus member-level detail.

5. **Index size**: Member-level deps are ~5–10× larger than class-level. RoaringBitmap compression helps, but the trie entries grow. May need to cap at "classes + their public methods" to keep size reasonable.

**Cost:** High. Touches agent, storage format, and scoring. But delivers the most precise impact analysis.

---

## Implementation Roadmap

### Phase 1: StructuralChangeAnalyzer (Tier 1)

New class bridging StructuralDiff → scoring:

```java
package me.bechberger.testorder.changes;

public class StructuralChangeAnalyzer {

    public record ClassImpact(String fqcn, double impact, List<Change> changes) {}

    /** For each changed file, compute structural impact score */
    public static Map<String, ClassImpact> analyzeImpact(
            Path projectRoot, Set<String> changedClasses,
            Path sourceRoot, String gitRef) {
        // 1. For each changed class, find its .java file
        // 2. Run StructuralDiff.diffAgainstGit()
        // 3. Compute impact score from changes
        // 4. Return map: FQCN → ClassImpact
    }

    /** Compute impact score from list of changes */
    public static double computeImpact(List<Change> changes) {
        // Max of individual change weights, or weighted sum capped at 1.0
    }
}
```

**Integration into TestScorer:**

```java
// In TestScorer constructor or score() method:
public ScoreResult score(String testClassName, Map<String, Double> classImpacts) {
    // When computing depOverlap, weight each dep by its impact:
    double weightedOverlap = 0;
    for (String dep : depMap.get(testClassName)) {
        if (classImpacts.containsKey(dep)) {
            weightedOverlap += classImpacts.get(dep);
        }
    }
    // Scale depOverlapScore by weighted overlap instead of raw count
}
```

**Files to modify:**
- New: `StructuralChangeAnalyzer.java`
- Modify: `TestScorer.java` (add impact-weighted overlap)
- Modify: `PriorityClassOrderer.java` (compute impacts, pass to scorer)
- Modify: `AbstractTestOrderMojo.java` (flow impact data through Maven plugin)

---

### Phase 2: Method-Level Impact Scoring (Tier 2)

Extend `PriorityMethodOrderer` to use structural impacts when V3 method deps are available:

```java
// In MethodScorer:
double methodDepOverlap(String testClass, String testMethod,
                        Map<String, Double> classImpacts) {
    Set<String> deps = depMap.getMethodDeps(testClass, testMethod);
    double impact = 0;
    for (String dep : deps) {
        impact = Math.max(impact, classImpacts.getOrDefault(dep, 0.0));
    }
    return impact;
}
```

**Files to modify:**
- Modify: `MethodScorer.java`
- Modify: `PriorityMethodOrderer.java`

---

### Phase 3: CLI Diagnostics

Add a `test-order impact` CLI command that shows the full impact analysis:

```
$ test-order impact test-dependencies.lz4 -m uncommitted

Changed classes (structural analysis):
  com.example.OrderService
    ├── METHOD calculateTotal(): body changed (impact: 1.0)
    └── FIELD discount: type changed (impact: 0.7)
  com.example.UserRepository
    └── METHOD findByEmail(): added (impact: 0.8)

Most affected test classes:
  1. OrderServiceTest        (score: 18, dep overlap: 5/12, impact: 1.0)
     ├── testCalculateTotal   (impact: 1.0 — uses OrderService)
     └── testApplyDiscount    (impact: 0.7 — uses OrderService)
  2. CheckoutFlowTest        (score: 14, dep overlap: 3/8, impact: 0.8)
     └── testCheckout         (impact: 1.0 — uses OrderService)
  3. UserServiceTest          (score: 12, dep overlap: 2/6, impact: 0.8)
     └── testFindUser         (impact: 0.8 — uses UserRepository)
```

**Files to modify:**
- New: `Tool.Impact` subcommand in `Tool.java`
- Uses: `StructuralChangeAnalyzer` + `TestScorer` + `DependencyMap`

---

### Phase 4: Member-Level Agent (Tier 3, future)

1. Add `FULL_MEMBER` instrumentation mode to agent
2. `recordUsage("className#memberName")` instead of `recordUsage("className")`
3. DependencyMap V4 format: stores member-level deps
4. `StructuralChangeAnalyzer` joins member-level deps with StructuralDiff changes
5. Perfect precision: "testFoo calls Bar.calculate() which changed" → max boost

---

## Data Flow (After Tier 1+2)

```
Source code (git working tree)
        │
        ├─ ChangeDetector.detect()
        │   └─ Set<String> changedClasses
        │
        ├─ StructuralChangeAnalyzer.analyzeImpact()
        │   └─ Map<String, ClassImpact> impacts
        │       (per-class: which methods/fields changed + impact score)
        │
        ├─ DependencyMap (V2: class deps, V3: + method deps)
        │
        └─ TestScorer.score()
            ├─ depOverlap weighted by classImpact (not just count)
            ├─ failureRecency, newTest, changedTest (unchanged)
            └─ ScoreResult (enhanced)
                    │
                    v
            PriorityClassOrderer → class order
            PriorityMethodOrderer → method order within class
                (uses V3 method deps × classImpact for per-method scoring)
```

---

## Configuration

New scoring weights (all optional, backward-compatible):

```properties
# Enable structural impact analysis (default: true if git available)
testorder.structuralDiff.enabled = true

# Impact weights (defaults shown)
testorder.impact.methodBodyChanged = 1.0
testorder.impact.methodAdded = 0.8
testorder.impact.methodRemoved = 0.9
testorder.impact.methodSignatureChanged = 1.0
testorder.impact.fieldChanged = 0.7
testorder.impact.typeRemoved = 1.0
testorder.impact.typeSignatureChanged = 0.9

# Blend factor: how much structural impact influences depOverlap
# 0.0 = ignore structural diff (current behavior)
# 1.0 = fully weight by structural impact
testorder.impact.blendFactor = 0.5
```

The `blendFactor` allows gradual adoption:
```java
effectiveWeight = (1 - blendFactor) * 1.0 + blendFactor * classImpact
```
At `blendFactor=0`, every changed class counts equally (current behavior).  
At `blendFactor=1`, only structurally significant changes boost the score.

---

## Edge Cases

1. **New files** (no git history): All members are ADDED → impact = 0.8 (new code). Already handled by `newTest` bonus for test classes.

2. **Deleted files**: All members are REMOVED → impact = 1.0. Tests using deleted classes will fail regardless.

3. **Rename refactoring**: Shows as ADDED + REMOVED for the renamed member. Impact = 1.0 (conservative). Could be refined with heuristic matching (same body hash → RENAMED, lower impact).

4. **Generated code**: Files in `target/` or `build/` should be excluded (StructuralDiff already scopes to git-tracked files).

5. **Performance**: `StructuralDiff.diffAgainstGit()` per changed file costs ~1ms (regex parsing). For 50 changed files, total overhead is ~50ms — negligible vs. test execution.

6. **No git**: Fall back to class-level scoring (current behavior). `StructuralChangeAnalyzer` returns empty map → `blendFactor` has no effect.

---

## Summary

| Phase | Effort | Precision | Value |
|-------|--------|-----------|-------|
| **1: StructuralChangeAnalyzer** | ~2 days | Class-level deps × member-level changes | High — filters noise from comment/format-only changes |
| **2: Method-Level Impact** | ~1 day | Test-method deps × member-level changes | Medium — orders methods within classes better |
| **3: CLI Diagnostics** | ~1 day | N/A | Developer experience — explains why tests are ordered |
| **4: Member-Level Agent** | ~1 week | Full member-level deps × member-level changes | Highest — precise "which test method calls which changed method" |

Phase 1 is the sweet spot: high value, low effort, no agent changes, backward compatible.
