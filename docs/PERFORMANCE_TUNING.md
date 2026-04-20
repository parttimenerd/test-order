# Performance Tuning Guide

Deep-dive reference for squeezing the most speed out of test-order in large codebases.

---

## What Affects Performance

```
Total test-order overhead = instrumentation + index-write + index-read + ranking + order-shuffle
```

| Phase | Cost driver | Typical time |
|-------|-------------|-------------|
| Instrumentation (learn) | Class count × complexity | 20-200 ms |
| Index write (learn) | Dependency graph size | 5-50 ms |
| Index read (run) | Index size on disk | 1-15 ms |
| Change detection | Strategy chosen | 1-500 ms |
| Ranking | Test count × dep count | <5 ms |
| JVM startup + fork overhead | Surefire forks | 3-10 s per fork |

**TL;DR** – JVM fork overhead dominates. Tuning fork configuration gives the biggest wins.

---

## Level 1: Low-Effort Wins (< 1 hour)

### 1.1 – Use In-Process Instrumentation

Avoid a second JVM fork for the agent by running tests in the same JVM process:

```xml
<plugin>
    <groupId>me.bechberger</groupId>
    <artifactId>test-order-maven-plugin</artifactId>
    <configuration>
        <!-- Default is "fork", change to "in-process" -->
        <instrumentationMode>in-process</instrumentationMode>
    </configuration>
</plugin>
```

**Tradeoff**: In-process mode cannot instrument classes already loaded by the JVM (e.g., JDK classes). For most projects this is fine.

### 1.2 – Keep the Index File on Local SSD

The index file (`test-dependencies.lz4`) should live on a fast local filesystem:

```xml
<configuration>
    <indexFile>${user.home}/.test-order/cache/${project.artifactId}/test-dependencies.lz4</indexFile>
</configuration>
```

Avoid network drives, cloud-synced folders (Dropbox, iCloud), or encrypted volumes that add latency on every read/write.

### 1.3 – Disable Full Suite on First Run

The first run with no index is a full suite run. Suppress this if working on a branch that has an existing main-branch index:

```xml
<configuration>
    <!-- If no index exists, do nothing (don't run full suite) -->
    <runFullSuiteWhenNoIndex>false</runFullSuiteWhenNoIndex>
</configuration>
```

Alternatively, download the main-branch index from CI (see CI Dependency Download section in ADVANCED_USAGE.md).

---

## Level 2: Medium-Effort Wins (a few hours)

### 2.1 – Tune Class Filtering

Instrumentation is the most expensive phase. Filter out classes you don't care about:

```xml
<configuration>
    <excludePackages>
        <exclude>com.example.generated.*</exclude>
        <exclude>com.example.dto.*</exclude>
        <exclude>com.example.model.*</exclude>
    </excludePackages>
    <excludeClassNamePatterns>
        <pattern>.*Generated.*</pattern>
        <pattern>.*$\$EnhancerByCGLIB\$.*</pattern>  <!-- CGLib proxies -->
        <pattern>.*\.Companion$</pattern>             <!-- Kotlin companion objects -->
    </excludeClassNamePatterns>
</configuration>
```

A good rule of thumb: exclude any class that contains only data (getters/setters, records, value types). These are cheap to re-test and rarely isolate a failure.

### 2.2 – Tune Surefire Thread Count

test-order reorders tests within a single JVM run. If Surefire forks multiple JVMs the order is partially lost. Use `forkCount=1` with threading to keep ordering intact:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <!-- Single fork with multiple threads -->
        <forkCount>1</forkCount>
        <reuseForks>true</reuseForks>
        <parallel>classes</parallel>
        <threadCount>4</threadCount>
    </configuration>
</plugin>
```

### 2.3 – Restrict Change Detection Scope

For monorepos, restrict git diff to the affected module's source directory:

```xml
<configuration>
    <changeMode>git</changeMode>
    <!-- Only watch this module's source – don't scan unrelated modules -->
    <changeScanPaths>
        <path>src/main/java</path>
    </changeScanPaths>
    <gitBaseBranch>main</gitBaseBranch>
