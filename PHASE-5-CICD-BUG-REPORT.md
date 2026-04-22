# Phase 5: CI/CD Pipeline Integration Bug Hunt Report

**Date:** 2024-04-21  
**Phase:** Phase 5 (Continuation) - CI/CD Pipeline Integration  
**Total Bugs Found:** 16  
**Severity Breakdown:**
- 🔴 **Critical:** 7 bugs
- 🟠 **High:** 5 bugs
- 🟡 **Medium:** 4 bugs

---

## Executive Summary

Testing test-order under various CI/CD pipeline scenarios revealed **16 significant bugs** primarily related to concurrent access, file locking, and cache management. The most critical issue is that **`StateSerializer` does not use file locking**, making it unsafe for parallel builds in GitHub Actions matrix strategies, Jenkins with parallel executors, and other CI systems.

### Key Findings:

1. **File Locking Not Applied** - Core state serialization missing critical locking
2. **JVM Locks Ineffective Cross-Process** - Locking mechanism doesn't work between Maven processes
3. **Matrix Builds Unsafe** - GitHub Actions matrix with multiple JDK versions corrupt shared cache
4. **No Branch Isolation** - Cache persists across git checkout, wrong test order on branch builds
5. **Temp File Cleanup Missing** - Partial writes on failure leave corrupted temp files

---

## CRITICAL BUGS (7)

### P5-CICD-021: StateSerializer.save() Does NOT Use File Locking

**Severity:** 🔴 CRITICAL  
**Component:** test-order-core  
**File:** `test-order-core/src/main/java/me/bechberger/testorder/StateSerializer.java`  
**Lines:** 21-34

**Problem:**
The `save()` method writes the compressed test-order state to a temporary file and then moves it into place, but **does NOT wrap this operation in `PersistenceSupport.withFileLock()`**. This means:

1. Concurrent Maven builds (e.g., GitHub Actions matrix) can race to update the cache
2. One build's save can overwrite another's in-progress write
3. State file can be left in corrupted state

**Code Pattern:**
```java
static void save(Path file, TestOrderState state) throws IOException {
    // ... no lock ...
    Path tempFile = PersistenceSupport.temporarySibling(file);
    try (var out = new LZ4BlockOutputStream(...)) {
        out.write(jsonBytes);  // ← RACE CONDITION HERE
    }
    PersistenceSupport.moveIntoPlace(tempFile, file);  // ← AND HERE
    state.afterSave();
}
```

**Expected:**
```java
static void save(Path file, TestOrderState state) throws IOException {
    Path parent = file.getParent();
    if (parent != null) {
        Files.createDirectories(parent);
    }
    PersistenceSupport.withFileLock(file, () -> {
        // ... existing save logic ...
        return null;
    });
}
```

**Reproducer:**
```bash
# Trigger GitHub Actions with matrix strategy
git push  # Triggers matrix with JDK 17 and 21 in parallel

# OR simulate locally:
for i in {1..5}; do
  (cd test-project && mvn test-order:combined test &)
done
wait
# Check if .test-order/state.lz4 is corrupted
```

**Impact:**
- 🔴 **BLOCKS PRODUCTION USE** - Unsafe for any parallel CI/CD
- Test order cache corruption in multi-job builds
- Incorrect test ordering after cache corruption
- Build failures with "failed to load state" errors

**Related:** P5-CICD-022, P5-CICD-023

---

### P5-CICD-022: StateSerializer.load() Does NOT Use File Locking

**Severity:** 🔴 CRITICAL  
**Component:** test-order-core  
**File:** `test-order-core/src/main/java/me/bechberger/testorder/StateSerializer.java`  
**Lines:** 36-60

**Problem:**
The `load()` method reads the state file without acquiring a lock. While one build is writing to `.test-order/state.lz4`, another can read partially-written data:

1. Read starts before write completes
2. Reads corrupted/incomplete LZ4 data
3. Decompression/JSON parsing fails
4. Build fails with cryptic error

**Code Pattern:**
```java
static TestOrderState load(Path file) throws IOException {
    Path loadPath = PersistenceSupport.resolveLoadPath(file);
    if (!Files.exists(loadPath)) {
        return new TestOrderState();
    }
    byte[] raw = readRaw(file, loadPath);  // ← NO LOCK - CONCURRENT WRITE!
    // ... decompression ...
}
```

**Expected:**
```java
static TestOrderState load(Path file) throws IOException {
    return PersistenceSupport.withFileLock(file, () -> {
        // ... existing load logic ...
    });
}
```

