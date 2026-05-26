# Known Bugs and Issues in test-order

Collected via systematic testing against Apache Commons IO, Commons Lang, Spring PetClinic,
and various synthetic edge-case projects. Each entry has a minimal reproducer.

---

## CRASH / WRONG

### B01 — `detect-dependencies` learn phase crashes with `NoClassDefFoundError: UsageStore` on instrumented projects

**Projects**: Apache Commons IO (and any project with offline-instrumented classes)
**Command**:
```
mvn test-order:detect-dependencies -Dtestorder.detect.algorithm=reverse -Dtestorder.detect.timeBudget=90
```
**Symptom**: The internal `runLearnPhase()` invocation fails immediately:
```
[WARNING] [test-order] Learn phase failed (exit code 1)
[ERROR] IOUtilsTest.beforeAll:144 » NoClassDefFound me/bechberger/testorder/agent/runtime/UsageStore
```
**Root cause**: `MavenTestRunner.runLearnPhase()` invokes `me.bechberger:test-order-maven-plugin:learn`
as a subprocess but does not pass the `test-order-agent-runtime` JAR (which contains `UsageStore`)
to the test classpath. The offline-instrumented classes call `UsageStore.recordClassOnly()` at runtime
but the JAR is not on the classpath of the subprocess.

**Workaround**: None — detection is completely broken for projects that use offline instrumentation.

**Note**: `DetectDependenciesMojo` was partially fixed to include agent-runtime in the `surefire:test`
subprocess classpath, but the `runLearnPhase()` path (separate Maven invocation via the `learn` goal)
was not updated.

**Severity**: CRASH

---

### B02 — Offline learn merge fails for projects using `<reuseForks>false</reuseForks>`

