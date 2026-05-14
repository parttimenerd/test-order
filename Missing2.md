# Remaining Unfixed Issues

Issues from MISSING.md that are **not yet implemented** in the codebase.  
Categorized as: **Bug**, **Improvement**, or **Documentation**.

---

## Bugs

### ~~R7-10: Gradle `testOrderLearn` duplicate `--enable-native-access` flag~~ [FIXED]
- **Category**: Bug
- **Status**: Fixed — native access handling code removed from Gradle plugin entirely.

### R10-4: State file schema downgrade throws with no recovery path
- **Category**: Bug
- **File**: `StateMigrations.java`
- **Issue**: When a user downgrades plugin version (newer state schema → older plugin), `migrate()` throws `StateDowngradeException`. No backup exists and no recovery guidance is provided beyond the error message.
- **Impact**: Plugin rollbacks are destructive — user must manually delete `.test-order/state.lz4` and lose all learning history.
- **Fix**: Create a timestamped backup before any migration. On downgrade detection, emit: "State file was written by a newer plugin version. Run `test-order:clean` to reset, or upgrade the plugin back."

### ~~R10-9: Failsafe integration incomplete — `argLine` property name differs~~ [FIXED]
- **Category**: Bug
- **Status**: Fixed — `detectArgLinePropertyName()` added to `AbstractTestOrderMojo.java` to parse `${}` and `@{}` patterns and detect Failsafe's custom argLine property.

### ~~R14-15: Windows path serialization issue in orderer config~~ [FIXED]
- **Category**: Bug
- **Status**: Fixed — `escapePropertyValue()` added to `AbstractTestOrderMojo.java` to escape backslashes before writing `.properties` files.

### R15-2: Multi-module `select` runs at parent AND each sub-module — parent selection is ignored
- **Category**: Bug
- **File**: `SelectMojo.java`
- **Issue**: In multi-module projects, parent-level `select` is informational only — sub-modules ignore it and do their own selection. `topN=1` at parent defers a test, but the child module re-selects it.
- **Impact**: Users think they're deferring tests but they all run.
- **Fix**: Propagate parent's selection decisions to child modules, skip sub-module select when parent already ran, or document that `select` must target a specific module with `-pl`.

### ~~R16-5: `testorder.skip=true` silently skips ALL goals including explicitly CLI-invoked ones~~ [FIXED]
- **Category**: Bug
- **Status**: Fixed — `isExplicitlyInvokedOnCli()` check added to `AbstractTestOrderMojo.java`; CLI-invoked goals now override `skip=true` with an info message.

### R17-1: `forkCount > 1` causes duplicated change-detection messages and incorrect test-ran summary
- **Category**: Bug
- **File**: `PriorityClassOrderer.java`
- **Issue**: With `forkCount=2+`, each fork JVM runs its own `PriorityClassOrderer` instance. Static `AtomicBoolean` guards don't span JVMs. Results in repeated log messages and missing/incorrect summary counts.
- **Impact**: Confusing output with multi-fork Surefire.
- **Fix**: Move change-detection logging to the Maven mojo (pre-fork). Summary should aggregate results across all forks.

### ~~R18-13: `autoRunRemaining=true` doesn't actually auto-run remaining tests~~ [FIXED]
- **Category**: Bug
- **Status**: Fixed — when `runRemaining=true`, `AutoMojo` now runs ALL tests in scored order rather than deferring.

---

## Improvements

### R8-1: Agent instrumentation breaks Multi-Release JAR projects [CRITICAL]
- **Category**: Improvement (architecture)
- **File**: Agent / `AbstractTestOrderMojo.java`
- **Issue**: The agent's classloading interception prevents the JVM from resolving MRJAR classes from `META-INF/versions/11/`. Causes `ClassNotFoundException` in projects like jsoup.
- **Impact**: Any project using MR-JARs has test failures in learn mode.
- **Fix**: Agent should detect MRJAR projects and skip instrumentation of multi-release classes, or use a classloader strategy that preserves MR resolution logic.

### R8-2: `maven.test.additionalClasspath` silently conflicts with Surefire XML `<additionalClasspathElements>`
- **Category**: Improvement
- **File**: `AbstractTestOrderMojo.java`
- **Issue**: When a project already declares `<additionalClasspathElements>` in Surefire XML, both sources merge unpredictably. No conflict detection or warning.
- **Impact**: Projects with existing Surefire classpath configuration get broken silently.
- **Fix**: Detect existing `<additionalClasspathElements>` in Surefire config and either merge or warn.

