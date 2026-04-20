# Phase 9A: Usability Audit Report

## Executive Summary

Comprehensive audit of all CLI options, Maven plugin parameters, and configuration across test-order project. Identified **28 findings**: 8 critical usability issues, 12 missing descriptions, 5 naming inconsistencies, 3 default value concerns.

**Severity Breakdown**:
- 🔴 **Critical**: 8 issues (missing descriptions, confusing naming, poor defaults)
- 🟡 **Medium**: 12 issues (inconsistent naming, unclear options)
- 🟢 **Low**: 8 issues (documentation gaps, minor clarity issues)

---

## 1. Maven Plugin Parameters (test-order-maven-plugin)

### 1.1 AbstractTestOrderMojo Base Parameters

**Location**: `test-order-maven-plugin/src/main/java/me/bechberger/testorder/plugin/AbstractTestOrderMojo.java`

#### Parameters Analyzed

| Parameter | Type | Default | Description | Issue |
|-----------|------|---------|-------------|-------|
| `indexFile` | String | `${project.basedir}/test-dependencies.lz4` | Index file location | ✅ OK |
| `stateFile` | String | `${project.basedir}/.test-order-state` | State file location | ✅ OK |
| `depsDir` | String | `${project.build.directory}/test-order-deps` | Deps directory | ✅ OK |
| `hashFile` | String | `${project.basedir}/.test-order-hashes.lz4` | Main source hash file | 🟡 **No description** |
| `testHashFile` | String | `${project.basedir}/.test-order-test-hashes.lz4` | Test source hash file | 🟡 **No description** |
| `methodHashFile` | String | `${project.basedir}/.test-order-method-hashes.lz4` | Method hash file | 🟡 **No description** |
| `sourceRoot` | String | Auto-detected | Main source directory | ✅ OK |
| `testSourceRoot` | String | Auto-detected | Test source directory | ✅ OK |
| `changeMode` | String | `auto` | Change detection: auto\|since-last-run\|since-last-commit\|uncommitted\|explicit | ✅ OK but values not clear |
| `changedClasses` | String | None | Comma-separated changed class FQCNs (explicit mode) | ✅ OK |
| `weightsFile` | String | None | Path to scoring weights file | ✅ OK |
| `verboseFile` | String | None | Path for verbose agent log output | 🟡 **Unclear**: when would user set this? |
| `methodOrderingEnabled` | Boolean | `false` | Enable method-level ordering | 🔴 **Critical**: What does this do? Why would you enable it? |

#### Issues Found

**🔴 Critical Issues**:
1. **methodOrderingEnabled** - No description of what it does or when to use it
2. **hashFile, testHashFile, methodHashFile** - These are internal implementation details exposed to users. Should they be configurable?

**🟡 Medium Issues**:
1. **changeMode** - Valid values not documented
2. **verboseFile** - Purpose and use case unclear

**Recommendation**: Add detailed descriptions to all @Parameter annotations.

---

### 1.2 CombinedMojo Parameters

**Location**: `test-order-maven-plugin/src/main/java/me/bechberger/testorder/plugin/CombinedMojo.java`

#### Parameters Analyzed

| Parameter | Type | Default | Issue |
|-----------|------|---------|-------|
| `includePackages` | String | None | 🟡 Name unclear: What format? Regex or exact match? |
| `filterByGroupId` | Boolean | `true` | 🟡 Purpose not documented |
| `instrumentationMode` | String | `FULL` | 🔴 Valid values not clear (FULL, SMART, etc.?) |
| `selectTopN` | Integer | `20` | ✅ OK |
| `selectRandomM` | Integer | `10` | 🟡 Related to selectTopN but naming inconsistent (Top vs Random) |
| `selectSeed` | Long | None | 🟡 Only used with selectRandomM - should document dependency |
| `selectRemainingFile` | String | None | 🟡 Unclear what "remaining" means |
| `selectedFile` | String | None | ✅ OK |
| `runRemaining` | Boolean | `true` | 🟡 Dependency with selectTopN/selectRandomM not clear |
| `optimizeEvery` | Integer | `10` | 🔴 Optimize what? How? Default value reasonable? |

