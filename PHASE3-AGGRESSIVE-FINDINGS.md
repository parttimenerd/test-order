# PHASE 3: AGGRESSIVE EDGE CASE FINDINGS
Testing extreme and unusual parameter combinations

## Section 1: Parameter Name Typos & Variations

### Typo: missing 'e' in changeMode
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Typo: missing 'M' in methodOrderingEnabled
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Wrong separator: hyphen instead of dot
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Wrong separator: underscore instead of dot
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

## Section 2: Extreme String Values

### 1MB parameter value
❌ FAILED (exit: 1)
```
Error: /Users/i560383_1/.sdkman/candidates/maven/current/bin/mvn: line 195: /Users/i560383_1/.sdkman/candidates/java/current/bin/java: Undefined error: 0
```

### Parameter with only special chars: !@#$%^&*()
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Parameter with embedded newlines
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

## Section 3: Numeric Boundary Cases

### Integer.MAX_VALUE
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Integer.MIN_VALUE (negative)
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Float value for int parameter: 1.5
❌ FAILED (exit: 1)
```
Error: [ERROR] Failed to execute goal me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:prepare (default-cli) on project test-project-001: Unable to parse configuration of mojo me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:prepare for parameter autoLearnDiffThreshold: Cannot convert '1.5' to int: For input string: "1.5" -> [Help 1]
```

### Float value for int parameter: 1.999
❌ FAILED (exit: 1)
```
Error: [ERROR] Failed to execute goal me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:prepare (default-cli) on project test-project-001: Unable to parse configuration of mojo me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:prepare for parameter autoLearnDiffThreshold: Cannot convert '1.999' to int: For input string: "1.999" -> [Help 1]
```

### Scientific notation: 1e10
❌ FAILED (exit: 1)
```
Error: [ERROR] Failed to execute goal me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:prepare (default-cli) on project test-project-001: Unable to parse configuration of mojo me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:prepare for parameter autoLearnDiffThreshold: Cannot convert '1e10' to int: For input string: "1e10" -> [Help 1]
```

### Hex notation: 0xFF
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

## Section 4: Boolean Variations

### Boolean value: 'TRUE'
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Boolean value: 'True'
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Boolean value: 'tRuE'
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Boolean value: 'FALSE'
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Boolean value: 'False'
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Boolean value: 'fAlSe'
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Boolean value: 'ON'
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Boolean value: 'Off'
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Boolean value: 'YES'
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Boolean value: 'No'
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Boolean value: '2'
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Boolean value: '-1'
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Boolean value: 'null'
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Boolean value: 'empty'
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Boolean value: ''
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

## Section 5: Enum Case Variations

### changeMode: AUTO (uppercase)
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### changeMode: Auto (mixed case)
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### changeMode: aUtO (mixed case)
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### instrumentationMode: full (lowercase)
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

## Section 6: Parameter with Path Injection

### Path traversal: ../../../etc/passwd
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Path with environment variable
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Path with backticks (command injection)
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

## Section 7: Parameter Conflicts & Combinations

### changeMode=explicit without changedClasses
❌ FAILED (exit: 1)
```
Error: [ERROR] Failed to execute goal me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:prepare (default-cli) on project test-project-001: [test-order] changeMode is 'explicit' but changedClasses parameter is not specified. Provide comma-separated fully qualified class names: -Dtestorder.changed.classes=com.example.A,com.example.B -> [Help 1]
```

### Both changeMode=auto and explicit (last wins)
❌ FAILED (exit: 1)
```
Error: [ERROR] Failed to execute goal me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:prepare (default-cli) on project test-project-001: [test-order] changeMode is 'explicit' but changedClasses parameter is not specified. Provide comma-separated fully qualified class names: -Dtestorder.changed.classes=com.example.A,com.example.B -> [Help 1]
```

### Method ordering on non-existent state
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

## Section 8: Configuration Inheritance

### Empty changedClasses in explicit mode
❌ FAILED (exit: 1)
```
Error: [ERROR] Failed to execute goal me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:prepare (default-cli) on project test-project-001: [test-order] changeMode is 'explicit' but changedClasses parameter is not specified. Provide comma-separated fully qualified class names: -Dtestorder.changed.classes=com.example.A,com.example.B -> [Help 1]
```

### Whitespace changedClasses in explicit mode
❌ FAILED (exit: 1)
```
Error: [ERROR] Failed to execute goal me.bechberger:test-order-maven-plugin:0.1.0-SNAPSHOT:prepare (default-cli) on project test-project-001: [test-order] changeMode is 'explicit' but changedClasses parameter is not specified. Provide comma-separated fully qualified class names: -Dtestorder.changed.classes=com.example.A,com.example.B -> [Help 1]
```

## Section 9: Unknown Parameters

### Unknown parameter: -Dtestorder.unknownParam=value
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

### Multiple unknown parameters
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation

## Section 10: Very Long Parameter Names

### Very long parameter name (1000 chars)
✅ SUCCESS (exit: 0)
```
Error: (no error message)
```
⚠️ **SILENT PASS** - May indicate missing validation