### R8-3: Agent-induced test failures permanently pollute state weights
- **Category**: Improvement
- **File**: `TestOrderState.java`
- **Issue**: If the agent CAUSES failures (e.g., MR-JAR), these are recorded as real failures. No rollback mechanism.
- **Impact**: One bad learn run permanently biases the state.
- **Fix**: Provide `mvn test-order:drop-last-run` or detect `ClassNotFoundException`/`NoClassDefFoundError` as suspect failures.

### R8-10: Gradle per-task mode override leaks between parallel tasks
- **Category**: Improvement
- **File**: `TestOrderPlugin.java`
- **Issue**: Per-task mode resolution reads JVM system properties. In parallel Gradle builds, one task's mode override can be seen by another task's configuration closure.
- **Impact**: Incorrect mode selection in parallel builds.
- **Fix**: Use Gradle project properties or task-local extra properties.

### R8-11: State file accumulates noise from environmental failures
- **Category**: Improvement
- **File**: `TestOrderState.java`
- **Issue**: All test run records are permanently recorded. Network timeouts, Docker failures, OOM kills all get recorded as "test failures."
- **Impact**: Over time, state becomes noisy and biases weights.
- **Fix**: Add `mvn test-order:drop-run --last` to remove entries. Or add decay/expiry.

### R9-9: Gradle `testOrderDashboard` error message unhelpful when index is missing
- **Category**: Improvement
- **File**: `TestOrderPlugin.java`
- **Issue**: When index doesn't exist, Gradle dashboard task just says "Failed to generate test-order dashboard" — root cause only visible with `--stacktrace`.
- **Fix**: Catch IOException and extract message: "[test-order] Dashboard generation failed: <cause>. Run ./gradlew test first."

### R10-1: Gradle plugin incompatible with `--configuration-cache` [HIGH]
- **Category**: Improvement (architecture)
- **File**: `TestOrderPlugin.java`
- **Issue**: 28 `doFirst`/`doLast` closures capture `project` references from configuration time. Configuration cache requires task actions to not reference `Project` at execution time.
- **Impact**: `./gradlew test --configuration-cache` fails.
- **Fix**: Extract all `project`-dependent values at configuration time into serializable fields. Use `Provider` API.

### R10-8: Gradle `--tests` filter + test-order select creates confusing double-filter
- **Category**: Improvement
- **File**: `TestOrderPlugin.java`
- **Issue**: If user passes `--tests MyTest` on CLI, Gradle applies BOTH filters (intersection). Only tests matching both test-order's selection AND the `--tests` pattern run.
- **Impact**: Empty test run with no warning if test isn't in selected set.
- **Fix**: Detect `--tests` and either skip test-order filtering with a warning, or merge patterns.

### R10-13: Gradle learn mode doesn't detect `forkEvery` setting
- **Category**: Improvement
- **File**: `TestOrderPlugin.java`
- **Issue**: When `forkEvery` is dynamically modified in a task action that runs AFTER learn configuration, agent may not attach to all forks.
- **Impact**: Edge case — deps may be missed for some test classes.
- **Fix**: Warn if `forkEvery` is non-default.

### R12-3: No guidance when test reordering reveals order-dependent failures
- **Category**: Improvement
- **File**: Plugin output
- **Issue**: When tests fail after reordering, no message explains this is the plugin *working as intended* — it discovered order-dependent tests.
- **Impact**: Users think the plugin broke their build.
- **Fix**: Add INFO: "Tests that fail only under reordering may have hidden order-dependencies. Run `mvn test-order:diagnose`."

### R12-6: `AggregateMojo` reads from single module's depsDir only
- **Category**: Improvement
- **File**: `AggregateMojo.java`
- **Issue**: Marked `@Mojo(aggregator = true)` but `ctx.resolveDepsDir(depsDir)` resolves to a single directory. Multi-module projects where each module writes .deps to its own `target/test-order-deps/` get an incomplete index.
- **Impact**: Multi-module projects without shared `depsDir` get incomplete dependency index.
- **Fix**: Iterate `session.getProjects()` and aggregate from each module's resolved depsDir.

### R12-7: `run-remaining` hint uses absolute filesystem path
- **Category**: Improvement
- **File**: `SelectMojo.java`
- **Issue**: Warning message shows absolute path `/Users/.../target/test-order-remaining.txt` which is machine-specific.
- **Impact**: Copy-pasting into CI scripts won't work.
- **Fix**: Show relative or `${project.build.directory}`-based path.