#### Issues Found

**🔴 Critical**:
1. **instrumentationMode** - Valid values and purpose not documented
2. **optimizeEvery** - Optimization strategy unclear

**🟡 Medium**:
1. **includePackages** - Format not specified (regex, wildcard, exact?)
2. **selectRandomM vs selectTopN** - Naming inconsistent (Random vs Top)
3. **selectSeed** - Dependency on selectRandomM not documented
4. **selectRemainingFile** - Purpose unclear
5. **filterByGroupId** - Purpose not documented
6. **runRemaining** - How it interacts with selection modes unclear

---

### 1.3 ShowOrderMojo Parameters

**Location**: `test-order-maven-plugin/src/main/java/me/bechberger/testorder/plugin/ShowOrderMojo.java`

| Parameter | Type | Default | Issue |
|-----------|------|---------|-------|
| `scoreNewTest` | Integer | ? | 🟡 **No default visible** - What is a "new test"? |
| `scoreChangedTest` | Integer | ? | 🟡 **No default visible** - What is a "changed test"? |
| `scoreMaxFailure` | Integer | ? | 🟡 **No default visible** - What does this cap? |

#### Issues Found

1. All three parameters lack visible defaults and clear descriptions

---

### 1.4 Other Mojos

**Analyzed**: AggregateMojo, PreparedMojo, DumpMojo, SelectMojo, RunRemainingMojo, SnapshotMojo, OptimizeMojo

**Finding**: These mojos have minimal parameters and are relatively clear. No major issues.

---

## 2. Maven Plugin Property Constants

**Location**: `MavenPluginConfigKeys.java`

### Issues Found

**🟡 Inconsistent Naming**:
- Properties mix `LEGACY_` prefix with non-legacy properties
- Some use `testorder.` prefix, others don't
- No clear naming convention for new properties

**Example**:
```
LEGACY_INDEX = "testorder.index"
LEGACY_STATE_FILE = "testorder.state-file"
CHANGE_MODE = "testorder.change-mode"  // Why no LEGACY_ prefix?
```

**Recommendation**: Document property naming convention and migrate legacy prefixes.

---

## 3. test-order-cli Parameters

**Location**: `test-order-cli/src/main/java/me/bechberger/testorder/cli/CiDepDownloadManager.java`

### Configuration via YAML (CiConfig)

#### GitHub Configuration

```yaml
github:
  owner: string        # Repo owner ✅
  repo: string         # Repo name ✅
  workflow: string     # Workflow file ✅
  artifact-name: string  # Artifact name ✅
  branch: string?      # Optional - default behavior not documented 🟡
  run-id: integer?     # Run ID - when to use vs workflow? 🟡
```

#### HTTP Configuration

```yaml
http:
  url: string          # Download URL ✅
  auth:
    type: string       # "bearer" or "basic"? Not documented 🟡
    value: string      # Tokens or credentials
```

#### Issues Found

**🔴 Critical**:
1. **auth.type** - Valid values not documented (bearer, basic, none?)
2. **branch** - When is it used? What's the default?

**🟡 Medium**:
1. **run-id vs workflow** - Not clear when to use which
2. **No examples in code** - Users must reverse-engineer from tests

---

## 4. test-order-coverage-mojo Parameters

**Location**: `test-order-coverage-mojo/src/main/java/me/bechberger/testorder/coverage/CoverageMojo.java`

| Parameter | Type | Default | Issue |
|-----------|------|---------|-------|
| `threshold` | Integer | `50` | 🟡 What is threshold? Coverage %? Why 50? |
| `outputDir` | File | `${project.build.directory}/coverage-reports` | ✅ OK |
| `outputFormat` | String | `comprehensive` | 🔴 Valid values not clear (comprehensive, json, markdown?) |
| `includeModules` | String | None | 🟡 Format not specified (comma-separated? regex? exact-match?) |

