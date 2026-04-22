# PHASE 5: Large-Scale Bug Hunt

## Objective
Test test-order Maven and Gradle plugins with extremely large codebases to find performance boundaries, memory limits, and scale-related bugs.

## Test Scenarios

### Scenario 1: Single Module with 1000+ Test Classes
- Create Maven module with 1000 test classes
- Each test class has 10 test methods
- Total: 10,000 test methods
- Measure: Discovery time, cache size, ordering time, memory usage

### Scenario 2: 500+ Test Classes with 10,000+ Test Methods
- Create module with 500 test classes
- Each class has 20+ test methods
- Total: 10,000+ test methods
- Focus on method ordering performance

### Scenario 3: 100+ Module Maven Reactor
- Create 100+ Maven modules
- Each module has 50+ test classes
- Total: 5,000+ tests across reactor
- Measure: Multi-module handling, cache coordination

### Scenario 4: Gradle Multi-Project with 200+ Subprojects
- Create Gradle project with 200+ subprojects
- Each subproject has tests
- Measure: Gradle discovery performance

### Scenario 5: Deep Nesting (50+ Levels)
- Create deeply nested package structure
- Test handling of extreme directory depth

### Scenario 6: Very Long Names
- Test classes with 200+ character names
- Packages with 200+ character names
- Method names with 100+ characters

### Scenario 7: Monorepo Patterns
- Gradle composite builds
- Maven with interdependent modules
- Complex test dependency chains

## Metrics to Collect
- Discovery time (seconds)
- Cache file size (MB)
- Memory usage (peak, during learn, during order)
- Ordering calculation time
- Number of tests processed
- CPU usage patterns
- I/O patterns

## Success Criteria
- All operations complete without timeout (< 5 minutes for learn, < 10 minutes for order)
- No out-of-memory errors
- No cache corruption
- Consistent results across runs
- Cache stays below reasonable size (<500MB)

## Failure Scenarios to Look For
- Memory exhaustion
- Timeout during discovery
- Incorrect ordering results
- Cache corruption
- Crash on specific test counts
- Performance cliff at specific scale

