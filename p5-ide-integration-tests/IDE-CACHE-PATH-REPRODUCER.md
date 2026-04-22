# IDE Cache Path Issue Reproducer - P5-IDE-007

## Issue: Working Directory Mismatch Breaks Cache Location

When tests are run from IDE, the working directory differs from Maven's project.basedir, causing .test-order cache to be created in wrong locations or not found at all.

## Problem Description

test-order stores cache in `.test-order/` directory relative to project root:
- Maven: Always ${project.basedir}/.test-order (consistent)
- IDE: Depends on IDE working directory (inconsistent)

IDE working directories:
- **IntelliJ:** May be project root, module root, or custom dir
- **Eclipse:** May be project root or workspace root
- **Maven IDE Plugin:** May be different from source root
- **Gradle IDE Plugin:** May be different from source root

## Reproducer Steps

### Multi-Module Project Setup

```
project/
  pom.xml (parent)
  module-a/
    pom.xml
    src/test/java/TestA.java
  module-b/
    pom.xml
    src/test/java/TestB.java
```

### Step 1: Run via Maven CLI

```bash
cd project/
mvn clean test

# Result: Creates project/.test-order/
ls -la .test-order/
# Output:
# total 48
# -rw-r--r--  1 user  staff  state.lz4
# -rw-r--r--  1 user  staff  test-dependencies.lz4
# -rw-r--r--  1 user  staff  hashes.lz4
```

### Step 2: Reload Project in IntelliJ

1. File → Open Recent → project/
2. IntelliJ loads project structure
3. Notice: `project/.idea/` created
4. Notice: `project/out/` created (IDE output directory)

### Step 3: Run Test from Module

1. In Project Navigator, right-click `module-a/src/test/java/TestA.java`
2. Select "Run 'TestA'" from context menu
3. IntelliJ runs test

### Step 4: Check Cache Locations

```bash
# Find where .test-order was created:
find project -name ".test-order" -type d

# Possible outputs:
# A) project/.test-order/        ✅ CORRECT
# B) module-a/.test-order/       ❌ WRONG (created in module dir)
# C) ~/.cache/junit-test-order/  ❌ WRONG (created in temp)
# D) /tmp/test-order-cache/      ❌ WRONG (created in /tmp)
# E) <none>                       ❌ WRONG (not created at all!)
```

## Root Cause Analysis

### IntelliJ Working Directory

When you run test from context menu on TestA.java:
```
IntelliJ execution context:
- Module: module-a
- IDE working directory: Could be:
  A) project/ (project root) ✅
  B) module-a/ (module root) ❌
  C) Custom directory from Run Config ❌
```

### Code Responsible

In `test-order-maven-plugin/src/main/java/.../PrepareMojo.java`:
```java
// Maven always has project.basedir available
Path cacheDir = Paths.get(project.getBasedir().toString(), ".test-order");
```

But in test-order-junit when run via IDE:
```java
// test-order-junit has no Maven project context
// Falls back to working directory
String stateFile = System.getProperty(TestOrderConfig.STATE_PATH);
if (stateFile == null) {
    // No explicit path - where to create cache?
    // Falls back to current working directory
    Path cacheDir = Paths.get(".test-order");  // Relative to IDE working dir!
}
```

## Test Results in Different IDEs

### IntelliJ IDEA
```
Maven CLI: project/.test-order/
IDE Run (module):
  - First attempt: module-a/.test-order/  (created here!)
  - Subsequent runs: module-a/.test-order/ (reuses it)
  - Cache never reused from project/.test-order/
  
Result: ❌ Separate cache per module, inconsistent with CLI
```

### Eclipse
```
Maven CLI: project/.test-order/
Eclipse IDE:
  - May create: project/.metadata/.plugins/test-order-cache/
  - Or: ${workspace}/.test-order/
  - Or: ${project}/.test-order/ (if lucky)
  
Result: ❌ Cache in IDE-specific location, CLI can't find it
```

### VS Code
```
Maven CLI: project/.test-order/
VS Code Test Explorer:
  - May run from workspace root
  - May run from project root
  - Unclear which directory is "current"
  
Result: ❌ Cache location unpredictable
```

## Expected Behavior

✅ Test-order cache should:
- Always be at project root, regardless of IDE
- Be reused across Maven CLI and IDE runs
- Be in same location whether run from module or project
- Be consistent across IDE runs
- Be under .gitignore automatically

## Actual Behavior

❌ Current behavior:
- Cache created in different location per IDE run
- IDE cache separate from Maven CLI cache
- Cache not reused between runs
- New learning happens every run
- Cache may be created in user temp directory

## Impact

1. **Cache Not Reused:** Test learning/optimization lost in IDE
2. **Performance:** Every IDE run starts from scratch
3. **Inconsistent Behavior:** Different results in IDE vs CLI
4. **Disk Space:** Multiple caches created across workspace
5. **Confusion:** Users don't understand why optimization isn't working

## Reproducer Test Case

```bash
#!/bin/bash
cd project/

# 1. Baseline: Maven CLI
echo "=== Maven CLI Run ==="
mvn clean test
ls -la .test-order/

# 2. IDE Run (simulated)
echo "=== IDE Simulation ==="
cd module-a/
mvn test -Dtestorder.state.path=.test-order/state.lz4 \
         -Dtestorder.index.path=.test-order/test-dependencies.lz4
ls -la .test-order/

# 3. Check if parent cache still exists
cd ../
echo "=== Parent Directory Cache ==="
ls -la .test-order/

# Result: Two separate caches instead of one shared cache
```

## Files Involved

- `test-order-junit/src/main/java/me/bechberger/testorder/TelemetryListener.java`
- `test-order-core/src/main/java/me/bechberger/testorder/TestOrderState.java`
- `test-order-maven-plugin/src/main/java/.../PrepareMojo.java`

## Solution Approaches

### Option A: IDE Plugin (Best)
Provide IntelliJ and Eclipse plugins that:
- Detect IDE execution context
- Set testorder.state.path to project/.test-order/
- Set testorder.index.path to project/.test-order/test-dependencies.lz4
- Persist across IDE sessions

### Option B: Auto-detection (Good)
Detect project root in IDE context:
```java
// Try to find pom.xml or build.gradle
Path root = findProjectRoot();
Path cacheDir = root.resolve(".test-order");
```

### Option C: Environment Variable (Workaround)
Check environment variable:
```java
String projectRoot = System.getenv("TEST_ORDER_PROJECT_ROOT");
if (projectRoot == null) {
    projectRoot = System.getProperty("user.dir");  // Fall back to working dir
}
```

### Option D: Configuration File (Medium)
Create `.test-order.config` at project root:
```properties
project.root=${project.basedir}
cache.dir=${project.basedir}/.test-order
```

## Workaround for Users

Set environment variables before running IDE:
```bash
export TEST_ORDER_PROJECT_ROOT=/Users/user/project
export TEST_ORDER_STATE_PATH=/Users/user/project/.test-order/state.lz4
export TEST_ORDER_INDEX_PATH=/Users/user/project/.test-order/test-dependencies.lz4
```

Or manually configure in IDE Run Configuration.

## Related Issues

- P5-IDE-001: Configuration path resolution
- P5-IDE-006: State file path not set
- P5-IDE-004: Configuration file not found