### R12-9: `optimize` gives no explanation of WHY weights changed
- **Category**: Improvement
- **File**: `OptimizeOperation.java`
- **Issue**: Output says `Optimised weights: ... speedPenalty=0` but doesn't explain why.
- **Impact**: Users can't evaluate whether optimization makes sense.
- **Fix**: For each weight that changed, add brief reason.

### R12-11: `diagnose` doesn't detect agent/MRJAR incompatibility or all-failures history
- **Category**: Improvement
- **File**: `DiagnosticOperation.java`
- **Issue**: No check for all recorded runs having failures (possible permanent breakage) or stale index entries.
- **Impact**: 100% healthy score while plugin is actively breaking tests.
- **Fix**: Add checks: "All N runs have failures" and "M index entries reference non-existent test classes."

### R12-13: `show-order` [SLOW] threshold undocumented and not configurable
- **Category**: Improvement
- **File**: `ShowOrderWorkflow.java`
- **Issue**: Tests marked `[SLOW]` at 39ms — threshold is opaque and non-configurable.
- **Fix**: Document threshold and make configurable: `-Dtestorder.show.slowThreshold=100`.

### R13-3: HelpMojo ignores `-Dgoal=` and `-Ddetail=` parameters
- **Category**: Improvement
- **File**: `HelpMojo.java`
- **Issue**: Standard Maven help mojos support per-goal detail mode. This one dumps everything.
- **Fix**: Support `-Dgoal=<name>` filtering.

### R13-4: `serve` goal property naming non-obvious (`testorder.dashboard.port` not `testorder.serve.port`)
- **Category**: Improvement
- **File**: `ServeDashboardMojo.java`, `PropertySuggestion.java`
- **Issue**: Users try `testorder.serve.port` but the actual property is `testorder.dashboard.port`.
- **Fix**: Add `testorder.serve.port` as recognized alias.

### R13-5: Multi-module `auto` mode skips learning for later modules
- **Category**: Improvement
- **File**: `PrepareMojo.java`
- **Issue**: In multi-module reactor, second module shows "No changed classes" (implying order mode) when it's actually still learning via shared agent.
- **Fix**: Log "Module 'web' — learning alongside existing index" instead of misleading message.

### R13-6: `debug=true` adds no extra output to `show-order`
- **Category**: Improvement
- **File**: `ShowOrderMojo.java`
- **Issue**: Users expect debug to enable verbose scoring but must discover separate `explain` property.
- **Fix**: When `debug=true`, auto-enable `explain` mode.

### R13-7: Property naming inconsistency — camelCase vs dot-separated
- **Category**: Improvement
- **File**: Various
- **Issue**: Mix of `testorder.showOrder.explain` (camelCase) vs `testorder.select.topN` (dot-separated).
- **Impact**: Users can't predict naming convention.
- **Fix**: Standardize on dot-separated and keep camelCase as deprecated aliases.

### R13-9: `show-order` negative scores unexplained in table view
- **Category**: Improvement
- **File**: `OrderReportPrinter.java`
- **Issue**: Score `-1` shown without reason in table. Only explain mode reveals speed penalty.
- **Fix**: Append brief tag for negative scores (e.g., `-1 [speed]`).

### R14-1: `download` mojo error message always generic
- **Category**: Improvement
- **File**: `DownloadMojo.java`
- **Issue**: Whether YAML is malformed, missing fields, or wrong provider — error is always generic.
- **Fix**: Surface specific `CiConfigException` reason.

### R14-2/R14-3: Gradle `testOrderDiagnose` on fresh project reports misleading "Permission denied" / 40% CRITICAL
- **Category**: Improvement
- **File**: `TestOrderPlugin.java`, `DiagnosticOperation.java`
- **Issue**: Fresh project gets error code 1108 "Permission denied" (should be "not yet created") and 40% CRITICAL health score for a normal state.
- **Fix**: Detect "no previous runs" scenario and report "Not yet initialized."

### R14-5: `randomM` shortfall not warned
- **Category**: Improvement
- **File**: `SelectOperation.java` / `TestSelector.java`
- **Issue**: `randomM=100` with only 3 candidates silently returns 3.
- **Fix**: Warn when requested count can't be fulfilled.

### R14-6: `topN=-1` makes `randomM` a silent no-op
- **Category**: Improvement
- **File**: `SelectOperation.java`
- **Issue**: With `topN=-1` (all selected), `randomM` does nothing. No warning.
- **Fix**: Warn if randomM > 0 and topN=-1.

### R14-12: Gradle `testOrderExportJson` outputs to stdout only
- **Category**: Improvement
- **File**: `TestOrderPlugin.java`
- **Issue**: No file output option for Gradle export JSON. Must pipe stdout.
- **Fix**: Support `testorder.export.outputFile` property.

