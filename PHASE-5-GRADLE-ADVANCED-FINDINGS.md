# Phase 5 Extension: Gradle Advanced Scenarios - Bug Hunt Results

**Date:** 2026-04-21  
**Investigator:** Copilot Code Intelligence Agent  
**Focus Area:** Advanced Gradle Plugin Scenarios  
**Total Bugs Found:** 17  

## Executive Summary

The test-order Gradle plugin has **17 significant bugs** when used in advanced scenarios:

| Severity | Count | Description |
|----------|-------|-------------|
| 🔴 Critical | 2 | Configuration cache incompatibility, critical file locking issues |
| 🟠 High | 4 | Custom tasks unsupported, multi-project issues, race conditions |
| 🟡 Medium | 9 | Validation, path handling, feature gaps |
| ⚪ Low | 2 | Resource leaks, edge cases |

**Key Findings:**
- Plugin is **not compatible with Gradle configuration cache** (2 critical bugs)
- **No multi-project build support** (proper state file isolation missing)
- **No parallel execution support** (race conditions in file operations)
- **Custom test tasks not supported** (withType(Test.class) too restrictive)

---

## Investigation Methodology

1. **Static Code Analysis**: Examined TestOrderPlugin.java and related classes
2. **Code Pattern Detection**: Searched for known problematic patterns
   - System property access at configuration time
   - File I/O without locking
   - Missing validation and error handling
   - Hardcoded paths and fallbacks
3. **Integration Points Analysis**: Checked Gradle API usage
4. **Configuration Cache Constraints Review**: Verified cache-safe practices

---

## Critical Bugs (Must Fix)

### P5-GAD-004: Configuration Cache - Agent JAR Path Resolution

**Location:** `TestOrderPlugin.java:246-247` in `AgentArgumentProvider.asArguments()`

**Problem:**
```java
@Override
public Iterable<String> asArguments() {
    File agentJar = agentConf.getSingleFile();  // ❌ Called at configuration time!
    // ...
}
```

**Impact:**
- Violates configuration cache constraints
- Plugin is incompatible with `--configuration-cache`
- Cache is invalidated on every run

**Fix:**
Use Gradle's Provider API to lazy-evaluate the file path:
```java
private final Provider<RegularFile> agentJarProvider;

@Override
public Iterable<String> asArguments() {
    File agentJar = agentJarProvider.get().getAsFile();  // ✓ Evaluated at execution time
}
```

---

### P5-GAD-010: Configuration Cache - System.getProperty("user.home")

**Location:** `TestOrderExtensionConfigurator.java:25`

**Problem:**
```java
repo.setUrl(new File(System.getProperty("user.home"), ".m2/repository").toURI());
```

**Impact:**
- System properties accessed during configuration
- Configuration cache incompatible
- Different results on different machines with different user.home values

**Fix:**
Use Provider API or resolve at execution time:
```java
repo.setUrl(project.getProviders().systemProperty("user.home")
    .map(home -> new File(home, ".m2/repository").toURI().toString())
    .getOrElse(System.getProperty("user.home")));
```

---

## High-Severity Bugs

### P5-GAD-001: Custom Test Tasks Not Supported

**Problem:**
Plugin only configures `withType(Test.class)`, missing custom implementations.

**Impact:**
- Custom test task subclasses not configured
- Test ordering not applied to custom runners
- No warning that plugin was skipped

**Fix:**
- Document this limitation clearly
- Or: Use task naming convention to apply to all `*Test` tasks
- Or: Add extension point for custom task registration

---

### P5-GAD-005: Race Condition in Index File Writing

**Location:** `TestOrderPlugin.java:939` in `aggregateDependencyFiles()`

**Problem:**
```java
DependencyMap map = DependencyMap.aggregate(depsDir);
map.save(indexFile);  // ❌ No file locking!
```

**Impact:**
- Parallel builds (`--parallel`) corrupt index file
- Data loss possible
- Silent corruption (no error raised)

**Fix:**
```java
PersistenceSupport.withFileLock(indexFile, () -> {
    DependencyMap map = DependencyMap.aggregate(depsDir);
    map.save(indexFile);
    return null;
});
```

---

### P5-GAD-002: No Multi-Project Build Support

**Problem:**
- All subprojects default to same `.test-order/state.lz4` file
- No shared configuration mechanism for parent projects
- Each subproject must independently apply plugin

**Impact:**
- Parallel subproject builds corrupt state file
- Configuration cannot be inherited
- Difficult to use in large codebases

**Fix:**
- Add parent project configuration inheritance
- Project-specific state files: `.test-order/<projectName>/state.lz4`
- Or: Shared cache in root with project-specific locks

---

### P5-GAD-014: Multi-Project State File Collision

**Location:** `TestOrderExtension.java:110` (default convention)

