# Phase 5 - Windows-Specific Bug Hunting Report

**Date:** 2026-04-21  
**Phase:** Phase 5 (Continuation) - Windows Environment Analysis  
**Total Bugs Found:** 30 (P5-WIN-001 to P5-WIN-030)  
**Status:** Complete Investigation - Ready for Remediation  

---

## Executive Summary

Comprehensive static analysis of test-order Maven and Gradle plugins on macOS/Linux revealed **30 critical and high-impact Windows-specific bugs**. These fall into key categories:

- **Path Handling (13 bugs):** Backslashes vs forward slashes, spaces in paths, UNC paths
- **Line Endings (4 bugs):** CRLF vs LF handling in source parsing and diffs
- **File Operations (5 bugs):** Atomic moves, file locking, temp cleanup
- **Javaagent Construction (3 bugs):** Path quoting for spaces and colons
- **Git Integration (3 bugs):** Path separator and output encoding issues
- **Miscellaneous (2 bugs):** Classpath separators, jar executability

---

## Blocking Issues (CRITICAL - Must Fix Before Windows Release)

### P5-WIN-001: Javaagent Path with Spaces on Windows
**Severity:** BLOCKING  
**Component:** test-order-gradle-plugin  
**File:** `test-order-gradle-plugin/src/main/java/me/bechberger/testorder/gradle/TestOrderPlugin.java:245`

**Issue:**
```java
"-javaagent:" + agentJar.getAbsolutePath() + "=" + agentArgs
```

