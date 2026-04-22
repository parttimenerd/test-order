# PHASE 5: Real-World Open-Source Project Testing

## Objective
Test test-order against real open-source projects to identify real-world issues, edge cases, and compatibility problems.

## Test Projects

### 1. Spring PetClinic
- **Status**: Available locally
- **Type**: Spring Boot web application
- **Test Framework**: JUnit 5
- **Complexity**: Medium (module structure + web/persistence layers)

### 2. JUnit Framework
- **Status**: Available locally (incomplete)
- **Type**: Test framework itself
- **Test Framework**: JUnit 5
- **Complexity**: Very High (meta - testing the test framework)

### 3. Other projects
- Will clone if available and needed

## Testing Methodology

For each project:
1. **Discovery Phase**: List all test classes and analyze structure
2. **Learn Phase**: Run test-order learn mode to discover test dependencies
3. **Order Phase**: Run test-order order mode and verify ordering
4. **Validation Phase**: Run tests with applied ordering
5. **Analysis Phase**: Document findings and issues

## Key Metrics to Track

- Total tests discovered
- Test execution time (baseline vs ordered)
- Success/failure of learned ordering
- Compatibility issues
- Edge cases found
- Performance improvements

## Issues to Document

- Crashes or errors
- Incorrect test ordering
- Undetected test dependencies
- Performance regressions
- Compatibility problems
- False positives/negatives

