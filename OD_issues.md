# Detect-Dependencies Usability Issues

Found by running `detect-dependencies` on real third-party projects (jsoup, gson, commons-lang, commons-collections, commons-io, commons-text, spring-petclinic, javaparser).

## Open Issues

| # | Severity | Issue | Status |
|---|----------|-------|--------|
| 23 | Low | Polluter identification inconsistent across algorithms | IMPROVED — classification consistent |
| 29 | Low | Polluter attribution varies by algorithm | INHERENT — documented |
| 49 | Critical | Class-level pass/fail too coarse — all classes excluded | FIXED — only marks class as failed when ALL methods fail |
| 50 | High | Learn phase subprocess fails on external projects (plugin prefix) | FIXED — uses fully-qualified coordinates |
| 51 | Medium | Large suites: no progress shown before first run completes | NEW |
| 52 | Medium | "No tests pass" message misleading when few methods fail | FIXED — message now mentions method-level failures |
| 53 | Low | Stale lock message bypasses Maven logging (System.err) | FIXED — uses LOGGER.info() |
| 54 | Low | Budget recommendation missing for large suites | FIXED — logs suggested timeBudget after first run |

## Fixed Issues (removed from tracking)

Issues 1–22, 24–28, 30–48 — all verified fixed.

---

## Critical

### 49. Class-level pass/fail too coarse — few method failures exclude entire classes

**Observed on**: commons-lang (231 classes, 64148 tests, 1053 method failures)  
**Symptom**: ALL 231 test classes are marked as "failing" and excluded from detection because each class has at least one failing method. The tool then reports "No tests pass in reference order" and gives up.  
**Root cause**: `MavenTestRunner.parseReport()` marks a class as `failed` if `failures + errors > 0` — even if 99% of methods pass. For commons-lang, ~1.6% method failure rate causes 100% class exclusion.  
**Impact**: Detection is completely non-functional on any project where a few tests fail due to environment issues (Java module access, network, etc).  
**Fix**: Either (a) consider a class "passing" if the majority of methods pass, or (b) only mark a class as failed if 100% of its methods fail, or (c) use method-level pass/fail granularity for the reference check.

---

## High

### 50. Learn phase subprocess fails on external projects (plugin prefix)

**Observed on**: commons-collections, commons-text, commons-io  
**Symptom**: `Learn phase failed — continuing without full dependency data` followed by suboptimal detection (no conflict graph from fresh dependency data).  
**Root cause**: `MavenTestRunner.runLearnPhase()` invokes `mvn test-order:learn test` using the short plugin prefix. External projects don't have `<pluginGroup>me.bechberger</pluginGroup>` in `settings.xml`, so the subprocess fails with "No plugin found for prefix 'test-order'."  
**Impact**: Detection works but without dependency-guided optimization, so more runs are needed to find the same bugs.  
**Fix**: Use fully-qualified coordinates: `mvn me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:learn test` in the subprocess command.

---

## Medium

### 51. Large suites: no progress shown before first run completes

**Observed on**: jsoup (64 classes, ~15s per run), commons-collections (244 classes)  
**Symptom**: With 30s budget, only 2 runs complete for jsoup. No progress line is printed before the first run finishes (~15s in), then budget expires during the second. User sees no output for 15+ seconds.  
**Impact**: Users think the tool is stuck. No guidance on what budget would be meaningful.  
**Fix**: (a) Print a "Starting first test run (N classes)..." message before the first run, (b) after the first run, log the measured per-run time and advise: "Each run takes ~Xs — recommend budget of at least Y s for meaningful coverage."

### 52. "No tests pass" message misleading when few methods fail

**Observed on**: commons-lang  
**Symptom**: Message says "No tests pass in reference order — cannot detect OD bugs. This usually means the test suite has compilation errors or all tests fail regardless of order." In reality, 63095 of 64148 tests pass — only a few methods per class fail.  
**Impact**: The suggested cause ("compilation errors or all tests fail") is wrong and confusing. User can't diagnose the real issue.  
**Fix**: Amend message: "N of M classes had method-level failures (passed classes: 0). If only a few methods fail per class, consider using `--add-opens` or fixing the failing tests."

---

## Low

### 23. Polluter identification inconsistent across algorithms (improved)

Classification (VICTIM/BRITTLE) is now consistent via isolation check. But the identified polluter still varies by algorithm.

### 29. Polluter attribution varies by algorithm

Different algorithms identify different polluters for the same victim. Inherent to the approach.

### 53. Stale lock message bypasses Maven logging (System.err)

**Observed on**: commons-collections, commons-text  
**Symptom**: `[test-order] Deleting stale lock file (older than 120 min): state.lz4.lock` is printed via `System.err.println()` and appears without Maven's `[INFO]`/`[WARNING]` prefix, breaking log format consistency.  
**Impact**: Cosmetic — message looks out of place in Maven output.  
**Fix**: Use `java.util.logging.Logger` or accept a `PluginLog` parameter in `PersistenceSupport.cleanupStaleLock()`.

### 54. Budget recommendation missing for large suites

**Observed on**: jsoup, commons-collections, commons-text  
**Symptom**: After only 1–2 runs complete in 30s, the warning says "increase testorder.detect.timeBudget" but doesn't say to what value.  
**Impact**: User has no idea whether they need 60s, 300s, or 3600s.  
**Fix**: After the first run, calculate: `suggestedBudget = perRunTime * estimatedRuns * 0.5` and log: "Each run takes ~Xs. For 50% coverage, set testorder.detect.timeBudget=Y."