**Projects**: Apache Commons IO (244 test classes), any project that forks a new JVM per test class
**Command**:
```
mvn test   # (first run, learn mode)
```
**Symptom**: Each test class gets its own JVM, and the shutdown hook for merging collected dependency
data fails to send its payload before the JVM dies:
```
[test-order] merge failed in shutdown hook (classloader teardown) — writing fallback file; will be merged on next run
[test-order] Wrote fallback payloads to .test-order/test-dependencies.lz4.collector-fallback
```
After a full `mvn test` run across 244 test classes, only 1 test class ends up in the index:
```
"testClassCount": 1
```
Each subsequent `mvn test` invocation processes only the most recent fallback batch (the last
JVM's data), never accumulating the full index.

**Reproducer**:
1. Project must have `<reuseForks>false</reuseForks>` in Surefire config
2. Run `mvn test` (learn mode)
3. Run `mvn test-order:export-json` — observe `testClassCount` is 1 or very low

**Expected**: All test classes should be in the index after one full learn run.

**Root cause**: The `IndexCollectorServer` TCP server approach only keeps one fallback from the last
dying JVM; earlier JVMs' payloads overwrite each other (or get lost) when the server dies with each fork.

**Workaround**: Run `mvn test` multiple times (N runs for N test classes) — impractical for large
projects. Alternatively set `<reuseForks>true</reuseForks>` if the project allows it.

**Severity**: WRONG (learn mode silently produces an incomplete index)

---

### B03 — Offline learn merge also fails on projects with Spring context teardown (reuseForks=true)

**Projects**: Spring PetClinic (and any Spring Boot project using `@SpringBootTest`)
**Command**:
```
mvn test   # (first run, learn mode)
```
**Symptom**: Even with default surefire settings (reuseForks=true), the Spring context teardown
during `@AfterAll` triggers classloader shutdown before the test-order shutdown hook completes:
```
[test-order] merge failed in shutdown hook (classloader teardown) — writing fallback file; will be merged on next run
```
**Expected**: Learn data should be merged in-process without relying on a JVM shutdown hook.

**Severity**: WRONG

---

### B04 — `export-json` outputs to stdout mixed with Maven `[INFO]` log lines

**Command**:
```
mvn test-order:export-json
```
**Symptom**: The JSON output appears interleaved with Maven log messages on stdout:
```
[INFO] --- test-order:0.0.1-SNAPSHOT:export-json (default-cli) @ commons-io ---
[INFO] [test-order] Exporting dependency index as JSON (1 test classes)
  "exportVersion": 2,
```
The tip is shown but not in a useful place:
```
[test-order] Tip: use -Dtestorder.exportJson.output=<file> to write to a file, or run with -q to suppress Maven log messages from stdout
```
**Expected**: Tip should appear BEFORE the JSON dump so users see it. When output is a terminal,
the JSON should ideally go to a file by default (the tip suggests `-q` which suppresses all info,
making diagnosis hard). Also, the tip is printed to Maven INFO log (not stdout), but the JSON
is on stdout — a tool parsing the output would need to filter `[INFO]` prefix lines.

**Severity**: CONFUSING

---

## CONFUSING

### B05 — `testorder.select.maxTests` silently fails with wrong "did you mean" suggestion

**Command**:
```
mvn test-order:select -Dtestorder.select.maxTests=10
```
**Symptom**:
```
[WARNING] [test-order] Unknown property 'testorder.select.maxTests' — did you mean 'testorder.select.selectedFile'?
```
**Expected**: The correct parameter for limiting test count is `-Dtestorder.select.topN=N`. The
"did you mean" suggestion (`testorder.select.selectedFile`) is unrelated and unhelpful — it should
suggest `testorder.select.topN` instead.

**Severity**: CONFUSING

---

### B06 — `tiered-select` prints warning about not executing tests BEFORE failing on missing index

**Command**:
```
mvn test-order:tiered-select   # on a fresh project with no index
```
**Symptom**:
```
[WARNING] [test-order] The 'tiered-select' goal configures Surefire but does not execute tests. Include the test phase: mvn test-order:tiered-select test
[INFO] BUILD FAILURE
[ERROR] No dependency index at .test-order/test-dependencies.lz4 ... Run learn mode first
```
The irrelevant warning about including the `test` phase is emitted even when the goal immediately
fails due to missing index. The user's first impression is the misleading warning, not the real error.

**Expected**: Check for missing index first (fail fast); only emit the "include test phase" warning
after successfully selecting tests.

**Severity**: CONFUSING

---

### B07 — `select` goal emits "non-deterministic" warning even when no subset is selected

**Command**:
```
mvn test-order:select -Dtestorder.select.topN=5
```
**Symptom**:
```
[WARNING] [test-order] Selection is non-deterministic (no seed set). Set testorder.select.seed for reproducible CI runs.
[INFO] [test-order] Selected all 1 tests (no subset — all will run in priority order)
```
When `topN` is larger than the test count (so all tests run), the non-determinism warning is
irrelevant — there's only one possible selection.

**Expected**: Suppress the non-determinism warning when `topN >= total test count` (i.e., all
tests are selected).

**Severity**: MINOR/CONFUSING

---

### B08 — `show-order` goal is deprecated but documentation/help text may still reference it

**Command**:
```
mvn test-order:show-order
```
**Symptom**:
```
[WARNING]  Goal 'show-order' is deprecated: Use mvn test-order:show instead. This goal will be removed in a future release.
```
The deprecation warning itself is correct. However, the GETTING_STARTED.md and other docs may
still reference `show-order` instead of `show`. Users who follow the docs will get a deprecation
warning on every run.

**Severity**: MINOR (docs issue)

---

### B09 — `No dependency index` error after `detect-dependencies` — no index is left in `.test-order/`

**Command**:
```
mvn me.bechberger:test-order-maven-plugin:detect-dependencies -Dtestorder.detect.timeBudget=30
# (without plugin in pom.xml, using fully qualified goal name)
```
**Symptom**: After `detect-dependencies` completes successfully ("No order-dependent tests detected"),
running `test-order:show` or `test-order:tiered-select` fails:
```
[ERROR] No dependency index at .test-order/test-dependencies.lz4
```
The `detect-dependencies` goal performs an internal learn phase but discards the resulting index.

**Expected**: The learn phase data collected during `detect-dependencies` should be persisted in
`.test-order/test-dependencies.lz4` so subsequent ordering goals can use it without requiring a
separate explicit learn run.

**Severity**: CONFUSING (unexpected extra step after running detection)

---

### B10 — `select` goal warning about Surefire `<excludes>` override may be a false positive

**Command**:
```
mvn test-order:select test
```
**On**: Apache Commons IO (which has a `<excludes>` for non-test data files)

**Symptom**:
```
[WARNING] [test-order] Surefire <excludes> is configured but test-order's select mode overrides it via <test> parameter. Previously excluded tests may run. Consider using <excludedGroups> (JUnit 5 @Tag) instead for consistent filtering.
```
Commons IO excludes `**/TestResources.java` (a test-data helper class, not a test) and
`**/FileUtilsDeleteDirectoryLinuxTest.java` (Linux-only). The warning is technically correct but
alarming — users may not understand that file exclusions (like excluding a non-test helper class)
are different from tag-based filtering, and the suggested workaround (`@Tag`) doesn't apply to
file-based exclusions.

**Expected**: The warning should distinguish between (a) tag-based exclusion (incompatible with
`-Dtest=` override) and (b) file-based exclusion of non-test helpers (usually harmless). Or at
minimum explain what the `<excludes>` pattern contained.

**Severity**: CONFUSING (may cause alarm for users with legitimate file-based exclusions)

---

### B11 — `diagnose` reports 100% HEALTHY even when full learn never succeeded

**After**: Running `mvn test` (learn mode) on commons-io where every class writes a fallback
**Command**:
```
mvn test-order:diagnose
```
**Symptom**:
```
[INFO] Health Score: 100%  HEALTHY ✓
```
Despite the index containing only 1 of 244 test classes (B02), the diagnose goal reports
100% health. The diagnose goal does not check whether the index is representative of the
project's actual test count.

**Expected**: Diagnose should warn when the index contains far fewer test classes than the
project's test class count (e.g., `<10% coverage`).

**Severity**: WRONG (false confidence)

---

### B12 — Plugin injected into `<pluginManagement><plugins>` section is silently ignored

**Command**: Any Maven invocation on a project where the plugin was added to `<pluginManagement>`
instead of `<build><plugins>`.
**Symptom**: No `[test-order]` messages appear; plugin never executes. No warning from Maven about
a plugin being declared in pluginManagement without being in build/plugins.

**Reproducer**:
```xml
<build>
  <pluginManagement>
    <plugins>
      <!-- WRONG: plugin will be registered but not executed -->
      <plugin>
        <groupId>me.bechberger</groupId>
        <artifactId>test-order-maven-plugin</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <executions>
          <execution><goals><goal>prepare</goal></goals></execution>
        </executions>
      </plugin>
    </plugins>
  </pluginManagement>
  <!-- Missing: plugin must also appear in <build><plugins> -->
</build>
```
**Expected**: This is standard Maven behavior, not a plugin bug. But the getting-started docs
should include a note that the plugin must be in `<build><plugins>`, not `<pluginManagement>`.

**Severity**: MINOR (documentation gap — common newcomer mistake)

---

### B13 — `reuseForks=false` warning appears even if learn mode is perfectly functional

**Command**: `mvn test` (learn mode, project with `reuseForks=false`)
**Symptom**:
```
[WARNING] [test-order] Surefire <reuseForks>false</reuseForks> — each test class runs in a new JVM. Learn mode works but is slower and may miss cross-class static dependencies.
```
The warning says "learn mode works" but as documented in B02, it does NOT reliably collect data
for all test classes. The warning is misleading — it downplays the severity.

**Expected**: The warning should say: "Learn mode is unreliable with `reuseForks=false` — dependency
data from each JVM is sent via a shutdown hook and may be lost. Consider setting `<reuseForks>true</reuseForks>`
for accurate dependency tracking."

**Severity**: CONFUSING (understates a critical issue)

---

### B14 — No source hash file (`hashes.lz4`) created when project has no `src/main/java`

**Project**: Test-only project (no production source)
**Command**: `mvn test` (learn mode)
**Symptom**: The `.test-order/` directory contains `test-hashes.lz4` and `method-hashes.lz4`
but no `hashes.lz4` (source hash). Running `diagnose` does not report this as an issue.

**Expected**: This is likely a non-issue for pure test libraries, but `diagnose` should not
flag missing `hashes.lz4` as a problem when there's no `src/main/java`. Currently it correctly
does NOT flag it, which is good — this is just a note that the behavior is correct.

Actually: Diagnose reports `Hash snapshot files missing: source` as INFORMATIONAL (not error).
Verify that this doesn't cause change detection to break on subsequent runs.

**Severity**: MINOR (observation, likely correct behavior)

---

### B15 — `export-json` only shows 1 test class after full learn run on `reuseForks=false` project

See B02 for root cause. The `export-json` output confirms:
```
"testClassCount": 1
```
despite 244 test classes existing. Users running `export-json` to validate their setup would
falsely believe learning is working when it isn't.

**Severity**: WRONG (consequence of B02)

---

## MINOR / DOCS

### B16 — No documentation on how to handle `@{argLine}` vs hardcoded `argLine` in Surefire config

**Observation**: Projects using JaCoCo typically have `<argLine>@{argLine} ...</argLine>` in their
Surefire config. The `@{argLine}` placeholder gets replaced by JaCoCo's `prepare-agent` goal.
test-order injects its properties via the same `@{argLine}` mechanism and they are correctly
appended. This works well.

However, if a project uses a hardcoded argLine (no `@{argLine}`), test-order's properties will
not be added (they rely on appending to `@{argLine}`). The plugin detects offline mode as the
fallback — but there is no warning when the test-order system properties are not in the argLine.

**Expected**: Document the `@{argLine}` interaction in the docs. Add a warning if the plugin
detects that the Surefire argLine does NOT contain `@{argLine}` and test-order needs to inject
properties.

**Severity**: MINOR (docs gap, potential silent failure for online agent mode)

---

### B17 — `tiered-select` on commons-io puts 238/244 tests in tier-2 and 6 in tier-3 with no learn data

**Observation**: After a partial learn run (only 1 test class in index), tiered-select puts:
- 0 tests in tier-1 (change-affected) — correct, no changes
- 238 tests in tier-2 (top-scored)
- 6 tests in tier-3 (rest)

The 6 tests in tier-3 are the slowest tests in the project. The scoring apparently works from
duration history even without dependency data. This is good behavior, but the fact that 238 tests
go to tier-2 (when ideally a subset would go to tier-1 based on dep tracking) shows the learning
gap from B02.

**Severity**: MINOR (expected consequence of B02, but shows graceful degradation)

---

### B18 — Missing `.test-order/hashes.lz4` after `detect-dependencies` run

After a `detect-dependencies` run on a project without the plugin in pom.xml, the `.test-order/`
directory is created but does NOT contain `hashes.lz4`. If the user then adds the plugin and
runs `mvn test`, the change detection will see all files as "changed" (no baseline to compare to).

**Expected**: The `detect-dependencies` goal's internal learn phase should save the source hash
snapshot so that the next `prepare` run can correctly detect changes.

**Severity**: MINOR

---

### B19 — `pending-runs` files accumulate, causing O(n²) performance collapse

**Projects**: Apache Commons Collections (and any project with `reuseForks=false` or Spring teardown)
**Command**:
```
mvn test   # repeated learn-mode runs
```
**Symptom**: After each run that triggers the shutdown-hook fallback, a `.part` file is written to
`.test-order/pending-runs/`. These files are **never deleted** after processing. With each
additional run the state file grows, and JUnit test discovery calls
`PriorityClassOrderer.getOrder()` once per test class — each call reads the growing state.
Measured on Commons Collections (244 test classes):
```
Run 1 (0 pending files): 27 s
Run 2 (1 pending file):  48 s
Run 3 (2 pending files): 82 s
Run 4 (3 pending files): 144 s
Run 5 (4 pending files): 188 s  (3m 8s total)
```
**Root cause**: Processed pending-run files are not deleted; state reads inside the JUnit orderer
callback are not cached at the JVM level so each per-class call pays the full deserialization cost.

**Expected**: Pending-run files should be deleted after successful merge. The `PriorityClassOrderer`
should deserialize the state once per JVM (lazy initialization), not once per test class.

**Severity**: CRASH (makes plugin unusable after a few runs on `reuseForks=false` projects)

---

### B20 — `clean` goal does not remove the `pending-runs` directory

**Command**:
```
mvn test-order:clean
```
**Symptom**: The clean goal deletes `.test-order/*.lz4` files but leaves the `.test-order/pending-runs/`
subdirectory (and all accumulated `.part` files) completely intact. Subsequent "clean" learn runs
immediately say "Processed collector fallback payloads from previous learn run" and pick up the
stale data.

**Reproducer**:
1. Run `mvn test` several times until `pending-runs/` accumulates `.part` files
2. Run `mvn test-order:clean`
3. Run `mvn test-order:show` — observe it still has data from the "cleaned" run

**Expected**: `test-order:clean` should remove the entire `.test-order/` directory tree including
`pending-runs/` and `detection/`.

**Severity**: WRONG

---

### B21 — `detect-dependencies` reports "No order-dependent tests detected" even when reference run fails entirely

**Projects**: Apache Commons Collections
**Command**:
```
mvn test-order:detect-dependencies -Dtestorder.detect.algorithm=reverse -Dtestorder.detect.timeBudget=60
```
**Symptom**: The reference test run failed catastrophically (`NoClassDefFoundError: UsageStore` for
all tests), producing 7,849 errors and 173 failures (only 89 test classes passed). Despite this,
the plugin reported:
```
Detection complete: 0 findings (0 victims, 0 brittles) in 1m 9s
[test-order] No order-dependent tests detected.
```
The only indication of failure was `[WARNING] Subprocess exited with code 1`.

**Expected**: When the reference run fails (subprocess exit code != 0), detection should be aborted
immediately with an error. Detecting "0 order-dependent tests" from a run where 90% of tests were
missing is a dangerous false negative.

**Severity**: WRONG (false-negative detection result)

---

### B22 — `detect-dependencies` permanently overwrites production `test-dependencies.lz4` with MEMBER-mode data

**Projects**: Apache Commons Collections
**Command**:
```
mvn test-order:detect-dependencies
```
**Symptom**: Before running, `test-dependencies.lz4` was 12 KB (CLASS-mode from initial learn).
After detect-dependencies triggered its internal MEMBER-mode re-learn phase, the file was
replaced with 1.4 MB of MEMBER-mode data. This is incompatible with the CLASS-mode instrumentation
still in `target/classes`, and corrupts subsequent plugin operations.

**Expected**: `detect-dependencies` should use a temporary/isolated copy of the dependency index
for its internal re-learn, leaving the production index untouched when done. Or at minimum warn
that the index was replaced.

**Severity**: WRONG (data corruption)

---

### B23 — `tiered-select` silently skips tier-3 tests without any warning

**Command**:
```
mvn test-order:tiered-select test
```
**Symptom**: `tiered-select test` runs tier-1 and tier-2 inline but leaves tier-3 for a separate
invocation. On Commons Collections, 150 tests (2 classes) were completely silently skipped. The
only indication was an INFO-level "next step" hint:
```
[INFO] Next step: mvn test-order:run-tier test -Dtestorder.tiered.currentTier=3
```
A user running `mvn test-order:tiered-select test` expecting a complete test run would silently
miss 98% coverage without any WARNING.

**Expected**: A `[WARNING]` should be emitted when tier-3 tests exist but are not executed:
"X tests in tier-3 were NOT run — build does not represent full coverage. Run:
`mvn test-order:run-tier test -Dtestorder.tiered.currentTier=3`"

**Severity**: CONFUSING

---

### B24 — `show` goal lists abstract test classes as `[NEW]` indefinitely

**Projects**: Apache Commons Collections (38 abstract base test classes excluded by Surefire)
**Command**:
```
mvn test-order:show
```
**Symptom**: After multiple learn runs, 38 abstract test classes (`AbstractBagTest`,
`AbstractMapTest`, etc.) still appear in the output with score=15 and label `[NEW]`, ranked at
the top as highest-priority tests. These classes are excluded by Surefire's
`<excludes>**/Abstract*.java</excludes>` and never actually run. They will always be `[NEW]`
because they are never observed running. The summary says `Total: 282 tests | 38 NEW` when
Surefire only runs 244.

**Reproducer**: Project with `<excludes>**/Abstract*.java</excludes>` in Surefire config, after
multiple `mvn test` runs.

**Expected**: The plugin should detect classes that are excluded by Surefire and either omit them
from the show output, mark them as "excluded", or after N runs without observation stop promoting
them as high-priority NEW tests.

**Severity**: CONFUSING

---

### B25 — `select` goal message: "topN=-1 selects all change-affected tests" is misleading

**Command**:
```
mvn test-order:select test
```
**Symptom**:
```
[INFO] [test-order] topN=-1 (default) selects all change-affected tests.
       For a true subset, set -Dtestorder.select.topN=N (e.g., topN=10).
```
With no source changes, `topN=-1` actually runs **all** tests — not just "change-affected" ones.
The phrase "all change-affected tests" implies filtering is occurring when the full suite runs.

**Expected**: Message should say: "topN=-1 (default) runs all tests in priority order (no subset
selection). To run only the top N, set -Dtestorder.select.topN=N."