### R14-13: Gradle `testOrderClean` doesn't invalidate `test` task
- **Category**: Improvement
- **File**: `TestOrderPlugin.java`
- **Issue**: After clean, `test` task shows UP-TO-DATE. User must pass `--rerun`.
- **Fix**: Invalidate test task outputs after clean.

### R15-6: `select` + `run-remaining` same command shows misleading "will NOT run" warning
- **Category**: Improvement
- **File**: `SelectMojo.java`
- **Issue**: Warning is technically accurate for select alone but misleading when run-remaining follows.
- **Fix**: Detect when `run-remaining` is also in goal list and adjust message.

### R15-7: Multi-module `tiered-select` produces redundant/conflicting tier file sets
- **Category**: Improvement
- **File**: `TieredSelectMojo.java`
- **Issue**: Parent and sub-modules produce different tier files. Unclear which is authoritative.
- **Fix**: Document which tier files are authoritative, or skip sub-module tier-select when parent ran.

### R15-11: `select` on multi-module parent warns "not going to run" even when sub-modules will
- **Category**: Improvement
- **File**: `SelectMojo.java`
- **Issue**: False warning on POM-packaging modules. Check looks at module goals, not reactor goals.
- **Fix**: Don't emit warning for POM-packaging modules.

### R16-1: Debug mode shows triple scoring for each test class
- **Category**: Improvement
- **File**: `PriorityClassOrderer.java` / scoring pipeline
- **Issue**: With `debug=true` on sample-shop (3 tests), outputs 9 score lines.
- **Fix**: Deduplicate scoring invocations or label each pass.

### R16-4: Ordering overhead runs even when user filters to a single test
- **Category**: Improvement
- **File**: `PriorityClassOrderer.java`
- **Issue**: `-Dtest=CartTest` still scores ALL indexed tests.
- **Fix**: When `-Dtest` is set, skip ordering or limit to filtered set.

### R16-9: Chaining `select + run-remaining + test` loses selected tests
- **Category**: Improvement
- **File**: `RunRemainingMojo.java`
- **Issue**: `run-remaining` overrides Surefire filter. Selected tests never run.
- **Fix**: Detect both goals in same execution and warn/merge, or document limitation.

### R17-6: Gradle plugin incompatible with configuration cache
- **Category**: Improvement (same as R10-1)
- **Impact**: Critical for Gradle 8.1+ users.

### R17-7: Gradle property names case-sensitive with no typo detection for casing
- **Category**: Improvement
- **File**: `TestOrderPlugin.java`
- **Issue**: `-PtestOrder.mode=learn` (capital O) is silently ignored.
- **Fix**: Detect `testOrder.*` properties and suggest `testorder.*`.

### R17-8: Gradle has no `testOrderMetrics` or `testOrderHelp` task
- **Category**: Improvement
- **File**: `TestOrderPlugin.java`
- **Issue**: Feature parity gap vs Maven (26 goals vs 21 tasks).
- **Fix**: Add `testOrderMetrics` and `testOrderHelp` tasks.

### R17-12: Explicit `mvn test-order:prepare test` causes prepare to run twice
- **Category**: Improvement
- **File**: `PrepareMojo.java`
- **Issue**: CLI `prepare` + lifecycle-bound `prepare` both execute. No deduplication.
- **Fix**: Apply same deduplication logic used by `learn`/`auto`.

### R18-11: `download` goal error says "falling back to local" then fails anyway
- **Category**: Improvement
- **File**: `DownloadMojo.java`
- **Issue**: Warning says graceful fallback, then ERROR crashes. Contradictory.
- **Fix**: Either actually fall back (use local index), or remove "falling back" from message.

### R18-12: `show-order` on multi-module parent shows combined cross-framework ranking
- **Category**: Improvement
- **File**: `ShowOrderMojo.java`
- **Issue**: Combines JUnit 5 and TestNG module rankings into a single list that will never be the actual execution order.
- **Fix**: Show per-module rankings separately or add disclaimer.

---

## Documentation

### M5: Explicit mode parameter names confusing
- **Category**: Documentation
- **File**: `AbstractTestOrderMojo.java`
- **Issue**: `changedClasses` vs `changedTestClasses` relationship not intuitive.
- **Fix**: Add JavaDoc clarifying semantics.

### R8-12: Property precedence (CLI vs POM) undocumented
- **Category**: Documentation
- **File**: `PrepareMojo.java`, README
- **Issue**: CLI `-D` always overrides POM. This is not documented.
- **Fix**: Document in `help` output and README.

