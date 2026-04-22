# IDE Integration Test Project - P5-IDE-001 Reproducer

## Project Structure

This Maven project demonstrates IDE Integration bug P5-IDE-001:
**Configuration Path Resolution Fails in IDE Context**

## Problem

When tests are executed through IDE interfaces (gutter click, context menu, Test Explorer), system properties `testorder.index.path` and `testorder.state.path` are typically not set. The test-order orderer silently disables itself without warning, making IDE test execution ineffective.

## How to Test

### Step 1: Run via Maven CLI (Baseline)
```bash
cd /Users/i560383_1/code/experiments/test-order/p5-ide-integration-tests/intellij-runconfig-test
mvn clean test
```

This creates `.test-order/test-dependencies.lz4` and demonstrates that test-order works from CLI.

### Step 2: Open in IntelliJ IDEA

1. File → Open → Select this project directory
2. IntelliJ IDEA loads the project
3. Notice `.idea/` directory created and `out/` directory may be generated

### Step 3: Run Test via IDE

#### Method A: Gutter Click
1. Open `src/test/java/me/bechberger/ide/IDEIntegrationTest.java`
2. Look for green triangle in left gutter next to `void testB_ShouldRunFirst()`
3. Click the triangle → Select "Run" from menu
4. Test executes

#### Method B: Context Menu
1. Right-click on class name in editor
2. Select "Run 'IDEIntegrationTest'" from context menu
3. Test executes

### Step 4: Observe the Problem

**Console Output Shows:**
```
Executed: Test A
Executed: Test B
Executed: Test C
```

Notice the tests run in arbitrary order (A, B, C instead of B, C, A per @Order annotations), indicating test-order orderer was not loaded.

**Expected:**
- Tests run in order: B (testB), C (testC), A (testA)
- test-order orderer loaded and applied prioritization
- Debug log shows: "[class-order] Loading index from..."

**Actual:**
- Tests run in arbitrary order
- No test-order debug output
- Orderer silently disabled
- No error or warning message

## Root Cause

In `test-order-junit/src/main/java/me/bechberger/testorder/PriorityClassOrderer.java`:

```java
public void orderClasses(ClassOrdererContext context) {
    String indexPath = getConfig(TestOrderConfig.INDEX_PATH);  // Returns null in IDE
    if (indexPath == null || indexPath.isEmpty()) {
        return;  // SILENTLY RETURNS - No error message!
    }
    // ... rest of initialization
}
```

The `getConfig()` method only checks:
1. System properties (`System.getProperty()`)
2. testorder-config.properties classpath resource

When IDE runs tests without Maven/Gradle, system properties are not set, and testorder-config.properties may not be on IDE classpath.

## Why This Matters

- **Developers can't use test-order in IDE** (primary use case)
- **IDE test running is broken** for test-order projects
- **No error message** makes it hard to debug
- **Users think test-order is broken** when it's just IDE integration

## Related Issues

- P5-IDE-002: Classpath order differs in IDE
- P5-IDE-004: Config properties file not found
- P5-IDE-006: State file not persisted

## Fix Needed

1. **Add IDE detection:** Detect when running in IDE context
2. **Provide defaults:** Default paths relative to project root
3. **Add warnings:** Log error if files not found
4. **Document:** Explain how to configure test-order in IDE

## IntelliJ Configuration Workaround

To make test-order work in IntelliJ until this is fixed:

1. Edit Run Configurations:
   - Run → Edit Configurations...
   - Select "JUnit" template
   - In "VM options" field, add:
     ```
     -Dtestorder.index.path=.test-order/test-dependencies.lz4
     -Dtestorder.state.path=.test-order/state.lz4
     -Dtestorder.debug=true
     ```

2. Apply to all future test runs
3. Tests should now use test-order in IDE

This workaround proves the issue: users shouldn't need to manually set system properties for IDE to work.
