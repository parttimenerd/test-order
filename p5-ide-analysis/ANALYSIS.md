# Phase 5 - IDE Integration Bug Analysis

## Critical IDE-Specific Issues Identified

### Issue 1: Configuration Path Resolution in IDE Context
**Location:** PriorityClassOrderer.getConfig() + TelemetryListener initialization
**Problem:** 
- testorder.index.path and testorder.state.path are SYSTEM PROPERTIES ONLY
- No IDE-specific path resolution
- When IDE runs tests (e.g., via IntelliJ gutter), system properties may not be set
- IDE may run from different working directory
- Relative paths become invalid in IDE context

**IDE Impact:**
- IntelliJ Run Configurations: Missing absolute paths → orderer disabled silently
- Eclipse TestRunner: Different working directory → cache not found
- VS Code Test Explorer: Relative paths don't resolve

### Issue 2: Classpath Order in IDE
**Location:** Dependencies loaded at orderer initialization
**Problem:**
- IDE test runner constructs classpath differently than Maven CLI
- IntelliJ: Dependencies from IDE modules, not .m2 repository
- Eclipse: Different dependency resolution order
- Compiled output directories vary (out/ vs target/)

**IDE Impact:**
- Test discovery may differ from CLI
- DependencyMap may not load correctly in IDE
- Cache may have different contents

### Issue 3: Cache Contamination from IDE Artifacts
**Location:** DependencyMap initialization
**Problem:**
- IDE-generated output directories (e.g., IntelliJ's out/ dir, Eclipse bin/)
- TestOrderListener scans ALL classpath entries
- Artifacts from IDE compilation can be included in cache
- IDE rebuild → cache becomes inconsistent

**IDE Impact:**
- IntelliJ IDEA: out/test/classes and out/production/classes contaminate cache
- Multiple compilations → cache inconsistency
- P5-034 existing bug validates this

### Issue 4: Configuration Properties File Loading
**Location:** PriorityClassOrderer.getConfig()
**Problem:**
- testorder-config.properties loaded from classpath
- IDE may not include this file on test classpath
- No fallback if not found, silently fails
- No warning if configuration missing

**IDE Impact:**
- IDE runs: Configuration silently ignored
- Difficult to debug why test-order doesn't work
- Different behavior between IDE and CLI

### Issue 5: TelemetryListener Initialization in IDE
**Location:** TelemetryListener.testPlanExecutionStarted()
**Problem:**
- UsageStore initialization uses reflection
- AgentTelemetry attachment in IDE context might fail
- No error message if agent not attached
- Silent degradation makes debugging hard

**IDE Impact:**
- IDE runs: Agent telemetry may not work
- No visible error, just silently degraded
- Hard to debug: tests run but no learning happens

### Issue 6: State File Path Handling
**Location:** TelemetryListener using statePath from system property
**Problem:**
- IDE doesn't automatically set testorder.state.path
- Each IDE instance might use different temporary directory
- Windows paths vs Unix paths in IDE configs
- Maven working directory != IDE run directory

**IDE Impact:**
- IDE runs: State file not persisted
- Cache between IDE runs not used
- Performance impact: learning happens in every run

### Issue 7: IDE-Specific Working Directory
**Location:** File resolution for .test-order directory
**Problem:**
- Maven: ${project.basedir}/.test-order
- IDE: May run from module root, project root, or custom working directory
- Relative path resolution differs in IDE
- IDE rebuild changes working directory

**IDE Impact:**
- .test-order cache created in wrong location
- Different location per IDE run
- Cache never reused

### Issue 8: IDE Plugin Classpath Isolation
**Location:** Test execution classpath construction
**Problem:**
- IDE test runner adds extra entries (instrumentation, coverage, profiling)
- Order of classpath entries may differ from Maven
- IDE plugins may modify classpath at runtime
- test-order may load wrong DependencyMap

**IDE Impact:**
- Classpath order: Different between IDE and CLI
- DependencyMap loading: May fail or use wrong version
- Coverage tools (JaCoCo) may interfere with test-order

### Issue 9: Dynamic Class Loading in IDE
**Location:** Class/Method discovery in IDE context
**Problem:**
- IDE hot-reload may affect test discovery
- IDE may cache reflection results
- Instrumentation may not work with IDE's dynamic loading
- Breakpoint debugging with instrumentation

**IDE Impact:**
- IntelliJ: Step-debugging with test-order instrumentation may fail
- Eclipse: Dynamic class discovery may not detect all tests
- VS Code: Extension may not discover tests after changes

### Issue 10: Configuration Override in IDE Run Configs
**Location:** No IDE-native configuration support
**Problem:**
- Maven/Gradle: Configuration in pom.xml / build.gradle
- IDE: Run configurations stored in IDE-specific format
- No mechanism for IDE to pass test-order config
- Users can't easily configure test-order in IDE

**IDE Impact:**
- IntelliJ: Can't set test-order weights in Run Configuration
- Eclipse: Can't override test-order settings
- VS Code: No UI for test-order configuration

## Reproducible Scenarios

### Scenario A: IntelliJ Test Gutter Click
1. Open test file in IntelliJ
2. Click test method gutter icon
3. Run single test via IDE
4. Expected: test-order orderer runs, ordering applied
5. Actual: system properties not set, orderer disabled silently

### Scenario B: Eclipse TestRunner
1. Right-click test class → Run As → JUnit Test
2. Eclipse TestRunner starts
3. Expected: test-order orderer discovered and applied
4. Actual: Cache not found, classpath different, degraded

### Scenario C: IDE Rebuild/Recompile
1. Run tests in IDE (IDE caches config)
2. Rebuild project (clears some caches)
3. Run same tests again
4. Expected: Same test order
5. Actual: .test-order directory might be in different location

### Scenario D: Multi-Module IDE Project
1. IDE with multi-module project structure
2. Run tests from one module via IDE
3. test-order needs to find cache relative to module or project
4. Expected: Cache found at correct location
5. Actual: Path resolution incorrect for IDE module setup

## Root Causes

1. **No IDE Integration Layer:** test-order assumes Maven/CLI execution context
2. **Hardcoded Expectations:** Paths, properties, configuration assume CLI usage
3. **Silent Degradation:** No error messages when IDE context invalid
4. **No IDE Abstraction:** Different IDE working directories not handled
5. **Property-Only Configuration:** No IDE-native config format

## Impact Summary

- **Critical:** IDE test execution unreliable (orderer silently disabled)
- **High:** IDE debugging incompatible with instrumentation
- **High:** Cache not persisted across IDE runs
- **Medium:** Configuration not accessible in IDE UI
- **Medium:** Classpath contamination in IDE context
