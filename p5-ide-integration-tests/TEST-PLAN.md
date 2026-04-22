# Phase 5 - IDE Integration Bug Hunt

## Overview
Testing test-order plugins through IDE integration and discovering IDE-specific issues.

## Test Categories

### 1. IntelliJ IDEA Integration
- **Test Discovery:** Does IntelliJ correctly discover test-order orderers?
- **Run Configurations:** Do custom run configurations work?
- **Debugging:** Does debugging work with test-order instrumentation?
- **Test Highlighting:** Are tests marked correctly?
- **Project Caching:** Does IntelliJ cache affect test-order?
- **Artifacts Directory:** IntelliJ generates `out/` directory - does this interfere?
- **IDE Test Gutter:** Can tests be run from gutter icons?

### 2. Eclipse TestRunner Integration
- **Test Discovery:** Does Eclipse recognize test-order orderers?
- **TestRunner Output:** Is test output correctly displayed?
- **Classpath Configuration:** Is test-order on classpath in Eclipse?
- **Run Configurations:** Custom run configs with test-order?

### 3. VS Code Test Adapter Integration
- **Test Explorer:** Can tests be discovered and listed?
- **Test Execution:** Can tests run from explorer?
- **Debugging:** Can tests be debugged?
- **Extension Compatibility:** Works with Test Adapter UI?

### 4. IDE-Specific Classpath Issues
- **Order of Classpath Entries:** IDE vs. CLI classpath differences
- **Dependency Scope:** Does IDE respect dependency scopes?
- **Module Dependencies:** Multi-module projects in IDEs
- **IDE Caching:** Does IDE cache affect dependency resolution?

### 5. IDE Cache and Invalidation
- **Cache Consistency:** Cache valid across IDE restart?
- **Manual Cache Invalidation:** Does clearing IDE cache break test-order?
- **Stale Cache:** Old cache from previous runs?
- **Cache Location:** Where does IDE store test-order cache?

### 6. Debug Mode with Instrumentation
- **Agent Attachment:** Can debugger work with test-order agent?
- **Breakpoints:** Do breakpoints work during instrumentation?
- **Variables:** Can local variables be inspected?
- **Step Debugging:** Can step through instrumented code?

### 7. IDE-Specific Test Runners
- **Custom Runners:** IDE-specific test runners interfere?
- **Parallel Testing:** IDE parallel runner with test-order?
- **Flaky Test Detection:** IDE flaky detection with reordering?
- **Coverage Integration:** IDE coverage tool with test-order?

### 8. Hot Reload/Refresh
- **Code Changes:** IDE detects changes and reruns tests?
- **Cache Invalidation:** Does code change invalidate cache?
- **Rebuild Behavior:** IDE rebuild affects cache?
- **Live Testing:** IDE live testing with test-order?

## Test Setup Requirements
- IntelliJ IDEA (if available)
- Eclipse (if available)
- VS Code with Test Adapter (if available)
- Maven projects with test-order configured
- Gradle projects with test-order configured

## Expected Issues Categories
1. **Cache Contamination:** IDE artifacts in cache
2. **Classpath Mismatches:** Different classpath order
3. **Configuration Issues:** IDE can't find test-order orderers
4. **Debug Incompatibility:** Instrumentation breaks debugging
5. **Timing Issues:** Different execution timing in IDE
6. **Plugin Conflicts:** Other IDE plugins interfere
7. **Configuration Inheritance:** IDE doesn't apply config correctly