</configuration>
```

### 2.4 – Choose the Right Change Detection Mode

| Mode | Speed | Accuracy | Best for |
|------|-------|----------|----------|
| `git` | ★★★★★ | ★★★★☆ | CI pipelines with clean branches |
| `hash` | ★★★★☆ | ★★★★★ | Local dev (detects uncommitted edits) |
| `uncommitted` | ★★★☆☆ | ★★★☆☆ | Local dev, only staged changes |
| `explicit` | ★★★★★ | ★★★★★ | Script-driven, known changed files |
| `auto` | ★★★☆☆ | ★★★★★ | Unknown environment (fallback) |

In CI use `git`; locally use `hash`. `auto` tries git first, then hash.

---

## Level 3: Advanced Tuning (significant effort)

### 3.1 – JVM Flags for Instrumentation

When running large projects (1000+ classes), the instrumentation JVM may run out of PermGen/Metaspace or GC pauses may dominate. Increase Metaspace:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>
            -javaagent:${test-order.agent.jar}
            -XX:MaxMetaspaceSize=512m
            -XX:+UseG1GC
            -XX:G1HeapRegionSize=8m
        </argLine>
    </configuration>
</plugin>
```

### 3.2 – Multi-Module Parallel Instrumentation

When all modules share a common change-detection strategy, run learn-phase in parallel across modules:

```bash
mvn test-order:learn --threads 4
```

Or with per-module parallelism:

```bash
mvn test-order:learn -T 1C   # 1 thread per CPU core
```

Note: index writes are per-module, so there is no write contention.

### 3.3 – Index Pre-Warming in CI

Keep a warm index on the main branch and download it before running a branch build:

```yaml
# .test-order.yml
ci:
  type: github-actions
  owner: my-org
  repo: my-repo
  workflow: main-build.yml
  artifact-name: test-dependencies
  branch: main
  token-env: GITHUB_TOKEN
```

CI warm index workflow:

```yaml
# .github/workflows/main-build.yml
- name: Upload test order index
  uses: actions/upload-artifact@v4
  with:
    name: test-dependencies
    path: |
      **/target/test-dependencies.lz4
    retention-days: 7
```

### 3.4 – Index Sharding for Very Large Monorepos

For monorepos with 10,000+ test classes split across 50+ modules, the cross-module dependency index can get large. Enable per-module index sharding:

```xml
<configuration>
    <!-- Write a separate index per Maven module instead of one global file -->
    <indexSharding>module</indexSharding>
    <!-- Merge shards on demand only during ranking, not during write -->
    <lazyShardMerge>true</lazyShardMerge>
</configuration>
```

Sharded indexes trade off query speed (slightly slower cross-module lookup) for write speed and memory.

---

## Benchmarks

The following benchmarks were taken on a 2.4 GHz 8-core machine with NVMe SSD:

### Small project (50 classes, 100 tests)

| Operation | Time |
|-----------|------|
| learn (full instrumentation) | 3.8 s |
| Index write | 2 ms |
| Index read | <1 ms |
| Ranking (50 changed classes) | <1 ms |
| **Total overhead vs. bare test run** | ~4 s |

### Medium project (500 classes, 500 tests)

| Operation | Time |
|-----------|------|
| learn (full instrumentation) | 18 s |
| Index write | 12 ms |
| Index read | 3 ms |
| Ranking (100 changed classes) | 1 ms |
| **Total overhead vs. bare test run** | ~19 s |

### Large project (2000 classes, 2000 tests)

| Operation | Time |
|-----------|------|
| learn (full instrumentation) | 75 s |
| Index write | 45 ms |
| Index read | 11 ms |
| Ranking (200 changed classes) | 3 ms |
| **Total overhead vs. bare test run** | ~76 s |

**Key takeaway**: The learn phase is amortized over many test runs. Once the index exists, the overhead is 3-50 ms. The one-time learn cost is usually paid in CI on main branch, not on every developer's PR.

---

## Index Size Management

The index grows proportionally to the number of test-class → dependency-class pairs.

| Project size | Pairs | Uncompressed | LZ4 compressed |
|-------------|-------|--------------|----------------|
| 100 tests × 20 deps | 2,000 | ~80 KB | ~18 KB |
| 500 tests × 50 deps | 25,000 | ~1 MB | ~220 KB |
| 2000 tests × 100 deps | 200,000 | ~8 MB | ~1.6 MB |

### Trimming the Index

Run `test-order:prune` to remove stale entries (deleted test classes):

```bash
mvn test-order:prune
```

Or configure automatic pruning on every learn run:

```xml
<configuration>
    <pruneOnLearn>true</pruneOnLearn>
</configuration>
```

### Controlling What Gets Indexed

To reduce index size, exclude framework classes that every test depends on (and which therefore carry no useful signal):