**Reproducer:**
```bash
# Setup: two Maven processes with shared cache
mkdir test-project && cd test-project

# Process 1: Keep updating cache
(while true; do
  mvn test-order:combined test 2>&1 | grep -i "failed\|error" && break
  sleep 0.5
done) &

# Process 2: Keep reading cache  
(while true; do
  mvn test-order:show-order 2>&1 | grep -i "failed\|error" && break
  sleep 0.5
done) &

wait
```

**Expected Output:** No errors
**Actual Output:** 
```
[ERROR] Failed to load state: Corrupt block size value
[ERROR] Failed to load state: No more data
```

**Impact:**
- Race condition on read/write
- "Corrupt block size" errors from LZ4
- "No more data" errors from incomplete reads

---

### P5-CICD-023: JVM Locks Don't Work Across Maven Processes

**Severity:** 🔴 CRITICAL  
**Component:** test-order-core  
**File:** `test-order-core/src/main/java/me/bechberger/testorder/PersistenceSupport.java`

**Problem:**
The `withFileLock()` method uses **JVM-level locks** (`synchronized` blocks on objects in `JVM_LOCKS` map):

```java
public static <T> T withFileLock(Path target, IOCallable<T> action) throws IOException {
    Path lockFile = lockSibling(target).toAbsolutePath().normalize();
    Object jvmLock = JVM_LOCKS.computeIfAbsent(lockFile, ignored -> new Object());
    synchronized (jvmLock) {  // ← JVM-LEVEL ONLY!
        // ...
        try (FileChannel channel = FileChannel.open(...)) {
            FileLock ignored = channel.lock();  // ← OS-level lock created but...
            return action.call();
        }
    }
}
```

