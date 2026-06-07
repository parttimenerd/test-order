# Follow-up tasks for another LLM session

Context: this document captures unresolved issues observed while running
`scripts/third_party_test_plan.sh full <repo>` on real OSS repos. The recent
session fixed the lifecycle/double-compilation bug (see "Recently fixed"
below) and surfaced the issues listed here.

## Recently fixed (for context)

1. **Double-lifecycle recompilation bug**.
   `mvn clean process-test-classes <CLI-goal> test` causes Maven to re-run
   the default lifecycle from `validate` when computing the `test` phase
   target — recompiling sources and overwriting instrumented bytecode.
   Fix: `CollectorLifecycleParticipant.afterProjectsRead()` now
   programmatically binds `test-order:prepare` to `process-test-classes`
   for every reactor project that does not already declare the plugin.
   The script was simplified to plain `mvn clean test -Dtestorder.mode=…`
   (no CLI goal, no extra phase). See
   `test-order-maven-plugin/src/main/java/me/bechberger/testorder/maven/CollectorLifecycleParticipant.java`
   (method `ensurePrepareGoalBound`).

2. **`-Dsurefire.argLine=-Xmx512m` was stripping test-order properties**
   from forked JVMs. Surefire 3.x's `surefire.argLine` user property
   overrides the `@{argLine}` placeholder entirely, discarding
   `testorder.collector.port` etc. Removed from `base_args` in
   `scripts/third_party_test_plan.sh`.

## Open issues

### 1. Bug not caught in top-3 for jackson-databind (step 7)

**Symptom**: Step 7 of the full workflow injects a flipped-comparison bug
into `tools.jackson.databind.introspect.POJOPropertiesCollector`. The
plugin selects 13 tests, none of them is one of the tests that actually
exercise `POJOPropertiesCollector` enough to fail.

**Diagnosis**:
- The dump shows **601 of 735 test classes (82%) have
  `POJOPropertiesCollector` in their dep set**.
- When 82% of tests "depend on" the changed class, the dependency-based
  score is essentially uniform — the scorer can't discriminate.
- The selector picks 13 from those 601, and the picked subset happens
  not to include any test that exercises the flipped branch.
- This is not a bug-injection script bug. It's a fundamental signal
  problem: too many transitive deps mean too many false positives in
  "tests that depend on changed class."

**Where to look**:
- `test-order-core/src/main/java/me/bechberger/testorder/TestScorer.java`
  — the scoring/selection logic.
- `test-order-core/src/main/java/me/bechberger/testorder/DependencyMap.java`
  — how deps are stored.
- `target/third-party-results/jackson-databind/<timestamp>/full-bug-select.log`
  shows the 13 selected tests; none cover `POJOPropertiesCollector`
  flips meaningfully.

