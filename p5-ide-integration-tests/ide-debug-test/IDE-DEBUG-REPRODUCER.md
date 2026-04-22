# IDE Debugging Issue Reproducer - P5-IDE-009

## Issue: Instrumentation Incompatible with IDE Debugger

When IDE debugger is attached and test-order agent is also trying to attach, JVM refuses multiple instrumentation agents. This prevents developers from debugging tests while using test-order.

## Problem Description

JVM allows only one instrumentation agent per JVM instance by default. test-order uses JVM instrumentation to collect test telemetry. When IDE debugger (which also uses instrumentation) is active, the agents conflict.

## Reproducer Steps

### Setup
```bash
cd /Users/i560383_1/code/experiments/test-order/p5-ide-integration-tests/ide-debug-test

# 1. Build with test-order enabled
mvn clean package
```

### In IntelliJ IDEA

#### Scenario 1: Normal Run (Works)
```
1. Open src/test/java/IDEDebugTest.java
2. Click gutter icon next to testExample()
3. Select "Run 'IDEDebugTest.testExample'"
4. Test runs successfully (though test-order may be disabled)
```

#### Scenario 2: Debug Run (Fails)
```
1. Open src/test/java/IDEDebugTest.java
2. Click on test method line number to set breakpoint
3. Click gutter icon next to testExample()
4. Select "Debug 'IDEDebugTest.testExample'"
5. Expected: Debugger attaches, test runs, breakpoint hits
6. Actual: One of:
   - a) Debugger fails to attach
      Error: "Cannot attach to process (Address already in use)" or similar
   
   - b) Exception in debug console:
       java.lang.RuntimeException: Unable to attach instrumentation agent
       Error attaching agent: can't assign requested address
   
   - c) Breakpoint never hits
       Test runs but breakpoint ignored
   
   - d) Exception about multiple agents:
       java.lang.Exception: java.lang.ClassNotFoundException: 
       java.lang.RuntimeException: Multiple instrumentation agents not allowed
```

## Technical Details

### JVM Instrumentation Conflict

test-order uses Java Instrumentation API to attach agent:
```java
// In test-order-agent module
public class Agent {
    public static void premain(String agentArgs, Instrumentation inst) {
        // Test telemetry collection using instrumentation
    }
}
```

IDE Debugger also attaches instrumentation agent for:
- Step debugging
- Variable inspection
- Method breakpoints
- Expression evaluation

### Why It Fails

JVM startup:
1. IDE debugger attaches via `-agentlib:jdwp=...`
2. test-order tries to attach via `-javaagent:test-order-agent.jar`
3. JVM rejects second agent attachment

Error message varies by IDE/JVM:
- **IntelliJ:** "Address already in use" 
- **Eclipse:** "Failed to establish agent socket connection"
- **Generic:** "Multiple instrumentation agents not allowed"

## Expected Behavior

✅ When running tests in debug mode with test-order:
- Debugger attaches successfully
- test-order agent also initializes
- Breakpoints hit during test execution
- Can inspect variables and step through code
- Telemetry collection works
- No errors or warnings

## Actual Behavior

❌ Current behavior:
- Only one of (debugger) or (test-order agent) can attach
- Debugger takes priority, test-order disabled
- Or: test-order enabled but debugger fails
- Cannot debug tests with test-order
- No clear error message about the conflict

## Root Causes

1. **No Debugger Detection:** test-order doesn't detect IDE debugger
2. **No Graceful Degradation:** Should disable instrumentation if debugger detected
3. **Single Agent Assumption:** JVM enforces single agent by default
4. **No Multi-Agent Support:** No mechanism for cooperation between agents

## Impact

- **Debugging Impossible:** Can't debug test code with test-order enabled
- **Hidden Issue:** Error message unclear, hard to diagnose
- **Workflow Broken:** Developers must choose: debug OR use test-order
- **Lost Feature:** Test prioritization disabled when debugging

## Scenarios This Breaks

1. **Debugging Test Failures**
   ```
   Developer: "Test failed, let me debug it"
   Attempts to: Run test in debug mode
   Result: Debugger fails → Can't find root cause
   ```

2. **Interactive Development**
   ```
   Developer: Set breakpoint → Debug test → See variable state
   Result: Can't do this with test-order enabled
   ```

3. **CI/CD Debugging**
   ```
   Test fails in CI but passes locally when run without debugger
   Developer tries to debug in IDE
   Result: Debugger conflicts with test-order
   ```

## Files Involved

- `test-order-agent/` - Instrumentation agent
- `test-order-junit/src/main/java/me/bechberger/testorder/TelemetryListener.java` - Telemetry setup
- `test-order-junit/src/main/java/me/bechberger/testorder/PriorityClassOrderer.java` - Orderer setup

## Solutions

### Option A: Detect Debugger and Disable Instrumentation
```java
// In TelemetryListener
if (System.getProperty("java.debug") != null) {
    // IDE debugger detected
    // Disable test-order instrumentation
    return;  // Fall back to default behavior
}
```

### Option B: Support Multiple Agents
- Use Java 11+ multi-agent support
- Coordinate with IDE debugger agent
- Share instrumentation context

### Option C: Lazy Agent Attachment
- Don't attach agent at startup
- Attach after debugger has initialized
- Requires complex timing coordination

### Option D: Provide Alternative Mechanism
- Don't use instrumentation for telemetry
- Use simpler listener-based approach
- Trade-off: Less telemetry data

## Related Issues

- P5-IDE-005: TelemetryListener fails silently
- P5-IDE-001: Configuration not set in IDE context
- P5-IDE-006: State file not persisted in IDE

## Workaround

Until this is fixed:

**Disable test-order when debugging:**
```
1. Edit IDE Run Configuration
2. Remove -javaagent parameter
3. Or: Disable test-order Maven plugin
4. Debug the test
5. Re-enable test-order after debugging
```

This workaround requires manual intervention and is not practical for developers.