#### Issues Found

**🔴 Critical**:
1. **outputFormat** - Valid values not documented

**🟡 Medium**:
1. **threshold** - What unit? Why default 50?
2. **includeModules** - Format not specified

---

## 5. Property Naming Inconsistencies

### Identified Patterns

**Pattern 1: Property Key Inconsistency**
```java
// Sometimes uses property attribute
@Parameter(property = "testorder.mode")
// Sometimes uses defaultValue directly
@Parameter(defaultValue = "auto")
```

**Pattern 2: Naming Style Inconsistency**
```
testorder.index              // Hyphenless
testorder.state-file         // Hyphenated
testorder.change-mode        // Hyphenated
testorder.weights-file       // Hyphenated
testorder.verbose-file       // Hyphenated
testorder.method-ordering    // Hyphenated
```

**Recommendation**: Establish consistent property naming convention (prefer hyphenated).

---

## 6. Default Value Concerns

### Issues Found

| Component | Parameter | Default | Concern |
|-----------|-----------|---------|---------|
| CombinedMojo | selectTopN | `20` | Is 20 a good default? Will surprise users expecting all tests |
| CombinedMojo | selectRandomM | `10` | Why 10? What's the rationale? |
| CombinedMojo | optimizeEvery | `10` | Why every 10 runs? Seems arbitrary |
| CoverageMojo | threshold | `50` | Why 50%? Should be documented |
| CoverageMojo | outputFormat | `comprehensive` | Why not `markdown`? More accessible format? |

### Recommendations

1. Document rationale for all non-obvious defaults
2. Consider adding profiles for common scenarios (quick, thorough, ci)
3. Validate defaults work for 80% of use cases

---

## 7. Error Messages & Validation

### Current State

**Good Examples**:
- `AggregateMojo`: Helpful message when no .deps files found
- `CiConfigParser`: Validates required GitHub/HTTP sections

**Missing Validation**:
- 🟡 No validation that changeMode value is valid
- 🟡 No validation that outputFormat value is valid
- 🟡 No validation that instrumentationMode value is valid
- 🟡 No helpful message if weights-file path is invalid

### Recommendations

1. Add validation for enum-like parameters (changeMode, outputFormat, etc.)
2. Provide helpful error messages with valid options
3. Validate file paths exist before processing

---

## 8. Documentation Gaps

### Missing Documentation

1. ❌ CLI Reference Guide (no comprehensive parameter list with examples)
2. ❌ Default Behavior Guide (what happens with default settings?)
3. ❌ Configuration Examples (how to configure for common scenarios?)
4. ❌ Property Naming Convention (canonical names vs legacy)
5. ❌ Mode Explanation (learn vs normal vs explicit)
6. ❌ Interaction Between Parameters (which parameters interact with each other?)

---

## 9. Usability Issues Summary

### By Severity

#### 🔴 Critical (8 issues)
1. methodOrderingEnabled - No description
2. instrumentationMode - Valid values not documented
3. optimizeEvery - Optimization strategy unclear
4. auth.type (CLI) - Valid values not documented
5. outputFormat (coverage-mojo) - Valid values not documented
6. changeMode - Valid values not clearly presented to user
7. No CLI Reference Guide - Users can't easily find options
8. No default behavior documentation - Surprises when parameter omitted

#### 🟡 Medium (12 issues)
1. hashFile, testHashFile, methodHashFile - No descriptions
2. verboseFile - Purpose unclear
3. includePackages - Format not specified
4. selectRandomM vs selectTopN - Naming inconsistent
5. selectSeed - Dependency not documented
6. selectRemainingFile - Purpose unclear
7. filterByGroupId - Purpose not documented
8. runRemaining - Interaction unclear
9. scoreNewTest, scoreChangedTest, scoreMaxFailure - No defaults shown
10. branch (CLI) - Behavior not documented
11. run-id vs workflow (CLI) - When to use which
12. includeModules - Format not specified