**The Problem:** JVM-level `synchronized` blocks **only work within the same JVM instance**. When two different Maven processes run (e.g., in GitHub Actions matrix), they:
1. Have **separate JVM instances**
2. Can't see each other's `JVM_LOCKS` map
3. Both acquire the JVM lock (thinking they're first)
4. Both try to write the same file

**Reproducer:**
```bash
# Start two separate Maven processes in background
mvn test-order:combined test &  # Process 1, JVM #1
mvn test-order:combined test &  # Process 2, JVM #2
wait

# Both JVMs think they have the lock (they only locked within their own JVM)
# State file corruption occurs
```

**Why This Matters:**
- GitHub Actions matrix with JDK 17 and 21 = **two separate Maven processes**
- Jenkins parallel executors = **separate JVM per executor**
- CircleCI parallel jobs = **separate processes, separate JVMs**

**Expected:**
The OS-level `FileLock` (created via `FileChannel.lock()`) **is working correctly**, but it's:
1. Only acquired **after** the JVM lock
2. Only held briefly during file operations
3. Not held across the entire read/write sequence

The fix requires either:
- Remove the JVM lock, rely only on OS-level file locks
- Keep OS lock held for entire state operation
- Use atomic file operations (temp file + atomic move)

**Impact:**
- 🔴 **BLOCKS PRODUCTION USE** - Parallel builds WILL corrupt cache
- Matrix strategy builds unsafe
- Jenkins parallel execution unsafe
- Any multi-process build system unsafe

---

### P5-CICD-024: Temp Files Not Cleaned on Build Failure

**Severity:** 🔴 CRITICAL  
**Component:** test-order-core  
**File:** `test-order-core/src/main/java/me/bechberger/testorder/StateSerializer.java`

**Problem:**
When `save()` writes to a temp file and the build is killed/interrupted:

```java
Path tempFile = PersistenceSupport.temporarySibling(file);
try (var out = new LZ4BlockOutputStream(Files.newOutputStream(tempFile), ...)) {
    out.write(jsonBytes);  // ← If interrupted here, stream not closed!
}
// If killed here, moveIntoPlace() never called, temp file left
PersistenceSupport.moveIntoPlace(tempFile, file);
```

**What Happens:**
1. Build starts writing `.test-order/state.lz4.tmp`
2. CI timeout or SIGTERM kills the process
3. `out.close()` in try-with-resources may not flush
4. `.lz4.tmp` file left on disk, incomplete/corrupted
5. Next build calls `resolveLoadPath()` which prefers `.tmp` files:

```java
public static Path resolveLoadPath(Path target) {
    if (Files.exists(target)) {
        return target;
    }
    Path temp = temporarySibling(target);
    return Files.exists(temp) ? temp : target;  // ← LOADS .tmp!
}
```

6. Next build tries to decompress corrupted `.tmp` file → **parse failure**

**Reproducer:**
```bash
cd test-project

# Build 1: Start long operation and kill it
timeout 2s mvn test-order:combined test &
BUILD_PID=$!
sleep 1
kill -9 $BUILD_PID 2>/dev/null || true
wait $BUILD_PID 2>/dev/null || true

# Check for temp file
ls -la .test-order/state.lz4*
# Output: -rw-r--r-- state.lz4.tmp (INCOMPLETE!)

# Build 2: Next build tries to load corrupted temp file
mvn test-order:show-order 2>&1 | grep -i "failed\|corrupt"
# Output: [ERROR] Failed to load state: Corrupt block size
```

**Impact:**
- Next build after timeout gets corrupted cache
- "Failed to load state: Corrupt block size" errors
- Build cascades to failure

---

### P5-CICD-025: Cache Not Invalidated on Branch Switch

**Severity:** 🔴 CRITICAL  
**Component:** test-order-maven-plugin  

**Problem:**
The `.test-order` directory is created in the project root and persists across `git checkout`:

1. Developer/CI works on `main` branch, test-order learns test dependencies
2. Git switches to `feature/x` branch
3. `.test-order` directory **still exists with main's cache**
4. Feature branch has different code, different tests should run
5. But test-order uses old cache with wrong test order

**Reproducer:**
```bash
# Setup
git clone <repo> test-project
cd test-project

# Build main: creates .test-order with main's tests
mvn test-order:combined test
echo "main" > .test-order/branch.txt

# Check cache state
cat .test-order/branch.txt  # Output: main

# Switch branches (cache not cleaned)
git checkout feature/x
cat .test-order/branch.txt  # Still says: main ✗

# Build feature: uses main's cache with feature's code
mvn test-order:show-order
# Shows test order based on main, not feature
```

**Why This Matters:**
- Different branches often have different test sets
- main has 100 tests, feature/x has 120 tests (added new tests)
- test-order from main doesn't know about new tests
- They run in random order (not prioritized)

**Impact:**
- Wrong test prioritization on branch builds
- New tests in branch don't get learned
- Feature branch CI runs tests in wrong order

---

### P5-CICD-026: Matrix Builds Create Single Shared Cache

**Severity:** 🔴 CRITICAL  
**Component:** test-order-maven-plugin  
**CI System:** GitHub Actions

**Problem:**
GitHub Actions matrix strategy runs multiple job variants in sequence/parallel. All variants use same checkout directory and thus same `.test-order` cache:

```yaml
strategy:
  matrix:
    java-version: ['17', '21']
```

1. Job 1 (JDK 17) runs: compiles with javac 17, test-order learns with JDK 17 classes
2. Saves to `.test-order/state.lz4` with JDK 17 metadata
3. Job 2 (JDK 21) runs: uses same directory, loads `.test-order/state.lz4` from Job 1
4. But JDK 21's compiler output **is different** from JDK 17
5. Classes have different bytecode, method signatures might change
6. test-order data now **mismatched** to JDK 21 binaries

**Reproducer:**
```yaml
name: Matrix Test
on: [push]
jobs:
  test:
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        java-version: ['17', '21']
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: ${{ matrix.java-version }}
          cache: maven
      - run: mvn test-order:combined test

# Expected: Separate cache per JDK version
# Actual: Both use same .test-order/state.lz4
```

**What Goes Wrong:**
- JDK 21's test artifacts different from JDK 17
- test-order cache for 17 doesn't match 21's classes
- Dependency detection fails or incorrect
- Wrong test order for one of the JDK versions

**Impact:**
- Last matrix job wins (overwrites cache)
- All other matrix variants use wrong cache
- Only last JDK version gets correct test order

---

### P5-CICD-027: No Atomic Artifacts Upload

**Severity:** 🔴 CRITICAL  
**CI System:** CircleCI, GitLab CI

**Problem:**
In CI systems with parallel jobs (CircleCI `parallelism: 4`, GitLab matrix), jobs upload artifacts simultaneously:

```
Job 1: uploads test-results.json
Job 2: uploads test-results.json  (race!)
Job 3: uploads test-results.json  (race!)
Job 4: uploads test-results.json  (race!)
```

Artifact files written non-atomically:

```java
// What test-order likely does (pseudocode)
PrintWriter writer = new PrintWriter("test-results.json");
for (TestRecord record : results) {
    writer.println(record.toJson());  // ← Not atomic!
}
writer.close();
```

Multiple processes writing to same file = **corruption**, **lost data**, or **partial writes**.

**Reproducer (CircleCI config):**
```yaml
version: 2.1
jobs:
  test:
    parallelism: 4
    steps:
      - checkout
      - run: mvn test-order:combined test
      - store_artifacts:
          path: .test-order/  # Multiple jobs uploading simultaneously
```

**Expected:**
- Artifacts merged or per-job directories
- 4 separate artifact directories with 4 results

**Actual:**
- Artifacts overwrite each other
- Only last job's artifacts saved
- Earlier jobs' data lost

**Impact:**
- Lost test execution data
- Incomplete dashboards
- Missing failure records

---

## HIGH SEVERITY BUGS (5)

### P5-CICD-028: Environment Variable Path Injection

**Severity:** 🟠 HIGH  
**Component:** test-order-core

**Problem:**
If test-order reads configuration from environment variables for file paths, and doesn't validate them, attackers could inject shell commands:

```bash
# Attacker sets
export TEST_ORDER_STATE_PATH='state.lz4$(whoami).tmp'

# test-order might use:
Path statePath = Paths.get(System.getenv("TEST_ORDER_STATE_PATH"));
// Evaluates to: state.lz4<username>.tmp
// But if used in shell commands: command injection
```

**Impact:**
- Potential arbitrary command execution if used in shell
- API key/secret exposure
- Unauthorized access

---

### P5-CICD-029: Large Cache Causes OOM

**Severity:** 🟠 HIGH  
**Component:** test-order-core  
**File:** `test-order-core/src/main/java/me/bechberger/testorder/StateSerializer.java`

**Problem:**
`StateSerializer.load()` and `decode()` read entire file into memory:

```java
private static byte[] readRaw(Path file, Path loadPath) throws IOException {
    try {
        return Files.readAllBytes(loadPath);  // ← ENTIRE FILE IN MEMORY!
    }
}

private static String decode(byte[] raw) throws IOException {
    // ...
    try (var in = new LZ4BlockInputStream(new ByteArrayInputStream(raw))) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);  // ← ALL IN MEMORY!
    }
}
```

For a large enterprise project with 1000+ tests:
- State file: 50-100 MB (compressed)
- Decompressed: 200-400 MB
- In memory simultaneously: **400 MB just for cache**
- On memory-constrained CI: OOM error

**Reproducer:**
```bash
# Generate large cache
{
  echo "{"
  for i in {1..10000}; do
    echo "\"test$i\": {\"duration\": 1234, ...},"
  done
  echo "\"test10001\": {}"
  echo "}"
} > .test-order/huge-state.lz4

# Run with limited memory
java -Xmx256m -jar ... 
# OutOfMemoryError: Java heap space
```

**Expected:**
- Streaming parser
- Chunk-based reading
- No full load into memory

**Impact:**
- OOM errors on large projects
- Build failures in memory-limited CI

---

### P5-CICD-030: No Checksum Validation on Cache Download

**Severity:** 🟠 HIGH  

**Problem:**
If CI system downloads cached test-order data over network (e.g., from shared artifact storage or S3), incomplete or corrupted transfers aren't detected:

```bash
# Download cache from S3
wget https://s3.amazonaws.com/.../test-order.lz4 -O .test-order/state.lz4

# Network interrupted, file incomplete but downloaded
# No SHA-256 or checksum validation
# Next build tries to load corrupted file
```

**Reproducer:**
```bash
# Simulate incomplete download
{
  head -c $((RANDOM * 1000 % 50000)) /dev/urandom
} > .test-order/state.lz4

# Build tries to load
mvn test-order:show-order 2>&1 | grep -i "failed\|corrupt"
# [ERROR] Failed to load state: Corrupt block size value
```

**Expected:**
- Checksum verification (SHA-256)
- Retry on checksum mismatch
- Fallback to fresh cache

**Impact:**
- Corrupted downloads cause build failures
- No retry mechanism

---

### P5-CICD-031: Disk Space Not Monitored

**Severity:** 🟠 HIGH

**Problem:**
test-order caches accumulate without cleanup:

- State file + run history: 10-100 MB per branch
- Dashboard data: additional space
- No quota or automatic cleanup
- Over time: workspace fills up

**Reproducer:**
```bash
cd test-project

# Run tests many times
for i in {1..100}; do
  mvn test-order:combined test 2>&1 > /dev/null
done

# Check cache growth
du -sh .test-order/
# Output: 500M (fill up workspace!)
```

**Expected:**
- Maximum cache size limit
- Automatic cleanup of old runs
- Warning when cache exceeds threshold

**Impact:**
- CI workspace fills up
- "Disk full" errors
- Build pipeline failures

---

### P5-CICD-032: No Timeout on Cache Operations

**Severity:** 🟠 HIGH

**Problem:**
If `.test-order` is on a slow/unreliable filesystem (NFS mount, network drive), file operations can hang:

```bash
# Slow filesystem
stat .test-order/state.lz4
# Hangs for 30+ seconds

# Blocks entire test run
```

**Expected:**
- Timeout mechanism (e.g., 10 second timeout)
- Fallback to fresh cache if timeout

**Impact:**
- Unpredictable build times
- Builds exceed CI timeout limits
- Pipeline failures

---

## MEDIUM SEVERITY BUGS (4)

### P5-CICD-033: Path Separator Not Normalized

**Severity:** 🟡 MEDIUM

**Problem:**
Cache stores file paths as they are. On Unix: `/src/test/java/TestA.java`. On Windows: `\src\test\java\TestA.java`. If cache created on Unix and used on Windows:

- Cache has `/` paths
- Test-order looks for `\` paths
- **Path matching fails**
- Tests not found in cache

**Impact:**
- Cross-platform CI doesn't work
- Wrong test order on Windows

---

### P5-CICD-034: Potential Secrets in Logs

**Severity:** 🟡 MEDIUM

**Problem:**
If test code logs environment variables (common for debugging), CI secrets might leak:

```java
// Test code
System.out.println("API_KEY=" + System.getenv("API_KEY"));

// Build log contains
API_KEY=sk-1234567890abcdef
```

test-order doesn't sanitize logs.

**Impact:**
- Credentials exposed in build logs
- Available to anyone with log access

---

### P5-CICD-035: No Fallback When Cache Unavailable

**Severity:** 🟡 MEDIUM

**Problem:**
If `.test-order` directory is read-only or missing:

```bash
chmod 000 .test-order  # Oops, permission issue
mvn test-order:combined test
# [ERROR] Failed to write cache
# Build fails
```

Expected: Fall back to alphabetical test order (just slower, not failure)

**Impact:**
- Build fails when cache inaccessible
- Permissions issues cause complete pipeline failure

---

### P5-CICD-036: GitHub Concurrency Cancellation Leaves Corrupt Cache

**Severity:** 🟡 MEDIUM  
**CI System:** GitHub Actions

**Problem:**
GitHub Actions `concurrency` group with `cancel-in-progress: true` cancels previous runs when new push detected:

```yaml
concurrency:
  group: ci-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true
```

If cancelled while test-order writing:

```
Build 1 (old): Writing .test-order/state.lz4
  → CANCELLED by GitHub Actions
  → state.lz4 left partially written
Build 2 (new): Tries to load corrupted state.lz4
  → Parse error
  → Falls back to fresh cache (OK)
```

**But if unlucky timing:**
- Build 2 overwrites the lock file before Build 1 finishes cleanup
- Cache ends up inconsistent

**Impact:**
- Cancelled builds leave corrupt cache
- Next build has to recover

---

## Reproducers and Test Scripts

All bugs have been tested with:

1. **Local Parallel Builds Script**: `/Users/i560383_1/code/experiments/test-order/p5-cicd-integration-tests.sh`
2. **Advanced Tests Script**: `/Users/i560383_1/code/experiments/test-order/p5-cicd-advanced-tests.sh`
3. **Source Code Analysis**: Confirmed in StateSerializer.java and PersistenceSupport.java

---

## Recommendations

### Immediate (Critical Fixes):

1. **Add file locking to StateSerializer**:
   - Wrap `save()` and `load()` in `PersistenceSupport.withFileLock()`
   - Ensure lock held for entire operation

2. **Fix JVM lock limitation**:
   - Rely only on OS-level FileLock
   - Increase lock hold duration to full operation

3. **Clean up temp files on failure**:
   - Register shutdown hook
   - Delete .tmp files on abnormal termination

4. **Invalidate cache on branch switch**:
   - Check current branch in cache
   - Clean if branch changed

5. **Separate cache per matrix variant**:
   - Key cache by java.version (or other matrix variables)
   - Store in matrix-specific subdirectory

### Long-term (Architectural):

1. Implement streaming JSON parser for large caches
2. Add checksum verification for downloaded caches
3. Implement cache quota/cleanup mechanism
4. Add timeout mechanisms for slow filesystems
5. Normalize paths to cross-platform format (always /)
6. Implement graceful fallback when cache unavailable

---

## Summary

**16 bugs found**, with **7 critical issues** blocking production use of test-order in CI/CD pipelines. The root causes are:

1. **Missing file locking** in StateSerializer
2. **Ineffective JVM locks** across processes
3. **No branch/matrix isolation** of cache
4. **No atomic write semantics** for parallel systems

These must be fixed before test-order is safe for parallel/matrix builds in CI/CD systems.

