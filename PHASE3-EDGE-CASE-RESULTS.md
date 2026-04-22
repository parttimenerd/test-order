# PHASE 3: EDGE CASE PARAMETER TESTING
Systematic testing of parameter handling in test-order Maven plugin
Generated: Tue Apr 21 14:44:59 CEST 2026

## Test: Invalid parameter: testorder.foo=bar
```
Command: mvn test-order:prepare -Dtestorder.foo=bar
Exit Code: 0
Status: SUCCESS
```

## Test: Typo: testorder.changemod (missing 'e')
```
Command: mvn test-order:prepare -Dtestorder.changemod=auto
Exit Code: 0
Status: SUCCESS
```

## Test: Invalid changeMode: changeMode=badvalue
```
Command: mvn test-order:prepare -Dtestorder.changeMode=badvalue
Exit Code: 1
Status: FAILED
Error: [ERROR] Failed to execute goal me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:prepare (default-cli) on project test-project-001: [test-order] Invalid changeMode 'badvalue'. Valid values are: uncommitted, since-last-commit, since-last-run, auto, explicit -> [Help 1]
```

## Test: Empty changeMode
```
Command: mvn test-order:prepare -Dtestorder.changeMode=''
Exit Code: 1
Status: FAILED
Error: [ERROR] Failed to execute goal me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:prepare (default-cli) on project test-project-001: Invalid changeMode ''. Valid values: [explicit, uncommitted, since-last-commit, since-last-run, auto] -> [Help 1]
```

## Test: Whitespace changeMode
```
Command: mvn test-order:prepare -Dtestorder.changeMode='   '
Exit Code: 1
Status: FAILED
Error: [ERROR] Failed to execute goal me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:prepare (default-cli) on project test-project-001: Invalid changeMode '   '. Valid values: [uncommitted, since-last-commit, since-last-run, auto, explicit] -> [Help 1]
```

## Test: Invalid instrumentationMode: BADMODE
```
Command: mvn test-order:prepare -Dtestorder.instrumentationMode=BADMODE
Exit Code: 1
Status: FAILED
Error: [ERROR] Failed to execute goal me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:prepare (default-cli) on project test-project-001: Invalid instrumentationMode 'BADMODE'. Valid values: [METHOD_ENTRY, FULL, FULL_METHOD, FULL_MEMBER] -> [Help 1]
```

## Test: Valid changeMode: auto
```
Command: mvn test-order:prepare -Dtestorder.changeMode=auto
Exit Code: 0
Status: SUCCESS
```

## Test: Valid changeMode: explicit
```
Command: mvn test-order:prepare -Dtestorder.changeMode=explicit
Exit Code: 1
Status: FAILED
Error: [ERROR] Failed to execute goal me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:prepare (default-cli) on project test-project-001: [test-order] changeMode is 'explicit' but changedClasses parameter is not specified. Provide comma-separated fully qualified class names: -Dtestorder.changed.classes=com.example.A,com.example.B -> [Help 1]
```

## Test: Valid changeMode: since-last-run
```
Command: mvn test-order:prepare -Dtestorder.changeMode=since-last-run
Exit Code: 0
Status: SUCCESS
```

## Test: Valid instrumentationMode: FULL
```
Command: mvn test-order:prepare -Dtestorder.instrumentationMode=FULL
Exit Code: 0
Status: SUCCESS
```

## Test: Non-existent state file
```
Command: mvn test-order:optimize -Dtestorder.stateFile=/nonexistent/state.lz4
Exit Code: 1
Status: FAILED
Error: [ERROR] Failed to execute goal me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:optimize (default-cli) on project test-project-001: State file not found: /nonexistent/state.lz4. Run some test-order test runs first. -> [Help 1]
```

## Test: methodOrderingEnabled=true
```
Command: mvn test-order:prepare -Dtestorder.methodOrderingEnabled=true
Exit Code: 0
Status: SUCCESS
```

## Test: methodOrderingEnabled=false
```
Command: mvn test-order:prepare -Dtestorder.methodOrderingEnabled=false
Exit Code: 0
Status: SUCCESS
```

## Test: methodOrderingEnabled=yes (non-standard)
```
Command: mvn test-order:prepare -Dtestorder.methodOrderingEnabled=yes
Exit Code: 0
Status: SUCCESS
```

## Test: methodOrderingEnabled=maybe (invalid)
```
Command: mvn test-order:prepare -Dtestorder.methodOrderingEnabled=maybe
Exit Code: 0
Status: SUCCESS
```

## Test: autoLearnDiffThreshold=0
```
Command: mvn test-order:prepare -Dtestorder.autoLearnDiffThreshold=0
Exit Code: 0
Status: SUCCESS
```

## Test: autoLearnDiffThreshold=-1
```
Command: mvn test-order:prepare -Dtestorder.autoLearnDiffThreshold=-1
Exit Code: 0
Status: SUCCESS
```

## Test: autoLearnDiffThreshold=999999999
```
Command: mvn test-order:prepare -Dtestorder.autoLearnDiffThreshold=999999999
Exit Code: 0
Status: SUCCESS
```

## Test: Duplicate parameter (last wins?)
```
Command: mvn test-order:prepare -Dtestorder.changeMode=auto -Dtestorder.changeMode=explicit
Exit Code: 1
Status: FAILED
Error: [ERROR] Failed to execute goal me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:prepare (default-cli) on project test-project-001: [test-order] changeMode is 'explicit' but changedClasses parameter is not specified. Provide comma-separated fully qualified class names: -Dtestorder.changed.classes=com.example.A,com.example.B -> [Help 1]
```