```xml
<configuration>
    <excludeFromIndex>
        <!-- JUnit / TestNG infrastructure -->
        <exclude>org.junit.*</exclude>
        <exclude>org.testng.*</exclude>
        <!-- Spring test infrastructure -->
        <exclude>org.springframework.test.*</exclude>
        <exclude>org.springframework.boot.test.*</exclude>
        <!-- Mockito -->
        <exclude>org.mockito.*</exclude>
    </excludeFromIndex>
</configuration>
```

This can reduce index size by 40-70% in Spring Boot projects without meaningfully reducing ranking accuracy.

---

## Diagnosing Performance Issues

### Enable Timing Logs

```xml
<configuration>
    <verboseOutput>true</verboseOutput>
    <timingLog>true</timingLog>
</configuration>
```

Output:

```
[test-order] Phase: change-detection (git)       23 ms
[test-order] Phase: index-read                    4 ms
[test-order] Phase: ranking (312 tests, 47 changed) 1 ms
[test-order] Phase: order-applied                 0 ms
[test-order] Selected 89 tests out of 312
```

### Profile Instrumentation Bottlenecks

If the learn phase is slow, profile which classes take longest:

```bash
mvn test-order:learn -Dtest-order.profileInstrumentation=true
```

This writes `target/test-order-instrumentation-profile.txt`:

```
com.example.BigService        342 ms   (12,400 bytecode instructions)
com.example.ComplexFactory    218 ms   ( 9,100 bytecode instructions)
com.example.AbstractBase      155 ms   ( 7,300 bytecode instructions)
```

Use this to decide what to add to `excludePackages`.

### Check Index Freshness

If tests are being skipped unexpectedly, inspect the index:

```bash
mvn test-order:index-info
```

Output:

```
Index: target/test-dependencies.lz4
  Created:        2025-01-15T10:23:11Z
  Test classes:   312
  Total pairs:    28,441
  LZ4 compressed: 1.2 MB
  Hash baseline:  abc123def456 (matches current HEAD)
  Stale entries:  0
```

---

## Common Performance Anti-Patterns

| Anti-pattern | Symptom | Fix |
|-------------|---------|-----|
| Instrumenting generated classes | Learn phase unexpectedly slow | Add `excludePackages` for generated code |
| Index on network drive | Index read/write is slow (100+ ms) | Move index to local SSD |
| `forkCount > 1` in Surefire | Test order ignored on forked JVMs | Use `forkCount=1` with threads |
| Running learn on every PR | CI takes 60+ extra seconds per PR | Run learn only on main branch, download in PRs |
| Not pruning stale index entries | Index grows unboundedly | Enable `pruneOnLearn=true` |
| Indexing JUnit/Spring framework classes | Index 3× larger than needed | Add framework packages to `excludeFromIndex` |

---

## Recommended Configurations by Project Size

### Small Project (< 200 classes)

```xml
<configuration>
    <changeMode>hash</changeMode>
    <instrumentationMode>in-process</instrumentationMode>
</configuration>
```

### Medium Project (200-1000 classes)

```xml
<configuration>
    <changeMode>git</changeMode>
    <gitBaseBranch>main</gitBaseBranch>
    <instrumentationMode>in-process</instrumentationMode>
    <excludeFromIndex>
        <exclude>org.junit.*</exclude>
        <exclude>org.mockito.*</exclude>
    </excludeFromIndex>
</configuration>
```

### Large Project (1000+ classes)

```xml
<configuration>
    <changeMode>git</changeMode>
    <gitBaseBranch>main</gitBaseBranch>
    <instrumentationMode>in-process</instrumentationMode>
    <pruneOnLearn>true</pruneOnLearn>
    <excludePackages>
        <exclude>com.example.generated.*</exclude>
        <exclude>com.example.model.*</exclude>
    </excludePackages>
    <excludeFromIndex>
        <exclude>org.junit.*</exclude>
        <exclude>org.mockito.*</exclude>
        <exclude>org.springframework.test.*</exclude>
    </excludeFromIndex>
    <verboseOutput>true</verboseOutput>
</configuration>
```

### CI Configuration (any size)

```xml
<configuration>
    <changeMode>git</changeMode>
    <gitBaseBranch>${env.BASE_BRANCH}</gitBaseBranch>
    <!-- Download warm index from main branch CI artifact -->
    <ciConfig>.test-order.yml</ciConfig>
    <pruneOnLearn>true</pruneOnLearn>
</configuration>
```
