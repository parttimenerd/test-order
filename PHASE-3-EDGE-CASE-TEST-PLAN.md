# PHASE 3: EDGE CASE PARAMETER TESTING - COMPREHENSIVE TEST PLAN

## Objective
Identify parameter handling bugs in test-order Maven, Gradle, and CLI plugins through systematic edge case testing.

## Test Coverage

### 1. INVALID PARAMETER NAMES (Maven: -D, Gradle: -P, CLI: --option)

#### 1.1 Typos in parameter names
- `testorder.foo=bar` (non-existent param)
- `testorder.changemod=auto` (typo in changeMode)
- `testorder.stafile=/path` (typo in stateFile)

#### 1.2 Case sensitivity
- `TestOrder.changeMode=auto` (uppercase prefix)
- `testorder.ChangeMode=auto` (uppercase param name)
- `TESTORDER.CHANGEMODE=auto` (all uppercase)

#### 1.3 Separator variants
- `testorder-changeMode=auto` (hyphen instead of dot)
- `testorder_changeMode=auto` (underscore instead of dot)

#### 1.4 Extreme parameter names
- Parameter name with 1000+ characters
- Parameter name with special chars: $, !, @, #, %, ^, &, *, (, )
- Empty parameter name

### 2. INVALID PARAMETER VALUES

#### 2.1 String parameters
- Empty string for required parameters
- Whitespace-only values (` `, `\t`, `\n`)
- Very long values (10KB, 1MB strings)
- Unicode characters (emojis, CJK, RTL text)
- Special chars in values: `\n`, `\r`, `\t`, `"`, `'`, `\`

#### 2.2 Numeric parameters
- Zero values (where positive expected)
- Negative values (where positive expected)
- Float instead of int (e.g., `1.5` for port)
- Very large numbers (Integer.MAX_VALUE, Long.MAX_VALUE)
- NaN, Infinity strings

#### 2.3 Boolean parameters
- Various spellings: `true`, `false`, `yes`, `no`, `1`, `0`, `on`, `off`
- Case variations: `TRUE`, `True`, `tRuE`, `FALSE`, `False`
- Invalid values: `maybe`, `unknown`, `true1`, `yesno`
- Empty boolean

#### 2.4 Path parameters
- Non-existent paths
- Relative vs absolute paths
- Paths with spaces: `/path with spaces/test`
- Paths with special chars: `/path/$special/@test`
- Very long paths (250+ chars)
- Symlinks and circular symlinks

### 3. PARAMETER COMBINATIONS

#### 3.1 Contradictory parameters
- Both `changeMode=auto` and `changeMode=explicit` set
- Both `includeTests=*` and `excludeTests=*` set

#### 3.2 Duplicate parameters
- Same parameter set twice with different values
- Test if last-wins or error

#### 3.3 Inheritance and precedence
- CLI param overrides config file param
- Default vs explicit param
- System property vs config

### 4. MAVEN SPECIFIC: Property Handling

#### 4.1 Maven property syntax
- `-Dtestorder.stateFile=/path` (correct)
- `-Dtestorder.stateFile=/path with spaces` (no quoting)
- `-Dtestorder.stateFile="/path with spaces"` (quoted)
- `-DtestorderStateFile` (camelCase instead of dot notation)

#### 4.2 POM configuration vs command line
- Parameter in pom.xml overridden by CLI `-D`
- Parameter in pom.xml with default value
- Empty parameter in pom.xml

### 5. GRADLE SPECIFIC: Property Handling

#### 5.1 Gradle property syntax
- `-PtestOrder.changeMode=auto` (correct)
- `-PtestOrder.stateFile=/path` (different from Maven dot notation)
- `-PtestOrderChangeMode` (different naming convention)

#### 5.2 build.gradle configuration vs command line
- Property in build.gradle overridden by CLI `-P`
- Property in gradle.properties file
- Environment variable as fallback

### 6. FILE PATH PARAMETERS

#### 6.1 State file
- Non-existent state file (should create or error?)
- Corrupted state file
- State file with no read permissions
- State file with no write permissions
- Symlink to state file
- State file path with spaces
- State file path with unicode chars

#### 6.2 Index file
- Non-existent index file
- Corrupted index file
- Index file with permissions issues

#### 6.3 Source/test roots
- Non-existent source directory
- Empty source directory
- Source directory with no read permissions
- Circular symlink in source

### 7. NUMERIC PARAMETERS

#### 7.1 Timeout parameters (if any)
- Zero timeout
- Negative timeout
- Very large timeout
- Non-numeric timeout

#### 7.2 Port parameters (if any)
- Port 0 (should assign random port?)
- Port 1 (privileged port)
- Port 65535 (max valid)
- Port 70000 (out of range)
- Negative port
- Non-numeric port

#### 7.3 Numeric limits
- Integer.MAX_VALUE
- Integer.MIN_VALUE
- Long.MAX_VALUE
- Floating point precision issues

### 8. FILTER/SELECTOR PARAMETERS

#### 8.1 Test inclusion/exclusion
- Empty filter
- `*` (wildcard)
- `*.* ` (regex-like)
- Case sensitivity in filter
- Unicode in filter name

#### 8.2 Class/method filters
- Filter with spaces
- Filter with special regex chars: `[`, `]`, `(`, `)`, `.`, `*`, `+`
- Non-existent class in filter

### 9. CONFIGURATION FILE PARAMETERS

#### 9.1 Config file handling
- Non-existent config file
- Invalid JSON in config
- Invalid YAML in config
- Empty config file
- Very large config file
- Circular includes (if supported)
- Config file with permission denied

### 10. ENVIRONMENT & SYSTEM

#### 10.1 Environment variables
- Parameter via env var
- Env var with spaces
- Env var with special chars

#### 10.2 System properties
- Java system property override
- Multiple properties conflict

## Test Execution Strategy

For each parameter/value combination:
1. Set parameter in test environment (Maven `-D`, Gradle `-P`, CLI option)
2. Execute relevant goal/command
3. Capture exit code and output
4. Log expected vs actual behavior
5. Identify if behavior is a bug (silent failure, wrong error, unexpected result)

## Expected Bugs to Find

1. **Silent Failures**: Parameter is ignored without error
2. **Wrong Error Messages**: Confusing or incorrect error messages
3. **Security Issues**: Parameter injection or path traversal
4. **Type Mismatches**: String parsed as int without validation
5. **Precedence Issues**: Wrong parameter takes precedence
6. **Partial Validation**: Some params validated, others not
7. **Inconsistent Naming**: Different param names in different places
8. **Path Issues**: Paths not properly normalized or validated

## Output Format

```
[PARAM_NAME] [VALUE_TYPE] [VALUE] 
Expected: [behavior]
Actual:   [behavior]
Status:   [PASS/BUG/UNCLEAR]
Details:  [explanation]
```