**Problem:**
```
all projects use: .test-order/state.lz4
```

**Impact:**
- Concurrent writes from multiple subproject tests
- State file corruption in parallel builds
- Loss of test duration/failure history

**Fix:**
```java
getStateFile().convention(
    project.getLayout().getProjectDirectory()
        .file(".test-order/" + project.getName() + ".state.lz4")
);
```

---

## Medium-Severity Bugs

### P5-GAD-003: No Parallel Execution Support

**Problem:**
Plugin doesn't handle `gradle --parallel --workers=N`

**Impact:**
- Race conditions on shared files
- No documentation of concurrency safety
- State/index file access unprotected

**Fix:**
- Use file locking for all file operations
- Document parallel-safe practices
- Or: Disable plugin in parallel mode with warning

---

### P5-GAD-006: Fragile BuildSrc Detection

**Location:** `test-order-init.gradle:29`

**Problem:**
```groovy
if (project.buildFile.absolutePath.contains('buildSrc')) {
    return
}
```

**Impact:**
- Plugin applied to unintended projects
- `/myBuildSrc/` or `/build/buildSrc-cache/` incorrectly matched

**Fix:**
```groovy
def buildSrcDir = project.rootProject.file('buildSrc').absolutePath
if (project.buildFile.absolutePath.startsWith(buildSrcDir)) {
    return
}
```

---

### P5-GAD-007: No Mode Validation

**Problem:**
Invalid modes silently fall back to auto-detect

**Impact:**
- `-Dtestorder.mode=lern` (typo) silently ignored
- Hard to debug misconfigurations
- No error message

**Fix:**
```java
String resolveMode(TestOrderExtension ext, Project project) {
    String mode = ext.getMode().get();
    String propMode = gradleOrSystemProperty(project, "testorder.mode");
    if (propMode != null) {
        if (!isValidMode(propMode)) {
            throw new GradleException("Invalid mode: " + propMode + 
                ". Supported: auto, learn, order, skip");
        }
        mode = propMode;
    }
    // ...
}
```

---

### P5-GAD-009: Hardcoded Source Root Fallback

**Location:** `TestOrderPlugin.java:906`

**Problem:**
```java
return project.getProjectDir().toPath().resolve("src/main/java");
```

**Impact:**
- Custom SourceSet configurations ignored
- Silent failure in change detection
- Non-existent directories assumed

**Fix:**
```java
if (sourceSets == null) {
    Path fallback = project.getProjectDir().toPath().resolve("src/main/java");
    if (!Files.isDirectory(fallback)) {
        throw new GradleException(
            "Cannot locate main source root for project " + project.getName());
    }
    return fallback;
}
```

---

### P5-GAD-012: Unhandled NumberFormatException

**Location:** `TestOrderPlugin.java:1150, 1155`

**Problem:**
```java
return Integer.parseInt(override);  // ❌ No try-catch
```

**Impact:**
- `-Dtestorder.select.topN=invalid` crashes build
- Uncaught exception with confusing stack trace
- No validation of numeric input

**Fix:**
```java
try {
    return Integer.parseInt(override);
} catch (NumberFormatException e) {
    throw new GradleException(
        "Invalid number for testorder.select.topN: '" + override + "'", e);
}
```

---

### P5-GAD-013: Missing Task Dependencies

**Problem:**
Dashboard tasks don't declare index file as input

**Impact:**
- Task ordering not optimized by Gradle
- Manual task ordering required
- Build cache can't track dependencies

**Fix:**
```java
project.getTasks().register("testOrderDashboard", task -> {
    // Declare input files
    task.getInputs().file(ext.getIndexFile());
    task.getInputs().file(ext.getStateFile());
    // ...
});
```

---

### P5-GAD-016: Hardcoded Kotlin Source Path

**Location:** `TestOrderPlugin.java:568`

**Problem:**
```java
Path ktRoot = project.getProjectDir().toPath().resolve("src/main/kotlin");
```

**Impact:**
- Custom Kotlin SourceSet dirs not supported
- Change complexity scoring incomplete for Kotlin
- Kotlin-only projects broken

**Fix:**
```java
SourceSetContainer sourceSets = ...;
SourceSet main = sourceSets.findByName(MAIN_SOURCE_SET_NAME);
// Resolve Kotlin source roots properly, don't hardcode
```

---

### P5-GAD-017: TestNG Detection Race Condition

**Location:** Lines 84, 120 in `TestOrderPlugin.java`

**Problem:**
Two separate `afterEvaluate` callbacks - ordering not guaranteed:
1. Line 84: Configure test tasks
2. Line 120: Detect TestNG and add dependency

**Impact:**
- TestNG support may not be added before test configuration
- Non-deterministic behavior
- Depends on Gradle's callback execution order