**Possible directions** (do NOT implement without discussion):
- Penalize near-universal deps (TF-IDF-style: a class that appears in
  >X% of tests gets a discount). This is also the right fix for the
  index-size problem (issue #2).
- Track *call frequency* (how many times the test calls into the dep)
  rather than *binary presence*. Currently the agent records "did test
  T touch class C," not "how heavily."
- For "dirty" changed classes (high in-degree), boost tests that hit
  that class via *direct* references rather than transitive ones.

**Reproduction**:
```bash
cd /Users/i560383_1/code/experiments/test-order
bash scripts/third_party_test_plan.sh full jackson-databind
# Look at target/third-party-results/jackson-databind/<latest>/full-bug-select.log
```

### 2. Index size: 80MB for jackson-databind (was 239KB)

**Symptom**: After fixing the double-lifecycle bug, jackson-databind's
`test-dependencies.lz4` is 80 MB. The same dump as JSON is ~9 MB
(735 test classes × avg 220 deps each = ~162k tuples).

**Root cause**: jackson is heavily transitive. A handful of utility
classes (`ClassUtil`, `ObjectMapper`, `TypeFactory`, …) appear in
~700 of 735 tests. They cost a lot of bytes and provide ~zero
discrimination signal.

**Top offenders** (from `/tmp/jackson-dump.json` analysis — regenerate
with the dump command in §1 reproduction):

| Class | # tests | % of 735 |
| --- | --: | --: |
| tools.jackson.databind.util.ClassUtil | 715 | 97% |
| tools.jackson.databind.util.internal.PrivateMaxEntriesMap | 713 | 97% |
| tools.jackson.databind.util.SimpleLookupCache | 711 | 97% |
| tools.jackson.databind.type.TypeFactory | 708 | 96% |
| tools.jackson.databind.ObjectMapper | 707 | 96% |
| … many more in the 95%+ band | | |

A class that 97% of tests depend on is by definition useless for test
selection: every test will "match" any change to it.

**Where to look**:
- `test-order-agent/src/main/java/me/bechberger/testorder/agent/runtime/UsageStore.java`
  — the in-fork tracker that records dep tuples.
- `test-order-core/src/main/java/me/bechberger/testorder/IndexCollectorServer.java`
  — server-side merge & write.
- `test-order-core/src/main/java/me/bechberger/testorder/DependencyMap.java`
  — on-disk representation.

**Possible directions** (discuss before implementing):
- **High-frequency dep cap (implemented):** during merge in
  `IndexCollectorServer`, drop deps where `count(dep) / count(tests) >
  threshold` (e.g. 0.5 or 0.8). Configurable via
  `-Dtestorder.deps.dropFrequencyThreshold=0.5`. This addresses both
  index-size AND the "82% of tests match" scoring problem in §1.
  Member deps for dropped classes are also filtered (`filterMemberDeps`).
- **Whitelist filtering**: respect `testorder.includePackages` on the
  *target* class, not just the test class. Today
  `includePackages=tools.jackson.databind` is satisfied by every
  jackson-databind class, which doesn't help.
- **Bytecode encoding**: switch from string-array per test to a
  bitmap-per-class layout (RoaringBitmap is already on the classpath
  per `CollectorLifecycleParticipant`'s comment). For ~700 highly-
  shared classes, a bitmap of 735 bits is 92 bytes vs storing the
  class name in 700 entries.

**Reproduction**:
```bash
cd /Users/i560383_1/code/experiments/test-order/third-party/jackson-databind
mvn me.bechberger:test-order-maven-plugin:dump \
    -Dtestorder.dump.format=json \
    -Dtestorder.dump.output=/tmp/jackson-dump.json -q
# Distribution of deps per test:
awk -F'\t' 'NF==2 {n=split($2, a, ","); total+=n; count++} \
    END {printf "tests=%d, total_deps=%d, avg=%d\n", count, total, total/count}' \
    /tmp/jackson-dump.json
# Top deps:
awk -F'\t' 'NF==2 {gsub(/,/,"\n",$2); print $2}' /tmp/jackson-dump.json \
    | sort | uniq -c | sort -rn | head -20
```

### 3. Multi-module repos: netty blocked by `xml-maven-plugin`

**Symptom**: `mvn clean test` on netty fails at `validate` because the
`xml-maven-plugin:check-format` execution finds pre-existing format
violations in `codec-http/pom.xml`. Plugin is `xml-maven-plugin:1.0.1`
and has **no `skip` parameter**.

**Workarounds tried**:
- `-Pfast` profile (defined in netty/pom.xml) sets `xml.skip=true` but
  the plugin doesn't read that property — only sets `skipTests=true`
  which we don't want. The bundle is ineffective for this case.

**Possible directions**:
- Per-repo Maven args in the script (`detect_extra_args`) that
  *deactivate* the `check-style` execution by ID — possible via
  Maven 3.6.1+'s `-Dmaven.plugin.<artifactId>.skip` style? No standard
  mechanism exists for `xml-maven-plugin`.
- Skip the `validate` phase entirely. Not possible in standard Maven —
  only achievable by bypassing the lifecycle, e.g.,
  `mvn surefire:test` direct goal — but that requires already-compiled
  classes.
- Two-step: `mvn -DskipTests test-compile && mvn surefire:test
  -Dtestorder.mode=learn` (the old `mvn_learn` shape). Already known to
  break multi-module builds because the second invocation can't resolve
  inter-module SNAPSHOTs without `install`.
- Tell users to fix their `xml-maven-plugin` config to read a `skip`
  property (upstream-fix).

**Reproduction**:
```bash
cd /Users/i560383_1/code/experiments/test-order
bash scripts/third_party_test_plan.sh full netty
# Look at target/third-party-results/netty/<latest>/full-learn-1.log
```

### 4. Untested third-party repos

Repos in `third-party/` that have NOT yet been verified with the new
goal-binding fix and the simplified `mvn clean test` invocation:
- spring-boot
- hibernate-orm
- okhttp
- junit5
- kafka
- micronaut-core
- mockito
- neonbee

The "currently working" list in
`memory/project_third_party_testing.md` predates the goal-binding fix.
A regression sweep is appropriate.

### 5. Synthetic history end-to-end

The user mentioned wanting to test "with synthetic history" — i.e.,
the history-replay or git-history simulation path. Not yet exercised
end-to-end with the new fixes. Look for `synthetic` / `history` related
flags in `scripts/third_party_test_plan.sh` and the plugin docs.

## Verification still needed

After any change to `CollectorLifecycleParticipant.ensurePrepareGoalBound`
or to the script, re-run:
```bash
# Quick smoke (should produce an index, no double-compile):
bash scripts/third_party_test_plan.sh full jackson-databind
bash scripts/third_party_test_plan.sh full commons-collections
bash scripts/third_party_test_plan.sh full commons-text
bash scripts/third_party_test_plan.sh full jsoup
bash scripts/third_party_test_plan.sh full spring-petclinic
```

Look for these positive signals in `full-learn-1.log`:
- Exactly ONE `Compiling N source files` and ONE `testCompile` per module.
- `test-order:prepare (test-order-prepare-injected)` line — proves the
  binding works.
- `IndexCollectorServer merged N test classes` line — proves the
  collector drained.

Negative signals to grep for:
- `Recompiling the module` appearing AFTER the prepare goal — means
  the fix didn't hold for that repo and bytecode was overwritten.
- Empty `target/test-order-deps` or missing `test-dependencies.lz4`.

## Concrete improvement suggestions

These are ordered by expected impact-per-effort, highest first. Each is
self-contained — pick one, finish it, ship it. Don't bundle.

### S1. TF-IDF dep weighting — **DONE (scoring only)**

**Status**: IDF weighting is implemented in `TestScorer` — each overlapping
dep contributes `idf(dep)` to the weighted overlap score instead of 1.
Near-universal deps (high df) get near-zero weight automatically.

The companion write-time pruning (dropping deps where `df > threshold * N`
from the index) was considered and explicitly **not implemented**: the
scoring-side fix is sufficient for ranking, and pruning at write time would
remove information that may be useful for future features (e.g. per-exercise
weight, S4). Index size remains as-is.

### S2. RoaringBitmap-per-class index encoding

**Target**: issue §2.

**Idea**: Today the on-disk format stores, per test, the list of dep
class names (strings). Invert: store, per dep class, a `RoaringBitmap`
of test-class IDs that touch it. For 735 tests, a bitmap is at most
~92 bytes uncompressed; near-universal deps compress to a single run.
RoaringBitmap is already a transitive dep of the plugin (per
`CollectorLifecycleParticipant`'s NoClassDefFoundError comment).

**Where**:
- `DependencyMap` — switch internal representation.
- `IndexCollectorServer` write path.
- All readers (`TestScorer`, `ExportJsonOperation`, `Tool`, dump goal).

**Risk**: medium-high (touches the persistence format). Bump the
index file's magic-number/version byte and write a one-shot migration
in `IndexCollectorServer` that reads v1 and writes v2.

**Skip if S1 alone gets the index under ~5 MB** — the storage win
above the dep-count drop is marginal.

### S3. Make netty (and similar) work via `-D<plugin>.skip` injection

**Target**: issue §3.

**Idea**: Some plugins respect a generic skip property (e.g.
`spotless.skip`, `checkstyle.skip`). `xml-maven-plugin` 1.0.1 doesn't,
but we can deactivate the *execution* by overriding it at runtime.
Add to `CollectorLifecycleParticipant.afterProjectsRead()`:

> For each project, walk `project.getBuildPlugins()`. If a plugin
> binds to `validate` and is on a known-problematic list (xml-maven-plugin,
> spring-javaformat-maven-plugin in check-only mode, …) AND the user
> opted in via `-Dtestorder.disableValidatePlugins=true`, replace the
> plugin's executions with an empty list (or move them to a non-binding
> phase like `none`).

**Risk**: medium. Some projects' validate-phase plugins are load-bearing
(e.g., generate sources). The opt-in flag protects against silent
breakage. Document in `MAVEN_PLUGIN.md`.

**Alternative** (lower-risk, less general): per-repo
`detect_extra_args` in the script with `-Dxml.skip=true` for repos that
honor the property, plus a manual fix list. Doesn't help the user
running their own build, only the integration script.

### S4. Per-test "exercise weight" instead of binary touch

**Target**: issue §1, deeper fix than S1's IDF.

**Idea**: Today the agent records "did test T load class C." Switch to
a count: how many distinct methods of C did T invoke? A test that
calls 1 method of `ObjectMapper` is a much weaker signal for an
`ObjectMapper` bug than one that calls 12. The agent's
`UsageStore.startTestClass()`/`endTestClass()` already track per-test
state; bumping a counter on each method-entry probe is cheap.

**Where**:
- `test-order-agent` runtime: add per-method counters per test.
- Wire format: extend the binary protocol to carry a small int per dep.
- `DependencyMap`: store weights, not just presence.
- `TestScorer`: use weight in scoring.

**Risk**: high (touches everything). Defer until S1 lands and is
proven insufficient.

### S5. Verification: regression sweep across known-working repos

**Target**: issue §4 — verify the goal-binding fix didn't regress.

**Idea**: Add a CI job (or a `--regression` flag to the script) that
runs `full <repo>` against the working list:
`jackson-databind, commons-collections, commons-text, commons-io,
jsoup, spring-petclinic, logging-log4j2, javaparser, ai-sdk-java`.
Pass criterion: each produces a `test-dependencies.lz4` of expected
size (within ±50% of recorded baseline) and step 7 catches the bug
where it did before.

**Where**:
- `scripts/third_party_test_plan.sh`: add `regression` subcommand.
- A `regression-baselines.json` checked in alongside, with per-repo
  expected index size and bug-caught flag.

**Risk**: low. Pure additive.

### S6. Better diagnosis output in step 7

**Target**: issue §1 (debugging).

When the bug is not caught, the script just prints "not caught." It
should print:
- Which 13 tests were selected.
- Whether the changed class appears in any of their dep sets (it
  almost certainly does — but how high in the score?).
- Top 5 tests by score that *did* exercise the changed class.
- Coverage gap diagnosis: did any test in the suite exercise the
  flipped branch? If not, that's a test-coverage issue, not a
  test-order issue.

**Where**: `scripts/third_party_test_plan.sh`, `find_bug_targets`
follow-up logic; possibly a new CLI subcommand
`test-order:diagnose-selection`.

**Risk**: low. Diagnostic only.

### S7. Per-repo override file (cleaner than `case "$repo"` in script)

**Target**: maintainability of `scripts/third_party_test_plan.sh`.

The script already has per-repo special cases (`detect_compiler_args`,
soon `detect_extra_args` if S3 lands). Move these to
`third-party/<repo>/.test-order-overrides` (or a single
`scripts/third-party-overrides.json`) so the script is data-driven.

**Risk**: low. Pure refactor.

## Suggested ordering

1. ~~S1~~ — done (IDF scoring weight only; write-time pruning won't-do).
2. ~~S5~~ — done (`regression` subcommand added to script).
3. ~~S6~~ — done (step-7 diagnosis: selected tests + top-5 show-order).
4. ~~S3~~ — done (`testorder.disableValidatePlugins=true` in CollectorLifecycleParticipant).
5. S2 (RoaringBitmap) — only if index size becomes a problem again.
6. ~~S7~~ — done (extracted to `scripts/third-party-overrides.sh`).
7. S4 (exercise weight) — last, biggest blast radius.

## More suggestions from today's bug runs — STATUS

S3, S5, S6, S7, S8, S9, S10, S11, S13, S14, S15, S16, S17, S18 have been **implemented**.
S2 (won't-do unless index size is an issue), S4 (deferred, high blast radius), S12 (won't-do, plugin side already correct) remain.

Summary of what was done:
- **S3** — `CollectorLifecycleParticipant.disableValidatePhasePlugins()`: when `-Dtestorder.disableValidatePlugins=true`, moves `xml-maven-plugin` and `spring-javaformat-maven-plugin` validate-phase executions to phase "none".
- **S5** — `regression` subcommand added to `scripts/third_party_test_plan.sh`; runs `full` on `REGRESSION_REPOS` and prints pass/fail summary.
- **S6** — When step 7 doesn't catch the bug, prints selected test names (extracted from log) and top-5 scorers from `show-order`.
- **S7** — `detect_compiler_args` and `detect_package_override` extracted to `scripts/third-party-overrides.sh`, sourced at startup.
- **S8** — Selection log now shows breakdown: "N scored + X new + Y always-run, deferred M".
- **S9** — `SelectOperation` and `ShowOrderWorkflow` now derive a stable seed from `hash(sorted changedClasses)` when none is set. The non-determinism warning is gone.
- **S10** — Bug-caught detection in the script now distinguishes three outcomes: `caught` (test failures), `unknown` (build failed before any `Tests run:` line), `not_caught` (tests ran and all passed). Fixed in both step 7 and `phase_bugs_maven`.
- **S11** — `SelectOperation` warns when any changed class appears in >50% of all test deps: `"Selection signal is weak — results may be near-random."` Fires at selection time, visible in the Maven log.
- **S13** — `IndexCollectorServer.logIndexSize()` logs `Index written: X MB (N tests)` after every write. Warns if >20 MB.
- **S14** — `topN=-1` log now says "all affected tests (topN=-1, running in priority order)".
- **S15** — `find_bug_targets` accepts a TSV dump; scores candidates by `inject_score * 1000 / (df + 1)` to prefer discriminating classes over near-universal utility classes.
- **S16** — New `mvn test-order:explain` goal (`ExplainMojo`): prints per-test score breakdown for the given changed-class set. `-Dtestorder.explain.test=FQCN` for a specific test; `-Dtestorder.explain.topN=N` for top-N (default 10).
- **S17** — `IndexCollectorServer.isSyntheticClass()` filters cglib (`$$EnhancerByCGLIB$$`, `$$FastClassByCGLIB$$`), JDK proxies (`com.sun.proxy.*`, `jdk.proxy*`), lambda forms, Spring cglib (`org.springframework.cglib.*`), and Hibernate repackaged classes (`org.hibernate.repackage.*`) from all dep sets at merge time. Jackson index dropped from 80 MB → 65 MB.
- **S18** — When a candidate class is not in the index, prints the approx number of test entries and whether the source file exists in the repo tree.

After these fixes, jackson-databind's bug is **caught in top-3** (was: not caught).

## More suggestions from today's bug runs

These come from concrete observations in
`target/third-party-results/jackson-databind/20260529_001213/` —
review those logs alongside if any of these need clarification.

### S8. Honor `testorder.affected.topN` strictly (or rename it)

**Observation**: step 7 invokes
`mvn test-order:affected test -Dtestorder.affected.topN=3
-Dtestorder.changed.classes=tools.jackson.databind.introspect.POJOPropertiesCollector`.
The log says **"Selected 13 tests, deferred 722"** — 13, not 3.

**Hypothesis**: `topN=3` is treated as "top 3 *score classes*" not
"top 3 *tests*", or always-run classes / alwaysSelect rules add
extras, or `topN` means "top 3 plus ties." The user can't tell from
the output.

**Where**: `test-order-core/src/main/java/me/bechberger/testorder/ops/SelectOperation.java`
and `TestSelector` (look for `topN` usage). Compare to
`SelectMojo.java` log "To run only the top N…".

**Action**:
- Either honor topN strictly (cap final list at N + alwaysRun) —
- Or rename the property to `testorder.affected.minTopN` / similar so
  the name reflects "*at least* N" semantics.
- Either way: log the breakdown. e.g.:
  `[test-order] topN=3 → 3 affected (top scores) + 8 ties + 2 always-run = 13 total`.

**Risk**: low (presentation/contract). Don't *silently* change
selection counts — bump a minor version if behavior changes.

### S9. Default seed = stable hash (silence the non-determinism warning in CI)

**Observation**: every full run logs:
```
[WARNING] [test-order] Selection is non-deterministic (no seed set).
                       Set testorder.affected.seed for reproducible CI runs.
```
The full pipeline emits this 6+ times. It's noise unless the user
acted on it — but the user can't reasonably set a seed for every
invocation.

**Idea**: when no seed is set, derive one from a stable input — e.g.
`hash(changedClasses ∪ indexFile.mtime)` or just `0`. Log "seed
derived from changed-set" instead of warning. Add the property
`testorder.affected.requireExplicitSeed=true` for users who want the
warning back (CI environments that pin reproducibility).

**Where**:
`test-order-core/src/main/java/me/bechberger/testorder/ops/SelectOperation.java:55-60`.

**Risk**: low. Warning was advisory.

### S10. Verify `bug-caught` correctly when surefire returns failures

**Observation**: the "bug caught" check in
`scripts/third_party_test_plan.sh` greps for
`Tests run:.*Failures: [^0]` in the selected-tests log. This is
brittle — a test that fails with `Errors: 1, Failures: 0` will be
counted (good), but a build that *fails to compile* and never runs
tests gets reported as "bug not caught" (misleading: we don't know
if the bug would have been caught).

**Action**: distinguish three outcomes in the script:
- `caught` — at least one test failed/errored
- `not_caught` — all selected tests passed
- `unknown` — build failed before tests ran (e.g. compilation error)

**Where**: `phase_full_maven` step 7 in `scripts/third_party_test_plan.sh`.

**Risk**: low. Diagnostic only.

### S11. Treat "near-universal dep matches" as a yellow signal

**Observation**: in jackson-databind, the changed class
(`POJOPropertiesCollector`) has **601/735 = 82% of tests as
'dependents'**. The plugin happily selects 13 of those 601 — in
practice that's a coin flip from the user's POV.

**Idea**: in `SelectOperation`, after computing scores, log a
yellow/orange flag if the changed class has `dependents > 50% of
tests`:
```
[test-order] WARNING: changed class POJOPropertiesCollector is touched
            by 601/735 (82%) of tests. Selection signal is weak; results
            may be near-random. Consider running the full suite or
            inspecting whether this class should be split.
```

This gives the user a way to *trust or distrust the result* without
reading the index manually.

**Where**: `SelectOperation.java`, after building the affected set.

**Risk**: low. Logging.

### S12. Snap the reactor index path to a stable location

**Observation**: jackson-databind is single-module, so
`.test-order/test-dependencies.lz4` lands at the project root —
fine. For multi-module repos (commons-text, log4j, javaparser),
the index lands inside the module that ran tests:
`.test-order/test-dependencies.lz4` *inside* that module dir.

The script has a complex `idx_dir`/`idx_module` dance to find
where the index landed (script lines ~840). This is brittle: if
two modules each produce indices, the script picks one
non-deterministically.

**Action**: write the merged index to a single, stable path —
`<reactor-root>/.test-order/test-dependencies.lz4` — regardless of
which module(s) were instrumented. Multi-module already has
`afterProjectsRead`'s reactor-reorder reading from there.

**Where**:
- `IndexCollectorServer.stopAndMerge(Path)` — caller in
  `AbstractTestOrderMojo` decides the path; have it default to
  reactor root.
- Update `CollectorLifecycleParticipant.tryReorderReactor` (already
  reads from reactor-root) — already correct.
- Migration: read both old and new locations on load; warn if old.

**Risk**: medium (path change is user-visible). Coordinate with §S5
regression baselines.

### S13. Index-write progress / size warning

**Observation**: the jackson-databind index ballooned to 80 MB
silently. The plugin gave no warning about size. CI users may not
notice until they push 80 MB into git.

**Action**: log the index size after write:
```
[test-order] Index written: 80.1 MB (735 tests, 220 avg deps/test, 161,992 tuples).
            For repos with >50% near-universal deps, set
            -Dtestorder.deps.dropFrequencyThreshold=0.8 to reduce.
```
And a hard warning for >100 MB or >50% over the 30-day moving
average for that index file.

**Where**: `IndexCollectorServer.stopAndMerge` after the write.

**Risk**: low. Log-only.

### S14. Document that `topN=-1` means "all affected, not all tests"

**Observation**: `HelpMojo` says `-Dtestorder.affected.topN=<n>
(default: -1, all affected)`. From the log "Selected 13, deferred
722" the user sees the deferred number is what would be skipped.
Two interpretations sources of confusion:
- "all affected" = all tests *touching* changed classes = 601 (per
  the dep map).
- "13 selected" = ?  (different from 601).

**Action**: doc + log a clearer breakdown. See also §S8.

**Risk**: low.

### S15. `find_bug_targets` should prefer classes WITH discriminating coverage

**Observation**: the script picks bug-injection targets by walking
the source tree and looking for "good" injection points. It should
*prefer classes that have test methods exercising the injected
branch*. Otherwise step 7 fails for a coverage reason, not a
test-order reason — and the user blames test-order.

**Action**: in `find_bug_targets` (script line ~293), filter
candidates to classes whose dep-graph has at least one test where
the changed class is in the **top quartile** of that test's deps
(a heuristic for "this test really uses this class"). Skip classes
that only appear as transitive deps in 90%+ of tests.

This requires the script to read the index. Either:
- After the learn phase, run `mvn test-order:dump
  -Dtestorder.dump.format=json -Dtestorder.dump.output=tmp.json`
  and parse it (8MB JSON for jackson — fast in Python/jq).
- Or add a new plugin goal `test-order:suggest-bug-targets` that
  emits N classes ranked by "discriminating power."

**Where**: `scripts/third_party_test_plan.sh:293` and possibly a
new mojo.

**Risk**: medium. Changes which bugs we try.

### S16. Add an `--explain SELECTED_TEST CHANGED_CLASS` debugger goal

**Observation**: when investigating "why didn't selection catch the
bug?", I had to: dump JSON, grep for the changed class, count
appearances, manually compare scores. This is a 5-minute task per
investigation. A targeted explain command would make it 5 seconds:

```
$ mvn test-order:explain \
    -Dtestorder.explain.test=tools.jackson.databind.deser.AnySetterTest \
    -Dtestorder.explain.changedClasses=tools.jackson.databind.introspect.POJOPropertiesCollector
[test-order] AnySetterTest score for changed-set {POJOPropertiesCollector}:
  base = 1.0 (POJOPropertiesCollector ∈ deps)
  idf  = 0.27 (601/735 tests touch this class — low signal)
  weight = 0.27
  rank = 47/735 (top 6%)
  top 5 by score:
    1. tools.jackson.databind.introspect.POJOPropertiesCollectorTest (5.2)
    2. ... (etc)
```

**Where**: new `ExplainMojo` + operation. The data is all in the
index already.

**Risk**: low (additive).

### S17. Make `testorder.includePackages` filter the *target* class

**Observation**: today setting `-Dtestorder.includePackages=tools.jackson.databind`
only filters which test classes get instrumented (and which test
classes report deps). It does NOT filter the *deps themselves*: even
with that filter set, every test reports deps on `java.util.List`
and other JDK classes — wait, actually do JDK classes appear?

Let me re-check from `/tmp/jackson-dump.json`: the dump showed
`tools.jackson.*` and a few `org.springframework.cglib` /
`org.hibernate.repackage` entries, but no JDK classes. So filter
DOES apply to deps already. Good.

But the `org.springframework.cglib`, `org.hibernate.repackage`
entries are leaks — they shouldn't be in jackson-databind's index.
These are runtime-generated proxy classes the agent couldn't
attribute to a single source.

**Action**: add `excludePackages` defaults that catch known proxy
namespaces:
```
testorder.excludePackages.default = \
    org.springframework.cglib.*,\
    org.hibernate.repackage.*,\
    com.sun.proxy.*,\
    jdk.proxy*,\
    java.lang.invoke.LambdaForm$*,\
    *$$EnhancerByCGLIB$$*,\
    *$$Lambda$*
```

Document the override path.

**Where**:
`test-order-agent/src/main/java/me/bechberger/testorder/agent/Agent.java`
or `OfflineInstrumentor.java`.

**Risk**: low. Filter is opt-out.

### S18. Surface "tests not in index" prominently

**Observation**: from memory, several repos have the failure mode
"step 7 warns — `find_bug_targets` not in index." The current
warning says `Bug class X not in dependency index (trying next
target)` — which suggests the bug-injection target wasn't in the
index, but the user can't tell *why*: was instrumentation skipped
for that package? Was the class loaded but never touched by tests?
Was the test runner skipped?

**Action**: when emitting that warning, also print:
- Number of test classes in the index.
- Number of distinct dep classes in the index.
- Whether the class is anywhere in the JAR (file-system check).
- Whether any test was instrumented in the same package as the
  target.

**Where**: `phase_full_maven` step 7 in
`scripts/third_party_test_plan.sh`, or the underlying plugin.

**Risk**: low. Diagnostic.

## Suggested ordering (combined)

Original ordering still holds for S2–S7. S1 is done. Insertions:

- **S5** (regression sweep) is now the starting point.
- After **S5**, add **S10** (caught vs unknown) — same script.
- After **S6** (diagnose-selection in script), add **S16**
  (`mvn test-order:explain`) — same diagnostic motivation, plugin-side
  version.
- **S9** (default seed) and **S13** (size warning) are done.
- **S12** (stable index path) needs S5 baselines first.
- **S15** (better bug targets) and **S17** (proxy excludes) clean
  up the script's failure modes; run after S5.
- **S8** and **S14** are small contract/doc fixes; ship anytime.

## UX round-2 follow-ups (deferred)

Captured during the round-2 UX pass (auto decision logging, end-of-run summary,
property rename, Gradle parity, sample READMEs). These are out of scope for
that round but worth tracking.

- **Schema/version migration messaging** — `TestOrderState.java:1062-1065` and
  `StateDowngradeException` throw cryptic errors when an older state file is
  loaded. Translate to actionable guidance ("delete `.test-order/` and re-run
  learn mode" or "downgrade not supported").
- **`DiagnosticReportPrinter` sort by severity** — currently prints findings
  in detection order. Group/sort by severity (ERROR → WARN → INFO) so the
  most actionable issues are at the top.
- **Dashboard empty-state messaging** — `DashboardGenerator.java:572` renders
  an empty dashboard when no data is present. Show a clear "no runs recorded
  yet — run `mvn test-order:auto test`" panel instead.
- **Multi-module reactor `-am` hint** — `AbstractTestOrderMojo.java:597-600`
  detects single-module invocation in a multi-module reactor; the message
  should explicitly mention `-am`/`-amd` so users know how to bring in
  upstream/downstream modules.
- **Property naming audit beyond `select` namespace** — `tiered.*`,
  `score.*`, `auto.*`, and various camelCase legacy keys still drift in
  style. Pick one convention (probably dotted lower-case), document it, and
  rename the rest in a follow-up wave.