#### 🟢 Low (8 issues)
1. Property naming inconsistency - Mix of hyphenated and non-hyphenated
2. Missing examples in code comments
3. No troubleshooting section
4. No migration guide for legacy properties
5. Default values lack rationale
6. Cross-references between related parameters missing
7. No "quick start" profile
8. No performance implications documented

---

## 10. Recommendations (Priority Order)

### Phase 1: Critical Fixes (This Phase)
1. ✅ Add descriptions to all undocumented @Parameter annotations
2. ✅ Document valid values for enum-like parameters (changeMode, outputFormat, etc.)
3. ✅ Create CLI Reference Guide with all options and examples
4. ✅ Add @Parameter descriptions to coverage-mojo
5. ✅ Document default value rationale

### Phase 2: Medium Improvements
1. Establish property naming convention (canonical vs legacy)
2. Add parameter validation with helpful error messages
3. Create Configuration Examples document
4. Document parameter interactions
5. Add troubleshooting section to README

### Phase 3: Nice-to-Have Enhancements
1. Create "quick start" profiles (dev, ci, thorough)
2. Add migration guide for legacy properties
3. Document performance implications
4. Add interactive configuration wizard
5. Provide IDE integration hints

---

## 11. Estimated Effort

| Task | Effort | Priority |
|------|--------|----------|
| Add @Parameter descriptions | 2-3 hours | 🔴 Critical |
| Create CLI Reference Guide | 3-4 hours | 🔴 Critical |
| Document enum values | 1-2 hours | 🔴 Critical |
| Add parameter validation | 2-3 hours | 🟡 Medium |
| Create Configuration Examples | 3-4 hours | 🟡 Medium |
| Property naming convention | 2-3 hours | 🟡 Medium |
| **Total Phase 1** | **13-19 hours** | |

---

## 12. Success Criteria for Phase 9A

✅ All @Parameter annotations have descriptions
✅ Valid values documented for all enum-like parameters
✅ CLI Reference Guide created and comprehensive
✅ Error messages improved with helpful suggestions
✅ Default values documented with rationale
✅ Parameter interactions documented
✅ Examples provided for common scenarios
✅ No user confusion about option meanings

---

## Files to Modify

1. **test-order-maven-plugin/src/main/java/me/bechberger/testorder/plugin/AbstractTestOrderMojo.java**
   - Add descriptions to 6 @Parameter annotations

2. **test-order-maven-plugin/src/main/java/me/bechberger/testorder/plugin/CombinedMojo.java**
   - Add descriptions to 10 @Parameter annotations
   - Document parameter interactions

3. **test-order-maven-plugin/src/main/java/me/bechberger/testorder/plugin/ShowOrderMojo.java**
   - Add descriptions to 3 @Parameter annotations
   - Document default values

4. **test-order-coverage-mojo/src/main/java/me/bechberger/testorder/coverage/CoverageMojo.java**
   - Add descriptions to all @Parameter annotations

5. **test-order-cli/src/main/java/me/bechberger/testorder/cli/CiConfig.java**
   - Add documentation for YAML structure

6. **README.md** (or new CLI_REFERENCE.md)
   - Create comprehensive parameter reference
   - Add examples for each major option

7. **docs/CONFIGURATION.md** (NEW)
   - Configuration guide with examples
   - Default behavior explanation
   - Common scenarios

---

## Next Steps

1. ✅ **Review this audit** - Stakeholder confirmation of findings
2. 🔄 **Add @Parameter descriptions** - Update all Mojo files
3. 🔄 **Create CLI Reference Guide** - New markdown document
4. 🔄 **Improve error messages** - Add validation and helpful output
5. 🔄 **Test with sample projects** - Verify improved usability