**Severity**: CONFUSING

---

### B26 — `detect-dependencies` exceeds time budget with no `[WARNING]`

**Command**:
```
mvn test-order:detect-dependencies -Dtestorder.detect.timeBudget=60
```
**Symptom**: Output shows `Detection complete: 0 findings in 1m 9s (budget: 60s)` — the run
exceeded the 60-second budget by 9 seconds with no `[WARNING]`. The budget-exceeded information
only appears in the INFO completion message. The hint `Set testorder.detect.timeBudget=69` is
also at INFO level.

**Expected**: When actual run time exceeds the specified budget, emit a `[WARNING]`:
"Detection run exceeded time budget (69s > 60s). Results may be incomplete. Set
`-Dtestorder.detect.timeBudget=70` to ensure a full run."

**Severity**: MINOR

---



| ID  | Severity | Area                  | Description                                              |
|-----|----------|-----------------------|----------------------------------------------------------|
| B01 | CRASH    | detect-dependencies   | NoClassDefFoundError UsageStore in learn phase           |
| B02 | WRONG    | learn mode            | reuseForks=false: index only gets 1 test class           |
| B03 | WRONG    | learn mode            | Spring context teardown causes merge failure             |
| B04 | CONFUSING| export-json           | JSON mixed with Maven log lines; tip order wrong         |
| B05 | CONFUSING| select                | maxTests → wrong "did you mean" suggestion               |
| B06 | CONFUSING| tiered-select         | Warning about test phase shown before missing-index error|
| B07 | MINOR    | select                | Non-determinism warning when all tests are selected      |
| B08 | MINOR    | show-order            | show-order still referenced in docs after deprecation    |
| B09 | CONFUSING| detect-dependencies   | Index not persisted after detect-deps run                |
| B10 | CONFUSING| select                | False-alarm warning about <excludes> override            |
| B11 | WRONG    | diagnose              | 100% healthy despite incomplete index                   |
| B12 | MINOR    | docs                  | pluginManagement vs build/plugins confusion              |
| B13 | CONFUSING| learn mode            | reuseForks warning understates the severity              |
| B14 | MINOR    | learn mode            | No hashes.lz4 for source-less projects                   |
| B15 | WRONG    | export-json           | Consequence of B02: testClassCount=1 after full learn    |
| B16 | MINOR    | docs                  | @{argLine} interaction not documented                    |
| B17 | MINOR    | tiered-select         | Expected degradation from B02 (graceful)                 |
| B18 | MINOR    | detect-dependencies   | hashes.lz4 not saved by detect-deps learn phase          |
| B19 | CRASH    | learn mode            | pending-runs files accumulate, O(n²) slowdown            |
| B20 | WRONG    | clean                 | clean goal does not remove pending-runs directory        |
| B21 | WRONG    | detect-dependencies   | "0 findings" reported when reference run catastrophically fails |
| B22 | WRONG    | detect-dependencies   | detect-deps overwrites production index with MEMBER-mode data |
| B23 | CONFUSING| tiered-select         | Tier-3 tests silently skipped with no warning            |
| B24 | CONFUSING| show                  | Abstract test classes shown as [NEW] indefinitely        |
| B25 | CONFUSING| select                | topN=-1 message says "change-affected" but means "all"   |
| B26 | MINOR    | detect-dependencies   | Time budget exceeded with no WARNING                     |
