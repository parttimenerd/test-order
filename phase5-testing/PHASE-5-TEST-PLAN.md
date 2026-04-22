# Phase 5 Bug Hunt: Windows & Legacy Testing

## Scope
Test areas not covered by Phases 1-4:
1. Windows-specific behavior
2. Legacy JUnit versions (3.x, 4.0-4.8, mixed)
3. Edge cases with unusual naming and configurations

## Test Projects to Create

### Category 1: Windows Path Handling
- test-project-windows-paths/
- Test cases:
  - Backslash path separators in cache/state files
  - Drive letters (C:\, D:\)
  - UNC paths (\\server\share)
  - Case-insensitive filesystem
  - Long paths (260+ characters)
  - Reserved filenames (CON, PRN, AUX, NUL, COM1-COM9, LPT1-LPT9)

### Category 2: Windows File Behavior
- test-project-windows-files/
- Test cases:
  - CRLF line endings in source files
  - File locking (can't delete while in use)
  - Read-only files
  - Short vs long filename support
  - Symlinks vs junctions

### Category 3: Legacy JUnit 3.x
- test-project-junit3/
- Test cases:
  - Classes extending TestCase
  - test* methods instead of @Test annotations
  - setUp/tearDown methods
  - suite() static method
  - No annotations at all

### Category 4: Legacy JUnit 4.0-4.8
- test-project-junit4-old/
- Test cases:
  - Early 4.0 syntax
  - No assumptions
  - Old @BeforeClass/@AfterClass
  - Static suite() method
  - Multiple test runners

### Category 5: Mixed JUnit Versions
- test-project-mixed-junit/
- Test cases:
  - JUnit 3 and 4 in same project
  - Both TestCase extends and @Test annotations
  - Mixed runners

### Category 6: Edge Case Class Names
- test-project-edge-names/
- Test cases:
  - Extremely long class names (500+ chars)
  - Special characters: Test$, Test#, Test@, Test&
  - Numeric-only names: Test123, 456Test
  - Default package (no package declaration)
  - Package names with numbers and special chars

### Category 7: Legacy TestNG
- test-project-testng-old/
- Test cases:
  - Old TestNG syntax
  - @Test on classes vs methods
  - testng.xml configuration

## Bug Categories to Hunt
1. **Silent Failures:** Tests silently skipped, no error reported
2. **Wrong Error Messages:** Confusing or misleading errors
3. **Data Corruption:** Cache corruption, cache inconsistency
4. **Path Handling:** Incorrect path manipulation on different OS
5. **Encoding Issues:** CRLF vs LF confusion
6. **Class Discovery:** Tests not discovered or discovered twice
7. **Performance:** Extreme slowness or hanging
8. **Thread Safety:** Concurrent access issues
9. **Resource Leaks:** File handles not closed
10. **Security:** Paths can be exploited, command injection

## Expected Outcomes
- Categorized bug report for each finding
- Reproduction steps
- Severity assessment (CRITICAL/HIGH/MEDIUM/LOW)
- Suggested fixes