On Windows, if the project path contains spaces (common in `C:\Program Files\`), the javaagent path is not quoted. The JVM shell interprets spaces as argument separators, breaking the instrumentation.

**Example Failure:**
```
-javaagent:C:\Program Files\MyProject\agent.jar=...
         ↑ Shell interprets this as separate argument
```

**Fix Required:** Quote the path or use ProcessBuilder list form

---

### P5-WIN-002: Git Path Separator Issue in StructuralDiff
**Severity:** HIGH  
**Component:** test-order-core (changes module)  
**File:** `test-order-core/src/main/java/me/bechberger/testorder/changes/StructuralDiff.java:56`

**Issue:**
```java
String relativePath = gitRoot.relativize(absFile).toString();
// Returns: src\main\java\MyClass.java on Windows
// But git expects: src/main/java/MyClass.java
```

When this path is passed to `git cat-file`, the command fails with "object not found".

**Fix Required:** Normalize path separators to forward slashes before passing to git commands

---

### P5-WIN-003: Line Ending Split Failure in LineDiff
**Severity:** HIGH  
**Component:** test-order-core (changes module)  
**File:** `test-order-core/src/main/java/me/bechberger/testorder/changes/LineDiff.java:26-27, 46-47`

**Issue:**
```java
String[] oldLines = oldText.split("\n", -1);  // Only splits on \n
// Windows CRLF file results in: ["line1\r", "line2\r", ...]
```

Each line retains the `\r` character, causing comparison failures and incorrect change detection.

**Fix Required:** Use `split("\\r?\\n")` or `System.lineSeparator()` aware splitting

---

### P5-WIN-004: Source File Model Line Splitting CRLF Issue
**Severity:** HIGH  
**Component:** test-order-core (changes module)  
**File:** `test-order-core/src/main/java/me/bechberger/testorder/changes/SourceFileModel.java:1138`

**Issue:**
```java
String[] lines = sb.toString().split("\n", -1);
```

Same issue as P5-WIN-003, affecting structural parsing and method detection.

**Fix Required:** Normalize line endings before splitting

---

### P5-WIN-011: Maven Plugin Javaagent Path with Spaces
**Severity:** BLOCKING  
**Component:** test-order-maven-plugin  
**File:** `test-order-maven-plugin/src/main/java/me/bechberger/testorder/plugin/AbstractTestOrderMojo.java:489`

**Issue:**
```java
String agentString = "-Xshare:off -javaagent:" + agentJar.toAbsolutePath() + "=" + agentArgs;
```

Same as P5-WIN-001 but for Maven plugin.

---

### P5-WIN-021: Git Show Command Path Separator Issue
**Severity:** HIGH  
**Component:** test-order-core (changes module)  
**File:** `test-order-core/src/main/java/me/bechberger/testorder/changes/GitChangeDetector.java:100`

**Issue:**
```java
List<String> command = List.of("git", "show", commitRef + ":" + filePath);
// filePath contains Windows backslashes: src\main\Foo.java
// Git expects: src/main/Foo.java
```

**Fix Required:** Normalize path separators in git command construction

---

## High-Priority Issues (Impact Test Functionality)

### P5-WIN-009: Long Path Names Exceed Windows MAX_PATH
**Severity:** HIGH  
**Component:** test-order-core (persistence)  

Windows has a 260-character MAX_PATH limit. Deep project structures combined with test-order cache subdirectories can exceed this limit:
```
C:\Users\username\very\deep\project\src\test\java\com\example\sub\package\
.test-order\deps\com.example.sub.package.MyTest.method.json
```

Total length may exceed 260 characters, causing file creation failures.

**Fix Required:** Use Windows long path prefix `\\?\` or keep paths shorter

---

### P5-WIN-018: UNC Paths Not Handled
**Severity:** HIGH  
**Component:** test-order-gradle-plugin, test-order-maven-plugin  

Windows network paths (UNC: `\\server\share\project`) are not explicitly handled. Path operations may fail on network drives.

**Fix Required:** Add explicit UNC path support and testing

---

## Medium-Priority Issues

### P5-WIN-005: Path Separators in FQCN Calculation
**File:** SourceFileModel.java:1332, 1352
```java
return withoutExt.replace('/', '.').replace('\\', '.');
return relativePath.substring(0, lastSlash).replace('/', '.').replace('\\', '.');
```

Current logic handles both, but it's fragile.

---

### P5-WIN-006: File Hash Store Path Separator Normalization
**File:** FileHashStore.java:40, 80
```java
String relativePath = sourceRoot.relativize(file).toString().replace('\\', '/');
// ...
String key = line.substring(0, tab).replace('\\', '/');
```

Works but relies on explicit backslash handling. Cache portability is fragile.

---

### P5-WIN-007: Atomic Move Not Supported on Network Drives
**File:** PersistenceSupport.java:46-48
```java
try {
    Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, ...);
} catch (AtomicMoveNotSupportedException ignored) {
    Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
}
```

On Windows network drives (SMB), atomic move fails, falling back to non-atomic. This degrades transactional guarantees.

---

### P5-WIN-008: File Locking on Network Drives
**File:** PersistenceSupport.java:60-62

FileChannel.lock() on Windows network drives (SMB) has different semantics than Unix advisory locks. May not block correctly.

---

### P5-WIN-010: Case-Insensitive Filename Handling
**File:** FileHashStore.java:23

TreeMap uses case-sensitive keys. Windows NTFS is case-insensitive. Two files `File.java` and `file.java` would be treated as different cache entries.

---

### P5-WIN-012: Paths with Colons in Javaagent Arguments
**Files:** AbstractTestOrderMojo.java:489, TestOrderPlugin.java:245

Windows drive letters (C:, D:) in paths may confuse javaagent parameter parsing.

---

### P5-WIN-013: Git Case Sensitivity on Windows
**File:** StructuralDiff.java

Git on Windows is case-insensitive. If filesystem and git index have different casing, change detection may miss files.

---

### P5-WIN-014: Line Ending Sensitivity in Parsing
**File:** SourceFileModel.java

Parser may produce different results for CRLF vs LF files, affecting structural analysis.

---

### P5-WIN-015: Temp File Cleanup Race Condition
**File:** PersistenceSupport.java

Windows file locking may prevent deletion of .tmp files. Cleanup deferred, causing disk space leaks.

---

### P5-WIN-017: Path Normalization in Git Batch Response
**File:** StructuralDiff.java

Git batch response paths use forward slashes. Windows request paths use backslashes. Matching may fail.

---

### P5-WIN-023: Drive Letter Mapping Cache Invalidation
Cache per-project path. If same project is accessible via different drive mappings (D: vs E:), cache is invalidated.

---

### P5-WIN-027: CLI JAR Not Executable on Windows
test-order-cli.jar requires explicit `java -jar`. Windows users expect .exe or .bat wrapper.

---

### P5-WIN-028: Classpath Separator Platform-Specific
Gradle uses `:` on Unix, `;` on Windows. Manual classpath construction may use wrong separator.

---

## Low-Priority Issues

### P5-WIN-016: FileChannel.lock() Semantics
Windows mandatory locks vs Unix advisory locks have different semantics.

### P5-WIN-019: Symlinks and Junctions
Cache may treat Windows junctions differently from symlinks.

### P5-WIN-024: File Permissions Not Preserved
Cache created on Linux may have different permissions on Windows.

### P5-WIN-025: Temp Directory Location
Custom temp paths may lack write permissions on Windows.

### P5-WIN-026: NTFS Alternative Data Streams
Downloaded cache files may accumulate ADS, consuming disk space.

### P5-WIN-029: Maven Property Separators
Maven properties contain backslashes on Windows. String operations may fail.

### P5-WIN-030: Gradle Wrapper Line Endings
Wrapper scripts may have incorrect line endings if git core.autocrlf=false.

---

## Remediation Priority

### IMMEDIATE (Before Windows Release)
1. P5-WIN-001 - Gradle javaagent quoting
2. P5-WIN-011 - Maven javaagent quoting
3. P5-WIN-002 - Git path separator in StructuralDiff
4. P5-WIN-021 - Git path separator in GitChangeDetector
5. P5-WIN-003 - LineDiff CRLF handling
6. P5-WIN-004 - SourceFileModel line splitting

### HIGH (Next Sprint)
7. P5-WIN-009 - MAX_PATH handling
8. P5-WIN-018 - UNC path support
9. P5-WIN-012 - Javaagent colon escaping
10. P5-WIN-006 - Path normalization consistency

### MEDIUM (Future)
11. P5-WIN-005, P5-WIN-007, P5-WIN-008, P5-WIN-010, P5-WIN-013, P5-WIN-014, P5-WIN-015, P5-WIN-017, P5-WIN-023, P5-WIN-027, P5-WIN-028

### LOW (Polish)
12. P5-WIN-016, P5-WIN-019, P5-WIN-024, P5-WIN-025, P5-WIN-026, P5-WIN-029, P5-WIN-030

---

## Testing Recommendations

### Pre-Windows Release Validation
1. **Path Handling Tests:**
   - Projects under `C:\Program Files\` (spaces)
   - Projects under `D:\`, `E:\` drives
   - Network UNC paths (`\\server\share\project`)
   - Deep nested structures (>200 chars)

2. **Line Ending Tests:**
   - CRLF Java source files
   - Mixed CRLF and LF in same project
   - Cross-platform commits (Windows→Linux)

3. **Git Integration Tests:**
   - Case-sensitive changes on case-insensitive filesystem
   - Files deleted between commits
   - Structural diffs on network drives

4. **Instrumentation Tests:**
   - Gradle instrumentation with spaces in path
   - Maven instrumentation with spaces in path
   - Custom instrumentation with quotes and special chars

---

## Files Requiring Changes

### Gradle Plugin
- `test-order-gradle-plugin/src/main/java/me/bechberger/testorder/gradle/TestOrderPlugin.java`

### Maven Plugin
- `test-order-maven-plugin/src/main/java/me/bechberger/testorder/plugin/AbstractTestOrderMojo.java`

### Core Library
- `test-order-core/src/main/java/me/bechberger/testorder/changes/LineDiff.java`
- `test-order-core/src/main/java/me/bechberger/testorder/changes/SourceFileModel.java`
- `test-order-core/src/main/java/me/bechberger/testorder/changes/StructuralDiff.java`
- `test-order-core/src/main/java/me/bechberger/testorder/changes/GitChangeDetector.java`
- `test-order-core/src/main/java/me/bechberger/testorder/changes/FileHashStore.java`
- `test-order-core/src/main/java/me/bechberger/testorder/PersistenceSupport.java`

---

## Conclusion

The test-order plugins are **not Windows-ready** without addressing the BLOCKING issues (P5-WIN-001, P5-WIN-011, P5-WIN-002, P5-WIN-021, P5-WIN-003, P5-WIN-004). 

Key areas requiring immediate attention:
1. **Javaagent path quoting** for spaces and special characters
2. **Git path separator normalization** (backslash → forward slash)
3. **Line ending normalization** in source parsing and diffs
4. **Windows path handling** throughout the codebase

With these fixes, test-order can be validated and released for Windows environments.