### R11-1: README `changeMode` default claimed as `auto` — code defaults to `uncommitted`
- **Category**: Documentation
- **File**: `README.md`
- **Fix**: Change README table to `| uncommitted (default) |`.

### R11-2: README explicit mode property name is wrong
- **Category**: Documentation
- **File**: `README.md`
- **Issue**: Says `testorder.changedClasses` but actual property is `testorder.changed.classes`.
- **Fix**: Correct property name in README.

### R11-3: README `.test-order/` commit advice overly broad
- **Category**: Documentation
- **File**: `README.md`
- **Issue**: Says commit entire `.test-order/` — but `hashes.lz4` and `state.lz4` shouldn't be committed.
- **Fix**: Break into granular rows per file.

### R11-4: README `since-last-commit` described as "Local development" — contradicts CLI_REFERENCE
- **Category**: Documentation
- **File**: `README.md`, `docs/CLI_REFERENCE.md`
- **Fix**: Change README to "Best for: CI/branch validation."

### R11-5: `auto` changeMode Javadoc says "alias for uncommitted" — code resolves differently
- **Category**: Documentation
- **File**: `AbstractTestOrderMojo.java`
- **Fix**: Change Javadoc to accurate description of auto resolution logic.

### R11-6: docs/README.md index lists only 3 of 8 documentation files
- **Category**: Documentation
- **File**: `docs/README.md`
- **Fix**: Add all 8 documents to index.

### R11-7: docs/ci-examples/README.md says `changeMode` default is `auto`
- **Category**: Documentation
- **File**: `docs/ci-examples/README.md`
- **Fix**: Change to `uncommitted`.

### R11-8: Gradle README Tasks table lists 10 of 25 registered tasks
- **Category**: Documentation
- **File**: `test-order-gradle-plugin/README.md`
- **Fix**: Add at least 8 most important missing tasks.

### R11-9: Gradle README doesn't mention TestNG support
- **Category**: Documentation
- **File**: `test-order-gradle-plugin/README.md`
- **Fix**: Add TestNG to compatibility line.

### R11-10: Gradle README mode DSL example omits `optimize` mode
- **Category**: Documentation
- **File**: `test-order-gradle-plugin/README.md`
- **Fix**: Add `| "optimize"` to mode comment.

### R11-11: Gradle README "Requires Gradle 7.6+" with no runtime check
- **Category**: Documentation + Improvement
- **File**: `test-order-gradle-plugin/README.md`, `TestOrderPlugin.java`
- **Fix**: Add Gradle version check in `apply()`.

### R11-12: CLI_REFERENCE.md goals table missing `metrics` goal
- **Category**: Documentation
- **File**: `docs/CLI_REFERENCE.md`
- **Fix**: Add metrics goal to table.

### R11-16: Main README doesn't document `@TestOrder` annotation
- **Category**: Documentation
- **File**: `README.md`
- **Fix**: Add subsection for `@TestOrder`.

### R13-8: Gradle `showOrder` includes select preview but Maven doesn't (without explain)
- **Category**: Documentation / Improvement
- **Issue**: Feature parity gap in default output.
- **Fix**: Always include select preview in Maven `show-order` output.

### CP3: Package instrumentation feedback inconsistent (Maven vs Gradle)
- **Category**: Documentation / Improvement
- **Issue**: Gradle logs detected packages at lifecycle level; Maven only at debug.
- **Fix**: Add INFO log in Maven's `configureLearnMode()`.

### CP4: Auto-learn threshold documentation inconsistent
- **Category**: Documentation
- **Issue**: Maven and Gradle have different explanations.
- **Fix**: Unify documentation.

---

## Summary

| Category | Count |
|----------|-------|
| Bugs | 8 |
| Improvements | 37 |
| Documentation | 17 |
| **Total** | **62** |

### High-Priority Items

1. **R8-1**: MR-JAR agent compatibility (blocks adoption on real projects)
2. **R10-1/R17-6**: Gradle configuration cache (blocks Gradle 8.1+ users)
3. **R17-1**: forkCount>1 duplicated messages (common CI scenario)
4. **R15-2**: Multi-module select propagation (affects all multi-module users)
5. **R16-5**: skip=true blocks CLI goals (unexpected behavior)
6. **R14-15**: Windows path serialization (blocks Windows users)
7. **R10-9**: Failsafe argLine (blocks Failsafe-only projects)
8. **R18-13**: autoRunRemaining misleading (API contract violation)