**Fix:**
```java
project.afterEvaluate(p -> {
    // First: detect TestNG
    if (isTestNGOnTestClasspath(p)) {
        // Add dependency
    }
    // Then: configure test tasks
    configureTestTasks(p, ...);
});
```

---

## Low-Severity Bugs

### P5-GAD-008: HttpServer Resource Leak

**Location:** `serveDashboard()` method

**Problem:**
```java
server.start();
// If exception here, server never stops
Runtime.getRuntime().addShutdownHook(...);
```

**Impact:**
- Port blockage if Dashboard.browse() throws exception
- Minor, only affects dashboard feature
- Not critical for core functionality

---

### P5-GAD-011: Sentinel Test Class Collision

**Location:** `applySelectedTests()` method

**Problem:**
```java
filter.includeTestsMatching("__testorder__.NoMatchingTests");  // ❌ Collision possible
```

**Impact:**
- Hypothetical test class matching sentinel causes issues
- Low probability but possible
- Better pattern exists

**Fix:**
```java
if (tests.isEmpty()) {
    task.onlyIf { false };  // ✓ Just skip the task
} else {
    task.filter(filter -> {
        for (String testClass : tests) {
            filter.includeTestsMatching(testClass);
        }
    });
}
```

---

### P5-GAD-015: Silent File Deletion Failures

**Location:** testOrderClean task

**Problem:**
```java
walk.forEach(File::delete);  // ❌ No error checking
```

**Impact:**
- Files not deleted silently ignored
- Users think clean succeeded
- Read-only files cause silent failures

---

## Summary Table

| Bug ID | Title | Severity | Category | Fix Complexity |
|--------|-------|----------|----------|-----------------|
| P5-GAD-004 | Config cache - agent path | CRITICAL | Cache | High |
| P5-GAD-010 | Config cache - user.home | CRITICAL | Cache | Medium |
| P5-GAD-001 | Custom test tasks | HIGH | Plugin API | Medium |
| P5-GAD-005 | Race condition - index file | HIGH | Concurrency | Low |
| P5-GAD-002 | Multi-project support | HIGH | Architecture | High |
| P5-GAD-014 | State file collision | HIGH | Concurrency | Medium |
| P5-GAD-003 | Parallel execution | MEDIUM | Concurrency | Medium |
| P5-GAD-006 | BuildSrc detection | MEDIUM | Init script | Low |
| P5-GAD-007 | Mode validation | MEDIUM | Validation | Low |
| P5-GAD-009 | Source root fallback | MEDIUM | Path Resolution | Low |
| P5-GAD-012 | Number parsing | MEDIUM | Validation | Low |
| P5-GAD-013 | Task dependencies | MEDIUM | Gradle API | Low |
| P5-GAD-016 | Kotlin source path | MEDIUM | Path Resolution | Low |
| P5-GAD-017 | TestNG detection order | MEDIUM | Initialization | Low |
| P5-GAD-008 | HttpServer leak | LOW | Resource | Low |
| P5-GAD-011 | Sentinel collision | LOW | Edge case | Low |
| P5-GAD-015 | Deletion failures | LOW | Error handling | Low |

---

## Recommendations

### Immediate Actions (Blocks Production)
1. **Fix P5-GAD-004** - Configuration cache compatibility
2. **Fix P5-GAD-010** - System property during configuration
3. **Fix P5-GAD-005** - Race condition in index file writing

### High-Priority (Next Release)
4. **Fix P5-GAD-001** - Custom test task support
5. **Fix P5-GAD-002** - Multi-project architecture
6. **Fix P5-GAD-014** - State file isolation

### Medium-Priority (Quality)
7. Add validation for all user-supplied properties
8. Fix all hardcoded paths to respect SourceSet configuration
9. Consolidate afterEvaluate callbacks
10. Add explicit task dependencies

### Documentation
- Document Gradle version compatibility (especially config cache)
- Document parallel execution limitations
- Document multi-project requirements
- Add troubleshooting guide for common misconfigurations

---

## Testing Recommendations

**Add integration tests for:**
1. `--configuration-cache` builds
2. `--parallel --workers=N` execution
3. Multi-project builds with >10 subprojects
4. Custom test task implementations
5. Projects with custom SourceSet configurations
6. TestNG projects
7. Kotlin-only projects

---

## Conclusion

The test-order Gradle plugin has **critical issues preventing production use** in advanced scenarios:

- ❌ Not compatible with Gradle configuration cache
- ❌ Not safe for parallel execution
- ❌ Not designed for multi-project builds
- ❌ Cannot handle custom test task implementations
- ❌ Lacks comprehensive validation

**Recommendation:** Address critical bugs (P5-GAD-004, P5-GAD-010, P5-GAD-005) before recommending plugin for production use. Consider major architectural refactor for multi-project and configuration cache support.
