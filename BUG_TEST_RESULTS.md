# Test-Order Plugin - Bug Findings Report

## Project: test-order-fields-methods-example

Created a comprehensive example project to test method and field-related scoring in the test-order plugin.

### Test Classes Created

1. **UserRegistryTest** - Tests instance fields and method interactions (6 tests)
2. **AccountServiceTest** - Tests volatile fields and initialization state (6 tests)
3. **DataProcessorTest** - Tests static field modifications (5 tests)
4. **SharedCounterGlobalTest** - Tests static field access patterns (3 tests)
5. **SharedCounterLocalTest** - Tests instance field access patterns (4 tests)
6. **StateMachineTest** - Tests complex field interdependencies (6 tests)

**Total: 30 tests across 6 test classes**

### Scoring Tests Performed

#### Test 1: Basic Modification Detection ✓
- **Scenario**: Modified UserRegistry.getEmail() method
- **Expected**: UserRegistryTest should be prioritized
- **Result**: PASS - UserRegistryTest moved to position 1 with score 2

#### Test 2: Static Field Modification ✓
- **Scenario**: Modified DataProcessor.getProcessedCount()
- **Expected**: DataProcessorTest should be prioritized
- **Result**: PASS - DataProcessorTest moved to position 1 with score 3

#### Test 3: Test Class Modification ✓
- **Scenario**: Modified SharedCounterLocalTest test method implementation
- **Expected**: Test bonus should be applied significantly
- **Result**: PASS - Score increased from 3 to 12, marked as changed test

#### Test 4: Unused Field Addition ✓
- **Scenario**: Added unused field to StateMachine
- **Expected**: Should not affect scoring
- **Result**: PASS - Score remained at 3 (correct behavior)

#### Test 5: Instance vs Static Field Scoring
- **Scenario**: Modified shared static field accessed by both instance and static tests
- **Expected**: Both tests should be prioritized equally
- **Result**: PASS - Both tests received same score (3), ordered by execution time

### Key Findings

**Working Correctly:**
- Plugin correctly detects method/field changes
- Plugin correctly prioritizes affected tests
- Plugin correctly handles test class modifications
- Plugin correctly ignores unused fields
- Plugin correctly applies scoring bonuses

**Observations:**
- No distinction between static and instance field changes in scoring
- All tests pass consistently (30/30)
- Scoring is deterministic and stable

### Project Files

- `/test-order-fields-methods-example/src/main/java/com/example/fields/`
  - UserRegistry.java
  - AccountService.java
  - DataProcessor.java
  - SharedCounter.java
  - StateMachine.java

- `/test-order-fields-methods-example/src/test/java/com/example/fields/`
  - UserRegistryTest.java
  - AccountServiceTest.java
  - DataProcessorTest.java
  - SharedCounterGlobalTest.java
  - SharedCounterLocalTest.java
  - StateMachineTest.java

### CRITICAL BUG FOUND: Overlap/Coverage Scoring Not Differentiating by Method

#### Bug Description
When multiple tests depend on the same class but exercise different methods, the plugin assigns identical scores to all tests regardless of which specific methods were modified.

#### Bug Details

**Test Case Setup:**
- `WideCoverageService` with 12+ methods grouped by category:
  - Item methods: `addItem`, `removeItem`, `getItemCount`, `containsItem`, `getAllItems` (5 methods)
  - Metadata methods: `setMetadata`, `getMetadata`, `removeMetadata`, `getAllMetadata` (4 methods)
  - Validation methods: `validate`, `clear`, `getAccessCount` (3 methods)

- Three test classes:
  - `ItemMethodsTest` - exercises only item methods (5 tests)
  - `MetadataMethodsTest` - exercises only metadata methods (4 tests)
  - `CombinedMethodsTest` - exercises all methods (4 tests)
  - `ValidationMethodsTest` - exercises only validation methods (3 tests)

**Test Scenario 1: Modify Item Method**
- Modified: `addItem()` 
- Expected: `ItemMethodsTest` score > `MetadataMethodsTest` score
- Actual: Both get score 3 (IDENTICAL)
- **Result: BUG CONFIRMED**

**Test Scenario 2: Modify Metadata Method**
- Modified: `setMetadata()`
- Expected: `MetadataMethodsTest` score > `ItemMethodsTest` score  
- Actual: Both get score 3 (IDENTICAL)
- **Result: BUG CONFIRMED**

**Test Scenario 3: Multiple Categories Modified**
- Modified: Both item and metadata methods
- Expected: `CombinedMethodsTest` > `ItemMethodsTest` = `MetadataMethodsTest`
- Actual: All get same base score (CombinedMethodsTest gets +2 bonus only)
- **Result: BUG CONFIRMED**

#### Root Cause
The plugin has support for member-level dependency tracking (see `TestScorer.java` lines 116-121 and `DependencyMap` member dependency methods), but member-level dependencies are NOT being collected or utilized during scoring.

The current implementation only tracks class-level dependencies, so when a class is modified:
1. ALL tests depending on that class get dependency overlap score
2. Ratio calculation: overlap/totalDeps is identical for both tests (1/1 = 100%)
3. No distinction based on which specific methods each test actually exercises

#### Code Evidence

In `TestScorer.java` line 117-121:
```java
Set<String> memberDeps = depMap.hasMemberDeps()
        ? depMap.getMemberDeps(testClassName)
        : depMap.classDeps(testClassName);  // Falls back to class-level!
Set<String> overlapClasses = StructuralChangeAnalyzer.computeOverlapClasses(
        deps, memberDeps, changedMembers, changedClasses);
```

The code has provision for member-level matching but reverts to class-level dependencies when `hasMemberDeps()` returns false, which it apparently always does.

#### Impact
- **Severity: HIGH** - Plugin cannot prioritize tests based on method/field coverage
- **Scope**: All projects using the plugin with multiple tests exercising same classes
- **Affected Feature**: Method and field-level scoring should prioritize tests based on coverage

#### Recommendations
1. Enable and verify member-level dependency collection during learn mode
2. Ensure `ChangedMembers` analysis is properly integrated
3. Add unit tests to verify method-level dependency collection
4. Document whether member-level dependencies are actively developed or experimental

### Additional Findings

1. Further testing with circular dependencies needed
2. Testing with deeply nested field access patterns recommended
3. Performance testing with large test suites suggested
4. Testing edge cases with modification timestamps advised
